package com.labprobe.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EditCalendar
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
    val appDevice = remember(deviceId, allDevices) {
        deviceId?.let { id -> allDevices.firstOrNull { cleanMac(it.mac) == cleanMac(id) } }
    }
    val profile = appDevice?.let { remember(it) { inferDeviceProfile(it) } }
    val fallback = repository.state.devices.firstOrNull { deviceId != null && it.summary.matchesChildGuardDevice(deviceId) }?.summary
    val resolvedId = deviceId ?: fallback?.deviceId.orEmpty()
    val resolvedName = appDevice?.let { it.remark.ifBlank { it.name.ifBlank { it.hostName.ifBlank { "未命名设备" } } } }
        ?: fallback?.name ?: "未命名设备"
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

    Column(Modifier.fillMaxSize().appBackground()) {
        ChildDeviceHeader(deviceState.summary, onBack)
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
private fun ChildDeviceHeader(summary: ProtectedDeviceSummary, onBack: () -> Unit) {
    val accent = Color(summary.accentArgb)
    Surface(color = LabV2.BackgroundTop, shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = LabV2.Ink)
            }
            Spacer(Modifier.width(7.dp))
            Surface(shape = RoundedCornerShape(15.dp), color = LabCoreSurface.Card) {
                Box(Modifier.padding(5.dp)) { LabMiniDeviceIcon(summary.iconKey, accent, sizeDp = 42) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(summary.name, style = LabTypography.PageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.Shield, null, tint = if (summary.status == GuardStatus.GUARDED) LabV2.Green else LabV2.InkMuted, modifier = Modifier.size(14.dp))
                    Text(summary.status.label, style = LabTypography.Supporting)
                }
            }
        }
    }
}

