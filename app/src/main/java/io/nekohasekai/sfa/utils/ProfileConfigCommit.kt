package io.nekohasekai.sfa.utils

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Safe config-file commit for remote subscription updates.
 *
 * - Unique temp file in the same directory as the target (no shared ".tmp" name).
 * - Content is fully written and verified before replace.
 * - Replace uses [StandardCopyOption.ATOMIC_MOVE] only; if the FS cannot atomic-move,
 *   the active file is left untouched and an error is thrown (no silent non-atomic fallback).
 * - Per-profile [Mutex] serializes concurrent commits; [beginOperation]/[isCurrent] guards
 *   stale HTTP responses that finish after a newer update started.
 */
object ProfileConfigCommit {

    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val generations = ConcurrentHashMap<Long, AtomicLong>()

    /** Profile ids removed by [markDeleted]; blocks further commits until cleared. */
    private val deletedIds = ConcurrentHashMap.newKeySet<Long>()

    /** Process-local successful file-replace counter (B3-3c). Separate from operation tokens. */
    private val writeGenerations = ConcurrentHashMap<Long, AtomicLong>()

    /**
     * Optional Room existence probe. Default allows commit (tests).
     * Production should set this so a process restart cannot resurrect a deleted id
     * via a stale in-memory profile list + new beginOperation token.
     */
    @Volatile
    var profileStillExists: (Long) -> Boolean = { true }

    /** Issue a monotonic operation token for [profileId]. Call before fetch. */
    fun beginOperation(profileId: Long): Long {
        val gen = generations.getOrPut(profileId) { AtomicLong(0L) }
        return gen.incrementAndGet()
    }

    fun isCurrent(profileId: Long, operationToken: Long): Boolean {
        val gen = generations[profileId] ?: return false
        return gen.get() == operationToken
    }

    /**
     * Bump generation so in-flight fetches with older tokens become [CommitOutcome.Stale].
     * Same effect as [beginOperation]; named for delete/update-cancel call sites.
     */
    fun invalidate(profileId: Long): Long = beginOperation(profileId)

    /**
     * Permanent (process-lifetime) tombstone for a deleted profile id.
     * Prevents a late [beginOperation] + [commit] from recreating config files after delete.
     * Call [clearDeleted] only if the same id is legitimately reused (rare with autoGenerate).
     */
    fun markDeleted(profileId: Long) {
        deletedIds.add(profileId)
        invalidate(profileId)
    }

    fun clearDeleted(profileId: Long) {
        deletedIds.remove(profileId)
    }

    fun isMarkedDeleted(profileId: Long): Boolean = deletedIds.contains(profileId)

    fun mutexFor(profileId: Long): Mutex = locks.getOrPut(profileId) { Mutex() }

    /** Run [block] under the per-profile commit/delete mutex. */
    suspend fun <T> withProfileLock(profileId: Long, block: suspend () -> T): T = mutexFor(profileId).withLock { block() }

    fun currentWriteGeneration(profileId: Long): Long = writeGenerations[profileId]?.get() ?: 0L

    private fun bumpWriteGeneration(profileId: Long): Long = writeGenerations.getOrPut(profileId) { AtomicLong(0L) }.incrementAndGet()

