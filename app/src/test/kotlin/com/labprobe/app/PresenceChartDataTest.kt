package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PresenceChartDataTest {
    private val utc = ZoneId.of("UTC")
    private val mac = "00:11:22:33:44:55"

    private fun device(followed: Boolean = true, online: Boolean = true) = DeviceItem(
        name = "测试设备", mac = mac, online = online, ip = "", ssid = "", band = "",
        rssi = "", rxrate = "", onlineSince = "", offlineAt = "", onlineDurationText = "",
        lastSeenAt = "", followedOverride = followed
    )

    private fun event(id: Int, type: String, time: String) = EventItem(
        id = id, title = type, type = type, name = "测试设备", oldValue = "", newValue = "",
        time = time, mac = mac
    )

    private fun chart(
        events: List<EventItem>,
        now: Instant,
        range: PresenceRange = PresenceRange.TODAY,
        zone: ZoneId = utc
    ): PresenceChartData {
        val presence = followedDevicePresence(device(), events, now, zone)
        return presenceChartData(presence, range, now, zone)
    }

    @Test
    fun halfAnHourOfContinuousOnlineTimeIsOneHundredPercent() {
        val data = chart(
            listOf(event(1, "device_online", "2026-09-12T00:00:00Z")),
            Instant.parse("2026-09-12T00:30:00Z")
        )
        assertEquals(100, data.onlineRatePercent ?: -1)
        assertEquals(30 * 60_000L, data.maxPossibleMillis)
        assertEquals(30 * 60_000L, data.peakDurationMillis)
        assertEquals(3_600_000L, data.maximum)
        assertEquals(30 * 60_000L, data.elapsedMillisByIndex[0])
        assertEquals(0L, data.elapsedMillisByIndex[1])
        assertEquals(100, presenceOnlineRate(data.values[0], data.elapsedMillisByIndex[0]) ?: -1)
        assertNull(presenceOnlineRate(data.values[1], data.elapsedMillisByIndex[1]))
    }

    @Test
    fun sevenAndTenDayRatesExcludeTheRestOfToday() {
        val now = Instant.parse("2026-09-12T12:30:00Z")
        for ((range, days) in listOf(PresenceRange.SEVEN_DAYS to 7L, PresenceRange.TEN_DAYS to 10L)) {
            val start = now.atZone(utc).toLocalDate().minusDays(days - 1).atStartOfDay(utc).toInstant()
            val data = chart(listOf(event(1, "device_online", start.toString())), now, range)
            assertEquals((days - 1) * 86_400_000L + 750 * 60_000L, data.maxPossibleMillis)
            assertEquals(100, data.onlineRatePercent ?: -1)
            assertEquals(750 * 60_000L, data.elapsedMillisByIndex.last())
        }
    }

    @Test
    fun selectedCurrentDayUsesOnlyElapsedHours() {
        val data = chart(
            listOf(event(1, "device_online", "2026-09-12T06:00:00Z")),
            Instant.parse("2026-09-12T12:00:00Z"),
            PresenceRange.SEVEN_DAYS
        )
        assertEquals(50, presenceOnlineRate(data.values.last(), data.elapsedMillisByIndex.last()) ?: -1)
    }

    @Test
    fun aFiveMinutePeakIsNotRoundedUpToTheAxisMinimum() {
        val events = listOf(
            event(1, "device_online", "2026-09-12T08:00:00Z"),
            event(2, "device_offline", "2026-09-12T08:05:00Z")
        )
        for (range in PresenceRange.entries) {
            val data = chart(events, Instant.parse("2026-09-12T12:00:00Z"), range)
            assertEquals(5 * 60_000L, data.peakDurationMillis)
            assertEquals("5分", formatPresenceDuration(data.peakDurationMillis))
            assertEquals(3_600_000L, data.maximum)
        }
    }

    @Test
    fun aRangeWithNoOnlineTimeHasAZeroPeak() {
        val data = chart(
            listOf(
                event(1, "device_online", "2026-08-01T08:00:00Z"),
                event(2, "device_offline", "2026-08-01T09:00:00Z")
            ),
            Instant.parse("2026-09-12T12:00:00Z")
        )
        assertEquals(0L, data.peakDurationMillis)
        assertEquals(0, data.onlineRatePercent ?: -1)
    }

    @Test
    fun missingEventsDoNotClaimAZeroOnlineRate() {
        val data = chart(emptyList(), Instant.parse("2026-09-12T12:00:00Z"))
        assertNull(data.onlineRatePercent)
    }

    @Test
    fun midnightDoesNotDivideByZero() {
        val data = chart(
            listOf(event(1, "device_online", "2026-09-12T00:00:00Z")),
            Instant.parse("2026-09-12T00:00:00Z")
        )
        assertEquals(0L, data.maxPossibleMillis)
        assertNull(data.onlineRatePercent)
    }

    @Test
    fun calendarDenominatorsRespectDaylightSavingChanges() {
        val zone = ZoneId.of("America/New_York")
        for ((nowText, extraHour) in listOf("2026-11-02T17:00:00Z" to 1L, "2026-03-09T16:00:00Z" to -1L)) {
            val now = Instant.parse(nowText)
            val start = now.atZone(zone).toLocalDate().minusDays(6).atStartOfDay(zone).toInstant()
            val data = chart(listOf(event(1, "device_online", start.toString())), now, PresenceRange.SEVEN_DAYS, zone)
            assertEquals((6 * 24 + 12 + extraHour) * 3_600_000L, data.maxPossibleMillis)
            assertEquals(100, data.onlineRatePercent ?: -1)
        }
    }

    @Test
    fun repeatedHourCanContainTwoHoursOfOnlineTime() {
        val data = chart(
            listOf(
                event(1, "device_online", "2026-11-01T05:00:00Z"),
                event(2, "device_offline", "2026-11-01T07:00:00Z")
            ),
            Instant.parse("2026-11-01T12:00:00Z"),
            zone = ZoneId.of("America/New_York")
        )
        assertEquals(2 * 3_600_000L, data.elapsedMillisByIndex[1])
        assertEquals(100, presenceOnlineRate(data.values[1], data.elapsedMillisByIndex[1]) ?: -1)
    }

    @Test
    fun refreshPreservesTheSelectedRangeButMidnightChangesItsKey() {
        val events = listOf(event(1, "device_online", "2026-09-12T00:00:00Z"))
        val first = chart(events, Instant.parse("2026-09-12T12:00:00Z"))
        val refreshed = chart(events, Instant.parse("2026-09-12T12:01:00Z"))
        val nextDay = chart(events, Instant.parse("2026-09-13T00:01:00Z"))
        assertEquals(first.selectionKey, refreshed.selectionKey)
        assertNotEquals(first.selectionKey, nextDay.selectionKey)
    }

    @Test
    fun followedHeroUsesTheSameSessionAndRoundingAsTheRecords() {
        val device = device().copy(onlineDurationText = "6小时55分")
        val now = Instant.parse("2026-09-12T15:56:59Z")
        val presence = followedDevicePresence(device, listOf(event(1, "device_online", "2026-09-12T09:00:00Z")), now, utc)
        val duration = requireNotNull(deviceDetailOnlineDurationMillis(device, presence, now))
        assertEquals(presence.currentOnlineDurationMillis, duration)
        assertEquals("6小时56分", formatPresenceDuration(duration))
        assertEquals(formatPresenceDuration(presence.sessions.single().durationMillis), formatPresenceDuration(duration))
    }

    @Test
    fun snapshotFallbackDoesNotInventPresenceHistoryOrAnOnlineRate() {
        val device = device().copy(onlineDurationText = "6小时55分")
        val now = Instant.parse("2026-09-12T15:56:00Z")
        val presence = followedDevicePresence(device, emptyList(), now, utc)
        val duration = requireNotNull(deviceDetailOnlineDurationMillis(device, presence, now))
        assertEquals("6小时55分", formatPresenceDuration(duration))
        assertEquals(0, presence.sessions.size)
        assertNull(presenceChartData(presence, PresenceRange.TODAY, now, utc).onlineRatePercent)
        assertNull(deviceDetailOnlineDurationMillis(device.copy(online = false), presence, now))
    }

    @Test
    fun unfollowedDevicesStillShowTheirCurrentSession() {
        val device = device(followed = false).copy(onlineSince = "2026-09-12T09:00:00Z", onlineDurationText = "6小时55分")
        val duration = deviceDetailOnlineDurationMillis(device, FollowedDevicePresence.unavailable(), Instant.parse("2026-09-12T15:56:59Z"))
        assertEquals("6小时56分", formatPresenceDuration(duration!!))
    }
}
