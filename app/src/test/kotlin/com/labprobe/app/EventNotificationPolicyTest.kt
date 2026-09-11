package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventNotificationPolicyTest {
    private fun event(
        id: Int,
        type: String = "device_online",
        title: String = "事件 $id",
        mac: String = "02:00:00:00:00:%02x".format(id),
        time: String = "2026-09-10T10:0$id:00",
    ) = EventItem(
        id = id,
        title = title,
        type = type,
        name = "设备 $id",
        oldValue = "offline",
        newValue = "online",
        time = time,
        mac = mac,
    )

    @Test
    fun firstSuccessfulSnapshotIsAQuietBaselineButStillPersistsKeys() {
        val selection = selectEventNotificationBatch(listOf(event(1), event(2)), emptySet(), silentBaseline = true)

        assertFalse(selection.shouldNotify)
        assertEquals(listOf("id:1", "id:2"), selection.keysToPersist)
    }

    @Test
    fun persistedKeysSuppressOnlyAlreadySeenEventsAfterRestart() {
        val persisted = appendEventNotificationSeen(emptyList(), listOf("id:1"))
        val selection = selectEventNotificationBatch(listOf(event(1), event(2)), persisted.toSet(), silentBaseline = false)

        assertEquals(listOf(2), selection.newEvents.map { it.id })
        assertTrue(selection.shouldNotify)
    }

    @Test
    fun hubScopeDigestSeparatesIdentitiesWithoutEmbeddingRawIdentity() {
        val first = "https://hub.example/a#token-one"
        val second = "https://hub.example/a#token-two"

        assertNotEquals(eventNotificationScopeDigest(first), eventNotificationScopeDigest(second))
        assertFalse(eventNotificationScopeDigest(first).contains("token-one"))
    }

    @Test
    fun seenPersistenceIsDeduplicatedAndBoundedByDroppingOldestOnly() {
        val persisted = appendEventNotificationSeen(
            existing = (1..EVENT_SEEN_MAX_KEYS).map { "id:$it" },
            additions = listOf("id:${EVENT_SEEN_MAX_KEYS}", "id:${EVENT_SEEN_MAX_KEYS + 1}"),
        )

        assertEquals(EVENT_SEEN_MAX_KEYS, persisted.size)
        assertFalse(persisted.contains("id:1"))
        assertTrue(persisted.contains("id:${EVENT_SEEN_MAX_KEYS + 1}"))
        assertEquals(persisted.size, persisted.toSet().size)
    }

    @Test
    fun oneEventRemainsSingleTargetAndMultipleEventsRemainOneBatch() {
        val single = selectEventNotificationBatch(listOf(event(1)), emptySet(), silentBaseline = false)
        val multiple = selectEventNotificationBatch(listOf(event(1), event(2)), emptySet(), silentBaseline = false)

        assertEquals(1, single.newEvents.size)
        assertEquals(2, multiple.newEvents.size)
        assertTrue(single.shouldNotify)
        assertTrue(multiple.shouldNotify)
    }

    @Test
    fun presenceNotificationsKeepOnlyExplicitlyFollowedNormalizedMacs() {
        val followed = event(1, mac = "AA-BB-CC-DD-EE-FF")
        val notFollowed = event(2, "device_offline", mac = "02:00:00:00:00:02")
        val missingMac = event(3, mac = "")
        val network = event(4, "ddns_changed", mac = "")

        val filtered = filterEventNotifications(
            listOf(followed, notFollowed, missingMac, network),
            setOf("aa:bb:cc:dd:ee:ff"),
        )

        assertEquals(listOf(1, 4), filtered.map { it.id })
    }

    @Test
    fun singlePresenceEventRoutesToItsDeviceDetail() {
        assertEquals(
            EventNotificationTarget("device_detail", "aa:bb:cc:dd:ee:ff"),
            eventNotificationTarget(listOf(event(1, mac = "AA-BB-CC-DD-EE-FF"))),
        )
    }

    @Test
    fun multiplePresenceEventsRouteToDevicesWhileMixedAndNonDeviceRouteToEvents() {
        assertEquals("devices", eventNotificationRoute(listOf(event(1, "device_online"), event(2, "device_offline"))))
        assertEquals("events", eventNotificationRoute(listOf(event(3, "ddns_changed"))))
        assertEquals("events", eventNotificationRoute(listOf(event(4, "device_online"), event(5, "stun_changed"))))
    }

    @Test
    fun appOpenReminderSelectsOnlyNewestFollowedPresenceEvent() {
        val olderFollowed = event(20, time = "")
        val newerFollowed = event(21, type = "device_offline", mac = olderFollowed.mac)
        val newestUnfollowed = event(22, mac = "aa:bb:cc:dd:ee:ff")
        val nonDevice = event(23, type = "ddns_changed", mac = "")

        val selection = selectLatestFollowedPresenceReminder(
            events = listOf(olderFollowed, newestUnfollowed, nonDevice, newerFollowed),
            followedDeviceMacs = setOf(olderFollowed.mac),
            previousIdentity = null,
            previousClaimedAtMs = 0L,
            nowMs = 10_000L,
        )

        assertEquals(21, selection.event?.id)
        assertTrue(selection.shouldNotify)
    }

    @Test
    fun sameOpenReminderIsSuppressedWithinHourAndAllowedAtBoundary() {
        val latest = event(30)
        val identity = eventNotificationIdentity(latest)

        val withinHour = selectLatestFollowedPresenceReminder(
            listOf(latest), setOf(latest.mac), identity, 1_000L,
            1_000L + EVENT_OPEN_REMINDER_COOLDOWN_MS - 1L,
        )
        val afterHour = selectLatestFollowedPresenceReminder(
            listOf(latest), setOf(latest.mac), identity, 1_000L,
            1_000L + EVENT_OPEN_REMINDER_COOLDOWN_MS,
        )

        assertFalse(withinHour.shouldNotify)
        assertTrue(afterHour.shouldNotify)
    }

    @Test
    fun newerFollowedEventCanRemindWithinPreviousEventsHour() {
        val previous = event(40)
        val latest = event(41, type = "device_offline", mac = previous.mac)

        val selection = selectLatestFollowedPresenceReminder(
            listOf(previous, latest), setOf(previous.mac), eventNotificationIdentity(previous),
            previousClaimedAtMs = 1_000L, nowMs = 2_000L,
        )

        assertEquals(41, selection.event?.id)
        assertTrue(selection.shouldNotify)
    }
}
