package com.labprobe.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.HorizontalDivider
import com.labprobe.app.ui.design.LabTypography
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class PresenceRange(val label: String) {
    TODAY("今天"),
    SEVEN_DAYS("近7天"),
    TEN_DAYS("近10天")
}

/** Compact, followed-device-only presentation. No network or persistence work is performed here. */
@Composable
internal fun FollowedDevicePresenceSection(device: DeviceItem, events: List<EventItem>) {
    if (device.followedOverride != true) return

    val zoneId = ZoneId.systemDefault()
    var nowEpochMs by remember(device.mac) { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedRangeName by rememberSaveable(device.mac) { mutableStateOf(PresenceRange.TODAY.name) }
    var showRecords by remember { mutableStateOf(false) }

    LaunchedEffect(device.mac) {
        nowEpochMs = System.currentTimeMillis()
        while (true) {
            delay(60_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }

    val now = remember(nowEpochMs) { Instant.ofEpochMilli(nowEpochMs) }
    val presence = remember(device.mac, device.followedOverride, device.online, events, now, zoneId) {
        followedDevicePresence(device = device, events = events, now = now, zoneId = zoneId)
    }
    if (!presence.isAvailable) return

    val selectedRange = remember(selectedRangeName) {
        PresenceRange.entries.firstOrNull { it.name == selectedRangeName } ?: PresenceRange.TODAY
    }

    CompactListCard(coreSurface = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("在网统计", modifier = Modifier.weight(1f), style = LabTypography.CardTitle)
            Surface(
                onClick = { showRecords = true },
                shape = RoundedCornerShape(99.dp),
                color = Color.Transparent
            ) {
                Row(
                    Modifier.padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("查看记录", style = LabTypography.CompactButton.copy(color = LabV2.Primary))
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(17.dp), tint = LabV2.Primary)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PresenceSummaryMetric(
                label = "连续在线",
                value = when {
                    !device.online -> "当前离线"
                    presence.currentOnlineDurationMillis != null -> formatPresenceDuration(presence.currentOnlineDurationMillis)
                    else -> "记录不足"
                },
                color = if (device.online) LabV2.Green else LabV2.InkMuted,
                modifier = Modifier.weight(1f)
            )
            PresenceSummaryMetric(
                label = "今日在线",
                value = if (!presence.hasPresenceEvents) {
                    "记录不足"
                } else {
                    formatPresenceDuration(presence.today?.onlineDurationMillis ?: 0L)
                },
                color = LabV2.Ink,
                modifier = Modifier.weight(1f)
            )
            PresenceSummaryMetric(
                label = "今日上线",
                value = if (!presence.hasPresenceEvents) "记录不足" else "${presence.today?.onlineStarts ?: 0} 次",
                color = LabV2.Ink,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("在线时长分布", modifier = Modifier.weight(1f), style = LabTypography.SectionTitle)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                PresenceRange.entries.forEach { range ->
                    PresenceRangeButton(range.label, range == selectedRange) {
                        selectedRangeName = range.name
                    }
                }
            }
        }

        val chart = remember(presence, selectedRange) {
            when (selectedRange) {
                PresenceRange.TODAY -> {
                    val detailLabels = presence.todayHours.map { hour ->
                        val dur = formatPresenceDuration(hour.onlineDurationMillis)
                        if (hour.onlineDurationMillis > 0L) "${hour.hour}点 · 在线 $dur"
                        else "${hour.hour}点 · 未在线"
                    }
                    val totalToday = presence.todayHours.sumOf { it.onlineDurationMillis }
                    val currentHour = now.atZone(zoneId).hour
                    val maxToday = (currentHour + 1).coerceAtLeast(1) * 3600_000L
                    PresenceChartData(
                        values = presence.todayHours.map { it.onlineDurationMillis },
                        labelsByIndex = presence.todayHours.map { hour ->
                            if (hour.hour % 4 == 0) "${hour.hour}点" else ""
                        },
                        detailLabels = detailLabels,
                        maximum = presence.todayHours.maxOfOrNull { it.onlineDurationMillis }
                            ?.coerceAtLeast(60L * 60L * 1000L)
                            ?: 60L * 60L * 1000L,
                        scaleLabel = "每小时 · 最高 ${
                            (presence.todayHours.maxOfOrNull { it.onlineDurationMillis }
                                ?.coerceAtLeast(60L * 60L * 1000L)
                                ?: 60L * 60L * 1000L) / 60_000L
                        } 分钟",
                        highlightIndex = currentHour,
                        totalDurationMillis = totalToday,
                        maxPossibleMillis = maxToday,
                        rangeLabel = "今日在网概况"
                    )
                }
                PresenceRange.SEVEN_DAYS -> dailyChartData(presence.last7Days, "近7天在网概况")
                PresenceRange.TEN_DAYS -> dailyChartData(presence.last10Days, "近10天在网概况")
            }
        }
        PresenceBarChart(chart)
        Text(
            "基于设备上下线事件统计 · 缺失记录不计入在线",
            modifier = Modifier.fillMaxWidth(),
            style = LabTypography.Caption.copy(color = LabV2.InkMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (showRecords) {
        FollowedDevicePresenceSheet(
            device = device,
            presence = presence,
            now = now,
            zoneId = zoneId,
            onDismiss = { showRecords = false }
        )
    }
}

@Composable
private fun PresenceSummaryMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = LabTypography.Caption.copy(color = LabV2.InkMuted), maxLines = 1)
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            style = LabTypography.ValueStrong.copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PresenceRangeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(99.dp),
        color = if (selected) LabV2.Primary.copy(alpha = .11f) else Color.Transparent
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            style = LabTypography.Caption.copy(color = if (selected) LabV2.PrimaryStrong else LabV2.InkMuted),
            maxLines = 1
        )
    }
}

private data class PresenceChartData(
    val values: List<Long>,
    val labelsByIndex: List<String>,
    val detailLabels: List<String>,
    val maximum: Long,
    val scaleLabel: String,
    val highlightIndex: Int = -1,
    val totalDurationMillis: Long = 0L,
    val maxPossibleMillis: Long = 1L,
    val rangeLabel: String = "统计概览"
)

private fun dailyChartData(days: List<FollowedDevicePresenceDay>, rangeLabel: String = "日常统计"): PresenceChartData {
    val values = days.map { it.onlineDurationMillis }
    val formatter = DateTimeFormatter.ofPattern("M/d", Locale.CHINA)
    val labels = days.mapIndexed { index, day ->
        if (index == 0 || index == days.lastIndex / 2 || index == days.lastIndex) {
            day.date.format(formatter)
        } else ""
    }
    val detailLabels = days.map { day ->
        val dateLabel = "${day.date.monthValue}月${day.date.dayOfMonth}日"
        if (day.onlineDurationMillis > 0L) {
            val dur = formatPresenceDuration(day.onlineDurationMillis)
            val starts = if (day.onlineStarts > 0) " (${day.onlineStarts}次上线)" else ""
            "$dateLabel · 在线 $dur$starts"
        } else {
            "$dateLabel · 未在线"
        }
    }
    val highestValue = values.maxOrNull() ?: 0L
    val maximum = highestValue.coerceAtLeast(60L * 60L * 1000L)
    val total = values.sum()
    val maxPossible = (days.size.coerceAtLeast(1) * 24L * 3600_000L)
    return PresenceChartData(
        values = values,
        labelsByIndex = labels,
        detailLabels = detailLabels,
        maximum = maximum,
        scaleLabel = if (highestValue > 0L) {
            "每日在线 · 最高 ${formatPresenceDuration(highestValue)}"
        } else {
            "每日在线小时"
        },
        highlightIndex = values.lastIndex,
        totalDurationMillis = total,
        maxPossibleMillis = maxPossible,
        rangeLabel = rangeLabel
    )
}

@Composable
private fun PresenceBarChart(data: PresenceChartData) {
    val hasData = data.values.any { it > 0L }
    var selectedIndex by remember(data) { mutableStateOf<Int?>(null) }
    val isSelected = selectedIndex != null
    val displayLabel = selectedIndex?.let { data.detailLabels.getOrNull(it) } ?: data.scaleLabel

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isSelected) {
                Text(
                    "已选",
                    style = LabTypography.Caption.copy(color = LabV2.PrimaryStrong, fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                displayLabel,
                modifier = Modifier.weight(1f),
                style = LabTypography.Caption.copy(
                    color = if (isSelected) LabV2.PrimaryStrong else LabV2.InkMuted,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                textAlign = TextAlign.End,
                maxLines = 1
            )
            if (isSelected) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = Color.Transparent,
                    onClick = { selectedIndex = null }
                ) {
                    Text("✕", modifier = Modifier.padding(horizontal = 4.dp), style = LabTypography.Caption.copy(color = LabV2.InkMuted))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(82.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                repeat(3) {
                    HorizontalDivider(color = LabV2.Border.copy(alpha = .58f))
                }
            }
            if (hasData) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(if (data.values.size > 12) 2.dp else 5.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    data.values.forEachIndexed { index, value ->
                        val fraction = if (data.maximum > 0L) {
                            (value.toFloat() / data.maximum.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        val isBarSelected = selectedIndex == index
                        val isDefaultHighlight = selectedIndex == null && index == data.highlightIndex
                        val isDimmed = selectedIndex != null && !isBarSelected
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    selectedIndex = if (selectedIndex == index) null else index
                                },
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (value > 0L) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(if (data.values.size > 12) .58f else .66f)
                                        .fillMaxHeight(fraction.coerceAtLeast(.035f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(
                                            when {
                                                isBarSelected -> LabV2.PrimaryStrong
                                                isDefaultHighlight -> LabV2.PrimaryStrong
                                                isDimmed -> LabV2.Primary.copy(alpha = .22f)
                                                else -> LabV2.Primary.copy(alpha = .56f)
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无足够的上下线记录", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                }
            }
        }
        if (data.labelsByIndex.any { it.isNotBlank() }) {
            if (data.values.size > 12) {
                Row(Modifier.fillMaxWidth()) {
                    data.labelsByIndex.filter { it.isNotBlank() }.forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                            textAlign = TextAlign.Start,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    data.labelsByIndex.forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Plan B: Dynamic Insight Panel filling white space with rich summary / slice metrics
        PresenceInsightPanel(
            data = data,
            selectedIndex = selectedIndex,
            onClear = { selectedIndex = null }
        )
    }
}

@Composable
private fun PresenceInsightPanel(
    data: PresenceChartData,
    selectedIndex: Int?,
    onClear: () -> Unit
) {
    val polished = LabMaterialPolish.enabled
    val isSelected = selectedIndex != null && selectedIndex in data.values.indices

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFFF0F7FF) else (if (polished) LabMaterialPolish.colors.surfaceInset else LabCoreSurface.Inner),
        border = BorderStroke(1.dp, if (isSelected) Color(0xFFCCE3FA) else LabCoreSurface.Border.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isSelected) {
            val idx = selectedIndex!!
            val dur = data.values[idx]
            val slotMax = if (data.values.size > 12) 3600_000L else 24L * 3600_000L
            val pct = ((dur.toFloat() / slotMax.toFloat()) * 100f).coerceIn(0f, 100f).roundToInt()
            val detail = data.detailLabels.getOrNull(idx).orEmpty()

            Column(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = LabV2.Primary
                    ) {
                        Text(
                            "已选时段",
                            Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                            style = LabTypography.Caption.copy(fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        detail,
                        Modifier.weight(1f),
                        style = LabTypography.Supporting.copy(fontWeight = FontWeight.SemiBold, color = Color(0xFF10264F)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFE2E8F0),
                        onClick = onClear
                    ) {
                        Text(
                            "✕",
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = LabTypography.Caption.copy(fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (dur > 0L) "时段活跃度：$pct%" else "该时段无在线活动记录",
                        style = LabTypography.Supporting.copy(fontWeight = FontWeight.Medium, color = if (dur > 0L) Color(0xFF0284C7) else Color(0xFF94A3B8))
                    )
                    Text(
                        if (dur > 0L) "时长 ${formatPresenceDuration(dur)}" else "离线",
                        style = LabTypography.Supporting.copy(fontWeight = FontWeight.Bold, color = if (dur > 0L) LabV2.PrimaryStrong else Color(0xFF94A3B8))
                    )
                }
            }
        } else {
            // Default global range summary
            val onlineRate = if (data.maxPossibleMillis > 0L) {
                ((data.totalDurationMillis.toFloat() / data.maxPossibleMillis.toFloat()) * 100f).coerceIn(0f, 100f).roundToInt()
            } else 0

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        data.rangeLabel,
                        style = LabTypography.Supporting.copy(fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        "在线率 $onlineRate%",
                        style = LabTypography.Value.copy(fontWeight = FontWeight.Bold, color = if (onlineRate > 50) LabV2.Green else LabV2.Primary)
                    )
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "累计在网",
                        style = LabTypography.Supporting.copy(fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        formatPresenceDuration(data.totalDurationMillis).ifBlank { "0分钟" },
                        style = LabTypography.Value.copy(fontWeight = FontWeight.Bold, color = LabV2.Ink)
                    )
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        "最高峰值",
                        style = LabTypography.Supporting.copy(fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        formatPresenceDuration(data.maximum).ifBlank { "--" },
                        style = LabTypography.Value.copy(fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowedDevicePresenceSheet(
    device: DeviceItem,
    presence: FollowedDevicePresence,
    now: Instant,
    zoneId: ZoneId,
    onDismiss: () -> Unit
) {
    val today = now.atZone(zoneId).toLocalDate()
    val cutoff = today.minusDays(9).atStartOfDay(zoneId).toInstant()
    val visibleSessions = remember(presence.sessions, cutoff) {
        presence.sessions.filter { session ->
            session.endedAt == null || session.endedAt.isAfter(cutoff) || !session.startedAt.isBefore(cutoff)
        }.take(80)
    }
    val grouped = remember(visibleSessions, zoneId) {
        visibleSessions.groupBy { it.startedAt.atZone(zoneId).toLocalDate() }
    }

    CompactBottomSheet(title = "在网记录", onDismiss = onDismiss, scrollable = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                deviceDisplayName(device),
                modifier = Modifier.weight(1f),
                style = LabTypography.CardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("最近10天", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
        }

        if (visibleSessions.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                Text("暂无足够的上下线记录", style = LabTypography.Body.copy(color = LabV2.InkMuted))
            }
        } else {
            grouped.forEach { (date, sessions) ->
                Text(presenceDateLabel(date, today), style = LabTypography.SectionTitle)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sessions.forEach { session ->
                        PresenceTimelineRow(session, now, zoneId)
                    }
                }
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
private fun PresenceTimelineRow(session: FollowedDevicePresenceSession, now: Instant, zoneId: ZoneId) {
    val timeText = remember(session, zoneId) { presenceSessionTime(session, zoneId) }
    val durationText = remember(session, now) {
        val duration = if (session.isCurrent) now.toEpochMilli() - session.startedAt.toEpochMilli() else session.durationMillis
        if (session.isCurrent) "已连续在线 ${formatPresenceDuration(duration)}"
        else "在线 ${formatPresenceDuration(duration)}"
    }
    val accent = if (session.isCurrent) LabV2.Green else LabV2.Primary

    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(9.dp))
        Text(
            timeText,
            modifier = Modifier.weight(1.2f),
            style = LabTypography.Supporting,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            durationText,
            modifier = Modifier.weight(1f),
            style = LabTypography.Body.copy(color = if (session.isCurrent) LabV2.Green else LabV2.InkMuted),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatPresenceDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "0分"
    val totalMinutes = (durationMillis.coerceAtLeast(0L) / 60_000L)
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

private fun presenceDateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> date.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
}

private fun presenceSessionTime(session: FollowedDevicePresenceSession, zoneId: ZoneId): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    val start = session.startedAt.atZone(zoneId)
    val end = session.endedAt?.atZone(zoneId)
    val dayDifference = end?.toLocalDate()?.toEpochDay()?.minus(start.toLocalDate().toEpochDay())
    return when {
        end == null -> "${start.format(formatter)}–现在"
        dayDifference == 1L -> "${start.format(formatter)}–次日${end.format(formatter)}"
        dayDifference != 0L -> "${start.format(formatter)}–${end.format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA))}"
        else -> "${start.format(formatter)}–${end.format(formatter)}"
    }
}
