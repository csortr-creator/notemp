package io.nekohasekai.sfa.utils

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object RuleSetUpdater {
    private const val TAG = "RuleSetUpdater"
    private const val RULE_SET_DIR = "rule_sets"

    suspend fun ensureRuleSets(
        context: Context,
        configJson: String,
        updateIntervalMs: Long,
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val root = JSONObject(configJson)
                val route = root.optJSONObject("route") ?: return@withContext configJson
                val ruleSets = route.optJSONArray("rule_set") ?: return@withContext configJson

                val dir = File(context.filesDir, RULE_SET_DIR)
                if (!dir.exists()) {
                    dir.mkdirs()
                }

                for (i in 0 until ruleSets.length()) {
                    val ruleSet = ruleSets.optJSONObject(i) ?: continue
                    if (ruleSet.optString("type") == "remote") {
                        val urlString = ruleSet.optString("url")
                        if (urlString.isNotEmpty()) {
                            val tag = ruleSet.optString("tag")
                            val format = ruleSet.optString("format", "binary")
                            val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "rule-set" }
                            val hash =
                                java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(urlString.toByteArray())
                                    .take(8)
                                    .joinToString("") { "%02x".format(it) }
                            val fileName =
                                "$safeTag-$hash.${if (format == "source") "json" else "srs"}"
                            val file = File(dir, fileName)
                            // Whitelist must be usable on a fresh install even when GitHub
                            // is unreachable on the current restricted mobile network.
                            if (!file.exists() && format == "binary" && RoutingPresets.remoteRuleSets[tag] == urlString) {
                                val operation = currentCoroutineContext()
                                RuleSetFileCommit.replace(file, write = { temporary ->
                                    context.assets.open("rule_sets/$tag.srs").use { input ->
                                        FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
                                    }
                                }, validate = { temporary ->
                                    operation.ensureActive()
                                    validateRuleSet(temporary, format)
                                })
                            }

                            var shouldDownload = true
                            if (file.exists()) {
                                if (updateIntervalMs <= 0) { // -1 means never update if exists
                                    shouldDownload = false
                                } else if (updateIntervalMs > 0) {
                                    val lastModified = file.lastModified()
                                    val now = System.currentTimeMillis()
                                    if (now - lastModified < updateIntervalMs) {
                                        shouldDownload = false
                                    }
                                }
                            }

                            if (shouldDownload && updateIntervalMs <= 0) {
                                downloadFile(urlString, file, format)
                            }

                            RuleSetPolicy.configure(
                                ruleSet,
                                file.takeIf { it.exists() }?.absolutePath,
                                updateIntervalMs,
                            )
                        }
                    }
                }
                root.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process rule sets")
                throw IllegalArgumentException(
                    "Не удалось подготовить наборы правил",
                    e,
                )
            }
        }
    }

    private fun validateRuleSet(file: File, format: String) {
        Libbox.checkConfig(
            JSONObject().put("route", JSONObject().put("rule_set", JSONArray().put(
                JSONObject().put("type", "local").put("tag", "validate-update")
                    .put("format", format).put("path", file.absolutePath),
            ))).toString(),
        )
    }

    private suspend fun downloadFile(urlString: String, destFile: File, format: String): Unit {
        val operation = currentCoroutineContext()
        val client = Libbox.newHTTPClient().apply { modernTLS(); setTimeout(30) }
        try {
            withCancellationClose(client::close) {
                val request = client.newRequest().apply {
                    setURL(urlString)
                    setUserAgent("notemp")
                    setResponseLimit(16L * 1024 * 1024)
                }
                val response = request.execute()
                operation.ensureActive()
                RuleSetFileCommit.replace(
                    destFile,
                    write = { temporary ->
                        response.writeTo(temporary.absolutePath)
                        operation.ensureActive()
                        FileOutputStream(temporary, true).use { it.fd.sync() }
                    },
                    validate = { temporary ->
                        operation.ensureActive()
                        validateRuleSet(temporary, format)
                        operation.ensureActive()
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            operation.ensureActive()
            Log.w(TAG, "Rule-set download failed; previous cache retained")
        } finally {
            client.close()
        }
    }
}
