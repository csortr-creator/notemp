package io.nekohasekai.sfa.bg

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local view of which profile config the VPN runtime last accepted.
 * Not a substitute for Status.Started and does not imply network reachability.
 *
 * Safe diagnostics only: profile ids and short content fingerprints — no URLs/keys/JSON.
 *
 * R2a: [tryMarkLoaded] applies only when [requestId] is still the latest switch id
 * ([currentSwitchRequestId]), discarding stale start completions after supersede/timeout.
 */
object RuntimeProfileState {
    private val switchIds = AtomicLong(0L)
    @Volatile
    private var userStopId = 0L

    /** Invalidate in-flight starts and queued selections for an explicit user Stop. */
    @Synchronized
    fun requestUserStop(): Long {
        val id = nextSwitchRequestId()
        userStopId = id
        return id
    }

    fun currentUserStopId(): Long = userStopId

    private val deletingProfileIds = mutableSetOf<Long>()

    @Volatile
    var startingProfileId: Long = -1L
        private set

    @Volatile
    var loadedProfileId: Long = -1L
        private set

    /** First 16 hex chars of SHA-256 of the runtime config string last accepted. */
    @Volatile
    var loadedConfigFingerprint: String = ""
        private set

    /** Monotonic id for switch/start attempts. */
    @Synchronized
    fun nextSwitchRequestId(): Long = switchIds.incrementAndGet()

    fun currentSwitchRequestId(): Long = switchIds.get()

    @Synchronized
    fun isStartCurrent(requestId: Long, profileId: Long): Boolean = requestId == currentSwitchRequestId() && profileId !in deletingProfileIds

    /** Register the target before reading its configuration or starting the native service. */
    @Synchronized
    fun beginStart(requestId: Long, profileId: Long): Boolean {
        if (!isStartCurrent(requestId, profileId)) return false
        startingProfileId = profileId
        return true
    }

    /** Block new/late starts; report ownership independently of Settings.selectedProfile. */
    @Synchronized
    fun blockProfileForDeletion(profileId: Long): Boolean {
        deletingProfileIds.add(profileId)
        val usesProfile = usesProfile(profileId)
        if (usesProfile) nextSwitchRequestId()
        return usesProfile
    }

    @Synchronized
    fun usesProfile(profileId: Long): Boolean = startingProfileId == profileId || loadedProfileId == profileId

    /** Prefer actual ownership; selectedProfile is only a fallback before runtime is known. */
    @Synchronized
    fun shouldStopForDeletion(profileId: Long, selectedProfileId: Long, serviceActive: Boolean): Boolean = if (startingProfileId != -1L || loadedProfileId != -1L) {
        usesProfile(profileId)
    } else {
        serviceActive && selectedProfileId == profileId
    }

    @Synchronized
    fun unblockProfileAfterFailedDelete(profileId: Long) {
        deletingProfileIds.remove(profileId)
    }

    /**
     * Accept a successful start only if [requestId] is still the current switch generation.
     * @return true if loaded state was updated; false if the completion is stale/superseded.
     */
    @Synchronized
    fun tryMarkLoaded(requestId: Long, profileId: Long, runtimeConfig: String): Boolean {
        if (!isStartCurrent(requestId, profileId)) {
            return false
        }
        markLoaded(profileId, runtimeConfig)
        return true
    }

    @Synchronized
    fun markLoaded(profileId: Long, runtimeConfig: String) {
        loadedProfileId = profileId
        loadedConfigFingerprint = fingerprint(runtimeConfig)
        startingProfileId = -1L
    }

    @Synchronized
    fun clear() {
        loadedProfileId = -1L
        loadedConfigFingerprint = ""
        startingProfileId = -1L
    }

    fun fingerprint(content: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(content.toByteArray(Charsets.UTF_8))
        return dig.take(8).joinToString("") { b -> "%02x".format(b) }
    }

    fun selectedMatchesLoaded(selectedProfileId: Long): Boolean = loadedProfileId != -1L && loadedProfileId == selectedProfileId
}
