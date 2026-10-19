package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

internal class SubscriptionContentParser(
    private val tunStack: String = "system",
    private val blockIpv6: Boolean = true,
    private val validateNode: (JSONObject) -> Unit = {},
) {
    /** Unknown optional share-link keys for one parse (A+ tolerant mode). */
    private val shareLinkWarnings = mutableListOf<String>()
    companion object {
        internal const val MAX_ENTRIES = 10000
        private const val MAX_BYTES = 8 * 1024 * 1024
        private fun normalizeText(text: String) = text.trim().removePrefix("\uFEFF").trim()
        private fun checkEntryCount(count: Int) {
            require(count <= MAX_ENTRIES) { "Подписка содержит слишком много серверов" }
        }

        fun supports(text: String): Boolean {
            val trimmed = normalizeText(text)
            if (ForeignSubscriptionParser.looksLike(trimmed) || Regex("(?im)^(vless|vmess|trojan|ss|hysteria|hysteria2|hy2|tuic|anytls|socks|socks5)://").containsMatchIn(trimmed)) return true
            if (trimmed.length !in 20..(8 * 1024 * 1024) || !Regex("[A-Za-z0-9+/=_\\-\\s]+").matches(trimmed)) return false
            val decoded = runCatching { Base64.getDecoder().decode(trimmed.filterNot { it.isWhitespace() }).toString(Charsets.UTF_8) }.getOrElse {
                runCatching { Base64.getUrlDecoder().decode(trimmed.filterNot { it.isWhitespace() }).toString(Charsets.UTF_8) }.getOrDefault("")
            }
            val normalized = normalizeText(decoded)
            return normalized.contains("://") || normalized.startsWith("{") || normalized.startsWith("[") || ForeignSubscriptionParser.looksLike(normalized)
        }
    }

    private fun decodeBase64(value: String): ByteArray {
        val compact = value.filterNot { it.isWhitespace() }
        return try {
            Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(compact)
        }
    }

    fun parse(raw: String): SubscriptionImportResult {
        if (raw.length > MAX_BYTES || raw.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) throw SubscriptionResponseFailure(SubscriptionResponseProblem.TOO_LARGE)
        val trimmed = normalizeText(raw)
        val mode = SubscriptionRouting.detectMode(trimmed)
        val content = normalizeText(tryDecodeBase64(trimmed))
        if (content.trimStart().startsWith('<')) throw SubscriptionResponseFailure(SubscriptionResponseProblem.HTML)
        val foreign = ForeignSubscriptionParser.decode(content)
        if (foreign == null && content.startsWith("{")) {
            val root = JSONObject(content)
            require(root.has("outbounds")) { "Expected a sing-box configuration with outbounds" }
            val nodes = root.getJSONArray("outbounds")
            checkEntryCount(nodes.length())
            val count =
                (0 until nodes.length()).count {
                    nodes.optJSONObject(it)?.optString("type") !in
                        setOf("selector", "urltest", "direct", "block", "dns")
                }
            val migrationWarnings = mutableListOf<String>()
            val migrated = sanitizeAndMigrateConfig(content, mode, migrationWarnings)
            return SubscriptionImportResult(
                migrated,
                SubscriptionImportReport(
                    count,
                    count,
                    emptyList(),
                    migrationWarnings,
                    format = "sing-box JSON",
                ),
            )
        }
        shareLinkWarnings.clear()
        val warnings = foreign?.warnings.orEmpty().toMutableList()
        val nodes = mutableListOf<JSONObject>()
        val issues = mutableListOf<SubscriptionImportIssue>()
        var received = 0
        fun accept(line: Int, name: String, independent: Boolean = true, parseNode: () -> JSONObject) {
            received++
            val node =
                try {
                    QuicTlsCompatibility.normalize(parseNode())
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    val classified = classifyParserError(e)
                    issues.add(
                        SubscriptionImportIssue(line, name, classified.second, classified.first),
                    )
                    return
                }
            try {
                if (independent) validateNode(node)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                // NODE_CORE_INVALID only — harness/core unavailable does not skip (no issue).
                if (e is NodeCoreNodeInvalidException) {
                    issues.add(
                        SubscriptionImportIssue(
                            line,
                            name,
                            "Ядро отклонило параметры сервера",
                            ImportIssueCode.NODE_CORE_INVALID,
                        ),
                    )
                    return
                }
                // Unexpected validate throw: safe unknown, still skip node.
                issues.add(
                    SubscriptionImportIssue(
                        line,
                        name,
                        "Некорректные параметры или кодировка ссылки",
                        ImportIssueCode.UNKNOWN,
                    ),
                )
                return
            }
            nodes.add(node)
        }
        if (foreign != null) {
            checkEntryCount(foreign.servers.size)
            for (server in foreign.servers) accept(server.position, server.name) { server.convert() }
        } else if (content.startsWith("[")) {
            val array = JSONArray(content)
            checkEntryCount(array.length())
            val tags = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val tag = array.optJSONObject(i)?.optString("tag").orEmpty()
                require(tag.isBlank() || tags.add(tag)) { "Invalid duplicate outbound tag" }
            }
            for (i in 0 until array.length()) {
                val source = array.optJSONObject(i)
                val name = source?.optString("tag")?.takeIf { it.isNotBlank() }?.take(120)
                    ?: "Сервер ${i + 1}"
                accept(i + 1, name, independent = false) {
                    source ?: error("Expected a server object")
                }
            }
        } else {
            checkEntryCount(content.lineSequence().count { rawLine ->
                val line = rawLine.trim()
                line.isNotEmpty() && !line.startsWith('#') && !line.startsWith("//")
            })
            for ((index, rawLine) in content.lineSequence().withIndex()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith('#') || line.startsWith("//")) continue
                val name = issueNameForLink(line, index + 1)
                accept(index + 1, name) {
                    parseUriLines(line).singleOrNull() ?: error("Invalid or unsupported server link")
                }
            }
        }
        for (w in shareLinkWarnings) {
            if (w !in warnings) warnings.add(w)
        }
        shareLinkWarnings.clear()
        val report = SubscriptionImportReport(received, nodes.size, issues, warnings, foreign?.format ?: if (content.startsWith("[")) "sing-box JSON array" else "Список ссылок")
        if (nodes.isEmpty()) throw SubscriptionResponseFailure(SubscriptionResponseProblem.NO_USABLE_NODES, report)
        return SubscriptionImportResult(buildSingBoxConfig(nodes, mode, preserveTags = foreign == null && content.startsWith("[")), report)
    }

    private fun issueNameForLink(line: String, position: Int): String {
        val fallback = "Сервер $position"
        // These are labels, not connection fields; extract before converting a failing node.
        val label = when {
            '#' in line -> line.substringAfter('#')
            line.startsWith("vmess://") -> runCatching {
                JSONObject(
                    decodeBase64(line.removePrefix("vmess://").substringBefore('?'))
                        .toString(StandardCharsets.UTF_8),
                ).opt("ps") as? String
            }.getOrNull()
            else -> null
        }
        return runCatching { cleanNodeName(label?.takeIf { it.isNotBlank() } ?: fallback) }
            .getOrDefault(fallback).take(120)
    }

    /**
     * Maps a parser exception to a typed code + user-safe reason (no URLs/secrets/raw traces).
     */
    private fun classifyParserError(error: Exception): Pair<ImportIssueCode, String> {
        if (error is NodeCoreNodeInvalidException) {
            return ImportIssueCode.NODE_CORE_INVALID to "Ядро отклонило параметры сервера"
        }
        val rawMessage = error.message.orEmpty()
        val detail = rawMessage.substringAfter(": ", "")
        val detailedPrefixes =
            setOf(
                "Unsupported share-link parameter",
                "Unsupported Hysteria parameter",
                "Unsupported XHTTP xmux parameter",
                "Unsupported XHTTP parameter",
                "Unsupported server field",
            )
        val prefix = rawMessage.substringBefore(": ")
        if (prefix in detailedPrefixes && Regex("[A-Za-z][A-Za-z0-9_.-]{0,49}").matches(detail)) {
            return ImportIssueCode.UNSUPPORTED_FEATURE to
                "Неподдерживаемый параметр: $detail"
        }
        val message = prefix
        if (message.startsWith("Expected ") || message.startsWith("Подписка")) {
            return ImportIssueCode.FORMAT_ERROR to "Некорректный формат записи"
        }
        val parsePrefixes =
            listOf(
                "Missing ",
                "Invalid ",
                "Unknown ",
                "Port hopping",
                "IPv6 addresses",
                "Hysteria supports",
                "Reality requires",
                "VLESS requires",
            )
        if (
            parsePrefixes.any { message.startsWith(it) } &&
            !message.contains("://") &&
            message.length <= 160 &&
            message.all { !it.isISOControl() }
        ) {
            return ImportIssueCode.PARSE_ERROR to message
        }
        if (
            message.startsWith("Unsupported ") &&
            !message.contains("://") &&
            message.length <= 160 &&
            message.all { !it.isISOControl() }
        ) {
            return ImportIssueCode.UNSUPPORTED_FEATURE to message
        }
        return ImportIssueCode.UNKNOWN to "Некорректные параметры или кодировка ссылки"
    }

    /**
     * Import-time config sanitize. FakeIP migration follows sing-box 1.11/1.12 semantics.
     */
    private fun sanitizeAndMigrateConfig(
        jsonStr: String,
        mode: SubscriptionRouting.Mode,
        migrationWarnings: MutableList<String> = mutableListOf(),
    ): String = try {
        val root = JSONObject(jsonStr)
        val sourceRoute = root.optJSONObject("route")
        val hadFinal = sourceRoute?.has("final") == true
        val sourceFinal = sourceRoute?.opt("final")
        val ruleSets = root.optJSONObject("route")?.optJSONArray("rule_set")
        if (ruleSets != null) for (i in 0 until ruleSets.length()) {
            val ruleSet = ruleSets.optJSONObject(i) ?: continue
            if (ruleSet.optString("url") == "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-ru.srs") {
                ruleSet.put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs")
            }
        }
        val dns = root.optJSONObject("dns")
        if (dns != null) {
            dns.remove("independent_cache")

            if (dns.has("address_strategy")) {
                val strat = dns.remove("address_strategy")
                if (!dns.has("strategy")) {
                    dns.put("strategy", strat)
                }
            }

            val servers = dns.optJSONArray("servers")
            if (servers != null) {
                val cleanedServers = JSONArray()
                for (i in 0 until servers.length()) {
                    val server = servers.optJSONObject(i) ?: continue

                    server.remove("strategy")
                    server.remove("address_strategy")

                    if (server.optString("type") == "rcode") {
                        continue
                    }

                    if (server.optString("detour") == "direct") {
                        server.remove("detour")
                    }

                    if (server.has("address") && !server.has("type")) {
                        val addr = server.remove("address").toString().trim()
                        when {
                            addr == "fakeip" || addr.startsWith("fakeip://") -> Unit
                            addr == "local" || addr.startsWith("rcode://") -> server.put("type", "local")
                            else -> migrateLegacyDnsAddress(server, addr)
                        }
                    }
                    if (server.has("address_resolver")) {
                        val res = server.remove("address_resolver")
                        server.put("domain_resolver", res)
                    }
                    cleanedServers.put(server)
                }
                dns.put("servers", cleanedServers)
            }
        }

        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null) {
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(i) ?: continue
                if (inbound.optString("type") == "tun") {
                    inbound.put("stack", tunStack)
                    val addresses = JSONArray()
                    if (inbound.has("inet4_address")) {
                        val v = inbound.remove("inet4_address")
                        if (v is JSONArray) {
                            for (j in 0 until v.length()) addresses.put(v.get(j))
                        } else {
                            addresses.put(v)
                        }
                    }
                    if (inbound.has("inet6_address")) {
                        val v = inbound.remove("inet6_address")
                        if (v is JSONArray) {
                            for (j in 0 until v.length()) addresses.put(v.get(j))
                        } else {
                            addresses.put(v)
                        }
                    }
                    if (addresses.length() > 0 && !inbound.has("address")) {
                        inbound.put("address", addresses)
                    }
                    inbound.remove("sniff")
                }
            }
        }

        val outbounds = root.optJSONArray("outbounds")
        if (outbounds != null) {
            val cleanedOutbounds = JSONArray()
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                if (ob.optString("type") != "dns") {
                    cleanedOutbounds.put(QuicTlsCompatibility.normalize(ob))
                }
            }
            root.put("outbounds", cleanedOutbounds)
        }

        root.optJSONObject("dns")?.let { DnsFakeIpMigration.migrate(it, migrationWarnings) }

        val migratedOutbounds = root.optJSONArray("outbounds")
        if (migratedOutbounds != null) {
            val tags = (0 until migratedOutbounds.length()).mapNotNull { migratedOutbounds.optJSONObject(it)?.optString("tag") }.toSet()
            for (type in listOf("direct", "block")) {
                if (type !in tags) migratedOutbounds.put(JSONObject().put("type", type).put("tag", type))
            }
        }
        SubscriptionRouting.apply(root, mode, blockIpv6)
        // Full native profiles own their final outbound; generated selector tags need not exist.
        val migratedRoute = root.getJSONObject("route")
        if (hadFinal) migratedRoute.put("final", sourceFinal) else migratedRoute.remove("final")

        root.toString(2)
    } catch (e: LegacyDnsFakeIpIncompatibleException) {
        throw e
    }

    private fun migrateLegacyDnsAddress(server: JSONObject, address: String) {
        val value = if ("://" in address) address else {
            if (address.count { it == ':' } > 1 && !address.startsWith("[")) "udp://[$address]" else "udp://$address"
        }
        val uri = try { URI(value) } catch (_: Exception) { error("Invalid legacy DNS address") }
        val scheme = uri.scheme?.lowercase()
        require(scheme in setOf("https", "tls", "tcp", "udp") && uri.rawUserInfo == null && uri.rawFragment == null) {
            "Invalid legacy DNS address"
        }
        val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }
            ?: error("Invalid legacy DNS server")
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid legacy DNS port" }
        server.put("type", scheme).put("server", host)
        if (uri.port != -1) server.put("server_port", uri.port)
        if (scheme == "https") {
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/dns-query"
            server.put("path", path + (uri.rawQuery?.let { "?$it" } ?: ""))
        } else {
            require((uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && uri.rawQuery == null) { "Invalid legacy DNS path" }
        }
    }

    private fun tryDecodeBase64(text: String): String {
        val trimmed = text.trim()
        if (trimmed.contains("://") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }

        val nonCommentLines =
            text
                .lines()
                .filter {
                    val t = it.trim()
                    t.isNotEmpty() && !t.startsWith("//") && !t.startsWith("#")
                }
                .joinToString("")

        try {
            val decoded = decodeBase64(nonCommentLines)
            val decodedStr = normalizeText(String(decoded, StandardCharsets.UTF_8))
            if (
                decodedStr.contains("://") ||
                decodedStr.startsWith("{") ||
                decodedStr.startsWith("[") || ForeignSubscriptionParser.looksLike(decodedStr)
            ) {
                return decodedStr
            }
        } catch (_: IllegalArgumentException) {}
        return trimmed
    }

    private fun cleanNodeName(rawTag: String?): String {
        if (rawTag.isNullOrBlank()) return "Proxy"
        val trimmed = rawTag.trim()
        if (!trimmed.contains("%")) return trimmed
        return try {
            ProxyLinkParser.decode(trimmed)
        } catch (e: Exception) {
            trimmed
        }
    }

    private fun applyMultiplex(
        outbound: JSONObject,
        params: Map<String, String>? = null,
        source: JSONObject? = null,
    ) {
        if (outbound.has("multiplex")) return

        source?.optJSONObject("multiplex")?.let {
            outbound.put("multiplex", JSONObject(it.toString()))
            return
        }

        val p = mutableMapOf<String, String>()
        params?.forEach { (k, v) -> p[k.lowercase()] = v }
        if (source != null) {
            val keys = source.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val kl = k.lowercase()
                if (
                    kl in
                    setOf(
                        "mux",
                        "muxprotocol",
                        "muxconcurrency",
                        "max_streams",
                        "maxstreams",
                        "max_connections",
                        "maxconnections",
                        "min_streams",
                        "minstreams",
                        "muxpadding",
                        "padding",
                    )
                ) {
                    val v = source.opt(k)?.toString() ?: continue
                    if (v.isNotBlank()) p.putIfAbsent(kl, v)
                }
            }
        }

        val muxRaw = p["mux"] ?: return
        val enabled =
            when (muxRaw.trim().lowercase()) {
                "1",
                "true",
                "yes",
                "on",
                -> true
                "0",
                "false",
                "no",
                "off",
                -> false
                else -> muxRaw.toIntOrNull()?.let { it > 0 } ?: false
            }
        if (!enabled) return

        val protocol =
            (p["muxprotocol"] ?: "smux").lowercase().let {
                when (it) {
                    "smux",
                    "yamux",
                    "h2mux",
                    -> it
                    else -> "smux"
                }
            }

        val multiplex = JSONObject()
        multiplex.put("enabled", true)
        multiplex.put("protocol", protocol)

        fun intParam(vararg names: String): Int? {
            for (n in names) {
                val v = p[n] ?: continue
                v.toIntOrNull()?.let {
                    return it
                }
            }
            return null
        }

        val maxStreams = intParam("muxconcurrency", "max_streams", "maxstreams")
        val maxConn = intParam("max_connections", "maxconnections")
        val minStreams = intParam("min_streams", "minstreams")

        when {
            maxStreams != null && maxStreams > 0 -> multiplex.put("max_streams", maxStreams)
            else -> {
                multiplex.put("max_connections", maxConn ?: 4)
                multiplex.put("min_streams", minStreams ?: 4)
            }
        }

        val paddingRaw = p["muxpadding"] ?: p["padding"]
        if (paddingRaw != null) {
            val pad = paddingRaw.trim().lowercase() in setOf("1", "true", "yes", "on")
            multiplex.put("padding", pad)
        }

        outbound.put("multiplex", multiplex)
    }

    private fun checkParameters(params: Map<String, String>, allowed: Set<String>) {
        val unsupported = params.keys.firstOrNull { it !in allowed }
        require(unsupported == null) { "Unsupported share-link parameter: $unsupported" }
        params["allowInsecure"]?.let {
            require(it.lowercase() in setOf("0", "1", "false", "true")) {
                "Invalid TLS verification flag"
            }
        }
    }

    private fun tolerateParameters(
        params: Map<String, String>,
        allowed: Set<String>,
        warnings: MutableList<String>? = null,
    ): Map<String, String> {
        val (known, unknown) = ProxyLinkParser.partitionParameters(params, allowed)
        XrayCompatibility.unknownOptionalNote(unknown)?.let { note ->
            if (warnings != null && note !in warnings) warnings.add(note)
        }
        return known
    }

    private val transportParameters =
        setOf(
            "type",
            "security",
            "sni",
            "host",
            "fp",
            "alpn",
            "allowInsecure",
            "pcs",
            "vcn",
            "path",
            "serviceName",
            "service_name",
            "mode",
            "extra",
            "x_padding_bytes",
            "mux",
            "muxProtocol",
            "muxprotocol",
            "muxConcurrency",
            "muxconcurrency",
            "max_streams",
            "maxStreams",
            "max_connections",
            "maxConnections",
            "min_streams",
            "minStreams",
            "muxPadding",
            "padding",
        )

    internal fun parseUriLines(text: String): List<JSONObject> {
        val nodes = mutableListOf<JSONObject>()
        for (line in text.lines()) {
            val input = line.trim()
            val trimmed =
                if (input.contains("://")) {
                    input.substringBefore("://").lowercase() + "://" + input.substringAfter("://")
                } else {
                    input
                }
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue
            val node =
                when {
                    trimmed.startsWith("vless://") -> parseVless(trimmed)
                    trimmed.startsWith("vmess://") -> parseVmess(trimmed)
                    trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
                    trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
                    trimmed.startsWith("tuic://") || trimmed.startsWith("anytls://") || trimmed.startsWith("socks://") || trimmed.startsWith("socks5://") || trimmed.startsWith("http://") || trimmed.startsWith("https://") -> ProxyLinkParser.additional(trimmed)
                    trimmed.startsWith("hysteria://") ||
                        trimmed.startsWith("hysteria2://") ||
                        trimmed.startsWith("hy2://") -> ProxyLinkParser.hysteria(trimmed) { note ->
                        if (note !in shareLinkWarnings) shareLinkWarnings.add(note)
                    }
                    else -> null
                }
            if (node != null) nodes.add(node)
        }
        return nodes
    }

    private fun parseVless(line: String): JSONObject? {
        val withoutScheme = line.removePrefix("vless://")
        val hashIdx = withoutScheme.lastIndexOf("#")
        val rawTag = if (hashIdx >= 0) withoutScheme.substring(hashIdx + 1) else null
        val main = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val tag = cleanNodeName(rawTag)

        val authority = main.substringBefore('?')
        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return null
        val uuid = ProxyLinkParser.decode(main.substring(0, atIdx))
        val rest = authority.substring(atIdx + 1)
        val qIdx = rest.indexOf("?")
        val hostPort = if (qIdx >= 0) rest.substring(0, qIdx) else rest
        val query = main.substringAfter('?', "")
        val rawParams = ProxyLinkParser.connectionQuery(query)
        val params =
            tolerateParameters(rawParams, XrayCompatibility.vlessShareKnown, shareLinkWarnings)

        val endpoint = ProxyLinkParser.endpoint(hostPort)
        require(endpoint.ports.size == 1 && ":" !in endpoint.ports.first()) {
            "Port hopping requires Hysteria"
        }
        val host = endpoint.host
        val port = endpoint.port
        if (host.isEmpty() || uuid.isEmpty()) return null

        val outbound = JSONObject()
        outbound.put("type", "vless")
        outbound.put("tag", tag)
        outbound.put("server", host)
        outbound.put("server_port", port)
        outbound.put("uuid", uuid)
        params["packetEncoding"]?.let { outbound.put("packet_encoding", it) }
        val flow = params["flow"]
        if (!flow.isNullOrBlank()) outbound.put("flow", flow)

        val network = params["type"] ?: "tcp"
        val security = (params["security"] ?: "none").lowercase()
        require(security in setOf("none", "tls", "reality")) { "Unsupported VLESS security" }
        ProxyLinkParser.applyVlessEncryption(outbound, params["encryption"], flow)
        require(security == "reality" || listOf("pbk", "sid", "support-x25519mlkem768").none { !params[it].isNullOrBlank() }) { "Invalid Reality options without Reality security" }
        require(security != "none" || listOf("sni", "fp", "alpn", "allowInsecure", "pcs", "vcn").none { !params[it].isNullOrBlank() }) { "Invalid TLS options without TLS security" }
        if (security == "reality") {
            val key = params["pbk"].orEmpty()
            require(
                key.isNotBlank() &&
                    runCatching { Base64.getUrlDecoder().decode(key).size == 32 }
                        .getOrDefault(false),
            ) {
                "Reality requires a valid public key (pbk)"
            }
            val sid = params["sid"].orEmpty()
            require(
                sid.length <= 16 &&
                    sid.length % 2 == 0 &&
                    sid.all { it in "0123456789abcdefABCDEF" },
            ) {
                "Invalid Reality short ID"
            }
        }
        val sni = params["sni"] ?: params["host"] ?: host
        val fp = params["fp"] ?: "chrome"
        val alpn = params["alpn"]

        if (security == "reality") {
            val tls = JSONObject()
            tls.put("enabled", true)
            tls.put("server_name", sni)
            if (!alpn.isNullOrBlank()) {
                tls.put("alpn", JSONArray(alpn.split(",")))
            }
            ProxyLinkParser.applyReality(
                tls,
                params["pbk"].orEmpty(),
                params["sid"].orEmpty(),
                spiderX = params["spiderX"] ?: params["spx"],
                fingerprint = params["fp"] ?: fp,
                supportX25519MLKEM768 = params["support-x25519mlkem768"]?.let(ProxyLinkParser::boolean),
            )
            outbound.put("tls", tls)
        } else if (security == "tls") {
            val tls = JSONObject()
            tls.put("enabled", true)
            tls.put("server_name", sni)
            if (!alpn.isNullOrBlank()) {
                tls.put("alpn", JSONArray(alpn.split(",")))
            }
            outbound.put("tls", tls)
        }

        require(security != "reality" || listOf("pcs", "vcn").none { !params[it].isNullOrBlank() }) { "Invalid certificate verification options with Reality" }
        outbound.optJSONObject("tls")?.let { tls ->
            ProxyLinkParser.applyXrayVerification(tls, params["pcs"], params["vcn"])
            if (!alpn.isNullOrBlank()) tls.put("alpn", JSONArray(alpn.split(',')))
            params["allowInsecure"]?.let {
                tls.put("insecure", ProxyLinkParser.boolean(it))
            }
            if (security == "tls" && !params["fp"].isNullOrBlank()) {
                tls.put(
                    "utls",
                    JSONObject().put("enabled", true).put("fingerprint", params.getValue("fp")),
                )
            }
        }

        ProxyLinkParser.transport(network, params, sni)?.let { transport ->
            val spider = (params["spiderX"] ?: params["spx"]).orEmpty().trim()
            if (spider.isNotEmpty() && spider != "/" && !transport.has("path")) {
                transport.put("path", if (spider.startsWith("/")) spider else "/$spider")
            }
            outbound.put("transport", transport)
        }

        applyMultiplex(outbound, params)
        params["fm"]?.let {
            ProxyLinkParser.applyFinalMask(outbound, JSONObject(it))
        }
        return outbound
    }

    private fun parseVmess(line: String): JSONObject? {
        val b64 = line.removePrefix("vmess://").trim()
        val decoded =
            try {
                decodeBase64(b64).toString(StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val vmessJson =
            try {
                JSONObject(decoded)
            } catch (e: Exception) {
                return null
            }

        val supported =
            transportParameters +
                setOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "tls", "multiplex")
        require(vmessJson.keys().asSequence().all { it in supported }) {
            "Unsupported VMess share-link parameter"
        }
        val headerType = vmessJson.optString("type")
        require(vmessJson.optString("net", "tcp") != "tcp" || headerType in setOf("", "none")) {
            "Unsupported VMess TCP header"
        }
        val tag = cleanNodeName(vmessJson.optString("ps"))
        val host = vmessJson.optString("add")
        val port =
            if (vmessJson.has("port")) {
                vmessJson.optString("port").toIntOrNull() ?: error("Invalid VMess server port")
            } else {
                443
            }
        val uuid = vmessJson.optString("id")
        if (host.isEmpty() || uuid.isEmpty()) return null

        require(port in 1..65535) { "Invalid VMess server port" }
        val outbound = JSONObject()
        outbound.put("type", "vmess")
        outbound.put("tag", tag)
        outbound.put("server", host)
        outbound.put("server_port", port)
        outbound.put("uuid", uuid)
        outbound.put("security", vmessJson.optString("scy", "auto"))
        val aid = if (vmessJson.has("aid")) vmessJson.optString("aid").toIntOrNull()?.takeIf { it >= 0 } ?: error("Invalid VMess alter ID") else 0
        if (aid > 0) outbound.put("alter_id", aid)

        val network = vmessJson.optString("net", "tcp")
        require(!vmessJson.has("security")) { "Unsupported VMess security alias; use scy and tls" }
        val tlsFlag = vmessJson.optString("tls").lowercase()
        require(tlsFlag in setOf("", "none", "tls")) { "Unsupported VMess TLS mode" }
        val sni =
            vmessJson.optString("sni").ifEmpty { vmessJson.optString("host").ifEmpty { host } }

        if (tlsFlag.equals("tls", true)) {
            outbound.put(
                "tls",
                JSONObject().apply {
                    put("enabled", true)
                    put("server_name", sni)
                    vmessJson
                        .optString("alpn")
                        .takeIf { it.isNotBlank() }
                        ?.let { put("alpn", JSONArray(it.split(','))) }
                    if (vmessJson.has("allowInsecure")) {
                        put("insecure", ProxyLinkParser.boolean(vmessJson.optString("allowInsecure")))
                    }
                    vmessJson
                        .optString("fp")
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
                        }
                },
            )
        }

        require(tlsFlag == "tls" || listOf("pcs", "vcn").none { vmessJson.optString(it).isNotBlank() }) { "Invalid certificate verification options without TLS" }
        outbound.optJSONObject("tls")?.let {
            ProxyLinkParser.applyXrayVerification(it, vmessJson.optString("pcs"), vmessJson.optString("vcn"))
        }
        val transportParams =
            vmessJson.keys().asSequence().associateWith { vmessJson.optString(it) }.toMutableMap()
        if (network == "grpc") transportParams["serviceName"] = vmessJson.optString("path")
        ProxyLinkParser.transport(network, transportParams, sni)?.let {
            outbound.put("transport", it)
        }

        applyMultiplex(outbound, source = vmessJson)
        return outbound
    }

    private fun parseTrojan(line: String): JSONObject? {
        val withoutScheme = line.removePrefix("trojan://")
        val hashIdx = withoutScheme.lastIndexOf("#")
        val rawTag = if (hashIdx >= 0) withoutScheme.substring(hashIdx + 1) else null
        val main = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val tag = cleanNodeName(rawTag)

        val authority = main.substringBefore('?')
        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return null
        val password = ProxyLinkParser.decode(main.substring(0, atIdx))
        val rest = authority.substring(atIdx + 1)
        val qIdx = rest.indexOf("?")
        val hostPort = if (qIdx >= 0) rest.substring(0, qIdx) else rest
        val query = main.substringAfter('?', "")
        val params =
            tolerateParameters(
                ProxyLinkParser.connectionQuery(query),
                XrayCompatibility.vlessShareKnown,
                shareLinkWarnings,
            )
        require(params["security"].isNullOrEmpty() || params["security"] in setOf("tls", "reality")) {
            "Unsupported Trojan security"
        }

        val endpoint = ProxyLinkParser.endpoint(hostPort)
        require(endpoint.ports.size == 1 && ":" !in endpoint.ports.first()) {
            "Port hopping requires Hysteria"
        }
        val host = endpoint.host
        val port = endpoint.port
        if (host.isEmpty() || password.isEmpty()) return null

        val outbound = JSONObject()
        outbound.put("type", "trojan")
        outbound.put("tag", tag)
        outbound.put("server", host)
        outbound.put("server_port", port)
        outbound.put("password", password)

        val sni = params["sni"] ?: params["host"] ?: host
        outbound.put(
            "tls",
            JSONObject().apply {
                put("enabled", true)
                put("server_name", sni)
                params["alpn"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("alpn", JSONArray(it.split(','))) }
                params["allowInsecure"]?.let {
                    put("insecure", ProxyLinkParser.boolean(it))
                }
                params["fp"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
            },
        )

        require(params["security"] == "reality" || listOf("pbk", "sid", "support-x25519mlkem768").none { !params[it].isNullOrBlank() }) { "Invalid Reality options without Reality security" }
        require(params["security"] != "reality" || listOf("pcs", "vcn").none { !params[it].isNullOrBlank() }) { "Invalid certificate verification options with Reality" }
        ProxyLinkParser.applyXrayVerification(outbound.getJSONObject("tls"), params["pcs"], params["vcn"])
        if (params["security"] == "reality") {
            ProxyLinkParser.applyReality(
                outbound.getJSONObject("tls"),
                params["pbk"].orEmpty(),
                params["sid"].orEmpty(),
                spiderX = params["spiderX"] ?: params["spx"],
                fingerprint = params["fp"],
                supportX25519MLKEM768 = params["support-x25519mlkem768"]?.let(ProxyLinkParser::boolean),
            )
        }
        val network = params["type"] ?: "tcp"
        ProxyLinkParser.transport(network, params, sni)?.let { transport ->
            val spider = (params["spiderX"] ?: params["spx"]).orEmpty().trim()
            if (spider.isNotEmpty() && spider != "/" && !transport.has("path")) {
                val path = if (spider.startsWith("/")) spider else "/$spider"
                transport.put("path", path)
            }
            outbound.put("transport", transport)
        }

        applyMultiplex(outbound, params)
        params["fm"]?.let { ProxyLinkParser.applyFinalMask(outbound, JSONObject(it)) }
        return outbound
    }

    private fun parseShadowsocks(line: String): JSONObject = ProxyLinkParser.shadowsocks(line).also { applyMultiplex(it) }

    private fun buildSingBoxConfig(
        nodes: List<JSONObject>,
        mode: SubscriptionRouting.Mode,
        preserveTags: Boolean = false,
    ): String {
        val validNodes = nodes.filter { it.optString("type") != "dns" }

        val usedTags = mutableMapOf<String, Int>()
        usedTags[SubscriptionRouting.NORMAL_SELECTOR_TAG] = 1
        usedTags[SubscriptionRouting.WHITELIST_SELECTOR_TAG] = 1
        usedTags["direct"] = 1
        usedTags["block"] = 1
        val allocatedTags = usedTags.keys.toMutableSet()
        if (preserveTags) {
            validNodes.map { it.optString("tag") }.filter { it.isNotBlank() }.forEach(allocatedTags::add)
        }

        val normalProxyTags = mutableListOf<String>()
        val whitelistProxyTags = mutableListOf<String>()

        for (node in validNodes) {
            if (!node.has("multiplex")) {
                applyMultiplex(node, source = node)
            }
            val rawTag =
                node.optString("tag").ifEmpty { node.optString("server").ifEmpty { "Proxy" } }
            val existingTag = node.optString("tag")
            val keepTag = preserveTags && existingTag.isNotBlank()
            val cleanTag = if (keepTag) existingTag else cleanNodeName(rawTag)
            var count = usedTags.getOrDefault(cleanTag, 0)
            var uniqueTag = if (keepTag) existingTag else if (count > 0) "$cleanTag ($count)" else cleanTag
            // A literal "A (1)" can collide with the suffix generated for a second "A".
            while (!keepTag && uniqueTag in allocatedTags) {
                count++
                uniqueTag = "$cleanTag ($count)"
            }
            usedTags[cleanTag] = count + 1
            allocatedTags.add(uniqueTag)
            node.put("tag", uniqueTag)

            val type = node.optString("type")
            if (type !in listOf("selector", "urltest", "direct", "block", "dns")) {
                if (SubscriptionRouting.isWhitelistBypassTag(uniqueTag)) {
                    whitelistProxyTags.add(uniqueTag)
                } else {
                    normalProxyTags.add(uniqueTag)
                }
            }
        }

        if (normalProxyTags.isEmpty() && whitelistProxyTags.isEmpty()) {
            for (node in validNodes) {
                normalProxyTags.add(node.getString("tag"))
            }
        }

        val root = JSONObject()

        root.put(
            "log",
            JSONObject().apply {
                put("level", "warn")
                put("timestamp", true)
            },
        )

        val dnsObj = JSONObject()
        val dnsServers =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("tag", "dns-remote")
                        put("type", "https")
                        put("server", "1.1.1.1")
                        put("path", "/dns-query")
                        put("domain_resolver", "dns-direct")
                        put("detour", SubscriptionRouting.NORMAL_SELECTOR_TAG)
                    },
                )
                put(
                    JSONObject().apply {
                        put("tag", "dns-direct")
                        put("type", "udp")
                        put("server", "77.88.8.8")
                        put("server_port", 53)
                    },
                )
            }
        dnsObj.put("servers", dnsServers)
        // DNS rules: only from UserRoutingConfig / profile — no hard-coded RU rules.
        dnsObj.put("rules", JSONArray())
        dnsObj.put("final", "dns-remote")
        dnsObj.put("strategy", "ipv4_only")
        root.put("dns", dnsObj)

        root.put(
            "inbounds",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put("interface_name", "tun0")
                        put("address", JSONArray().apply { put("172.19.0.1/30") })
                        put("auto_route", true)
                        put("strict_route", false)
                        put("stack", tunStack)
                    },
                )
            },
        )

        val outboundsArr = JSONArray()

        val hasNormalSelector =
            validNodes.any { it.optString("tag") == SubscriptionRouting.NORMAL_SELECTOR_TAG }
        if (!hasNormalSelector) {
            val tagsForNormal = normalProxyTags.ifEmpty { whitelistProxyTags }
            outboundsArr.put(
                JSONObject().apply {
                    put("type", "selector")
                    put("tag", SubscriptionRouting.NORMAL_SELECTOR_TAG)
                    val outs = JSONArray()
                    tagsForNormal.forEach { outs.put(it) }
                    outs.put("direct")
                    put("outbounds", outs)
                    if (tagsForNormal.isNotEmpty()) put("default", tagsForNormal[0])
                },
            )
        }

        val needWhitelistSelector =
            mode == SubscriptionRouting.Mode.WHITELIST_BYPASS || whitelistProxyTags.isNotEmpty()

        val hasWhitelistSelector =
            validNodes.any { it.optString("tag") == SubscriptionRouting.WHITELIST_SELECTOR_TAG }
        if (needWhitelistSelector && !hasWhitelistSelector) {
            val tagsForWl = whitelistProxyTags.ifEmpty { normalProxyTags }
            outboundsArr.put(
                JSONObject().apply {
                    put("type", "selector")
                    put("tag", SubscriptionRouting.WHITELIST_SELECTOR_TAG)
                    val outs = JSONArray()
                    tagsForWl.forEach { outs.put(it) }
                    outs.put("direct")
                    put("outbounds", outs)
                    if (tagsForWl.isNotEmpty()) put("default", tagsForWl[0])
                },
            )
        }

        for (node in validNodes) {
            outboundsArr.put(node)
        }

        if (validNodes.none { it.optString("tag") == "direct" }) {
            outboundsArr.put(
                JSONObject().apply {
                    put("type", "direct")
                    put("tag", "direct")
                },
            )
        }
        if (validNodes.none { it.optString("tag") == "block" }) {
            outboundsArr.put(
                JSONObject().apply {
                    put("type", "block")
                    put("tag", "block")
                },
            )
        }

        root.put("outbounds", outboundsArr)

        SubscriptionRouting.apply(root, mode, blockIpv6)
        return root.toString(2)
    }
}

internal class LegacyDnsFakeIpIncompatibleException(message: String) : IllegalArgumentException(message)
