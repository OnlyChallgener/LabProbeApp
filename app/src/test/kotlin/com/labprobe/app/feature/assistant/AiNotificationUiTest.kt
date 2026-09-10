package com.labprobe.app.feature.assistant

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AiNotificationUiTest {
    @Test
    fun hubKeyIsStableDigestAndNeverContainsRawIdentity() {
        val identity = "https://hub.example|secret-token"

        val key = aiNotificationHubKey(identity)

        assertEquals(key, aiNotificationHubKey(identity))
        assertEquals(64, key.length)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertFalse(key.contains("secret-token"))
        assertEquals("", aiNotificationHubKey(""))
    }

    @Test
    fun perNoticeIntentIdentityAndPayloadRemainDistinct() {
        val context: Application = RuntimeEnvironment.getApplication()
        val hubIdentity = "https://hub.example|secret-token"
        val first = aiNotificationDeliveryIdentity(41, "第一条", "完整正文 A", hubIdentity)
        val second = aiNotificationDeliveryIdentity(42, "第二条", "完整正文 B", hubIdentity)
        val firstIntent = aiNotificationIntent(context, "ai_chat", "第一条", "完整正文 A", first)
        val secondIntent = aiNotificationIntent(context, "ai_chat", "第二条", "完整正文 B", second)

        assertNotEquals(first.intentData, second.intentData)
        assertNotEquals(first.notificationTag, second.notificationTag)
        assertFalse(firstIntent.filterEquals(secondIntent))
        assertEquals("41", firstIntent.getStringExtra("ai_notice_id"))
        assertEquals("第一条", firstIntent.getStringExtra("ai_notice_title"))
        assertEquals("完整正文 A", firstIntent.getStringExtra("ai_notice_content"))
        assertEquals(aiNotificationHubKey(hubIdentity), firstIntent.getStringExtra("ai_notice_hub_key"))
        assertFalse(firstIntent.data.toString().contains("secret-token"))
    }

    @Test
    fun missingRowIdUsesDeterministicContentDigest() {
        val first = aiNotificationDeliveryIdentity(0, "通知", "相同正文", "hub-a")
        val same = aiNotificationDeliveryIdentity(0, "通知", "相同正文", "hub-a")
        val changed = aiNotificationDeliveryIdentity(0, "通知", "不同正文", "hub-a")

        assertEquals(first, same)
        assertNotEquals(first.noticeId, changed.noticeId)
        assertNotEquals(first.intentData, changed.intentData)
    }

    @Test
    fun largeBodyUsesSmallIntentReferenceAndResolvesExactly() {
        val context: Application = RuntimeEnvironment.getApplication()
        val identity = aiNotificationDeliveryIdentity(99, "长通知", "unused", "hub-large")
        val fullContent = "完整通知正文。".repeat(6_000)

        val payload = aiNotificationIntentContent(context, identity, fullContent)
        val intent = aiNotificationIntent(context, "ai_chat", "长通知", payload, identity)

        assertTrue(aiNotificationContentIsStored(payload))
        assertTrue(payload.toByteArray().size < 512)
        assertEquals(payload, intent.getStringExtra("ai_notice_content"))
        assertEquals(fullContent, aiNotificationResolveContent(context, payload))
    }

    @Test
    fun literalReferencePrefixAndLargeTitleRoundTripExactly() {
        val context: Application = RuntimeEnvironment.getApplication()
        val identity = aiNotificationDeliveryIdentity(100, "title", "content", "hub-prefix")
        val literalContent = "labprobe-ai-notice-ref:这是正文，不是内部引用"
        val largeTitle = "标题".repeat(3_000)

        val contentPayload = aiNotificationIntentContent(context, identity, literalContent)
        val titlePayload = aiNotificationIntentTitle(context, identity, largeTitle)

        assertTrue(aiNotificationContentIsStored(contentPayload))
        assertTrue(aiNotificationContentIsStored(titlePayload))
        assertEquals(literalContent, aiNotificationResolveContent(context, contentPayload))
        assertEquals(largeTitle, aiNotificationResolveContent(context, titlePayload))
    }

    @Test
    fun referencedPayloadStoreRetainsAtMostNewestHundredFields() {
        val context: Application = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ai_notification_payloads_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var latestPayload = ""
        var latestContent = ""

        repeat(105) { index ->
            val identity = aiNotificationDeliveryIdentity(index + 1, "通知 $index", "unused", "hub-bounded")
            latestContent = "labprobe-ai-notice-ref:正文-$index"
            latestPayload = aiNotificationIntentContent(context, identity, latestContent)
        }

        assertTrue(prefs.all.keys.count { it.startsWith("payload:") } <= 100)
        assertTrue(prefs.all.keys.count { it.startsWith("time:") } <= 100)
        assertEquals(latestContent, aiNotificationResolveContent(context, latestPayload))
    }
}
