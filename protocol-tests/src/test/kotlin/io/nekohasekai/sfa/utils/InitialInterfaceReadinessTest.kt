package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test

class InitialInterfaceReadinessTest {
    @Test fun registrationPublishesBeforeRuleSetDownloadCanBegin() {
        var interfaces = emptyList<String>()
        var linkPropertyReads = 0
        publishInitialInterface(
            resolve = { if (++linkPropertyReads < 3) null else "rmnet_data0" },
            publish = { interfaces = listOf(it); true },
            retryDelayMs = 1,
        )
        // Equivalent to the first native dial after StartDefaultInterfaceMonitor returns.
        assertEquals(listOf("rmnet_data0"), interfaces)
    }

    @Test fun missingInterfaceFailsStartupInsteadOfReturningAnEmptyNetworkList() {
        var published = false
        val error = runCatching {
            publishInitialInterface<String>({ null }, { published = true; true }, attempts = 2, retryDelayMs = 1)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(published)
    }

    @Test fun networkSwitchDuringRegistrationUsesTheLatestInterface() {
        var network = "mobile-not-ready"
        var published = ""
        publishInitialInterface(
            resolve = {
                if (network == "mobile-not-ready") { network = "wlan0"; null } else network
            },
            publish = { published = it; true },
            retryDelayMs = 1,
        )
        assertEquals("wlan0", published)
    }
    @Test fun replacedRegistrationDoesNotPublishAResolvedOldSnapshot() {
        var current = true
        var published = false
        val error = runCatching {
            publishInitialInterface(
                resolve = { current = false; "old-mobile" },
                publish = { published = true; true },
                isCurrent = { current },
                retryDelayMs = 1,
            )
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertFalse(published)
    }

    @Test fun cancelledRegistrationStopsBeforePolling() {
        var resolved = false
        val error = runCatching {
            publishInitialInterface(
                resolve = { resolved = true; "wifi" }, publish = { true }, isCurrent = { false },
            )
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertFalse(resolved)
    }

    @Test fun aSnapshotReplacedBeforePublicationIsRetried() {
        var resolved = 0
        var published = ""
        publishInitialInterface(
            resolve = { if (++resolved == 1) "old" else "new" },
            publish = { if (it == "old") false else { published = it; true } },
            retryDelayMs = 1,
        )
        assertEquals("new", published)
        assertEquals(2, resolved)
    }

}
