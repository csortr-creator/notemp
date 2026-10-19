package io.nekohasekai.sfa.utils

/**
 * Result of a single outbound URLTest probe.
 *
 * Core method (sing-box urltest): TCP dial via outbound + HTTP HEAD to
 * [DEFAULT_PROBE_URL], with a second origin inside the same timeout when the
 * default origin stalls. Explicit URLs are retained. HTTP status codes are **not** checked by
 * core — a 403/404/405 after a successful response still yields SUCCESS with delay.
 * Failures return an error from libbox; Kotlin must not discard it as delay=0 only.
 */
enum class ProbeStatus {
    UNTESTED,
    TESTING,
    SUCCESS,
    TIMEOUT,
    CONNECT_TIMEOUT,
    DNS_ERROR,
    HANDSHAKE_ERROR,
    HTTP_ERROR,
    PROBE_ERROR,
    CANCELLED,
}

data class ProbeOutcome(
    val status: ProbeStatus,
    val delayMs: Int = 0,
    /** Short, non-sensitive diagnostic for logs/UI (no credentials). */
    val detail: String = "",
) {
    val isSuccess: Boolean
        get() = status == ProbeStatus.SUCCESS && delayMs > 0

    companion object {
        /** Default URL when libbox/sing-box receives an empty test URL. */
        const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"

        fun success(delayMs: Int) = ProbeOutcome(ProbeStatus.SUCCESS, delayMs.coerceAtLeast(0), "")

        fun untested() = ProbeOutcome(ProbeStatus.UNTESTED)

        /**
         * Map a libbox/platform throwable to a category.
         * Prefer exception **types** and stable English substrings from the Go/mobile stack;
         * do not rely on localized UI strings.
         */
        fun fromThrowable(error: Throwable): ProbeOutcome {
            if (error is java.util.concurrent.CancellationException ||
                error is kotlinx.coroutines.CancellationException
            ) {
                return ProbeOutcome(ProbeStatus.CANCELLED, detail = "cancelled")
            }
            val chain = generateSequence(error) { it.cause }.toList()
            val typeNames = chain.joinToString(" ") { it.javaClass.name }
            val messages =
                chain.mapNotNull { it.message?.lowercase() }.joinToString(" ")

            fun has(vararg tokens: String) = tokens.any { t -> typeNames.contains(t, ignoreCase = true) || messages.contains(t) }

            val status =
                when {
                    chain.any { it is java.util.concurrent.CancellationException } ||
                        has("context canceled", "context cancelled", "code = canceled", "code = cancelled") ->
                        ProbeStatus.CANCELLED
                    // Timeouts first: core often reports "dns: exchange failed … deadline exceeded"
                    // which is a timeout on the DNS exchange, not NXDOMAIN.
                    has("dial tcp ") &&
                        has("deadline exceeded", "i/o timeout", "timed out") -> ProbeStatus.CONNECT_TIMEOUT
                    has(
                        "SocketTimeoutException",
                        "TimeoutException",
                        "deadline exceeded",
                        "context deadline",
                        "i/o timeout",
                        "timed out",
                    ) || (has("timeout") && !has("UnknownHostException")) -> ProbeStatus.TIMEOUT
                    has(
                        "UnknownHostException",
                        "NoAddressAssociatedWithHostname",
                        "no such host",
                        "no address associated",
                        "dns lookup",
                        "android_getaddrinfo",
                    ) || (has("dns") && has("fail", "error", "exchange", "resolve")) ->
                        ProbeStatus.DNS_ERROR
                    has(
                        "SSLException",
                        "SSLHandshakeException",
                        "CertificateException",
                        "TLS",
                        "handshake",
                        "certificate",
                        "x509",
                        "reality",
                    ) -> ProbeStatus.HANDSHAKE_ERROR
                    has("UnexpectedResponse", "HTTP status", "http error", "bad response") ->
                        ProbeStatus.HTTP_ERROR
                    else -> ProbeStatus.PROBE_ERROR
                }

            val detail =
                (error.message ?: error.javaClass.simpleName)
                    .lineSequence()
                    .firstOrNull()
                    .orEmpty()
                    .take(160)
            return ProbeOutcome(status, delayMs = 0, detail = detail)
        }

        /** Preserve a precise individual failure when the native stream replays
         * the same second-resolution zero-delay history without an error code. */
        fun fromOnlineHistory(
            delay: Int,
            timeSeconds: Long,
            cached: ProfileLatencyCache.Result?,
        ): ProbeOutcome {
            if (delay > 0) return success(delay)
            val precise = cached?.status !in setOf(
                null, ProbeStatus.UNTESTED, ProbeStatus.TESTING,
                ProbeStatus.SUCCESS, ProbeStatus.CANCELLED, ProbeStatus.PROBE_ERROR,
            )
            val cachedMillis = cached?.time?.let { if (it < 100_000_000_000L) it * 1000 else it } ?: 0L
            if (cached != null && precise && cachedMillis >= timeSeconds * 1000) {
                return ProbeOutcome(cached.status, 0, cached.detail)
            }
            return ProbeOutcome(ProbeStatus.PROBE_ERROR)
        }
    }
}
