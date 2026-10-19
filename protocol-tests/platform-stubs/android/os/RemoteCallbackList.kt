package android.os

class RemoteCallbackList<T> {
    private val callbacks = LinkedHashSet<T>()
    private var killed = false
    private var broadcasting: List<T>? = null
    @Synchronized fun register(callback: T): Boolean = !killed && callbacks.add(callback)
    @Synchronized fun unregister(callback: T?): Boolean = callbacks.remove(callback)
    @Synchronized fun beginBroadcast(): Int {
        check(broadcasting == null)
        broadcasting = callbacks.toList()
        return broadcasting!!.size
    }
    @Synchronized fun getBroadcastItem(index: Int): T = broadcasting!![index]
    @Synchronized fun finishBroadcast() { check(broadcasting != null); broadcasting = null }
    @Synchronized fun kill() { killed = true; callbacks.clear() }
}
