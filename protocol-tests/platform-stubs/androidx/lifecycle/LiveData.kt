package androidx.lifecycle

fun interface Observer<T> { fun onChanged(value: T) }
class MutableLiveData<T>(initial: T) {
    private val observers = LinkedHashSet<Observer<in T>>()
    val observerCount get() = observers.size
    var value: T? = initial
        set(value) {
            field = value
            if (value != null) observers.toList().forEach { it.onChanged(value) }
        }
    fun observeForever(observer: Observer<in T>) {
        observers.add(observer)
        value?.let { observer.onChanged(it) }
    }
    fun removeObserver(observer: Observer<in T>) { observers.remove(observer) }
}
