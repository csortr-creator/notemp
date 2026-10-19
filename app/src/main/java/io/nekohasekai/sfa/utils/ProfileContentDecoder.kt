package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.ProfileContent
import org.json.JSONObject

internal data class DecodedProfileContent(
    val content: ProfileContent,
    val report: SubscriptionImportReport?,
)

object ProfileContentDecoder {
    fun decode(data: ByteArray): ProfileContent = decodeWithReport(data).content

    internal fun decodeWithReport(data: ByteArray): DecodedProfileContent =
        SubscriptionDiagnostics.at(SubscriptionStage.PARSE) { decodeBounded(data) }

    private fun decodeBounded(data: ByteArray): DecodedProfileContent {
        ImportInput.checkSize(data.size)
        val text = data.toString(Charsets.UTF_8).trim()
        if (!SubscriptionContentParser.supports(text)) {
            return DecodedProfileContent(Libbox.decodeProfileContent(data), null)
        }
        val result = SubscriptionDiagnostics.at(SubscriptionStage.PARSE) { HTTPClient().use { it.parseSubscription(text) } }
        try { Libbox.checkConfig(result.config) } catch (e: Exception) {
            throw ImportValidationFailure(e)
        }
        val name =
            JSONObject(result.config).getJSONArray("outbounds").let { outbounds ->
                (0 until outbounds.length())
                    .asSequence()
                    .map { outbounds.getJSONObject(it) }
                    .firstOrNull { it.has("server") }
                    ?.optString("tag") ?: "Imported servers"
            }
        val content =
            ProfileContent().apply {
                this.name = SubscriptionMetadata.title(content = text) ?: name
                type = Libbox.ProfileTypeLocal
                this.config = result.config
            }
        return DecodedProfileContent(content, result.report)
    }
}
