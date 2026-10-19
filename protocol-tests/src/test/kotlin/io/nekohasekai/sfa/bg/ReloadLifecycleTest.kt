package io.nekohasekai.sfa.bg

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class ReloadLifecycleTest {
    private fun running(profile: Long = 81L): Long {
        RuntimeProfileState.clear()
        val id = RuntimeProfileState.nextSwitchRequestId()
        assertTrue(RuntimeProfileState.beginStart(id, profile))
        assertTrue(RuntimeProfileState.tryMarkLoaded(id, profile, "before"))
        return id
    }
    @After fun clear() { RuntimeProfileState.clear() }

    @Test fun stopDuringReloadCannotBeAdoptedAsReloadGeneration() {
        running()
        val reload = RuntimeProfileState.captureReload(81)!!
        RuntimeProfileState.requestUserStop()
        assertFalse(RuntimeProfileState.tryMarkReloaded(reload, "after-stop"))
        assertNull(RuntimeProfileState.captureReload(81))
        assertFalse(RuntimeProfileState.isStartCurrent(RuntimeProfileState.currentSwitchRequestId(), 81))
        assertEquals(RuntimeProfileState.fingerprint("before"), RuntimeProfileState.loadedConfigFingerprint)
    }

    @Test fun queuedReloadIsInvalidAfterSwitchEvenToSameProfile() {
        running()
        val reload = RuntimeProfileState.captureReload(81)!!
        RuntimeProfileState.nextSwitchRequestId()
        assertFalse(RuntimeProfileState.isReloadCurrent(reload))
        assertFalse(RuntimeProfileState.tryMarkReloaded(reload, "stale"))
    }

    @Test fun stopAfterReloadLoadRejectsDelayedStartedPublication() {
        running()
        val reload = RuntimeProfileState.captureReload(81)!!
        assertTrue(RuntimeProfileState.tryMarkReloaded(reload, "fresh"))
        RuntimeProfileState.requestUserStop()
        assertFalse(RuntimeProfileState.isReloadCurrent(reload))
    }

    @Test fun selectionChangeCannotReloadDifferentRunningProfile() {
        running()
        assertNull(RuntimeProfileState.captureReload(82))
    }

    @Test fun stoppedOrStartingServiceDoesNotAdmitReload() {
        RuntimeProfileState.clear()
        assertNull(RuntimeProfileState.captureReload(81))
        val id = RuntimeProfileState.nextSwitchRequestId()
        RuntimeProfileState.beginStart(id, 81)
        assertNull(RuntimeProfileState.captureReload(81))
    }

    @Test fun explicitNewStartAfterStopAllowsNormalReload() {
        running()
        RuntimeProfileState.requestUserStop()
        running()
        val reload = RuntimeProfileState.captureReload(81)!!
        assertTrue(RuntimeProfileState.tryMarkReloaded(reload, "new"))
        assertEquals(RuntimeProfileState.fingerprint("new"), RuntimeProfileState.loadedConfigFingerprint)
    }

    @Test fun deletionInvalidatesCapturedReload() {
        running()
        val reload = RuntimeProfileState.captureReload(81)!!
        RuntimeProfileState.blockProfileForDeletion(81)
        try { assertFalse(RuntimeProfileState.tryMarkReloaded(reload, "deleted")) }
        finally { RuntimeProfileState.unblockProfileAfterFailedDelete(81) }
    }
}
