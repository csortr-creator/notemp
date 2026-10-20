package io.nekohasekai.sfa.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

/** Each Android binding and remote callback owns its own revocable identity. */
class ServiceConnection(private val context: Context, private val callback: Callback, private val register: Boolean = true) {
    companion object { private const val TAG = "ServiceConnection" }

    @Volatile private var delegate: Delegate? = null

    private inner class Delegate : android.content.ServiceConnection {
        @Volatile var remote: IService? = null
        @Volatile var events: RemoteEvents? = null
        var bindingRequested = false

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (delegate !== this) return
            clearRemote(this)
            val remote = IService.Stub.asInterface(binder)
            val events = RemoteEvents(this, remote)
            this.remote = remote
            this.events = events
            try {
                if (register) remote.registerCallback(events)
                if (events.isCurrent()) events.onServiceStatusChanged(remote.getStatus())
            } catch (_: RemoteException) {
                clearRemote(this)
                Log.w(TAG, "SERVICE_INITIALIZATION_FAILED")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (delegate === this) clearRemote(this)
            // Android keeps the binding and can deliver a new connected event.
        }

        override fun onBindingDied(name: ComponentName?) {
            if (delegate === this) reconnect()
        }

        override fun onNullBinding(name: ComponentName?) {
            if (delegate === this) disconnect()
        }
    }

    private inner class RemoteEvents(val owner: Delegate, val remote: IService) : IServiceCallback.Stub() {
        fun isCurrent() = delegate === owner && owner.remote === remote && owner.events === this
        override fun onServiceStatusChanged(status: Int) {
            if (isCurrent()) Status.values().getOrNull(status)?.let(callback::onServiceStatusChanged)
        }
        override fun onServiceAlert(type: Int, message: String?) {
            if (isCurrent()) Alert.values().getOrNull(type)?.let { callback.onServiceAlert(it, message) }
        }
    }

    val status: Status
        get() {
            val owner = delegate ?: return Status.Stopped
            val remote = owner.remote ?: return Status.Stopped
            return try {
                val status = Status.values().getOrNull(remote.getStatus()) ?: Status.Stopped
                if (delegate === owner && owner.remote === remote) status else Status.Stopped
            } catch (_: RemoteException) {
                if (owner.remote === remote) clearRemote(owner)
                Status.Stopped
            }
        }

    fun connect() {
        if (delegate != null) return
        val intent = runBlocking {
            withContext(Dispatchers.IO) {
                Intent(context, Settings.serviceClass()).setAction(Action.SERVICE)
            }
        }
        val owner = Delegate()
        delegate = owner
        owner.bindingRequested = true
        try {
            if (!context.bindService(intent, owner, AppCompatActivity.BIND_AUTO_CREATE)) {
                if (delegate === owner) delegate = null
                clearRemote(owner)
                unbind(owner)
            }
        } catch (_: Exception) {
            if (delegate === owner) delegate = null
            clearRemote(owner)
            unbind(owner)
            Log.w(TAG, "SERVICE_BIND_FAILED")
        }
    }

    fun disconnect() {
        val owner = delegate ?: return
        // Retire before IPC/unbind: already queued callbacks cannot update the UI.
        delegate = null
        clearRemote(owner)
        unbind(owner)
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    private fun clearRemote(owner: Delegate) {
        val remote = owner.remote
        val events = owner.events
        owner.remote = null
        owner.events = null
        if (register && remote != null && events != null) {
            try { remote.unregisterCallback(events) } catch (_: RemoteException) {
                Log.w(TAG, "SERVICE_CALLBACK_CLEANUP_FAILED")
            }
        }
    }

    private fun unbind(owner: Delegate) {
        if (!owner.bindingRequested) return
        owner.bindingRequested = false
        try { context.unbindService(owner) } catch (_: IllegalArgumentException) { }
    }

    interface Callback {
        fun onServiceStatusChanged(status: Status)
        fun onServiceAlert(type: Alert, message: String?) { }
    }
}
