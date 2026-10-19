package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

internal data class ForeignServer(
    val position: Int,
    val name: String,
    val convert: () -> JSONObject,
)

internal data class ForeignSubscription(
    val format: String,
    val servers: List<ForeignServer>,
    val warnings: List<String>,
)

internal object ForeignSubscriptionParser {
    fun looksLike(content: String): Boolean = Regex("(?m)^\\s*(proxies|proxy-providers):").containsMatchIn(content) ||
        (
            content.trimStart().firstOrNull() in setOf('{', '[') &&
                (content.contains("\"proxies\"") || content.contains("\"protocol\""))
            )

    private fun checkYamlTree(value: Any?) {
        val ancestors =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        var count = 0
        fun visit(item: Any?, depth: Int) {
            require(++count <= 100000 && depth <= 40) { "Invalid excessive YAML structure" }
            if (item is Map<*, *> || item is List<*>) {
                require(ancestors.add(item)) { "Invalid recursive YAML alias" }
                if (item is Map<*, *>) {
                    require(item.keys.all { it is String }) { "Invalid YAML map key" }
                    item.values.forEach { visit(it, depth + 1) }
                }
                if (item is List<*>) item.forEach { visit(it, depth + 1) }
                ancestors.remove(item)
            }
        }
        visit(value, 0)
    }

    fun decode(content: String): ForeignSubscription? {
        val root =
            when {
                content.startsWith('{') -> JSONObject(content)
                Regex("(?m)^\\s*(proxies|proxy-providers):").containsMatchIn(content) -> {
                    val options =
                        LoaderOptions().apply {
                            isAllowDuplicateKeys = false
                            maxAliasesForCollections = 10
                            nestingDepthLimit = 40
                            codePointLimit = 2 * 1024 * 1024
                        }
                    val value =
                        try {
                            Yaml(SafeConstructor(options)).load<Any>(content)
                        } catch (_: Exception) {
                            error("Invalid Clash YAML structure")
                        }
                    require(value is Map<*, *>) { "Invalid Clash YAML root" }
                    checkYamlTree(value)
                    JSONObject(value)
                }
                content.startsWith('[') -> {
                    val array = JSONArray(content)
                    if (
                        (0 until array.length()).any {
                            array.optJSONObject(it)?.has("protocol") == true
                        }
                    ) {
                        return xray(JSONObject().put("outbounds", array))
                    }
                    if (
                        (0 until array.length()).any {
                            array.optJSONObject(it)?.let { obj ->
                                !obj.has("type") &&
                                    obj.optJSONArray("outbounds")?.let { outs ->
                                        (0 until outs.length()).any { j ->
                                            outs.optJSONObject(j)?.has("protocol") == true
                                        }
                                    } == true
                            } == true
                        }
                    ) {
                        val outbounds = JSONArray()
                        for (i in 0 until array.length()) {
                            val config =
                                array.optJSONObject(i) ?: error("Invalid Xray configuration list")
                            val list =
                                config.optJSONArray("outbounds") ?: error("Invalid Xray outbounds")
                            for (j in 0 until list.length()) {
                                val outbound = list.optJSONObject(j)
                                if (
                                    outbound != null &&
                                    config.optString("remarks").isNotBlank() &&
                                    outbound.optString("protocol") !in
                                    setOf("freedom", "blackhole", "dns")
                                ) {
                                    outbounds.put(
                                        JSONObject(outbound.toString())
                                            .put("tag", config.getString("remarks")),
                                    )
                                } else {
                                    outbounds.put(list.get(j))
                                }
                            }
                        }
                        return xray(JSONObject().put("outbounds", outbounds))
                    }
                    return null
                }
                else -> return null
            }
        if (root.has("proxies") || root.has("proxy-providers")) return clash(root)
        val outbounds = root.optJSONArray("outbounds") ?: return null
        if (
            (0 until outbounds.length()).any {
                outbounds.optJSONObject(it)?.has("protocol") == true
            }
        ) {
            return xray(root)
        }
        return null
    }

    private fun clash(root: JSONObject): ForeignSubscription {
        val array =
            root.optJSONArray("proxies")
                ?: error(
                    "Unsupported provider-only Clash configuration; use a direct server subscription URL",
                )
        val warnings = mutableListOf<String>()
        if (root.has("proxy-providers")) {
            warnings.add(
                "Внешние proxy-providers не загружаются. Для них добавьте прямые URL подписок отдельно.",
            )
        }
        if (listOf("rules", "rule-providers", "dns", "proxy-groups").any { root.has(it) }) {
            warnings.add(
                "Из Clash импортируются серверы. Группы, DNS и правила Clash не переносятся; используются настройки SFAxtnd.",
            )
        }
        val servers =
            (0 until array.length()).map { i ->
                val source = array.optJSONObject(i)
                ForeignServer(
                    i + 1,
                    source?.optString("name")?.takeIf { it.isNotBlank() }?.take(120)
                        ?: "Сервер ${i + 1}",
                ) {
                    clashNode(source ?: error("Invalid Clash proxy object"))
                }
            }
        return ForeignSubscription("Clash / Mihomo", servers, warnings)
    }

