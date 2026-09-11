package com.labprobe.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A single, confirmed online interval for a followed device.
 *
 * [endedAt] is null only while the device is currently online. Consumers can
 * use [durationMillis] directly, which is calculated against the supplied
 * `now` value for that open interval.
 */
internal data class FollowedDevicePresenceSession(
    val startedAt: Instant,
    val endedAt: Instant?,
    val durationMillis: Long
) {
    val isCurrent: Boolean get() = endedAt == null
}

/** A local-calendar aggregate, suitable for a daily duration chart. */
internal data class FollowedDevicePresenceDay(
    val date: LocalDate,
    val onlineDurationMillis: Long,
    val onlineStarts: Int
)

/** One local hour in the selected day's 24-column distribution chart. */
internal data class FollowedDevicePresenceHour(
    val hour: Int,
    val onlineDurationMillis: Long
)

/**
 * Read-only presence data for one explicitly followed device.
 *
 * No value is inferred from traffic, device snapshots, or malformed events.
 * A closed session therefore requires both a parseable online event and a
 * later parseable offline event. An open session is allowed only when the
 * normalized event history ends online and the current device snapshot is
 * also online; it is capped by the supplied [now] instant.
 */
internal data class FollowedDevicePresence(
    val isAvailable: Boolean,
    val hasPresenceEvents: Boolean,
    val sessions: List<FollowedDevicePresenceSession>,
    val today: FollowedDevicePresenceDay?,
    val last7Days: List<FollowedDevicePresenceDay>,
    val last10Days: List<FollowedDevicePresenceDay>,
    val todayHours: List<FollowedDevicePresenceHour>,
    val currentOnlineDurationMillis: Long?
) {
    companion object {
        fun unavailable() = FollowedDevicePresence(
            isAvailable = false,
            hasPresenceEvents = false,
            sessions = emptyList(),
            today = null,
            last7Days = emptyList(),
            last10Days = emptyList(),
            todayHours = emptyList(),
            currentOnlineDurationMillis = null
        )
    }
}

/**
 * Builds presence statistics for one locally followed device.
 *
 * Event timestamps are parsed through the existing [parseEventMillis] and
 * deduplicated through [normalizeDeviceEvents]. [zoneId] controls only the
 * calendar presentation and bucket boundaries, so callers and tests can keep
 * date labels deterministic without changing global device-event behaviour.
 */
internal fun followedDevicePresence(
    device: DeviceItem,
    events: List<EventItem>,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): FollowedDevicePresence {
    if (device.followedOverride != true) return FollowedDevicePresence.unavailable()

    val deviceMac = cleanMac(device.mac)
    if (!isValidMac(deviceMac)) return FollowedDevicePresence.unavailable()

    val normalized = normalizeDeviceEvents(
        events.asSequence()
            .filter { event ->
                event.type == "device_online" || event.type == "device_offline"
            }
            .filter { event -> cleanMac(event.mac) == deviceMac }
            .filter { event ->
                val at = parseEventMillis(event.time)
                at != null && at <= now.toEpochMilli()
            }
            .toList()
    ).sortedWith(compareBy<EventItem> { parseEventMillis(it.time) }.thenBy { it.id })

    val sessions = buildPresenceSessions(normalized, now, device.online)
    val todayDate = now.atZone(zoneId).toLocalDate()
    val last10Days = dailyPresence(sessions, todayDate, 10, now, zoneId)
    val last7Days = last10Days.takeLast(7)
    val today = last10Days.last()
    val hours = hourlyPresence(sessions, todayDate, now, zoneId)
    val current = sessions.firstOrNull(FollowedDevicePresenceSession::isCurrent)

    return FollowedDevicePresence(
        isAvailable = true,
        hasPresenceEvents = normalized.isNotEmpty(),
        sessions = sessions.sortedByDescending(FollowedDevicePresenceSession::startedAt),
        today = today,
        last7Days = last7Days,
        last10Days = last10Days,
        todayHours = hours,
        currentOnlineDurationMillis = current?.durationMillis
    )
}

private fun buildPresenceSessions(
    events: List<EventItem>,
    now: Instant,
    deviceOnline: Boolean
): List<FollowedDevicePresenceSession> {
    var activeStart: Instant? = null
    val sessions = mutableListOf<FollowedDevicePresenceSession>()

    events.forEach { event ->
        val at = parseEventMillis(event.time)?.let(Instant::ofEpochMilli) ?: return@forEach
        when (event.type) {
            "device_online" -> if (activeStart == null) activeStart = at
            "device_offline" -> {
                val start = activeStart ?: return@forEach
                if (!at.isBefore(start)) {
                    sessions += FollowedDevicePresenceSession(
                        startedAt = start,
                        endedAt = at,
                        durationMillis = at.toEpochMilli() - start.toEpochMilli()
                    )
                }
                activeStart = null
            }
        }
    }

    activeStart?.takeIf { deviceOnline && !it.isAfter(now) }?.let { start ->
        sessions += FollowedDevicePresenceSession(
            startedAt = start,
            endedAt = null,
            durationMillis = now.toEpochMilli() - start.toEpochMilli()
        )
    }
    return sessions
}

private fun dailyPresence(
    sessions: List<FollowedDevicePresenceSession>,
    today: LocalDate,
    dayCount: Long,
    now: Instant,
    zoneId: ZoneId
): List<FollowedDevicePresenceDay> {
    val firstDate = today.minusDays(dayCount - 1)
    return (0 until dayCount).map { offset ->
        val date = firstDate.plusDays(offset)
        val start = date.atStartOfDay(zoneId).toInstant()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().coerceAtMost(now)
        val duration = if (end.isAfter(start)) {
            sessions.sumOf { session -> overlapMillis(session.startedAt, sessionEnd(session, now), start, end) }
        } else 0L
        FollowedDevicePresenceDay(
            date = date,
            onlineDurationMillis = duration,
            onlineStarts = sessions.count { it.startedAt.atZone(zoneId).toLocalDate() == date }
        )
    }
}

private fun hourlyPresence(
    sessions: List<FollowedDevicePresenceSession>,
    today: LocalDate,
    now: Instant,
    zoneId: ZoneId
): List<FollowedDevicePresenceHour> = (0..23).map { hour ->
    val start = today.atTime(hour, 0).atZone(zoneId).toInstant()
    val end = today.atTime(hour, 0).plusHours(1).atZone(zoneId).toInstant().coerceAtMost(now)
    FollowedDevicePresenceHour(
        hour = hour,
        onlineDurationMillis = if (end.isAfter(start)) {
            sessions.sumOf { session -> overlapMillis(session.startedAt, sessionEnd(session, now), start, end) }
        } else 0L
    )
}

private fun sessionEnd(session: FollowedDevicePresenceSession, now: Instant): Instant = session.endedAt ?: now

private fun overlapMillis(start: Instant, end: Instant, rangeStart: Instant, rangeEnd: Instant): Long {
    val overlapStart = maxOf(start, rangeStart)
    val overlapEnd = minOf(end, rangeEnd)
    return if (overlapEnd.isAfter(overlapStart)) {
        overlapEnd.toEpochMilli() - overlapStart.toEpochMilli()
    } else 0L
}
