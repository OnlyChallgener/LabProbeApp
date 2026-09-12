package com.labprobe.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal enum class PresenceRange(val label: String) {
    TODAY("今天"),
    SEVEN_DAYS("近7天"),
    TEN_DAYS("近10天")
}

internal data class PresenceChartData(
    val values: List<Long>,
    val labelsByIndex: List<String>,
    val detailLabels: List<String>,
    val elapsedMillisByIndex: List<Long>,
    val scaleLabel: String,
    val highlightIndex: Int,
    val rangeLabel: String,
    val selectionKey: String,
    val hasRecords: Boolean
) {
    val peakDurationMillis: Long get() = values.maxOrNull() ?: 0L
    // The minimum axis height keeps short bars readable; it is not a recorded peak.
    val maximum: Long get() = peakDurationMillis.coerceAtLeast(3_600_000L)
    val totalDurationMillis: Long get() = values.sum()
    val maxPossibleMillis: Long get() = elapsedMillisByIndex.sum()
    val onlineRatePercent: Int?
        get() = if (hasRecords) presenceOnlineRate(totalDurationMillis, maxPossibleMillis) else null
}

internal fun presenceOnlineRate(durationMillis: Long, elapsedMillis: Long): Int? =
    if (elapsedMillis <= 0L) null else {
        (durationMillis.coerceAtLeast(0L).toDouble() / elapsedMillis * 100.0)
            .coerceIn(0.0, 100.0).roundToInt()
    }

private fun elapsedPresenceMillis(start: Instant, end: Instant, now: Instant): Long =
    (minOf(end, now).toEpochMilli() - start.toEpochMilli()).coerceAtLeast(0L)

internal fun presenceChartData(
    presence: FollowedDevicePresence,
    range: PresenceRange,
    now: Instant,
    zoneId: ZoneId
): PresenceChartData {
    val today = now.atZone(zoneId).toLocalDate()
    if (range == PresenceRange.TODAY) {
        val hours = presence.todayHours
        val values = hours.map { it.onlineDurationMillis }
        val highest = values.maxOrNull() ?: 0L
        return PresenceChartData(
            values = values,
            labelsByIndex = hours.map { if (it.hour % 4 == 0) "${it.hour}点" else "" },
            detailLabels = hours.map {
                if (it.onlineDurationMillis > 0L) "${it.hour}点 · 在线 ${formatPresenceDuration(it.onlineDurationMillis)}"
                else "${it.hour}点 · 无在线记录"
            },
            elapsedMillisByIndex = hours.map {
                val start = today.atTime(it.hour, 0)
                elapsedPresenceMillis(
                    start.atZone(zoneId).toInstant(),
                    start.plusHours(1).atZone(zoneId).toInstant(),
                    now
                )
            },
            scaleLabel = if (highest > 0L) "每小时在线 · 最高 ${formatPresenceDuration(highest)}" else "每小时在线时长",
            highlightIndex = now.atZone(zoneId).hour,
            rangeLabel = "今日在网概况",
            selectionKey = "${range.name}:$today",
            hasRecords = presence.hasPresenceEvents
        )
    }
    val days = if (range == PresenceRange.SEVEN_DAYS) presence.last7Days else presence.last10Days
    val values = days.map { it.onlineDurationMillis }
    val highest = values.maxOrNull() ?: 0L
    val formatter = DateTimeFormatter.ofPattern("M/d", Locale.CHINA)
    return PresenceChartData(
        values = values,
        labelsByIndex = days.mapIndexed { index, day ->
            if (index == 0 || index == days.lastIndex / 2 || index == days.lastIndex) day.date.format(formatter) else ""
        },
        detailLabels = days.map { day ->
            val dateLabel = "${day.date.monthValue}月${day.date.dayOfMonth}日"
            if (day.onlineDurationMillis > 0L) {
                val starts = if (day.onlineStarts > 0) " (${day.onlineStarts}次上线)" else ""
                "$dateLabel · 在线 ${formatPresenceDuration(day.onlineDurationMillis)}$starts"
            } else "$dateLabel · 无在线记录"
        },
        elapsedMillisByIndex = days.map { day ->
            elapsedPresenceMillis(
                day.date.atStartOfDay(zoneId).toInstant(),
                day.date.plusDays(1).atStartOfDay(zoneId).toInstant(),
                now
            )
        },
        scaleLabel = if (highest > 0L) "每日在线 · 最高 ${formatPresenceDuration(highest)}" else "每日在线时长",
        highlightIndex = values.lastIndex,
        rangeLabel = if (range == PresenceRange.SEVEN_DAYS) "近7天在网概况" else "近10天在网概况",
        selectionKey = "${range.name}:$today",
        hasRecords = presence.hasPresenceEvents
    )
}

/** Prefer the records' current session. Snapshot fallback is display-only, never historical data. */
internal fun deviceDetailOnlineDurationMillis(
    device: DeviceItem,
    presence: FollowedDevicePresence,
    now: Instant
): Long? {
    if (!device.online) return null
    presence.currentOnlineDurationMillis?.let { return it }
    val startedAt = parseEventMillis(device.onlineSince)
    if (startedAt != null) return (now.toEpochMilli() - startedAt).takeIf { it >= 0L }
    return parseDurationSeconds(device.onlineDurationText)?.times(1000L)
}

internal fun formatPresenceDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "0分"
    val totalMinutes = durationMillis / 60_000L
    if (totalMinutes <= 0L) return "<1分"
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val minutes = totalMinutes % 60L
    return buildString {
        if (days > 0L) append(days).append("天")
        if (hours > 0L) append(hours).append("小时")
        if (minutes > 0L && days == 0L) append(minutes).append("分")
    }
}
