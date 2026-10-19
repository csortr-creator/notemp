package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test

class ProfileLatencyCacheTest {
    @Test
    fun switchingSubscriptionsDoesNotMixIdenticalNames() {
        val cache = ProfileLatencyCache()
        cache.put(1, "Germany", "a", 120, 1000)
        cache.put(2, "Germany", "b", 240, 2000)
        assertEquals(120, cache.get(1, "Germany", "a")?.delay)
        assertEquals(240, cache.get(2, "Germany", "b")?.delay)
        assertNull(cache.get(2, "Germany", "a"))
    }

    @Test
    fun persistsBothSuccessAndUnavailableResults() {
        val cache = ProfileLatencyCache()
        cache.put(1, "A", "a", 120, 1000)
        cache.put(2, "B", "b", 0, 2000)
        val restored = ProfileLatencyCache(cache.encode())
        assertEquals(
            ProfileLatencyCache.Result(120, 1000, ProbeStatus.SUCCESS, ""),
            restored.get(1, "A", "a"),
        )
        assertEquals(
            ProfileLatencyCache.Result(0, 2000, ProbeStatus.PROBE_ERROR, ""),
            restored.get(2, "B", "b"),
        )
    }

    @Test
    fun putDoesNotPersistDetailText() {
        val cache = ProfileLatencyCache()
        cache.put(
            1,
            "A",
            "fp",
            0,
            1000L,
            ProbeStatus.DNS_ERROR,
            "no such host: secret.example.invalid",
        )
        val raw = cache.encode()
        assertFalse(raw.contains("secret.example"))
        assertFalse(raw.contains("\"detail\""))
        val r = cache.get(1, "A", "fp")!!
        assertEquals(ProbeStatus.DNS_ERROR, r.status)
        assertEquals("", r.detail)
    }

    @Test
    fun legacyDetailInStoredJsonIsIgnoredOnReadAndStrippedOnEncode() {
        val raw =
            """{"1":{"A":{"fingerprint":"fp","delay":0,"time":1000,"status":"TIMEOUT","detail":"dns: exchange failed example.com"}}}"""
        val cache = ProfileLatencyCache(raw)
        val r = cache.get(1, "A", "fp")!!
        assertEquals(ProbeStatus.TIMEOUT, r.status)
        assertEquals("", r.detail)
        val encoded = cache.encode()
        assertFalse(encoded.contains("example.com"))
        assertFalse(encoded.contains("\"detail\""))
        assertTrue(encoded.contains("TIMEOUT"))
    }

    @Test
    fun changedOrRemovedServersLoseTheirMeasurements() {
        val cache = ProfileLatencyCache()
        cache.put(1, "A", "old", 120, 1000)
        cache.put(1, "B", "keep", 240, 2000)
        cache.put(1, "C", "removed", 360, 3000)
        cache.retain(1, mapOf("A" to "new", "B" to "keep"))
        assertNull(cache.get(1, "A", "old"))
        assertNull(cache.get(1, "A", "new"))
        assertNull(cache.get(1, "C", "removed"))
        assertEquals(240, cache.get(1, "B", "keep")?.delay)
    }

    @Test
    fun fingerprintsIgnoreJsonFormattingButDetectChangedEndpoint() {
        val a =
            ProfileLatencyCache.fingerprints(
                """{"outbounds":[{"tag":"A","server":"one","tls":{"enabled":true,"server_name":"example.com"}}]}"""
            )
        val b =
            ProfileLatencyCache.fingerprints(
                """{ "outbounds": [ {"tls":{"server_name":"example.com","enabled":true},"server":"one","tag":"A"} ] }"""
            )
        val changed =
            ProfileLatencyCache.fingerprints(
                """{"outbounds":[{"tag":"A","server":"two","tls":{"enabled":true,"server_name":"example.com"}}]}"""
            )
        assertEquals(a, b)
        assertNotEquals(a, changed)
        val cache = ProfileLatencyCache()
        cache.put(1, "A", a.getValue("A"), 120, 1000)
        assertFalse(cache.encode().contains("example.com"))
        assertFalse(cache.encode().contains("one"))
    }

    @Test
    fun invalidStoredJsonDoesNotBlockLoading() {
        assertNull(ProfileLatencyCache("bad json").get(1, "A", "hash"))
    }
    @Test fun unchangedRefreshDoesNotRequestPersistentCacheWrite() {
        val cache = ProfileLatencyCache()
        assertFalse(cache.retain(1, emptyMap()))
        cache.put(1, "A", "same", 120, 1000)
        assertFalse(cache.retain(1, mapOf("A" to "same")))
        assertTrue(cache.retain(1, mapOf("A" to "changed")))
        assertFalse(cache.retain(1, mapOf("A" to "changed")))
    }

}
