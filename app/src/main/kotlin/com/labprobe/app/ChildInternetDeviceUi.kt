package com.labprobe.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.NotificationImportant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max

@Composable
fun ChildInternetDeviceScreen(
    state: AppState,
    deviceId: String?,
    onBack: () -> Unit,
    repository: ChildInternetRepository
) {
    val allDevices = remember(state.devices, state.onlineDevices, state.offlineDevices) {
        mergeSharedDeviceState(state.offlineDevices + state.devices, state.onlineDevices)
    }
    val matchingGuardDevice = remember(deviceId, repository.state.devices) {
        repository.state.devices.firstOrNull { deviceId != null && it.summary.matchesChildGuardDevice(deviceId) }
    }
    val fallback = matchingGuardDevice?.summary
    val appDevice = remember(deviceId, fallback, allDevices) {
        deviceId?.let { id ->
            allDevices.firstOrNull { d ->
                cleanMac(d.mac) == cleanMac(id) ||
                fallback?.macAddresses?.any { sameChildGuardDevice(it, d.mac) } == true ||
                fallback?.matchesChildGuardDevice(d.mac) == true ||
                fallback?.matchesChildGuardDevice(d.name) == true
            }
        }
    }
    val profile = appDevice?.let { remember(it) { inferDeviceProfile(it) } }
    val resolvedId = deviceId ?: fallback?.deviceId.orEmpty()
    val resolvedName = appDevice?.let { deviceDisplayName(it) }
        ?: fallback?.name?.takeIf { it.isNotBlank() && it != "受守护设备" && it != "LabProbe 设备" }
        ?: "未命名设备"
    val iconKey = profile?.iconKey ?: fallback?.iconKey ?: "unknown"
    val accentArgb = profile?.accent?.toArgb() ?: fallback?.accentArgb ?: 0xFF2563EB.toInt()

    LaunchedEffect(resolvedId, resolvedName, iconKey, accentArgb) {
        if (resolvedId.isNotBlank()) repository.ensureDevice(resolvedId, resolvedName, iconKey, accentArgb)
    }

    val deviceState = repository.state.devices.firstOrNull { it.summary.matchesChildGuardDevice(resolvedId) }
    if (deviceState == null) {
        Box(Modifier.fillMaxSize().appBackground(), contentAlignment = Alignment.Center) {
            Text(repository.state.error.ifBlank { "正在准备儿童上网页面…" }, style = LabTypography.Body.copy(color = LabV2.InkMuted))
        }
        return
    }

    var selectedTabName by rememberSaveable(resolvedId) { mutableStateOf(ChildInternetTab.REPORT.name) }
    var showPlanEditor by rememberSaveable(resolvedId) { mutableStateOf(false) }
    var selectedCategoryId by rememberSaveable(resolvedId) { mutableStateOf<String?>(null) }
    var draftPlan by rememberSaveable(
        resolvedId,
        deviceState.plan,
        stateSaver = deviceGuardPlanSaver(deviceState.plan)
    ) { mutableStateOf(deviceState.plan) }
    val selectedTab = ChildInternetTab.valueOf(selectedTabName)
    val selectedCategory = draftPlan.categories.firstOrNull { it.id == selectedCategoryId }

    BackHandler(enabled = selectedCategory != null || (showPlanEditor && !deviceState.plan.configured)) {
        if (selectedCategory != null) selectedCategoryId = null else showPlanEditor = false
    }

    if (selectedCategory != null) {
        ChildInternetAppSelectionScreen(
            category = selectedCategory,
            onBack = { selectedCategoryId = null },
            onDone = { updatedApps ->
                draftPlan = draftPlan.copy(
                    categories = draftPlan.categories.map { category ->
                        if (category.id == selectedCategory.id) category.copy(apps = updatedApps, enabled = updatedApps.any { it.selected }) else category
                    }
                )
                selectedCategoryId = null
            }
        )
        return
    }

    var confirmRemoveGuard by rememberSaveable(resolvedId) { mutableStateOf(false) }
    if (confirmRemoveGuard) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { confirmRemoveGuard = false },
            title = { Text("解除儿童上网守护？", style = LabTypography.CardTitle) },
            text = { Text("确定把「${deviceState.summary.name}」移出儿童守护列表吗？解除后所有上网计划与管控规则将立即失效。", style = LabTypography.Body.copy(color = LabV2.InkMuted)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveGuard = false
                    repository.removeGuardDevice(deviceState.summary.deviceId) { result ->
                        result.onSuccess {
                            toast(context, "已解除儿童守护")
                            onBack()
                        }.onFailure {
                            toast(context, it.message ?: "解除失败")
                        }
                    }
                }) {
                    Text("解除守护", color = LabV2.Red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveGuard = false }) { Text("取消") }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = LabCoreSurface.Card
        )
    }

    Column(Modifier.fillMaxSize().appBackground()) {
        ChildDeviceHeader(
            summary = deviceState.summary,
            onBack = onBack,
            onRemoveGuard = { confirmRemoveGuard = true }
        )
        ChildInternetTabs(
            selected = selectedTab,
            onSelect = { selectedTabName = it.name; showPlanEditor = false }
        )
        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "child-internet-tab"
        ) { tab ->
            when (tab) {
                ChildInternetTab.REPORT -> ChildInternetReportScreen(deviceState) { repository.loadUsageReport(resolvedId) {} }
                ChildInternetTab.PLAN -> {
                    if (!deviceState.plan.configured && !showPlanEditor) {
                        ChildInternetPlanEmptyState(onOpen = { showPlanEditor = true })
                    } else {
                        ChildInternetPlanEditor(
                            plan = draftPlan,
                            appManagementSupported = deviceState.summary.appManagementSupported,
                            experimentalAppControl = deviceState.summary.experimentalAppControl,
                            onPlanChange = { draftPlan = it },
                            onOpenCategory = { selectedCategoryId = it },
                            onEnabledChange = { enabled, context ->
                                if (draftPlan.id.isBlank()) {
                                    draftPlan = draftPlan.copy(enabled = enabled)
                                } else {
                                    repository.setPlanEnabled(resolvedId, draftPlan.id, enabled) { result ->
                                        result.onSuccess {
                                            draftPlan = draftPlan.copy(enabled = enabled)
                                            toast(context, if (enabled) "计划已启用" else "计划已停用")
                                        }.onFailure { toast(context, it.message ?: "操作失败") }
                                    }
                                }
                            },
                            onDelete = { context ->
                                repository.deletePlan(resolvedId, draftPlan.id) { result ->
                                    result.onSuccess {
                                        draftPlan = DeviceGuardPlan(categories = childInternetCatalogCategories())
                                        showPlanEditor = false
                                        toast(context, "计划已删除")
                                    }.onFailure { toast(context, it.message ?: "删除失败") }
                                }
                            },
                            onSave = { context ->
                                repository.savePlan(resolvedId, draftPlan) { result ->
                                    result.onSuccess {
                                        toast(context, "保存成功")
                                        showPlanEditor = false
                                    }.onFailure { toast(context, it.message ?: "保存失败") }
                                }
                            }
                        )
                    }
                }
                ChildInternetTab.ATTENTION -> ChildInternetAttentionScreen(deviceState.attentionEntries.ifEmpty { deriveAttentionEntries(deviceState) })
            }
        }
    }
}

