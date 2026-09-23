package com.labprobe.app

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val CHILD_GUARD_OVERVIEW_POLL_MS = 20_000L

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

    val effectiveDevices = remember(overview.devices) {
        val seen = mutableSetOf<String>()
        overview.devices.filter { dev ->
            val mac = dev.summary.macAddresses.firstOrNull()?.let(::cleanMac)?.lowercase()
            val key = mac?.takeIf { it.isNotBlank() } ?: dev.summary.deviceId.lowercase()
            seen.add(key)
        }
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = LabV2.Ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddDevice, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.PersonAdd, contentDescription = "添加守护", tint = LabV2.Ink, modifier = Modifier.size(22.dp))
            }
        }

        ChildInternetOfficialHero(
            guardedCount = effectiveDevices.size,
            freshness = childGuardFreshnessLabel(
                dataEpoch = overview.lastSampleAtEpoch,
                stale = overview.stale,
                refreshing = overview.refreshing,
                failed = overview.error.isNotBlank()
            )
        )

        MasterGuardCard(
            enabled = overview.masterEnabled,
            busy = overview.pendingDeviceIds.isNotEmpty(),
            hasPlans = overview.devices.any { it.plans.isNotEmpty() },
            onEnabledChange = repository::setMasterEnabled
        )

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
                        Text("添加设备", style = LabTypography.CompactButton.copy(color = Color(0xFF16B9BE), fontSize = 14.sp))
                    }
                }
            }
        } else {
            effectiveDevices.forEach { device ->
                val matched = remember(device.summary.deviceId, devices) {
                    devices.firstOrNull { d ->
                        cleanMac(d.mac).equals(cleanMac(device.summary.deviceId), ignoreCase = true) ||
                            device.summary.macAddresses.any { cleanMac(it).equals(cleanMac(d.mac), ignoreCase = true) }
                    }
                }
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
private fun ChildInternetOfficialHero(guardedCount: Int, freshness: String) {
    // 时效标签的底色跟着状态走：失败才是红，同步中是灰蓝，正常是绿。
    val tone = when {
        freshness.startsWith("更新失败") -> Color(0xFFDC2626)
        freshness.startsWith("等待") || freshness.contains("同步中") -> Color(0xFF64748B)
        else -> Color(0xFF059669)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFEAF3FE),
        border = BorderStroke(1.dp, Color(0xFFDDEBFC))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFE5F1FF),
                            Color(0xFFEEF5FF),
                            Color(0xFFF7FAFF)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "儿童上网守护",
                        style = LabTypography.PageTitle.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Face,
                            null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "已守护 ${guardedCount} 台设备 · 保护健康上网",
                            style = LabTypography.Supporting.copy(
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal
                            )
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = tone.copy(alpha = 0.12f),
                        border = BorderStroke(0.6.dp, tone.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(tone))
                            Text(
                                freshness,
                                style = LabTypography.Caption.copy(
                                    fontSize = 10.5.sp,
                                    color = tone,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.size(54.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFD3E7FE).copy(alpha = 0.7f),
                        modifier = Modifier.size(50.dp)
                    ) {}
                    Icon(
                        Icons.Rounded.LaptopMac,
                        null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(28.dp)
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFFEDD5),
                        border = BorderStroke(1.5.dp, Color.White),
                        modifier = Modifier.size(22.dp).align(Alignment.TopEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ChildCare, null, tint = Color(0xFFEA580C), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterGuardCard(enabled: Boolean, busy: Boolean, hasPlans: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "全设备上网计划",
                        style = LabTypography.CardTitle.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                    )
                    if (enabled) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF5B67EA)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                Text(
                                    "管控中",
                                    style = LabTypography.Caption.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }
                Switch(
                    checked = enabled,
                    enabled = !busy && hasPlans,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF16B9BE),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFE2E8F0),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (hasPlans) "控制全部上网计划；一键禁网需单独恢复，统计不受影响" else "请先为设备创建上网计划，统计不受影响",
                style = LabTypography.Supporting.copy(
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
            )
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
        shape = RoundedCornerShape(18.dp),
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            // 「一键禁网」那行和「家长请注意」那行之间留 9dp 会显得卡片很松，
            // 官方是贴着排的。
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accent.copy(alpha = .08f),
                    border = BorderStroke(1.dp, Color(0xFFF1F5F9))
                ) {
                    Box(Modifier.padding(5.dp)) {
                        LabMiniDeviceIcon(iconKey, accent, sizeDp = 40)
                    }
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        displayName,
                        style = LabTypography.CardTitle.copy(
                            fontSize = 15.sp,
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
                            fontSize = 12.sp,
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
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.BarChart,
                            null,
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Spacer(Modifier.size(6.dp))
                // 今日数字归上网报告所有：null 是「还没统计」，0 才是「无上网记录」。
                Text(
                    childGuardTodayLine(device),
                    style = LabTypography.Body.copy(
                        color = if (device.usage?.onlineMinutes == null) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 12.5.sp
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
                        DropdownMenuItem(
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
                    DropdownMenu(
                        expanded = showBlockMenu,
                        onDismissRequest = { showBlockMenu = false },
                        modifier = Modifier.width(168.dp),
                        shape = RoundedCornerShape(20.dp),
                        containerColor = LabCoreSurface.Card,
                        tonalElevation = 0.dp,
                        shadowElevation = 10.dp
                    ) {
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
internal fun childGuardTodayLine(device: ChildInternetDeviceState): String {
    val scope = childGuardUsageScopeLabel(device)
    val minutes = device.usage?.onlineMinutes
    return when {
        minutes == null -> "${scope}上网 -- · 数据同步中"
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
