package com.labprobe.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Router-native speed test.
 *
 * Transport mirrors the eWeb "diagnose / speedtest" page: the firmware runs
 * speedtest.elf by itself, the Hub only starts it and forwards the samples the
 * Lua module reads back from disk.  Nothing is measured on the phone, so every
 * number comes straight from the router and "--" is rendered when absent.
 */

private val SpeedInk = LabV2.Ink
private val SpeedMuted = LabV2.InkMuted
private val SpeedBorder = LabCoreSurface.Border
private val SpeedDownColor = Color(0xFF0095D8) // official eWeb down-line colour
private val SpeedUpColor = Color(0xFF00C783) // official eWeb up-line colour
private val SpeedBlue = LabV2.Primary

data class SpeedTestPort(
    val portId: String,
    val name: String,
    val panelName: String,
    val up: Boolean,
    val speedMbps: Int,
    val testable: Boolean
)

data class SpeedTestNode(val id: String, val name: String)

data class SpeedHistoryRecord(
    val epoch: Long,
    val intf: String,
    val downMbps: Double?,
    val upMbps: Double?,
    val latency: Double?
)

data class SpeedSampleSet(
    val intf: String,
    val down: List<Double>,
    val up: List<Double>,
    val currentDown: Double?,
    val currentUp: Double?,
    val latency: Double?,
    val jitter: Double?,
    val loss: Double?
)

data class SpeedProgress(
    val stat: String,
    val finished: Boolean,
    val primary: SpeedSampleSet?
)

class SpeedTestApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)

    private fun execute(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val root = hubApi.requestJson(path, method, body)
        if (root.has("ok") && !root.optBoolean("ok")) {
            val message = root.optString("message").ifBlank { root.optString("error") }
            throw RuntimeException(message.ifBlank { "测速请求失败" })
        }
        return root.optJSONObject("data") ?: JSONObject()
    }

    private fun number(value: Any?): Double? {
        if (value == null || value is Boolean) return null
        if (value is Number) return value.toDouble()
        return value.toString().trim().toDoubleOrNull()
    }

    private fun JSONArray?.toSpeedSeries(): List<Double> {
        if (this == null) return emptyList()
        val out = ArrayList<Double>(length())
        for (index in 0 until length()) {
            number(opt(index))?.let { out.add(it) }
        }
        return out
    }

    private fun sampleFrom(block: JSONObject): SpeedSampleSet = SpeedSampleSet(
        intf = block.optString("intf"),
        down = block.optJSONArray("downspeed").toSpeedSeries(),
        up = block.optJSONArray("upspeed").toSpeedSeries(),
        // The Hub already reduces the trailing sample to a scalar, so prefer it
        // and only fall back to the series when it is missing.
        currentDown = number(block.opt("currentDown"))
            ?: block.optJSONArray("downspeed").toSpeedSeries().lastOrNull(),
        currentUp = number(block.opt("currentUp"))
            ?: block.optJSONArray("upspeed").toSpeedSeries().lastOrNull(),
        latency = number(block.opt("latency")),
        jitter = number(block.opt("jitter")),
        loss = number(block.opt("loss"))
    )

    suspend fun ports(): List<SpeedTestPort> = withContext(Dispatchers.IO) {
        val rows = execute("/api/router/speedtest/ports").optJSONArray("ports") ?: JSONArray()
        val out = ArrayList<SpeedTestPort>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            out.add(
                SpeedTestPort(
                    portId = row.optString("portId"),
                    name = row.optString("name"),
                    panelName = row.optString("panelName"),
                    up = row.optBoolean("up"),
                    speedMbps = row.optInt("speedMbps"),
                    testable = row.optBoolean("testable")
                )
            )
        }
        out
    }

    suspend fun nodes(): List<SpeedTestNode> = withContext(Dispatchers.IO) {
        val rows = execute("/api/router/speedtest/servers").optJSONArray("servers") ?: JSONArray()
        val out = ArrayList<SpeedTestNode>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id")
            if (id.isBlank()) continue
            out.add(SpeedTestNode(id = id, name = row.optString("name").ifBlank { id }))
        }
        out
    }

    suspend fun running(): Boolean = withContext(Dispatchers.IO) {
        execute("/api/router/speedtest/state").optBoolean("running")
    }

    suspend fun progress(): SpeedProgress = withContext(Dispatchers.IO) {
        val data = execute("/api/router/speedtest/progress")
        val stat = data.optString("stat").ifBlank { "idle" }
        SpeedProgress(
            stat = stat,
            finished = stat == "end",
            primary = data.optJSONObject("primary")?.let { sampleFrom(it) }
        )
    }

    suspend fun history(): List<SpeedHistoryRecord> = withContext(Dispatchers.IO) {
        val rows = execute("/api/router/speedtest/history").optJSONArray("records") ?: JSONArray()
        val out = ArrayList<SpeedHistoryRecord>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val primary = row.optJSONObject("primary") ?: continue
            out.add(
                SpeedHistoryRecord(
                    epoch = row.optLong("epoch"),
                    intf = primary.optString("intf"),
                    downMbps = number(primary.opt("currentDown")),
                    upMbps = number(primary.opt("currentUp")),
                    latency = number(primary.opt("latency"))
                )
            )
        }
        out
    }

    suspend fun start(intf: String, node: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("intf", JSONArray().put(intf))
            .put("nodes", JSONObject().put(intf, node))
        execute("/api/router/speedtest/start", "POST", body).optBoolean("ok", true)
    }
}

