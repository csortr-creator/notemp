package io.nekohasekai.sfa.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sfa.aidl.IService
import io.nekohasekai.sfa.aidl.IServiceCallback
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ServiceConnection(private val context: Context, callback: Callback, private val register: Boolean = true) {

    private inner class Delegate : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (this != delegate) return
            this@ServiceConnection.onServiceConnected(name, binder)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (this != delegate) return
            this@ServiceConnection.onServiceDisconnected(name)
        }
        override fun onBindingDied(name: ComponentName?) {
            if (this != delegate) return
            this@ServiceConnection.onBindingDied(name)
        }
    }

    private var delegate: Delegate? = null
    companion object {
        private const val TAG = "ServiceConnection"
    }

    private val callback = ServiceCallback(callback)
    private var service: IService? = null

    val status get() = service?.getStatus()?.let { Status.values()[it] } ?: Status.Stopped

    fun connect() {
        val intent =
            runBlocking {
                withContext(Dispatchers.IO) {
                    Intent(context, Settings.serviceClass()).setAction(Action.SERVICE)
                }
            }
        delegate = Delegate()
        context.bindService(intent, delegate!!, AppCompatActivity.BIND_AUTO_CREATE)
        Log.d(TAG, "request connect")
    }

    fun disconnect() {
        try {
            service?.unregisterCallback(callback)
        } catch (e: RemoteException) {
            Log.e(TAG, "cleanup service connection", e)
        }
        try {
            delegate?.let { context.unbindService(it) }
        } catch (_: IllegalArgumentException) {
        }
        delegate = null
        service = null
        Log.d(TAG, "request disconnect")
    }

    fun reconnect() {
        try {
            service?.unregisterCallback(callback)
        } catch (e: RemoteException) {
            Log.e(TAG, "cleanup service connection", e)
        }
        try {
            delegate?.let { context.unbindService(it) }
        } catch (_: IllegalArgumentException) {
        }
        delegate = null
        service = null
        val intent =
            runBlocking {
                withContext(Dispatchers.IO) {
                    Intent(context, Settings.serviceClass()).setAction(Action.SERVICE)
                }
            }
        delegate = Delegate()
        context.bindService(intent, delegate!!, AppCompatActivity.BIND_AUTO_CREATE)
        Log.d(TAG, "request reconnect")
    }

    fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val service = IService.Stub.asInterface(binder)
        this.service = service
        try {
            if (register) service.registerCallback(callback)
            callback.onServiceStatusChanged(service.getStatus())
        } catch (e: RemoteException) {
            Log.e(TAG, "initialize service connection", e)
        }
        Log.d(TAG, "service connected")
    }

    fun onServiceDisconnected(name: ComponentName?) {
        try {
            service?.unregisterCallback(callback)
        } catch (e: RemoteException) {
            Log.e(TAG, "cleanup service connection", e)
        }
        service = null
        Log.d(TAG, "service disconnected")
    }

    fun onBindingDied(name: ComponentName?) {
        reconnect()
        Log.d(TAG, "service dead")
    }

    interface Callback {
        fun onServiceStatusChanged(status: Status)

        fun onServiceAlert(type: Alert, message: String?) {
        }
    }

    class ServiceCallback(private val callback: Callback) : IServiceCallback.Stub() {
        override fun onServiceStatusChanged(status: Int) {
            callback.onServiceStatusChanged(Status.values()[status])
        }

        override fun onServiceAlert(type: Int, message: String?) {
            callback.onServiceAlert(Alert.values()[type], message)
        }
    }
}
