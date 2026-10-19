package io.nekohasekai.sfa.utils

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SubscriptionMetadataTest {
    @Test fun readsUtf8TitleAndUsesConfigFallback() {
        val encoded = Base64.getEncoder().encodeToString("Моя подписка".toByteArray())
        assertEquals("Моя подписка", SubscriptionMetadata.title("base64:$encoded"))
        assertEquals("Server", SubscriptionMetadata.title("base64:!invalid", """{"remarks":"Server"}"""))
        assertNull(SubscriptionMetadata.title("bad\nname"))
        assertEquals("My profile", SubscriptionMetadata.title(content = """{"name":"My profile"}"""))
    }
    @Test fun readsHoursAndDoesNotExposeUrlCredentialsInName() {
        assertEquals(120, SubscriptionMetadata.intervalMinutes("2"))
        assertEquals(15, SubscriptionMetadata.intervalMinutes("0.1"))
        assertNull(SubscriptionMetadata.intervalMinutes("NaN"))
        assertNull(SubscriptionMetadata.intervalMinutes("-1"))
        assertEquals("Моя подписка", SubscriptionMetadata.nameFromUrl("https://example.org/sub?token=secret#%D0%9C%D0%BE%D1%8F%20%D0%BF%D0%BE%D0%B4%D0%BF%D0%B8%D1%81%D0%BA%D0%B0"))
        assertEquals("sub", SubscriptionMetadata.nameFromUrl("https://example.org/sub?token=secret"))
    }
    @Test fun acceptsUrlSafeBase64TitleWithoutPadding() {
        val title = "Подписка 😃"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(title.toByteArray())
        assertEquals(title, SubscriptionMetadata.title("BASE64: $encoded "))
    }

}