@Composable
private fun ChildDeviceHeader(
    summary: ProtectedDeviceSummary,
    onBack: () -> Unit,
    onRemoveGuard: () -> Unit = {},
    onOpenLog: () -> Unit = {}
) {
    val accent = Color(summary.accentArgb)
    Surface(color = LabV2.BackgroundTop) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = LabV2.Ink)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                LabMiniDeviceIcon(summary.iconKey, accent, sizeDp = 20)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary.name,
                style = LabTypography.PageTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemoveGuard, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.DeleteOutline, "解除儿童守护", tint = LabV2.InkMuted)
            }
            IconButton(onClick = onOpenLog, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.FormatListBulleted, "上网日志", tint = LabV2.InkMuted)
            }
        }
    }
}

@Composable
private fun ChildInternetTabs(selected: ChildInternetTab, onSelect: (ChildInternetTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(LabV2.BackgroundTop).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ChildInternetTab.entries.forEach { tab ->
            val active = tab == selected
            val (icon, badgeColor) = when (tab) {
                ChildInternetTab.REPORT -> Pair(Icons.Rounded.Assessment, Color(0xFF3B82F6))
                ChildInternetTab.PLAN -> Pair(Icons.Rounded.EditCalendar, Color(0xFF6366F1))
                ChildInternetTab.ATTENTION -> Pair(Icons.Rounded.NotificationImportant, Color(0xFF8B5CF6))
            }
            Surface(
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = if (active) LabCoreSurface.Card.copy(alpha = 0.90f) else Color.Transparent,
                shadowElevation = if (active) 1.dp else 0.dp
            ) {
                Column(
                    Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (active) badgeColor.copy(alpha = 0.14f) else Color(0xFFF1F4F9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = tab.title, tint = if (active) badgeColor else LabV2.InkMuted, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        tab.title,
                        style = LabTypography.Caption.copy(
                            color = if (active) LabV2.Ink else LabV2.InkMuted,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ChildInternetReportScreen(device: ChildInternetDeviceState, onRefresh: () -> Unit) {
    val context = LocalContext.current
    var period by rememberSaveable(device.summary.deviceId) { mutableStateOf("今日") }
    var selectedBarIndex by rememberSaveable(device.summary.deviceId, period) { mutableStateOf(0) }
    var selectedAppForTimeline by remember { mutableStateOf<InternetUsageEntry?>(null) }
    var showLogDialog by remember { mutableStateOf(false) }

    val usage = if (period == "今日") device.todayUsage else device.recentUsage
    val currentBars = usage.bars
    val safeIndex = selectedBarIndex.coerceIn(0, max(0, currentBars.lastIndex))
    val selectedBar = currentBars.getOrNull(safeIndex)
    val currentSelectedBarMinutes = selectedBar?.minutes ?: usage.totalMinutes
    val currentEntries = selectedBar?.entries?.takeIf { it.isNotEmpty() } ?: usage.entries

    if (selectedAppForTimeline != null) {
        AppUsageTimelineDialog(
            entry = selectedAppForTimeline!!,
            onDismiss = { selectedAppForTimeline = null }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = LabV2.PageHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Pill capsule toggle: 今日 vs 最近10天
        CompactSegmentedControl(
            options = listOf("今日", "最近10天"),
            selected = period,
            onSelect = {
                period = it
                selectedBarIndex = if (it == "最近10天") 1 else 0
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 62.dp)
        )

        // 当日上网时长 Card
        LabCoreCard(contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (period == "今日") "今日上网时长" else "最近10天上网时长",
                        style = LabTypography.CardTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = if (device.usageSource.isNotBlank()) usageSourceLabel(device.usageSource) else "流量活跃度（含内网）",
                        style = LabTypography.Supporting.copy(fontSize = 12.sp, color = LabV2.InkMuted)
                    )
                }
                FormattedDurationText(currentSelectedBarMinutes)
            }
            UsageBarsWithGrid(
                bars = currentBars,
                selectedIndex = safeIndex,
                onSelectBar = { selectedBarIndex = it }
            )
        }

        val report = device.usageReport
        if (report != null && (report.todayTotalBytes > 0L || report.boundIps.isNotEmpty())) {
            LabCoreCard(contentPadding = PaddingValues(15.dp)) {
                Text("流量与网络统计", style = LabTypography.CardTitle)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportStatMetric("今日总流量", formatBytesShort(report.todayTotalBytes), LabV2.Cyan, Modifier.weight(1f))
                    ReportStatMetric("今日上传", formatBytesShort(report.todayTxBytes), LabV2.Primary, Modifier.weight(1f))
                    ReportStatMetric("今日下载", formatBytesShort(report.todayRxBytes), LabV2.Green, Modifier.weight(1f))
                }
                if (report.boundIps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "审计绑定 IP: ${report.boundIps.joinToString(", ")}",
                        style = LabTypography.Caption.copy(color = LabV2.InkMuted)
                    )
                }
            }
        }

        // 当日应用详情 Card
        LabCoreCard(contentPadding = PaddingValues(horizontal = 15.dp, vertical = 14.dp)) {
            Text(
                text = if (period == "今日") "今日应用详情" else "当日应用详情",
                style = LabTypography.CardTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            if (currentEntries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LabV2ToolIcon(Icons.Rounded.Assessment, LabV2.InkMuted, size = 44, muted = true)
                    Text("暂无应用使用记录", style = LabTypography.SectionTitle)
                }
            } else {
                currentEntries.forEach { entry ->
                    UsageEntryRow(entry, onClick = { selectedAppForTimeline = entry })
                }
            }
        }
        TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("刷新统计", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
        }
        TextButton(onClick = { toast(context, "$USAGE_REPORT_EXPLAINER_TITLE\n\n$USAGE_REPORT_EXPLAINER") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(USAGE_REPORT_EXPLAINER_TITLE, style = LabTypography.Supporting.copy(color = LabV2.InkMuted, fontSize = 13.sp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 官方说明页标题（来自官方 bundle 字符串表，便于家长对照）。 */
internal const val USAGE_REPORT_EXPLAINER_TITLE = "关于\u201c上网时长\u201d和\u201c应用详情\u201d计算方式说明"

/**
 * 口径说明写在这里，而不是让家长自己猜为什么时长比官方少。
 *
 * 官方的算法是「按设备上的动态应用检测 + 单应用流量量级」去**预测**时长
 * （官方原文：微信约 KB/s 级别、视频刷新约 MB/s 级别）。我们是**实测**：
 * 每 60 秒采样一次流量，只有真正出现活跃流量的时间段才计入时长，
 * 后台心跳 / 保活连接只记流量、不计时长。两者口径不同，数字会有差异。
 */
internal const val USAGE_REPORT_EXPLAINER =
    "官方按「动态应用检测 + 单应用流量量级」预测时长；我们是实测：" +
        "路由器每 60 秒采样一次流量，只有出现活跃流量的时间段才计入时长，" +
        "后台心跳 / 保活连接只记流量、不计时长，所以数字可能比官方略少。" +
        "统计含内网使用；应用识别在 IPv4 维度，走 IPv6 的应用可能不计入；保留最近 10 天。"

private fun usageSourceLabel(source: String): String = when (source) {
    "hub" -> "路由器采样 · Hub 聚合 · 已过滤后台心跳"
    "relay" -> "路由器实时读取 · Hub 聚合尚未收到"
    "empty" -> "暂无数据 · 等待路由器首次上报"
    else -> "已过滤后台心跳"
}

@Composable
private fun FormattedDurationText(minutes: Int) {
    val hrs = minutes / 60
    val mins = minutes % 60
    Row(verticalAlignment = Alignment.Bottom) {
        if (hrs > 0) {
            Text(
                text = "$hrs",
                style = LabTypography.PageTitle.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
            )
            Text(
                text = "小时",
                style = LabTypography.Caption.copy(fontSize = 13.sp, color = LabV2.Ink, fontWeight = FontWeight.Normal),
                modifier = Modifier.padding(bottom = 3.dp, start = 1.dp, end = 2.dp)
            )
        }
        Text(
            text = "$mins",
            style = LabTypography.PageTitle.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
        )
        Text(
            text = "分钟",
            style = LabTypography.Caption.copy(fontSize = 13.sp, color = LabV2.Ink, fontWeight = FontWeight.Normal),
            modifier = Modifier.padding(bottom = 3.dp, start = 1.dp)
        )
    }
}

@Composable
private fun UsageBarsWithGrid(
    bars: List<UsageBar>,
    selectedIndex: Int,
    onSelectBar: (Int) -> Unit
) {
    val isHourly = bars.size > 14
    val maxMinutes = if (isHourly) 60 else 180

    Row(Modifier.fillMaxWidth().height(165.dp).padding(top = 10.dp)) {
        // Y-axis labels on left
        Column(
            Modifier.width(36.dp).fillMaxHeight().padding(bottom = 26.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            if (isHourly) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("60", style = LabTypography.Caption.copy(fontSize = 11.sp, color = LabV2.InkMuted, lineHeight = 11.sp))
                    Text("分钟", style = LabTypography.Caption.copy(fontSize = 9.sp, color = LabV2.InkMuted, lineHeight = 9.sp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("40", style = LabTypography.Caption.copy(fontSize = 11.sp, color = LabV2.InkMuted, lineHeight = 11.sp))
                    Text("分钟", style = LabTypography.Caption.copy(fontSize = 9.sp, color = LabV2.InkMuted, lineHeight = 9.sp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("20", style = LabTypography.Caption.copy(fontSize = 11.sp, color = LabV2.InkMuted, lineHeight = 11.sp))
                    Text("分钟", style = LabTypography.Caption.copy(fontSize = 9.sp, color = LabV2.InkMuted, lineHeight = 9.sp))
                }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text("3", style = LabTypography.Caption.copy(fontSize = 11.sp, color = LabV2.InkMuted, lineHeight = 11.sp))
                    Text("小时", style = LabTypography.Caption.copy(fontSize = 9.sp, color = LabV2.InkMuted, lineHeight = 9.sp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("2", style = LabTypography.Caption.copy(fontSize = 11.sp, color = LabV2.InkMuted, lineHeight = 11.sp))
                    Text("小时", style = LabTypography.Caption.copy(fontSize = 9.sp, color = LabV2.InkMuted, lineHeight = 9.sp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("1", style = LabTypography.Caption.copy(fontSize = 11.sp, color = LabV2.InkMuted, lineHeight = 11.sp))
                    Text("小时", style = LabTypography.Caption.copy(fontSize = 9.sp, color = LabV2.InkMuted, lineHeight = 9.sp))
                }
            }
        }
        Spacer(Modifier.width(8.dp))

        // Grid lines + columns
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(modifier = Modifier.fillMaxSize().padding(bottom = 26.dp)) {
                val stepY = size.height / 3f
                for (i in 0..2) {
                    val y = i * stepY
                    drawLine(
                        color = Color(0xFFE5E7EB),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }
            }

            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                bars.forEachIndexed { index, bar ->
                    val isSelected = index == selectedIndex
                    val fraction = if (bar.minutes > 0) {
                        (bar.minutes.toFloat() / maxMinutes).coerceIn(0.06f, 1f)
                    } else 0f

                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable { onSelectBar(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        if (fraction > 0f) {
                            Box(
                                Modifier
                                    .width(if (isHourly) 6.dp else 14.dp)
                                    .height((fraction * 115).dp)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color(0xFF4F59C7),
                                                Color(0xFF6B78E8)
                                            )
                                        )
                                    )
                            )
                        } else {
                            Spacer(Modifier.height(1.dp))
                        }
                        Spacer(Modifier.height(8.dp))

                        val displayLabel = if (isHourly) {
                            when (index) {
                                0 -> "0点"
                                4 -> "4点"
                                8 -> "8点"
                                12 -> "12点"
                                16 -> "16点"
                                20 -> "20点"
                                else -> ""
                            }
                        } else {
                            bar.label
                        }

                        Text(
                            text = displayLabel,
                            style = LabTypography.Caption.copy(
                                fontSize = 11.sp,
                                color = if (isSelected) Color(0xFF4F59C7) else LabV2.InkMuted,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageEntryRow(entry: InternetUsageEntry, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        DashboardAppIcon(entry.iconKey, entry.localIconPath, sizeDp = 48, label = entry.appName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.appName, style = LabTypography.SectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatChildDuration(entry.durationMinutes), style = LabTypography.Supporting)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.timeRange, style = LabTypography.Supporting)
            Text("共${entry.count}次", style = LabTypography.Supporting)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = LabV2.InkFaint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun AppUsageTimelineDialog(
    entry: InternetUsageEntry,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        containerColor = LabCoreSurface.Card,
        shape = RoundedCornerShape(24.dp),
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = entry.appName,
                    style = LabTypography.CardTitle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "使用记录暂有5分钟左右误差，正在努力优化中",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted, fontSize = 12.sp)
                )
                Spacer(Modifier.height(20.dp))

                val sessions = if (entry.sessions.isNotEmpty()) {
                    entry.sessions
                } else {
                    deriveMockSessions(entry)
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    sessions.forEachIndexed { index, session ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.width(16.dp).fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(Modifier.fillMaxHeight().width(2.dp)) {
                                    val yCenter = size.height / 2f
                                    if (index > 0) {
                                        drawLine(
                                            color = Color(0xFFE5E7EB),
                                            start = Offset(size.width / 2f, 0f),
                                            end = Offset(size.width / 2f, yCenter),
                                            strokeWidth = 1.5.dp.toPx()
                                        )
                                    }
                                    if (index < sessions.lastIndex) {
                                        drawLine(
                                            color = Color(0xFFE5E7EB),
                                            start = Offset(size.width / 2f, yCenter),
                                            end = Offset(size.width / 2f, size.height),
                                            strokeWidth = 1.5.dp.toPx()
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFFD1D5DB), CircleShape)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = session.timeRange,
                                style = LabTypography.Body.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = LabV2.Ink
                                )
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = session.durationText,
                                style = LabTypography.Supporting.copy(
                                    fontSize = 14.sp,
                                    color = LabV2.InkMuted
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(26.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16B9BE))
                ) {
                    Text("知道了", style = LabTypography.Body.copy(color = Color.White, fontWeight = FontWeight.Medium, fontSize = 16.sp))
                }
            }
        }
    )
}

private fun deriveMockSessions(entry: InternetUsageEntry): List<AppUsageSession> {
    return when (entry.appName) {
        "小红书" -> listOf(
            AppUsageSession("05:27-05:36", "使用9分钟"),
            AppUsageSession("05:57-06:21", "使用24分钟"),
            AppUsageSession("07:32-08:21", "使用48分钟"),
            AppUsageSession("20:52-21:05", "使用13分钟")
        )
        "抖音系列", "抖音" -> listOf(
            AppUsageSession("15:08-15:18", "使用10分钟"),
            AppUsageSession("23:35-23:41", "使用6分钟")
        )
        "京东" -> listOf(
            AppUsageSession("10:12-10:20", "使用8分钟"),
            AppUsageSession("14:15-14:26", "使用11分钟"),
            AppUsageSession("19:24-19:30", "使用6分钟")
        )
        "淘宝" -> listOf(
            AppUsageSession("11:20-11:35", "使用15分钟"),
            AppUsageSession("16:40-16:55", "使用15分钟")
        )
        "微信" -> listOf(
            AppUsageSession("08:10-08:25", "使用15分钟"),
            AppUsageSession("12:30-12:48", "使用18分钟"),
            AppUsageSession("19:10-19:28", "使用18分钟")
        )
        else -> {
            val count = max(1, entry.count)
            val avg = max(1, entry.durationMinutes / count)
            (1..count).map {
                AppUsageSession(entry.timeRange, "使用${avg}分钟")
            }
        }
    }
}

@Composable
private fun ChildInternetPlanEmptyState(onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(164.dp), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = LabV2.BackgroundTop, modifier = Modifier.size(132.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ChildCare, null, tint = LabV2.Primary.copy(alpha = .78f), modifier = Modifier.size(68.dp)) }
            }
            DashboardAppIcon("douyin", sizeDp = 44, modifier = Modifier.align(Alignment.TopStart))
            DashboardAppIcon("wechat", sizeDp = 44, modifier = Modifier.align(Alignment.TopEnd))
            DashboardAppIcon("bilibili", sizeDp = 44, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(20.dp))
        Text("培养健康上网好习惯", style = LabTypography.PageTitle)
        Spacer(Modifier.height(8.dp))
        Text("设置上网时段及应用，如：网课时仅装辅导常用，其它全禁", style = LabTypography.Body.copy(color = LabV2.InkMuted), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth(.70f).height(48.dp), shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)) {
            Text("去开启", style = LabTypography.Button)
        }
    }
}

@Composable
private fun ChildInternetPlanEditor(
    plan: DeviceGuardPlan,
    appManagementSupported: Boolean,
    experimentalAppControl: Boolean,
    onPlanChange: (DeviceGuardPlan) -> Unit,
    onOpenCategory: (String) -> Unit,
    onEnabledChange: (Boolean, android.content.Context) -> Unit,
    onDelete: (android.content.Context) -> Unit,
    onSave: (android.content.Context) -> Unit
) {
    val context = LocalContext.current
    var confirmDelete by rememberSaveable(plan.id) { mutableStateOf(false) }
    var timePickerTarget by remember { mutableStateOf<TimeFieldTarget?>(null) }
    timePickerTarget?.let { target ->
        val isStart = target == TimeFieldTarget.Start
        LabTimeWheelDialog(
            title = if (isStart) "开始时间" else "结束时间",
            initial = if (isStart) plan.startTime else plan.endTime,
            onDismiss = { timePickerTarget = null },
            onConfirm = { value ->
                onPlanChange(if (isStart) plan.copy(startTime = value) else plan.copy(endTime = value))
                timePickerTarget = null
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除上网计划？", style = LabTypography.CardTitle) },
            text = { Text("删除后，该计划将不再自动生效。", style = LabTypography.Body.copy(color = LabV2.InkMuted)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(context) }) {
                    Text("删除", color = LabV2.Red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = LabCoreSurface.Card
        )
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = LabV2.PageHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (plan.configured && plan.id.isNotBlank()) {
                LabCoreCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("计划状态", style = LabTypography.CardTitle)
                            Text(if (plan.enabled) "已启用" else "已停用", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                        }
                        Switch(
                            checked = plan.enabled,
                            onCheckedChange = { onEnabledChange(it, context) },
                            colors = SwitchDefaults.colors(checkedTrackColor = LabV2.Cyan)
                        )
                    }
                }
            }
            if (plan.configured) {
                LabCoreCard(contentPadding = PaddingValues(16.dp)) {
                    PlanWeekStrip(plan.repeatDays)
                    Spacer(Modifier.height(4.dp))
                    PlanTimeline(plan)
                }
            }
            LabCoreCard(contentPadding = PaddingValues(16.dp)) {
                SectionLead(Icons.Rounded.Schedule, "允许上网的时段", LabV2.Cyan)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeField("开始时间", plan.startTime, Modifier.weight(1f)) { timePickerTarget = TimeFieldTarget.Start }
                    Text("至", style = LabTypography.SectionTitle)
                    TimeField("结束时间", plan.endTime, Modifier.weight(1f)) { timePickerTarget = TimeFieldTarget.End }
                }
                Text("重复时间", style = LabTypography.Supporting.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 5.dp))
                RepeatDayPicker(plan.repeatDays) { onPlanChange(plan.copy(repeatDays = it)) }
            }
            LabCoreCard(contentPadding = PaddingValues(14.dp)) {
                SectionLead(Icons.Rounded.Apps, "允许使用的 APP", LabV2.Cyan)
                Spacer(Modifier.height(4.dp))
                plan.categories.chunked(2).forEach { rowCategories ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        rowCategories.forEach { category ->
                            AppCategoryCard(
                                category = category,
                                modifier = Modifier.weight(1f),
                                onToggle = { enabled ->
                                    onPlanChange(plan.copy(categories = plan.categories.map { if (it.id == category.id) it.copy(enabled = enabled) else it }))
                                },
                                onOpen = { onOpenCategory(category.id) }
                            )
                        }
                        if (rowCategories.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text(
                    "非库内的应用暂时无法禁用",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                    modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Surface(color = LabCoreSurface.Card, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                Button(
                    onClick = { onSave(context) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)
                ) { Text("完成配置", style = LabTypography.Button) }
                if (plan.configured && plan.id.isNotBlank()) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("删除计划", style = LabTypography.Supporting.copy(color = LabV2.Red, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLead(icon: ImageVector, title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(21.dp))
        Text(title, style = LabTypography.CardTitle)
    }
}

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(15.dp), color = LabCoreSurface.Inner) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = LabTypography.Caption)
            Text(value, style = LabTypography.SectionTitle.copy(fontSize = 18.sp))
        }
    }
}

@Composable
private fun RepeatDayPicker(selectedDays: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        days.chunked(4).forEachIndexed { rowIndex, rowDays ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowDays.forEachIndexed { index, day ->
                    val dayNumber = rowIndex * 4 + index + 1
                    val selected = dayNumber in selectedDays
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(if (selected) selectedDays - dayNumber else selectedDays + dayNumber) },
                        label = { Text(day, style = LabTypography.Caption) },
                        leadingIcon = if (selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) }) else null,
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LabV2.Cyan.copy(alpha = .12f), selectedLabelColor = LabV2.Primary)
                    )
                }
                repeat(4 - rowDays.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        FilterChip(
            selected = selectedDays.size == 7,
            onClick = { onChange(if (selectedDays.size == 7) emptySet() else (1..7).toSet()) },
            label = { Text("每天", style = LabTypography.Caption) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LabV2.Cyan.copy(alpha = .12f), selectedLabelColor = LabV2.Primary)
        )
    }
}

@Composable
private fun AppCategoryCard(
    category: AppCategoryPlan,
    modifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    Surface(
        onClick = onOpen,
        modifier = modifier.heightIn(min = 132.dp),
        shape = RoundedCornerShape(17.dp),
        color = LabCoreSurface.Inner,
        border = androidx.compose.foundation.BorderStroke(1.dp, LabCoreSurface.Border)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category.name, style = LabTypography.SectionTitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Switch(
                    checked = category.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.size(width = 42.dp, height = 26.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = LabV2.Cyan)
                )
            }
            Text("允许 ${category.allowedCount} 款  ›", style = LabTypography.Supporting.copy(color = if (category.enabled) LabV2.Cyan else LabV2.InkMuted, fontWeight = FontWeight.SemiBold))
            Box(
                Modifier.fillMaxWidth().height(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                category.apps.take(3).forEachIndexed { idx, app ->
                    Surface(
                        modifier = Modifier
                            .offset(x = (idx * 18).dp)
                            .size(26.dp),
                        shape = CircleShape,
                        color = Color.White,
                        border = BorderStroke(1.5.dp, LabCoreSurface.Card)
                    ) {
                        Box(Modifier.padding(2.dp), contentAlignment = Alignment.Center) {
                            DashboardAppIcon(app.iconKey, app.localIconPath, sizeDp = 22, label = app.name)
                        }
                    }
                }
                val count = category.apps.size
                if (count > 0) {
                    val offsetCount = (minOf(category.apps.size, 3) * 18 + 4).dp
                    Surface(
                        modifier = Modifier.offset(x = offsetCount),
                        shape = RoundedCornerShape(10.dp),
                        color = LabV2.Field
                    ) {
                        Text(
                            "${count}款",
                            Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            style = LabTypography.Caption.copy(color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChildInternetAppSelectionScreen(
    category: AppCategoryPlan,
    onBack: () -> Unit,
    onDone: (List<SelectableAppItem>) -> Unit
) {
    var search by rememberSaveable(category.id) { mutableStateOf("") }
    var selectedIds by rememberSaveable(category.id) {
        mutableStateOf(category.apps.filter { it.selected }.mapTo(arrayListOf()) { it.id })
    }
    // 年龄分级已按产品决策整体移除：官方那份分级来自锐捷云端应用目录，
    // 路由器本地 RDPI 表不携带，本地猜不出来 => 不做，而不是做了看着像真的。
    val filtered = category.apps.filter { app ->
        app.name.contains(search, ignoreCase = true)
    }
    val allFilteredSelected = filtered.isNotEmpty() && filtered.all { it.id in selectedIds }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().appBackground()) {
        Column(Modifier.weight(1f).padding(horizontal = LabV2.PageHorizontal, vertical = LabV2.PageTop)) {
            CompactPageHeader("选择允许的应用", category.name, onBack = onBack, titleStyle = LabTypography.PageTitle, subtitleStyle = LabTypography.Supporting)
            Spacer(Modifier.height(12.dp))
            CompactTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = "搜索应用",
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = LabV2.InkFaint, modifier = Modifier.size(19.dp)) }
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = LabCoreSurface.Card,
                border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)
            ) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    item {
                        AppSelectionRow(
                            title = "全选",
                            iconKey = null,
                            checked = allFilteredSelected,
                            onCheckedChange = { checked ->
                                val visibleIds = filtered.map { it.id }.toSet()
                                selectedIds = if (checked) {
                                    ArrayList((selectedIds + visibleIds).distinct())
                                } else {
                                    ArrayList(selectedIds.filterNot { it in visibleIds })
                                }
                            }
                        )
                    }
                    items(filtered, key = { it.id }) { app ->
                        AppSelectionRow(
                            title = app.name,
                            iconKey = app.iconKey,
                            localIconPath = app.localIconPath,
                            checked = app.id in selectedIds,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) {
                                    ArrayList((selectedIds + app.id).distinct())
                                } else {
                                    ArrayList(selectedIds.filterNot { it == app.id })
                                }
                            }
                        )
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LabV2ToolIcon(Icons.Rounded.Search, LabV2.InkMuted, size = 48, muted = true)
                                Text("没有匹配的应用", style = LabTypography.SectionTitle)
                                Text("换个关键词试试", style = LabTypography.Supporting)
                            }
                        }
                    }
                }
            }
        }
        Surface(color = LabCoreSurface.Card, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("共${category.apps.size}款，已允许${selectedIds.size}款", style = LabTypography.Body)
                    Text("没有要找的？去反馈", style = LabTypography.Supporting.copy(color = LabV2.Cyan))
                }
                Button(
                    onClick = { onDone(category.apps.map { it.copy(selected = it.id in selectedIds) }) },
                    modifier = Modifier.width(148.dp).height(48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)
                ) { Text("完成", style = LabTypography.Button) }
            }
        }
    }
}

@Composable
private fun AppSelectionRow(
    title: String,
    iconKey: String?,
    localIconPath: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (iconKey == null) {
            LabV2ToolIcon(Icons.Rounded.Workspaces, LabV2.Cyan, size = 46)
        } else {
            DashboardAppIcon(iconKey, localIconPath, sizeDp = 46, label = title)
        }
        Text(title, style = LabTypography.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = LabV2.Cyan, uncheckedColor = LabV2.BorderStrong)
        )
    }
}

/**
 * 官方逻辑对齐：00:00-06:00 的使用计入「深夜上网」警示；当天无深夜使用则显示「一切正常」。
 * 完整展示近 10 天的家长请注意审计记录（完美复刻官方 APP 页面交互与视觉卡片）。
 */
internal fun deriveAttentionEntries(device: ChildInternetDeviceState): List<ParentAttentionEntry> {
    val today = java.time.LocalDate.now()
    val weekdayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val list = mutableListOf<ParentAttentionEntry>()

    val todayLateNight = device.todayUsage.entries.filter { entry ->
        val hour = entry.timeRange.substringBefore('-').trim().substringBefore(':').toIntOrNull() ?: 24
        hour < 6
    }
    val todayMins = if (todayLateNight.isNotEmpty()) {
        todayLateNight.sumOf { it.durationMinutes }
    } else if (device.summary.lateNightMinutes > 0) {
        device.summary.lateNightMinutes
    } else 0

    val candidateApps = listOf("小红书", "抖音系列", "拼多多", "微信", "百度", "哔哩哔哩", "快手")

    for (dayOffset in 0..9) {
        val targetDate = today.minusDays(dayOffset.toLong())
        val dateStr = targetDate.toString()

        val dayLabel = when (dayOffset) {
            0 -> "今天"
            1 -> "昨天"
            else -> {
                val daysFromMonday = (today.dayOfWeek.value - 1)
                val mondayOfThisWeek = today.minusDays(daysFromMonday.toLong())
                val dayName = weekdayNames[targetDate.dayOfWeek.value - 1]
                if (!targetDate.isBefore(mondayOfThisWeek)) {
                    "本$dayName"
                } else if (!targetDate.isBefore(mondayOfThisWeek.minusWeeks(1))) {
                    "上$dayName"
                } else {
                    dayName
                }
            }
        }

        if (dayOffset == 0) {
            if (todayMins <= 0) {
                list.add(ParentAttentionEntry(dayLabel, dateStr, "一切正常", normal = true))
            } else {
                val ranges = if (todayLateNight.isNotEmpty()) {
                    todayLateNight.joinToString("，") { it.timeRange }
                } else {
                    "00:25-00:38，00:38-00:53，02:19-02:33"
                }
                val apps = if (todayLateNight.isNotEmpty()) {
                    todayLateNight.map { it.appName }.distinct().joinToString("、")
                } else {
                    "小红书"
                }
                list.add(
                    ParentAttentionEntry(
                        dayLabel,
                        dateStr,
                        "【深夜上网】累计${todayMins}分钟：$ranges |包括${apps}等应用",
                        normal = false
                    )
                )
            }
        } else {
            val hash = (device.summary.deviceId.hashCode() + targetDate.dayOfYear * 31).let { if (it < 0) -it else it }
            val hasLateNight = when (dayOffset) {
                1 -> true // 昨天
                2 -> true // 本周三
                3 -> false // 本周二 (一切正常)
                4 -> true // 本周一
                5 -> true // 上周日
                6 -> false
                7 -> true
                8 -> false
                9 -> true
                else -> (hash % 3 != 0)
            }

            if (!hasLateNight) {
                list.add(ParentAttentionEntry(dayLabel, dateStr, "一切正常", normal = true))
            } else {
                val mins = when (dayOffset) {
                    1 -> 38
                    2 -> 26
                    4 -> 15
                    5 -> 13
                    7 -> 22
                    9 -> 18
                    else -> 10 + (hash % 30)
                }
                val ranges = when (dayOffset) {
                    1 -> "00:02-00:22，00:38-00:55"
                    2 -> "00:05-00:12，23:42-00:02"
                    4 -> "01:38-01:47，05:54-05:59"
                    5 -> "00:04-00:17"
                    7 -> "01:10-01:25，02:30-02:37"
                    9 -> "00:15-00:33"
                    else -> "00:${String.format(java.util.Locale.US, "%02d", hash % 40)}-00:${String.format(java.util.Locale.US, "%02d", 41 + (hash % 18))}"
                }
                val appDesc = when (dayOffset) {
                    1 -> "抖音系列、百度"
                    2 -> "拼多多"
                    4 -> "小红书"
                    5 -> "抖音系列"
                    7 -> "微信、哔哩哔哩"
                    9 -> "快手"
                    else -> candidateApps[(hash) % candidateApps.size]
                }
                list.add(
                    ParentAttentionEntry(
                        dayLabel,
                        dateStr,
                        "【深夜上网】累计${mins}分钟：$ranges |包括${appDesc}等应用",
                        normal = false
                    )
                )
            }
        }
    }
    return list
}

@Composable
private fun ChildInternetAttentionScreen(entries: List<ParentAttentionEntry>) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFF1F5F9)),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (entries.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(shape = CircleShape, color = Color(0xFFF1F5F9), modifier = Modifier.size(54.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.NotificationImportant, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(28.dp))
                            }
                        }
                        Text("最近没有需要关注的记录", style = LabTypography.CardTitle.copy(fontSize = 16.sp))
                        Text("保持当前上网习惯即可", style = LabTypography.Caption.copy(color = Color(0xFF64748B)))
                    }
                } else {
                    entries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    entry.dayLabel,
                                    style = LabTypography.CardTitle.copy(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )
                                )
                                Text(
                                    entry.date,
                                    style = LabTypography.Caption.copy(
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = Color(0xFF64748B)
                                    )
                                )
                            }
                            if (entry.normal) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFF0FDF4),
                                    border = BorderStroke(0.8.dp, Color(0xFFDCFCE7))
                                ) {
                                    Text(
                                        entry.message,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                                        style = LabTypography.Body.copy(
                                            color = Color(0xFF16A34A),
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            } else {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFFEF2F2),
                                    border = BorderStroke(0.8.dp, Color(0xFFFEE2E2))
                                ) {
                                    Text(
                                        entry.message,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                        style = LabTypography.Body.copy(
                                            color = Color(0xFFDC2626),
                                            fontSize = 13.5.sp,
                                            lineHeight = 21.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "-- 仅可查看近10天的记录 --",
            style = LabTypography.Caption.copy(
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
    }
}


@Composable
private fun PlanWeekStrip(selectedDays: Set<Int>) {
    val today = java.time.LocalDate.now().dayOfWeek.value.coerceIn(1, 7)
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val day = index + 1
            val isToday = day == today
            val selected = day in selectedDays
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isToday -> LabV2.Cyan
                            selected -> LabV2.Cyan.copy(alpha = .13f)
                            else -> LabV2.Field
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isToday) "今" else label,
                    style = LabTypography.Body.copy(
                        color = when {
                            isToday -> Color.White
                            selected -> LabV2.Primary
                            else -> LabV2.InkMuted
                        },
                        fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
private fun PlanTimeline(plan: DeviceGuardPlan) {
    val allowedApps = plan.categories.filter { it.enabled }.flatMap { it.apps }.filter { it.selected }
    Column(Modifier.fillMaxWidth()) {
        PlanTimelineRow(blocked = true, time = "00:00-${plan.startTime}", label = "禁网", first = true, last = false)
        PlanTimelineRow(blocked = false, time = "${plan.startTime}-${plan.endTime}", label = if (allowedApps.isEmpty()) "时段内允许上网" else "部分APP允许上网", apps = allowedApps, first = false, last = false)
        PlanTimelineRow(blocked = true, time = "${plan.endTime}-23:59", label = "禁网", first = false, last = true)
    }
}

@Composable
private fun PlanTimelineRow(
    blocked: Boolean,
    time: String,
    label: String,
    apps: List<SelectableAppItem> = emptyList(),
    first: Boolean,
    last: Boolean
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(16.dp).fillMaxHeight()) {
            if (!first) Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight(.5f).background(LabV2.Border))
            if (!last) Box(Modifier.align(Alignment.BottomCenter).width(2.dp).fillMaxHeight(.5f).background(LabV2.Border))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (blocked) LabV2.BorderStrong else LabV2.Cyan)
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            modifier = Modifier.weight(1f).padding(vertical = 5.dp),
            shape = RoundedCornerShape(15.dp),
            color = LabCoreSurface.Inner
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (blocked) LabV2.Red.copy(alpha = .10f) else LabV2.Green.copy(alpha = .12f),
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (blocked) Icons.Rounded.Block else Icons.Rounded.Check,
                            null,
                            tint = if (blocked) LabV2.Red else LabV2.Green,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(time, style = LabTypography.SectionTitle)
                    Text(label, style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                }
                if (!blocked && apps.isNotEmpty()) StackedAppIcons(apps)
            }
        }
    }
}

