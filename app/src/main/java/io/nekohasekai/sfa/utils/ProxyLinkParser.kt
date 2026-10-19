package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

internal object ProxyLinkParser {
    private val schemes =
        setOf(
            "vless",
            "vmess",
            "trojan",
            "ss",
            "hysteria",
            "hysteria2",
            "hy2",
            "tuic",
            "anytls",
            "socks",
            "socks5",
        )

    fun applyVlessEncryption(node: JSONObject, value: String?, flow: String?) {
        if (value.isNullOrEmpty() || value == "none") return
        val parts = value.split('.')
        require(
            parts.size == 4 &&
                parts[0] == "mlkem768x25519plus" &&
                parts[1] == "native" &&
                parts[2] in setOf("0rtt", "1rtt"),
        ) {
            "Unsupported VLESS encryption profile"
        }
        require(flow.isNullOrEmpty() || flow == "xtls-rprx-vision") {
            "Unsupported VLESS flow"
        }
        require(Regex("[A-Za-z0-9_-]+").matches(parts[3])) { "Invalid VLESS encryption key" }
        val key =
            try {
                Base64.getUrlDecoder().decode(parts[3])
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid VLESS encryption key")
            }
        require(
            key.size in setOf(32, 1184) &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(key) == parts[3],
        ) {
            "Invalid VLESS encryption key"
        }
        node.put("encryption", value)
    }

    fun isShareLink(text: String): Boolean = text.trim().substringBefore("://").lowercase() in schemes

