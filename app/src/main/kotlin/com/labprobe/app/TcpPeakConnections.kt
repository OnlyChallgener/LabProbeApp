package com.labprobe.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private data class TcpPeakUiState(
    val snapshot: TcpPeakSnapshot = TcpPeakSnapshot(),
    val trend: List<TcpPeakTrendPoint> = emptyList(),
    val history: List<TcpPeakHistory> = emptyList(),
    val notice: String = ""
)

private class TcpPeakController(context: Context, private val prefs: AppPrefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = TcpPeakLocalRunner()
    private val api = HubApi(prefs)
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val savedIds = mutableSetOf<String>()
    private var activeJob: Job? = null
    private var activeConfig: TcpPeakConfig? = null
    private val initialHistory = parseTcpPeakHistory(prefs.tcpPeakHistoryJson)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        TcpPeakUiState(history = initialHistory)
    )
    val state: kotlinx.coroutines.flow.StateFlow<TcpPeakUiState> = _state

    init {
        val retained = encodeTcpPeakHistory(initialHistory)
        if (retained != prefs.tcpPeakHistoryJson) prefs.tcpPeakHistoryJson = retained
    }

    fun start(rawConfig: TcpPeakConfig) {
        val error = rawConfig.validationError()
        if (error != null) {
            _state.value = _state.value.copy(notice = error)
            return
        }
        if (!running.compareAndSet(false, true)) {
            _state.value = _state.value.copy(notice = "已有测试正在运行，请先停止")
            return
        }
        val config = rawConfig.normalized()
        activeConfig = config
        stopRequested.set(false)
        val localTaskId = "app-${UUID.randomUUID()}"
        _state.value = _state.value.copy(
            snapshot = TcpPeakSnapshot(
                taskId = localTaskId,
                side = config.side,
                state = "queued",
                status = if (config.side == TcpPeakSide.APP) "正在启动本机测试" else "正在向 Hub 提交一次性任务",
                resourcesReleased = true,
                releaseStatus = "尚未创建测试连接",
                startedEpochMs = System.currentTimeMillis()
            ),
            trend = emptyList(),
            notice = ""
        )
        activeJob = scope.launch {
            try {
                if (config.side == TcpPeakSide.APP) {
                    runner.run(localTaskId, config, ::publish)
                } else {
                    runRelay(config)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (errorValue: Throwable) {
                val current = _state.value.snapshot
                publish(
                    current.copy(
                        state = "interrupted",
                        status = "测试已中断",
                        finishReason = uiMessageZh(errorValue.message.orEmpty()).ifBlank { "测试链路异常" },
                        error = uiMessageZh(errorValue.message.orEmpty()),
                        finishedEpochMs = System.currentTimeMillis()
                    )
                )
            } finally {
                running.set(false)
                activeJob = null
            }
        }
    }

    fun stop() {
        stopRequested.set(true)
        val current = _state.value.snapshot
        if (!current.active) return
        _state.value = _state.value.copy(
            snapshot = current.copy(state = "stop_requested", status = "正在停止并释放连接"),
            notice = ""
        )
        if (current.side == TcpPeakSide.APP) {
            scope.launch { runner.requestStop() }
        } else {
            scope.launch {
                runCatching { api.stopTcpPeakTask(current.taskId) }
                    .onSuccess { publish(it) }
                    .onFailure {
                        _state.value = _state.value.copy(notice = "停止通知暂未送达，正在继续确认 Relay 状态")
                    }
            }
        }
    }

    fun dispose() {
        stop()
        scope.launch {
            delay(8_000L)
            runner.requestStop()
            activeJob?.cancel()
            scope.cancel()
        }
    }

    private suspend fun runRelay(config: TcpPeakConfig) {
        var snapshot = runCatching { api.startTcpPeakTask(config) }.getOrElse { startError ->
            val recovered = runCatching { api.getTcpPeakTask() }.getOrNull()
            if (recovered?.active == true) recovered else throw startError
        }
        publish(snapshot)
        if (stopRequested.get() && snapshot.active && snapshot.taskId.isNotBlank()) {
            snapshot = api.stopTcpPeakTask(snapshot.taskId)
            publish(snapshot)
        }
        var consecutiveFailures = 0
        while (scope.isActive && snapshot.active) {
            delay(1_000L)
            val next = runCatching { api.getTcpPeakTask() }
            if (next.isSuccess) {
                snapshot = next.getOrThrow()
                consecutiveFailures = 0
                publish(snapshot)
            } else {
                consecutiveFailures++
                val error = uiMessageZh(next.exceptionOrNull()?.message.orEmpty())
                _state.value = _state.value.copy(
                    snapshot = snapshot.copy(status = "Relay 状态暂时不可用，正在重试"),
                    notice = error
                )
                if (consecutiveFailures >= 5) {
                    runCatching { api.stopTcpPeakTask(snapshot.taskId) }
                    snapshot = snapshot.copy(
                        state = "interrupted",
                        status = "Hub 状态请求连续超时",
                        finishReason = "Hub 连续 5 次未返回状态，测试已中断",
                        resourcesReleased = false,
                        releaseStatus = "等待 Relay 自身保护流程释放连接",
                        finishedEpochMs = System.currentTimeMillis()
                    )
                    publish(snapshot)
                }
            }
        }
    }

    private suspend fun publish(snapshot: TcpPeakSnapshot) {
        withContext(Dispatchers.Default) {
            val current = _state.value
            val freezeTrend = snapshot.state == "stop_requested" ||
                snapshot.state == "releasing" ||
                snapshot.terminal
            val trend = if (snapshot.state == "idle" || freezeTrend) current.trend else {
                appendTcpPeakTrend(current.trend, snapshot, System.currentTimeMillis())
            }
            _state.value = current.copy(snapshot = snapshot, trend = trend, notice = snapshot.error)
            if (snapshot.terminal) saveHistory(snapshot)
        }
    }

    private fun saveHistory(snapshot: TcpPeakSnapshot) {
        val config = activeConfig ?: return
        val id = snapshot.taskId.ifBlank { "${config.side}-${snapshot.startedEpochMs}" }
        if (!savedIds.add(id)) return
        val started = snapshot.startedEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val history = listOf(
            TcpPeakHistory(id, started, config.side, config.host, config.port, config.family, snapshot)
        ) + _state.value.history
        val retained = parseTcpPeakHistory(encodeTcpPeakHistory(history))
        prefs.tcpPeakHistoryJson = encodeTcpPeakHistory(retained)
        _state.value = _state.value.copy(history = retained)
    }
}

@Composable
fun TcpPeakConnectionsScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(prefs) { TcpPeakController(context, prefs) }
    val pendingAiCommand = remember(prefs) {
        TcpPeakPendingAiCommand.fromJson(prefs.tcpPeakPendingAiCommandJson)
    }
    val ui by controller.state.collectAsState()
    var side by remember { mutableStateOf(pendingAiCommand?.config?.side ?: TcpPeakSide.APP) }
    var family by remember { mutableStateOf(pendingAiCommand?.config?.family ?: TcpPeakFamily.BOTH) }
    var host by remember { mutableStateOf(pendingAiCommand?.config?.host ?: prefs.tcpPeakHost) }
    var port by remember { mutableStateOf(pendingAiCommand?.config?.port?.toString() ?: prefs.tcpPeakPort) }
    var target by remember { mutableStateOf(pendingAiCommand?.config?.targetConnections?.toString() ?: prefs.tcpPeakTarget) }
    var cps by remember { mutableStateOf(pendingAiCommand?.config?.cps?.toString() ?: prefs.tcpPeakCps) }
    var extremeMode by remember { mutableStateOf(pendingAiCommand?.config?.extremeMode ?: false) }
    var logsExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    val active = ui.snapshot.active

    fun currentConfig(): TcpPeakConfig = TcpPeakConfig(
        side = side,
        host = host,
        port = port.toIntOrNull() ?: 0,
        family = family,
        targetConnections = target.toIntOrNull() ?: 0,
        cps = cps.toIntOrNull() ?: 0,
        extremeMode = extremeMode
    )
    fun leavePage() {
        controller.stop()
        onBack()
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    LaunchedEffect(controller, pendingAiCommand?.id) {
        if (prefs.tcpPeakPendingAiCommandJson.isNotBlank()) {
            // One-shot handoff: clearing before start prevents a recomposition
            // or process restore from launching a duplicate task.
            prefs.tcpPeakPendingAiCommandJson = ""
        }
        pendingAiCommand?.let { controller.start(it.config) }
    }

    DetailShell(
        title = "TCP 峰值连接数",
        subtitle = "本机 APP 与 Relay 使用同一目标配置",
        onBack = ::leavePage,
        compactHeader = true,
        unifiedTypography = true
    ) {
        TcpPeakConfigCard(
            side = side,
            family = family,
            host = host,
            port = port,
            target = target,
            cps = cps,
            extremeMode = extremeMode,
            enabled = !active,
            onSide = { side = it },
            onFamily = { family = it },
            onHost = { host = it; prefs.tcpPeakHost = it },
            onPort = { port = it; prefs.tcpPeakPort = it },
            onTarget = { target = it; prefs.tcpPeakTarget = it },
            onCps = { cps = it; prefs.tcpPeakCps = it },
            onExtremeMode = { next ->
                extremeMode = next
                if (!next && (cps.toIntOrNull() ?: 0) > 2_000) {
                    cps = "2000"
                    prefs.tcpPeakCps = cps
                }
            }
        )

        Button(
            onClick = { if (active) controller.stop() else controller.start(currentConfig()) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = LabV2.CardShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) LabV2.Red else LabV2.Primary,
                contentColor = Color.White
            )
        ) {
            Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (active) "停止并释放连接" else "开始测试", fontWeight = FontWeight.Bold)
        }
        if (ui.notice.isNotBlank()) {
            Text(ui.notice, style = LabTypography.Supporting.copy(color = LabV2.Red), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        TcpPeakProgressCard(ui.snapshot, logsExpanded) { logsExpanded = !logsExpanded }
        TcpPeakTrendChart(ui.trend)
        TcpPeakProtocolCard("IPv4", ui.snapshot.ipv4, LabV2.Primary, expanded = family != TcpPeakFamily.IPV6)
        TcpPeakProtocolCard("IPv6", ui.snapshot.ipv6, LabV2.Green, expanded = family != TcpPeakFamily.IPV4)
        TcpPeakResourceCard(ui.snapshot)
        TcpPeakExpandableSection("测试历史", ui.history.size, historyExpanded, { historyExpanded = !historyExpanded }) {
            TcpPeakHistoryContent(ui.history)
        }
    }
}

@Composable
private fun TcpPeakConfigCard(
    side: TcpPeakSide,
    family: TcpPeakFamily,
    host: String,
    port: String,
    target: String,
    cps: String,
    extremeMode: Boolean,
    enabled: Boolean,
    onSide: (TcpPeakSide) -> Unit,
    onFamily: (TcpPeakFamily) -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onTarget: (String) -> Unit,
    onCps: (String) -> Unit,
    onExtremeMode: (Boolean) -> Unit
) {
    Surface(
        shape = LabV2.CardShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabCoreSurface.Border)
    ) {
        val segmentColors = tcpPeakSegmentedColors()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("测试设置", style = LabTypography.CardTitle.copy(color = LabV2.Ink))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("测试端", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TcpPeakSide.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            modifier = Modifier.weight(1f),
                            shape = SegmentedButtonDefaults.itemShape(index, TcpPeakSide.entries.size, LabV2.CompactCardShape),
                            colors = segmentColors,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                            icon = {},
                            selected = side == item,
                            onClick = { onSide(item) },
                            enabled = enabled,
                            label = { Text(item.label, style = LabTypography.CompactButton) }
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("目标域名或 IP", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
                OutlinedTextField(
                    value = host,
                    onValueChange = onHost,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    singleLine = true,
                    placeholder = { Text("例如：example.com", style = LabTypography.Placeholder) },
                    textStyle = LabTypography.FieldValue,
                    shape = LabV2.CompactCardShape,
                    colors = labOutlinedColors()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TcpPeakNumberField("端口", port, onPort, enabled, Modifier.weight(1f))
                TcpPeakNumberField("连接量程", target, onTarget, enabled, Modifier.weight(1f))
                TcpPeakNumberField("CPS", cps, onCps, enabled, Modifier.weight(1f))
            }
            Surface(
                shape = LabV2.CompactCardShape,
                color = if (extremeMode) LabV2.Amber.copy(alpha = .08f) else LabV2.FieldSoft,
                border = BorderStroke(
                    1.dp,
                    if (extremeMode) LabV2.Amber.copy(alpha = .34f) else LabCoreSurface.Border.copy(alpha = .82f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "极限模式",
                            style = LabTypography.SectionTitle.copy(color = LabV2.Ink)
                        )
                        Text(
                            if (extremeMode && side == TcpPeakSide.RELAY)
                                "CPS 上限 10000 · 临时扩展源端口，结束自动恢复"
                            else if (extremeMode)
                                "CPS 上限 10000 · 本机仍保留资源保护"
                            else
                                "安全模式 · CPS 上限 2000",
                            style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = extremeMode,
                        onCheckedChange = onExtremeMode,
                        enabled = enabled
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("地址族", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TcpPeakFamily.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            modifier = Modifier.weight(1f),
                            shape = SegmentedButtonDefaults.itemShape(index, TcpPeakFamily.entries.size, LabV2.CompactCardShape),
                            colors = segmentColors,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
                            icon = {},
                            selected = family == item,
                            onClick = { onFamily(item) },
                            enabled = enabled,
                            label = { Text(item.label, style = LabTypography.CompactButton) }
                        )
                    }
                }
            }
            Text(
                if (extremeMode && side == TcpPeakSide.RELAY)
                    "Relay 会临时调整源端口范围；停止、完成或异常恢复时会还原，同时保留 FD、Conntrack、内存与 CPU 保护。"
                else if (extremeMode)
                    "本机极限模式提高 CPS 上限；FD 与资源释放保护仍然生效。"
                else
                    "65535 是量程上限，不代表设备必须达到；IPv4 使用 A 记录，IPv6 使用 AAAA 记录。",
                style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
            )
        }
    }
}

private fun tcpPeakSegmentedColors() = SegmentedButtonColors(
    activeContainerColor = LabV2.Primary.copy(alpha = .12f),
    activeContentColor = LabV2.PrimaryStrong,
    activeBorderColor = LabV2.Primary.copy(alpha = .42f),
    inactiveContainerColor = Color.White,
    inactiveContentColor = LabV2.InkMuted,
    inactiveBorderColor = LabCoreSurface.Border,
    disabledActiveContainerColor = LabV2.Primary.copy(alpha = .07f),
    disabledActiveContentColor = LabV2.Primary.copy(alpha = .48f),
    disabledActiveBorderColor = LabV2.Primary.copy(alpha = .16f),
    disabledInactiveContainerColor = LabV2.FieldSoft,
    disabledInactiveContentColor = LabV2.InkFaint,
    disabledInactiveBorderColor = LabCoreSurface.Border.copy(alpha = .60f)
)

@Composable
private fun TcpPeakNumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted), maxLines = 1)
        OutlinedTextField(
            value = value,
            onValueChange = { onValue(it.filter(Char::isDigit).take(5)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            singleLine = true,
            textStyle = LabTypography.FieldValue,
            shape = LabV2.CompactCardShape,
            colors = labOutlinedColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun TcpPeakProgressCard(
    snapshot: TcpPeakSnapshot,
    logsExpanded: Boolean,
    onToggleLogs: () -> Unit
) {
    val statusColor = when {
        snapshot.state == "failed" || snapshot.state == "interrupted" -> LabV2.Red
        snapshot.active -> LabV2.Amber
        else -> LabV2.Green
    }
    Surface(shape = LabV2.CardShape, color = Color.White, border = BorderStroke(1.dp, LabCoreSurface.Border)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(snapshot.status, style = LabTypography.CardTitle.copy(color = LabV2.Ink), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(formatTcpPeakDuration(snapshot.elapsedMs), style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
            }
            Text(
                snapshot.finishReason.ifBlank { "结束原因将在测试停止后显示" },
                style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
            )
            HorizontalDivider(color = LabCoreSurface.Border.copy(alpha = .72f), thickness = .5.dp)
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleLogs).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "最近日志（${snapshot.logs.size}）",
                    modifier = Modifier.weight(1f),
                    style = LabTypography.Supporting.copy(color = LabV2.Ink, fontWeight = FontWeight.SemiBold)
                )
                Icon(
                    if (logsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (logsExpanded) "折叠最近日志" else "展开最近日志",
                    tint = LabV2.InkMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (logsExpanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = LabCoreSurface.InnerShape,
                    color = LabCoreSurface.Inner,
                    border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = .72f))
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (snapshot.logs.isEmpty()) {
                            Text("暂无日志", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                        } else {
                            snapshot.logs.takeLast(20).forEach { line ->
                                Text(line, style = LabTypography.Supporting.copy(color = LabV2.Ink, lineHeight = 18.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TcpPeakTrendChart(points: List<TcpPeakTrendPoint>) {
    Surface(shape = LabV2.CardShape, color = Color.White, border = BorderStroke(1.dp, LabCoreSurface.Border)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("活动连接数趋势", style = LabTypography.CardTitle.copy(color = LabV2.Ink), modifier = Modifier.weight(1f))
                TcpPeakLegend(LabV2.Primary, "IPv4")
                Spacer(Modifier.width(9.dp))
                TcpPeakLegend(LabV2.Green, "IPv6")
            }
            Canvas(Modifier.fillMaxWidth().height(152.dp)) {
                val grid = Color(0xFFE8EDF3)
                repeat(4) { index ->
                    val y = size.height * index / 3f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = .5.dp.toPx())
                }
                if (points.size < 2) return@Canvas
                val maximum = points.maxOf { maxOf(it.ipv4Current, it.ipv6Current) }.coerceAtLeast(1)
                val horizontalInset = 2.dp.toPx()
                val verticalInset = 4.dp.toPx()
                val plotWidth = (size.width - horizontalInset * 2).coerceAtLeast(1f)
                val plotHeight = (size.height - verticalInset * 2).coerceAtLeast(1f)
                fun path(value: (TcpPeakTrendPoint) -> Int): Path = Path().apply {
                    points.forEachIndexed { index, point ->
                        val x = horizontalInset + plotWidth * index / (points.size - 1).toFloat()
                        val y = verticalInset + plotHeight * (1f - value(point) / maximum.toFloat())
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                val lineStyle = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(path(TcpPeakTrendPoint::ipv4Current), LabV2.Primary, style = lineStyle)
                drawPath(path(TcpPeakTrendPoint::ipv6Current), LabV2.Green, style = lineStyle)
            }
            if (points.size < 2) Text("开始测试后约每秒更新一次", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
        }
    }
}

@Composable
private fun TcpPeakLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, color = LabV2.InkMuted)
    }
}

@Composable
private fun TcpPeakProtocolCard(label: String, metric: TcpPeakMetric, accent: Color, expanded: Boolean) {
    Surface(shape = LabV2.CardShape, color = Color.White, border = BorderStroke(1.dp, LabCoreSurface.Border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = LabTypography.CardTitle.copy(color = accent), modifier = Modifier.weight(1f))
                Text(metric.status, style = LabTypography.Supporting.copy(color = LabV2.InkMuted), maxLines = 1)
            }
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TcpPeakMetricCell("当前连接", metric.current.toString(), Modifier.weight(1f))
                    TcpPeakMetricCell("峰值", metric.peak.toString(), Modifier.weight(1f))
                    TcpPeakMetricCell("CPS", metric.cps.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TcpPeakMetricCell("成功", metric.success.toString(), Modifier.weight(1f))
                    TcpPeakMetricCell("失败", metric.failure.toString(), Modifier.weight(1f))
                    TcpPeakMetricCell("耗时", formatTcpPeakDuration(metric.elapsedMs), Modifier.weight(1f))
                }
                Text(metric.finishReason.ifBlank { "结束原因：—" }, style = LabTypography.Supporting.copy(color = LabV2.InkMuted), maxLines = 2)
            }
        }
    }
}

@Composable
private fun TcpPeakMetricCell(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(vertical = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = LabTypography.Caption.copy(color = LabV2.InkMuted), maxLines = 1)
        Text(value, style = LabTypography.ValueStrong.copy(color = LabV2.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TcpPeakResourceCard(snapshot: TcpPeakSnapshot) {
    val relay = snapshot.side == TcpPeakSide.RELAY
    val releasing = snapshot.state == "stop_requested" || snapshot.state == "releasing"
    Surface(shape = LabV2.CardShape, color = Color.White, border = BorderStroke(1.dp, LabCoreSurface.Border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("资源保护", style = LabTypography.CardTitle.copy(color = LabV2.Ink))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TcpPeakMetricCell("Conntrack 峰值", if (relay && snapshot.conntrackPeak > 0) snapshot.conntrackPeak.toString() else "—", Modifier.weight(1f))
                TcpPeakMetricCell("CPU 峰值", if (relay && snapshot.cpuPeak > 0) String.format(Locale.getDefault(), "%.1f%%", snapshot.cpuPeak) else "—", Modifier.weight(1f))
                TcpPeakMetricCell("最低可用内存", if (relay && snapshot.memoryMinAvailableMb > 0) "${snapshot.memoryMinAvailableMb} MB" else "—", Modifier.weight(1f))
            }
            if (releasing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = LabV2.Primary,
                    trackColor = LabV2.Primary.copy(alpha = .10f)
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = if (snapshot.resourcesReleased) "测试资源已释放" else "测试资源正在使用或释放",
                        tint = if (snapshot.resourcesReleased) LabV2.Green else LabV2.Amber,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    snapshot.releaseStatus,
                    modifier = Modifier.weight(1f),
                    style = LabTypography.Supporting.copy(color = LabV2.Ink, lineHeight = 18.sp)
                )
            }
        }
    }
}

@Composable
private fun TcpPeakExpandableSection(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(shape = LabV2.CardShape, color = Color.White, border = BorderStroke(1.dp, LabCoreSurface.Border)) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$title（$count）", style = LabTypography.CardTitle.copy(color = LabV2.Ink), modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "折叠$title" else "展开$title", tint = LabV2.InkMuted, modifier = Modifier.size(22.dp))
            }
            if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { content() }
        }
    }
}

@Composable
private fun TcpPeakHistoryContent(history: List<TcpPeakHistory>) {
    if (history.isEmpty()) {
        Text("暂无历史记录", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
        return
    }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val groups = remember(history) { history.groupBy { dateFormatter.format(Date(it.startedEpochMs)) } }
    groups.forEach { (date, rows) ->
        var expanded by remember(date) { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(date, fontWeight = FontWeight.Bold, color = LabV2.Ink, modifier = Modifier.weight(1f))
            Text("${rows.size} 次", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
            Spacer(Modifier.width(4.dp))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "折叠$date" else "展开$date", tint = LabV2.InkMuted, modifier = Modifier.size(20.dp))
        }
        if (expanded) rows.forEach { row ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = LabCoreSurface.InnerShape,
                color = LabCoreSurface.Inner,
                border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = .72f))
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${timeFormatter.format(Date(row.startedEpochMs))} · ${row.side.label} · ${row.host}:${row.port}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
                    Text("IPv4 峰值 ${row.snapshot.ipv4.peak} · IPv6 峰值 ${row.snapshot.ipv6.peak} · ${row.snapshot.finishReason}", style = LabTypography.Supporting.copy(color = LabV2.InkMuted), maxLines = 3)
                    if (row.side == TcpPeakSide.RELAY) {
                        Text("Conntrack ${row.snapshot.conntrackPeak} · CPU ${String.format(Locale.getDefault(), "%.1f%%", row.snapshot.cpuPeak)} · 最低可用内存 ${row.snapshot.memoryMinAvailableMb} MB", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                    }
                }
            }
        }
    }
}

private fun formatTcpPeakDuration(value: Long): String {
    val seconds = value.coerceAtLeast(0L) / 1_000L
    return if (seconds < 60) "${seconds}s" else "%d:%02d".format(seconds / 60, seconds % 60)
}
