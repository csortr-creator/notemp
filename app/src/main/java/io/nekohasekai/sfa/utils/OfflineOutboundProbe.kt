package io.nekohasekai.sfa.utils

import android.net.Network
import android.os.ParcelFileDescriptor
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.DefaultNetworkListener
import io.nekohasekai.sfa.bg.PlatformInterfaceWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.NetworkInterface

class OfflineProbePlatform : PlatformInterfaceWrapper {
    @Volatile private var network: Network? = null
    @Volatile private var generation = 0L

    // The isolated probe has no services or TUN and does not publish notifications.
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val token = synchronized(this) { ++generation }
        runBlocking(Dispatchers.IO) {
            DefaultNetworkListener.start(this@OfflineProbePlatform) { current ->
                synchronized(this@OfflineProbePlatform) {
                    if (generation == token) publishNetwork(current, listener)
                }
            }
            // Do not return to native Box.Start with an uninitialised default interface.
            // Get is processed after Start and awaits the first network callback.
            awaitProbeNetwork({ network }, { DefaultNetworkListener.get() })
            publishInitialInterface(
                resolve = {
                    network?.let { current ->
                        val name = Application.connectivity.getLinkProperties(current)?.interfaceName
                        val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
                        if (name != null && index != null && index > 0) Triple(current, name, index) else null
                    }
                },
                isCurrent = { generation == token },
                publish = { (current, name, index) ->
                    synchronized(this@OfflineProbePlatform) {
                        if (generation != token || network != current) false else {
                            listener.updateDefaultInterface(name, index, false, false)
                            true
                        }
                    }
                },
            )
        }
    }

    private fun publishNetwork(current: Network?, listener: InterfaceUpdateListener) {
        network = current
        val name = current?.let { Application.connectivity.getLinkProperties(it)?.interfaceName }
        val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
        listener.updateDefaultInterface(name ?: "", index ?: -1, false, false)
    }

    private fun currentNetwork(): Network = runBlocking(Dispatchers.IO) {
        awaitProbeNetwork({ network }, { DefaultNetworkListener.get() })
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        synchronized(this) { generation++; network = null }
        runBlocking(Dispatchers.IO) { DefaultNetworkListener.stop(this@OfflineProbePlatform) }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        val current = currentNetwork()
        ParcelFileDescriptor.fromFd(fd).use { current.bindSocket(it.fileDescriptor) }
    }

    override fun localDNSTransport(): LocalDNSTransport = object : LocalDNSTransport {
        override fun raw() = false
        override fun exchange(ctx: ExchangeContext, message: ByteArray) = error("not supported")
        override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
            val current = currentNetwork()
            val addresses = current.getAllByName(domain).filter {
                when (network) {
                    "ip4" -> it is java.net.Inet4Address
                    "ip6" -> it is java.net.Inet6Address
                    else -> true
                }
            }
            ctx.success(addresses.mapNotNull { it.hostAddress }.joinToString("\n"))
        }
    }
}