    // URI components are not form data: a literal '+' in a password must survive decoding.
    fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    fun query(value: String?): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in value.orEmpty().split('&').filter { it.isNotEmpty() }) {
            require(pair.indexOf('=') > 0) { "Invalid share-link query parameter" }
            val key = decode(pair.substringBefore('='))
            require(key !in result) { "Invalid duplicate share-link parameter" }
            result[key] = decode(pair.substringAfter('='))
        }
        return result
    }

    fun connectionQuery(value: String?): Map<String, String> {
        val source = query(value).toMutableMap()
        for (
        (alias, canonical) in
        mapOf(
            "peer" to "sni",
            "serverName" to "sni",
            "insecure" to "allowInsecure",
            "skip-cert-verify" to "allowInsecure",
            "fingerprint" to "fp",
            "publicKey" to "pbk",
            "shortId" to "sid",
            "network" to "type",
            "service_name" to "serviceName",
        )
        ) {
            source.remove(alias)?.let { converted ->
                require(!source.containsKey(canonical) || source[canonical] == converted) {
                    "Invalid conflicting share-link aliases"
                }
                source[canonical] = converted
            }
        }
        // Normalize Reality spider path aliases used by Xray share links.
        val spider =
            source.remove("spx")?.takeIf { it.isNotBlank() }
                ?: source.remove("spiderX")?.takeIf { it.isNotBlank() }
        if (spider != null) {
            source["spiderX"] = spider
        }
        return source
    }

    /**
     * Split share-link params into known (allowed) and optional unknown keys.
     * Unknown keys no longer abort import; callers record PARTIAL compatibility.
     */
    fun partitionParameters(
        params: Map<String, String>,
        allowed: Set<String>,
    ): Pair<Map<String, String>, List<String>> {
        val known = linkedMapOf<String, String>()
        val unknown = mutableListOf<String>()
        for ((key, value) in params) {
            if (key in allowed) known[key] = value else unknown.add(key)
        }
        params["allowInsecure"]?.let {
            require(it.lowercase() in setOf("0", "1", "false", "true")) {
                "Invalid TLS verification flag"
            }
        }
        return known to unknown
    }

    data class Endpoint(val host: String, val ports: List<String>) {
        val port: Int
            get() = ports.first().substringBefore(':').toInt()
    }

    fun endpoint(authority: String, defaultPort: Int = 443): Endpoint {
        val value = authority.trimEnd('/')
        val host: String
        val portText: String
        if (value.startsWith('[')) {
            val end = value.indexOf(']')
            require(end > 1) { "Invalid IPv6 server address" }
            host = value.substring(1, end)
            val suffix = value.substring(end + 1)
            require(suffix.isEmpty() || suffix.startsWith(':')) { "Invalid server port" }
            portText = suffix.removePrefix(":").ifEmpty { defaultPort.toString() }
        } else {
            require(value.count { it == ':' } <= 1) {
                "IPv6 addresses must be enclosed in brackets"
            }
            host = value.substringBefore(':')
            portText = value.substringAfter(':', defaultPort.toString())
        }
        require(host.isNotBlank()) { "Missing server address" }
        require(host.none { it.isWhitespace() || it in "/?@#" }) { "Invalid server address" }
        val ports =
            portText.split(',').map { range ->
                val ends = range.split('-', ':')
                require(ends.size in 1..2) { "Invalid server port range" }
                val numbers = ends.map { it.toIntOrNull() ?: error("Invalid server port") }
                require(numbers.all { it in 1..65535 } && numbers.first() <= numbers.last()) {
                    "Invalid server port range"
                }
                numbers.joinToString(":")
            }
        return Endpoint(host, ports)
    }

    fun certificatePin(value: String): String {
        val hex = value.replace(":", "").trim()
        val bytes =
            if (hex.length == 64 && hex.all { it in "0123456789abcdefABCDEF" }) {
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                try {
                    Base64.getDecoder().decode(value)
                } catch (_: IllegalArgumentException) {
                    error("Invalid certificate SHA-256 fingerprint")
                }
            }
        require(bytes.size == 32) { "Invalid certificate SHA-256 fingerprint" }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun applyXrayVerification(tls: JSONObject, pin: String?, names: String?) {
        pin?.takeIf { it.isNotBlank() }
            ?.let {
                val pins = it.split(',').map { fingerprint -> certificatePin(fingerprint.trim()) }
                tls.put("xray_certificate_sha256", JSONArray(pins))
            }
        names
            ?.takeIf { it.isNotBlank() }
            ?.let {
                val list = it.split(',').map { name -> name.trim() }
                require(
                    list.all { name ->
                        name.isNotBlank() && name.none { ch -> ch.isWhitespace() || ch in "/?#@" }
                    },
                ) {
                    "Invalid certificate verification name"
                }
                tls.put("verify_server_names", JSONArray(list))
            }
    }

    fun applyFinalMask(node: JSONObject, mask: JSONObject) = FinalMaskCompatibility.apply(node, mask)

    fun applyReality(
        tls: JSONObject,
        publicKey: String,
        shortId: String,
        spiderX: String? = null,
        fingerprint: String? = null,
        supportX25519MLKEM768: Boolean? = null,
    ) {
        require(
            publicKey.isNotBlank() &&
                runCatching { Base64.getUrlDecoder().decode(publicKey).size == 32 }
                    .getOrDefault(false),
        ) {
            "Reality requires a valid public key"
        }
        require(
            shortId.length <= 16 &&
                shortId.length % 2 == 0 &&
                shortId.all { it in "0123456789abcdefABCDEF" },
        ) {
            "Invalid Reality short ID"
        }
        val fp = fingerprint?.takeIf { it.isNotBlank() } ?: "chrome"
        // Reality in sing-box requires uTLS; always apply fingerprint from the share link.
        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        val reality =
            JSONObject()
                .put("enabled", true)
                .put("public_key", publicKey)
                .put("short_id", shortId)
        supportX25519MLKEM768?.let { reality.put("support_x25519mlkem768", it) }
        // Persist spider path for core consumers / future Reality spider support.
        // Empty and "/" are the Xray defaults (same behaviour).
        val spider = spiderX?.trim().orEmpty().ifEmpty { "/" }
        reality.put("spider_x", if (spider.startsWith("/")) spider else "/$spider")
        tls.put("reality", reality)
    }

    fun additional(line: String): JSONObject {
        val scheme = line.substringBefore("://").lowercase()
        val main = line.substringAfter("://").substringBefore('#')
        val authority = main.substringBefore('?').trimEnd('/')
        val params = query(main.substringAfter('?', ""))
        val tlsParameters = setOf("sni", "peer", "insecure", "allowInsecure", "alpn", "disable_sni")
        val allowed =
            when (scheme) {
                "tuic" ->
                    tlsParameters +
                        setOf(
                            "congestion_control",
                            "congestion-control",
                            "udp_relay_mode",
                            "udp-relay-mode",
                            "zero_rtt_handshake",
                            "reduce-rtt",
                            "heartbeat",
                        )
                "anytls" ->
                    tlsParameters +
                        setOf(
                            "fp",
                            "idle_session_check_interval",
                            "idle_session_timeout",
                            "min_idle_session",
                        )
                "https" -> tlsParameters + "fp"
                else -> emptySet()
            }
        val unsupported = params.keys.firstOrNull { it !in allowed }
        require(unsupported == null) { "Unsupported share-link parameter: $unsupported" }
        for (
        (alias, canonical) in
        mapOf(
            "peer" to "sni",
            "insecure" to "allowInsecure",
            "congestion-control" to "congestion_control",
            "udp-relay-mode" to "udp_relay_mode",
            "reduce-rtt" to "zero_rtt_handshake",
        )
        ) {
            require(
                params[alias] == null ||
                    params[canonical] == null ||
                    params[alias] == params[canonical],
            ) {
                "Invalid conflicting share-link aliases"
            }
        }
        val userInfo = if ('@' in authority) authority.substringBeforeLast('@') else ""
        val server =
            endpoint(
                authority.substringAfterLast('@'),
                when (scheme) {
                    "http" -> 80
                    "socks",
                    "socks5",
                    -> 1080
                    else -> 443
                },
            )
        require(server.ports.size == 1 && ':' !in server.ports.first()) { "Invalid server port" }
        val type =
            when (scheme) {
                "socks",
                "socks5",
                -> "socks"
                "https" -> "http"
                else -> scheme
            }
        val node =
            JSONObject()
                .put("type", type)
                .put("tag", decode(line.substringAfter('#', "Proxy")))
                .put("server", server.host)
                .put("server_port", server.port)
        when (type) {
            "tuic" -> {
                require(':' in userInfo) { "Missing TUIC UUID and password" }
                node
                    .put("uuid", decode(userInfo.substringBefore(':')))
                    .put("password", decode(userInfo.substringAfter(':')))
                for (
                (from, to) in
                mapOf(
                    "congestion_control" to "congestion_control",
                    "congestion-control" to "congestion_control",
                    "udp_relay_mode" to "udp_relay_mode",
                    "udp-relay-mode" to "udp_relay_mode",
                    "heartbeat" to "heartbeat",
                )
                ) {
                    params[from]?.let { node.put(to, it) }
                }
                (params["zero_rtt_handshake"] ?: params["reduce-rtt"])?.let {
                    node.put("zero_rtt_handshake", boolean(it))
                }
            }
            "anytls" -> {
                require(userInfo.isNotEmpty()) { "Missing AnyTLS password" }
                node.put("password", decode(userInfo))
                for (key in listOf("idle_session_check_interval", "idle_session_timeout")) {
                    params[
                        key,
                    ]
                        ?.let { node.put(key, it) }
                }
                params["min_idle_session"]?.let {
                    node.put(
                        "min_idle_session",
                        it.toIntOrNull() ?: error("Invalid AnyTLS session limit"),
                    )
                }
            }
            "http",
            "socks",
            -> {
                if (userInfo.isNotEmpty()) {
                    require(':' in userInfo) { "Missing proxy username and password" }
                    node
                        .put("username", decode(userInfo.substringBefore(':')))
                        .put("password", decode(userInfo.substringAfter(':')))
                }
                if (type == "socks") node.put("version", "5")
                require(type != "socks" || params.isEmpty()) {
                    "Unsupported SOCKS share-link parameter"
                }
            }
            else -> error("Unsupported proxy protocol")
        }
        if (type in setOf("tuic", "anytls") || scheme == "https") {
            val tls =
                JSONObject()
                    .put("enabled", true)
                    .put("server_name", params["sni"] ?: params["peer"] ?: server.host)
            (params["insecure"] ?: params["allowInsecure"])?.let {
                tls.put("insecure", boolean(it))
            }
            params["alpn"]?.let { tls.put("alpn", JSONArray(it.split(','))) }
            params["fp"]?.let {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
            }
            params["disable_sni"]?.let { tls.put("disable_sni", boolean(it)) }
            node.put("tls", tls)
        } else {
            require(params.isEmpty()) { "Unsupported TLS options for plain HTTP proxy" }
        }
        return node
    }

    fun shadowsocks(line: String): JSONObject {
        val body = line.substringAfter("://").substringBefore('#')
        val main = body.substringBefore('?').trimEnd('/')
        val params = query(body.substringAfter('?', ""))
        require(params.keys.all { it == "plugin" }) { "Unsupported Shadowsocks parameter" }
        fun base64(value: String): String {
            val bytes =
                try {
                    Base64.getDecoder().decode(value)
                } catch (_: IllegalArgumentException) {
                    Base64.getUrlDecoder().decode(value)
                }
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }
        val decoded = if ('@' in main) main else base64(main)
        require('@' in decoded) { "Missing Shadowsocks server address" }
        val rawUserInfo = decoded.substringBeforeLast('@')
        val userInfo =
            if ('@' !in main || ':' in rawUserInfo) rawUserInfo else base64(decode(rawUserInfo))
        require(':' in userInfo) { "Invalid Shadowsocks credentials" }
        val plain = '@' in main && ':' in rawUserInfo
        val method =
            if (plain) decode(userInfo.substringBefore(':')) else userInfo.substringBefore(':')
        val password =
            if (plain) decode(userInfo.substringAfter(':')) else userInfo.substringAfter(':')
        val methods =
            setOf(
                "none",
                "aes-128-gcm",
                "aes-192-gcm",
                "aes-256-gcm",
                "chacha20-ietf-poly1305",
                "xchacha20-ietf-poly1305",
                "2022-blake3-aes-128-gcm",
                "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305",
            )
        require(method in methods) { "Unsupported Shadowsocks encryption method" }
        require(password.isNotEmpty() || method == "none") { "Missing Shadowsocks password" }
        val authority = decoded.substringAfterLast('@')
        require(
            if (authority.startsWith('[')) {
                authority.substringAfter(']').startsWith(':')
            } else {
                ':' in authority
            },
        ) {
            "Missing Shadowsocks server port"
        }
        val server = endpoint(authority)
        require(server.ports.size == 1 && ':' !in server.ports.first()) {
            "Invalid Shadowsocks server port"
        }
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", decode(line.substringAfter('#', "Proxy")))
            .put("server", server.host)
            .put("server_port", server.port)
            .put("method", method)
            .put("password", password)
            .also { node ->
                params["plugin"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { plugin ->
                        val name = plugin.substringBefore(';')
                        require(name in setOf("obfs-local", "v2ray-plugin")) {
                            "Unsupported Shadowsocks plugin"
                        }
                        node.put("plugin", name).put("plugin_opts", plugin.substringAfter(';', ""))
                    }
            }
    }

    fun hysteria(line: String, warn: (String) -> Unit = {}): JSONObject {
        val scheme = line.substringBefore("://").lowercase()
        val v2 = scheme != "hysteria"
        val body = line.substringAfter("://").substringBefore('#')
        val authority = body.substringBefore('?').trimEnd('/')
        val params = query(body.substringAfter('?', "")).toMutableMap()
        for (
        (alias, canonical) in
        mapOf(
            "allowInsecure" to "insecure",
            "skip-cert-verify" to "insecure",
            "serverName" to "sni",
            "peer" to "sni",
            "fingerprint" to "fp",
            "obfsPassword" to "obfs-password",
            "hop-interval" to "hopInterval",
            "up" to "upmbps",
            "down" to "downmbps",
        )
        ) {
            params.remove(alias)?.let { value ->
                require(params[canonical] == null || params[canonical] == value) {
                    "Invalid conflicting Hysteria aliases"
                }
                params[canonical] = value
            }
        }
        val allowed =
            setOf(
                "auth",
                "sni",
                "peer",
                "insecure",
                "pinSHA256",
                "alpn",
                "ech",
                "mport",
                "hopInterval",
                "obfs",
                "obfs-password",
                "obfsPassword",
                "protocol",
                "obfsParam",
                "upmbps",
                "downmbps",
                "up_mbps",
                "down_mbps",
                "security",
                "type",
                "pcs",
                "vcn",
                "fm",
                "fp",
            )
        val unsupported = params.keys.firstOrNull { it !in allowed }
        require(unsupported == null) { "Unsupported Hysteria parameter: $unsupported" }
        require(params["security"].isNullOrBlank() || params["security"] == "tls") {
            "Unsupported Hysteria security"
        }
        require(params["type"].isNullOrBlank() || params["type"] == "udp") {
            "Unsupported Hysteria transport"
        }
        val auth =
            if ('@' in authority) {
                decode(authority.substringBeforeLast('@'))
            } else {
                params["auth"].orEmpty()
            }
        val server = endpoint(authority.substringAfterLast('@'))
        val tls =
            JSONObject()
                .put("enabled", true)
                .put("server_name", params["sni"] ?: params["peer"] ?: server.host)
        params["insecure"]?.let { tls.put("insecure", boolean(it)) }
        params["pinSHA256"]
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                val hex = value.replace(":", "").trim()
                val bytes =
                    if (hex.length == 64 && hex.all { it in "0123456789abcdefABCDEF" }) {
                        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    } else {
                        try {
                            Base64.getDecoder().decode(value.trim())
                        } catch (_: IllegalArgumentException) {
                            error("Invalid Hysteria certificate SHA-256 fingerprint")
                        }
                    }
                require(bytes.size == 32) { "Invalid Hysteria certificate SHA-256 fingerprint" }
                tls.put(
                    "certificate_sha256",
                    JSONArray(listOf(Base64.getEncoder().encodeToString(bytes))),
                )
            }
        params["alpn"]
            ?.takeIf { it.isNotBlank() }
            ?.let { tls.put("alpn", JSONArray(it.split(','))) }
        params["fp"]
            ?.takeIf { it.isNotBlank() }
            ?.let { fp ->
                // Normalize below to Hysteria2 QUIC Chrome parrot, not TCP uTLS.
                tls.put(
                    "utls",
                    JSONObject().put("enabled", true).put("fingerprint", fp),
                )
            }
        params["ech"]
            ?.takeIf { it.isNotBlank() }
            ?.let {
                val encoded = Base64.getEncoder().encodeToString(Base64.getDecoder().decode(it))
                val pem =
                    "-----BEGIN ECH CONFIGS-----\n${encoded.chunked(64).joinToString("\n")}\n-----END ECH CONFIGS-----"
                tls.put(
                    "ech",
                    JSONObject().put("enabled", true).put("config", JSONArray(listOf(pem))),
                )
            }
        val result =
            JSONObject()
                .put("type", if (v2) "hysteria2" else "hysteria")
                .put("server", server.host)
                .put("server_port", server.port)
                .put("tls", tls)
                .put(
                    "tag",
                    decode(line.substringAfter('#', "")).ifBlank {
                        "${if (v2) "Hysteria2" else "Hysteria"} ${server.host}"
                    },
                )
        val ports = params["mport"]?.let { endpoint("[${server.host}]:$it").ports } ?: server.ports
        if (ports.size > 1 || ports.any { ':' in it }) result.put("server_ports", JSONArray(ports))
        params["hopInterval"]?.let {
            result.put("hop_interval", if (it.toDoubleOrNull() != null) "${it}s" else it)
        }
        if (v2) {
            result.put("password", auth)
            params["obfs"]
                ?.takeIf { it.isNotBlank() && it != "none" }
                ?.let { type ->
                    require(type in setOf("salamander", "gecko")) {
                        "Unsupported Hysteria2 obfuscation: $type"
                    }
                    val password = params["obfs-password"] ?: params["obfsPassword"]
                    require(!password.isNullOrEmpty()) { "Missing Hysteria2 obfuscation password" }
                    result.put("obfs", JSONObject().put("type", type).put("password", password))
                }
            bandwidth(result, params, "up", false)
            bandwidth(result, params, "down", false)
        } else {
            require(params["protocol"].isNullOrEmpty() || params["protocol"] == "udp") {
                "Hysteria supports UDP transport only in this core"
            }
            result.put("auth_str", auth)
            val obfs = params["obfs"]
            if (!obfs.isNullOrEmpty() && obfs != "none") {
                require(obfs == "xplus") { "Unsupported Hysteria obfuscation: $obfs" }
                require(!params["obfsParam"].isNullOrEmpty()) {
                    "Missing Hysteria obfuscation password"
                }
                result.put("obfs", params.getValue("obfsParam"))
            }
            bandwidth(result, params, "up", true)
            bandwidth(result, params, "down", true)
        }
        applyXrayVerification(tls, params["pcs"], params["vcn"])
        params["fm"]?.let { applyFinalMask(result, JSONObject(it)) }
        if (v2 && !params["fp"].isNullOrBlank()) warn(QuicTlsCompatibility.XRAY_HYSTERIA_NOTE)
        return if (v2) {
            QuicTlsCompatibility.fromXrayHysteria(result)
        } else {
            QuicTlsCompatibility.normalize(result)
        }
    }

    private fun bandwidth(
        result: JSONObject,
        params: Map<String, String>,
        direction: String,
        required: Boolean,
    ) {
        val value = params["${direction}mbps"] ?: params["${direction}_mbps"]
        if (value != null) {
            val mbps =
                Regex("(?i)^(\\d+)(?:\\s*(?:mbps|m))?$")
                    .matchEntire(value.trim())
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            require(mbps != null && mbps > 0) { "Invalid Hysteria $direction bandwidth" }
            result.put("${direction}_mbps", mbps)
        } else if (required) {
            // Hysteria v1 requires a congestion-control bandwidth; v2 can negotiate it.
            result.put("${direction}_mbps", 100)
        }
    }

    fun boolean(value: String): Boolean = when (value.lowercase()) {
        "1",
        "true",
        -> true
        "0",
        "false",
        -> false
        else -> error("Invalid boolean share-link parameter")
    }

    fun transport(network: String, params: Map<String, String>, defaultHost: String): JSONObject? = when (network.lowercase()) {
        "",
        "tcp",
        "raw",
        -> null
        "ws" ->
            JSONObject()
                .put("type", "ws")
                .put("path", params["path"] ?: "/")
                .put(
                    "headers",
                    JSONObject()
                        .put("Host", params["host"]?.takeIf { it.isNotBlank() } ?: defaultHost),
                )
        "http",
        "h2",
        ->
            JSONObject()
                .put("type", "http")
                .put("host", JSONArray(params["host"]?.split(',') ?: listOf(defaultHost)))
                .put("path", params["path"] ?: "/")
        "grpc" ->
            JSONObject()
                .put("type", "grpc")
                .put("service_name", params["serviceName"] ?: params["service_name"] ?: "")
        "httpupgrade" ->
            JSONObject()
                .put("type", "httpupgrade")
                .put("path", params["path"] ?: "/")
                .put("host", params["host"] ?: defaultHost)
        "xhttp",
        "splithttp",
        -> xhttp(params)
        else -> error("Unsupported transport: $network")
    }

    private val ranges =
        mapOf(
            "xPaddingBytes" to "x_padding_bytes",
            "sessionIDLength" to "session_id_length",
            "uplinkChunkSize" to "uplink_chunk_size",
            "scMaxEachPostBytes" to "sc_max_each_post_bytes",
            "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
            "scStreamUpServerSecs" to "sc_stream_up_server_secs",
        )
    private val fields =
        mapOf(
            "host" to "host",
            "path" to "path",
            "mode" to "mode",
            "headers" to "headers",
            "xPaddingObfsMode" to "x_padding_obfs_mode",
            "xPaddingKey" to "x_padding_key",
            "xPaddingHeader" to "x_padding_header",
            "xPaddingPlacement" to "x_padding_placement",
            "xPaddingMethod" to "x_padding_method",
            "uplinkHTTPMethod" to "uplink_http_method",
            "sessionIDPlacement" to "session_id_placement",
            "sessionIDKey" to "session_id_key",
            "sessionPlacement" to "session_id_placement",
            "sessionKey" to "session_id_key",
            "sessionIDTable" to "session_id_table",
            "seqPlacement" to "seq_placement",
            "seqKey" to "seq_key",
            "uplinkDataPlacement" to "uplink_data_placement",
            "uplinkDataKey" to "uplink_data_key",
            "noGRPCHeader" to "no_grpc_header",
            "noSSEHeader" to "no_sse_header",
            "scMaxBufferedPosts" to "sc_max_buffered_posts",
            "serverMaxHeaderBytes" to "server_max_header_bytes",
        )
    private val xmuxRanges =
        mapOf(
            "maxConcurrency" to "max_concurrency",
            "maxConnections" to "max_connections",
            "cMaxReuseTimes" to "c_max_reuse_times",
            "maxReuseTimes" to "c_max_reuse_times",
            "hMaxRequestTimes" to "h_max_request_times",
            "hMaxReusableSecs" to "h_max_reusable_secs",
        )

    private fun range(value: Any): JSONObject {
        if (value is JSONObject) return value
        val parts = value.toString().split('-')
        require(parts.size in 1..2) { "Invalid XHTTP range" }
        val from = parts.first().toIntOrNull() ?: error("Invalid XHTTP range")
        val to = parts.last().toIntOrNull() ?: error("Invalid XHTTP range")
        require(from >= 0 && to >= from) { "Invalid XHTTP range" }
        return JSONObject().put("from", from).put("to", to)
    }

    private fun convertExtra(extra: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in extra.keys()) {
            val value = extra.get(key)
            if (
                value === JSONObject.NULL &&
                key in
                setOf("downloadSettings", "download_settings", "headers", "xmux", "extra")
            ) {
                continue
            }
            when {
                key in ranges -> result.put(ranges.getValue(key), range(value))
                key in fields -> result.put(fields.getValue(key), value)
                key in ranges.values -> result.put(key, range(value))
                key in fields.values -> result.put(key, value)
                key == "scMaxConcurrentPosts" || key == "sc_max_concurrent_posts" -> {
                    require(value.toString().toIntOrNull()?.let { it in 0..1024 } == true) {
                        "Invalid XHTTP concurrent POST limit"
                    }
                    require(!result.has("sc_max_concurrent_posts")) {
                        "Invalid conflicting XHTTP concurrent POST aliases"
                    }
                    result.put("sc_max_concurrent_posts", value.toString().toInt())
                }
                key == "xmux" -> {
                    val converted = JSONObject()
                    val source = value as? JSONObject ?: error("Invalid XHTTP xmux options")
                    for (field in source.keys()) {
                        val name = xmuxRanges[field] ?: field
                        require(!converted.has(name)) { "Invalid conflicting XHTTP xmux aliases" }
                        when {
                            name in xmuxRanges.values ->
                                converted.put(name, range(source.get(field)))
                            field == "hKeepAlivePeriod" || field == "h_keep_alive_period" ->
                                converted.put("h_keep_alive_period", source.get(field))
                            else -> error("Unsupported XHTTP xmux parameter: $field")
                        }
                    }
                    result.put("xmux", converted)
                }
                key == "downloadSettings" || key == "download_settings" ->
                    result.put(
                        "download_settings",
                        download(value as? JSONObject ?: error("Invalid XHTTP download settings")),
                    )
                else -> error("Unsupported XHTTP parameter: $key")
            }
        }
        normalizeMode(result)
        val mode = result.optString("mode", "auto")
        require(mode in setOf("auto", "packet-up", "stream-up", "stream-one")) {
            "Unsupported XHTTP mode: $mode"
        }
        return result
    }

    private fun download(source: JSONObject): JSONObject {
        // Native sing-box download settings can be passed through unchanged.
        if (source.has("server")) return source
        val settings =
            source.optJSONObject("xhttpSettings")
                ?: source.optJSONObject("splithttpSettings")
                ?: JSONObject()
        val result = convertExtra(settings.optJSONObject("extra") ?: JSONObject())
        for (key in listOf("host", "path", "mode")) {
            if (settings.has(key)) {
                result.put(key, settings.get(key))
            }
        }
        normalizeMode(result)
        require(source.optString("network", "xhttp") in setOf("xhttp", "splithttp")) {
            "Unsupported XHTTP download transport"
        }
        result.put("server", source.getString("address")).put("server_port", source.getInt("port"))
        when (source.optString("security", "none")) {
            "none",
            "",
            -> Unit
            "tls",
            "reality",
            -> {
                val reality = source.optString("security") == "reality"
                val options =
                    source.optJSONObject(if (reality) "realitySettings" else "tlsSettings")
                        ?: JSONObject()
                val tls =
                    JSONObject()
                        .put("enabled", true)
                        .put(
                            "server_name",
                            options.optString("serverName", source.getString("address")),
                        )
                if (options.has("allowInsecure")) {
                    tls.put("insecure", options.getBoolean("allowInsecure"))
                }
                if (options.has("alpn")) tls.put("alpn", options.getJSONArray("alpn"))
                if (options.has("fingerprint")) {
                    tls.put(
                        "utls",
                        JSONObject()
                            .put("enabled", true)
                            .put("fingerprint", options.getString("fingerprint")),
                    )
                }
                if (reality) {
                    if (!tls.has("utls")) {
                        tls.put(
                            "utls",
                            JSONObject().put("enabled", true).put("fingerprint", "chrome"),
                        )
                    }
                    tls.put(
                        "reality",
                        JSONObject()
                            .put("enabled", true)
                            .put("public_key", options.getString("publicKey"))
                            .put("short_id", options.optString("shortId")),
                    )
                }
                result.put("tls", tls)
            }
            else -> error("Unsupported XHTTP download security")
        }
        return result
    }

    private fun normalizeMode(settings: JSONObject) {
        if (
            settings.has("mode") &&
            (settings.isNull("mode") || settings.optString("mode").isBlank())
        ) {
            settings.put("mode", "auto")
        }
    }

    private fun xhttp(params: Map<String, String>): JSONObject {
        val result =
            convertExtra(
                params["extra"]?.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject(),
            )
        params["x_padding_bytes"]?.let { value ->
            val padding = range(value)
            val existing = result.optJSONObject("x_padding_bytes")
            require(
                existing == null ||
                    (
                        existing.getInt("from") == padding.getInt("from") &&
                            existing.getInt("to") == padding.getInt("to")
                        ),
            ) {
                "Conflicting XHTTP padding parameters"
            }
            result.put("x_padding_bytes", padding)
        }
        for (key in listOf("host", "path", "mode")) {
            params[key]
                ?.takeIf { it.isNotBlank() }
                ?.let { result.put(key, it) }
        }
        normalizeMode(result)
        require(
            result.optString("mode", "auto") in
                setOf("auto", "packet-up", "stream-up", "stream-one"),
        ) {
            "Unsupported XHTTP mode"
        }
        return result.put("type", "xhttp")
    }
}
