package com.labprobe.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(repository.state.error.ifBlank { "正在准备儿童上网页面…" }, style = LabTypography.Body.copy(color = LabV2.InkMuted))
                TextButton(onClick = { repository.refresh() }) { Text("重新加载") }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
        return
    }

    // 用 remember 而不是 rememberSaveable：存过状态的话，从总览再进同一台设备会回到
    // 上次停留的那个标签，看起来就像「随机跳到上网计划/家长请注意」。进设备页固定是
    // 上网报告。
    var selectedTabName by remember(resolvedId) { mutableStateOf(ChildInternetTab.REPORT.name) }
    var planDay by rememberSaveable(resolvedId) { mutableStateOf(currentBeijingWeekday()) }
    /** null = 时段总览；"" = 新建时段；其他 = 正在编辑的规则 id。 */
    var editingPlanId by rememberSaveable(resolvedId) { mutableStateOf<String?>(null) }
    var selectedCategoryId by rememberSaveable(resolvedId) { mutableStateOf<String?>(null) }
    val editingPlan = editingPlanId?.takeIf { it.isNotBlank() }?.let { id -> deviceState.plans.firstOrNull { it.id == id } }
    val draftBase = editingPlan ?: DeviceGuardPlan(
        enabled = true,
        repeatDays = setOf(planDay),
        categories = childInternetCatalogCategories()
    )
    var draftPlan by rememberSaveable(
        resolvedId,
        editingPlanId,
        stateSaver = deviceGuardPlanSaver(draftBase)
    ) { mutableStateOf(draftBase) }
    val selectedTab = ChildInternetTab.valueOf(selectedTabName)
    val selectedCategory = draftPlan.categories.firstOrNull { it.id == selectedCategoryId }

    BackHandler(enabled = selectedCategory != null || editingPlanId != null) {
        if (selectedCategory != null) selectedCategoryId = null else editingPlanId = null
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
    var showUsageLog by rememberSaveable(resolvedId) { mutableStateOf(false) }
    if (showUsageLog) {
        AlertDialog(
            onDismissRequest = { showUsageLog = false },
            title = { Text("今日上网记录") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val entries = deviceState.todayUsage.entries.filter { it.sessions.isNotEmpty() }
                    if (entries.isEmpty()) Text("暂无真实时段记录。更新采集服务后，新记录会在这里显示。")
                    entries.forEach { entry ->
                        Text(entry.appName, style = LabTypography.CardTitle)
                        entry.sessions.forEach { session ->
                            Text("${session.timeRange}\n${session.durationText}", style = LabTypography.Supporting)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showUsageLog = false }) { Text("关闭") } }
        )
    }
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
                            toast(context, it.userMessage())
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
        ChildGuardOperationHud(repository.state.pendingHud)
        Column(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(ChildInternetBandTop, ChildInternetBandBottom))
            )
        ) {
            ChildDeviceHeader(
                summary = deviceState.summary,
                onBack = onBack,
                onRemoveGuard = { confirmRemoveGuard = true },
                onOpenLog = { showUsageLog = true }
            )
            ChildInternetTabs(
                selected = selectedTab,
                onSelect = { selectedTabName = it.name; editingPlanId = null }
            )
        }
        // 内容区：圆角顶 + 与蓝带接缝处那个小尖尖同色，整块像从蓝带里长出来的。
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(ChildInternetSheet)
        ) {
            if (repository.state.error.isNotBlank()) {
                // 之前是一行裸红字飘在背景上，和页面没有任何关系；做成和总览
                // 「更新失败」那颗一样调子的软色条。
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFF5F5),
                    border = BorderStroke(1.dp, Color(0xFFFEE2E2))
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("❗", style = LabTypography.Caption.copy(color = Color(0xFFEF4444), fontSize = 12.sp))
                        Text(
                            repository.state.error,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = LabTypography.Caption.copy(
                                color = Color(0xFFEF4444),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "child-internet-tab"
            ) { tab ->
                when (tab) {
                    ChildInternetTab.REPORT -> ChildInternetReportScreen(
                        deviceState,
                        onRefresh = { repository.loadUsageReport(resolvedId) {} },
                        onSelectDay = { date -> repository.loadUsageForDate(resolvedId, date) {} }
                    )
                    ChildInternetTab.PLAN -> {
                        if (editingPlanId == null && deviceState.plans.isEmpty()) {
                            ChildInternetPlanEmptyState(onOpen = { editingPlanId = "" })
                        } else if (editingPlanId == null) {
                            ChildInternetPlanOverview(
                                plans = deviceState.plans,
                                selectedDay = planDay,
                                onSelectDay = { planDay = it },
                                onOpenRule = { id -> editingPlanId = id },
                                onAddRule = { editingPlanId = "" }
                            )
                        } else {
                            ChildInternetPlanEditor(
                                plan = draftPlan,
                                saving = repository.state.pendingDeviceIds.isNotEmpty(),
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
                                            }.onFailure { toast(context, it.userMessage()) }
                                        }
                                    }
                                },
                                onDelete = { context ->
                                    repository.deletePlan(resolvedId, draftPlan.id) { result ->
                                        result.onSuccess {
                                            editingPlanId = null
                                            toast(context, "时段已删除")
                                        }.onFailure { toast(context, it.userMessage()) }
                                    }
                                },
                                onSave = { context ->
                                    repository.savePlan(resolvedId, draftPlan) { result ->
                                        result.onSuccess {
                                            draftPlan.repeatDays.minOrNull()?.let { planDay = it }
                                            editingPlanId = null
                                            toast(context, "保存成功")
                                        }.onFailure { toast(context, it.userMessage()) }
                                    }
                                }
                            )
                        }
                    }
                    ChildInternetTab.ATTENTION -> ChildInternetAttentionScreen(deviceState.attentionEntries)
                }
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
    // 官方那版：设备名居中，返回键和右侧两个操作浮在同一条蓝带上。这里刻意不再
    // 自己刷背景 —— 状态栏是透明的，头部再盖一层平色就会把外层那道渐变切断，
    // 顶上看起来就成了「状态栏 / 头部 / 标签」三条。
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = LabV2.Ink)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemoveGuard, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.DeleteOutline, "解除儿童守护", tint = LabV2.InkMuted)
            }
            IconButton(onClick = onOpenLog, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.FormatListBulleted, "上网日志", tint = LabV2.InkMuted)
            }
        }
        // 设备名要在自己那一行的正中：左右居中靠 Arrangement.Center，上下居中靠
        // align(Center) —— 少了后者，它会贴着 Box 顶部，看起来整体偏上。
        Row(
            Modifier.fillMaxWidth().align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                LabMiniDeviceIcon(summary.iconKey, accent, sizeDp = 17)
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = summary.name,
                style = LabTypography.PageTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 210.dp)
            )
        }
    }
}

