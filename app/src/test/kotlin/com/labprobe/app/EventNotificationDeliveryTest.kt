package com.labprobe.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventNotificationDeliveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun event(id: Int) = EventItem(id, "地址变化", "network", "测试设备", "old", "new", "2026-09-10")

    @Before fun resetStore() {
        context.getSharedPreferences("labprobe_event_notifications_v1", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun permissionDenialStillClaimsEventsWithoutReplayingOnGrant() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        EventNotificationCenter.notifyNewEvents(context, listOf(event(1)), "hub-a")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        EventNotificationCenter.notifyNewEvents(context, listOf(event(1)), "hub-a")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        EventNotificationCenter.notifyNewEvents(context, listOf(event(2)), "hub-a")
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun quietBaselineAndOneNotificationPerLiveBatch() {
        EventNotificationCenter.notifyNewEvents(context, listOf(event(1), event(2)), "hub-a", silentBaseline = true)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        EventNotificationCenter.notifyNewEvents(context, listOf(event(2), event(3), event(4)), "hub-a")
        assertEquals(1, shadowOf(manager).allNotifications.size)
        manager.cancelAll()
        EventNotificationCenter.notifyNewEvents(context, listOf(event(3), event(4)), "hub-a")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
