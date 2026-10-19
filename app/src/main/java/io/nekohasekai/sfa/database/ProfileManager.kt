package io.nekohasekai.sfa.database

import androidx.room.Room
import androidx.room.withTransaction
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun notifyCallbacks() {
        callbacks.forEach { callback ->
            scope.launch {
                callback()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        Application.application.getDatabasePath(Path.PROFILES_DATABASE_PATH).parentFile?.mkdirs()
        Room
            .databaseBuilder(
                Application.application,
                ProfileDatabase::class.java,
                Path.PROFILES_DATABASE_PATH,
            )
            .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }

    suspend fun nextOrder(): Long = instance.profileDao().nextOrder() ?: 0

    suspend fun nextFileID(): Long = instance.profileDao().nextFileID() ?: 1

    suspend fun get(id: Long): Profile? = instance.profileDao().get(id)

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        profile.id = instance.profileDao().insert(profile)
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        notifyCallbacks()
        return profile
    }

    /** Publish a fully prepared config once; post-save selection cannot undo the row. */
    internal suspend fun createImported(
        profile: Profile,
        config: String,
        report: io.nekohasekai.sfa.utils.SubscriptionImportReport? = null,
    ): Profile {
        io.nekohasekai.sfa.utils.ImportCreationCommit.create(
            directory = java.io.File(Application.application.filesDir, "configs"),
            content = config,
            validate = { io.nekohasekai.libbox.Libbox.checkConfig(it) },
            saveReport = { file -> report?.save(file.path) },
            persist = { file ->
                profile.typed.path = file.path
                instance.withTransaction {
                    profile.userOrder = instance.profileDao().nextOrder() ?: 0L
                    profile.id = instance.profileDao().insert(profile)
                }
            },
        )
        // These side effects happen after durable creation. Their failure must not
        // present an already saved profile as an import failure and encourage duplicates.
        runCatching { Settings.selectedProfile = profile.id }.onFailure {
            android.util.Log.w("ProfileManager", "PROFILE_SELECTION_FAILED")
        }
        notifyCallbacks()
        return profile
    }

    /** Update only fetch metadata from the latest row; never restore a pre-fetch profile snapshot. */
    suspend fun markSubscriptionUpdated(id: Long, path: String, remoteURL: String): Date {
        val updatedAt = instance.withTransaction {
            val current = instance.profileDao().get(id) ?: error("PROFILE_UNAVAILABLE")
            check(current.typed.type == TypedProfile.Type.Remote &&
                current.typed.path == path && current.typed.remoteURL == remoteURL) { "PROFILE_SOURCE_CHANGED" }
            val now = Date()
            current.typed.lastUpdated = now
            check(instance.profileDao().update(current) == 1) { "PROFILE_UNAVAILABLE" }
            now
        }
        notifyCallbacks()
        return updatedAt
    }

    suspend fun subscriptionSourceMatches(id: Long, path: String, remoteURL: String): Boolean {
        val current = get(id) ?: return false
        return current.typed.type == TypedProfile.Type.Remote &&
            current.typed.path == path && current.typed.remoteURL == remoteURL
    }

    suspend fun update(profile: Profile): Int {
        try {
            return instance.profileDao().update(profile)
        } finally {
            notifyCallbacks()
        }
    }

    suspend fun updateOrder(profiles: List<Profile>): Int {
        try {
            return instance.withTransaction {
                profiles.sumOf { instance.profileDao().updateUserOrder(it.id, it.userOrder) }
            }
        } finally {
            notifyCallbacks()
        }
    }

    suspend fun delete(profile: Profile): Int {
        try {
            return instance.profileDao().delete(profile).also {
                if (it > 0) java.io.File("${profile.typed.path}.import.json").delete()
            }
        } finally {
            notifyCallbacks()
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().delete(profiles).also {
                if (it == profiles.size) {
                    profiles.forEach { profile ->
                        java.io.File("${profile.typed.path}.import.json").delete()
                    }
                }
            }
        } finally {
            notifyCallbacks()
        }
    }

    suspend fun list(): List<Profile> = instance.profileDao().list()

    fun remoteServerDao(): RemoteServer.Dao = instance.remoteServerDao()
}