/** Official eWeb mapping: speed thresholds map to 8 dial segments, max 2500. */
private val GaugeScale = floatArrayOf(0f, 10f, 50f, 100f, 500f, 1000f, 1500f, 2000f, 2500f)
private const val GaugeSegment = 312.5f

internal fun gaugePosition(mbps: Float): Float {
    if (mbps <= 0f) return 0f
    for (index in 1 until GaugeScale.size) {
        if (mbps <= GaugeScale[index]) {
            // eWeb's getGaugeData maps each speed threshold to one uniform
            // dial segment; its formatter performs the inverse conversion.
            val fraction = (mbps - GaugeScale[index - 1]) /
                (GaugeScale[index] - GaugeScale[index - 1])
            val dialValue = GaugeSegment * (index - 1) + fraction * GaugeSegment
            return dialValue.roundToInt() / (GaugeSegment * (GaugeScale.size - 1))
        }
    }
    return 1f
}

internal fun formatSpeed(mbps: Double?): String =
    mbps?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--"

internal fun formatSpeedWithUnit(mbps: Double?): String = "${formatSpeed(mbps)} Mbps"

internal fun isSpeedTestUploadPhase(running: Boolean, progress: SpeedProgress?): Boolean {
    val current = progress ?: return false
    return running && current.stat == "running" && !current.finished && current.primary?.up?.isNotEmpty() == true
}

internal fun visibleSpeedHistory(
    history: List<SpeedHistoryRecord>,
    expanded: Boolean
): List<SpeedHistoryRecord> = if (expanded) history.take(10) else emptyList()

/** 6th top-level page: router tool board — speed test, child guard, then RDPI and native settings. */
@Composable
fun RouterToolsScreen(
    prefs: AppPrefs,
    topNav: @Composable () -> Unit,
    onOpen: (String) -> Unit
) {
    ScreenShell("路由工具", "路由器原生能力 · 与官方网页端同源接口", topNav = topNav) {
        SpeedTestCard(prefs)
        // 儿童上网是家长天天用的入口，排在特征库上面；特征库是低频运维页。
        ChildGuardQuickSection(onOpen)
        RdpiSignatureCard(prefs)
        RouterToolsQuickSection(onOpen)
    }
}

@Composable
private fun ChildGuardQuickSection(onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("儿童上网", style = LabTypography.SectionTitle.copy(color = SpeedInk))
        RouterToolTile(
            title = "儿童上网",
            subtitle = "上网计划 · 应用管控 · 停网时段",
            icon = Icons.Rounded.ChildCare
        ) { onOpen("child_internet_overview") }
    }
}

private data class RouterToolEntry(
    val title: String,
    val subtitle: String,
    val route: String
)

