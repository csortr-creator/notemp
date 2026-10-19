package io.nekohasekai.sfa.bg

import android.net.Network
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.publishInitialInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    @Volatile var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null
    private var generation = 0L
    private var listenerGeneration = 0L
    private var monitorGeneration = 0L
    private var acceptingListeners = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checkJob: Job? = null

    suspend fun start() {
        val token = synchronized(this) { acceptingListeners = true; ++monitorGeneration }
        DefaultNetworkListener.start(this) update@{ network ->
            synchronized(this) {
                if (monitorGeneration != token) return@update
                defaultNetwork = network
                checkDefaultInterfaceUpdate(network)
            }
        }
        // The listener owns the live snapshot. An awaited result may already have been lost.
        withTimeout(10000L) { DefaultNetworkListener.get() }
        synchronized(this) {
            if (monitorGeneration == token) checkDefaultInterfaceUpdate(defaultNetwork)
        }
    }

    suspend fun stop() {
        synchronized(this) { monitorGeneration++ }
        cancelPendingRegistration()
        DefaultNetworkListener.stop(this)
        defaultNetwork = null
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun cancelPendingRegistration() {
        synchronized(this) { acceptingListeners = false }
        setListener(null)
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        val token = synchronized(this) {
            if (listener != null && !acceptingListeners) throw kotlinx.coroutines.CancellationException("Network monitor is stopping")
            generation++
            checkJob?.cancel()
            checkJob = null
            this.listener = listener
            ++listenerGeneration
        }
        if (listener != null) {
            // Poll outside the monitor lock: network callbacks must be able to publish replacements.
            publishInitialInterface(
                resolve = { defaultNetwork?.let { network -> interfaceSnapshot(network)?.let { Triple(network, it.first, it.second) } } },
                isCurrent = { synchronized(this) { listenerGeneration == token && this.listener === listener } },
                publish = { (network, name, index) ->
                    synchronized(this) {
                        if (listenerGeneration == token && this.listener === listener && defaultNetwork == network) {
                            listener.updateDefaultInterface(name, index, false, false)
                            true
                        } else false
                    }
                },
            )
        }
    }

    private fun interfaceSnapshot(network: Network): Pair<String, Int>? {
        val name = Application.connectivity.getLinkProperties(network)?.interfaceName
            ?.takeIf { it.isNotBlank() } ?: return null
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull()
            ?.takeIf { it > 0 } ?: return null
        return name to index
    }

    @Synchronized
    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val updateGeneration = ++generation
        checkJob?.cancel()
        val currentListener = listener ?: return
        checkJob = scope.launch {
            if (newNetwork != null) {
                for (times in 0 until 50) {
                    val snapshot = interfaceSnapshot(newNetwork)
                    if (snapshot != null) {
                        synchronized(this@DefaultNetworkMonitor) {
                            if (generation == updateGeneration && listener === currentListener) {
                                currentListener.updateDefaultInterface(snapshot.first, snapshot.second, false, false)
                            }
                        }
                        return@launch
                    }
                    delay(100)
                }
            } else {
                synchronized(this@DefaultNetworkMonitor) {
                    if (generation == updateGeneration && listener === currentListener) {
                        currentListener.updateDefaultInterface("", -1, false, false)
                    }
                }
            }
        }
    }
}
