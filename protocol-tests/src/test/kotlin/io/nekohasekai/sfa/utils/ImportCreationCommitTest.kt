package io.nekohasekai.sfa.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImportCreationCommitTest {
    private fun directory(): File = Files.createTempDirectory("import-create-").toFile()
    @Test fun validationFailurePublishesNothingAndHasTypedReason() = runBlocking {
        val directory = directory()
        try {
            try {
                ImportCreationCommit.create(directory, "secret", { error("private-key") }) { fail("No INSERT") }
                fail()
            } catch (error: ImportCreationFailure) {
                assertEquals(ImportCreationStage.VALIDATE, error.stage)
                assertTrue(ImportResultFormatter.creationFailure(error).contains("CONFIG_INVALID"))
                assertFalse(ImportResultFormatter.creationFailure(error).contains("private-key"))
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun reportFailureCleansConfigAndPartialReportBeforeInsert() = runBlocking {
        val directory = directory()
        try {
            try {
                ImportCreationCommit.create(directory, "config", {}, saveReport = {
                    File("${it.path}.import.json").writeText("partial")
                    error("report disk failure")
                }) { fail("No INSERT") }
                fail()
            } catch (error: ImportCreationFailure) { assertEquals(ImportCreationStage.REPORT, error.stage) }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun databaseFailureCleansBothFilesAndRetainsExistingProfile() = runBlocking {
        val directory = directory()
        val existing = File(directory, "1.json").apply { writeText("existing") }
        try {
            try {
                ImportCreationCommit.create(directory, "new", {}, saveReport = { File("${it.path}.import.json").writeText("report") }) {
                    error("db private query")
                }
                fail()
            } catch (error: ImportCreationFailure) {
                assertEquals(ImportCreationStage.DATABASE, error.stage)
                assertTrue(ImportResultFormatter.creationFailure(error).contains("PROFILE_DATABASE_FAILED"))
            }
            assertEquals(listOf(existing), directory.listFiles()!!.toList())
            assertEquals("existing", existing.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun parallelInitialImportsNeverSharePaths() = runBlocking {
        val directory = directory()
        try {
            val files = (1..40).map { number -> async(Dispatchers.IO) {
                ImportCreationCommit.create(directory, "$number", {}, saveReport = { File("${it.path}.import.json").writeText("$number") }) {
                    assertEquals("$number", it.readText())
                    delay(2)
                    it
                }
            } }.awaitAll()
            assertEquals(40, files.map { it.path }.toSet().size)
            assertEquals(80, directory.listFiles()!!.size)
            assertEquals((1..40).map { "$it" }.toSet(), files.map { it.readText() }.toSet())
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancellationBeforeDatabaseCleansPreparedFiles() = runBlocking {
        val directory = directory()
        try {
            val task = launch(Dispatchers.IO) {
                val ownJob = currentCoroutineContext().job
                ImportCreationCommit.create(directory, "new", {}, saveReport = { ownJob.cancel() }) { fail("No INSERT") }
            }
            task.join()
            assertTrue(task.isCancelled)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancellationDuringDatabaseKeepsPublishedFiles() = runBlocking {
        val directory = directory()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var committed: File? = null
        try {
            val task = launch(Dispatchers.IO) {
                ImportCreationCommit.create(directory, "new", {}) {
                    entered.complete(Unit)
                    finish.await()
                    committed = it
                }
            }
            entered.await()
            task.cancel()
            finish.complete(Unit)
            task.join()
            assertTrue(task.isCancelled)
            assertEquals("new", requireNotNull(committed).readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun unusableDirectoryIsReportedAsWriteFailure() = runBlocking {
        val directory = directory()
        try {
            val file = File(directory, "not-a-directory").apply { writeText("keep") }
            try { ImportCreationCommit.create(file, "new", {}) { fail("No INSERT") }; fail() }
            catch (error: ImportCreationFailure) { assertEquals(ImportCreationStage.WRITE, error.stage) }
            assertEquals("keep", file.readText())
        } finally { directory.deleteRecursively() }
    }
}