@Composable
private fun RouterToolsQuickSection(onOpen: (String) -> Unit) {
    // 儿童上网和特征库都已经有自己独立的卡片了，这里别再重复列一遍入口。
    val settingSection = listOf(
        RouterToolEntry("映射与 UPnP", "IPv6 映射 · 原生端口映射 · UPnP", "tool_portmap"),
        RouterToolEntry("防火墙", "入站 · 出站 · 转发规则", "tool_router_firewall"),
        RouterToolEntry("网络自检", "物理接线与协商速率检测", "tool_router_diag"),
        RouterToolEntry("路由 NAT 诊断", "RFC3489 / RFC5780 原生检测", "tool_router_nat"),
        RouterToolEntry("IPv6 设置", "WAN · LAN · DHCPv6 客户端", "tool_router_ipv6"),
        RouterToolEntry("DDNS", "LabProbe · 路由器原生 · 证书监控", "tool_router_ddns"),
        RouterToolEntry("WireGuard", "APP 客户端 · DDNS 与 STUN 联动", "tool_wireguard"),
        RouterToolEntry("Webhook", "自定义服务推送与公网地址", "tool_router_webhook")
    )
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("路由设置与诊断", style = LabTypography.SectionTitle.copy(color = SpeedInk))
        settingSection.forEach { entry ->
            RouterToolTile(title = entry.title, subtitle = entry.subtitle) { onOpen(entry.route) }
        }
    }
}

@Composable
private fun RouterToolTile(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Rounded.Settings,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LabCoreSurface.CompactShape)
            .clickable(onClick = onClick),
        shape = LabCoreSurface.CompactShape,
        color = Color.White,
        border = BorderStroke(1.dp, SpeedBorder),
        shadowElevation = 1.dp
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabV2ToolIcon(icon, SpeedBlue, size = 36, muted = true)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = LabTypography.CardTitle.copy(color = SpeedInk),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = LabTypography.Supporting.copy(color = SpeedMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = SpeedMuted)
        }
    }
}

@Composable
private fun SpeedTestCard(prefs: AppPrefs) {
    val scope = rememberCoroutineScope()
    val api = remember(prefs.hub, prefs.token) { SpeedTestApi(prefs) }

    var ports by remember { mutableStateOf<List<SpeedTestPort>>(emptyList()) }
    var nodes by remember { mutableStateOf<List<SpeedTestNode>>(emptyList()) }
    var history by remember { mutableStateOf<List<SpeedHistoryRecord>>(emptyList()) }
    var historyExpanded by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf("0") }
    var selectedPort by remember { mutableStateOf("wan") }
    var running by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SpeedProgress?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(prefs.hub, prefs.token) {
        preparing = true
        runCatching {
            ports = api.ports()
            nodes = api.nodes()
            selectedNode = "0"
        }.onFailure { current -> error = current.message ?: "无法读取路由器测速信息" }
        ports.firstOrNull { it.testable }?.name?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            selectedPort = it
        }
        // A test started from the web UI keeps its progress visible on entry.
        runCatching { api.running() }.onSuccess { running = it }
        runCatching { api.history() }.onSuccess { history = it }
        preparing = false
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1000)
            val result = runCatching { api.progress() }.getOrNull() ?: continue
            progress = result
            if (result.finished) {
                runCatching { api.history() }.onSuccess { history = it }
                break
            }
        }
        running = false
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CardShape,
        color = Color.White,
        border = BorderStroke(1.dp, SpeedBorder),
        shadowElevation = 1.dp
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabV2ToolIcon(Icons.Rounded.Speed, SpeedBlue, size = 36, muted = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("网络测速", style = LabTypography.CardTitle.copy(color = SpeedInk))
                    Text(
                        "路由器直连宽带测速 · 节点可选",
                        style = LabTypography.Supporting.copy(color = SpeedMuted)
                    )
                }
            }

            SpeedGauge(running = running, progress = progress)
            SpeedMetricRow(progress)
            progress?.primary?.takeIf { it.down.isNotEmpty() || it.up.isNotEmpty() }?.let { SpeedChart(it) }

            if (error.isNotBlank()) {
                Text(error, style = LabTypography.Supporting.copy(color = LabV2.Red))
            }

            if (nodes.isNotEmpty()) {
                SpeedNodeRow(nodes = nodes, selected = selectedNode, enabled = !running) { selectedNode = it }
            }

            Button(
                onClick = {
                    if (running || preparing) return@Button
                    error = ""
                    progress = null
                    scope.launch {
                        try {
                            api.start(selectedPort, selectedNode)
                            running = true
                        } catch (throwable: Exception) {
                            error = throwable.message ?: "路由器未能启动测速"
                        }
                    }
                },
                enabled = !running && !preparing,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpeedBlue, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text(
                    when {
                        running -> "测速进行中…"
                        preparing -> "正在读取路由器"
                        else -> "开始测速"
                    },
                    color = if (running || preparing) LabV2.InkMuted else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (history.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { historyExpanded = !historyExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "历史测速记录 (${history.size})",
                            style = LabTypography.SectionTitle.copy(color = SpeedInk)
                        )
                        Icon(
                            imageVector = if (historyExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (historyExpanded) "收起" else "展开",
                            tint = SpeedMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    visibleSpeedHistory(history, historyExpanded).forEach { record -> SpeedHistoryRow(record) }
                }
            }
        }
    }
}

