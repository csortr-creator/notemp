package io.nekohasekai.sfa.utils

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionUpdateOrderingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun allowExistingProfiles() {
        ProfileConfigCommit.profileStillExists = { true }
    }

    @Test
    fun slowFetchCannotReplaceNewerCompletedUpdate() = runBlocking {
        val target = tmp.newFile("newer.json").apply { writeText("LKG") }
        val profileId = 61001L
        val older = ProfileConfigCommit.beginUpdate(profileId, target.path)
        val newer = ProfileConfigCommit.beginUpdate(profileId, target.path)
        val newerResult = ProfileConfigCommit.commitIfUnchanged(
            expected = newer,
            content = "NEWER",
            validate = {},
            afterFileCommit = {},
        )
        assertTrue(newerResult is CommitOutcome.Success)

        var validated = false
        var metadataWritten = false
        val olderResult = ProfileConfigCommit.commitIfUnchanged(
            expected = older,
            content = "OLDER",
            validate = { validated = true },
            afterFileCommit = { metadataWritten = true },
        )
        assertEquals(CommitOutcome.Stale, olderResult)
        assertEquals("NEWER", target.readText())
        assertTrue(!validated && !metadataWritten)
    }

    @Test
    fun pendingConfirmIsStaleWhenNewerFetchStartsWithoutWriting() = runBlocking {
        val target = tmp.newFile("pending.json").apply { writeText("LKG") }
        val profileId = 61002L
        val pending = ProfileConfigCommit.beginUpdate(profileId, target.path)
        ProfileConfigCommit.beginUpdate(profileId, target.path)

        var metadataWritten = false
        val outcome = ProfileConfigCommit.commitIfUnchanged(
            expected = pending,
            content = "PARTIAL",
            validate = {},
            afterFileCommit = { metadataWritten = true },
        )
        assertEquals(CommitOutcome.Stale, outcome)
        assertEquals("LKG", target.readText())
        assertTrue(!metadataWritten)
    }

    @Test
    fun editorWriteDuringFetchIsNotOverwrittenByItsResponse() = runBlocking {
        val target = tmp.newFile("editor.json").apply { writeText("LKG") }
        val profileId = 61003L
        val expected = ProfileConfigCommit.beginUpdate(profileId, target.path)
        assertTrue(ProfileConfigCommit.applyEditorWrite(profileId, target, "EDITED"))

        val outcome = ProfileConfigCommit.commitIfUnchanged(
            expected = expected,
            content = "FETCHED",
            validate = {},
            afterFileCommit = {},
        )
        assertEquals(CommitOutcome.Stale, outcome)
        assertEquals("EDITED", target.readText())
    }

    @Test
    fun unchangedFileWithCurrentFetchAcceptsFullOrConfirmedPartialResult() = runBlocking {
        for ((index, content) in listOf("FULL", "PARTIAL").withIndex()) {
            val target = tmp.newFile("current-$index.json").apply { writeText("LKG") }
            val expected = ProfileConfigCommit.beginUpdate(61004L + index, target.path)
            var metadataWritten = false
            val outcome = ProfileConfigCommit.commitIfUnchanged(
                expected = expected,
                content = content,
                validate = {},
                afterFileCommit = { metadataWritten = true },
            )
            assertTrue(outcome is CommitOutcome.Success)
            assertEquals(content, target.readText())
            assertTrue(metadataWritten)
        }
    }

    @Test
    fun invalidateBlocksFetchResponseEvenWhenBytesDidNotChange() = runBlocking {
        val target = tmp.newFile("invalidated.json").apply { writeText("LKG") }
        val profileId = 61006L
        val expected = ProfileConfigCommit.beginUpdate(profileId, target.path)
        ProfileConfigCommit.invalidate(profileId)
        val outcome = ProfileConfigCommit.commitIfUnchanged(
            expected = expected,
            content = "FETCHED",
            validate = {},
            afterFileCommit = {},
        )
        assertEquals(CommitOutcome.Stale, outcome)
        assertEquals("LKG", target.readText())
    }
    @Test
    fun changedSubscriptionSourceRejectsEvenWhenFileIsUnchanged() = runBlocking {
        val target = tmp.newFile("changed-source.json").apply { writeText("LKG") }
        val expected = ProfileConfigCommit.beginUpdate(62001L, target.path)
        var validated = false
        var metadataWritten = false
        val result = ProfileConfigCommit.commitIfUnchanged(
            expected, "OLD-SOURCE", validate = { validated = true },
            sourceStillMatches = { false }, afterFileCommit = { metadataWritten = true },
        )
        assertEquals(CommitOutcome.Stale, result)
        assertEquals("LKG", target.readText())
        assertTrue(!validated && !metadataWritten)
    }

    @Test
    fun cancelledUpdateCannotReplaceFileAfterValidation() = runBlocking {
        val target = tmp.newFile("cancelled.json").apply { writeText("LKG") }
        val expected = ProfileConfigCommit.beginUpdate(62002L, target.path)
        var metadataWritten = false
        val job = launch {
            ProfileConfigCommit.commitIfUnchanged(
                expected, "CANCELLED", validate = { coroutineContext[kotlinx.coroutines.Job]!!.cancel() },
                afterFileCommit = { metadataWritten = true },
            )
        }
        job.join()
        assertEquals("LKG", target.readText())
        assertTrue(!metadataWritten)
    }

}