/**
 * 顶部蓝带和内容区分层用的三个色：官方那版设备名和标签都在一条渐变蓝带上，内容
 * 从带子下面「长」出来，接缝处有一个指向当前标签的小尖尖。
 */
internal val ChildInternetBandTop = Color(0xFFD6E2F8)
internal val ChildInternetBandBottom = Color(0xFFE7EEFA)
internal val ChildInternetSheet = Color(0xFFF5F8FC)

@Composable
private fun ChildInternetTabs(selected: ChildInternetTab, onSelect: (ChildInternetTab) -> Unit) {
    val tabs = ChildInternetTab.entries.toList()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            tabs.forEach { tab ->
                val active = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ChildInternetTabIcon(tab, active, Modifier.size(30.dp))
                    Text(
                        text = tab.title,
                        style = LabTypography.Caption.copy(
                            color = if (active) Color(0xFF0F172A) else Color(0xFF7A879B),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        ),
                        maxLines = 1
                    )
                }
            }
        }
        // 小尖尖：和内容区同色，压在蓝带与内容区的接缝上，正对着选中的标签 ——
        // 官方靠它把「当前标签」和「下面的内容」连成一个整体，而不是三条下划线。
        BoxWithConstraints(Modifier.fillMaxWidth().height(9.dp)) {
            val index = tabs.indexOf(selected).coerceAtLeast(0)
            Canvas(
                Modifier
                    .size(width = 22.dp, height = 9.dp)
                    .align(Alignment.TopCenter)
                    .offset(x = maxWidth * ((index + 0.5f) / tabs.size) - maxWidth / 2)
            ) {
                drawPath(
                    Path().apply {
                        moveTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    },
                    ChildInternetSheet
                )
            }
        }
    }
}

/**
 * 三个标签的插画图标，照着官方那版画：网格纸 + 折线（上网报告）、打开的本子 +
 * 铅笔（上网计划）、淡紫圆 + 感叹号（家长请注意）。未选中一律压成灰调，选中才
 * 上色 —— 官方就是靠饱和度而不是下划线区分当前页。
 */