@Composable
private fun SpeedGauge(running: Boolean, progress: SpeedProgress?) {
    val sample = progress?.primary
    val down = sample?.currentDown
    val up = sample?.currentUp
    // During upload the firmware keeps the completed downlink samples while
    // adding uplink samples, so scalar values alone cannot identify the phase.
    val isTestingUp = isSpeedTestUploadPhase(running, progress)
    val activeSpeed = if (isTestingUp) up ?: 0.0 else down ?: 0.0
    val displayed = activeSpeed.toFloat().coerceAtLeast(0f)
    val targetPosition = gaugePosition(displayed).coerceIn(0f, 1f)
    val position by animateFloatAsState(
        targetValue = targetPosition,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "speed_gauge_progress"
    )
    val activeGradients = if (isTestingUp) {
        listOf(SpeedUpColor, Color(0xFF4ADE80), Color(0xFF16A34A))
    } else {
        listOf(Color(0xFF0095D8), Color(0xFF38BDF8), Color(0xFF0284C7))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF8FAFD),
                        Color(0xFFF1F5F9).copy(alpha = 0.6f)
                    )
                )
            )
            .padding(top = 16.dp, bottom = 14.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Dial Box with well-proportioned arc and centered clearance
        Box(
            modifier = Modifier
                .width(260.dp)
                .height(170.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 11.dp.toPx()
                val radius = (size.width / 2f) - strokeWidth - 6.dp.toPx()
                val center = Offset(size.width / 2f, size.height * 0.72f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)
                val startAngle = -215f
                val totalSweep = 250f

                // Outer decorative tick dots
                val tickCount = 25
                val tickRadius = radius + 8.dp.toPx()
                for (i in 0..tickCount) {
                    val angleDeg = startAngle + (totalSweep * (i.toFloat() / tickCount))
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val tickX = center.x + (tickRadius * Math.cos(angleRad)).toFloat()
                    val tickY = center.y + (tickRadius * Math.sin(angleRad)).toFloat()
                    val isMajor = i % 5 == 0
                    drawCircle(
                        color = if (isMajor) Color(0xFFCBD5E1) else Color(0xFFE2E8F0),
                        radius = if (isMajor) 2.2.dp.toPx() else 1.2.dp.toPx(),
                        center = Offset(tickX, tickY)
                    )
                }

                // Background Track
                drawArc(
                    color = Color(0xFFE2E8F0),
                    startAngle = startAngle,
                    sweepAngle = totalSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active Gradient Track
                if (position > 0.005f) {
                    val activeSweep = totalSweep * position
                    drawArc(
                        brush = Brush.sweepGradient(activeGradients, center = center),
                        startAngle = startAngle,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Glowing endpoint indicator
                    val endAngleRad = Math.toRadians((startAngle + activeSweep).toDouble())
                    val endX = center.x + (radius * Math.cos(endAngleRad)).toFloat()
                    val endY = center.y + (radius * Math.sin(endAngleRad)).toFloat()
                    drawCircle(
                        color = Color.White,
                        radius = strokeWidth * 0.38f,
                        center = Offset(endX, endY)
                    )
                }
            }

            // Numbers and labels inside dial - completely framed without arc intersection
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                // Status pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (running) Color(0xFF0095D8).copy(alpha = 0.12f) else Color(0xFFE2E8F0).copy(alpha = 0.7f),
                    border = BorderStroke(0.8.dp, if (running) Color(0xFF0095D8).copy(alpha = 0.25f) else Color(0xFFCBD5E1))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (running) Color(0xFF0095D8) else Color(0xFF94A3B8))
                        )
                        Text(
                            text = if (running) "测速进行中" else "测速就绪",
                            style = LabTypography.Caption.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (running) Color(0xFF0284C7) else Color(0xFF64748B)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                // Main Speed Value
                Text(
                    text = formatSpeed(activeSpeed.takeIf { it > 0.0 } ?: if (running) 0.0 else down),
                    style = LabTypography.AppTitle.copy(
                        fontSize = 42.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isTestingUp) SpeedUpColor else Color(0xFF0F172A),
                        letterSpacing = (-0.5).sp
                    )
                )

                Text(
                    text = when {
                        isTestingUp -> "上行速率 · Mbps"
                        down != null && down > 0.0 -> "下行速率 · Mbps"
                        running -> "实时测速 · Mbps"
                        else -> "下行速率 · Mbps"
                    },
                    style = LabTypography.Caption.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF64748B)
                    )
                )
            }
        }

        // Live Rate Dual Card Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Downlink Card
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFEFF6FF),
                border = BorderStroke(1.dp, Color(0xFFDBEAFE))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF0095D8).copy(alpha = 0.15f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.ArrowDownward,
                                null,
                                tint = Color(0xFF0095D8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Column {
                        Text("实时下行", style = LabTypography.Caption.copy(color = Color(0xFF64748B), fontSize = 11.sp))
                        Text(
                            formatSpeedWithUnit(down),
                            style = LabTypography.CardTitle.copy(
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0284C7)
                            ),
                            maxLines = 1
                        )
                    }
                }
            }

            // Uplink Card
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFF0FDF4),
                border = BorderStroke(1.dp, Color(0xFFDCFCE7))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF16A34A).copy(alpha = 0.15f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.ArrowUpward,
                                null,
                                tint = Color(0xFF16A34A),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Column {
                        Text("实时上行", style = LabTypography.Caption.copy(color = Color(0xFF64748B), fontSize = 11.sp))
                        Text(
                            formatSpeedWithUnit(up),
                            style = LabTypography.CardTitle.copy(
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF16A34A)
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedMetricRow(progress: SpeedProgress?) {
    val primary = progress?.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpeedMetricInlineItem("时延", primary?.latency, "ms", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(18.dp).background(Color(0xFFE2E8F0)))
            SpeedMetricInlineItem("抖动", primary?.jitter, "ms", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(18.dp).background(Color(0xFFE2E8F0)))
            SpeedMetricInlineItem("丢包", primary?.loss, "%", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpeedMetricInlineItem(label: String, value: Double?, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = "${formatSpeed(value)} $unit",
            style = LabTypography.ValueStrong.copy(fontSize = 13.5.sp, color = SpeedInk),
            maxLines = 1
        )
        Text(label, style = LabTypography.Caption.copy(color = SpeedMuted, fontSize = 10.5.sp))
    }
}

@Composable
private fun SpeedChart(sample: SpeedSampleSet) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("速率波动曲线", style = LabTypography.Caption.copy(fontWeight = FontWeight.SemiBold, color = SpeedInk))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeedLegend(SpeedDownColor, "下行")
                    SpeedLegend(SpeedUpColor, "上行")
                }
            }
            Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                val padding = 4.dp.toPx()
                val width = size.width - padding * 2
                val height = size.height - padding * 2
                val peak = listOf(
                    sample.down.maxOrNull() ?: 0.0,
                    sample.up.maxOrNull() ?: 0.0
                ).maxOrNull()?.coerceAtLeast(1.0)?.toFloat() ?: 1f

                fun seriesPath(values: List<Double>): Path {
                    val path = Path()
                    if (values.isEmpty()) return path
                    val lastIndex = values.size - 1
                    values.forEachIndexed { index, value ->
                        val x = padding + width * (if (lastIndex == 0) 0f else index.toFloat() / lastIndex)
                        val y = padding + height * (1f - (value.toFloat() / peak).coerceIn(0f, 1f))
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }

                // Draw area gradient under down curve
                if (sample.down.isNotEmpty()) {
                    val fillPath = Path()
                    val lastIndex = sample.down.size - 1
                    sample.down.forEachIndexed { index, value ->
                        val x = padding + width * (if (lastIndex == 0) 0f else index.toFloat() / lastIndex)
                        val y = padding + height * (1f - (value.toFloat() / peak).coerceIn(0f, 1f))
                        if (index == 0) fillPath.moveTo(x, y) else fillPath.lineTo(x, y)
                    }
                    fillPath.lineTo(padding + width, padding + height)
                    fillPath.lineTo(padding, padding + height)
                    fillPath.close()
                    drawPath(
                        fillPath,
                        brush = Brush.verticalGradient(
                            listOf(
                                SpeedDownColor.copy(alpha = 0.22f),
                                SpeedDownColor.copy(alpha = 0.02f)
                            ),
                            startY = padding,
                            endY = padding + height
                        )
                    )
                }

                val strokeWidth = 2.dp.toPx()
                drawPath(
                    seriesPath(sample.down), SpeedDownColor,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    seriesPath(sample.up), SpeedUpColor,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}


@Composable
private fun SpeedLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = LabTypography.Caption.copy(color = SpeedMuted))
    }
}

