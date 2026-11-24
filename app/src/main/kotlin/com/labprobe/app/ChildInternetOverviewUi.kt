package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Popup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val CHILD_GUARD_OVERVIEW_POLL_MS = 20_000L

internal fun childGuardMatchedDevice(device: ChildInternetDeviceState, devices: List<DeviceItem>): DeviceItem? =
    devices.firstOrNull { device.summary.matchesChildGuardDevice(it.mac) }

internal fun childGuardOnlineState(device: ChildInternetDeviceState, devices: List<DeviceItem>): Boolean? =
    device.presence?.online ?: childGuardMatchedDevice(device, devices)?.online ?: device.summary.isOnline.takeIf { it }

internal fun orderedChildGuardDevices(guarded: List<ChildInternetDeviceState>, devices: List<DeviceItem>): List<ChildInternetDeviceState> {
    val seen = mutableSetOf<String>()
    return guarded.filter { device ->
        seen.add(device.summary.macAddresses.firstOrNull()?.let(::cleanMac)?.lowercase()?.takeIf { it.isNotBlank() }
            ?: device.summary.deviceId.lowercase())
    }.sortedBy { when (childGuardOnlineState(it, devices)) { true -> 0; null -> 1; false -> 2 } }
}

@Composable
fun ChildInternetOverviewScreen(
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onAddDevice: () -> Unit,
    repository: ChildInternetRepository,
    devices: List<DeviceItem> = emptyList()
) {
    val overview = repository.state
    // 进入页面只铺缓存 + 读一次聚合；重量级扇出留给用户主动动作。
    LaunchedEffect(repository) { repository.hydrateFromCache() }
    var screenVisible by remember { mutableStateOf(true) }
    val context = LocalContext.current
    DisposableEffect(context) {
        val lifecycle = (context.findActivity() as? ComponentActivity)?.lifecycle
            ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> screenVisible = true
                Lifecycle.Event.ON_STOP -> screenVisible = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repository, screenVisible) {
        while (isActive && screenVisible) {
            repository.refreshOverview()
            delay(CHILD_GUARD_OVERVIEW_POLL_MS)
        }
    }

    val effectiveDevices = remember(overview.devices, devices) {
        orderedChildGuardDevices(overview.devices, devices)
    }

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ChildGuardOperationHud(overview.pendingHud)
        // 返回在左、添加在右、标题正好居中：三个元素互不挤占，标题不会随按钮宽度偏移。
        Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = LabV2.Ink, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onAddDevice, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.PersonAdd, contentDescription = "添加守护", tint = LabV2.Ink, modifier = Modifier.size(22.dp))
                }
            }
            Text(
                "儿童上网",
                modifier = Modifier.align(Alignment.Center),
                style = LabTypography.PageTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = LabV2.Ink
            )
        }

        ChildInternetOfficialHero(
            guardedCount = effectiveDevices.size,
            onlineCount = effectiveDevices.count { childGuardOnlineState(it, devices) == true },
            offlineCount = effectiveDevices.count { childGuardOnlineState(it, devices) == false },
            freshness = childGuardFreshnessLabel(
                dataEpoch = overview.lastSampleAtEpoch,
                stale = overview.stale,
                refreshing = overview.loading && effectiveDevices.isEmpty(),
                failed = overview.error.isNotBlank()
            )
        )

        MasterGuardCard(
            enabled = overview.masterEnabled,
            busy = overview.pendingDeviceIds.isNotEmpty(),
            known = overview.masterKnown,
            onEnabledChange = repository::setMasterEnabled
        )

        val dayTotals = remember(effectiveDevices) { childGuardDayTotals(effectiveDevices) }
        ChildGuardTodayUsageCard(dayTotals)

        if (overview.error.isNotBlank()) {
            // 失败只加一条横幅，下面那一屏缓存内容原样留着。
            LabCoreCard(contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        overview.error,
                        style = LabTypography.Supporting.copy(color = LabV2.Red),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = repository::refreshOverview) { Text("重试") }
                }
            }
        }

        if (overview.loading && effectiveDevices.isEmpty()) {
            Text("正在读取路由器守护列表…", style = LabTypography.Supporting,
                modifier = Modifier.padding(vertical = 16.dp))
        } else if (effectiveDevices.isEmpty()) {
            LabCoreCard(contentPadding = PaddingValues(vertical = 32.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = CircleShape, color = LabV2.Field, modifier = Modifier.size(60.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Face, null, tint = LabV2.InkMuted, modifier = Modifier.size(34.dp))
                        }
                    }
                    Text("还没有守护设备", style = LabTypography.CardTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
                    Text("选择需要管理的设备加入守护", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onAddDevice,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, Color(0xFF16B9BE)),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Color(0xFF16B9BE), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("添加设备", style = LabTypography.CompactButton.copy(color = Color(0xFF16B9BE), fontSize = 13.sp))
                    }
                }
            }
        } else {
            effectiveDevices.forEach { device ->
                val matched = remember(device.summary, devices) { childGuardMatchedDevice(device, devices) }
                ProtectedDeviceCard(
                    device = device,
                    matchedDevice = matched,
                    onOpen = { onOpenDevice(device.summary.deviceId) },
                    repository = repository
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun ChildInternetOfficialHero(guardedCount: Int, onlineCount: Int, offlineCount: Int, freshness: String) {
    val unknownCount = (guardedCount - onlineCount - offlineCount).coerceAtLeast(0)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.Transparent) {
        Row(
            Modifier
                .background(Brush.horizontalGradient(listOf(Color(0xFFE8F3FF), Color(0xFFEEF7FF), Color(0xFFF7FBFF))))
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "儿童上网守护",
                    style = LabTypography.PageTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Text("共 $guardedCount 台设备加入守护", style = LabTypography.Supporting.copy(fontSize = 12.sp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    StatusDot(LabV2.Green)
                    Text("在线 $onlineCount", style = LabTypography.Caption.copy(color = LabV2.Green, fontSize = 11.sp))
                    StatusDot(LabV2.InkMuted.copy(alpha = .55f))
                    Text("离线 $offlineCount", style = LabTypography.Caption.copy(fontSize = 11.sp))
                    if (unknownCount > 0) {
                        StatusDot(LabV2.InkMuted.copy(alpha = .3f))
                        Text("待确认 $unknownCount", style = LabTypography.Caption.copy(fontSize = 11.sp))
                    }
                    Box(Modifier.width(1.dp).height(9.dp).background(Color(0xFFD9E6F2)))
                    Text(freshness, style = LabTypography.Caption.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // 插画是这张卡的视觉重心，但不该撑高卡片：78dp 刚好跟三行文字齐平。
            Image(
                painter = painterResource(R.drawable.child_guard_hero),
                contentDescription = null,
                modifier = Modifier.height(78.dp).padding(start = 2.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
}

/**
 * 概览统计卡的唯一口径：所有受守护设备当日有效活跃分钟之和。
 *
 * 小时桶按每台设备自己的 `hourly[]` 逐小时累加，多设备同时上网时单桶可以超过
 * 60 分钟；总时长优先取桶之和，保证「柱状图加起来」永远等于「今日总时长」。
 * 未知（`hasData == false`）的设备不计入，但也绝不把总数压成 0。
 */
internal data class ChildGuardDayTotals(
    val minutes: Int?,
    val yesterdayMinutes: Int?,
    val hourly: List<Int>,
    val hasData: Boolean,
)

internal fun childGuardDayTotals(
    devices: List<ChildInternetDeviceState>,
    today: String = childGuardStatisticsDate(),
    yesterday: String = LocalDate.parse(today).minusDays(1).toString(),
): ChildGuardDayTotals {
    val buckets = IntArray(24)
    var barTotal = 0
    devices.forEach { device ->
        device.todayUsage.bars.forEach { bar ->
            if (bar.hour in 0..23) {
                val minutes = bar.minutes.coerceAtLeast(0)
                buckets[bar.hour] += minutes
                barTotal += minutes
            }
        }
    }
    val knownMinutes = devices.mapNotNull { device ->
        device.usage?.takeIf { it.hasData }?.onlineMinutes
    }
    val minutes = when {
        barTotal > 0 -> barTotal
        knownMinutes.isNotEmpty() -> knownMinutes.sum()
        else -> null
    }
    // 只有每台设备都拿到了昨天那一格，才谈「较昨日」；缺一台就不下结论。
    val yesterdayBars = devices.map { device ->
        device.recentUsage.bars.firstOrNull { it.date == yesterday }
    }
    val yesterdayMinutes = if (devices.isNotEmpty() && yesterdayBars.all { it != null }) {
        yesterdayBars.filterNotNull().sumOf { it.minutes }
    } else {
        null
    }
    return ChildGuardDayTotals(
        minutes = minutes,
        yesterdayMinutes = yesterdayMinutes,
        hourly = buckets.toList(),
        hasData = devices.any { it.usage?.hasData == true } || barTotal > 0,
    )
}

/** 今日总时长 + 分时段柱状图；左右两栏用一条淡色竖线分开。 */
@Composable
internal fun ChildGuardTodayUsageCard(totals: ChildGuardDayTotals) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.BarChart, null, tint = Color(0xFF8FB4D6), modifier = Modifier.size(12.dp))
                    Text(
                        "今日上网总时长",
                        style = LabTypography.Caption.copy(fontSize = 11.5.sp),
                        maxLines = 1
                    )
                }
                ChildGuardDurationValue(totals.minutes)
                ChildGuardDayDelta(totals)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFFEAF1F8)))
            Column(
                Modifier
                    .weight(1.06f)
                    .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "今日分时段上网（小时）",
                    style = LabTypography.Caption.copy(fontSize = 11.5.sp),
                    maxLines = 1
                )
                ChildGuardHourChart(totals.hourly, Modifier.fillMaxWidth().height(64.dp))
            }
        }
    }
}

