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
    private fun presenceEvent(id: Int, mac: String) = EventItem(
        id = id,
        title = "设备上线",
        type = "device_online",
        name = "关注设备",
        oldValue = "offline",
        newValue = "online",
        time = "2026-09-10",
        mac = mac,
    )

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

    @Test fun followedPresenceNotificationCarriesNormalizedDeviceDetailTarget() {
        val event = presenceEvent(10, "AA-BB-CC-DD-EE-FF")

        EventNotificationCenter.notifyNewEvents(
            context = context,
            events = listOf(event),
            hubIdentity = "hub-a",
            followedDeviceMacs = setOf("aa:bb:cc:dd:ee:ff"),
        )

        val notification = shadowOf(manager).allNotifications.single()
        val intent = shadowOf(requireNotNull(notification.contentIntent)).savedIntent
        assertEquals("device_detail", intent.getStringExtra("navigate_route"))
        assertEquals("aa:bb:cc:dd:ee:ff", intent.getStringExtra("device_mac"))
    }

    @Test fun unfollowedPresenceNotificationIsNotDelivered() {
        EventNotificationCenter.notifyNewEvents(
            context = context,
            events = listOf(presenceEvent(11, "02:00:00:00:00:11")),
            hubIdentity = "hub-a",
            followedDeviceMacs = emptySet(),
        )

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun appOpenDeliversOnlyLatestFollowedPresenceAndRateLimitsItForOneHour() {
        val followedMac = "02:00:00:00:00:21"
        val older = presenceEvent(20, followedMac).copy(time = "2026-09-10T10:00:00")
        val latest = presenceEvent(21, followedMac).copy(time = "2026-09-10T11:00:00")
        val unfollowed = presenceEvent(22, "02:00:00:00:00:22").copy(time = "2026-09-10T12:00:00")
        val firstOpenAt = 10_000L

        EventNotificationCenter.notifyLatestFollowedPresenceOnOpen(
            context, listOf(older, unfollowed, latest), "hub-a", setOf(followedMac), firstOpenAt,
        )

        val first = shadowOf(manager).allNotifications.single()
        val firstIntent = shadowOf(requireNotNull(first.contentIntent)).savedIntent
        assertEquals("id:21", firstIntent.getStringExtra("event_identity"))
        assertEquals("device_detail", firstIntent.getStringExtra("navigate_route"))
        assertEquals(followedMac, firstIntent.getStringExtra("device_mac"))

        manager.cancelAll()
        EventNotificationCenter.notifyLatestFollowedPresenceOnOpen(
            context, listOf(older, latest), "hub-a", setOf(followedMac),
            firstOpenAt + EVENT_OPEN_REMINDER_COOLDOWN_MS - 1L,
        )
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        EventNotificationCenter.notifyLatestFollowedPresenceOnOpen(
            context, listOf(older, latest), "hub-a", setOf(followedMac),
            firstOpenAt + EVENT_OPEN_REMINDER_COOLDOWN_MS,
        )
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun realtimePresenceClaimSuppressesImmediateAppOpenReminder() {
        val followedMac = "02:00:00:00:00:31"
        val event = presenceEvent(31, followedMac)

        EventNotificationCenter.notifyNewEvents(
            context, listOf(event), "hub-a", followedDeviceMacs = setOf(followedMac),
        )
        manager.cancelAll()
        EventNotificationCenter.notifyLatestFollowedPresenceOnOpen(
            context, listOf(event), "hub-a", setOf(followedMac), System.currentTimeMillis(),
        )

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
