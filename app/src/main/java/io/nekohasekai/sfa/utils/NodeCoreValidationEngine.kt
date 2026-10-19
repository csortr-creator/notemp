package io.nekohasekai.sfa.utils

import org.json.JSONObject

/**
 * B2 per-node core validation engine (injectable checkConfig for tests).
 *
 * Skip (throw) **only** on [NodeCoreVerdict.NODE_INVALID] after a green family control.
 * HARNESS / UNKNOWN / CORE_UNAVAILABLE never auto-skip.
 */
internal class NodeCoreNodeInvalidException(
    val reasonCode: String,
) : Exception("node_core_invalid:$reasonCode")

internal class NodeCoreValidationEngine(
    private val checkConfig: (String) -> Unit,
) {
    private val familyControlOk = mutableMapOf<String, Boolean>()
    private data class Validation(val verdict: NodeCoreVerdict, val reason: String = "")
    private val validatedNodes = object : LinkedHashMap<String, Validation>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Validation>?) = size > 256
    }
    private var inBatch = false

    fun beginBatch() {
        familyControlOk.clear()
        validatedNodes.clear()
        inBatch = true
    }

    fun endBatch() {
        familyControlOk.clear()
        validatedNodes.clear()
        inBatch = false
    }

    fun evaluate(node: JSONObject): NodeCoreVerdict = checkNode(node).verdict

    fun validateOrThrow(node: JSONObject) {
        val result = checkNode(node)
        if (result.verdict == NodeCoreVerdict.NODE_INVALID) {
            throw NodeCoreNodeInvalidException(result.reason)
        }
    }

    private fun checkNode(node: JSONObject): Validation {
        val snapshot = node.toString()
        // Batch-local and bounded: repeated entries do not repeat native configuration checks.
        // Unknown/unavailable results are never cached, so a temporary failure can recover.
        if (inBatch) validatedNodes[snapshot]?.let { return it }
        val family = NodeValidationCompare.familyOf(node)
        val controlPassed = familyControlOk.getOrPut(family) { runFamilyControl(family) }
        val harness = try {
            NodeCoreHarness.build(node)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            return Validation(NodeCoreVerdict.HARNESS_INVALID)
        }
        check(node.toString() == snapshot) { "NodeCore harness must not mutate node JSON" }
        val result = try {
            checkConfig(harness)
            Validation(NodeCoreVerdict.VALID)
        } catch (_: UnsatisfiedLinkError) {
            Validation(NodeCoreVerdict.CORE_UNAVAILABLE)
        } catch (_: NoClassDefFoundError) {
            Validation(NodeCoreVerdict.CORE_UNAVAILABLE)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            Validation(
                NodeCoreHarness.classify(e.message, controlPassed, expectedHarnessFailure = false),
                NodeValidationCompare.mapReason(e.message ?: "other"),
            )
        }
        if (inBatch && result.verdict in setOf(NodeCoreVerdict.VALID, NodeCoreVerdict.NODE_INVALID)) {
            validatedNodes[snapshot] = result
        }
        return result
    }

    private fun runFamilyControl(family: String): Boolean {
        if (family == "invalid") return false
        val control =
            when (family) {
                "vless-xhttp" -> NodeCoreHarness.controlVlessXhttp()
                "hysteria2" -> NodeCoreHarness.controlHysteria2()
                else -> NodeCoreHarness.controlVlessRealityDomain()
            }
        return try {
            checkConfig(NodeCoreHarness.build(control))
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: NoClassDefFoundError) {
            false
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            false
        }
    }
}
