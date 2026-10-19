package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionRefreshHardeningTest {
    private val link = "ss://aes-128-gcm:password@example.org:8388#Good"
    private fun outbounds(content: String) = JSONObject(content).getJSONArray("outbounds").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("tag") }
    }

    @Test fun nativeArrayPreservesReservedTagsAndReferences() {
        val source = JSONArray()
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
            .put(JSONObject().put("type", "shadowsocks").put("tag", "A%20B")
                .put("server", "example.org").put("server_port", 8388)
                .put("method", "aes-128-gcm").put("password", "password").put("detour", "direct"))
            .put(JSONObject().put("type", "selector").put("tag", "proxy")
                .put("outbounds", JSONArray().put("A%20B").put("direct")).put("default", "A%20B"))
        val config = SubscriptionContentParser().parse(source.toString()).config
        java.io.File("build/native-configs").mkdirs()
        java.io.File("build/native-configs/import-native-reference-tags.json").writeText(config)
        val nodes = outbounds(config)
        assertTrue(nodes.containsKey("A%20B"))
        assertEquals("direct", nodes.getValue("A%20B").getString("detour"))
        assertEquals("direct", nodes.getValue("direct").getString("type"))
        assertEquals("A%20B", nodes.getValue("proxy").getJSONArray("outbounds").getString(0))
    }

    @Test fun duplicateNativeTagsFailBeforeCreatingAmbiguousReferences() {
        val node = ProxyLinkParser.shadowsocks(link)
        try {
            SubscriptionContentParser().parse(JSONArray().put(node).put(JSONObject(node.toString())).toString())
            fail("Ambiguous native tags must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun decodedBomDoesNotHideNativeOrClashFormat() {
        for (content in listOf(
            "\uFEFF{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}",
            "\uFEFFproxies:\n  - {name: Test, type: ss, server: example.org, port: 8388, cipher: aes-128-gcm, password: password}",
        )) {
            val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
            assertTrue(SubscriptionContentParser.supports(encoded))
            assertTrue(SubscriptionContentParser().parse(encoded).report.issues.isEmpty())
        }
    }

    @Test fun oversizedLinkListIsRejectedBeforeNodeValidation() {
        var checks = 0
        try {
            SubscriptionContentParser(validateNode = { checks++ }).parse(List(10001) { link }.joinToString("\n"))
            fail("Expected entry limit")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, checks)
    }

    @Test fun sizeLimitCountsUtf8BytesNotCharacters() {
        val input = "#" + "я".repeat(4 * 1024 * 1024 + 1)
        try {
            SubscriptionContentParser().parse(input)
            fail("Expected byte limit")
        } catch (e: SubscriptionResponseFailure) {
            assertEquals(SubscriptionResponseProblem.TOO_LARGE, e.problem)
        }
    }
    @Test fun streamedSizeFailureUsesImportLimitDiagnostic() {
        val nativeLimit = SubscriptionStageFailure(SubscriptionStage.FETCH,
            SubscriptionResponseFailure(SubscriptionResponseProblem.TOO_LARGE))
        assertEquals("IMPORT_TOO_LARGE: ответ превышает 8 МиБ", SubscriptionDiagnostics.reason(nativeLimit))
        assertFalse(SubscriptionFetchRetry.isRetriable(nativeLimit))
    }

    @Test fun largeNativeArrayIsRejectedBeforeIndependentValidation() {
        val node = JSONObject().put("type", "direct").put("tag", "direct")
        val nodes = JSONArray()
        repeat(10001) { nodes.put(JSONObject(node.toString()).put("tag", "n$it")) }
        for (source in listOf(nodes.toString(), JSONObject().put("outbounds", nodes).toString())) {
            try { SubscriptionContentParser().parse(source); fail("Expected entry limit") }
            catch (_: IllegalArgumentException) { }
        }
    }

}