@Composable
private fun ChildInternetTabIcon(tab: ChildInternetTab, active: Boolean, modifier: Modifier = Modifier) {
    val muted = Color(0xFFAEB9C9)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (tab) {
            ChildInternetTab.REPORT -> {
                drawRoundRect(
                    color = if (active) Color.White else Color(0xFFE8ECF2),
                    topLeft = Offset(w * 0.18f, h * 0.10f),
                    size = Size(w * 0.64f, h * 0.80f), cornerRadius = CornerRadius(w * 0.10f)
                )
                val grid = if (active) Color(0xFFDCE6F5) else Color(0xFFD6DDE7)
                for (i in 1..3) {
                    val y = h * (0.10f + 0.80f * i / 4f)
                    drawLine(grid, Offset(w * 0.24f, y), Offset(w * 0.76f, y), w * 0.035f)
                }
                drawPath(
                    Path().apply {
                        moveTo(w * 0.26f, h * 0.66f)
                        lineTo(w * 0.42f, h * 0.40f)
                        lineTo(w * 0.56f, h * 0.58f)
                        lineTo(w * 0.74f, h * 0.30f)
                    },
                    if (active) Color(0xFF2563EB) else muted,
                    style = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            ChildInternetTab.PLAN -> {
                drawRoundRect(
                    color = if (active) Color.White else Color(0xFFE8ECF2),
                    topLeft = Offset(w * 0.10f, h * 0.22f),
                    size = Size(w * 0.56f, h * 0.60f), cornerRadius = CornerRadius(w * 0.07f)
                )
                val rule = if (active) Color(0xFFBFD8F5) else Color(0xFFD6DDE7)
                for (i in 1..3) {
                    val y = h * (0.34f + 0.15f * i)
                    drawLine(rule, Offset(w * 0.20f, y), Offset(w * 0.58f, y), w * 0.04f)
                }
                drawLine(
                    if (active) Color(0xFF60A5FA) else muted,
                    Offset(w * 0.10f, h * 0.22f), Offset(w * 0.10f, h * 0.82f), w * 0.05f
                )
                run {
                    drawRoundRect(
                        color = if (active) Color(0xFFF59E0B) else muted,
                        topLeft = Offset(w * 0.62f, h * 0.16f),
                        size = Size(w * 0.15f, h * 0.46f), cornerRadius = CornerRadius(w * 0.035f)
                    )
                    drawPath(
                        Path().apply {
                            moveTo(w * 0.62f, h * 0.62f)
                            lineTo(w * 0.695f, h * 0.78f)
                            lineTo(w * 0.77f, h * 0.62f)
                            close()
                        },
                        if (active) Color(0xFFFCD34D) else muted
                    )
                }
            }
            ChildInternetTab.ATTENTION -> {
                val center = Offset(w / 2f, h / 2f)
                drawCircle(
                    if (active) Color(0xFFE1DDFB) else Color(0xFFE2E7EE),
                    radius = w * 0.42f, center = center
                )
                val mark = if (active) Color(0xFF6D5BD0) else muted
                drawRoundRect(
                    mark, Offset(w * 0.455f, h * 0.26f),
                    Size(w * 0.09f, h * 0.30f), cornerRadius = CornerRadius(w * 0.045f)
                )
                drawCircle(mark, radius = w * 0.055f, center = Offset(w / 2f, h * 0.70f))
            }
        }
    }
}

@Composable
private fun ChildInternetReportScreen(
    device: ChildInternetDeviceState,
    onRefresh: () -> Unit,
    onSelectDay: (String) -> Unit
) {
    var period by rememberSaveable(device.summary.deviceId) { mutableStateOf("今日") }
    // -1 = 还没手动选过。「最近10天」默认点亮最后一根（今天）。以前固定 0，而切过去
    // 那一刻柱子常常还没回来，lastIndex 就是 0，于是选中最老的那天 —— 图上一片空、
    // 应用详情写着「暂无应用使用记录」。
    var selectedBarIndex by rememberSaveable(device.summary.deviceId, period) { mutableStateOf(-1) }
    var selectedAppForTimeline by remember { mutableStateOf<InternetUsageEntry?>(null) }
    var showExplanation by remember { mutableStateOf(false) }
    if (showExplanation) {
        AlertDialog(onDismissRequest = { showExplanation = false },
            title = { Text(USAGE_REPORT_EXPLAINER_TITLE) },
            text = { Text(USAGE_REPORT_EXPLAINER) },
            confirmButton = { TextButton(onClick = { showExplanation = false }) { Text("知道了") } })
    }

    val usage = if (period == "今日") device.todayUsage else device.recentUsage
    val currentBars = usage.bars
    val safeIndex = when {
        selectedBarIndex >= 0 -> selectedBarIndex.coerceIn(0, max(0, currentBars.lastIndex))
        // 没手动选过：逐日看今天，小时图从 0 点起。
        period == "最近10天" -> max(0, currentBars.lastIndex)
        else -> 0
    }
    val selectedBar = currentBars.getOrNull(safeIndex)
    // 「最近10天」看的是选中那一天的详情，不是把 10 天混成一堆看不出顺序的数字。
    val selectedDate = if (period == "今日") "" else selectedBar?.date.orEmpty()
    LaunchedEffect(period, selectedDate) {
        if (selectedDate.isNotBlank()) onSelectDay(selectedDate)
    }
    val dayDetail = device.dayUsage?.takeIf { it.date == selectedDate }
    val currentEntries = when {
        period == "今日" -> device.todayUsage.entries
        dayDetail != null -> dayDetail.usage.entries
        else -> emptyList()
    }
    val entriesLoading = period != "今日" && dayDetail?.loading != false

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
        // 官方是浅灰轨道上的一枚白色小胶囊，不是实心蓝块 —— 蓝块在这个页面上太抢。
        CompactSegmentedControl(
            options = listOf("今日", "最近10天"),
            selected = period,
            onSelect = { period = it },
            accent = Color.White,
            activeContentColor = LabV2.Ink,
            barHeight = 34.dp,
            cornerRadius = 20.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 88.dp)
        )

        // 当日上网时长 Card
        LabCoreCard(contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (period == "今日") "今日上网时长" else "当日上网时长",
                        style = LabTypography.CardTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "流量活跃度（含内网）",
                        style = LabTypography.Supporting.copy(fontSize = 12.sp, color = LabV2.InkMuted)
                    )
                }
                FormattedDurationText(usage.totalMinutes)
            }
            UsageBarsWithGrid(
                bars = currentBars,
                selectedIndex = safeIndex,
                onSelectBar = { selectedBarIndex = it }
            )
        }

        val report = device.usageReport
        if (report != null && report.todayTotalBytes > 0L) {
            LabCoreCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp)) {
                Text("流量与网络统计", style = LabTypography.CardTitle.copy(fontSize = 15.5.sp, lineHeight = 19.sp))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReportStatMetric("今日总流量", formatTrafficBytes(report.todayTotalBytes), LabV2.Cyan, Modifier.weight(1f))
                    ReportStatMetric("今日上传", formatTrafficBytes(report.todayTxBytes), LabV2.Primary, Modifier.weight(1f))
                    ReportStatMetric("今日下载", formatTrafficBytes(report.todayRxBytes), LabV2.Green, Modifier.weight(1f))
                }
                // 审计绑定 IP 属于诊断信息，家长页面不展示，避免把卡片撑高。
            }
        }

        // 当日应用详情 Card
        LabCoreCard(contentPadding = PaddingValues(horizontal = 15.dp, vertical = 12.dp)) {
            // 官方标题不带日期；日期只在选中那根柱子的气泡上出现。
            Text(
                text = if (period == "今日") "今日应用详情" else "当日应用详情",
                style = LabTypography.CardTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(2.dp))
            if (currentEntries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (entriesLoading || device.usageLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = LabV2.Cyan
                        )
                    } else {
                        LabV2ToolIcon(Icons.Rounded.Assessment, LabV2.InkMuted, size = 44, muted = true)
                        Text("暂无应用使用记录", style = LabTypography.SectionTitle)
                    }
                }
            } else {
                currentEntries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        // 官方就是一根很细的线把两行隔开，不是卡片、不是留白。
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                .height(0.6.dp)
                                .background(LabV2.Border)
                        )
                    }
                    UsageEntryRow(entry, onClick = { selectedAppForTimeline = entry })
                }
            }
        }
        // 官方这里就是一行居中的小字，不是一个空心的大按钮；同步时只多一枚和文字
        // 等高的细环，不额外占一块地方。
        Row(
            Modifier.fillMaxWidth().height(36.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (device.usageLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = LabV2.InkMuted
                )
            }
            Text(
                if (device.usageLoading) "正在同步统计…" else "刷新统计",
                modifier = if (device.usageLoading) Modifier else Modifier.clickable(onClick = onRefresh),
                style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
            )
            // 两个动作并成一行，中间一个间隔点；原来上下两行各占一块，显得空。
            Text("·", style = LabTypography.Supporting.copy(color = LabV2.InkFaint))
            Text(
                USAGE_REPORT_EXPLAINER_TITLE,
                modifier = Modifier.clickable { showExplanation = true },
                style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
            )
        }
    }
}

