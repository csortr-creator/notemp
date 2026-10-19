package io.nekohasekai.sfa.utils

import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubscriptionFetchRetryTest {

    private class TlsHandshakeTimeout : Exception("net/http: TLS handshake timeout")

    private class AuthForbidden : Exception("HTTP status 403 Forbidden")

    private class StatusNotFound : Exception("HTTP status 404")

    private class ConnectionReset : Exception("connection reset by peer")

    @Test
    fun tlsTimeoutIsRetriable() {
        assertTrue(SubscriptionFetchRetry.isRetriable(TlsHandshakeTimeout()))
        assertTrue(SubscriptionFetchRetry.isRetriable(SocketTimeoutException("read timed out")))
        assertTrue(SubscriptionFetchRetry.isRetriable(ConnectionReset()))
    }

    @Test
    fun authAndNotFoundAreNotRetriable() {
        assertFalse(SubscriptionFetchRetry.isRetriable(AuthForbidden()))
        assertFalse(SubscriptionFetchRetry.isRetriable(StatusNotFound()))
    }

    @Test
    fun recoversAfterSingleTlsTimeout() {
        val calls = AtomicInteger(0)
        val result =
            SubscriptionFetchRetry.run(sleep = {}) {
                if (calls.incrementAndGet() == 1) throw TlsHandshakeTimeout()
                "ok-body"
            }
        assertEquals("ok-body", result)
        assertEquals(2, calls.get())
    }

    @Test
    fun authFailsImmediatelyWithoutRetry() {
        val calls = AtomicInteger(0)
        try {
            SubscriptionFetchRetry.run(sleep = {}) {
                calls.incrementAndGet()
                throw AuthForbidden()
            }
            fail("expected AuthForbidden")
        } catch (e: AuthForbidden) {
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun exhaustsAttemptsOnPersistentTlsTimeout() {
        val calls = AtomicInteger(0)
        val sleeps = mutableListOf<Long>()
        try {
            SubscriptionFetchRetry.run(sleep = { sleeps.add(it) }) {
                calls.incrementAndGet()
                throw TlsHandshakeTimeout()
            }
            fail("expected TlsHandshakeTimeout")
        } catch (e: TlsHandshakeTimeout) {
            assertEquals(SubscriptionFetchRetry.MAX_ATTEMPTS, calls.get())
            assertEquals(SubscriptionFetchRetry.MAX_ATTEMPTS - 1, sleeps.size)
        }
    }

    @Test
    fun delayIsExponentialWithJitter() {
        val fixed = Random(0)
        val d1 = SubscriptionFetchRetry.delayBeforeNextAttemptMs(1, fixed)
        val d2 = SubscriptionFetchRetry.delayBeforeNextAttemptMs(2, fixed)
        assertTrue(d1 in 1000L..1250L)
        assertTrue(d2 in 2000L..2250L)
        assertTrue(d2 > d1)
    }

    @Test
    fun attemptCallbackDoesNotExposeUrl() {
        val secret = "https://panel.example/sub/private-token-abc?user=secret"
        val logs = mutableListOf<String>()
        try {
            SubscriptionFetchRetry.run(
                sleep = {},
                onAttempt = { attempt, code, willRetry ->
                    logs.add("attempt=$attempt code=$code willRetry=$willRetry url=$secret")
                },
            ) {
                throw TlsHandshakeTimeout()
            }
            fail("expected timeout")
        } catch (_: TlsHandshakeTimeout) {
        }
        // Policy itself must not put URL into code; test that classify codes are clean
        assertTrue(logs.isNotEmpty())
        for (line in logs) {
            // The test injects secret only to assert callers must not rely on logging URL —
            // logCode must not contain the secret path.
            val code = line.substringAfter("code=").substringBefore(" ")
            assertFalse(code.contains("private-token"))
            assertFalse(code.contains("panel.example"))
            assertTrue(code.startsWith("NET_"))
        }
        val classified = NetworkErrorPresentation.classify(TlsHandshakeTimeout())
        assertEquals(NetworkErrorKind.TLS_TIMEOUT, classified.kind)
        assertFalse(classified.code.contains("http"))
        assertFalse(NetworkErrorPresentation.logCode(Exception("GET \"$secret\" TLS handshake timeout"))
            .contains("private-token"))
    }

    @Test
    fun onAttemptReportsWillRetryCorrectly() {
        val flags = mutableListOf<Boolean>()
        try {
            SubscriptionFetchRetry.run(
                sleep = {},
                onAttempt = { _, _, willRetry -> flags.add(willRetry) },
            ) {
                throw TlsHandshakeTimeout()
            }
        } catch (_: TlsHandshakeTimeout) {
        }
        assertEquals(listOf(true, true, false), flags)
    }
    @Test fun cancellationAndInterruptionNeverRetryOrLogFailure() {
        for (error in listOf(java.util.concurrent.CancellationException("connection reset by peer"),
                InterruptedException("read timed out"))) {
            var calls = 0
            var logged = false
            assertFalse(SubscriptionFetchRetry.isRetriable(error))
            try {
                SubscriptionFetchRetry.run(sleep = { fail("Must not sleep") },
                    onAttempt = { _, _, _ -> logged = true }) { calls++; throw error }
                fail("Expected cancellation or interruption")
            } catch (caught: Exception) { assertTrue(caught === error) }
            assertEquals(1, calls)
            assertFalse(logged)
        }
    }

    @Test fun fatalVmErrorsAreNotTreatedAsNetworkFailures() {
        val fatal = AssertionError("connection reset by peer")
        var calls = 0
        try {
            SubscriptionFetchRetry.run(sleep = { fail("Must not retry VM errors") }) { calls++; throw fatal }
            fail("Expected fatal error")
        } catch (caught: AssertionError) { assertTrue(caught === fatal) }
        assertEquals(1, calls)
    }

    @Test fun cancelledOperationDoesNotRetryAConnectionErrorFromClosingClient() {
        var cancelled = false
        var logged = false
        try {
            SubscriptionFetchRetry.run(sleep = { fail("Must not back off") },
                onAttempt = { _, _, _ -> logged = true },
                checkActive = { if (cancelled) throw java.util.concurrent.CancellationException() }) {
                cancelled = true
                throw java.io.IOException("connection reset by peer")
            }
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertFalse(logged)
    }

}
