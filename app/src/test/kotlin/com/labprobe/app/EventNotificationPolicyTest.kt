package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventNotificationPolicyTest {
    private fun event(id: Int, type: String = "device_online", title: String = "事件 $id") = EventItem(
        id = id,
        title = title,
        type = type,
        name = "设备 $id",
        oldValue = "offline",
        newValue = "online",
        time = "2026-09-10T10:0$id:00",
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
    fun devicePresenceRoutesToDevicesButOtherOrMixedBatchesRouteToEvents() {
        assertEquals("devices", eventNotificationRoute(listOf(event(1, "device_online"), event(2, "device_offline"))))
        assertEquals("events", eventNotificationRoute(listOf(event(3, "ddns_changed"))))
        assertEquals("events", eventNotificationRoute(listOf(event(4, "device_online"), event(5, "stun_changed"))))
    }
}
