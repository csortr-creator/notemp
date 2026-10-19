package io.nekohasekai.sfa.utils

/**
 * A+ compatibility layer primitives: support levels and share-link field sets.
 * Does not claim runtime parity; tracks parser → mapping intent only.
 */
internal object XrayCompatibility {
    enum class Level {
        FULL,
        EXTENDED,
        PARTIAL,
        IGNORED,
        UNSUPPORTED,
    }

    /** Share-link query keys we map into sing-box outbound/TLS/transport. */
    val vlessShareKnown =
        setOf(
            "type",
            "security",
            "sni",
            "host",
            "fp",
            "support-x25519mlkem768",
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
            "encryption",
            "fm",
            "flow",
            "pbk",
            "sid",
            "packetEncoding",
            "spiderX",
            "spx",
            "headerType",
            "header_type",
            "seed",
            "authority",
        )

    fun unknownOptionalNote(keys: Collection<String>): String? {
        if (keys.isEmpty()) return null
        val sorted = keys.sorted()
        return "Частичная совместимость: неизвестные параметры сохранены в отчёте и не влияют на конфиг: ${sorted.joinToString(", ")}"
    }
}
