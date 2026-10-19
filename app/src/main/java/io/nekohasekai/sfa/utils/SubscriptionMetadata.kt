package io.nekohasekai.sfa.utils

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

internal object SubscriptionMetadata {
    fun title(header: String? = null, content: String = ""): String? {
        val fromHeader = header?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            if (value.startsWith("base64:", true)) {
                runCatching {
                    val payload = value.substringAfter(':').filterNot(Char::isWhitespace)
                    val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrElse {
                        Base64.getUrlDecoder().decode(payload)
                    }
                    bytes.toString(Charsets.UTF_8)
                }.getOrNull()
            } else {
                value
            }
        }
        val embedded = runCatching {
            val json = JSONObject(content)
            sequenceOf("remarks", "name", "title").map { json.optString(it) }.firstOrNull { it.isNotBlank() }
        }.getOrNull()
        return clean(fromHeader) ?: clean(embedded)
    }

    fun intervalMinutes(header: String?): Int? = header?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let {
        (it * 60).toInt().coerceIn(15, 10080)
    }

    fun nameFromUrl(url: String): String = runCatching {
        val uri = URI(url)
        clean(uri.rawFragment?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") })
            ?: clean(uri.path?.substringAfterLast('/')?.substringBeforeLast('.'))
            ?: uri.host ?: "Подписка"
    }.getOrDefault("Подписка")

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }?.take(160)
}
