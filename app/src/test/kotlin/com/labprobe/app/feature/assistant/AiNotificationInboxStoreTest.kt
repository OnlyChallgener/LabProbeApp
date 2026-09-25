package com.labprobe.app.feature.assistant

import android.Manifest
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiNotificationInboxStoreTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun storesEveryRowInNewestFirstOrderAndSeparatesHubs() {
        context.deleteDatabase("labprobe_ai_notifications.db")
        val store = AiNotificationInboxStore(context)
        val rows = listOf(
            AiNotification(9, "alert", "第 9 条", "内容 9"),
            AiNotification(10, "alert", "第 10 条", "内容 10"),
        )
        assertEquals(2, store.save("hub-a#token", rows).size)
        assertEquals(listOf("10", "9"), store.list("hub-a#token").map { it.id })
        assertEquals("内容 9", store.find("hub-a#token", "9")?.content)
        assertTrue(store.list("hub-b#token").isEmpty())
        assertTrue(store.save("hub-a#token", rows).isEmpty())
        assertEquals(2, store.save("hub-b#token", rows).size)
        store.close()
    }

    @Test
    fun deletionErasesContentAndPreventsReplayForSingleAndMultipleRows() {
        context.deleteDatabase("labprobe_ai_notifications.db")
        val store = AiNotificationInboxStore(context)
        val identity = "hub-delete#token"
        val rows = (1..3).map { AiNotification(it, "alert", "标题 $it", "正文 $it") }
        store.save(identity, rows)
        assertEquals(1, store.delete(identity, setOf("2")))
        assertEquals(listOf("3", "1"), store.list(identity).map { it.id })
        assertNull(store.find(identity, "2"))
        assertTrue(store.save(identity, rows).isEmpty())
        assertEquals(2, store.delete(identity, setOf("1", "3")))
        assertTrue(store.list(identity).isEmpty())
        store.close()
    }

    @Test
    fun deniedSystemPermissionStillLeavesNoticeInLocalInbox() {
        context.deleteDatabase("labprobe_ai_notifications.db")
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val identity = "hub-no-permission#token"
        val delivered = AiNotifier.notifyAssistantMessage(
            context, "预警", "完整内容", notificationId = 801, hubIdentity = identity,
        )
        assertFalse(delivered)
        val store = AiNotificationInboxStore(context)
        assertEquals("完整内容", store.find(identity, "801")?.content)
        store.close()
    }
}