@Composable
private fun SpeedNodeRow(nodes: List<SpeedTestNode>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("测速节点", style = LabTypography.SectionTitle.copy(color = SpeedInk))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            nodes.take(8).forEach { node ->
                val active = node.id == selected
                val shape = RoundedCornerShape(11.dp)
                Surface(
                    modifier = Modifier
                        .clip(shape)
                        .clickable(enabled = enabled) { onSelect(node.id) },
                    shape = shape,
                    color = if (active) SpeedBlue.copy(alpha = .12f) else LabV2.FieldSoft,
                    border = BorderStroke(1.dp, if (active) SpeedBlue.copy(alpha = .45f) else SpeedBorder)
                ) {
                    Text(
                        node.name,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = LabTypography.Caption.copy(color = if (active) SpeedBlue else SpeedMuted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedHistoryRow(record: SpeedHistoryRecord) {
    val stamp = if (record.epoch > 0) {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(record.epoch * 1000L))
    } else "--"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(0.8.dp, Color(0xFFF1F5F9))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stamp, style = LabTypography.Caption.copy(color = SpeedMuted, fontSize = 11.5.sp), modifier = Modifier.width(76.dp))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "↓ ${formatSpeedWithUnit(record.downMbps)}",
                    style = LabTypography.Caption.copy(fontWeight = FontWeight.Bold, color = Color(0xFF0284C7)),
                    maxLines = 1
                )
                Text(
                    "↑ ${formatSpeedWithUnit(record.upMbps)}",
                    style = LabTypography.Caption.copy(fontWeight = FontWeight.SemiBold, color = Color(0xFF16A34A)),
                    maxLines = 1
                )
            }
            Text("${formatSpeed(record.latency)} ms", style = LabTypography.Caption.copy(color = SpeedMuted, fontSize = 11.sp))
        }
    }
}

/** Custom glyph for the 6th nav slot: router body with antenna and signal arc. */
object RouterToolsIcon {
    private const val GLYPH =
        "M4.6,11.4 A9.6,9.6 0 0,1 19.4,11.4 " + // signal arc
        "M12,14.6 L12,6.8 " + // antenna
        "M4.4,14.6 L19.6,14.6 " +
        "C20.6,14.6 21.4,15.4 21.4,16.4 L21.4,18.4 " +
        "C21.4,19.4 20.6,20.2 19.6,20.2 L4.4,20.2 " +
        "C3.4,20.2 2.6,19.4 2.6,18.4 L2.6,16.4 " +
        "C2.6,15.4 3.4,14.6 4.4,14.6 Z" // router body

    val vector: ImageVector by lazy {
        ImageVector.Builder(
            name = "RouterTools",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = addPathNodes(GLYPH),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ).build()
    }
}
