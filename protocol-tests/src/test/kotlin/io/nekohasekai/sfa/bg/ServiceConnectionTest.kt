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
        var alerts = 0
        override fun onServiceStatusChanged(status: Status) {
            this.status = status
        }
        override fun onServiceAlert(type: Alert, message: String?) { alerts++ }
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

    open class MockIService : IService.Stub() {
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

        // Retain the old Android callback; MockContext clears conn on unbind.
        val oldDelegate = requireNotNull(context.conn)
        connection.disconnect()

        val mockService = MockIService()
        // Simulate a late onServiceConnected that arrives after disconnect
        oldDelegate.onServiceConnected(ComponentName("test", "test"), mockService)

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

        val oldDelegate = requireNotNull(context.conn)
        connection.disconnect()
        val prevBindCount = context.bindCount

        // Late onBindingDied
        oldDelegate.onBindingDied(null)

        // Should not have triggered a reconnect
        assertEquals(prevBindCount, context.bindCount)
    }
    @Test fun duplicateConnectKeepsOneBindingAndReusesItsIdentity() {
        val context = MockContext()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, MockCallback())
        connection.connect()
        val original = context.conn
        connection.connect()
        assertEquals(1, context.bindCount)
        assertSame(original, context.conn)
        connection.disconnect()
    }

    @Test fun oldRemoteStatusAndAlertsCannotUpdateReplacementSession() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)
        connection.connect()
        val oldDelegate = requireNotNull(context.conn)
        val oldService = MockIService()
        oldDelegate.onServiceConnected(ComponentName("fixture", "old"), oldService)
        val oldEvents = requireNotNull(oldService.registeredCallback)
        connection.reconnect()
        val nextService = MockIService()
        requireNotNull(context.conn).onServiceConnected(ComponentName("fixture", "next"), nextService)
        oldEvents.onServiceStatusChanged(Status.Stopped.ordinal)
        oldEvents.onServiceAlert(0, "stale")
        oldDelegate.onBindingDied(null)
        oldDelegate.onServiceDisconnected(null)
        assertEquals(Status.Started, callback.status)
        assertEquals(0, callback.alerts)
        assertEquals(2, context.bindCount)
        assertEquals(Status.Started, connection.status)
        connection.disconnect()
    }

    @Test fun replacementRemoteOnSameBindingRetiresOldCallback() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)
        connection.connect()
        val delegate = requireNotNull(context.conn)
        val old = MockIService()
        delegate.onServiceConnected(ComponentName("fixture", "old"), old)
        val events = requireNotNull(old.registeredCallback)
        delegate.onServiceDisconnected(null)
        val next = MockIService()
        delegate.onServiceConnected(ComponentName("fixture", "next"), next)
        events.onServiceStatusChanged(Status.Stopped.ordinal)
        assertEquals(Status.Started, callback.status)
        assertNull(old.registeredCallback)
        connection.disconnect()
    }

    @Test fun deadRemoteStatusReturnsStoppedAndClearsReference() {
        val context = MockContext()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, MockCallback())
        connection.connect()
        var dead = false
        val service = object : MockIService() {
            override fun getStatus(): Int {
                if (dead) throw android.os.RemoteException()
                return Status.Started.ordinal
            }
        }
        requireNotNull(context.conn).onServiceConnected(ComponentName("fixture", "service"), service)
        dead = true
        assertEquals(Status.Stopped, connection.status)
        assertNull(service.registeredCallback)
        connection.disconnect()
    }

    @Test fun noRegistrationModeNeverRegistersOrUnregisters() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback, register = false)
        val service = object : MockIService() {
            override fun registerCallback(callback: IServiceCallback) { fail("No registration requested") }
            override fun unregisterCallback(callback: IServiceCallback?) { fail("Never registered") }
        }
        connection.connect()
        requireNotNull(context.conn).onServiceConnected(ComponentName("fixture", "service"), service)
        assertEquals(Status.Started, callback.status)
        connection.disconnect()
    }

    @Test fun bindingCanDieBeforeFirstConnectedAndStillRecover() {
        val context = MockContext()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, MockCallback())
        connection.connect()
        val first = requireNotNull(context.conn)
        first.onBindingDied(null)
        assertEquals(2, context.bindCount)
        assertEquals(1, context.unbindCount)
        assertNotSame(first, context.conn)
        connection.disconnect()
        first.onBindingDied(null)
        assertEquals(2, context.bindCount)
    }

    @Test fun nullBindingReleasesAndRejectsItsLateEvents() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)
        connection.connect()
        val delegate = requireNotNull(context.conn)
        delegate.onNullBinding(null)
        delegate.onServiceConnected(ComponentName("fixture", "late"), MockIService())
        assertEquals(1, context.unbindCount)
        assertEquals(Status.Stopped, connection.status)
        assertNull(callback.status)
    }

    @Test fun unknownRemoteEnumsAreIgnoredWithoutCrashing() {
        val context = MockContext()
        val callback = MockCallback()
        val connection = io.nekohasekai.sfa.bg.ServiceConnection(context, callback)
        connection.connect()
        val service = object : MockIService() { override fun getStatus() = Int.MAX_VALUE }
        requireNotNull(context.conn).onServiceConnected(ComponentName("fixture", "remote"), service)
        requireNotNull(service.registeredCallback).onServiceAlert(Int.MAX_VALUE, "invalid")
        assertNull(callback.status)
        assertEquals(0, callback.alerts)
        assertEquals(Status.Stopped, connection.status)
        connection.disconnect()
    }

}
