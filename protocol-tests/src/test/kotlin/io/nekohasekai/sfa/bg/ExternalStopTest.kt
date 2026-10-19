package io.nekohasekai.sfa.bg

import org.junit.Assert.*
import org.junit.Test

class ExternalStopTest {
    @Test
    fun stopDiscardsActiveAndPendingWithoutRestartingButAllowsLaterSelection() {
        val queue = ProfileSelectionQueue(RuntimeProfileState::currentUserStopId)
        queue.offer(11, true)
        val active = queue.take(true)!!
        val start = RuntimeProfileState.nextSwitchRequestId()
        assertTrue(RuntimeProfileState.beginStart(start, 11))
        queue.offer(22, true)
        RuntimeProfileState.requestUserStop()
        assertFalse(RuntimeProfileState.tryMarkLoaded(start, 11, "config"))
        var dispatched = false
        assertFalse(queue.runIfCurrent(active) { dispatched = true })
        assertFalse(dispatched)
        queue.complete(active)
        assertNull(queue.take(true))
        queue.offer(33, false)
        val afterStop = queue.take(true)!!
        assertEquals(33L, afterStop.profileId)
        assertFalse(afterStop.connect)
        assertTrue(queue.runIfCurrent(afterStop) { dispatched = true })
        assertTrue(dispatched)
        RuntimeProfileState.clear()
    }

    @Test
    fun stopBeforeTakingPendingDiscardsItWithoutNeedingUiCallback() {
        val queue = ProfileSelectionQueue(RuntimeProfileState::currentUserStopId)
        queue.offer(11, true)
        RuntimeProfileState.requestUserStop()
        assertNull(queue.take(true))
    }

    @Test
    fun stopAfterNativeLoadInvalidatesDelayedStartedPublication() {
        val start = RuntimeProfileState.nextSwitchRequestId()
        assertTrue(RuntimeProfileState.beginStart(start, 44))
        assertTrue(RuntimeProfileState.tryMarkLoaded(start, 44, "config"))
        RuntimeProfileState.requestUserStop()
        assertFalse(RuntimeProfileState.isStartCurrent(start, 44))
        RuntimeProfileState.clear()
    }

    @Test
    fun internalSwitchDoesNotCancelTheQueue() {
        val queue = ProfileSelectionQueue(RuntimeProfileState::currentUserStopId)
        queue.offer(11, true)
        val active = queue.take(true)!!
        RuntimeProfileState.nextSwitchRequestId()
        assertTrue(queue.runIfCurrent(active) {})
        queue.complete(active)
    }
}