@Composable
private fun ChildGuardDurationValue(minutes: Int?) {
    // 左栏只有屏幕的一半宽，「10小时47分钟」在 27sp 下会折行成「10小时47分 / 钟」。
    if (minutes == null) {
        Text("--", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF94A3B8))
        return
    }
    val hours = minutes / 60
    val rest = minutes % 60
    val numberStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
        color = Color(0xFF172233), letterSpacing = (-0.4).sp
    )
    val unitStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 13.sp, fontWeight = FontWeight.Bold,
        color = Color(0xFF475569), letterSpacing = (-0.2).sp
    )
    Row(verticalAlignment = Alignment.Bottom) {
        // 一行只用一行的高度：每段都锁死 maxLines/softWrap，窄屏上宁可挤也不要折行。
        if (hours > 0) {
            Text("$hours", style = numberStyle, maxLines = 1, softWrap = false)
            Text("小时", style = unitStyle, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(bottom = 1.5.dp))
        }
        Text("$rest", style = numberStyle, maxLines = 1, softWrap = false)
        Text("分钟", style = unitStyle, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(bottom = 1.5.dp))
    }
}

@Composable
private fun ChildGuardDayDelta(totals: ChildGuardDayTotals) {
    val today = totals.minutes
    val yesterday = totals.yesterdayMinutes
    val label: String
    val tone: Color
    val direction: Int
    when {
        today == null -> {
            label = "今日暂无统计"; tone = Color(0xFF94A3B8); direction = 0
        }
        yesterday == null -> {
            label = "昨日数据不足"; tone = Color(0xFF94A3B8); direction = 0
        }
        today == yesterday -> {
            label = "与昨日持平"; tone = Color(0xFF64748B); direction = 0
        }
        today > yesterday -> {
            label = "较昨日 +${formatChildDuration(today - yesterday)}"
            tone = Color(0xFFDC2626); direction = 1
        }
        else -> {
            label = "较昨日 -${formatChildDuration(yesterday - today)}"
            tone = LabV2.Green; direction = -1
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            style = LabTypography.Caption.copy(fontSize = 11.sp, color = tone, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (direction != 0) {
            Icon(
                if (direction > 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                null,
                tint = tone,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

/**
 * 24 个小时桶。桶值可以超过 60 分钟（多设备叠加），所以纵轴按最大桶动态取值，
 * 单位是小时。没有采集到的小时只画浅色底槽，不编造柱子。
 */
@Composable
private fun ChildGuardHourChart(hourly: List<Int>, modifier: Modifier = Modifier) {
    val values = if (hourly.size == 24) hourly else List(24) { 0 }
    val peakMinutes = values.maxOrNull()?.coerceAtLeast(0) ?: 0
    Column(modifier) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
            Column(
                Modifier.width(16.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(hourAxisLabel(peakMinutes), style = LabTypography.Caption.copy(fontSize = 8.5.sp, color = Color(0xFFA9B6C6)))
                Text(hourAxisLabel((peakMinutes + 1) / 2), style = LabTypography.Caption.copy(fontSize = 8.5.sp, color = Color(0xFFA9B6C6)))
                Text("0", style = LabTypography.Caption.copy(fontSize = 8.5.sp, color = Color(0xFFA9B6C6)))
            }
            Spacer(Modifier.width(4.dp))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val gap = 2.dp.toPx()
                val barWidth = ((size.width - gap * 23f) / 24f).coerceAtLeast(1f)
                val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
                val gradient = Brush.verticalGradient(
                    listOf(Color(0xFF8FD3F8), Color(0xFF2F8FE0)),
                    startY = 0f,
                    endY = size.height
                )
                values.forEachIndexed { index, minutes ->
                    val x = index * (barWidth + gap)
                    drawRoundRect(
                        color = Color(0xFFEEF4FB),
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = radius
                    )
                    if (minutes > 0 && peakMinutes > 0) {
                        val height = (size.height * minutes / peakMinutes).coerceAtLeast(2.dp.toPx())
                        drawRoundRect(
                            brush = gradient,
                            topLeft = Offset(x, size.height - height),
                            size = Size(barWidth, height),
                            cornerRadius = radius
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("0", "4", "8", "12", "16", "20").forEach {
                Text(it, style = LabTypography.Caption.copy(fontSize = 8.5.sp, color = Color(0xFFA9B6C6)))
            }
        }
    }
}

private fun hourAxisLabel(minutes: Int): String {
    if (minutes <= 0) return "0"
    val hours = minutes / 60f
    return if (hours >= 10f) "%.0f".format(hours) else "%.1f".format(hours)
}

@Composable
internal fun MasterGuardCard(enabled: Boolean, busy: Boolean, known: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.Transparent) {
        Row(Modifier.background(Brush.horizontalGradient(listOf(Color.White, Color(0xFFF2FAFE), Color(0xFFEDF9F8))))
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("全设备上网计划", style = LabTypography.CardTitle.copy(fontWeight = FontWeight.SemiBold))
                    // 状态只认 Hub/路由器确认的结果；本地开关值不参与这段文案。
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = when {
                            !known -> LabV2.Field
                            enabled -> Color(0xFFE6F8F8)
                            else -> Color(0xFFF1F5F9)
                        }
                    ) {
                        Text(
                            if (!known) "正在同步" else if (enabled) "已开启" else "已暂停",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = LabTypography.Caption.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    !known -> LabV2.InkMuted
                                    enabled -> Color(0xFF0E9AA0)
                                    else -> Color(0xFF64748B)
                                }
                            )
                        )
                    }
                }
                Text(if (known) "统一暂停或恢复计划，统计照常记录" else "等待路由器确认总开关状态",
                    style = LabTypography.Supporting.copy(fontWeight = FontWeight.Normal))
            }
            Switch(checked = enabled, enabled = !busy && known, onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(checkedTrackColor = LabV2.Cyan, checkedThumbColor = Color.White,
                    uncheckedTrackColor = LabV2.Field, uncheckedBorderColor = Color.Transparent))
        }
    }
}

/**
 * 卡片副标题那句「此刻能不能上网」。全部是 Hub 算好的生效态，App 只做单位换算：
 * 倒计时从 `nextChangeAtEpoch` 这个真实边界减出来，绝不拿当前时间编一段时间。
 */
internal fun childGuardScheduleText(schedule: ChildGuardSchedule, nowEpoch: Long): String {
    if (!schedule.known) return ""
    val window = if (schedule.currentStart.isNotBlank() && schedule.currentEnd.isNotBlank())
        " ${schedule.currentStart}–${schedule.currentEnd}" else ""
    val plans = if (schedule.planCount > 1) " · ${schedule.planCount} 条计划" else ""
    val changeIn = childGuardChangeIn(schedule, nowEpoch)
    return when (schedule.state) {
        "allowed" -> "允许上网$window$plans"
        "partial" -> "部分应用可用$window$plans"
        // 下一次变化已经过去（缓存的旧总览）就不报倒计时，宁可只说「当前时段禁网」。
        "blocked" -> if (changeIn.isBlank()) "当前时段禁网" else "当前时段禁网，${changeIn}后允许上网"
        "unrestricted" -> "当前网络无限制"
        else -> ""
    }
}

private val childGuardWeekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val childGuardClockFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 写操作期间的页面级状态。
 *
 * 官方点「完成配置」是整页报「配置中…」，而不是只把那颗按钮变灰显示「正在同步」——
 * 用户需要确认路由器真的在处理，而不是以为自己没点上。文案由仓库按动作给
 * （配置中…/删除中…/解除中…），没有写操作时什么都不画。用 Popup 浮在中间，
 * 两个页面就不必为了盖一层遮罩去改各自的根布局。
 */
@Composable
fun ChildGuardOperationHud(text: String) {
    if (text.isBlank()) return
    Popup(alignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = LabCoreSurface.Card,
            border = BorderStroke(1.dp, Color(0xFFEEF2F7)),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = LabV2.Cyan
                )
                Text(text, style = LabTypography.Body)
            }
        }
    }
}

