package io.nekohasekai.sfa.utils

import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealityHybridOptionTest {
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 42 })
    private val uri = "vless://$uuid@example.org:443?security=reality&pbk=$key&sid=abcd&fp=firefox"

    @Test fun panelHybridFlagSurvivesUriBase64AndClashWithoutUnknownParameterWarning() {
        for ((text, expected) in mapOf("true" to true, "1" to true, "false" to false, "0" to false)) {
            val link = "$uri&support-x25519mlkem768=$text#hybrid"
            val clash = """{"proxies":[{"name":"hybrid","type":"vless","server":"example.org","port":443,"uuid":"$uuid","tls":true,"client-fingerprint":"firefox","reality-opts":{"public-key":"$key","short-id":"abcd","support-x25519mlkem768":$expected}}]}"""
            val yaml = """
                proxies:
                  - name: hybrid
                    type: vless
                    server: example.org
                    port: 443
                    uuid: $uuid
                    tls: true
                    client-fingerprint: firefox
                    reality-opts: {public-key: $key, short-id: abcd, support-x25519mlkem768: $expected}
            """.trimIndent()
            for ((format, source) in listOf("uri" to link, "base64" to Base64.getEncoder().encodeToString(link.toByteArray()), "clash" to clash, "yaml" to yaml)) {
                val result = SubscriptionContentParser().parse(source)
                assertEquals(1, result.report.imported)
                assertTrue(result.report.issues.isEmpty())
                assertFalse(result.report.warnings.any { it.contains("неизвестные параметры") })
                val list = JSONObject(result.config).getJSONArray("outbounds")
                val tls = (0 until list.length()).map { list.getJSONObject(it) }
                    .single { it.has("server") }.getJSONObject("tls")
                assertEquals(expected, tls.getJSONObject("reality").getBoolean("support_x25519mlkem768"))
                assertEquals(key, tls.getJSONObject("reality").getString("public_key"))
                assertEquals("abcd", tls.getJSONObject("reality").getString("short_id"))
                assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
                assertFalse(tls.optBoolean("insecure"))
                File("build/native-configs").mkdirs()
                File("build/native-configs/reality-hybrid-$format-$text.json").writeText(result.config)
            }
        }
    }

    @Test fun malformedHybridFlagOrFlagWithoutRealityCannotSilentlyChangeTls() {
        for (link in listOf("$uri&support-x25519mlkem768=maybe", "$uri&support-x25519mlkem768=", "vless://$uuid@example.org:443?security=tls&support-x25519mlkem768=true")) {
            assertTrue(runCatching { SubscriptionContentParser().parse(link) }.isFailure)
        }
    }
}