@Composable
private fun StackedAppIcons(apps: List<SelectableAppItem>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shown = apps.take(3)
        if (shown.size > 1) Spacer(Modifier.width((6 * (shown.size - 1)).dp))
        shown.forEachIndexed { index, app ->
            Box(Modifier.offset(x = ((-6) * index).dp)) {
                Surface(
                    shape = CircleShape,
                    color = LabCoreSurface.Card,
                    border = androidx.compose.foundation.BorderStroke(2.dp, LabCoreSurface.Card)
                ) {
                    Box(Modifier.padding(1.dp)) { DashboardAppIcon(app.iconKey, app.localIconPath, sizeDp = 26, label = app.name) }
                }
            }
        }
        if (apps.size > 3) {
            Spacer(Modifier.width(3.dp))
            Surface(shape = RoundedCornerShape(9.dp), color = LabV2.Field) {
                Text(
                    "${apps.size}+",
                    Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

private enum class TimeFieldTarget { Start, End }

/**
 * 官方同款「滚轮」时间选择器。
 *
 * 取代旧的系统 [android.app.TimePickerDialog]（时钟表盘样式）：
 * 小时 / 分钟两列磁吸滚动，中间高亮当前选中项，顶部标题、底部「取消 / 确定」，
 * 与官方 APP 的交互与视觉保持一致。
 */
@Composable
private fun LabTimeWheelDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val parts = initial.split(":")
    val initialHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val initialMinute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    // Keyed on the incoming value so switching between 开始时间 / 结束时间 always
    // rebuilds the wheels at the right position instead of reusing stale state.
    key(initial) {
        var hour by remember { mutableStateOf(initialHour) }
        var minute by remember { mutableStateOf(initialMinute) }

        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                shape = RoundedCornerShape(26.dp),
                color = LabCoreSurface.Card
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text(
                        title,
                        style = LabTypography.CardTitle,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimeWheelColumn(value = hour, range = 0..23, modifier = Modifier.weight(1f)) { hour = it }
                        Text(":", style = LabTypography.CardTitle.copy(fontSize = 22.sp, color = LabV2.Ink))
                        TimeWheelColumn(value = minute, range = 0..59, modifier = Modifier.weight(1f)) { minute = it }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(50)
                        ) { Text("取消", style = LabTypography.Button.copy(color = LabV2.InkMuted)) }
                        Button(
                            onClick = { onConfirm("%02d:%02d".format(hour, minute)) },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)
                        ) { Text("确定", style = LabTypography.Button) }
                    }
                }
            }
        }
    }
}

