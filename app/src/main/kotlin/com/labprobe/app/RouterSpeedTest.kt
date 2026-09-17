package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

/** Official gauge mapping: 8 linear segments of 312.5 Mbps each, dial max 2500. */
private val GaugeScale = floatArrayOf(0f, 10f, 50f, 100f, 500f, 1000f, 1500f, 2000f, 2500f)
private const val GaugeSegment = 312.5f

internal fun gaugePosition(mbps: Float): Float {
    if (mbps <= 0f) return 0f
    for (index in 0 until GaugeScale.size - 1) {
        if (mbps <= GaugeSegment * (index + 1)) {
            val base = GaugeScale[index]
            val step = (mbps - GaugeSegment * index) * (GaugeScale[index + 1] - base) / GaugeSegment
            return (base + step) / GaugeScale.last()
        }
    }
    return 1f
}

internal fun formatSpeed(mbps: Double?): String =
    mbps?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--"

/** 6th top-level page: router tool board — speed test up front, then child guard and native settings. */
@Composable
fun RouterToolsScreen(
    prefs: AppPrefs,
    topNav: @Composable () -> Unit,
    onOpen: (String) -> Unit
) {
    ScreenShell("路由工具", "路由器原生能力 · 与官方网页端同源接口", topNav = topNav) {
        SpeedTestCard(prefs)
        RouterToolsQuickSection(onOpen)
    }
}

private data class RouterToolEntry(
    val title: String,
    val subtitle: String,
    val route: String
)

@Composable
private fun RouterToolsQuickSection(onOpen: (String) -> Unit) {
    val guardSection = listOf(
        RouterToolEntry("儿童上网", "上网计划 · 应用管控 · 停网时段", "child_internet_overview")
    )
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
        Text("儿童上网", style = LabTypography.SectionTitle.copy(color = SpeedInk))
        guardSection.forEach { entry ->
            RouterToolTile(title = entry.title, subtitle = entry.subtitle, icon = Icons.Rounded.ChildCare) { onOpen(entry.route) }
        }
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

            SpeedGauge(running = running, down = progress?.primary?.currentDown, up = progress?.primary?.currentUp)
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (history.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("历史测速记录", style = LabTypography.SectionTitle.copy(color = SpeedInk))
                    history.take(5).forEach { record -> SpeedHistoryRow(record) }
                }
            }
        }
    }
}

@Composable
private fun SpeedGauge(running: Boolean, down: Double?, up: Double?) {
    val accent = if (running) SpeedBlue else LabV2.Green
    val displayed = (down ?: 0.0).toFloat().coerceAtLeast(0f)
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = size.minDimension * .055f, cap = StrokeCap.Round)
            val radius = size.minDimension / 2f - stroke.width
            val center = Offset(size.width / 2f, size.height * .76f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            // 240° sweep, 120° open at the bottom, like the official dial.
            drawArc(
                color = LabV2.Field,
                startAngle = -210f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
            drawArc(
                color = accent,
                startAngle = -210f,
                sweepAngle = 240f * gaugePosition(displayed),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                formatSpeed(down),
                style = LabTypography.AppTitle.copy(fontSize = 34.sp, lineHeight = 38.sp, color = SpeedInk)
            )
            Text(
                if (running) "下行测速中 · Mbps" else "下行速率 · Mbps",
                style = LabTypography.Supporting.copy(color = SpeedMuted)
            )
            Text(
                "上行 ${formatSpeed(up)} Mbps",
                style = LabTypography.Supporting.copy(color = SpeedUpColor)
            )
        }
    }
}

@Composable
private fun SpeedMetricRow(progress: SpeedProgress?) {
    val primary = progress?.primary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeedMetricTile("时延", primary?.latency, "ms", Modifier.weight(1f))
        SpeedMetricTile("抖动", primary?.jitter, "ms", Modifier.weight(1f))
        SpeedMetricTile("丢包", primary?.loss, "%", Modifier.weight(1f))
    }
}

@Composable
private fun SpeedMetricTile(label: String, value: Double?, unit: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = LabV2.FieldSoft,
        border = BorderStroke(1.dp, SpeedBorder)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                "${formatSpeed(value)} $unit",
                style = LabTypography.ValueStrong.copy(color = SpeedInk),
                maxLines = 1
            )
            Text(label, style = LabTypography.Caption.copy(color = SpeedMuted))
        }
    }
}

@Composable
private fun SpeedChart(sample: SpeedSampleSet) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = LabV2.FieldSoft,
        border = BorderStroke(1.dp, SpeedBorder)
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedLegend(SpeedDownColor, "下行")
                SpeedLegend(SpeedUpColor, "上行")
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stamp, style = LabTypography.Caption.copy(color = SpeedMuted), modifier = Modifier.width(78.dp))
        Text(
            "↓ ${formatSpeed(record.downMbps)}  ↑ ${formatSpeed(record.upMbps)} Mbps",
            style = LabTypography.Value.copy(color = SpeedInk),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text("${formatSpeed(record.latency)} ms", style = LabTypography.Caption.copy(color = SpeedMuted))
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
