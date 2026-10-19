package io.nekohasekai.sfa.utils

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeOutcomeTest {

    @Test
    fun defaultProbeUrlIsGstaticGenerate204() {
        assertEquals("https://www.gstatic.com/generate_204", ProbeOutcome.DEFAULT_PROBE_URL)
    }

    @Test
    fun successOutcome() {
        val o = ProbeOutcome.success(85)
        assertTrue(o.isSuccess)
        assertEquals(ProbeStatus.SUCCESS, o.status)
        assertEquals(85, o.delayMs)
    }

    @Test
    fun classifiesUnknownHostAsDns() {
        val o = ProbeOutcome.fromThrowable(UnknownHostException("no such host: example.test"))
        assertEquals(ProbeStatus.DNS_ERROR, o.status)
        assertEquals(0, o.delayMs)
    }

    @Test
    fun classifiesSocketTimeout() {
        val o = ProbeOutcome.fromThrowable(SocketTimeoutException("timeout"))
        assertEquals(ProbeStatus.TIMEOUT, o.status)
    }

    @Test
    fun classifiesDeadlineExceededMessage() {
        val o =
            ProbeOutcome.fromThrowable(
                RuntimeException("dns: exchange failed for example.test: context deadline exceeded"),
            )
        assertEquals(ProbeStatus.TIMEOUT, o.status)
    }

    @Test
    fun classifiesHandshakeKeywords() {
        val o = ProbeOutcome.fromThrowable(javax.net.ssl.SSLHandshakeException("handshake failed"))
        assertEquals(ProbeStatus.HANDSHAKE_ERROR, o.status)
    }

    @Test
    fun unknownMapsToProbeErrorWithDetail() {
        val o = ProbeOutcome.fromThrowable(IllegalStateException("outbound not found: x"))
        assertEquals(ProbeStatus.PROBE_ERROR, o.status)
        assertTrue(o.detail.contains("outbound not found"))
    }

    @Test
    fun grpcContextCancellationIsNotServerFailure() {
        val outcome = ProbeOutcome.fromThrowable(
            RuntimeException("url test: rpc error: code = Canceled desc = context canceled"),
        )
        assertEquals(ProbeStatus.CANCELLED, outcome.status)
        assertFalse(outcome.isSuccess)
    }

    @Test
    fun wrappedCoroutineCancellationIsStillCancellation() {
        val outcome = ProbeOutcome.fromThrowable(
            RuntimeException("probe stopped", java.util.concurrent.CancellationException("cancelled")),
        )
        assertEquals(ProbeStatus.CANCELLED, outcome.status)
    }

    @Test
    fun latencyCacheStoresStatus() {
        val cache = ProfileLatencyCache()
        cache.put(1L, "node", "fp", 0, 1000L, ProbeStatus.DNS_ERROR, "no such host")
        val r = cache.get(1L, "node", "fp")!!
        assertEquals(ProbeStatus.DNS_ERROR, r.status)
        assertEquals(0, r.delay)
        assertEquals("", r.detail)
        assertFalse(cache.encode().contains("no such host"))
    }

    @Test
    fun socketConnectTimeoutIsDistinctFromResponseTimeout() {
        val tcp = ProbeOutcome.fromThrowable(RuntimeException("url test: rpc error: code = DeadlineExceeded desc = dial rmnet_data3 (32): dial tcp 192.0.2.1:8443: i/o timeout"))
        assertEquals(ProbeStatus.CONNECT_TIMEOUT, tcp.status)
        val response = ProbeOutcome.fromThrowable(RuntimeException("urltest http-response: context deadline exceeded"))
        assertEquals(ProbeStatus.TIMEOUT, response.status)
    }

    @Test
    fun nativeReplayRetainsPreciseFailureButNewHistoryReplacesIt() {
        // Legacy seconds and current milliseconds are both accepted.
        val fresh = ProfileLatencyCache.Result(0, 1_800_000_000_500L, ProbeStatus.CONNECT_TIMEOUT)
        assertEquals(ProbeStatus.CONNECT_TIMEOUT, ProbeOutcome.fromOnlineHistory(0, 1_800_000_000L, fresh.copy(time = 1_800_000_000L)).status)
        assertEquals(ProbeStatus.CONNECT_TIMEOUT, ProbeOutcome.fromOnlineHistory(0, 1_800_000_000L, fresh).status)
        assertEquals(ProbeStatus.PROBE_ERROR, ProbeOutcome.fromOnlineHistory(0, 1_800_000_001L, fresh).status)
        assertEquals(ProbeStatus.SUCCESS, ProbeOutcome.fromOnlineHistory(70, 1_800_000_001L, fresh).status)
    }
}