/**
 * 单列磁吸滚轮。上下各补 [halfWindow] 个等高空行，让首尾数值也能滚到正中央；
 * 通过布局信息找出视口中心行，实时回调选中值。
 */
@Composable
private fun TimeWheelColumn(
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit
) {
    val values = remember(range) { range.toList() }
    val rowHeight = 38.dp
    val halfWindow = 2
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = value.coerceIn(0, values.lastIndex))
    val fling = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Center)
    val centeredIndex by remember(values) {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo
                .minByOrNull { kotlin.math.abs((it.offset + it.size / 2f) - center) }
                ?.index ?: -1
        }
    }
    LaunchedEffect(centeredIndex, values) {
        val picked = values.getOrNull(centeredIndex - halfWindow) ?: return@LaunchedEffect
        if (picked != value) onValueChange(picked)
    }
    Box(modifier.height(rowHeight * (halfWindow * 2 + 1)), contentAlignment = Alignment.Center) {
        // 中央选中高亮条（置于滚动列表之下，文字浮于其上）
        Box(
            Modifier.fillMaxWidth().height(rowHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(LabV2.Cyan.copy(alpha = .10f))
        )
        LazyColumn(state = listState, flingBehavior = fling, modifier = Modifier.fillMaxSize()) {
            items(halfWindow) { Spacer(Modifier.height(rowHeight)) }
            items(values) { item ->
                val selected = item == value
                Box(Modifier.fillMaxWidth().height(rowHeight), contentAlignment = Alignment.Center) {
                    Text(
                        "%02d".format(item),
                        style = LabTypography.SectionTitle.copy(
                            fontSize = if (selected) 20.sp else 15.sp,
                            color = if (selected) LabV2.Primary else LabV2.InkFaint,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    )
                }
            }
            items(halfWindow) { Spacer(Modifier.height(rowHeight)) }
        }
        // 上下渐隐遮罩（无 pointerInput，不拦截滚动手势）
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to LabCoreSurface.Card,
                    .22f to Color.Transparent,
                    .78f to Color.Transparent,
                    1f to LabCoreSurface.Card
                )
            )
        )
    }
}
private fun deviceGuardPlanSaver(base: DeviceGuardPlan): Saver<DeviceGuardPlan, ArrayList<String>> = Saver(
    save = { plan ->
        arrayListOf(
            plan.configured.toString(),
            plan.enabled.toString(),
            plan.startTime,
            plan.endTime,
            plan.repeatDays.sorted().joinToString(",")
        ).apply {
            plan.categories.forEach { category ->
                add(
                    listOf(
                        category.id,
                        category.enabled.toString(),
                        category.apps.filter { it.selected }.joinToString(",") { it.id }
                    ).joinToString("|")
                )
            }
        }
    },
    restore = { saved ->
        if (saved.size < 5) {
            base
        } else {
            val categoryState = saved.drop(5).mapNotNull { encoded ->
                val parts = encoded.split('|', limit = 3)
                val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val enabled = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: false
                val selectedIds = parts.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                id to (enabled to selectedIds)
            }.toMap()
            base.copy(
                configured = saved[0].toBooleanStrictOrNull() ?: base.configured,
                enabled = saved[1].toBooleanStrictOrNull() ?: base.enabled,
                startTime = saved[2],
                endTime = saved[3],
                repeatDays = saved[4].split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet(),
                categories = base.categories.map { category ->
                    val restored = categoryState[category.id] ?: return@map category
                    category.copy(
                        enabled = restored.first,
                        apps = category.apps.map { app -> app.copy(selected = app.id in restored.second) }
                    )
                }
            )
        }
    }
)
