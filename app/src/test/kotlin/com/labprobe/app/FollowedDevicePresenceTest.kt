package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FollowedDevicePresenceTest {
    private val zone = ZoneId.of("UTC")
    private val mac = "00:11:22:33:44:55"

    private fun followedDevice(followed: Boolean? = true, online: Boolean = false) = DeviceItem(
        name = "书房笔记本",
        mac = mac,
        online = online,
        ip = "",
        ssid = "",
        band = "",
        rssi = "",
        rxrate = "",
        onlineSince = "",
        offlineAt = "",
        onlineDurationText = "",
        lastSeenAt = "",
        followedOverride = followed
    )

    private fun event(id: Int, type: String, time: String, eventMac: String = mac) = EventItem(
        id = id,
        title = type,
        type = type,
        name = "书房笔记本",
        oldValue = "",
        newValue = "",
        time = time,
        mac = eventMac
    )

    private fun presence(
        events: List<EventItem>,
        now: String = "2026-09-11T12:00:00Z",
        followed: Boolean? = true,
        online: Boolean = false
    ) = followedDevicePresence(
        device = followedDevice(followed, online),
        events = events,
        now = Instant.parse(now),
        zoneId = zone
    )

    @Test
    fun unfollowedDeviceIsUnavailableAndDoesNotExposeHistory() {
        val result = presence(
            events = listOf(
                event(1, "device_online", "2026-09-11T09:00:00Z"),
                event(2, "device_offline", "2026-09-11T10:00:00Z")
            ),
            followed = false
        )

        assertFalse(result.isAvailable)
        assertTrue(result.sessions.isEmpty())
        assertTrue(result.last7Days.isEmpty())
        assertTrue(result.todayHours.isEmpty())
        assertNull(result.currentOnlineDurationMillis)
    }

    @Test
    fun invalidDeviceMacIsUnavailable() {
        val invalid = followedDevice().copy(mac = "unknown")
        val result = followedDevicePresence(
            device = invalid,
            events = listOf(event(1, "device_online", "2026-09-11T09:00:00Z", "unknown")),
            now = Instant.parse("2026-09-11T12:00:00Z"),
            zoneId = zone
        )

        assertFalse(result.isAvailable)
        assertTrue(result.sessions.isEmpty())
    }

    @Test
    fun filtersByMacAndIgnoresNonPresenceEvents() {
        val result = presence(listOf(
            event(1, "device_online", "2026-09-11T09:00:00Z"),
            event(2, "network_changed", "2026-09-11T09:20:00Z"),
            event(3, "device_offline", "2026-09-11T10:00:00Z"),
            event(4, "device_online", "2026-09-11T08:00:00Z", "aa:bb:cc:dd:ee:ff"),
            event(5, "device_offline", "2026-09-11T11:00:00Z", "aa:bb:cc:dd:ee:ff")
        ))

        assertTrue(result.isAvailable)
        assertEquals(1, result.sessions.size)
        assertEquals(60 * 60 * 1000L, result.sessions.single().durationMillis)
        assertEquals(60 * 60 * 1000L, result.today?.onlineDurationMillis)
    }

    @Test
    fun normalizedDuplicateTransitionsDoNotCreateExtraSessionsOrDuration() {
        val result = presence(listOf(
            event(1, "device_online", "2026-09-11T09:00:00Z"),
            event(2, "device_online", "2026-09-11T09:01:00Z"),
            event(3, "device_offline", "2026-09-11T10:00:00Z"),
            event(4, "device_offline", "2026-09-11T10:01:00Z")
        ))

        assertEquals(1, result.sessions.size)
        assertEquals(60 * 60 * 1000L, result.sessions.single().durationMillis)
        assertEquals(1, result.today?.onlineStarts)
    }

    @Test
    fun crossMidnightSessionIsSplitAcrossDailySummaries() {
        val result = presence(
            events = listOf(
                event(1, "device_online", "2026-09-10T23:30:00Z"),
                event(2, "device_offline", "2026-09-11T01:15:00Z")
            ),
            now = "2026-09-11T12:00:00Z"
        )

        val yesterday = result.last10Days.single { it.date == LocalDate.of(2026, 9, 10) }
        assertEquals(30 * 60 * 1000L, yesterday.onlineDurationMillis)
        assertEquals(75 * 60 * 1000L, result.today?.onlineDurationMillis)
        assertEquals(0, result.today?.onlineStarts)
        assertEquals(60 * 60 * 1000L, result.todayHours[0].onlineDurationMillis)
        assertEquals(15 * 60 * 1000L, result.todayHours[1].onlineDurationMillis)
        assertEquals(0L, result.todayHours[2].onlineDurationMillis)
    }

    @Test
    fun currentSessionUsesInjectedNowAndBuildsTwentyFourHourBuckets() {
        val result = presence(
            events = listOf(event(1, "device_online", "2026-09-11T09:30:00Z")),
            now = "2026-09-11T10:40:00Z",
            online = true
        )

        assertEquals(70 * 60 * 1000L, result.currentOnlineDurationMillis)
        assertEquals(70 * 60 * 1000L, result.today?.onlineDurationMillis)
        assertEquals(24, result.todayHours.size)
        assertEquals(30 * 60 * 1000L, result.todayHours[9].onlineDurationMillis)
        assertEquals(40 * 60 * 1000L, result.todayHours[10].onlineDurationMillis)
        assertEquals(0L, result.todayHours[11].onlineDurationMillis)
    }

    @Test
    fun malformedOrUnpairedBoundariesDoNotInventOnlineDuration() {
        val result = presence(listOf(
            event(1, "device_online", "not-a-timestamp"),
            event(2, "device_offline", "2026-09-11T10:00:00Z"),
            event(3, "device_offline", "2026-09-11T11:00:00Z")
        ))

        assertTrue(result.sessions.isEmpty())
        assertEquals(0L, result.today?.onlineDurationMillis)
        assertNull(result.currentOnlineDurationMillis)
    }

    @Test
    fun onlineTransitionSeparatesNearbyOfflineBoundaries() {
        val result = presence(listOf(
            event(1, "device_online", "2026-09-11T08:00:00Z"),
            event(2, "device_offline", "2026-09-11T08:10:00Z"),
            event(3, "device_online", "2026-09-11T08:11:00Z"),
            event(4, "device_offline", "2026-09-11T08:12:00Z")
        ))

        assertEquals(2, result.sessions.size)
        assertEquals(11 * 60 * 1000L, result.today?.onlineDurationMillis)
        assertEquals(2, result.today?.onlineStarts)
    }

    @Test
    fun staleOpenEventDoesNotKeepCountingAfterDeviceIsKnownOffline() {
        val result = presence(
            events = listOf(event(1, "device_online", "2026-09-11T09:30:00Z")),
            now = "2026-09-11T10:40:00Z",
            online = false
        )

        assertTrue(result.sessions.isEmpty())
        assertEquals(0L, result.today?.onlineDurationMillis)
        assertNull(result.currentOnlineDurationMillis)
    }

    @Test
    fun dailyWindowsAreFixedToInjectedZoneAndIncludeSevenAndTenDays() {
        val result = presence(
            events = listOf(
                event(1, "device_online", "2026-09-02T08:00:00Z"),
                event(2, "device_offline", "2026-09-02T09:00:00Z"),
                event(3, "device_online", "2026-09-05T08:00:00Z"),
                event(4, "device_offline", "2026-09-05T10:00:00Z"),
                event(5, "device_online", "2026-09-11T08:00:00Z"),
                event(6, "device_offline", "2026-09-11T09:30:00Z")
            )
        )

        assertEquals(10, result.last10Days.size)
        assertEquals(LocalDate.of(2026, 9, 2), result.last10Days.first().date)
        assertEquals(7, result.last7Days.size)
        assertEquals(LocalDate.of(2026, 9, 5), result.last7Days.first().date)
        assertEquals(60 * 60 * 1000L, result.last10Days.first().onlineDurationMillis)
        assertEquals(2 * 60 * 60 * 1000L, result.last7Days.first().onlineDurationMillis)
        assertEquals(90 * 60 * 1000L, result.today?.onlineDurationMillis)
    }

    @Test
    fun repeatedDaylightSavingHourKeepsItsFullDuration() {
        val newYork = ZoneId.of("America/New_York")
        val result = followedDevicePresence(
            device = followedDevice(online = false),
            events = listOf(
                event(1, "device_online", "2026-11-01T05:00:00Z"),
                event(2, "device_offline", "2026-11-01T07:00:00Z")
            ),
            now = Instant.parse("2026-11-01T12:00:00Z"),
            zoneId = newYork
        )

        assertEquals(120 * 60 * 1000L, result.today?.onlineDurationMillis)
        assertEquals(120 * 60 * 1000L, result.todayHours[1].onlineDurationMillis)
    }
}
