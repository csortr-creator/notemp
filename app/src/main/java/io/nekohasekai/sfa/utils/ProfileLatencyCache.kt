package io.nekohasekai.sfa.utils

import org.json.JSONObject
import java.security.MessageDigest

class ProfileLatencyCache(raw: String = "{}") {
    data class Result(
        val delay: Int,
        val time: Long,
        val status: ProbeStatus = ProbeStatus.UNTESTED,
        /** Session-only; never written to persistent encode. */
        val detail: String = "",
    )

    private val entries = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

    @Synchronized
    fun get(profile: Long, tag: String, fingerprint: String): Result? {
        val entry = entries.optJSONObject(profile.toString())?.optJSONObject(tag) ?: return null
        if (entry.optString("fingerprint") != fingerprint) return null
        val time = entry.optLong("time")
        if (time <= 0) return null
        val status =
            runCatching { ProbeStatus.valueOf(entry.optString("status", "SUCCESS")) }
                .getOrDefault(
                    if (entry.optInt("delay") > 0) ProbeStatus.SUCCESS else ProbeStatus.PROBE_ERROR,
                )
        // Ignore any legacy free-text detail that may contain host/path fragments.
        return Result(
            delay = entry.optInt("delay").coerceAtLeast(0),
            time = time,
            status = status,
            detail = "",
        )
    }

    @Synchronized
    fun put(
        profile: Long,
        tag: String,
        fingerprint: String,
        delay: Int,
        time: Long,
        status: ProbeStatus = if (delay > 0) ProbeStatus.SUCCESS else ProbeStatus.PROBE_ERROR,
        @Suppress("UNUSED_PARAMETER") detail: String = "",
    ) {
        if (time <= 0 || profile < 0) return
        val values = entries.optJSONObject(profile.toString()) ?: JSONObject()
        // Do not persist exception messages or other free-text diagnostics.
        values.put(
            tag,
            JSONObject()
                .put("fingerprint", fingerprint)
                .put("delay", delay.coerceAtLeast(0))
                .put("time", time)
                .put("status", status.name),
        )
        entries.put(profile.toString(), values)
    }

    @Synchronized
    fun retain(profile: Long, fingerprints: Map<String, String>): Boolean {
        val values = entries.optJSONObject(profile.toString()) ?: return false
        var changed = false
        values.keys().asSequence().toList().forEach { tag ->
            if (values.optJSONObject(tag)?.optString("fingerprint") != fingerprints[tag]) {
                values.remove(tag)
                changed = true
            }
        }
        return changed
    }

    /** Serialize without legacy detail keys. */
    @Synchronized
    fun encode(): String {
        stripLegacyDetail()
        return entries.toString()
    }

    private fun stripLegacyDetail() {
        val profileKeys = entries.keys().asSequence().toList()
        for (profileKey in profileKeys) {
            val values = entries.optJSONObject(profileKey) ?: continue
            val tags = values.keys().asSequence().toList()
            for (tag in tags) {
                val entry = values.optJSONObject(tag) ?: continue
                if (entry.has("detail")) {
                    entry.remove("detail")
                }
            }
        }
    }

    companion object {
        fun fingerprints(config: String): Map<String, String> {
            val outbounds = JSONObject(config).optJSONArray("outbounds") ?: return emptyMap()
            return (0 until outbounds.length())
                .mapNotNull { index ->
                    val outbound = outbounds.optJSONObject(index) ?: return@mapNotNull null
                    val tag = outbound.optString("tag")
                    if (tag.isBlank()) return@mapNotNull null
                    val hash =
                        MessageDigest.getInstance("SHA-256")
                            .digest(canonical(outbound).toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                    tag to hash
                }
                .toMap()
        }

        private fun canonical(value: Any?): String = when (value) {
            is JSONObject ->
                value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
                    JSONObject.quote(it) + ":" + canonical(value.get(it))
                }
            is org.json.JSONArray ->
                (0 until value.length()).joinToString(",", "[", "]") {
                    canonical(value.get(it))
                }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }
}
