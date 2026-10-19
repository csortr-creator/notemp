package io.nekohasekai.sfa.utils

import kotlin.math.min
import kotlin.random.Random

/**
 * Retry policy for subscription HTTP fetch (TLS/timeout resilience on mobile).
 *
 * Does not log or retain URLs. Classification uses [NetworkErrorPresentation] only.
 */
object SubscriptionFetchRetry {
    const val MAX_ATTEMPTS: Int = 3

    /** Base delays after attempt 1 and 2 failures (ms): 1s → 2s (next would be 4s). */
    private val BASE_DELAYS_MS = longArrayOf(1_000L, 2_000L)

    /**
     * Transient network failures worth retrying. Auth/status/unknown are not retried.
     * TLS (non-timeout handshake/cert) is not retried — usually not transient.
     */
    fun isRetriable(error: Throwable): Boolean {
        if (error is java.util.concurrent.CancellationException || error is InterruptedException) return false
        return when (NetworkErrorPresentation.classify(error).kind) {
            NetworkErrorKind.TLS_TIMEOUT,
            NetworkErrorKind.TIMEOUT,
            NetworkErrorKind.CONNECTION,
            NetworkErrorKind.DNS,
            -> true
            NetworkErrorKind.UTLS_UNSUPPORTED,
            NetworkErrorKind.REALITY_VERIFICATION,
            NetworkErrorKind.TLS,
            NetworkErrorKind.HTTP_AUTH,
            NetworkErrorKind.HTTP_STATUS,
            NetworkErrorKind.UNKNOWN,
            -> false
        }
    }

    /**
     * Delay before the next attempt after a failed [failedAttempt] (1-based).
     * Exponential base with small positive jitter; never negative.
     */
    fun delayBeforeNextAttemptMs(failedAttempt: Int, random: Random = Random.Default): Long {
        if (failedAttempt < 1 || failedAttempt > BASE_DELAYS_MS.size) return 0L
        val base = BASE_DELAYS_MS[failedAttempt - 1]
        val jitter = random.nextLong(0L, min(250L, base / 4 + 1))
        return base + jitter
    }

    /**
     * Execute [block] up to [MAX_ATTEMPTS] times.
     * @param sleep invoked with delay ms between attempts (injectable for tests)
     * @param onAttempt optional safe diagnostic callback (attempt 1..N, error code) — no URL
     */
    fun <T> run(
        sleep: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
        onAttempt: ((attempt: Int, code: String, willRetry: Boolean) -> Unit)? = null,
        block: () -> T,
    ): T {
        var last: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return block()
            } catch (t: Exception) {
                if (t is java.util.concurrent.CancellationException || t is InterruptedException) throw t
                last = t
                val code = NetworkErrorPresentation.logCode(t)
                val retry = attempt < MAX_ATTEMPTS && isRetriable(t)
                onAttempt?.invoke(attempt, code, retry)
                if (!retry) throw t
                sleep(delayBeforeNextAttemptMs(attempt))
            }
        }
        throw last ?: error("SubscriptionFetchRetry: unreachable")
    }
}