private fun childGuardChangeIn(schedule: ChildGuardSchedule, nowEpoch: Long): String {
    val at = schedule.nextChangeAtEpoch
    val minutes = at?.let { (it - nowEpoch + 59) / 60 } ?: schedule.minutesToChange?.toLong()
    return when {
        minutes == null || minutes <= 0 -> ""
        minutes < 60 -> "$minutes 分钟"
        // 139 分钟说成「2 小时」会把家长差出去二十分钟，所以带上余数。
        minutes < 24 * 60 -> if (minutes % 60 == 0L) "${minutes / 60} 小时"
            else "${minutes / 60} 小时 ${minutes % 60} 分钟"
        at == null -> "${minutes / (24 * 60)} 天"
        else -> childGuardWeekdayClock(at, nowEpoch)
    }
}

/**
 * 超过一天的下一次变化按官方那样报「下周三 14:44」。
 *
 * 「下周」按 ISO 周算：周日属于上一周，所以周日看周三就是「下周三」，尽管只隔三天。
 */
private fun childGuardWeekdayClock(epoch: Long, nowEpoch: Long): String {
    val zone = childGuardDefaultZone()
    val fromWeek = Instant.ofEpochSecond(nowEpoch).atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)
    val target = Instant.ofEpochSecond(epoch).atZone(zone)
    val weeks = ChronoUnit.WEEKS.between(fromWeek, target.toLocalDate().with(DayOfWeek.MONDAY))
    val prefix = when {
        weeks >= 2 -> "${weeks}周后"
        weeks == 1L -> "下周"
        else -> "本周"
    }
    return "$prefix${childGuardWeekdayLabels[target.dayOfWeek.value - 1]} " +
        target.format(childGuardClockFormat)
}