@Composable
private fun ReportStatMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.08f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                label,
                maxLines = 1,
                style = LabTypography.Caption.copy(color = LabV2.InkMuted, fontSize = 10.sp, lineHeight = 12.5.sp)
            )
            Text(
                value,
                maxLines = 1,
                style = LabTypography.SectionTitle.copy(
                    color = color,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

/** 流量保留两位小数，让总流量与上/下载之和在界面上对得上。 */
private fun formatTrafficBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
}

/** 官方说明页标题（来自官方 bundle 字符串表，便于家长对照）。 */
internal const val USAGE_REPORT_EXPLAINER_TITLE = "关于\u201c上网时长\u201d和\u201c应用详情\u201d计算方式说明"

/**
 * 口径说明写在这里，而不是让家长自己猜为什么时长和官方对不上。
 *
 * 官方的算法是「按设备上的动态应用检测 + 单应用流量量级」去**预测**时长
 * （官方说明以微信 KB/s、视频 MB/s 举例）。本项目按自然分钟计数：一个分钟内
 * 有活跃流量即算 1 分钟，绝不由字节量或平均速率反推时长。
 */
internal const val USAGE_REPORT_EXPLAINER =
    "上网时长按自然分钟统计：某分钟内有活跃流量即记 1 分钟，不足一分钟按一分钟计，不等于设备屏幕使用时间。" +
        "采集会过滤后台心跳与保活；与官方统计口径不同，数字可能有差异。" +
        "应用时长只累计已识别并能映射到应用的活动，未识别流量、部分内网和 IPv6 活动可能遗漏。" +
        "同时使用多个应用时，设备活跃分钟去重、应用时长分别累计，因此应用之和可能大于设备总时长。保留最近 10 天。"

private fun usageSourceLabel(source: String): String = when (source) {
    "hub" -> "按活跃分钟统计 · Hub 汇总"
    "relay" -> "按活跃分钟统计 · 路由器采样"
    "empty" -> "暂无数据 · 等待路由器首次上报"
    else -> "按活跃分钟统计"
}

@Composable
private fun FormattedDurationText(minutes: Int?) {
    if (minutes == null) {
        // Hub 这一天没有统计：显示 --，不写 0分钟。
        Text(
            text = "--",
            style = LabTypography.PageTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
        )
        return
    }
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
    // 官方的小时图固定 60/40/20 分钟三格、不标 0；逐日图按当天最长那根向上取到
    // 偶数小时，再三等分。
    val maxMinutes = if (isHourly) 60 else {
        val maxHours = ((bars.maxOfOrNull { it.minutes } ?: 0) + 59) / 60
        (((maxHours.coerceAtLeast(2) + 1) / 2) * 2) * 60
    }
    val axisUnit = if (isHourly) "分钟" else "小时"
    val axisValues = if (isHourly) listOf(60, 40, 20)
        else listOf(maxMinutes, maxMinutes * 2 / 3, maxMinutes / 3)

    // 点柱子才浮出气泡，5 秒自己消退；官方没有常驻的「N点 · M分钟」那一行。
    var bubbleShown by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex, bars.size) {
        bubbleShown = true
        delay(5_000)
        bubbleShown = false
    }

    BoxWithConstraints(
        // 顶上留一条 32dp 的带子专门放气泡：官方那句「15点:上网45分钟」是贴在最高
        // 那根网格线**上方**，用一小段竖线连到柱子；我们之前浮在绘图区里面，压住了
        // 柱头和 60 分钟那条线。
        Modifier.fillMaxWidth().height(192.dp).padding(top = 32.dp)
    ) {
        val chartWidth = maxWidth
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(21.dp).fillMaxHeight().padding(bottom = 26.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                axisValues.forEach { value ->
                    // 官方是两行式：数字在上、单位在下，最下面不再补一个 0。
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (isHourly) "$value" else "${value / 60}",
                            style = LabTypography.Caption.copy(fontSize = 10.sp, color = LabV2.InkMuted)
                        )
                        Text(
                            axisUnit,
                            style = LabTypography.Caption.copy(fontSize = 10.sp, color = LabV2.InkMuted)
                        )
                    }
                }
            }
            Spacer(Modifier.width(3.dp))

            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(modifier = Modifier.fillMaxSize().padding(bottom = 26.dp)) {
                    val stepY = size.height / 3f
                    for (i in 0..2) {
                        drawLine(
                            color = Color(0xFFE5E7EB),
                            start = Offset(0f, i * stepY),
                            end = Offset(size.width, i * stepY),
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
                            (bar.minutes.toFloat() / maxMinutes).coerceIn(0f, 1f)
                        } else 0f

                        Column(
                            // 柱子只要选中态，点下去不要那层灰色涟漪 —— 一整排细柱
                            // 每根都亮一下比官方那种「只有一根变色」脏得多。
                            Modifier.weight(1f).fillMaxHeight()
                                .clickable(
                                    interactionSource = remember(index) { MutableInteractionSource() },
                                    indication = null
                                ) { onSelectBar(index) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                                if (fraction > 0f) {
                                    Box(
                                        Modifier
                                            .width(if (isHourly) 8.dp else 16.dp)
                                            .fillMaxHeight(fraction)
                                            .heightIn(min = 2.dp)
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                            .background(
                                                // 逐日图只有选中那根是紫渐变，其余浅灰；
                                                // 小时图整排都是紫渐变（官方 14:15 那样）。
                                                if (!isHourly && !isSelected) {
                                                    Brush.verticalGradient(
                                                        listOf(Color(0xFFE8EAF0), Color(0xFFE8EAF0))
                                                    )
                                                } else {
                                                    Brush.verticalGradient(
                                                        listOf(Color(0xFF8D7BF3), Color(0xFFC3B8F9))
                                                    )
                                                }
                                            )
                                    )
                                }
                            }

                            Box(Modifier.height(26.dp), contentAlignment = Alignment.BottomCenter) {
                                Text(
                                    text = if (isHourly) {
                                        // 24 根柱子只标整点里的 6 个，否则挤成一团。
                                        if (index % 4 == 0) bar.label else ""
                                    } else bar.label,
                                    style = LabTypography.Caption.copy(
                                        fontSize = 10.sp,
                                        color = if (isSelected) LabV2.Primary else LabV2.InkMuted,
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

        if (bubbleShown && selectedIndex in bars.indices) {
            val bar = bars[selectedIndex]
            val bubbleWidth = 100.dp
            // 柱子是从 Y 轴右边开始的，锚点要按绘图区算，不然气泡会整体偏左。
            val plotLeft = 24.dp
            val anchor = plotLeft + (chartWidth - plotLeft) * ((selectedIndex + 0.5f) / bars.size)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (anchor - bubbleWidth / 2).coerceAtLeast(0.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 140.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF2F3F7)
                ) {
                    Text(
                        "${bar.label}:上网${formatChildDuration(bar.minutes)}",
                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = LabTypography.Caption.copy(fontSize = 11.sp, color = LabV2.Ink),
                        maxLines = 1
                    )
                }
                // 官方那截把气泡连到柱子上的细竖线。
                Box(Modifier.width(1.5.dp).height(9.dp).background(Color(0xFFCBD5E1)))
            }
        }
    }
}

/** 官方那一行：图标 | 名称+时长 | 时段在上、「共N次」在下 | chevron。 */
@Composable
private fun UsageEntryRow(entry: InternetUsageEntry, onClick: () -> Unit = {}) {
    // 行内时段说「最近一段」，不是首末包络 —— 00:10 和 13:52 之间那 13 小时并没有
    // 在用；只有一条会话时 Hub 已经给了同一段，多条时取最后一段才是用户刚发生的事。
    val lastRange = entry.sessions.lastOrNull()?.timeRange?.takeIf { it.isNotBlank() }
        ?: entry.timeRange
    Row(
        // 列表行只要「按下就弹详情」，不要 Material 那层矩形涟漪 —— 一整列细行
        // 每点一条就亮一块灰底，比官方的无反馈还脏。
        Modifier.fillMaxWidth()
            .clickable(
                interactionSource = remember(entry.id) { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        DashboardAppIcon(entry.iconKey, entry.localIconPath, sizeDp = 44, label = entry.appName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.appName, style = LabTypography.SectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (entry.durationMinutes > 0) formatChildDuration(entry.durationMinutes) else "使用不足1分钟",
                style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (lastRange.isNotBlank()) {
                Text(lastRange, style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
            }
            Text("共${entry.count}次", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
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
        confirmButton = {
            // 放进气泡自己的按钮栏，正文底部就不会再留一整条空白；官方那里是紧贴的。
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(42.dp),
                shape = RoundedCornerShape(21.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)
            ) {
                Text("知道了", style = LabTypography.Body.copy(color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp))
            }
        },
        dismissButton = {},
        containerColor = LabCoreSurface.Card,
        shape = RoundedCornerShape(20.dp),
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = entry.appName,
                    style = LabTypography.CardTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))
                // 官方把统计压成一行。「活跃时段数 7 次」会被读成「用了 7 次共 7 分钟」，
                // 所以直说是几段连续使用；时长只报服务器给的分钟数，不拿首末跨度凑。
                Text(
                    text = "累计 " +
                        (if (entry.durationMinutes > 0) formatChildDuration(entry.durationMinutes) else "不足 1 分钟") +
                        " · ${entry.count} 段连续使用",
                    style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
                )
                Spacer(Modifier.height(12.dp))

                if (entry.sessions.isNotEmpty()) {
                    // 官方是「圆点 + 上下点之间的竖线」把几段会话串成一列，时长紧跟在
                    // 时段后面、同一个字号，而不是被顶到最右边 —— 顶到右边会让中间空出一
                    // 大片，读起来像两张表。
                    entry.sessions.forEachIndexed { index, session ->
                        Row(
                            Modifier.fillMaxWidth().height(38.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.width(16.dp).fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(Modifier.fillMaxHeight().width(2.dp)) {
                                    val mid = size.height / 2f
                                    if (index > 0) drawLine(
                                        color = Color(0xFFE5E7EB),
                                        start = Offset(size.width / 2f, 0f),
                                        end = Offset(size.width / 2f, mid),
                                        strokeWidth = 1.5.dp.toPx()
                                    )
                                    if (index < entry.sessions.lastIndex) drawLine(
                                        color = Color(0xFFE5E7EB),
                                        start = Offset(size.width / 2f, mid),
                                        end = Offset(size.width / 2f, size.height),
                                        strokeWidth = 1.5.dp.toPx()
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(6.dp)
                                        .clip(CircleShape).background(Color(0xFFD1D5DB))
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                session.timeRange,
                                modifier = Modifier.width(120.dp),
                                style = LabTypography.Body.copy(fontSize = 15.sp, color = LabV2.Ink),
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                "使用${session.durationText}",
                                style = LabTypography.Body.copy(fontSize = 15.sp, color = LabV2.Ink)
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "当前记录尚未提供真实起止时间，因此不展示推算时段。更新采集服务后，新记录会显示活跃时段。",
                        style = LabTypography.Caption.copy(
                            color = LabV2.InkMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp)
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
        Text("设置允许上网的时段，并选择时段内允许使用的应用。", style = LabTypography.Body.copy(color = LabV2.InkMuted), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth(.70f).height(48.dp), shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)) {
            Text("去开启", style = LabTypography.Button)
        }
    }
}

/** 官方交互：点周几看当天的时段，点时段进入对应规则页，可继续添加时段。 */
@Composable
private fun ChildInternetPlanOverview(
    plans: List<DeviceGuardPlan>,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    onOpenRule: (String) -> Unit,
    onAddRule: () -> Unit
) {
    val segments = remember(selectedDay, plans) { dayPlanSegments(selectedDay, plans) }
    val dayRules = remember(selectedDay, plans) { plans.filter { selectedDay in it.repeatDays } }
    val pausedRules = dayRules.filterNot { it.enabled }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = LabV2.PageHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LabCoreCard(contentPadding = PaddingValues(16.dp)) {
            PlanWeekStrip(
                selectedDays = dayRules.flatMap { it.repeatDays }.toSet(),
                selectedDay = selectedDay,
                onSelectDay = onSelectDay
            )
            Spacer(Modifier.height(8.dp))
            Text(
                weekdayTitle(selectedDay) + if (dayRules.isEmpty()) " · 全天禁网" else " · ${dayRules.size}条上网时段",
                style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
            )
            Spacer(Modifier.height(2.dp))
            segments.forEachIndexed { index, segment ->
                val owners = segment.planIds.mapNotNull { id -> plans.firstOrNull { it.id == id } }
                PlanTimelineRow(
                    blocked = segment.blocked,
                    time = "${segment.start}-${segment.end}",
                    label = when {
                        segment.blocked -> "禁网"
                        owners.size > 1 -> "${owners.size}条规则同时生效"
                        else -> owners.firstOrNull()?.let(::planRuleLabel) ?: "时段内允许上网"
                    },
                    apps = owners.flatMap(::planAllowedApps).distinctBy { it.id },
                    first = index == 0,
                    last = index == segments.lastIndex,
                    onClick = segment.planIds.firstOrNull { it.isNotBlank() }?.let { id -> { onOpenRule(id) } }
                )
            }
        }
        // 官方把每条规则并列摆在外面，不是只画选中的那一天 —— 一周排了几条、分别管
        // 哪几天、是整段允许还是只放部分 APP，得在列表页一眼看全。
        val configured = plans.filter { it.configured && it.id.isNotBlank() && it.enabled }
        if (configured.isNotEmpty()) {
            LabCoreCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Text("全部上网时段", style = LabTypography.CardTitle)
                Spacer(Modifier.height(4.dp))
                configured.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenRule(rule.id) }
                            .padding(horizontal = 4.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Schedule, null, tint = LabV2.InkMuted, modifier = Modifier.size(18.dp))
                        Text("${rule.startTime}-${rule.endTime}", style = LabTypography.SectionTitle)
                        Text(
                            planRepeatLabel(rule),
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            style = LabTypography.Supporting.copy(color = LabV2.InkMuted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(planRuleLabel(rule), style = LabTypography.Caption.copy(color = LabV2.InkMuted))
                        Icon(Icons.Rounded.ChevronRight, null, tint = LabV2.InkMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (pausedRules.isNotEmpty()) {
            LabCoreCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Text("已停用的时段", style = LabTypography.CardTitle)
                Spacer(Modifier.height(4.dp))
                pausedRules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { rule.id.takeIf { it.isNotBlank() }?.let(onOpenRule) }
                            .padding(horizontal = 4.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Schedule, null, tint = LabV2.InkMuted, modifier = Modifier.size(18.dp))
                        Text("${rule.startTime}-${rule.endTime}",
                            modifier = Modifier.weight(1f),
                            style = LabTypography.SectionTitle.copy(color = LabV2.InkMuted))
                        Text("已停用", style = LabTypography.Caption.copy(color = LabV2.InkMuted))
                        Icon(Icons.Rounded.ChevronRight, null, tint = LabV2.InkMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Button(
            onClick = onAddRule,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)
        ) { Text("添加上网时段", style = LabTypography.Button) }
        Spacer(Modifier.height(4.dp))
    }
}

private fun planAllowedApps(plan: DeviceGuardPlan): List<SelectableAppItem> =
    plan.categories.filter { it.enabled }.flatMap { it.apps }.filter { it.selected }

private fun planRuleLabel(plan: DeviceGuardPlan): String =
    if (planAllowedApps(plan).isEmpty()) "时段内允许上网" else "部分APP允许上网"

/** 一条规则重复在哪几天。全选说「每天」，否则按周一到周日列出。 */
internal fun planRepeatLabel(plan: DeviceGuardPlan): String {
    val days = plan.repeatDays.sorted()
    if (days.size >= 7) return "每天"
    return days.joinToString("、") { weekdayTitle(it) }
}

internal fun weekdayTitle(day: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(day - 1) { "" }

internal fun currentBeijingWeekday(): Int =
    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).dayOfWeek.value.coerceIn(1, 7)

/** One 禁网 / 允许 block on the per-weekday timeline. */
internal data class PlanDaySegment(
    val blocked: Boolean,
    val start: String,
    val end: String,
    val planIds: List<String> = emptyList()
)

/**
 * Merges every rule repeating on [day] into consecutive blocks, so two rules
 * like 17:00-19:00 and 19:00-21:30 read as one allowed window instead of
 * overlapping rows. Zero-padded HH:MM compares correctly as text.
 */
internal fun dayPlanSegments(day: Int, plans: List<DeviceGuardPlan>): List<PlanDaySegment> {
    val allowed = plans
        .filter { day in it.repeatDays && it.enabled && it.startTime.isNotBlank() && it.startTime < it.endTime }
        .sortedWith(compareBy({ it.startTime }, { it.endTime }))
    val starts = ArrayList<String>()
    val ends = ArrayList<String>()
    val owners = ArrayList<MutableList<String>>()
    allowed.forEach { plan ->
        val lastIndex = starts.lastIndex
        if (lastIndex >= 0 && plan.startTime <= ends[lastIndex]) {
            if (plan.endTime > ends[lastIndex]) ends[lastIndex] = plan.endTime
            owners[lastIndex].add(plan.id)
        } else {
            starts.add(plan.startTime)
            ends.add(plan.endTime)
            owners.add(mutableListOf(plan.id))
        }
    }
    val segments = ArrayList<PlanDaySegment>()
    var cursor = "00:00"
    starts.forEachIndexed { index, start ->
        if (start > cursor) segments.add(PlanDaySegment(true, cursor, start))
        segments.add(PlanDaySegment(false, start, ends[index], owners[index].filter { it.isNotBlank() }))
        cursor = ends[index]
    }
    if (cursor < "23:59") segments.add(PlanDaySegment(true, cursor, "23:59"))
    return segments
}

@Composable
private fun ChildInternetPlanEditor(
    plan: DeviceGuardPlan,
    saving: Boolean,
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
        // 官方的确认框很紧：居中标题 + 一行问句 + 两枚等宽胶囊。确认按钮仍写「删除」
        // 而不是照抄官方的「提交」—— 这是不可撤销的操作，按钮就该说它要干什么。
        Dialog(onDismissRequest = { confirmDelete = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
                shape = RoundedCornerShape(20.dp),
                color = LabCoreSurface.Card
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                    Text(
                        "提示",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = LabTypography.CardTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "是否删除时段？",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = LabTypography.Body
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { confirmDelete = false },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                        ) { Text("取消", style = LabTypography.Button.copy(color = LabV2.Ink)) }
                        Button(
                            onClick = { confirmDelete = false; onDelete(context) },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)
                        ) { Text("删除", style = LabTypography.Button.copy(color = Color.White)) }
                    }
                }
            }
        }
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
                            enabled = !saving,
                            colors = SwitchDefaults.colors(checkedTrackColor = LabV2.Cyan)
                        )
                    }
                }
            }
            // 这里不再放「当天时段」预览：官方把生效规则放在列表页，编辑页只管改
            // 当前这一条。以前同屏那圈只读周条不能点，看起来就像坏了。
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
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onSave(context) },
                    // 忙时照样禁双击，但不许变灰：官方点「完成配置」是按钮保持原样、
                    // 由页面级「配置中…」报状态；变灰会让人以为没点上而反复点。
                    enabled = !saving && plan.repeatDays.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LabV2.Cyan,
                        contentColor = Color.White,
                        disabledContainerColor = LabV2.Cyan,
                        disabledContentColor = Color.White
                    )
                ) { Text("完成配置", style = LabTypography.Button.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) }
                if (plan.configured && plan.id.isNotBlank()) {
                    Surface(
                        onClick = { if (!saving) confirmDelete = true },
                        enabled = true,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(50),
                        color = LabCoreSurface.Card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "删除时段",
                                style = LabTypography.Button.copy(color = LabV2.Red, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            )
                        }
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
 * 家长请注意只渲染服务器逐日返回的记录（`ChildInternetDeviceState.attentionEntries`）：
 * 界面不再自己拼行，没有记录就是空态。
 */
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
                        Text("暂无使用记录", style = LabTypography.CardTitle.copy(fontSize = 16.sp))
                        Text("收到设备真实统计后显示提醒", style = LabTypography.Caption.copy(color = Color(0xFF64748B)))
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
                            if (!entry.hasData) {
                                Text(entry.message, style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                            } else if (entry.normal) {
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
                                    Column(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        Text(
                                            entry.message,
                                            style = LabTypography.Body.copy(
                                                color = Color(0xFFDC2626),
                                                fontSize = 13.5.sp,
                                                lineHeight = 21.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                        entry.windows.forEach { window ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    window.rangeText,
                                                    style = LabTypography.Caption.copy(
                                                        color = Color(0xFFB91C1C),
                                                        fontSize = 12.5.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                )
                                                Text(
                                                    window.app,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = LabTypography.Caption.copy(
                                                        color = Color(0xFF7F1D1D),
                                                        fontSize = 12.5.sp
                                                    )
                                                )
                                                Text(
                                                    formatLateNightDuration(window.minutes),
                                                    style = LabTypography.Caption.copy(
                                                        color = Color(0xFFB91C1C),
                                                        fontSize = 12.5.sp
                                                    )
                                                )
                                            }
                                        }
                                    }
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
private fun PlanWeekStrip(
    selectedDays: Set<Int>,
    selectedDay: Int? = null,
    onSelectDay: ((Int) -> Unit)? = null
) {
    val today = currentBeijingWeekday()
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val day = index + 1
            val isToday = day == today
            val hasRule = day in selectedDays
            val active = day == selectedDay
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .then(if (onSelectDay == null) Modifier else Modifier.clickable { onSelectDay(day) })
                    .background(
                        when {
                            active -> LabV2.Cyan
                            isToday -> LabV2.Cyan.copy(alpha = .22f)
                            hasRule -> LabV2.Cyan.copy(alpha = .13f)
                            else -> LabV2.Field
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        label,
                        style = LabTypography.Body.copy(
                            color = when {
                                active -> Color.White
                                isToday || hasRule -> LabV2.Primary
                                else -> LabV2.InkMuted
                            },
                            fontWeight = if (active || isToday || hasRule) FontWeight.Bold else FontWeight.Medium
                        )
                    )
                    if (isToday) {
                        Box(
                            Modifier.size(4.dp).clip(CircleShape)
                                .background(if (active) Color.White else LabV2.Primary)
                        )
                    }
                }
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
    last: Boolean,
    onClick: (() -> Unit)? = null
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
            modifier = Modifier.weight(1f).padding(vertical = 5.dp)
                .then(if (onClick == null) Modifier else Modifier.clip(RoundedCornerShape(15.dp)).clickable { onClick() }),
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
                if (onClick != null) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = LabV2.InkMuted, modifier = Modifier.size(18.dp))
                }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(20.dp),
                color = LabCoreSurface.Card
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        title,
                        style = LabTypography.CardTitle,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimeWheelColumn(value = hour, range = 0..23, modifier = Modifier.weight(1f)) { hour = it }
                        Text(":", style = LabTypography.CardTitle.copy(fontSize = 22.sp, color = LabV2.Ink))
                        TimeWheelColumn(value = minute, range = 0..59, modifier = Modifier.weight(1f)) { minute = it }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(50)
                        ) { Text("取消", style = LabTypography.Button.copy(color = LabV2.InkMuted)) }
                        Button(
                            onClick = { onConfirm("%02d:%02d".format(hour, minute)) },
                            modifier = Modifier.weight(1f).height(40.dp),
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
            plan.id,
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
        if (saved.size < 6) {
            base
        } else {
            val categoryState = saved.drop(6).mapNotNull { encoded ->
                val parts = encoded.split('|', limit = 3)
                val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val enabled = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: false
                val selectedIds = parts.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                id to (enabled to selectedIds)
            }.toMap()
            base.copy(
                id = saved[0],
                configured = saved[1].toBooleanStrictOrNull() ?: base.configured,
                enabled = saved[2].toBooleanStrictOrNull() ?: base.enabled,
                startTime = saved[3],
                endTime = saved[4],
                repeatDays = saved[5].split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet(),
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
