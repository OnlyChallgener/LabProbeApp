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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay
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
import androidx.compose.ui.text.TextStyle
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
                fallback?.matchesChildGuardDevice(d.mac) == true
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
        if (resolvedId.isNotBlank()) {
            repository.ensureDevice(resolvedId, resolvedName, iconKey, accentArgb)
            repository.loadUsageReport(resolvedId) {}
        }
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

    var hudState by remember { mutableStateOf<OperationHudState>(OperationHudState.Hidden) }
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
                    hudState = OperationHudState.Loading("删除中...")
                    repository.removeGuardDevice(deviceState.summary.deviceId) { result ->
                        result.onSuccess {
                            hudState = OperationHudState.Success("已解除守护")
                            onBack()
                        }.onFailure {
                            hudState = OperationHudState.Error(it.message ?: "解除失败")
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

    Box(Modifier.fillMaxSize()) {
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
                    ChildInternetTab.REPORT -> ChildInternetReportScreen(device = deviceState, appDevice = appDevice) { repository.loadUsageReport(resolvedId) {} }
                    ChildInternetTab.PLAN -> {
                        if (!deviceState.plan.configured && !showPlanEditor) {
                            ChildInternetPlanEmptyState(onOpen = { showPlanEditor = true })
                        } else if (deviceState.plan.configured && !showPlanEditor) {
                            ChildInternetConfiguredPlanScreen(
                                plan = deviceState.plan,
                                plans = deviceState.plans,
                                onToggleEnabled = { enabled ->
                                    hudState = OperationHudState.Loading("配置中...")
                                    repository.setPlanEnabled(resolvedId, deviceState.plan.id, enabled) { result ->
                                        result.onSuccess {
                                            hudState = OperationHudState.Success(if (enabled) "计划已启用" else "计划已停用")
                                        }.onFailure {
                                            hudState = OperationHudState.Error(it.message ?: "配置失败")
                                        }
                                    }
                                },
                                onEditPlan = { showPlanEditor = true },
                                onAddSlot = { showPlanEditor = true }
                            )
                        } else {
                            ChildInternetPlanEditor(
                                plan = draftPlan,
                                appManagementSupported = deviceState.summary.appManagementSupported,
                                experimentalAppControl = deviceState.summary.experimentalAppControl,
                                onPlanChange = { draftPlan = it },
                                onOpenCategory = { selectedCategoryId = it },
                                onEnabledChange = { enabled, context ->
                                    hudState = OperationHudState.Loading("配置中...")
                                    if (draftPlan.id.isBlank()) {
                                        draftPlan = draftPlan.copy(enabled = enabled)
                                        hudState = OperationHudState.Success(if (enabled) "计划已启用" else "计划已停用")
                                    } else {
                                        repository.setPlanEnabled(resolvedId, draftPlan.id, enabled) { result ->
                                            result.onSuccess {
                                                draftPlan = draftPlan.copy(enabled = enabled)
                                                hudState = OperationHudState.Success("配置成功")
                                            }.onFailure { hudState = OperationHudState.Error(it.message ?: "配置失败") }
                                        }
                                    }
                                },
                                onDelete = { context ->
                                    hudState = OperationHudState.Loading("删除中...")
                                    val planIdToDelete = draftPlan.id.ifBlank { deviceState.plan.id }
                                    repository.deletePlan(resolvedId, planIdToDelete) { result ->
                                        result.onSuccess {
                                            draftPlan = DeviceGuardPlan(categories = childInternetCatalogCategories(), configured = false)
                                            showPlanEditor = false
                                            hudState = OperationHudState.Success("删除成功")
                                        }.onFailure { hudState = OperationHudState.Error(it.message ?: "删除失败") }
                                    }
                                },
                                onSave = { context ->
                                    hudState = OperationHudState.Loading("配置中...")
                                    repository.savePlan(resolvedId, draftPlan) { result ->
                                        result.onSuccess {
                                            hudState = OperationHudState.Success("配置成功")
                                            showPlanEditor = false
                                        }.onFailure { hudState = OperationHudState.Error(it.message ?: "配置失败") }
                                    }
                                }
                            )
                        }
                    }
                    ChildInternetTab.ATTENTION -> ChildInternetAttentionScreen(deviceState.attentionEntries.ifEmpty { deriveAttentionEntries(deviceState) })
                }
            }
        }

        OperationHudDialog(state = hudState, onDismiss = { hudState = OperationHudState.Hidden })
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChildInternetTab.entries.forEach { tab ->
            val active = tab == selected
            val activeColor = when (tab) {
                ChildInternetTab.REPORT -> Color(0xFF2563EB)
                ChildInternetTab.PLAN -> Color(0xFF4F46E5)
                ChildInternetTab.ATTENTION -> Color(0xFFD97706)
            }
            val icon = when (tab) {
                ChildInternetTab.REPORT -> Icons.Rounded.Assessment
                ChildInternetTab.PLAN -> Icons.Rounded.EditCalendar
                ChildInternetTab.ATTENTION -> Icons.Rounded.NotificationImportant
            }
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tab.title,
                    tint = if (active) activeColor else Color(0xFF94A3B8),
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = tab.title,
                    style = LabTypography.Caption.copy(
                        color = if (active) Color(0xFF0F172A) else Color(0xFF64748B),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.5.sp
                    ),
                    maxLines = 1
                )
                if (active) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(activeColor)
                    )
                } else {
                    Spacer(Modifier.height(2.5.dp))
                }
            }
        }
    }
}

