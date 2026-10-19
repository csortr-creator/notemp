package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class RuleSetFileCommitTest {
    @get:Rule val dir = TemporaryFolder()
    @Test fun invalidDownloadKeepsPreviousCacheAndCleansTemporaryFile() {
        val target = dir.newFile("cache.srs").apply { writeText("good") }
        try {
            RuleSetFileCommit.replace(target, { it.writeText("invalid") }, { error("invalid SRS") })
            fail("Expected validation failure")
        } catch (_: IllegalStateException) {}
        assertEquals("good", target.readText())
        assertEquals(listOf("cache.srs"), dir.root.list()!!.toList())
    }
    @Test fun cancelledDownloadCannotPublishPartialCache() {
        val target = dir.newFile("cache.srs").apply { writeText("good") }
        try {
            RuleSetFileCommit.replace(target, {
                it.writeText("partial"); throw java.util.concurrent.CancellationException()
            }, { fail("Should not validate") })
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) {}
        assertEquals("good", target.readText())
        assertEquals(1, dir.root.list()!!.size)
    }
    @Test fun validatedDownloadAtomicallyReplacesPreviousFile() {
        val target = dir.newFile("cache.srs").apply { writeText("old") }
        RuleSetFileCommit.replace(target, { it.writeText("new") }, {
            assertEquals("old", target.readText()); assertEquals("new", it.readText())
        })
        assertEquals("new", target.readText())
        assertEquals(1, dir.root.list()!!.size)
    }
    @Test fun concurrentDownloadsHaveSeparateTemporaryFiles() {
        val target = dir.newFile("cache.srs").apply { writeText("old") }
        val entered = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        val paths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        try {
            val futures = listOf("A", "B").map { value -> pool.submit {
                RuleSetFileCommit.replace(target, {
                    paths.add(it.path); it.writeText(value); entered.countDown()
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                }, { assertEquals(value, it.readText()) })
            } }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, paths.size)
            assertTrue(target.readText() in listOf("A", "B"))
            assertEquals(1, dir.root.list()!!.size)
        } finally { pool.shutdownNow() }
    }
}
