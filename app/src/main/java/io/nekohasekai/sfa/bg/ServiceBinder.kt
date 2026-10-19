package io.nekohasekai.sfa.bg

import android.os.RemoteCallbackList
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import io.nekohasekai.sfa.aidl.IService
import io.nekohasekai.sfa.aidl.IServiceCallback
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class ServiceBinder(
    private val status: MutableLiveData<Status>,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : IService.Stub() {
    private val callbacks = RemoteCallbackList<IServiceCallback>()
    private val broadcastLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)
    private val statusObserver = Observer<Status> { value ->
        broadcast { callback -> callback.onServiceStatusChanged(value.ordinal) }
    }

    init {
        status.observeForever(statusObserver)
    }

    fun broadcast(work: (IServiceCallback) -> Unit) {
        if (closed.get()) return
        scope.launch {
            broadcastLock.withLock {
                if (closed.get()) return@withLock
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        if (closed.get()) return@repeat
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }
    }

    override fun getStatus(): Int = (status.value ?: Status.Stopped).ordinal

    override fun registerCallback(callback: IServiceCallback) {
        if (!closed.get()) callbacks.register(callback)
    }

    override fun unregisterCallback(callback: IServiceCallback?) {
        callbacks.unregister(callback)
    }

    fun close() {
        // Constructed and closed by the service on Main, as LiveData requires.
        if (!closed.compareAndSet(false, true)) return
        status.removeObserver(statusObserver)
        scope.cancel()
        callbacks.kill()
    }
}
