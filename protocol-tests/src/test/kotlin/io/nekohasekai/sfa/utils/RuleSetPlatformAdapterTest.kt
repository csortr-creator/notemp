package io.nekohasekai.sfa.utils

import android.content.Context
import com.sun.net.httpserver.HttpServer
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuleSetPlatformAdapterTest {
    @get:Rule val dir = TemporaryFolder()
    private fun config(url: String) = JSONObject().put("route", JSONObject().put("rule_set", JSONArray().put(
        JSONObject().put("type", "remote").put("tag", "fixture").put("format", "source").put("url", url),
    ))).toString()
    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rules", handler)
            executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            start()
        }
    private fun address(server: HttpServer) = "http://127.0.0.1:${server.address.port}/rules?token=synthetic"

    @Test fun actualUpdaterValidatesAndPublishesDownloadedFile() = runBlocking {
        val payload = "{\"version\":3,\"rules\":[]}"
        val server = server { exchange ->
            val bytes = payload.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        var validated = false
        Libbox.check = { content ->
            val path = JSONObject(content).getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).getString("path")
            assertEquals(payload, File(path).readText()); validated = true
        }
        try {
            val result = RuleSetUpdater.ensureRuleSets(Context(dir.root), config(address(server)), -1)
            val rule = JSONObject(result).getJSONObject("route").getJSONArray("rule_set").getJSONObject(0)
            assertTrue(validated)
            assertEquals("local", rule.getString("type"))
            assertEquals(payload, File(rule.getString("path")).readText())
            assertFalse(File(dir.root, "rule_sets").list()!!.any { it.endsWith(".tmp") })
        } finally { server.stop(0); Libbox.check = {} }
    }

    @Test fun nativeValidationFailureCannotBecomeInitialCache() = runBlocking {
        val server = server { exchange ->
            val bytes = "invalid".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        Libbox.check = { error("synthetic native validation failure") }
        try {
            try { RuleSetUpdater.ensureRuleSets(Context(dir.root), config(address(server)), -1); fail("Expected missing initial cache") }
            catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains("token")) }
            assertTrue(File(dir.root, "rule_sets").list()!!.isEmpty())
        } finally { server.stop(0); Libbox.check = {} }
    }

    @Test fun oversizedResponseIsRejectedWithoutCreatingInitialCache() = runBlocking {
        val server = server { exchange -> exchange.sendResponseHeaders(200, 17L * 1024 * 1024); exchange.close() }
        Libbox.check = { fail("Oversized response must not reach validation") }
        try {
            try { RuleSetUpdater.ensureRuleSets(Context(dir.root), config(address(server)), -1); fail("Expected size failure") }
            catch (_: IllegalArgumentException) { }
            assertTrue(File(dir.root, "rule_sets").list()!!.isEmpty())
        } finally { server.stop(0); Libbox.check = {} }
    }

    @Test fun cancelledBodyReadDoesNotWaitForReadTimeoutOrPublishPartialFile() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = server { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.write(byteArrayOf(1)); exchange.responseBody.flush(); entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        val job = async(Dispatchers.IO) { RuleSetUpdater.ensureRuleSets(Context(dir.root), config(address(server)), -1) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(File(dir.root, "rule_sets").list()!!.isEmpty())
        } finally { release.countDown(); job.cancelAndJoin(); server.stop(0); Libbox.check = {} }
    }
    @Test fun aPresetNameWithCustomUrlDoesNotLoadBundledRulesInstead() = runBlocking {
        val server = server { exchange ->
            val bytes = "custom".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val root = JSONObject(config(address(server)))
            root.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0)
                .put("tag", RoutingPresets.remoteRuleSets.keys.first()).put("format", "binary")
            val result = RuleSetUpdater.ensureRuleSets(Context(dir.root), root.toString(), -1)
            val path = JSONObject(result).getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).getString("path")
            assertEquals("custom", File(path).readText())
        } finally { server.stop(0); Libbox.check = {} }
    }

    @Test fun longRuleSetNamesProduceBoundedCacheFileNames() = runBlocking {
        val server = server { exchange ->
            val bytes = "rules".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val root = JSONObject(config(address(server)))
            root.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).put("tag", "x".repeat(400))
            val result = RuleSetUpdater.ensureRuleSets(Context(dir.root), root.toString(), -1)
            val path = JSONObject(result).getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).getString("path")
            assertTrue(File(path).name.length < 120)
            assertEquals("rules", File(path).readText())
        } finally { server.stop(0); Libbox.check = {} }
    }

}
