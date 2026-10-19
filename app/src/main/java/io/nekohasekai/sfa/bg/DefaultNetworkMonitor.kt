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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checkJob: Job? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
        }
        defaultNetwork = withTimeout(10000L) { DefaultNetworkListener.get() }
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    suspend fun stop() {
        setListener(null)
        defaultNetwork = null
        DefaultNetworkListener.stop(this)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    @Synchronized
    fun setListener(listener: InterfaceUpdateListener?) {
        generation++
        checkJob?.cancel()
        checkJob = null
        this.listener = listener
        if (listener != null) {
            // Must finish before native startup downloads remote rule-sets or resolves DNS.
            // Resolve the latest network on each attempt: onAvailable can precede link properties.
            publishInitialInterface(
                resolve = { defaultNetwork?.let(::interfaceSnapshot) },
                publish = { (name, index) -> listener.updateDefaultInterface(name, index, false, false) },
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
