package io.nekohasekai.sfa.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sfa.aidl.IService
import io.nekohasekai.sfa.aidl.IServiceCallback
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class ServiceConnectionTest {
    class MockCallback : io.nekohasekai.sfa.bg.ServiceConnection.Callback {
        var status: Status? = null
        override fun onServiceStatusChanged(status: Status) {
            this.status = status
        }
        override fun onServiceAlert(type: Alert, message: String?) {}
    }

    class MockContext : Context(File("")) {
        var bindCount = 0
        var unbindCount = 0
        var conn: android.content.ServiceConnection? = null

        override fun bindService(intent: Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
            bindCount++
            this.conn = conn
            return true
        }

        override fun unbindService(conn: android.content.ServiceConnection) {
            unbindCount++
            if (this.conn == conn) {
                this.conn = null
            }
        }
    }

    class MockIService : IService.Stub() {
        var registeredCallback: IServiceCallback? = null
        override fun getStatus(): Int = Status.Started.ordinal
        override fun registerCallback(callback: IServiceCallback) {
            registeredCallback = callback
        }
        override fun unregisterCallback(callback: IServiceCallback?) {
            if (registeredCallback == callback) {
                registeredCallback = null
            }
        }
    }

    @Test
    fun testDisconnectCleansUpReference() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)

        connection.connect()
        assertEquals(1, context.bindCount)

        val mockService = MockIService()
        (context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService)
        assertEquals(Status.Started, callback.status)
        assertEquals(Status.Started, connection.status)
        assertNotNull(mockService.registeredCallback)

        connection.disconnect()
        // Disconnect should manually clear the service reference, even before onServiceDisconnected
        assertEquals(Status.Stopped, connection.status)
        assertNull(mockService.registeredCallback)

        // Simulating the delayed callback from the system
        (context.conn as? android.content.ServiceConnection)?.onServiceDisconnected(null)

        assertNull(mockService.registeredCallback)
        assertEquals(Status.Stopped, connection.status)
    }

    @Test
    fun testReconnectDoesNotAccumulateBindings() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)

        connection.connect()
        val mockService1 = MockIService()
        (context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService1)

        connection.reconnect()
        assertEquals(2, context.bindCount)
        assertEquals(1, context.unbindCount)
        assertNull(mockService1.registeredCallback)

        val mockService2 = MockIService()
        (context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService2)
        assertNotNull(mockService2.registeredCallback)
    }

    @Test
    fun testBindingDiedCleansUpAndReconnects() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)

        connection.connect()
        val mockService = MockIService()
        (context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService)

        (context.conn as? android.content.ServiceConnection)?.onBindingDied(null)

        assertEquals(2, context.bindCount)
        assertEquals(1, context.unbindCount)
        assertNull(mockService.registeredCallback)
    }

    @Test
    fun testRepeatedDisconnectIsSafe() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)

        connection.connect()
        val mockService = MockIService()
        (context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService)

        connection.disconnect()
        connection.disconnect()

        assertEquals(1, context.unbindCount)
        assertNull(mockService.registeredCallback)
    }

    @Test
    fun testLateCallbackFromClosedSessionDoesNotUpdateUI() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)

        connection.connect()

        // Disconnect immediately before the service connects
        connection.disconnect()

        val mockService = MockIService()
        // Simulate a late onServiceConnected that arrives after disconnect
        (context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService)

        // The service should have been unregistered, and we shouldn't hold a reference to it
        assertNull(mockService.registeredCallback)
        assertEquals(Status.Stopped, connection.status)
        assertNull(callback.status) // UI should not have been updated
    }

    @Test
    fun testBindingDiedAfterDisconnectDoesNotReconnect() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)

        connection.connect()
        val mockService = MockIService()
        (context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService)

        connection.disconnect()
        val prevBindCount = context.bindCount

        // Late onBindingDied
        (context.conn as? android.content.ServiceConnection)?.onBindingDied(null)

        // Should not have triggered a reconnect
        assertEquals(prevBindCount, context.bindCount)
    }
}
