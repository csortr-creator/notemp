package io.nekohasekai.sfa.bg

import androidx.lifecycle.MutableLiveData
import io.nekohasekai.sfa.aidl.IServiceCallback
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ServiceBinderTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    private open class Callback : IServiceCallback {
        val statuses = mutableListOf<Int>()
        var alerts = 0
        override fun onServiceStatusChanged(status: Int) { statuses.add(status) }
        override fun onServiceAlert(type: Int, message: String?) { alerts++ }
    }

    @Test fun closeRemovesObserverAndCancelsQueuedStatusAndAlert() {
        val dispatcher = QueueDispatcher()
        val status = MutableLiveData(Status.Stopped)
        val binder = ServiceBinder(status, dispatcher)
        val callback = Callback()
        binder.registerCallback(callback)
        dispatcher.drain()
        callback.statuses.clear()
        status.value = Status.Started
        binder.broadcast { it.onServiceAlert(1, "test") }
        assertEquals(1, status.observerCount)
        binder.close()
        dispatcher.drain()
        assertEquals(0, status.observerCount)
        assertTrue(callback.statuses.isEmpty())
        assertEquals(0, callback.alerts)
    }

    @Test fun closedBinderRejectsLateRegistrationAndDoesNotQueueWork() {
        val dispatcher = QueueDispatcher()
        val status = MutableLiveData(Status.Stopped)
        val binder = ServiceBinder(status, dispatcher)
        dispatcher.drain()
        binder.close(); binder.close()
        val callback = Callback()
        binder.registerCallback(callback)
        binder.broadcast { it.onServiceAlert(1, "test") }
        status.value = Status.Started
        dispatcher.drain()
        assertTrue(dispatcher.queue.isEmpty())
        assertTrue(callback.statuses.isEmpty())
        assertEquals(0, callback.alerts)
    }

    @Test fun deadCallbackDoesNotBlockOthersOrTheNextBroadcast() {
        val dispatcher = QueueDispatcher()
        val status = MutableLiveData(Status.Stopped)
        val binder = ServiceBinder(status, dispatcher)
        dispatcher.drain()
        binder.registerCallback(object : Callback() {
            override fun onServiceStatusChanged(status: Int) { error("dead callback") }
        })
        val callback = Callback()
        binder.registerCallback(callback)
        status.value = Status.Starting
        status.value = Status.Started
        dispatcher.drain()
        assertEquals(listOf(Status.Starting.ordinal, Status.Started.ordinal), callback.statuses)
        binder.unregisterCallback(callback)
        status.value = Status.Stopped
        dispatcher.drain()
        assertEquals(2, callback.statuses.size)
        binder.close()
    }

    @Test fun closingFromCallbackSkipsRemainingRecipientsAndFinishesBroadcast() {
        val dispatcher = QueueDispatcher()
        val status = MutableLiveData(Status.Stopped)
        val binder = ServiceBinder(status, dispatcher)
        dispatcher.drain()
        binder.registerCallback(object : Callback() {
            override fun onServiceStatusChanged(status: Int) { binder.close() }
        })
        val callback = Callback()
        binder.registerCallback(callback)
        status.value = Status.Started
        dispatcher.drain()
        assertTrue(callback.statuses.isEmpty())
        assertEquals(0, status.observerCount)
    }

    @Test fun repeatedServiceInstancesDoNotAccumulateObservers() {
        val dispatcher = QueueDispatcher()
        val status = MutableLiveData(Status.Stopped)
        repeat(100) {
            val binder = ServiceBinder(status, dispatcher)
            assertEquals(1, status.observerCount)
            binder.close()
        }
        dispatcher.drain()
        assertEquals(0, status.observerCount)
    }
}
