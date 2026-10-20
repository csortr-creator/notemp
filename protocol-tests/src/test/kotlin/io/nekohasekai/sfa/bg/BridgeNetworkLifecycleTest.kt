package io.nekohasekai.sfa.bg

import org.junit.Assert.*
import org.junit.Test

class BridgeNetworkLifecycleTest {
    private class Fake : BridgeNetworkLifecycle.Backend<String> {
        val events = mutableListOf<String>()
        var failure: String? = null
        private fun record(value: String) {
            events += value
            if (failure == value.substringBefore(':')) error("fixture failure")
        }
        override fun start(config: String, provider: String) = record("start:$config:$provider")
        override fun update(provider: String) = record("update:$provider")
        override fun suspendNetwork() = record("suspend")
        override fun stop() = record("stop")
    }

    @Test fun lossSuspendsOnceAndRecoveryResumesWithoutRestart() {
        val backend = Fake()
        val state = BridgeNetworkLifecycle<String, String>({ "captured-$it" }, backend)
        val token = state.begin("config")
        state.network(token, null)
        assertEquals(BridgeNetworkLifecycle.Phase.WAITING_FOR_NETWORK, state.phase)
        assertTrue(backend.events.isEmpty())
        state.network(token, "wifi")
        assertEquals(BridgeNetworkLifecycle.Phase.RUNNING, state.phase)
        state.network(token, null)
        assertEquals(BridgeNetworkLifecycle.Phase.SUSPENDED, state.phase)
        state.network(token, null)
        state.network(token, "mobile")
        assertEquals(BridgeNetworkLifecycle.Phase.RUNNING, state.phase)
        state.end(token)
        assertEquals(BridgeNetworkLifecycle.Phase.STOPPED, state.phase)
        assertEquals(listOf("start:config:captured-wifi", "suspend", "update:captured-mobile", "stop"), backend.events)
    }

    @Test fun staleCallbackOrStopCannotAffectReplacement() {
        val backend = Fake()
        val state = BridgeNetworkLifecycle<String, String>({ it }, backend)
        val old = state.begin("old")
        state.network(old, "wifi")
        val current = state.begin("new")
        state.network(old, "obsolete")
        state.network(old, null)
        state.end(old)
        state.network(current, "mobile")
        state.end(current)
        state.network(current, "late")
        state.end(current)
        assertEquals(listOf("start:old:wifi", "stop", "start:new:mobile", "stop"), backend.events)
    }

    @Test fun captureFailureRetiresOldSocketsAndNextNetworkRestarts() {
        val backend = Fake()
        val state = BridgeNetworkLifecycle<String, String>({ if (it == "vpn") error("not underlying") else it }, backend)
        val token = state.begin("config")
        state.network(token, "wifi")
        try { state.network(token, "vpn"); fail("Expected rejection") } catch (_: IllegalStateException) { }
        state.network(token, "mobile")
        assertEquals(listOf("start:config:wifi", "stop", "start:config:mobile"), backend.events)
        state.end(token)
    }

    @Test fun partialStartAndFailedUpdateAreAlwaysStopped() {
        for (operation in listOf("start", "update", "suspend")) {
            val backend = Fake()
            val state = BridgeNetworkLifecycle<String, String>({ it }, backend)
            val token = state.begin("config")
            if (operation != "start") state.network(token, "wifi")
            backend.failure = operation
            try {
                state.network(token, if (operation == "suspend") null else "mobile")
                fail("Expected $operation failure")
            } catch (_: IllegalStateException) { }
            assertEquals(BridgeNetworkLifecycle.Phase.FAILED, state.phase)
            assertEquals("stop", backend.events.last())
            backend.failure = null
            state.network(token, "recovered")
            assertEquals("start:config:recovered", backend.events.last())
            state.end(token)
        }
    }

    @Test fun stopFailureStillInvalidatesOldCallbacks() {
        val backend = Fake()
        val state = BridgeNetworkLifecycle<String, String>({ it }, backend)
        val token = state.begin("config")
        state.network(token, "wifi")
        backend.failure = "stop"
        try { state.end(token); fail("Expected stop failure") } catch (_: IllegalStateException) { }
        state.network(token, "late")
        state.end(token)
        assertEquals(listOf("start:config:wifi", "stop"), backend.events)
    }

    @Test fun captureFailureBeforeStartDoesNotStopAnUnownedBridge() {
        val state = BridgeNetworkLifecycle<String, String>({ error("capture") }, object : BridgeNetworkLifecycle.Backend<String> {
            override fun start(config: String, provider: String) = Unit
            override fun update(provider: String) = Unit
            override fun suspendNetwork() = Unit
            override fun stop() = error("cleanup")
        })
        // A capture failure before any native start must not attempt native Stop.
        val token = state.begin("config")
        try { state.network(token, "wifi"); fail("Expected capture failure") } catch (error: IllegalStateException) {
            assertEquals("capture", error.message)
            assertTrue(error.suppressed.isEmpty())
        }
    }
}