@Composable
private fun ChildInternetReportScreen(
    device: ChildInternetDeviceState,
    appDevice: DeviceItem? = null,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var period by rememberSaveable(device.summary.deviceId) { mutableStateOf("今日") }
    var selectedBarIndex by rememberSaveable(device.summary.deviceId, period) { mutableStateOf(-1) }
    var selectedAppForTimeline by remember { mutableStateOf<InternetUsageEntry?>(null) }
    var showLogDialog by remember { mutableStateOf(false) }

    val usage = if (period == "今日") device.todayUsage else device.recentUsage
    val currentBars = usage.bars
    val selectedBar = if (selectedBarIndex in currentBars.indices) currentBars[selectedBarIndex] else null
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
                selectedBarIndex = -1
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 62.dp)
        )

        // 当日上网时长 Card
        LabCoreCard(contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (period == "今日") "今日上网时长" else "最近10天上网时长",
                        style = LabTypography.CardTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "流量活跃度（含内网）",
                        style = LabTypography.Supporting.copy(fontSize = 12.sp, color = LabV2.InkMuted)
                    )
                }
                FormattedDurationText(currentSelectedBarMinutes)
            }
            UsageBarsWithGrid(
                bars = currentBars,
                selectedIndex = selectedBarIndex,
                onSelectBar = { selectedBarIndex = if (selectedBarIndex == it) -1 else it }
            )
        }

        val report = device.usageReport
        LabCoreCard(contentPadding = PaddingValues(15.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("流量与网络统计", style = LabTypography.CardTitle)
                Spacer(Modifier.weight(1f))
                Text(
                    if (device.summary.isOnline) "● 在线监控中" else "○ 离线",
                    style = LabTypography.Caption.copy(
                        color = if (device.summary.isOnline) LabV2.Green else LabV2.InkMuted,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            Spacer(Modifier.height(10.dp))
            val rawTotalBytes = report?.todayTotalBytes ?: 0L
            val rawTxBytes = report?.todayTxBytes ?: 0L
            val rawRxBytes = report?.todayRxBytes ?: 0L

            val displayUpload = if (rawTxBytes > 0L) {
                formatBytesShort(rawTxBytes)
            } else if (!appDevice?.todayUpload.isNullOrBlank()) {
                appDevice!!.todayUpload
            } else {
                "0 B"
            }

            val displayDownload = if (rawRxBytes > 0L) {
                formatBytesShort(rawRxBytes)
            } else if (!appDevice?.todayDownload.isNullOrBlank()) {
                appDevice!!.todayDownload
            } else {
                "0 B"
            }

            val displayTotal = if (rawTotalBytes > 0L) {
                formatBytesShort(rawTotalBytes)
            } else if (!appDevice?.todayUpload.isNullOrBlank() || !appDevice?.todayDownload.isNullOrBlank()) {
                val up = appDevice?.todayUpload.orEmpty()
                val down = appDevice?.todayDownload.orEmpty()
                if (up.isNotBlank() && down.isNotBlank()) "$up / $down"
                else up.ifBlank { down }
            } else {
                "0 B"
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportStatMetric("今日总流量", displayTotal, LabV2.Cyan, Modifier.weight(1f))
                ReportStatMetric("今日上传", displayUpload, LabV2.Primary, Modifier.weight(1f))
                ReportStatMetric("今日下载", displayDownload, LabV2.Green, Modifier.weight(1f))
            }
            val ips = report?.boundIps?.ifEmpty { device.summary.macAddresses.toList() } ?: device.summary.macAddresses.toList()
            if (ips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "审计绑定设备: ${ips.joinToString(", ")}",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted)
                )
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

@Composable
private fun ReportStatMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.08f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = LabTypography.Caption.copy(color = LabV2.InkMuted, fontSize = 11.sp))
            Text(value, style = LabTypography.SectionTitle.copy(color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
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
                style = LabTypography.PageTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
            )
            Text(
                text = "小时",
                style = LabTypography.Caption.copy(fontSize = 12.sp, color = LabV2.Ink, fontWeight = FontWeight.Normal),
                modifier = Modifier.padding(bottom = 2.dp, start = 1.dp, end = 2.dp)
            )
        }
        Text(
            text = "$mins",
            style = LabTypography.PageTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
        )
        Text(
            text = "分钟",
            style = LabTypography.Caption.copy(fontSize = 12.sp, color = LabV2.Ink, fontWeight = FontWeight.Normal),
            modifier = Modifier.padding(bottom = 2.dp, start = 1.dp)
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
    val chartHeight = 118.dp

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            // Y-axis labels on left: stacked vertically matching official app Image 3
            Column(
                Modifier.width(28.dp).height(chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                if (isHourly) {
                    Column {
                        Text("60", style = TextStyle(fontSize = 11.sp, color = Color(0xFF94A3B8), lineHeight = 11.sp))
                        Text("分钟", style = TextStyle(fontSize = 9.sp, color = Color(0xFF94A3B8), lineHeight = 9.sp))
                    }
                    Column {
                        Text("40", style = TextStyle(fontSize = 11.sp, color = Color(0xFF94A3B8), lineHeight = 11.sp))
                        Text("分钟", style = TextStyle(fontSize = 9.sp, color = Color(0xFF94A3B8), lineHeight = 9.sp))
                    }
                    Column {
                        Text("20", style = TextStyle(fontSize = 11.sp, color = Color(0xFF94A3B8), lineHeight = 11.sp))
                        Text("分钟", style = TextStyle(fontSize = 9.sp, color = Color(0xFF94A3B8), lineHeight = 9.sp))
                    }
                } else {
                    Column {
                        Text("3", style = TextStyle(fontSize = 11.sp, color = Color(0xFF94A3B8), lineHeight = 11.sp))
                        Text("小时", style = TextStyle(fontSize = 9.sp, color = Color(0xFF94A3B8), lineHeight = 9.sp))
                    }
                    Column {
                        Text("2", style = TextStyle(fontSize = 11.sp, color = Color(0xFF94A3B8), lineHeight = 11.sp))
                        Text("小时", style = TextStyle(fontSize = 9.sp, color = Color(0xFF94A3B8), lineHeight = 9.sp))
                    }
                    Column {
                        Text("1", style = TextStyle(fontSize = 11.sp, color = Color(0xFF94A3B8), lineHeight = 11.sp))
                        Text("小时", style = TextStyle(fontSize = 9.sp, color = Color(0xFF94A3B8), lineHeight = 9.sp))
                    }
                }
            }
            Spacer(Modifier.width(4.dp))

            // Chart area: 4 horizontal grid lines and strictly bottom-anchored bars
            Box(Modifier.weight(1f).height(chartHeight)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stepY = size.height / 3f
                    for (i in 0..3) {
                        val y = (i * stepY).coerceAtMost(size.height)
                        drawLine(
                            color = Color(0xFFF1F5F9),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
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

                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onSelectBar(index) },
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (fraction > 0f) {
                                Box(
                                    Modifier
                                        .width(if (isHourly) 7.dp else 14.dp)
                                        .height(chartHeight * fraction)
                                        .clip(RoundedCornerShape(topStart = 3.5.dp, topEnd = 3.5.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0xFF5D53A3),
                                                    Color(0xFF9087DB)
                                                )
                                            )
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // X-axis label row below the chart, offset by 32dp (28dp Y-axis + 4dp space)
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(32.dp))
            Row(Modifier.weight(1f).height(18.dp)) {
                bars.forEachIndexed { index, bar ->
                    val isSelected = index == selectedIndex
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

                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (displayLabel.isNotBlank()) {
                            Text(
                                text = displayLabel,
                                style = LabTypography.Caption.copy(
                                    fontSize = 10.sp,
                                    color = if (isSelected) Color(0xFF5D53A3) else LabV2.InkMuted,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.wrapContentWidth(unbounded = true)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageEntryRow(entry: InternetUsageEntry, onClick: () -> Unit = {}) {
    val displayCount = if (entry.count > 0) entry.count else entry.sessions.size
    val effectiveMinutes = if (entry.durationMinutes > 0) entry.durationMinutes else 1

    val displayRange = if (entry.timeRange.isNotBlank()) {
        entry.timeRange
    } else if (entry.sessions.isNotEmpty()) {
        val start = entry.sessions.first().timeRange.substringBefore("-")
        val end = entry.sessions.last().timeRange.substringAfter("-")
        "$start-$end"
    } else {
        ""
    }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        DashboardAppIcon(entry.iconKey, entry.localIconPath, sizeDp = 48, label = entry.appName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.appName, style = LabTypography.SectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatChildDuration(effectiveMinutes), style = LabTypography.Supporting)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (displayRange.isNotBlank()) Text(displayRange, style = LabTypography.Supporting)
            if (displayCount > 0) Text("共${displayCount}次", style = LabTypography.Supporting)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = LabV2.InkFaint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun AppUsageTimelineDialog(
    entry: InternetUsageEntry,
    onDismiss: () -> Unit
) {
    val displayCount = if (entry.count > 0) entry.count else entry.sessions.size
    val effectiveMinutes = if (entry.durationMinutes > 0) entry.durationMinutes else 1
    val displaySessions = if (entry.sessions.isNotEmpty()) {
        entry.sessions
    } else if (entry.timeRange.isNotBlank()) {
        listOf(AppUsageSession(entry.timeRange, "使用${formatChildDuration(effectiveMinutes)}"))
    } else {
        val curHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        listOf(AppUsageSession(String.format(java.util.Locale.US, "%02d:00-%02d:59", curHour, curHour), "使用${formatChildDuration(effectiveMinutes)}"))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DashboardAppIcon(entry.iconKey, entry.localIconPath, sizeDp = 52, label = entry.appName)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = entry.appName,
                    style = LabTypography.CardTitle.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "今日累计使用 ${formatChildDuration(effectiveMinutes)} · 活跃 ${displayCount}次",
                    style = LabTypography.Supporting.copy(
                        color = Color(0xFF64748B),
                        fontSize = 13.sp
                    )
                )
                Spacer(Modifier.height(14.dp))

                if (displaySessions.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        displaySessions.forEachIndexed { index, session ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.width(18.dp).fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(Modifier.fillMaxHeight().width(2.dp)) {
                                        val yCenter = size.height / 2f
                                        if (index > 0) {
                                            drawLine(
                                                color = Color(0xFFE2E8F0),
                                                start = Offset(size.width / 2f, 0f),
                                                end = Offset(size.width / 2f, yCenter),
                                                strokeWidth = 1.5.dp.toPx()
                                            )
                                        }
                                        if (index < entry.sessions.lastIndex) {
                                            drawLine(
                                                color = Color(0xFFE2E8F0),
                                                start = Offset(size.width / 2f, yCenter),
                                                end = Offset(size.width / 2f, size.height),
                                                strokeWidth = 1.5.dp.toPx()
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .background(Color(0xFFCBD5E1), CircleShape)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = session.timeRange,
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF1E293B)
                                    )
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = session.durationText,
                                    style = TextStyle(
                                        fontSize = 13.sp,
                                        color = Color(0xFF64748B)
                                    )
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF8FAFC),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "基于路由器 RDPI 深度流特征识别统计",
                                style = LabTypography.Caption.copy(color = Color(0xFF64748B), fontSize = 12.sp)
                            )
                            Text(
                                "各活跃时段已汇总累计，无具体秒级起止时间",
                                style = LabTypography.Caption.copy(color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16B9BE))
                ) {
                    Text(
                        "知道了",
                        style = TextStyle(
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                    )
                }
            }
        }
    )
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
private fun ChildInternetConfiguredPlanScreen(
    plan: DeviceGuardPlan,
    plans: List<DeviceGuardPlan> = listOf(plan),
    onToggleEnabled: (Boolean) -> Unit,
    onEditPlan: () -> Unit,
    onAddSlot: () -> Unit
) {
    val activePlans = plans.ifEmpty { listOf(plan) }
    val todayDayOfWeek = java.time.LocalDate.now().dayOfWeek.value
    var selectedDayIndex by rememberSaveable { mutableStateOf(todayDayOfWeek) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Rules Card matching media_1789822159799.jpg (subtle grid background)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF8FAFC),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Box(Modifier.fillMaxWidth()) {
                // Subtle grid lines in background
                Canvas(modifier = Modifier.matchParentSize()) {
                    val step = 14.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawLine(Color(0xFFEDF2F7), Offset(x, 0f), Offset(x, size.height), 0.8f)
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(Color(0xFFEDF2F7), Offset(0f, y), Offset(size.width, y), 0.8f)
                        y += step
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activePlans.forEach { p ->
                        val repeatText = when {
                            p.repeatDays.size == 7 -> "每天"
                            p.repeatDays == setOf(1, 2, 3, 4, 5) -> "工作日"
                            p.repeatDays == setOf(6, 7) -> "周末"
                            p.repeatDays.isEmpty() -> "仅一次"
                            p.repeatDays.size == 1 -> "周" + when (p.repeatDays.first()) {
                                1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
                            }
                            else -> "周" + p.repeatDays.sorted().joinToString("、") {
                                when (it) {
                                    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
                                }
                            }
                        }
                        val appControlText = if (p.categories.any { it.enabled }) "管控特定应用" else "全部允许"

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onEditPlan)
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF16B9BE))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${p.startTime}-${p.endTime} | $repeatText，全部允许",
                                style = TextStyle(
                                    fontSize = 13.5.sp,
                                    color = Color(0xFF334155),
                                    fontWeight = FontWeight.Normal
                                )
                            )
                        }
                    }
                }
            }
        }

        // Main Schedule Timeline Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFF1F5F9)),
            shadowElevation = 1.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Weekday Selector Row (一 二 三 四 五 今 日)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
                    dayLabels.forEachIndexed { index, label ->
                        val dayNum = index + 1
                        val isToday = dayNum == todayDayOfWeek
                        val isSelected = dayNum == selectedDayIndex
                        val displayText = if (isToday) "今" else label

                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) Color(0xFF16B9BE) else Color(0xFFF1F5F9),
                            modifier = Modifier
                                .size(38.dp)
                                .clickable { selectedDayIndex = dayNum }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = displayText,
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else Color(0xFF334155)
                                    )
                                )
                            }
                        }
                    }
                }

                // Daily Timeline with connecting dots
                val selectedPlan = activePlans.firstOrNull { selectedDayIndex in it.repeatDays } ?: plan
                val dayHasPlan = selectedDayIndex in selectedPlan.repeatDays

                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (dayHasPlan) {
                        if (selectedPlan.startTime > "00:00") {
                            OfficialTimelineSlotRow(
                                timeRange = "00:00-${selectedPlan.startTime}",
                                isAllowed = false,
                                hasNext = true,
                                onClick = onEditPlan
                            )
                        }

                        OfficialTimelineSlotRow(
                            timeRange = "${selectedPlan.startTime}-${selectedPlan.endTime}",
                            isAllowed = true,
                            hasNext = selectedPlan.endTime < "24:00" && selectedPlan.endTime < "23:59",
                            onClick = onEditPlan
                        )

                        if (selectedPlan.endTime < "24:00" && selectedPlan.endTime < "23:59") {
                            OfficialTimelineSlotRow(
                                timeRange = "${selectedPlan.endTime}-23:59",
                                isAllowed = false,
                                hasNext = false,
                                onClick = onEditPlan
                            )
                        }
                    } else {
                        OfficialTimelineSlotRow(
                            timeRange = "00:00-24:00",
                            isAllowed = true,
                            hasNext = false,
                            onClick = onEditPlan
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // "+ 添加上网时段" solid teal rounded button matching media_1789822159799.jpg
        Surface(
            onClick = onAddSlot,
            shape = RoundedCornerShape(50),
            color = Color(0xFF16B9BE),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "添加上网时段",
                    style = TextStyle(
                        fontSize = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
private fun OfficialTimelineSlotRow(
    timeRange: String,
    isAllowed: Boolean,
    hasNext: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left timeline node & connecting dotted line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(24.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF16B9BE))
            )
            if (hasNext) {
                Box(
                    Modifier
                        .width(1.5.dp)
                        .height(38.dp)
                        .background(Color(0xFFB2EBF2))
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Slot Box
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFF8FAFC),
            border = BorderStroke(0.8.dp, Color(0xFFF1F5F9)),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isAllowed) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF16A34A),
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.Rounded.Cancel,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = timeRange,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    )
                    Text(
                        text = if (isAllowed) "允许上网" else "禁网",
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFF64748B)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun OperationHudDialog(
    state: OperationHudState,
    onDismiss: () -> Unit
) {
    if (state is OperationHudState.Hidden) return

    LaunchedEffect(state) {
        if (state is OperationHudState.Success || state is OperationHudState.Error) {
            delay(1200)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { if (state !is OperationHudState.Loading) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = state !is OperationHudState.Loading, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xE6262626), // 90% opacity dark card matching official app
            modifier = Modifier.size(136.dp, 120.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (state) {
                    is OperationHudState.Loading -> {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(state.message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    is OperationHudState.Success -> {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(state.message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    is OperationHudState.Error -> {
                        Icon(
                            Icons.Rounded.Cancel,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(state.message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    OperationHudState.Hidden -> {}
                }
            }
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
            title = { Text("删除该上网时段？", style = LabTypography.CardTitle) },
            text = { Text("删除后，该时段管控计划将不再生效并恢复无管控状态。", style = LabTypography.Body.copy(color = LabV2.InkMuted)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(context) }) {
                    Text("确认删除", color = LabV2.Red, fontWeight = FontWeight.SemiBold)
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
                if (plan.id.isNotBlank() && plan.configured) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                        Text("删除时段", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Red))
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
    val allDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日", "每天")
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        allDays.chunked(4).forEach { rowDays ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowDays.forEach { day ->
                    if (day == "每天") {
                        val allSelected = selectedDays.size == 7
                        FilterChip(
                            selected = allSelected,
                            onClick = { onChange(if (allSelected) emptySet() else (1..7).toSet()) },
                            label = { Text(day, style = LabTypography.Caption) },
                            leadingIcon = if (allSelected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) }) else null,
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LabV2.Cyan.copy(alpha = .12f), selectedLabelColor = LabV2.Primary)
                        )
                    } else {
                        val dayNumber = allDays.indexOf(day) + 1
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
                }
            }
        }
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
    val list = mutableListOf<ParentAttentionEntry>()
    val today = java.time.LocalDate.now()
    val weekdayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    val todayLateNight = device.todayUsage.entries.filter { entry ->
        val hour = entry.timeRange.substringBefore('-').trim().substringBefore(':').toIntOrNull() ?: 24
        hour < 6
    }
    val todayLateMins = if (device.summary.lateNightMinutes > 0) {
        device.summary.lateNightMinutes
    } else if (todayLateNight.isNotEmpty()) {
        todayLateNight.sumOf { it.durationMinutes.coerceAtLeast(1) }
    } else 0

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
            if (todayLateMins <= 0) {
                list.add(ParentAttentionEntry(dayLabel, dateStr, "一切正常", normal = true))
            } else {
                val hours = todayLateMins / 60.0
                val durationText = if (hours >= 1.0) String.format(java.util.Locale.CHINA, "%.1f小时", hours) else "${todayLateMins}分钟"
                val ranges = if (todayLateNight.isNotEmpty()) {
                    todayLateNight.joinToString(", ") { it.timeRange }
                } else {
                    "00:15-00:30"
                }
                list.add(ParentAttentionEntry(dayLabel, dateStr, "【深夜上网】累计${durationText}: $ranges", normal = false))
            }
        } else {
            val existing = device.attentionEntries.firstOrNull { it.date == dateStr }
            if (existing != null) {
                list.add(existing.copy(dayLabel = dayLabel))
            } else {
                list.add(ParentAttentionEntry(dayLabel, dateStr, "一切正常", normal = true))
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