    private fun required(source: JSONObject, field: String): String = source.optString(field).also {
        require(source.opt(field) is String || field in setOf("port", "up", "down")) {
            "Invalid non-string server field: $field"
        }
        require(it.isNotBlank()) { "Missing server field: $field" }
    }

    private fun fields(source: JSONObject, supported: Set<String>) {
        // A+: optional unknown keys ignored (PARTIAL). Required keys still via required().
    }

    private fun unknownFields(source: JSONObject, supported: Set<String>): List<String> = source.keys().asSequence().filter { it !in supported }.toList()

    private fun bool(source: JSONObject, key: String, fallback: Boolean = false): Boolean {
        if (!source.has(key)) return fallback
        return when (source.optString(key).lowercase()) {
            "true",
            "1",
            -> true
            "false",
            "0",
            -> false
            else -> error("Invalid boolean field: $key")
        }
    }

    private fun port(source: JSONObject, key: String = "port"): Int = required(source, key).toIntOrNull()?.takeIf { it in 1..65535 }
        ?: error("Invalid server port")

    private fun clashNode(source: JSONObject): JSONObject {
        val type = required(source, "type").lowercase()
        val mapped =
            when (type) {
                "ss" -> "shadowsocks"
                "socks5" -> "socks"
                "hy2" -> "hysteria2"
                "vless",
                "vmess",
                "trojan",
                "hysteria",
                "hysteria2",
                "tuic",
                "anytls",
                "http",
                -> type
                else -> error("Unsupported Clash proxy type: $type")
            }
        fields(
            source,
            setOf(
                "name",
                "type",
                "server",
                "port",
                "password",
                "username",
                "uuid",
                "cipher",
                "alterId",
                "flow",
                "encryption",
                "udp",
                "tls",
                "servername",
                "sni",
                "alpn",
                "skip-cert-verify",
                "client-fingerprint",
                "fingerprint",
                "reality-opts",
                "network",
                "ws-opts",
                "grpc-opts",
                "h2-opts",
                "http-opts",
                "http-upgrade-opts",
                "xhttp-opts",
                "packet-encoding",
                "plugin",
                "plugin-opts",
                "auth-str",
                "auth",
                "obfs",
                "obfs-password",
                "up",
                "down",
                "ports",
                "hop-interval",
                "congestion-controller",
                "udp-relay-mode",
                "udp-over-stream",
                "reduce-rtt",
                "heartbeat-interval",
                "disable-sni",
                "idle-session-check-interval",
                "idle-session-timeout",
                "min-idle-session",
                "smux",
                "tfo",
                "mptcp",
                "ip-version",
            ),
        )
        val specific =
            when (mapped) {
                "shadowsocks" -> setOf("cipher", "password", "plugin", "plugin-opts")
                "vless" -> setOf("uuid", "flow", "encryption", "packet-encoding")
                "vmess" -> setOf("uuid", "cipher", "alterId")
                "trojan" -> setOf("password")
                "anytls" ->
                    setOf(
                        "password",
                        "idle-session-check-interval",
                        "idle-session-timeout",
                        "min-idle-session",
                    )
                "tuic" ->
                    setOf(
                        "uuid",
                        "password",
                        "congestion-controller",
                        "udp-relay-mode",
                        "udp-over-stream",
                        "reduce-rtt",
                        "heartbeat-interval",
                        "disable-sni",
                    )
                "hysteria",
                "hysteria2",
                ->
                    setOf(
                        "password",
                        "auth-str",
                        "auth",
                        "obfs",
                        "obfs-password",
                        "up",
                        "down",
                        "ports",
                        "hop-interval",
                    )
                else -> setOf("username", "password")
            }
        val transportFields =
            if (mapped in setOf("vless", "vmess", "trojan")) {
                setOf(
                    "network",
                    "ws-opts",
                    "grpc-opts",
                    "h2-opts",
                    "http-opts",
                    "http-upgrade-opts",
                    "xhttp-opts",
                    "smux",
                )
            } else {
                emptySet()
            }
        fields(
            source,
            specific +
                transportFields +
                setOf(
                    "name",
                    "type",
                    "server",
                    "port",
                    "udp",
                    "tls",
                    "servername",
                    "sni",
                    "alpn",
                    "skip-cert-verify",
                    "client-fingerprint",
                    "fingerprint",
                    "reality-opts",
                    "tfo",
                    "mptcp",
                    "ip-version",
                ),
        )
        val node =
            JSONObject()
                .put("type", mapped)
                .put("tag", source.optString("name", "Proxy"))
                .put("server", required(source, "server"))
                .put("server_port", port(source))
        when (mapped) {
            "vless",
            "vmess",
            -> {
                node.put("uuid", required(source, "uuid"))
                if (mapped == "vmess") {
                    node.put("security", source.optString("cipher", "auto"))
                    if (source.has("alterId")) node.put("alter_id", source.getInt("alterId"))
                } else {
                    ProxyLinkParser.applyVlessEncryption(
                        node,
                        source.optString("encryption"),
                        source.optString("flow"),
                    )
                    source
                        .optString("flow")
                        .takeIf { it.isNotBlank() }
                        ?.let { node.put("flow", it) }
                    source
                        .optString("packet-encoding")
                        .takeIf { it.isNotBlank() }
                        ?.let { node.put("packet_encoding", it) }
                }
            }
            "shadowsocks" -> {
                node
                    .put("method", required(source, "cipher"))
                    .put("password", required(source, "password"))
                source
                    .optString("plugin")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        require(it in setOf("obfs", "obfs-local", "v2ray-plugin")) {
                            "Unsupported Shadowsocks plugin"
                        }
                        node.put("plugin", if (it == "obfs") "obfs-local" else it)
                        val options = source.optJSONObject("plugin-opts") ?: JSONObject()
                        fields(
                            options,
                            if (it == "obfs" || it == "obfs-local") {
                                setOf("mode", "host")
                            } else {
                                setOf("mode", "tls", "host", "path", "mux", "skip-cert-verify")
                            },
                        )
                        val values =
                            options
                                .keys()
                                .asSequence()
                                .map { key ->
                                    val option =
                                        if (it == "obfs" || it == "obfs-local") {
                                            when (key) {
                                                "mode" -> "obfs"
                                                else -> "obfs-host"
                                            }
                                        } else {
                                            key
                                        }
                                    val value = options.get(key)
                                    if (value is Boolean) {
                                        if (value) option else ""
                                    } else {
                                        "$option=$value"
                                    }
                                }
                                .filter { it.isNotEmpty() }
                                .joinToString(";")
                        node.put("plugin_opts", values)
                    }
            }
            "trojan",
            "anytls",
            -> node.put("password", required(source, "password"))
            "tuic" -> {
                node
                    .put("uuid", required(source, "uuid"))
                    .put("password", required(source, "password"))
                for (
                (from, to) in
                mapOf(
                    "congestion-controller" to "congestion_control",
                    "udp-relay-mode" to "udp_relay_mode",
                    "heartbeat-interval" to "heartbeat",
                )
                ) {
                    if (source.has(from)) {
                        node.put(
                            to,
                            source.get(from).let {
                                if (from == "heartbeat-interval" && it is Number) "${it}s" else it
                            },
                        )
                    }
                }
                if (source.has("reduce-rtt")) {
                    node.put("zero_rtt_handshake", bool(source, "reduce-rtt"))
                }
                if (source.has("udp-over-stream")) {
                    node.put("udp_over_stream", bool(source, "udp-over-stream"))
                }
            }
            "hysteria",
            "hysteria2",
            -> {
                node.put(
                    if (mapped == "hysteria") "auth_str" else "password",
                    required(source, if (source.has("auth-str")) "auth-str" else "password"),
                )
                if (mapped == "hysteria") {
                    require(!source.has("auth")) {
                        "Unsupported Hysteria binary auth; use auth-str"
                    }
                }
                for (key in listOf("up", "down")) {
                    if (source.has(key)) {
                        node.put("${key}_mbps", bandwidth(required(source, key)))
                    }
                }
                if (mapped == "hysteria") {
                    if (!node.has("up_mbps")) node.put("up_mbps", 100)
                    if (!node.has("down_mbps")) node.put("down_mbps", 100)
                }
                source
                    .optString("obfs")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        if (mapped == "hysteria2") {
                            node.put(
                                "obfs",
                                JSONObject()
                                    .put("type", it)
                                    .put("password", required(source, "obfs-password")),
                            )
                        } else {
                            node.put("obfs", it)
                        }
                    }
                if (source.has("ports")) {
                    val ranges =
                        ProxyLinkParser.endpoint(
                            "[${node.getString("server")}]:${source.get("ports")}",
                        )
                            .ports
                    node.put("server_ports", JSONArray(ranges))
                }
                if (source.has("hop-interval")) {
                    node.put(
                        "hop_interval",
                        source.get("hop-interval").let { if (it is Number) "${it}s" else it },
                    )
                }
            }
            "socks",
            "http",
            -> {
                if (mapped == "socks") {
                    node.put("version", "5")
                    require(!bool(source, "tls")) { "Unsupported SOCKS TLS transport" }
                }
                for (key in listOf("username", "password")) {
                    if (source.has(key)) {
                        node.put(key, source.get(key))
                    }
                }
            }
        }
        if (source.has("udp") && !bool(source, "udp")) {
            require(mapped !in setOf("http", "anytls")) {
                "Unsupported UDP option for this proxy type"
            }
            node.put("network", "tcp")
        }
        if (source.has("tfo")) node.put("tcp_fast_open", bool(source, "tfo"))
        if (source.has("mptcp")) node.put("tcp_multi_path", bool(source, "mptcp"))
        if (source.has("ip-version")) {
            val strategy =
                when (source.getString("ip-version")) {
                    "ipv4" -> "ipv4_only"
                    "ipv6" -> "ipv6_only"
                    "ipv4-prefer" -> "prefer_ipv4"
                    "ipv6-prefer" -> "prefer_ipv6"
                    "dual" -> ""
                    else -> error("Unsupported IP version")
                }
            if (strategy.isNotEmpty()) {
                node.put(
                    "domain_resolver",
                    JSONObject().put("server", "dns-direct").put("strategy", strategy),
                )
            }
        }
        require(!source.has("plugin-opts") || source.has("plugin")) { "Missing Shadowsocks plugin" }
        require(!source.has("obfs-password") || source.has("obfs")) {
            "Missing Hysteria obfuscation type"
        }
        if (mapped == "hysteria2") {
            require(!source.has("auth")) { "Unsupported Hysteria binary auth" }
        }
        val mandatoryTls = mapped in setOf("trojan", "hysteria", "hysteria2", "tuic", "anytls")
        val enabledTls = mandatoryTls || bool(source, "tls") || source.has("reality-opts")
        if (!enabledTls) {
            require(
                listOf(
                    "servername",
                    "sni",
                    "alpn",
                    "skip-cert-verify",
                    "client-fingerprint",
                    "fingerprint",
                )
                    .none { source.has(it) },
            ) {
                "Invalid TLS parameters without enabled TLS"
            }
        }
        if (enabledTls) {
            require(mapped !in setOf("socks", "shadowsocks")) {
                "Unsupported TLS wrapper for this proxy type"
            }
            require(!source.has("tls") || bool(source, "tls")) {
                "Invalid disabled TLS for this proxy"
            }
            val tls =
                JSONObject()
                    .put("enabled", true)
                    .put(
                        "server_name",
                        source.optString("servername").ifBlank {
                            source.optString("sni").ifBlank { node.getString("server") }
                        },
                    )
            if (source.has("alpn")) tls.put("alpn", source.getJSONArray("alpn"))
            if (source.has("skip-cert-verify")) {
                tls.put("insecure", bool(source, "skip-cert-verify"))
            }
            if (source.has("disable-sni")) tls.put("disable_sni", bool(source, "disable-sni"))
            source
                .optString("client-fingerprint")
                .takeIf { it.isNotBlank() }
                ?.let {
                    require(
                        mapped !in setOf("tuic", "hysteria") &&
                            (mapped != "hysteria2" || it.equals("chrome", ignoreCase = true)),
                    ) {
                        "Unsupported QUIC uTLS fingerprint"
                    }
                    tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
                }
            source
                .optString("fingerprint")
                .takeIf { it.isNotBlank() }
                ?.let {
                    tls.put(
                        "certificate_sha256",
                        JSONArray().put(ProxyLinkParser.certificatePin(it)),
                    )
                }
            source.optJSONObject("reality-opts")?.let { reality ->
                fields(reality, setOf("public-key", "short-id", "spider-x", "support-x25519mlkem768"))
                ProxyLinkParser.applyReality(
                    tls,
                    required(reality, "public-key"),
                    reality.optString("short-id"),
                    spiderX = reality.optString("spider-x").ifBlank { null },
                    supportX25519MLKEM768 = if (reality.has("support-x25519mlkem768")) bool(reality, "support-x25519mlkem768") else null,
                    fingerprint = source.optString("client-fingerprint").ifBlank {
                        source.optString("fingerprint")
                    }.ifBlank { null },
                )
            }
            node.put("tls", tls)
        }
        if (mapped in setOf("vless", "vmess", "trojan")) {
            clashTransport(source)?.let { node.put("transport", it) }
        } else {
            require(source.optString("network") in setOf("", "tcp")) {
                "Unsupported transport for proxy type"
            }
        }
        source.optJSONObject("smux")?.let { options ->
            fields(
                options,
                setOf(
                    "enabled",
                    "protocol",
                    "max-connections",
                    "min-streams",
                    "max-streams",
                    "padding",
                ),
            )
            val mux = JSONObject()
            options.keys().forEach { key -> mux.put(key.replace('-', '_'), options.get(key)) }
            node.put("multiplex", mux)
        }
        if (mapped == "anytls") {
            for (
            (from, to) in
            mapOf(
                "idle-session-check-interval" to "idle_session_check_interval",
                "idle-session-timeout" to "idle_session_timeout",
                "min-idle-session" to "min_idle_session",
            )
            ) {
                if (source.has(from)) node.put(to, source.get(from))
            }
        }
        return QuicTlsCompatibility.normalize(node)
    }

    private fun bandwidth(value: String): Int = Regex("(?i)^(\\d+)(?:\\s*(?:mbps|m))?$")
        .matchEntire(value.trim())
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 } ?: error("Invalid Hysteria bandwidth")

    private fun clashTransport(source: JSONObject): JSONObject? {
        val network = source.optString("network", "tcp")
        val selectedOption =
            when (network) {
                "ws" -> "ws-opts"
                "grpc" -> "grpc-opts"
                "h2" -> "h2-opts"
                "http" -> "http-opts"
                "httpupgrade" -> "http-upgrade-opts"
                "xhttp" -> "xhttp-opts"
                else -> ""
            }
        require(
            listOf(
                "ws-opts",
                "grpc-opts",
                "h2-opts",
                "http-opts",
                "http-upgrade-opts",
                "xhttp-opts",
            )
                .none { it != selectedOption && source.has(it) },
        ) {
            "Invalid transport options for selected network"
        }
        val optionKey =
            when (network) {
                "ws" -> "ws-opts"
                "grpc" -> "grpc-opts"
                "h2" -> "h2-opts"
                "http" -> "http-opts"
                "httpupgrade" -> "http-upgrade-opts"
                "xhttp" -> "xhttp-opts"
                "tcp",
                "raw",
                "",
                -> return null
                else -> error("Unsupported Clash transport")
            }
        val options = source.optJSONObject(optionKey) ?: JSONObject()
        val params = mutableMapOf<String, String>()
        if (options.opt("path") is String) {
            options.optString("path").takeIf { it.isNotBlank() }?.let { params["path"] = it }
        }
        options.optString("host").takeIf { it.isNotBlank() }?.let { params["host"] = it }
        if (network == "xhttp") {
            fields(options, setOf("host", "path", "mode"))
            params["mode"] = options.optString("mode", "auto")
            return ProxyLinkParser.transport(network, params, source.getString("server"))
        }
        if (network == "grpc") {
            fields(options, setOf("grpc-service-name"))
            params["serviceName"] = options.optString("grpc-service-name")
        } else if (network in setOf("h2", "http")) {
            fields(options, setOf("host", "path", "method", "headers"))
            val transport = JSONObject().put("type", "http")
            if (options.has("host")) {
                transport.put(
                    "host",
                    if (options.get("host") is JSONArray) {
                        options.getJSONArray("host")
                    } else {
                        JSONArray().put(options.get("host"))
                    },
                )
            }
            if (options.has("path")) {
                val path = options.get("path")
                require(path !is JSONArray || path.length() == 1) {
                    "Unsupported multiple HTTP paths"
                }
                transport.put("path", if (path is JSONArray) path.getString(0) else path)
            }
            for (key in listOf("method", "headers")) {
                if (options.has(key)) {
                    transport.put(key, options.get(key))
                }
            }
            return transport
        } else {
            fields(
                options,
                if (network == "ws") {
                    setOf("path", "host", "headers", "max-early-data", "early-data-header-name")
                } else {
                    setOf("path", "host")
                },
            )
        }
        val transport =
            ProxyLinkParser.transport(network, params, source.getString("server")) ?: return null
        if (options.has("headers")) transport.put("headers", options.getJSONObject("headers"))
        if (network == "ws") {
            if (options.has("max-early-data")) {
                transport.put("max_early_data", options.getInt("max-early-data"))
            }
            if (options.has("early-data-header-name")) {
                transport.put("early_data_header_name", options.getString("early-data-header-name"))
            }
        }
        return transport
    }

    private fun xray(root: JSONObject): ForeignSubscription {
        val servers = mutableListOf<ForeignServer>()
        var hysteriaFingerprint = false
        val outbounds = root.getJSONArray("outbounds")
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i)
            if (outbound == null) {
                servers.add(
                    ForeignServer(servers.size + 1, "Сервер ${i + 1}") {
                        error("Invalid Xray outbound object")
                    },
                )
                continue
            }
            val protocol = outbound.optString("protocol")
            if (protocol == "hysteria" && !outbound.optJSONObject("streamSettings")
                    ?.optJSONObject("tlsSettings")?.optString("fingerprint").isNullOrBlank()
            ) {
                hysteriaFingerprint = true
            }
            if (protocol in setOf("freedom", "blackhole", "dns")) continue
            val settings = outbound.optJSONObject("settings") ?: JSONObject()
            val entries =
                (
                    settings.optJSONArray(
                        if (protocol in setOf("vmess", "vless")) "vnext" else "servers",
                    ) ?: JSONArray()
                    )
                    .also {
                        if (it.length() == 0) {
                            it.put(if (protocol == "hysteria") settings else JSONObject())
                        }
                    }
            for (j in 0 until entries.length()) {
                val server = entries.optJSONObject(j) ?: JSONObject()
                val users =
                    if (protocol in setOf("vmess", "vless", "socks")) {
                        server.optJSONArray("users") ?: JSONArray().put(JSONObject())
                    } else {
                        JSONArray().put(server)
                    }
                if (users.length() == 0) users.put(JSONObject())
                for (k in 0 until users.length()) {
                    val position = servers.size + 1
                    val name =
                        root.optString("remarks").ifBlank {
                            outbound.optString("tag").ifBlank { "Сервер $position" }
                        } +
                            if (entries.length() > 1 || users.length() > 1) {
                                " (${j + 1}/${k + 1})"
                            } else {
                                ""
                            }
                    servers.add(
                        ForeignServer(position, name.take(120)) {
                            xrayNode(outbound, server, users.optJSONObject(k) ?: JSONObject(), name)
                        },
                    )
                }
            }
        }
        return ForeignSubscription(
            "Xray JSON",
            servers,
            listOf(
                "Из Xray импортируются только outbound-серверы. Inbounds, routing, DNS, groups и policy Xray не переносятся — используются настройки SFAxtnd. Reality spiderX/fingerprint маппятся в sing-box при наличии.",
            ) + if (hysteriaFingerprint) listOf(QuicTlsCompatibility.XRAY_HYSTERIA_NOTE) else emptyList(),
        )
    }

    private fun xrayHysteria(source: JSONObject, name: String): JSONObject {
        fields(source, setOf("tag", "protocol", "settings", "streamSettings"))
        val settings = source.getJSONObject("settings")
        fields(settings, setOf("address", "port", "version"))
        require(settings.getInt("version") == 2) { "Unsupported Xray Hysteria version" }
        val stream = source.getJSONObject("streamSettings")
        fields(stream, setOf("network", "security", "hysteriaSettings", "tlsSettings", "finalmask"))
        require(
            stream.getString("network") == "hysteria" && stream.getString("security") == "tls",
        ) {
            "Invalid Xray Hysteria transport"
        }
        val hy = stream.getJSONObject("hysteriaSettings")
        fields(hy, setOf("version", "auth"))
        require(hy.getInt("version") == 2) { "Unsupported Xray Hysteria version" }
        val options = stream.getJSONObject("tlsSettings")
        fields(
            options,
            setOf(
                "serverName",
                "allowInsecure",
                "alpn",
                "fingerprint",
                "enableSessionResumption",
                "pinnedPeerCertSha256",
                "verifyPeerCertByName",
            ),
        )
        val tls =
            JSONObject()
                .put("enabled", true)
                .put("server_name", options.optString("serverName", required(settings, "address")))
        if (options.has("allowInsecure")) tls.put("insecure", bool(options, "allowInsecure"))
        if (options.has("alpn")) tls.put("alpn", options.getJSONArray("alpn"))
        require(
            !options.has("enableSessionResumption") || !bool(options, "enableSessionResumption"),
        ) {
            "Unsupported Hysteria session resumption"
        }
        options
            .optString("fingerprint")
            .takeIf { it.isNotBlank() }
            ?.let { fp ->
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            }
        ProxyLinkParser.applyXrayVerification(
            tls,
            options.optString("pinnedPeerCertSha256"),
            options.optString("verifyPeerCertByName"),
        )
        val node =
            JSONObject()
                .put("type", "hysteria2")
                .put("tag", name)
                .put("server", required(settings, "address"))
                .put("server_port", port(settings))
                .put("password", required(hy, "auth"))
                .put("tls", tls)
        stream.optJSONObject("finalmask")?.let { ProxyLinkParser.applyFinalMask(node, it) }
        require(!stream.has("finalmask") || stream.optJSONObject("finalmask") != null) {
            "Invalid finalmask object"
        }
        return QuicTlsCompatibility.fromXrayHysteria(node)
    }

    private fun xrayNode(
        source: JSONObject,
        server: JSONObject,
        user: JSONObject,
        name: String,
    ): JSONObject {
        val protocol = source.optString("protocol")
        if (protocol == "hysteria") return xrayHysteria(source, name)
        require(protocol in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")) {
            "Unsupported Xray outbound protocol"
        }
        fields(source, setOf("tag", "protocol", "settings", "streamSettings", "mux"))
        val node =
            JSONObject()
                .put("type", protocol)
                .put("tag", name)
                .put("server", required(server, "address"))
                .put("server_port", port(server))
        val settings = source.optJSONObject("settings") ?: JSONObject()
        fields(settings, setOf(if (protocol in setOf("vless", "vmess")) "vnext" else "servers"))
        fields(
            server,
            when (protocol) {
                "vless",
                "vmess",
                "socks",
                "http",
                -> setOf("address", "port", "users")
                "trojan" -> setOf("address", "port", "password", "level", "email")
                else -> setOf("address", "port", "method", "password", "uot", "level", "email")
            },
        )
        when (protocol) {
            "vless",
            "vmess",
            -> {
                fields(
                    user,
                    if (protocol == "vless") {
                        setOf("id", "flow", "encryption", "level", "email")
                    } else {
                        setOf("id", "security", "alterId", "level", "email")
                    },
                )
                node.put("uuid", required(user, "id"))
                if (protocol == "vless") {
                    ProxyLinkParser.applyVlessEncryption(
                        node,
                        user.optString("encryption"),
                        user.optString("flow"),
                    )
                    user.optString("flow").takeIf { it.isNotBlank() }?.let { node.put("flow", it) }
                } else {
                    node.put("security", user.optString("security", "auto"))
                    if (user.has("alterId")) node.put("alter_id", user.getInt("alterId"))
                }
            }
            "trojan" -> node.put("password", required(server, "password"))
            "shadowsocks" -> {
                node
                    .put("method", required(server, "method"))
                    .put("password", required(server, "password"))
                require(!bool(server, "uot")) { "Unsupported Xray Shadowsocks UDP-over-TCP" }
            }
            "socks",
            "http",
            -> {
                if (protocol == "socks") node.put("version", "5")
                val auth =
                    if (protocol == "http") {
                        server.optJSONArray("users")?.let {
                            require(it.length() <= 1) { "Unsupported multiple HTTP users" }
                            it.optJSONObject(0)
                        }
                    } else {
                        user
                    }
                auth?.let {
                    fields(it, setOf("user", "pass", "level"))
                    for ((from, to) in mapOf("user" to "username", "pass" to "password")) {
                        if (
                            it.has(from)
                        ) {
                            node.put(to, it.get(from))
                        }
                    }
                }
            }
        }
        val stream = source.optJSONObject("streamSettings") ?: JSONObject()
        fields(
            stream,
            setOf(
                "network",
                "security",
                "tlsSettings",
                "realitySettings",
                "tcpSettings",
                "rawSettings",
                "wsSettings",
                "grpcSettings",
                "httpSettings",
                "httpupgradeSettings",
                "xhttpSettings",
                "splithttpSettings",
                "finalmask",
            ),
        )
        val security = stream.optString("security", "none")
        require(security in setOf("none", "tls", "reality")) { "Unsupported Xray security" }
        require(protocol != "trojan" || security != "none") { "Missing Trojan TLS configuration" }
        require(!stream.has("tlsSettings") || security == "tls") {
            "Invalid Xray TLS options for selected security"
        }
        require(!stream.has("realitySettings") || security == "reality") {
            "Invalid Xray Reality options for selected security"
        }
        if (security != "none") {
            require(protocol != "socks") { "Unsupported SOCKS TLS transport" }
            val options =
                stream.optJSONObject(
                    if (security == "reality") "realitySettings" else "tlsSettings",
                ) ?: JSONObject()
            fields(
                options,
                setOf("serverName", "allowInsecure", "alpn", "fingerprint") +
                    if (security == "reality") {
                        setOf("publicKey", "shortId", "spiderX", "show", "fingerprint", "serverName")
                    } else {
                        setOf(
                            "pinnedPeerCertSha256",
                            "verifyPeerCertByName",
                            "enableSessionResumption",
                        )
                    },
            )
            val tls =
                JSONObject()
                    .put("enabled", true)
                    .put("server_name", options.optString("serverName", node.getString("server")))
            if (options.has("allowInsecure")) tls.put("insecure", bool(options, "allowInsecure"))
            if (options.has("alpn")) tls.put("alpn", options.getJSONArray("alpn"))
            options
                .optString("fingerprint")
                .takeIf { it.isNotBlank() }
                ?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
            if (security == "tls") {
                require(
                    !options.has("enableSessionResumption") ||
                        !bool(options, "enableSessionResumption"),
                ) {
                    "Unsupported TLS session resumption"
                }
                ProxyLinkParser.applyXrayVerification(
                    tls,
                    options.optString("pinnedPeerCertSha256"),
                    options.optString("verifyPeerCertByName"),
                )
            }
            if (security == "reality") {
                ProxyLinkParser.applyReality(
                    tls,
                    required(options, "publicKey"),
                    options.optString("shortId"),
                    spiderX = options.optString("spiderX").ifBlank { null },
                    fingerprint = options.optString("fingerprint").ifBlank { null },
                )
            }
            node.put("tls", tls)
        }
        val network = stream.optString("network", "tcp")
        if (protocol !in setOf("vless", "vmess", "trojan")) {
            require(network in setOf("tcp", "raw")) { "Unsupported Xray transport for proxy type" }
        }
        val key =
            when (network) {
                "tcp" -> "tcpSettings"
                "raw" -> "rawSettings"
                "ws" -> "wsSettings"
                "grpc" -> "grpcSettings"
                "http",
                "h2",
                -> "httpSettings"
                "httpupgrade" -> "httpupgradeSettings"
                "xhttp" -> "xhttpSettings"
                "splithttp" -> "splithttpSettings"
                else -> error("Unsupported Xray transport")
            }
        require(
            listOf(
                "tcpSettings",
                "rawSettings",
                "wsSettings",
                "grpcSettings",
                "httpSettings",
                "httpupgradeSettings",
                "xhttpSettings",
                "splithttpSettings",
            )
                .none { it != key && stream.has(it) },
        ) {
            "Invalid Xray transport options for selected network"
        }
        val options = stream.optJSONObject(key) ?: JSONObject()
        when (network) {
            "tcp",
            "raw",
            -> {
                fields(options, setOf("header"))
                val header = options.optJSONObject("header")
                require(header == null || header.optString("type", "none") == "none") {
                    "Unsupported Xray TCP HTTP header; not equivalent to HTTP/2 transport"
                }
            }
            "ws" -> {
                fields(options, setOf("path", "headers", "maxEarlyData", "earlyDataHeaderName"))
                val transport =
                    JSONObject().put("type", "ws").put("path", options.optString("path", "/"))
                for (
                (from, to) in
                mapOf(
                    "headers" to "headers",
                    "maxEarlyData" to "max_early_data",
                    "earlyDataHeaderName" to "early_data_header_name",
                )
                ) {
                    if (options.has(from)) transport.put(to, options.get(from))
                }
                node.put("transport", transport)
            }
            "grpc" -> {
                fields(options, setOf("serviceName", "multiMode"))
                require(!bool(options, "multiMode")) { "Unsupported Xray gRPC multiMode" }
                node.put(
                    "transport",
                    JSONObject()
                        .put("type", "grpc")
                        .put("service_name", options.optString("serviceName")),
                )
            }
            "http",
            "h2",
            "httpupgrade",
            -> {
                fields(
                    options,
                    if (network == "httpupgrade") {
                        setOf("host", "path")
                    } else {
                        setOf("host", "path", "method", "headers")
                    },
                )
                val transport =
                    JSONObject(options.toString())
                        .put("type", if (network == "httpupgrade") "httpupgrade" else "http")
                node.put("transport", transport)
            }
            "xhttp",
            "splithttp",
            -> {
                val params = mutableMapOf<String, String>()
                for (field in listOf("host", "path", "mode")) {
                    if (options.has(field)) {
                        params[field] = options.getString(field)
                    }
                }
                val extra = JSONObject(options.toString())
                listOf("host", "path", "mode").forEach { extra.remove(it) }
                val nested = extra.remove("extra") as? JSONObject
                nested?.keys()?.forEach {
                    require(!extra.has(it)) { "Invalid duplicate XHTTP option" }
                    extra.put(it, nested.get(it))
                }
                params["extra"] = extra.toString()
                node.put(
                    "transport",
                    ProxyLinkParser.transport(network, params, node.getString("server")),
                )
            }
        }
        source.optJSONObject("mux")?.let { mux ->
            fields(mux, setOf("enabled", "concurrency", "xudpConcurrency", "xudpProxyUDP443"))
            require(!bool(mux, "enabled")) {
                "Unsupported Xray Mux.Cool; not equivalent to sing-box multiplex"
            }
        }
        stream.optJSONObject("finalmask")?.let { ProxyLinkParser.applyFinalMask(node, it) }
        require(!stream.has("finalmask") || stream.optJSONObject("finalmask") != null) {
            "Invalid finalmask object"
        }
        return QuicTlsCompatibility.normalize(node)
    }
}
