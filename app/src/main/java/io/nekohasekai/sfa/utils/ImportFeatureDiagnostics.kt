package io.nekohasekai.sfa.utils

/** Exact protocol capability vocabulary, shared by live and persisted reports.
 * Provider-controlled exception text is never rendered as a feature description.
 */
internal object ImportFeatureDiagnostics {
    private val knownReasons = setOf(
        "Unsupported Clash proxy type",
        "Unsupported Clash transport",
        "Unsupported Hysteria binary auth",
        "Unsupported Hysteria binary auth; use auth-str",
        "Unsupported Hysteria obfuscation",
        "Unsupported Hysteria parameter",
        "Unsupported Hysteria security",
        "Unsupported Hysteria session resumption",
        "Unsupported Hysteria transport",
        "Unsupported Hysteria2 obfuscation",
        "Unsupported IP version",
        "Unsupported QUIC uTLS fingerprint",
        "Unsupported SOCKS TLS transport",
        "Unsupported SOCKS share-link parameter",
        "Unsupported Shadowsocks encryption method",
        "Unsupported Shadowsocks parameter",
        "Unsupported Shadowsocks plugin",
        "Unsupported TLS options for plain HTTP proxy",
        "Unsupported TLS session resumption",
        "Unsupported TLS wrapper for this proxy type",
        "Unsupported UDP option for this proxy type",
        "Unsupported VLESS encryption profile",
        "Unsupported VLESS flow",
        "Unsupported XHTTP download security",
        "Unsupported XHTTP download transport",
        "Unsupported XHTTP mode",
        "Unsupported XHTTP parameter",
        "Unsupported XHTTP xmux parameter",
        "Unsupported Xray Hysteria version",
        "Unsupported Xray Mux.Cool; not equivalent to sing-box multiplex",
        "Unsupported Xray Shadowsocks UDP-over-TCP",
        "Unsupported Xray TCP HTTP header; not equivalent to HTTP/2 transport",
        "Unsupported Xray gRPC multiMode",
        "Unsupported Xray outbound protocol",
        "Unsupported Xray security",
        "Unsupported Xray transport",
        "Unsupported Xray transport for proxy type",
        "Unsupported conflicting Hysteria2 Chrome fingerprint options",
        "Unsupported finalmask QUIC options for proxy type",
        "Unsupported finalmask QUIC parameter",
        "Unsupported finalmask Salamander settings",
        "Unsupported finalmask TCP header item",
        "Unsupported finalmask TCP header settings",
        "Unsupported finalmask TCP mask",
        "Unsupported finalmask TCP mask chain",
        "Unsupported finalmask TCP transport combination",
        "Unsupported finalmask UDP mask",
        "Unsupported finalmask UDP mask chain or conflicting obfuscation",
        "Unsupported finalmask UDP transport combination",
        "Unsupported finalmask congestion control",
        "Unsupported finalmask debug mode",
        "Unsupported finalmask parameter",
        "Unsupported finalmask port hopping parameter",
        "Unsupported multiple HTTP paths",
        "Unsupported multiple HTTP users",
        "Unsupported provider-only Clash configuration; use a direct server subscription URL",
        "Unsupported proxy protocol",
        "Unsupported share-link parameter",
        "Unsupported transport",
        "Unsupported transport for proxy type",
    )
    fun detail(reason: String): String? {
        val parameter = Regex("Неподдерживаемый параметр: ([A-Za-z][A-Za-z0-9_.-]{0,49})")
            .matchEntire(reason)?.groupValues?.get(1)
        if (parameter != null && SubscriptionDiagnostics.issueName(
                SubscriptionImportIssue(0, parameter, ""),
            ) == parameter) return "Неподдерживаемый параметр: $parameter [FEATURE_PARAMETER]"
        if (reason !in knownReasons) return null
        val feature = reason.removePrefix("Unsupported ")
        val code = "FEATURE_" + feature.uppercase(java.util.Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        return "Не поддерживается: $feature [$code]"
    }
}
