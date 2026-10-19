package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CompletableDeferred

/** Accessed only by the network actor. Android callbacks carry the registration generation. */
internal class NetworkListenerState<N : Any>(
    private val register: (Long) -> N?,
    private val unregister: () -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val listeners = linkedMapOf<Any, (N?) -> Unit>()
    private val pending = linkedSetOf<CompletableDeferred<N>>()
    private var network: N? = null
    var generation = 0L
        private set
    val pendingCount: Int get() = pending.size

    fun start(key: Any, listener: (N?) -> Unit) {
        if (listeners.isEmpty()) {
            val token = ++generation
            try {
                network = register(token)
            } catch (e: Exception) {
                generation++
                network = null
                runCatching { unregister() }
                throw e
            }
        }
        listeners[key] = listener
        network?.let { notify(listener, it) }
    }

    fun get(response: CompletableDeferred<N>, fallback: (() -> N?)? = null) {
        if (!response.isActive) return
        if (listeners.isEmpty()) {
            response.completeExceptionally(IllegalStateException("Network monitor is not running"))
            return
        }
        val current = if (fallback != null) fallback() else network
        if (current != null) response.complete(current)
        else if (fallback != null) response.completeExceptionally(IllegalStateException("Missing default network"))
        else pending.add(response)
    }

    fun cancelGet(response: CompletableDeferred<N>) {
        pending.remove(response)
        response.cancel()
    }

    fun stop(key: Any, expectedListener: ((N?) -> Unit)? = null) {
        if (expectedListener != null && listeners[key] !== expectedListener) return
        if (listeners.remove(key) == null || listeners.isNotEmpty()) return
        generation++
        network = null
        pending.forEach { it.cancel() }
        pending.clear()
        try { unregister() } catch (e: Exception) { onFailure(e) }
    }

    fun available(token: Long, value: N) {
        if (token != generation || listeners.isEmpty()) return
        network = value
        pending.forEach { it.complete(value) }
        pending.clear()
        listeners.values.toList().forEach { notify(it, value) }
    }

    fun update(token: Long, value: N) {
        if (token != generation || network != value || listeners.isEmpty()) return
        listeners.values.toList().forEach { notify(it, value) }
    }

    fun lost(token: Long, value: N) {
        if (token != generation || network != value || listeners.isEmpty()) return
        network = null
        listeners.values.toList().forEach { notify(it, null) }
    }

    private fun notify(listener: (N?) -> Unit, value: N?) {
        try { listener(value) } catch (e: Exception) { onFailure(e) }
    }
}
