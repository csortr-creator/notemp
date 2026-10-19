package io.nekohasekai.sfa.bg

import android.net.Network
import android.net.LinkProperties
import io.nekohasekai.sfa.Application
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NetworkPlatformAdapterTest {
    @Test fun actualActorKeepsReplacementAfterOldCallbackAndRecoversAfterCancelledGet() = runBlocking {
        val key = Any()
        DefaultNetworkListener.start(key) {}
        val old = Application.connectivity.callbacks.single()
        val cancelled = async { DefaultNetworkListener.get() }
        yield(); cancelled.cancelAndJoin()
        DefaultNetworkListener.stop(key)
        DefaultNetworkListener.start(key) {}
        try {
            val next = Application.connectivity.callbacks.single()
            old.onAvailable(Network(1))
            val ready = async { DefaultNetworkListener.get() }
            next.onAvailable(Network(2))
            assertEquals(Network(2), withTimeout(2000) { ready.await() })
            old.onLost(Network(2))
            assertEquals(Network(2), withTimeout(2000) { DefaultNetworkListener.get() })
        } finally { DefaultNetworkListener.stop(key) }
    }

    @Test fun interfaceRegistrationDoesNotBlockProcessingAReplacementNetwork() = runBlocking {
        val manager = Application.connectivity
        val start = async(Dispatchers.IO) { DefaultNetworkMonitor.start() }
        withTimeout(2000) { while (manager.callbacks.isEmpty()) delay(5) }
        val callback = manager.callbacks.single()
        val mobile = Network(11)
        val wifi = Network(12)
        callback.onAvailable(mobile)
        start.await()
        manager.links[wifi] = LinkProperties("lo")
        val published = CompletableDeferred<String>()
        val registration = async(Dispatchers.IO) {
            DefaultNetworkMonitor.setListener(InterfaceUpdateListener { name, _, _, _ -> published.complete(name) })
        }
        try {
            delay(30)
            callback.onLinkPropertiesChanged(mobile, LinkProperties())
            callback.onAvailable(wifi)
            assertEquals("lo", withTimeout(2000) { published.await() })
            withTimeout(2000) { registration.await() }
            assertEquals(wifi, DefaultNetworkMonitor.defaultNetwork)
        } finally {
            DefaultNetworkMonitor.stop()
            registration.cancelAndJoin()
            manager.links.clear()
        }
    }
    @Test fun offlineProbeAlsoWaitsForUsableLinkProperties() = runBlocking {
        val manager = Application.connectivity
        val probe = io.nekohasekai.sfa.utils.OfflineProbePlatform()
        val ready = CompletableDeferred<String>()
        val listener = InterfaceUpdateListener { name, index, _, _ -> if (index > 0) ready.complete(name) }
        val registration = async(Dispatchers.IO) { probe.startDefaultInterfaceMonitor(listener) }
        try {
            withTimeout(2000) { while (manager.callbacks.isEmpty()) delay(5) }
            val callback = manager.callbacks.single()
            val mobile = Network(21)
            callback.onAvailable(mobile)
            delay(30)
            assertFalse(registration.isCompleted)
            manager.links[mobile] = LinkProperties("lo")
            callback.onLinkPropertiesChanged(mobile, manager.links.getValue(mobile))
            withTimeout(2000) { registration.await() }
            assertEquals("lo", withTimeout(2000) { ready.await() })
        } finally { probe.closeDefaultInterfaceMonitor(listener); registration.cancelAndJoin(); manager.links.clear() }
    }

    @Test fun stopInterruptsInitialInterfaceWaitAndRejectsLateRegistration() = runBlocking {
        val manager = Application.connectivity
        val start = async(Dispatchers.IO) { DefaultNetworkMonitor.start() }
        withTimeout(2000) { while (manager.callbacks.isEmpty()) delay(5) }
        manager.callbacks.single().onAvailable(Network(31))
        start.await()
        val listener = InterfaceUpdateListener { _, _, _, _ -> fail("No interface is ready") }
        val registration = async(Dispatchers.IO) { DefaultNetworkMonitor.setListener(listener) }
        try {
            delay(30)
            DefaultNetworkMonitor.cancelPendingRegistration()
            try { withTimeout(1000) { registration.await() }; fail("Expected registration cancellation") }
            catch (e: CancellationException) { assertFalse(e is TimeoutCancellationException) }
            try { DefaultNetworkMonitor.setListener(listener); fail("Late registration must be rejected") }
            catch (_: CancellationException) { }
        } finally { DefaultNetworkMonitor.stop(); registration.cancelAndJoin() }
    }

}
