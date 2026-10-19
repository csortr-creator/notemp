package io.nekohasekai.sfa.utils

import android.content.Context
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.ktx.unwrap
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val hwidMemoryCache = ConcurrentHashMap<String, String>()

class HTTPClient : Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var nativeClient: io.nekohasekai.libbox.HTTPClient? = null

    private val clientDelegate = lazy {
        Libbox.newHTTPClient().apply {
            modernTLS()
            setTimeout(30)
        }.also { created ->
            nativeClient = created
            if (closed.get()) created.close()
        }
    }
    private val client by clientDelegate

    fun getString(url: String): String {
        val result = getSubscription(url)
        require(result.report.issues.isEmpty()) { result.report.displayText() }
        return result.config
    }

    internal suspend fun fetchSubscription(url: String): SubscriptionImportResult =
        withCancellationClose(::close) {
            val operation = currentCoroutineContext()
            getSubscription(url) { operation.ensureActive() }
        }

    internal fun getSubscription(url: String, checkActive: () -> Unit = {}): SubscriptionImportResult {
        val response = SubscriptionDiagnostics.at(SubscriptionStage.FETCH) { executeFetch(url, checkActive) }
        val parsed = SubscriptionDiagnostics.at(SubscriptionStage.PARSE) {
            parseSubscription(response.content, checkActive)
        }
        return SubscriptionDiagnostics.at(SubscriptionStage.METADATA) {
            parsed.copy(
                profileName = SubscriptionMetadata.title(response.title, response.content),
                updateIntervalMinutes = SubscriptionMetadata.intervalMinutes(response.interval),
            )
        }
    }

    private data class FetchedSubscription(val content: String, val title: String, val interval: String)

    /**
     * Builds a request (HWID + headers unchanged) and executes with retry on
     * transient TLS/timeout/connection failures. Does not log the URL.
     *
     * The whole HTTP exchange is limited to 30 seconds per attempt, including body reads.
     * Native response reads are bounded before allocation; transient failures use [SubscriptionFetchRetry].
     */
    private fun executeFetch(url: String, checkActive: () -> Unit) = SubscriptionFetchRetry.run(
        checkActive = checkActive,
        sleep = { duration ->
            var remaining = duration
            while (remaining > 0L) {
                checkActive()
                val slice = minOf(remaining, 50L)
                Thread.sleep(slice)
                remaining -= slice
            }
        },
        onAttempt = { attempt, code, willRetry ->
            Log.i(
                FETCH_TAG,
                "event=subscription_fetch_attempt attempt=$attempt code=$code willRetry=$willRetry",
            )
        },
    ) {
        val request = client.newRequest()
        request.setResponseLimit(8L * 1024 * 1024)
        request.setURL(url)

        val hwid = getOrCreateHwid(url)

        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val rawModel = Build.MODEL
        val model =
            if (rawModel.startsWith(manufacturer, ignoreCase = true)) {
                rawModel
            } else {
                "$manufacturer $rawModel"
            }
        val androidVer = Build.VERSION.RELEASE
        val buildId = Build.ID.ifEmpty { "UKQ1.231003.002" }

        val userAgentStr =
            "sing-box/${Libbox.version().removePrefix("v")} notemp/${io.nekohasekai.sfa.BuildConfig.VERSION_NAME} (Linux; Android $androidVer; $model Build/$buildId) HWID/$hwid"

        request.setUserAgent(userAgentStr)
        request.setHeader("HWID", hwid)
        request.setHeader("hwid", hwid)
        request.setHeader("X-HWID", hwid)
        request.setHeader("Device-ID", hwid)
        request.setHeader("Happ-HWID", hwid)

        val fullDeviceTitle = "$model (Android $androidVer)"
        request.setHeader("Device-Name", fullDeviceTitle)
        request.setHeader("X-Device-Name", fullDeviceTitle)
        request.setHeader("Happ-Device-Name", fullDeviceTitle)
        request.setHeader("Device-Model", model)
        request.setHeader("X-Device-Model", model)
        request.setHeader("Device-OS", "Android $androidVer")
        request.setHeader("X-Device-OS", "Android $androidVer")

        request.setHeader("App-Name", "notemp")
        request.setHeader("Platform", "Android")
        request.setHeader("Accept", "*/*")

        val response = request.execute()
        val content = try {
            response.content.unwrap
        } catch (e: Exception) {
            if (e.message.orEmpty().contains("HTTP_RESPONSE_TOO_LARGE")) {
                throw SubscriptionResponseFailure(SubscriptionResponseProblem.TOO_LARGE)
            }
            throw e
        }
        FetchedSubscription(content, response.getHeader("profile-title"), response.getHeader("profile-update-interval"))
    }

    private fun getOrCreateHwid(url: String): String {
        synchronized(hwidMemoryCache) {
            val normalizedUrl = url.trim()
            val urlKey =
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val hash = digest.digest(normalizedUrl.toByteArray(StandardCharsets.UTF_8))
                    hash.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    normalizedUrl.hashCode().toString()
                }

            val memoryHwid = hwidMemoryCache[urlKey]
            if (!memoryHwid.isNullOrBlank()) {
                return memoryHwid
            }

            val context = getApplicationContext()
            if (context != null) {
                val prefs =
                    context.getSharedPreferences("subscription_hwid_store", Context.MODE_PRIVATE)
                val savedHwid = prefs.getString(urlKey, null)
                if (!savedHwid.isNullOrBlank()) {
                    hwidMemoryCache[urlKey] = savedHwid
                    return savedHwid
                }

                val newHwid = UUID.randomUUID().toString().replace("-", "").take(16)
                prefs.edit().putString(urlKey, newHwid).apply()
                hwidMemoryCache[urlKey] = newHwid
                return newHwid
            }

            val fallbackHwid = urlKey.take(16)
            hwidMemoryCache[urlKey] = fallbackHwid
            return fallbackHwid
        }
    }

    private fun getApplicationContext(): Context? = try {
        val appClass = Class.forName("io.nekohasekai.sfa.Application")
        val field = appClass.getDeclaredField("application")
        field.isAccessible = true
        field.get(null) as? Context
    } catch (e: Exception) {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            currentAppMethod.invoke(null) as? Context
        } catch (e2: Exception) {
            null
        }
    }

    internal fun processSubscriptionContent(raw: String): String {
        val result = parseSubscription(raw)
        require(result.report.issues.isEmpty()) { result.report.displayText() }
        return result.config
    }

    /**
     * B2: structural parse via [SubscriptionContentParser], per-node core via [NodeCoreValidator]
     * (NodeCoreHarness + family control). Old minimal dns-direct-only scaffold removed.
     * Final [Libbox.checkConfig] remains at commit time ([ProfileConfigCommit]).
     */
    internal fun parseSubscription(raw: String, checkActive: () -> Unit = {}): SubscriptionImportResult {
        NodeCoreValidator.beginBatch()
        try {
            return SubscriptionContentParser(
                io.nekohasekai.sfa.database.Settings.tunStack,
                io.nekohasekai.sfa.database.Settings.routingBlockIpv6,
                checkActive = checkActive,
                validateNode = { node -> NodeCoreValidator.validateOrThrow(node) },
            )
                .let { parser -> SubscriptionDiagnostics.at(SubscriptionStage.PARSE) { parser.parse(raw) } }
        } finally {
            NodeCoreValidator.endBatch()
        }
    }

    override fun close() {
        closed.set(true)
        nativeClient?.close()
    }

    companion object {
        const val userAgent = "notemp"
        private const val FETCH_TAG = "SFA.Fetch"
    }
}
