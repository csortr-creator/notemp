package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B2 engine tests with injectable checkConfig (no Android libbox required).
 */
class NodeCoreValidatorTest {

    @Test
    fun validNodeKept() {
        val engine = NodeCoreValidationEngine { /* always ok */ }
        engine.beginBatch()
        val node = NodeCoreHarness.controlVlessRealityDomain()
        assertEquals(NodeCoreVerdict.VALID, engine.evaluate(node))
        engine.validateOrThrow(node) // must not throw
    }

    @Test
    fun unknownTypeSkippedOnlyWithGreenControl() {
        // Family control passes; node fails with unknown type → NODE_INVALID → throw
        val engine =
            NodeCoreValidationEngine { content ->
                if (content.contains("not-a-real-outbound-type")) {
                    error("decode config: outbounds[0]: unknown outbound type: not-a-real-outbound-type")
                }
            }
        engine.beginBatch()
        try {
            engine.validateOrThrow(NodeCoreHarness.invalidUnknownType())
            fail("expected NodeCoreNodeInvalidException")
        } catch (e: NodeCoreNodeInvalidException) {
            assertEquals("unknown_type", e.reasonCode)
        }
    }

    @Test
    fun harnessErrorDoesNotSkip() {
        val engine =
            NodeCoreValidationEngine { content ->
                // Fail everything including family control and node with harness signature
                error("default domain resolver not found: dns-direct")
            }
        engine.beginBatch()
        // control fails → controlPassed false → UNKNOWN or HARNESS; never throw skip
        engine.validateOrThrow(NodeCoreHarness.controlVlessRealityDomain())
        val v = engine.evaluate(NodeCoreHarness.controlVlessRealityDomain())
        assertTrue(
            v == NodeCoreVerdict.HARNESS_INVALID || v == NodeCoreVerdict.UNKNOWN,
        )
        assertFalse(v == NodeCoreVerdict.NODE_INVALID)
    }

    @Test
    fun unknownVerdictDoesNotSkip() {
        val engine =
            NodeCoreValidationEngine { content ->
                if (content.contains("ctrl-")) {
                    // family control OK
                    return@NodeCoreValidationEngine
                }
                error("tls: handshake failure")
            }
        engine.beginBatch()
        engine.validateOrThrow(NodeCoreHarness.controlVlessRealityDomain())
        assertEquals(
            NodeCoreVerdict.UNKNOWN,
            engine.evaluate(
                NodeCoreHarness.controlVlessRealityDomain().put("tag", "other"),
            ),
        )
    }

    @Test
    fun doesNotMutateNode() {
        val node = NodeCoreHarness.controlVlessRealityDomain()
        val before = node.toString()
        val engine = NodeCoreValidationEngine { }
        engine.beginBatch()
        engine.evaluate(node)
        engine.validateOrThrow(node)
        assertEquals(before, node.toString())
    }

    @Test
    fun ninetyNinePlusOnePartial() {
        var checked = 0
        val engine =
            NodeCoreValidationEngine { content ->
                checked++
                if (content.contains("not-a-real-outbound-type")) {
                    error("unknown outbound type: not-a-real-outbound-type")
                }
            }
        engine.beginBatch()
        var kept = 0
        var skipped = 0
        repeat(99) { i ->
            val n = NodeCoreHarness.controlVlessRealityDomain().put("tag", "n-$i")
            engine.validateOrThrow(n)
            kept++
        }
        try {
            engine.validateOrThrow(NodeCoreHarness.invalidUnknownType())
        } catch (_: NodeCoreNodeInvalidException) {
            skipped++
        }
        assertEquals(99, kept)
        assertEquals(1, skipped)
    }

    @Test
    fun wrongFieldTypeSkippedWithGreenControl() {
        val engine =
            NodeCoreValidationEngine { content ->
                if (content.contains("not-a-number") || content.contains("\"server_port\": \"not")) {
                    error(
                        "decode config: outbounds[0].server_port: json: cannot unmarshal string " +
                            "into Go struct field",
                    )
                }
            }
        engine.beginBatch()
        try {
            engine.validateOrThrow(NodeCoreHarness.invalidWrongFieldType())
            fail("expected skip")
        } catch (e: NodeCoreNodeInvalidException) {
            assertTrue(e.reasonCode.isNotEmpty())
        }
    }

    @Test
    fun familyControlCachedPerBatch() {
        var calls = 0
        val engine =
            NodeCoreValidationEngine {
                calls++
            }
        engine.beginBatch()
        engine.validateOrThrow(NodeCoreHarness.controlVlessRealityDomain().put("tag", "a"))
        engine.validateOrThrow(NodeCoreHarness.controlVlessRealityDomain().put("tag", "b"))
        // 1 family control + 2 nodes = 3 checks (not 2 controls + 2 nodes)
        assertEquals(3, calls)
    }
    @Test fun repeatedNodesShareValidationOnlyWithinOneBatch() {
        var calls = 0
        val engine = NodeCoreValidationEngine { calls++ }
        val node = NodeCoreHarness.controlVlessRealityDomain()
        engine.beginBatch()
        repeat(100) { engine.validateOrThrow(node) }
        assertEquals(2, calls) // one control and one node
        engine.endBatch()
        engine.beginBatch()
        engine.validateOrThrow(node)
        assertEquals(4, calls)
    }

    @Test fun validationCacheDoesNotHideChangedParameters() {
        var calls = 0
        val engine = NodeCoreValidationEngine { calls++ }
        engine.beginBatch()
        val node = NodeCoreHarness.controlVlessRealityDomain()
        engine.validateOrThrow(node)
        node.put("server_port", 8443)
        engine.validateOrThrow(node)
        assertEquals(3, calls)
    }

    @Test fun unavailableResultIsRetriedInsteadOfCached() {
        var available = false
        val engine = NodeCoreValidationEngine { if (!available) throw UnsatisfiedLinkError("not loaded") }
        engine.beginBatch()
        val node = NodeCoreHarness.controlVlessRealityDomain()
        assertEquals(NodeCoreVerdict.CORE_UNAVAILABLE, engine.evaluate(node))
        available = true
        assertEquals(NodeCoreVerdict.VALID, engine.evaluate(node))
    }

    @Test fun validationCacheHasBoundedLifetimeAndSize() {
        var calls = 0
        val engine = NodeCoreValidationEngine { calls++ }
        engine.beginBatch()
        repeat(257) { engine.validateOrThrow(NodeCoreHarness.controlVlessRealityDomain().put("tag", "n-$it")) }
        assertEquals(258, calls)
        engine.validateOrThrow(NodeCoreHarness.controlVlessRealityDomain().put("tag", "n-0"))
        assertEquals(259, calls) // the first entry was evicted
        engine.endBatch()
        repeat(2) { engine.validateOrThrow(NodeCoreHarness.controlVlessRealityDomain()) }
        assertEquals(262, calls) // caching disabled outside a batch
    }

    @Test fun cancellationOfFamilyControlIsPropagated() {
        val engine = NodeCoreValidationEngine { throw java.util.concurrent.CancellationException("cancelled") }
        engine.beginBatch()
        try {
            engine.validateOrThrow(NodeCoreHarness.controlVlessRealityDomain())
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
    }

}
