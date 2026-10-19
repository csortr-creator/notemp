package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

class UnifiedImportTest {
    @Test fun nativeJsonAndArraysEnterTheSameParserAsNetworkSubscriptions() {
        val array = """[{"type":"socks","tag":"native","server":"fixture.invalid","server_port":1080}]"""
        val root = """{"outbounds":$array}"""
        for (input in listOf(array, root, "\uFEFF$root", "\uFEFF$array")) {
            assertTrue(SubscriptionContentParser.supports(input))
            val parsed = SubscriptionContentParser().parse(input)
            assertEquals(1, parsed.report.imported)
            assertTrue(parsed.config.contains("native"))
        }
    }
    @Test fun plainQrsEnvelopeIsNotMistakenForNativeConfig() {
        assertFalse(SubscriptionContentParser.supports("""{"name":"profile","config":"{}"}"""))
    }
    @Test fun boundedReaderStopsBeforeAllocatingAnOversizedPayload() {
        val input = ByteArrayInputStream(ByteArray(ImportInput.MAX_BYTES + 1))
        try { ImportInput.read(input); fail() }
        catch (error: SubscriptionResponseFailure) { assertEquals(SubscriptionResponseProblem.TOO_LARGE, error.problem) }
    }
    @Test fun readerAcceptsExactLimitAndPreservesBytes() {
        val bytes = ByteArray(ImportInput.MAX_BYTES) { (it % 127).toByte() }
        assertArrayEquals(bytes, ImportInput.read(ByteArrayInputStream(bytes)))
    }
    @Test fun boundedReaderPreservesCancellation() {
        val original = CancellationException("cancel")
        try { ImportInput.read(ByteArrayInputStream(byteArrayOf(1))) { throw original }; fail() }
        catch (error: CancellationException) { assertSame(original, error) }
    }
    @Test fun finalCreationFailureKeepsRejectedNodeStatisticsAndSafeFeatureReason() {
        val error = try {
            SubscriptionDiagnostics.at(SubscriptionStage.PARSE) {
                SubscriptionContentParser().parse("vless://00000000-0000-0000-0000-000000000001@private.invalid:443?type=secret-transport#Node")
            }
            error("Expected rejection")
        } catch (error: SubscriptionStageFailure) { error }
        val message = ImportResultFormatter.creationFailure(error)
        assertTrue(message.contains("IMPORT_NO_USABLE_NODES"))
        assertTrue(message.contains("FEATURE_TRANSPORT"))
        assertFalse(message.contains("private.invalid"))
        assertFalse(message.contains("secret-transport"))
    }
    @Test fun capabilityTextUsesExactVocabularyWithoutRenderingProviderText() {
        val issue = SubscriptionImportIssue(1, "fixture", "Unsupported VLESS encryption profile", ImportIssueCode.UNSUPPORTED_FEATURE)
        assertTrue(SubscriptionDiagnostics.issueDetail(issue)!!.contains("FEATURE_VLESS_ENCRYPTION_PROFILE"))
        assertNull(SubscriptionDiagnostics.issueDetail(issue.copy(reason = issue.reason + " token=secret")))
    }
    @Test fun reportUsesTheSameSafeCapabilityDetailsAsPartialPreview() {
        val issue = SubscriptionImportIssue(1, "fixture", "Неподдерживаемый параметр: unsupportedOption", ImportIssueCode.UNSUPPORTED_FEATURE)
        val report = SubscriptionImportReport(1, 0, listOf(issue))
        for (message in listOf(report.displayText(), ImportResultFormatter.partialPreview(report))) {
            assertTrue(message.contains("unsupportedOption"))
            assertTrue(message.contains("FEATURE_PARAMETER"))
        }
        val malicious = issue.copy(reason = "Неподдерживаемый параметр: https://secret.invalid/?token=private")
        assertNull(SubscriptionDiagnostics.issueDetail(malicious))
        assertFalse(SubscriptionImportReport(1, 0, listOf(malicious)).displayText().contains("secret.invalid"))
    }

}