@Composable
private fun ProtectedDeviceCard(
    device: ChildInternetDeviceState,
    matchedDevice: DeviceItem?,
    onOpen: () -> Unit,
    repository: ChildInternetRepository
) {
    val summary = device.summary
    val profile = matchedDevice?.let { remember(it) { inferDeviceProfile(it) } }
    val displayName = matchedDevice?.let { deviceDisplayName(it) } ?: summary.name
    val iconKey = profile?.iconKey ?: summary.iconKey
    val accent = profile?.accent ?: Color(summary.accentArgb)
    val presence = device.presence
    // 在离线只认总览聚合那一份，聚合没回来才退回局域网列表的已知值。
    val onlineKnown = presence != null || matchedDevice != null
    val isOnline = presence?.online ?: matchedDevice?.online ?: false
    val isBlocked = summary.status == GuardStatus.BLOCKED
    // 「正在同步」只属于被操作的那一台；只有全设备开关（"*"）才让所有卡片一起变。
    val busy = repository.state.pendingDeviceIds.any { pending ->
        pending == "*" || sameChildGuardDevice(pending, summary.deviceId)
    }
    var nowEpoch by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(summary.blockedUntilEpoch, summary.passUntilEpoch, isBlocked) {
        nowEpoch = System.currentTimeMillis() / 1000L
        val deadline = maxOf(summary.blockedUntilEpoch, summary.passUntilEpoch)
        if (deadline > 1L) {
            while (nowEpoch < deadline) {
                kotlinx.coroutines.delay(15_000)
                nowEpoch = System.currentTimeMillis() / 1000L
            }
            // 放行/禁网到点的那一瞬间路由器已经改了状态，界面上不能停在旧文案。
            repository.refreshOverview()
        }
    }

    var showBlockMenu by remember { mutableStateOf(false) }
    // 临时放行是固件的 skip 通道：Hub 在放行期间已经把 isBlocked 算成 false，所以这里
    // 只需要说「为什么现在能上网、还剩多久」。
    val onPass = summary.passUntilEpoch > nowEpoch
    val passMinutes =
        if (onPass) ((summary.passUntilEpoch - nowEpoch + 59) / 60).toInt().coerceAtLeast(1) else 0

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 8.dp),
            // 三行内容（身份 / 今日 / 进度）贴着排，中间不再留大块空白。
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = accent.copy(alpha = .08f),
                    border = BorderStroke(1.dp, Color(0xFFF1F5F9))
                ) {
                    Box(Modifier.padding(4.dp)) {
                        LabMiniDeviceIcon(iconKey, accent, sizeDp = 32)
                    }
                }
                Spacer(Modifier.size(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        displayName,
                        style = LabTypography.CardTitle.copy(
                            fontSize = 14.5.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val isTemporaryBlock = isBlocked && summary.blockedUntilEpoch > nowEpoch
                    val remainingMins = if (isTemporaryBlock) {
                        ((summary.blockedUntilEpoch - nowEpoch + 59) / 60).toInt().coerceAtLeast(1)
                    } else 0

                    val scheduleText = childGuardScheduleText(device.schedule, nowEpoch)
                    val statusText = when {
                        isTemporaryBlock -> "已禁网 · 剩余 $remainingMins 分钟"
                        isBlocked && summary.blockedUntilEpoch > 1L -> "禁网已到期 · 正在确认状态"
                        isBlocked -> "已一键禁网 · 手动恢复后可用"
                        onPass && summary.blockedUntilEpoch > nowEpoch ->
                            "临时放行中 · 剩余 $passMinutes 分钟，之后恢复禁网"
                        onPass -> "临时放行中 · 剩余 $passMinutes 分钟"
                        scheduleText.isNotBlank() -> scheduleText
                        summary.status == GuardStatus.GUARDED -> "上网计划守护中"
                        else -> "当前网络无限制"
                    }
                    val statusColor = when {
                        isBlocked || device.schedule.state == "blocked" -> LabV2.Red
                        onPass -> Color(0xFF16A34A)
                        summary.status == GuardStatus.GUARDED -> LabV2.Primary
                        else -> Color(0xFF94A3B8)
                    }
                    Text(
                        statusText,
                        style = LabTypography.Supporting.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            color = statusColor,
                            fontWeight = if (isBlocked) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
                if (isBlocked) {
                    val isTemporaryBlock = summary.blockedUntilEpoch > System.currentTimeMillis() / 1000L
                    val remainingMins = if (isTemporaryBlock) {
                        ((summary.blockedUntilEpoch - System.currentTimeMillis() / 1000L + 59) / 60).toInt().coerceAtLeast(1)
                    } else 0
                    PresenceBadge(
                        if (isTemporaryBlock) "已禁网 ($remainingMins 分钟)" else "已禁网",
                        Color(0xFFFEE2E2), Color(0xFFDC2626), bold = true
                    )
                } else if (onPass) {
                    PresenceBadge("放行中 ($passMinutes 分钟)", Color(0xFFEAF8EF), Color(0xFF16A34A), bold = true)
                } else if (presence?.activeNow == true) {
                    // 「正在上网」只认当前/上一个自然分钟的真实业务流量。
                    PresenceBadge("正在上网", Color(0xFFEAF8EF), Color(0xFF16A34A))
                } else if (isOnline) {
                    PresenceBadge("在线", Color(0xFFEFF6FF), Color(0xFF2563EB))
                } else if (onlineKnown) {
                    PresenceBadge("离线", Color(0xFFF1F5F9), Color(0xFF94A3B8))
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFEEF2FF),
                    modifier = Modifier.size(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.BarChart,
                            null,
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.size(6.dp))
                // 今日数字归上网报告所有：null 是「还没统计」，0 才是「无上网记录」。
                Text(
                    childGuardTodayLine(device, if (onlineKnown) isOnline else null),
                    style = LabTypography.Body.copy(
                        color = if (device.usage?.onlineMinutes == null) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    modifier = Modifier.weight(1f)
                )

                val btnBorderColor = if (isBlocked) Color(0xFF16A34A) else Color(0xFF16B9BE)
                val btnTextColor = if (isBlocked) Color(0xFF16A34A) else Color(0xFF16B9BE)
                val btnBgColor = if (isBlocked) Color(0xFFEAF8EF) else Color.Transparent

                Box {
                    Surface(
                        onClick = {
                            if (isBlocked) repository.setDeviceBlocked(summary.deviceId, false)
                            else showBlockMenu = true
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(50),
                        color = btnBgColor,
                        border = BorderStroke(1.dp, btnBorderColor)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                if (isBlocked) Icons.Rounded.CheckCircleOutline else Icons.Rounded.Block,
                                null,
                                tint = btnTextColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                if (busy) "正在同步…" else if (isBlocked) "恢复上网" else "一键禁网",
                                style = LabTypography.CompactButton.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = btnTextColor
                            )
                            if (!isBlocked) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    null,
                                    tint = btnTextColor,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                    // 官方是一张贴着按钮的大圆角小卡：居中文、没有标题和说明。
                    // 禁网和放行是两个相反的开关，各占一组，中间一道分隔线。
                    val menuChoice: @Composable (String, () -> Unit) -> Unit = { label, choose ->
                        LabDropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = LabTypography.Body.copy(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            },
                            onClick = {
                                showBlockMenu = false
                                choose()
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                    LabDropdownMenu(
                        expanded = showBlockMenu,
                        onDismissRequest = { showBlockMenu = false },
                        modifier = Modifier.widthIn(max = 240.dp),
                        shape = LabPopupShapes.Menu,
                        containerColor = LabCoreSurface.Card,
                        tonalElevation = 0.dp,
                        shadowElevation = 10.dp, actionMenu = true) {
                        listOf<Pair<String, Int?>>(
                            "立即禁网" to null,
                            "禁网 10 分钟" to 10,
                            "禁网 30 分钟" to 30,
                            "禁网 1 小时" to 60
                        ).forEach { (label, minutes) ->
                            menuChoice(label) {
                                repository.setDeviceBlocked(summary.deviceId, true, durationMinutes = minutes)
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        listOf(
                            "今日放行" to "today",
                            "放行 10 分钟" to "10m",
                            "放行 30 分钟" to "30m",
                            "放行 1 小时" to "1h"
                        ).forEach { (label, preset) ->
                            menuChoice(label) { repository.setDevicePass(summary.deviceId, preset) }
                        }
                    }
                }
            }

            // 分母一律写出来：有真实额度用真实额度，没有就用 8 小时参考值。
            // 右边这段文字同时把进度条压短，不再横到卡片边上。
            val progressMinutes = device.usage?.onlineMinutes
            val dailyLimit = device.usage?.dailyLimitMinutes
            if (progressMinutes != null) {
                val percent = childGuardUsagePercent(progressMinutes, dailyLimit)
                val exhausted = dailyLimit != null && percent >= 100
                val barBrush = if (exhausted) {
                    Brush.horizontalGradient(listOf(Color(0xFFFCA5A5), Color(0xFFEF4444)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFF5FD3D6), Color(0xFF16B9BE)))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFEEF4FB))
                    ) {
                        if (percent > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth(percent / 100f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(barBrush)
                            )
                        }
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    color = if (exhausted) Color(0xFFEF4444) else Color(0xFF64748B)
                                )
                            ) { append("$percent%") }
                            withStyle(SpanStyle(color = Color(0xFFA9B6C6))) {
                                append(" / ${childGuardLimitLabel(dailyLimit ?: CHILD_GUARD_PROGRESS_REFERENCE_MINUTES)}")
                            }
                        },
                        style = LabTypography.Caption.copy(fontSize = 10.5.sp),
                        maxLines = 1
                    )
                }
            }

            // 深夜提醒只认报告写出的 ALERT；未知/无记录都不是「一切正常」，也就都不报喜。
            val lateNightMinutes = device.usage?.lateNightMinutes
            if (device.usage?.attention == ChildAttentionState.ALERT && (lateNightMinutes ?: 0) > 0) {
                val durationText = formatLateNightDuration(lateNightMinutes)
                val attentionMessage = "【深夜上网】${childGuardUsageScopeLabel(device)}已累计$durationText"
                Surface(
                    onClick = onOpen,
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFFF5F5)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            "❗ 家长请注意",
                            style = LabTypography.Caption.copy(
                                color = Color(0xFFEF4444),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                        Text(
                            attentionMessage,
                            style = LabTypography.Caption.copy(
                                color = Color(0xFF64748B),
                                fontSize = 12.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 没有真实限额时进度条按 8 小时折算，只用于百分比展示，不是限额，也不参与任何禁网判断。 */
internal const val CHILD_GUARD_PROGRESS_REFERENCE_MINUTES = 8 * 60

/**
 * 有真实每日限额（Hub 下发 `dailyLimitMinutes`）就用它做分母，没有才退回 8 小时参考值。
 * 额度用完时封顶 100%，不显示 120% 这种越过限额的数字。
 */
internal fun childGuardUsagePercent(minutes: Int?, limitMinutes: Int? = null): Int {
    if (minutes == null || minutes <= 0) return 0
    val denominator = limitMinutes?.takeIf { it > 0 } ?: CHILD_GUARD_PROGRESS_REFERENCE_MINUTES
    return ((minutes * 100.0) / denominator).roundToInt().coerceIn(0, 100)
}

/** 时长格式化一律接受 null：没统计过就是 `--`，绝不塌成 0分钟。 */
internal fun formatChildDuration(minutes: Int?): String {
    if (minutes == null) return "--"
    val hours = minutes / 60
    val remain = minutes % 60
    return if (hours > 0) "${hours}小时${remain}分钟" else "${remain}分钟"
}

internal fun formatLateNightDuration(minutes: Int?): String {
    if (minutes == null || minutes <= 0) return "0分钟"
    val hours = minutes / 60.0
    return if (hours >= 1.0) {
        if (minutes % 60 == 0) "${minutes / 60}小时"
        else String.format(java.util.Locale.US, "%.1f小时", hours)
    } else {
        "${minutes}分钟"
    }
}

@Composable
private fun PresenceBadge(label: String, container: Color, content: Color, bold: Boolean = false) {
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
            style = LabTypography.Caption.copy(
                color = content,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.5.sp
            )
        )
    }
}

/** 报告覆盖的不是今天时，界面上必须写清是哪一天，不能拿旧数字当今日。 */
internal fun childGuardUsageScopeLabel(device: ChildInternetDeviceState): String {
    val date = device.usage?.date.orEmpty()
    return if (date.isBlank() || date == childGuardStatisticsDate()) "今日" else date.takeLast(5)
}

/** 总览的今日行：未统计 → `--` + 数据同步中；0 分钟 → 无上网记录。 */
internal fun childGuardTodayLine(device: ChildInternetDeviceState, online: Boolean? = device.presence?.online): String {
    val scope = childGuardUsageScopeLabel(device)
    val minutes = device.usage?.onlineMinutes
    return when {
        minutes == null && online != false && device.usageLoading -> "${scope}上网 -- · 更新中"
        minutes == null -> "今日暂无统计"
        minutes == 0 -> "${scope}无上网记录"
        else -> "${scope}上网 ${formatChildDuration(minutes)}"
    }
}

/**
 * 时效标签只讲数据自己的时间，不讲「我刚问过 Hub」。
 * Hub 的 generatedAt 是生成时刻：用量冻结时它照样是"现在"，于是「更新于 12:21」
 * 会盖在 07:44 的旧数字上。数据超过十分钟没动就说「数据停在」。
 */
internal fun childGuardFreshnessLabel(
    dataEpoch: Long?,
    stale: Boolean,
    refreshing: Boolean,
    failed: Boolean,
): String {
    val stamp = dataEpoch?.takeIf { it > 0L }?.let(::formatChildGuardClock)
    val dataAgeSeconds = dataEpoch?.takeIf { it > 0L }?.let { System.currentTimeMillis() / 1000 - it } ?: Long.MAX_VALUE
    val frozen = stale || dataAgeSeconds > 10 * 60
    return when {
        failed && stamp != null -> "更新失败 · 最后数据 $stamp"
        failed -> "更新失败"
        stamp == null -> if (refreshing) "数据同步中…" else "等待首次同步"
        frozen -> if (refreshing) "数据停在 $stamp · 同步中" else "数据停在 $stamp"
        refreshing -> "更新于 $stamp · 同步中"
        else -> "更新于 $stamp"
    }
}

private fun formatChildGuardClock(epochSeconds: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochSeconds * 1000L))
