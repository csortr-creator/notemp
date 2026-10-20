package io.nekohasekai.sfa.bg

/** Serialized owner for experimental native networking; no Android/JNI dependency. */
internal class BridgeNetworkLifecycle<N : Any, P : Any>(
    private val capture: (N) -> P,
    private val backend: Backend<P>,
) {
    interface Backend<P> {
        fun start(config: String, provider: P)
        fun update(provider: P)
        fun suspendNetwork()
        fun stop()
    }

    enum class Phase { STOPPED, WAITING_FOR_NETWORK, RUNNING, SUSPENDED, FAILED }
    private var currentPhase = Phase.STOPPED
    val phase: Phase @Synchronized get() = currentPhase

    private var generation = 0L
    private var config: String? = null
    private var started = false
    private var suspended = false

    @Synchronized
    fun begin(value: String): Long {
        generation++
        config = null
        currentPhase = Phase.STOPPED
        retire()
        config = value
        currentPhase = Phase.WAITING_FOR_NETWORK
        return generation
    }

    @Synchronized
    fun network(token: Long, network: N?) {
        if (token != generation) return
        val current = config ?: return
        try {
            if (network == null) {
                if (started && !suspended) {
                    backend.suspendNetwork()
                    suspended = true
                }
                currentPhase = if (started) Phase.SUSPENDED else Phase.WAITING_FOR_NETWORK
                return
            }
            val provider = capture(network)
            if (started) backend.update(provider) else {
                // Treat a partially failed start as owned, so catch always retires it.
                started = true
                backend.start(current, provider)
            }
            suspended = false
            currentPhase = Phase.RUNNING
        } catch (error: Exception) {
            currentPhase = Phase.FAILED
            try { retire() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    @Synchronized
    fun end(token: Long) {
        if (token != generation) return
        generation++
        config = null
        currentPhase = Phase.STOPPED
        retire()
    }

    private fun retire() {
        val wasStarted = started
        started = false
        suspended = false
        if (wasStarted) backend.stop()
    }
}
