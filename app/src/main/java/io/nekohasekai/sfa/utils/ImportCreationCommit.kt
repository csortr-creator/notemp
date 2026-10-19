package io.nekohasekai.sfa.utils

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException

internal enum class ImportCreationStage { VALIDATE, WRITE, REPORT, DATABASE }
internal class ImportCreationFailure(val stage: ImportCreationStage, cause: Exception) : Exception(cause)

/** New files remain private until a database row publishes their unique path. */
internal object ImportCreationCommit {
    suspend fun <T> create(
        directory: File,
        content: String,
        validate: (String) -> Unit,
        saveReport: (File) -> Unit = {},
        persist: suspend (File) -> T,
    ): T {
        var stage = ImportCreationStage.VALIDATE
        var file: File? = null
        var published = false
        try {
            currentCoroutineContext().ensureActive()
            validate(content)
            stage = ImportCreationStage.WRITE
            check(directory.isDirectory || directory.mkdirs()) { "CONFIG_DIRECTORY_UNAVAILABLE" }
            // MAX(id)+1 is not a file reservation: two imports may fetch concurrently.
            val target = File.createTempFile("profile-", ".json", directory)
            file = target
            FileOutputStream(target).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            stage = ImportCreationStage.REPORT
            saveReport(target)
            currentCoroutineContext().ensureActive()
            stage = ImportCreationStage.DATABASE
            // Cancellation after INSERT must never remove files referenced by the row.
            return withContext(NonCancellable) {
                persist(target).also { published = true }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ImportCreationFailure(stage, error)
        } finally {
            if (!published) file?.let { target ->
                runCatching { File("${target.path}.import.json").delete() }
                runCatching { target.delete() }
            }
        }
    }
}