    /** SHA-256 of file bytes, or null if missing/unreadable. */
    fun fileSha256(file: File): String? {
        if (!file.isFile) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Snapshot under the per-profile Mutex so hash and write-generation match one file state.
     */
    suspend fun snapshotExpectedState(
        profileId: Long,
        configPath: String,
    ): ConfigExpectedState = mutexFor(profileId).withLock {
        val file = File(configPath)
        ConfigExpectedState(
            profileId = profileId,
            configPath = configPath,
            contentSha256 = fileSha256(file),
            writeGeneration = currentWriteGeneration(profileId),
        )
    }

    /** Bind a pre-fetch file snapshot to the operation that started the fetch. */
    suspend fun beginUpdate(profileId: Long, configPath: String): ConfigExpectedState {
        val operationToken = beginOperation(profileId)
        return snapshotExpectedState(profileId, configPath).copy(operationToken = operationToken)
    }

    /**
     * Conditional commit: apply [content] only if profile/file state still matches [expected].
     * A snapshot from [beginUpdate] also rejects responses superseded by a newer fetch.
     * Acquires the per-profile Mutex itself — do not call from under [withProfileLock].
     *
     * writeGeneration is bumped only when [replaceAtomically] returns true (bytes changed).
     */
    suspend fun <T> commitIfUnchanged(
        expected: ConfigExpectedState,
        content: String,
        validate: (String) -> Unit,
        sourceStillMatches: suspend () -> Boolean = { true },
        afterFileCommit: suspend () -> T,
    ): CommitOutcome<T> {
        val profileId = expected.profileId
        mutexFor(profileId).withLock {
            if (isMarkedDeleted(profileId)) return CommitOutcome.Stale
            if (!profileStillExists(profileId)) return CommitOutcome.Stale
            if (expected.operationToken?.let { !isCurrent(profileId, it) } == true) {
                return CommitOutcome.Stale
            }
            currentCoroutineContext().ensureActive()
            if (!sourceStillMatches()) return CommitOutcome.Stale
            val target = File(expected.configPath)
            // Path identity: target must still be the expected path string (caller binds path).
            if (target.path != File(expected.configPath).path) return CommitOutcome.Stale
            val currentHash = fileSha256(target)
            if (currentHash != expected.contentSha256) return CommitOutcome.Stale
            if (currentWriteGeneration(profileId) != expected.writeGeneration) {
                return CommitOutcome.Stale
            }
            try {
                validate(content)
            } catch (e: Exception) {
                return CommitOutcome.Failed(e)
            }
            currentCoroutineContext().ensureActive()
            val replaced =
                try {
                    replaceAtomically(target, content)
                } catch (e: Exception) {
                    return CommitOutcome.Failed(e)
                }
            if (replaced) {
                bumpWriteGeneration(profileId)
            }
            return try {
                val meta = afterFileCommit()
                CommitOutcome.Success(replaced = replaced, metadata = meta)
            } catch (e: Exception) {
                CommitOutcome.FileCommittedMetadataFailed(replaced = replaced, error = e)
            }
        }
    }

    /**
     * Atomically replace [target] with [content], or leave [target] unchanged on any failure.
     *
     * @return true if the file bytes were replaced; false if existing content was already identical
     *         (no write performed).
     */
    fun replaceAtomically(target: File, content: String): Boolean {
        val parent =
            target.parentFile
                ?: throw IOException("Config path has no parent directory: ${target.path}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create config directory: ${parent.path}")
        }

        if (target.exists()) {
            val existing =
                try {
                    target.readText()
                } catch (_: Exception) {
                    null
                }
            if (existing == content) return false
        }

        val tmp =
            File(
                parent,
                ".sfax-commit-${target.name}-${UUID.randomUUID()}.tmp",
            )
        try {
            writeFully(tmp, content)
            val verified =
                try {
                    tmp.readText()
                } catch (e: Exception) {
                    throw IOException("Failed to verify temporary config: ${tmp.path}", e)
                }
            if (verified != content) {
                throw IOException("Temporary config content mismatch after write")
            }

            val src = tmp.toPath()
            val dst = target.toPath()
            try {
                Files.move(
                    src,
                    dst,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException(
                    "Atomic move not supported on this filesystem; refusing unsafe config replace",
                    e,
                )
            }
            // move consumed tmp
            return true
        } catch (e: Exception) {
            // Ensure active file was not partially overwritten (we never write to target directly).
            throw e
        } finally {
            if (tmp.exists()) {
                runCatching { tmp.delete() }
            }
        }
    }

    /**
     * Commit under per-profile lock with stale-operation check.
     *
     * [validate] runs inside the lock (e.g. Libbox.checkConfig) before any write.
     * [afterFileCommit] runs only after a successful file replace or skip-identical
     * (report + metadata). Failures there are returned as [CommitOutcome.FileCommittedMetadataFailed]
     * without rolling back the already-committed file.
     */
    suspend fun <T> commit(
        profileId: Long,
        operationToken: Long,
        target: File,
        content: String,
        validate: (String) -> Unit,
        afterFileCommit: suspend () -> T,
    ): CommitOutcome<T> {
        mutexFor(profileId).withLock {
            if (isMarkedDeleted(profileId) || !isCurrent(profileId, operationToken)) {
                return CommitOutcome.Stale
            }
            // Survive process restart: tombstones are process-lifetime only.
            if (!profileStillExists(profileId)) {
                return CommitOutcome.Stale
            }
            try {
                validate(content)
            } catch (e: Exception) {
                return CommitOutcome.Failed(e)
            }
            val replaced =
                try {
                    replaceAtomically(target, content)
                } catch (e: Exception) {
                    return CommitOutcome.Failed(e)
                }
            if (replaced) {
                bumpWriteGeneration(profileId)
            }
            return try {
                val meta = afterFileCommit()
                CommitOutcome.Success(replaced = replaced, metadata = meta)
            } catch (e: Exception) {
                CommitOutcome.FileCommittedMetadataFailed(replaced = replaced, error = e)
            }
        }
    }

    /**
     * Editor save path: replace under mutex, bump write generation when bytes change.
     * Call only outside outer withProfileLock (acquires mutex itself) OR use from
     * code that does not already hold the lock — this acquires [mutexFor].
     * Returns false only for identical content; unavailable profiles throw before any write.
     */
    suspend fun applyEditorWrite(profileId: Long, target: File, content: String): Boolean {
        mutexFor(profileId).withLock {
            check(!isMarkedDeleted(profileId) && profileStillExists(profileId)) {
                "Профиль удалён или недоступен"
            }
            val replaced = replaceAtomically(target, content)
            if (replaced) bumpWriteGeneration(profileId)
            return replaced
        }
    }

    private fun writeFully(file: File, content: String) {
        FileOutputStream(file).use { fos ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            fos.write(bytes)
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Exception) {
                // Best-effort fsync; still rely on ATOMIC_MOVE of a fully written temp.
            }
        }
    }
}

data class ConfigExpectedState(
    val profileId: Long,
    val configPath: String,
    val contentSha256: String?,
    val writeGeneration: Long,
    val operationToken: Long? = null,
)

sealed class CommitOutcome<out T> {
    data class Success<T>(val replaced: Boolean, val metadata: T) : CommitOutcome<T>()

    /** File on disk is the new content; report/DB update failed. */
    data class FileCommittedMetadataFailed(val replaced: Boolean, val error: Throwable) : CommitOutcome<Nothing>()

    data class Failed(val error: Throwable) : CommitOutcome<Nothing>()

    data object Stale : CommitOutcome<Nothing>()
}
