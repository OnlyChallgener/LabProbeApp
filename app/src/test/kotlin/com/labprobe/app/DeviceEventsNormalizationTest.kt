package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceEventsNormalizationTest {
    private fun event(id: Int, type: String, time: String) = EventItem(
        id = id,
        title = "device event",
        type = type,
        name = "test-device",
        oldValue = "",
        newValue = "",
        time = time,
        mac = "00:11:22:33:44:55"
    )

    @Test
    fun normalizedSnapshotCanBeReusedForOfflineReconciliation() {
        val raw = listOf(
            event(1, "device_online", "2026-08-11 09:00:00"),
            event(2, "device_online", "2026-08-11 09:01:00"),
            event(3, "device_offline", "2026-08-11 09:05:00")
        )

        val normalized = normalizeDeviceEvents(raw)

        assertEquals(listOf(3, 1), normalized.map(EventItem::id))
        assertEquals(
            reconcileOfflineDevicesWithEvents(emptyList(), raw, emptyList()),
            reconcileOfflineDevicesWithNormalizedEvents(emptyList(), normalized, emptyList())
        )
    }

    @Test
    fun onlineTransitionResetsOfflineDuplicateCooldown() {
        val raw = listOf(
            event(1, "device_online", "2026-08-11 09:00:00"),
            event(2, "device_offline", "2026-08-11 09:10:00"),
            event(3, "device_online", "2026-08-11 09:11:00"),
            event(4, "device_offline", "2026-08-11 09:12:00")
        )

        val normalized = normalizeDeviceEvents(raw)

        assertEquals(listOf(4, 3, 2, 1), normalized.map(EventItem::id))
    }

    @Test
    fun invalidOrTrailingTimestampTextIsRejected() {
        assertEquals(null, parseEventMillis("2026-02-31 09:00:00"))
        assertEquals(null, parseEventMillis("2026-08-11 09:00:00 extra"))
        assertEquals(null, parseEventMillis("2026-08-11 25:00:00"))
    }
}
