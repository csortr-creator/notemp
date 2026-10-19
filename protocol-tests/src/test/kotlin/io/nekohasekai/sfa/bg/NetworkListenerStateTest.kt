package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkListenerStateTest {
    @Test fun getWhileStoppedDoesNotBreakLaterRegistration() = runBlocking {
        val state = NetworkListenerState<String>({ null }, {})
        val missing = CompletableDeferred<String>()
        state.get(missing)
        try { missing.await(); fail("Expected stopped monitor") } catch (_: IllegalStateException) { }
        state.start("vpn") {}
        val ready = CompletableDeferred<String>()
        state.get(ready)
        state.available(state.generation, "wifi")
        assertEquals("wifi", ready.await())
    }

    @Test fun cancelledRequestsDoNotAccumulate() {
        val state = NetworkListenerState<String>({ null }, {})
        state.start("vpn") {}
        repeat(1000) {
            val request = CompletableDeferred<String>()
            state.get(request)
            state.cancelGet(request)
        }
        assertEquals(0, state.pendingCount)
    }

    @Test fun lastStopCancelsWaitersAndDoesNotLeakIntoRestart() = runBlocking {
        var unregisters = 0
        val state = NetworkListenerState<String>({ null }, { unregisters++ })
        state.start("vpn") {}
        val waiting = CompletableDeferred<String>()
        state.get(waiting)
        val old = state.generation
        state.stop("vpn")
        assertTrue(waiting.isCancelled)
        assertEquals(0, state.pendingCount)
        state.start("vpn") {}
        val next = CompletableDeferred<String>()
        state.get(next)
        state.available(old, "stale-mobile")
        assertFalse(next.isCompleted)
        state.available(state.generation, "wifi")
        assertEquals("wifi", next.await())
        assertEquals(1, unregisters)
    }

    @Test fun oldCallbackCannotChangeOrLoseCurrentNetwork() {
        val seen = mutableListOf<String?>()
        val state = NetworkListenerState<String>({ null }, {})
        state.start("vpn", seen::add)
        val old = state.generation
        state.stop("vpn")
        state.start("vpn", seen::add)
        state.available(state.generation, "wifi")
        state.available(old, "mobile")
        state.update(old, "wifi")
        state.lost(old, "wifi")
        assertEquals(listOf("wifi"), seen)
        state.update(state.generation, "wifi")
        state.lost(state.generation, "wifi")
        assertEquals(listOf("wifi", "wifi", null), seen)
    }

    @Test fun stoppingOneConsumerKeepsOtherConsumerAlive() = runBlocking {
        var registrations = 0
        var unregisters = 0
        val seen = mutableListOf<String?>()
        val state = NetworkListenerState<String>({ registrations++; null }, { unregisters++ })
        state.start("vpn") {}
        state.start("probe", seen::add)
        val waiting = CompletableDeferred<String>(); state.get(waiting)
        state.stop("vpn")
        state.available(state.generation, "mobile")
        assertEquals("mobile", waiting.await())
        assertEquals(listOf("mobile"), seen)
        assertEquals(1, registrations)
        assertEquals(0, unregisters)
        state.stop("probe")
        assertEquals(1, unregisters)
    }

    @Test fun brokenConsumerDoesNotKillOtherListenersOrWaiters() = runBlocking {
        var failures = 0
        val state = NetworkListenerState<String>({ null }, {}, { failures++ })
        state.start("broken") { error("consumer closed") }
        var seen: String? = null
        state.start("working") { seen = it }
        val waiting = CompletableDeferred<String>(); state.get(waiting)
        state.available(state.generation, "wifi")
        assertEquals("wifi", waiting.await())
        assertEquals("wifi", seen)
        assertEquals(1, failures)
    }

    @Test fun failedRegistrationCanBeRetriedAndOldEventsAreIgnored() = runBlocking {
        var fail = true
        var failedToken = 0L
        val state = NetworkListenerState<String>({ token ->
            if (fail) { failedToken = token; error("registration failed") }
            null
        }, {})
        try { state.start("vpn") {}; fail("Expected registration failure") } catch (_: IllegalStateException) {}
        fail = false
        state.start("vpn") {}
        val waiting = CompletableDeferred<String>(); state.get(waiting)
        state.available(failedToken, "stale")
        assertFalse(waiting.isCompleted)
        state.available(state.generation, "ready")
        assertEquals("ready", waiting.await())
    }

    @Test fun lostOldNetworkDoesNotEraseReplacement() = runBlocking {
        val state = NetworkListenerState<String>({ null }, {})
        state.start("vpn") {}
        val token = state.generation
        state.available(token, "mobile")
        state.available(token, "wifi")
        state.lost(token, "mobile")
        val waiting = CompletableDeferred<String>(); state.get(waiting)
        assertEquals("wifi", waiting.await())
    }

    @Test fun fallbackIsFreshAndNotAnOldSnapshot() = runBlocking {
        val state = NetworkListenerState<String>({ "old" }, {})
        state.start("vpn") {}
        val waiting = CompletableDeferred<String>(); state.get(waiting) { "new-underlying" }
        assertEquals("new-underlying", waiting.await())
    }

    @Test fun unregisterFailureDoesNotLeaveStateRunning() {
        var failures = 0
        val state = NetworkListenerState<String>({ null }, { error("already unregistered") }, { failures++ })
        state.start("vpn") {}
        state.stop("vpn")
        state.stop("vpn")
        assertEquals(1, failures)
        state.start("vpn") {}
    }
    @Test fun cancelledOldRegistrationDoesNotRemoveNewListenerWithSameKey() {
        var unregisters = 0
        val state = NetworkListenerState<String>({ null }, { unregisters++ })
        val old: (String?) -> Unit = {}
        val seen = mutableListOf<String?>()
        state.start("vpn", old)
        state.start("vpn", seen::add)
        state.stop("vpn", old)
        state.available(state.generation, "wifi")
        assertEquals(listOf("wifi"), seen)
        assertEquals(0, unregisters)
    }

}
