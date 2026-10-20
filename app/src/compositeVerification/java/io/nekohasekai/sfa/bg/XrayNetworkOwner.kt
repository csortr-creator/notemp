package io.nekohasekai.sfa.bg

import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.util.Log
import io.nekohasekai.composite.ExecutorFacade
import io.nekohasekai.composite.NetworkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Experimental service-side owner; production executor selection is unchanged. */
internal class XrayNetworkOwner(service: VpnService, manager: ConnectivityManager) {
    private val facade = ExecutorFacade()
    private val lifecycle = BridgeNetworkLifecycle<Network, NetworkProvider>(
        capture = { XrayNetworkProvider.capture(service, manager, it) },
        backend = object : BridgeNetworkLifecycle.Backend<NetworkProvider> {
            override fun start(config: String, provider: NetworkProvider) = facade.startProtectedBridge(config, provider)
            override fun update(provider: NetworkProvider) = facade.updateBridgeNetwork(provider)
            override fun suspendNetwork() = facade.suspendBridgeNetwork()
            override fun stop() = facade.stopBridge()
        },
    )
    private val mutablePhase = MutableStateFlow(BridgeNetworkLifecycle.Phase.STOPPED)
    val phase = mutablePhase.asStateFlow()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Registration(val key: Any, val token: Long, val events: Channel<Network?>, val worker: Job)
    private var registration: Registration? = null

    suspend fun start(config: String) = mutex.withLock {
        stopLocked()
        val token = lifecycle.begin(config)
        mutablePhase.value = lifecycle.phase
        val key = Any()
        // Only the newest network snapshot matters; replacement also retires
        // old sockets. Never queue unlimited callbacks or block the shared actor.
        val events = Channel<Network?>(Channel.CONFLATED)
        val worker = scope.launch {
            for (network in events) {
                try {
                    lifecycle.network(token, network)
                } catch (_: Exception) {
                    // No configs, credentials or platform DNS exceptions in logs.
                    Log.w("XrayNetworkOwner", "Protected network update failed; bridge retired")
                } finally {
                    mutablePhase.value = lifecycle.phase
                }
            }
        }
        registration = Registration(key, token, events, worker)
        try {
            // A separate keyed consumer; never replace Libbox's monitor listener.
            DefaultNetworkListener.start(key) { network -> events.trySend(network) }
        } catch (error: Exception) {
            try { stopLocked() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        val current = registration ?: return
        registration = null
        // Cleanup survives caller cancellation and never blocks Android Main.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                // Invalidate before listener removal: queued callbacks are stale.
                lifecycle.end(current.token)
            } finally {
                mutablePhase.value = lifecycle.phase
                current.events.cancel()
                current.worker.cancelAndJoin()
                DefaultNetworkListener.stop(current.key)
            }
        }
    }
}