@Composable
private fun ChildInternetTabs(selected: ChildInternetTab, onSelect: (ChildInternetTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(LabV2.BackgroundTop).padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        ChildInternetTab.entries.forEach { tab ->
            val active = tab == selected
            val icon = when (tab) {
                ChildInternetTab.REPORT -> Icons.Rounded.Assessment
                ChildInternetTab.PLAN -> Icons.Rounded.EditCalendar
                ChildInternetTab.ATTENTION -> Icons.Rounded.NotificationImportant
            }
            Surface(
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = if (active) LabCoreSurface.Card else Color.Transparent,
                border = if (active) androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border) else null
            ) {
                Column(
                    Modifier.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(icon, null, tint = if (active) LabV2.Primary else LabV2.InkMuted, modifier = Modifier.size(22.dp))
                    Text(tab.title, style = LabTypography.Caption.copy(color = if (active) LabV2.Ink else LabV2.InkMuted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
private fun ChildInternetReportScreen(device: ChildInternetDeviceState, onRefresh: () -> Unit) {
    val context = LocalContext.current
    var period by rememberSaveable(device.summary.deviceId) { mutableStateOf("今日") }
    val usage = if (period == "今日") device.todayUsage else device.recentUsage
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = LabV2.PageHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CompactSegmentedControl(listOf("今日", "最近10天"), period, { period = it }, Modifier.fillMaxWidth().padding(horizontal = 62.dp))
        LabCoreCard(contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (period == "今日") "今日上网时长" else "最近10天上网时长", style = LabTypography.CardTitle)
                    Text(usageSourceLabel(device.usageSource), style = LabTypography.Supporting)
                }
                Text(formatChildDuration(usage.totalMinutes), style = LabTypography.PageTitle)
            }
            UsageBars(usage.bars, period == "今日")
        }
        LabCoreCard(contentPadding = PaddingValues(horizontal = 15.dp, vertical = 14.dp)) {
            Text(if (period == "今日") "今日应用详情" else "应用使用摘要", style = LabTypography.CardTitle)
            Spacer(Modifier.height(2.dp))
            if (usage.entries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LabV2ToolIcon(Icons.Rounded.Assessment, LabV2.InkMuted, size = 44, muted = true)
                    Text("暂无应用使用记录", style = LabTypography.SectionTitle)
                }
            } else {
                usage.entries.forEach { entry -> UsageEntryRow(entry) }
            }
        }
        TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("刷新统计", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
        }
        TextButton(onClick = { toast(context, "$USAGE_REPORT_EXPLAINER_TITLE\n\n$USAGE_REPORT_EXPLAINER") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(USAGE_REPORT_EXPLAINER_TITLE, style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
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
private fun UsageBars(bars: List<UsageBar>, today: Boolean) {
    val maxValue = max(1, bars.maxOfOrNull { it.minutes } ?: 1)
    Row(
        Modifier.fillMaxWidth().height(150.dp).padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (bars.size > 6) 5.dp else 11.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEachIndexed { index, bar ->
            val fraction = (bar.minutes.toFloat() / maxValue).coerceIn(0f, 1f)
            val highlighted = (today && bar.label == "0点") || (!today && index == bars.lastIndex)
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .fillMaxWidth(.55f)
                        .height((8 + fraction * 100).dp)
                        .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                        .background(
                            if (highlighted) {
                                Brush.verticalGradient(listOf(LabV2.Primary.copy(alpha = .28f), LabV2.Primary.copy(alpha = .88f)))
                            } else {
                                SolidColor(LabV2.BorderStrong.copy(alpha = .72f))
                            }
                        )
                )
                Spacer(Modifier.height(7.dp))
                Text(bar.label, style = LabTypography.Caption.copy(color = if (highlighted) LabV2.Primary else LabV2.InkMuted, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium), maxLines = 1)
            }
        }
    }
}

@Composable
private fun UsageEntryRow(entry: InternetUsageEntry) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
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
            if (appManagementSupported) {
                LabCoreCard(contentPadding = PaddingValues(14.dp)) {
                    SectionLead(Icons.Rounded.Apps, "允许使用的 APP", LabV2.Cyan)
                    if (experimentalAppControl) {
                        Text("实验性应用控制", style = LabTypography.Caption.copy(color = LabV2.InkMuted), modifier = Modifier.padding(top = 1.dp))
                    }
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
            } else {
                Text("当前设备不支持应用控制，仍可设置上网时段", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                category.apps.take(3).forEach { app -> DashboardAppIcon(app.iconKey, app.localIconPath, sizeDp = 22, label = app.name) }
                Surface(shape = RoundedCornerShape(8.dp), color = LabV2.Field) {
                    Text("${category.apps.size}+", Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = LabTypography.Caption)
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
 * 官方逻辑对齐:00:00-06:00 的使用计入「深夜上网」警示;当天无深夜使用则显示「一切正常」。
 * 服务端暂不下发 attentionEntries,先用今日使用记录本地推导。
 */
internal fun deriveAttentionEntries(device: ChildInternetDeviceState): List<ParentAttentionEntry> {
    val date = java.time.LocalDate.now().toString()
    val lateNight = device.todayUsage.entries.filter { entry ->
        val hour = entry.timeRange.substringBefore('-').trim().substringBefore(':').toIntOrNull() ?: 24
        hour < 6
    }
    if (lateNight.isEmpty()) {
        return listOf(ParentAttentionEntry("今天", date, "一切正常", normal = true))
    }
    val total = lateNight.sumOf { it.durationMinutes }
    val apps = lateNight.map { it.appName }.distinct().joinToString("、")
    val ranges = lateNight.joinToString("，") { it.timeRange }
    return listOf(ParentAttentionEntry("今天", date, "【深夜上网】累计${total}分钟：$ranges |包括${apps}等应用", normal = false))
}

@Composable
private fun ChildInternetAttentionScreen(entries: List<ParentAttentionEntry>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = LabV2.PageHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LabCoreCard(contentPadding = PaddingValues(16.dp)) {
            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    LabV2ToolIcon(Icons.Rounded.NotificationImportant, LabV2.InkMuted, size = 46, muted = true)
                    Text("最近没有需要关注的记录", style = LabTypography.SectionTitle)
                    Text("保持当前上网习惯即可", style = LabTypography.Supporting)
                }
            } else {
                entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(entry.dayLabel, style = LabTypography.SectionTitle)
                            Text(entry.date, style = LabTypography.Supporting)
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (entry.normal) LabV2.Green.copy(alpha = .08f) else LabV2.Red.copy(alpha = .075f)
                        ) {
                            Text(
                                entry.message,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                style = LabTypography.Body.copy(color = if (entry.normal) LabV2.Green else LabV2.Red, fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }
        Text("— 仅可查看最近10天的记录 —", style = LabTypography.Supporting, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(4.dp))
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
