package io.nekohasekai.sfa.bg
import io.nekohasekai.libbox.*
interface PlatformInterfaceWrapper {
    fun sendNotification(notification: Notification)
    fun cancelNotification(identifier: String, typeID: Int)
    fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener)
    fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener)
    fun autoDetectInterfaceControl(fd: Int)
    fun localDNSTransport(): LocalDNSTransport
}
