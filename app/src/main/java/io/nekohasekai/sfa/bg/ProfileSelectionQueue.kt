package io.nekohasekai.sfa.bg

/** Serial selections with one replaceable pending request; no Android or service side effects. */
class ProfileSelectionQueue(private val cancellationGeneration: () -> Long = { 0L }) {
    data class Request(val profileId: Long, val connect: Boolean)

    private var active: Request? = null
    private var pending: Request? = null
    private var activeCancelled = false
    private var seenCancellation = cancellationGeneration()

    // Read synchronously before dispatch, so a delayed UI collector cannot
    // restart the VPN after a notification/Tile Stop. Retain the active slot
    // until its coroutine finishes cleaning up, as cancelAll does.
    private fun refreshCancellation() {
        val current = cancellationGeneration()
        if (current != seenCancellation) {
            seenCancellation = current
            pending = null
            activeCancelled = true
        }
    }

    @Synchronized
    fun offer(profileId: Long, serviceWantsConnection: Boolean) {
        refreshCancellation()
        pending = Request(
            profileId,
            serviceWantsConnection ||
                (!activeCancelled && active?.connect == true) || pending?.connect == true,
        )
    }

    @Synchronized
    fun take(serviceStable: Boolean): Request? {
        refreshCancellation()
        if (!serviceStable || active != null) return null
        val request = pending ?: return null
        pending = null
        active = request
        activeCancelled = false
        return request
    }

    @Synchronized
    fun complete(request: Request) {
        if (active === request) active = null
    }

    @Synchronized
    fun cancelAll() {
        pending = null
        // Retain the active slot until its coroutine has finished cleaning up.
        activeCancelled = true
    }

    /** Serialize dispatch with cancellation; a cancelled request cannot dispatch a start. */
    @Synchronized
    fun runIfCurrent(request: Request, action: () -> Unit): Boolean {
        refreshCancellation()
        if (active !== request || activeCancelled) return false
        action()
        return true
    }
}
