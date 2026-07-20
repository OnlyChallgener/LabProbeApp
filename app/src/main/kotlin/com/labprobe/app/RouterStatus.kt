package com.labprobe.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private data class RouterPortUi(
    val name: String,
    val connected: Boolean,
    val isWan: Boolean,
    val isGame: Boolean,
    val isHybrid: Boolean,
    val speedLabel: String = ""
)

private data class RouterDashboardUi(
    val name: String = "路由器",
    val model: String = "--",
    val online: Boolean = false,
    val temperature: Double = 0.0,
    val temperature2g: Double = 0.0,
    val temperature5g: Double = 0.0,
    val cpu: Double = 0.0,
    val memory: Double = 0.0,
    val uptimeSeconds: Long = 0,
    val onlineDevices: Int = 0,
    val uploadBps: Long = 0,
    val downloadBps: Long = 0,
    val wanIpv4: String = "--",
    val wanGateway: String = "--",
    val connectionType: String = "--",
    val mtu: String = "--",
    val dnsServers: List<String> = emptyList(),
    val lanIpv4: String = "--",
    val netmask: String = "--",
    val vlanId: String = "--",
    val dhcpLease: String = "--",
    val uplink: String = "--",
    val ssid: String = "--",
    val bands: String = "--",
    val channels: String = "--",
    val apEnabled: Boolean = false,
    val ports: List<RouterPortUi> = emptyList(),
    val updatedAt: String = "--",
    val telemetryStale: Boolean = true,
    val detailsStale: Boolean = true,
    val refreshCompletedNonce: Long = 0
)

private fun JSONObject.obj(name: String): JSONObject = optJSONObject(name) ?: JSONObject()
private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray()

private fun jsonNumber(root: JSONObject, key: String): Double {
    val value = root.opt(key)
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().trimEnd('%').toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}

private fun walkJson(value: Any?, visit: (String, Any?) -> Unit) {
    when (value) {
        is JSONObject -> {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = value.opt(key)
                visit(key, child)
                walkJson(child, visit)
            }
        }
        is JSONArray -> for (index in 0 until value.length()) walkJson(value.opt(index), visit)
    }
}

private fun recursiveString(root: JSONObject, keys: Set<String>, predicate: (String) -> Boolean = { true }): String {
    var result = ""
    walkJson(root) { key, value ->
        if (result.isBlank() && key.lowercase() in keys) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank() && text != "null" && predicate(text)) result = text
        }
    }
    return result
}

private fun recursiveDns(root: JSONObject): List<String> {
    val result = linkedSetOf<String>()
    walkJson(root) { key, value ->
        if (!key.contains("dns", ignoreCase = true)) return@walkJson
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) {
                value.optString(i).split(',', ' ', ';').map(String::trim).filter { isIpText(it) }.forEach(result::add)
            }
            else -> value?.toString()?.split(',', ' ', ';')?.map(String::trim)?.filter { isIpText(it) }?.forEach(result::add)
        }
    }
    return result.take(3)
}

private fun isIpText(value: String): Boolean = value.matches(Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")) || value.contains(':')

private fun connectionType(network: JSONObject): String {
    var result = ""
    walkJson(network) { key, value ->
        if (result.isNotBlank()) return@walkJson
        if (key.lowercase() !in setOf("proto", "protocol", "connectiontype", "connecttype", "linktype", "mode", "type")) return@walkJson
        val text = value?.toString()?.trim().orEmpty()
        val low = text.lowercase()
        result = when {
            "pppoe" in low -> "PPPoE"
            "dhcp" in low -> "DHCP"
            "static" in low || "manual" in low -> "静态 IP"
            else -> ""
        }
    }
    return result.ifBlank { "--" }
}

private fun parseRouterDashboard(root: JSONObject?): RouterDashboardUi {
    if (root == null) return RouterDashboardUi()
    val telemetry = root.obj("telemetry")
    val wanTelemetry = telemetry.obj("wan")
    val details = root.obj("details")
    val identity = details.obj("identity")
    val wan = details.obj("wan")
    val lan = details.obj("lan")
    val wireless = details.obj("wireless")
    val network = details.obj("network")

    val ssids = wireless.array("ssidList")
    var primarySsid = ""
    for (i in 0 until ssids.length()) {
        val item = ssids.optJSONObject(i) ?: continue
        if (!item.optBoolean("enabled", item.optString("enabled").equals("true", true))) continue
        primarySsid = item.optString("ssidName").trim()
        if (primarySsid.isNotBlank()) break
    }

    val radios = wireless.array("radioList")
    var radioEnabled = false
    val channelParts = mutableListOf<String>()
    val bandParts = mutableListOf<String>()
    for (i in 0 until radios.length()) {
        val item = radios.optJSONObject(i) ?: continue
        if (item.optBoolean("enabled", item.optString("enabled").equals("true", true))) radioEnabled = true
        val band = item.optString("band").ifBlank { item.optString("name") }.ifBlank { item.optString("radio") }
        if (band.isNotBlank()) bandParts += band
        val channel = item.optString("channel").ifBlank { item.optString("channelText") }
        if (channel.isNotBlank()) channelParts += channel
    }

    val ports = buildList {
        val list = details.array("ports")
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val panelName = item.optString("panel_name").ifBlank { item.optString("name") }.ifBlank { "端口${i + 1}" }
            val speedRaw = item.optString("speed")
            val speedLabel = when (speedRaw.trim().uppercase()) {
                "10" -> "10M"
                "100" -> "100M"
                "1000", "1G" -> "1G"
                "2500", "2.5G" -> "2.5G"
                "10000", "10G" -> "10G"
                else -> speedRaw.takeIf { it.isNotBlank() } ?: ""
            }
            add(
                RouterPortUi(
                    name = panelName,
                    connected = item.optString("status").equals("on", true) || item.optBoolean("status", false),
                    isWan = panelName.equals("WAN", true) || item.optString("name").equals("WAN", true),
                    isGame = panelName.contains("GAME", true),
                    isHybrid = panelName.contains("WAN1", true) || panelName.contains("聚合", true) || item.optString("aggregator_port") == "1",
                    speedLabel = speedLabel
                )
            )
        }
    }

    val netmask = recursiveString(network, setOf("netmask", "subnetmask", "mask")) { it.count { ch -> ch == '.' } == 3 }
    val vlan = recursiveString(network, setOf("vlanid", "vlan_id", "vid")) { value -> value.toIntOrNull()?.let { it in 0..4094 } == true }
    val lanIp = lan.optString("ipv4").ifBlank {
        recursiveString(network, setOf("lanip", "lan_ip", "ipaddr", "ipaddress")) {
            it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")
        }
    }
    val gateway = recursiveString(network, setOf("gateway", "gw", "gatewayip")) { isIpText(it) }
    val mtu = recursiveString(network, setOf("mtu")) { it.toIntOrNull() != null }
    val lease = recursiveString(network, setOf("leasetime", "lease", "lease_time", "dhcp_lease", "leaseText"))
    val uplink = recursiveString(network, setOf("wanif", "wan_if", "interface", "uplink", "upstream"))

    val name = identity.optString("hostname").ifBlank { root.optString("router") }.ifBlank { "路由器" }
    return RouterDashboardUi(
        name = name,
        model = identity.optString("model").ifBlank { "--" },
        online = root.optBoolean("online", false),
        temperature = jsonNumber(telemetry, "temperatureC"),
        temperature2g = jsonNumber(telemetry, "temperature2gC"),
        temperature5g = jsonNumber(telemetry, "temperature5gC"),
        cpu = jsonNumber(telemetry, "cpuPercent"),
        memory = jsonNumber(telemetry, "memoryPercent"),
        uptimeSeconds = telemetry.optLong("uptimeSeconds", 0),
        onlineDevices = telemetry.optInt("onlineDeviceCount", 0),
        uploadBps = wanTelemetry.optLong("uploadBps", 0),
        downloadBps = wanTelemetry.optLong("downloadBps", 0),
        wanIpv4 = wan.optString("ipv4").ifBlank { "--" },
        wanGateway = gateway.ifBlank { "--" },
        connectionType = connectionType(network),
        mtu = mtu.ifBlank { "--" },
        dnsServers = recursiveDns(network),
        lanIpv4 = lanIp.ifBlank { "--" },
        netmask = netmask.ifBlank { "--" },
        vlanId = vlan.ifBlank { "--" },
        dhcpLease = lease.ifBlank { "--" },
        uplink = uplink.ifBlank { "--" },
        ssid = primarySsid.ifBlank { "--" },
        bands = bandParts.distinct().joinToString(" / ").ifBlank { "2.4G / 5G" },
        channels = channelParts.distinct().joinToString(" / ").ifBlank { "--" },
        apEnabled = radioEnabled,
        ports = ports,
        updatedAt = root.optString("telemetryAt").ifBlank { root.optString("receivedAt") }.ifBlank { "--" },
        telemetryStale = root.optBoolean("telemetryStale", true),
        detailsStale = root.optBoolean("detailsStale", true),
        refreshCompletedNonce = root.optLong("refreshCompletedNonce", 0)
    )
}

@Composable
fun RouterStatusScreen(prefs: AppPrefs, state: AppState, onBack: () -> Unit, onOpenDevices: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var refreshing by remember { mutableStateOf(false) }
    var showPortLegend by remember { mutableStateOf(false) }
    var showRouterEditor by remember { mutableStateOf(false) }

    val rawUi = remember(state.routerDashboard) { parseRouterDashboard(state.routerDashboard) }
    val displayName = prefs.routerDisplayName.ifBlank { rawUi.name }
    val maskedWan = if (prefs.privacyMode) maskAddressForUi(rawUi.wanIpv4, true) else rawUi.wanIpv4
    val ui = rawUi.copy(name = displayName, wanIpv4 = maskedWan)

    LaunchedEffect(Unit) { state.refreshRouterDashboard(silent = true) }
    LaunchedEffect(state.mqttConnected) {
        while (isActive) {
            delay(if (state.mqttConnected) 15_000L else 20_000L)
            state.refreshRouterDashboard(silent = true)
        }
    }

    fun normalizedRouterUrl(raw: String): String {
        val value = raw.trim()
        return if (value.isBlank() || value.contains("://")) value else "https://$value"
    }

    fun openRouterUrl() {
        val lan = normalizedRouterUrl(prefs.routerLanUrl)
        val wan = normalizedRouterUrl(prefs.routerWanUrl)
        val target = if (prefs.favoriteNetworkMode == "wan") wan.ifBlank { lan } else lan.ifBlank { wan }
        if (target.isBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching { state.requestRouterDashboardRefresh() }
                .onFailure { state.refreshRouterDashboard(silent = false) }
            refreshing = false
        }
    }

    if (showRouterEditor) {
        RouterIdentityDialog(
            name = prefs.routerDisplayName.ifBlank { rawUi.name },
            lanUrl = prefs.routerLanUrl,
            wanUrl = prefs.routerWanUrl,
            onDismiss = { showRouterEditor = false },
            onSave = { name, lan, wan ->
                prefs.routerDisplayName = name
                prefs.routerLanUrl = normalizedRouterUrl(lan)
                prefs.routerWanUrl = normalizedRouterUrl(wan)
                showRouterEditor = false
            }
        )
    }

    if (showPortLegend) {
        PortLegendDialog(onDismiss = { showPortLegend = false })
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Brush.verticalGradient(listOf(Color(0xFFF4F8FF), Color(0xFFF8FBFF), Color.White)))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RouterStatusHeader(onBack)
        RouterHeroCard(
            ui = ui,
            refreshing = refreshing,
            onRefresh = ::refresh,
            onOpenDevices = onOpenDevices,
            onEdit = { showRouterEditor = true },
            onOpenRouter = ::openRouterUrl
        )
        RouterRealtimeCard(ui)
        RouterNetworkCard(ui)
        RouterPortsCard(ui, onShowLegend = { showPortLegend = true })
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RouterStatusHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.ArrowBack, "返回", Modifier.size(24.dp), tint = Color(0xFF0D2858))
        }
        Text(
            "路由器状态",
            Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 19.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF10264F)
        )
        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(24.dp), tint = Color(0xFF10264F))
        }
    }
}

@Composable
private fun RouterHeroCard(
    ui: RouterDashboardUi,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenDevices: () -> Unit,
    onEdit: () -> Unit,
    onOpenRouter: () -> Unit
) {
    RouterGlassCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.weight(1.05f).height(172.dp).clickable(onClick = onOpenRouter),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    drawCircle(Color(0x1673A7FF), radius = size.minDimension * .42f, center = c)
                    drawCircle(Color(0x1073A7FF), radius = size.minDimension * .32f, center = c)
                    drawArc(
                        color = Color(0x1173A7FF),
                        startAngle = -10f,
                        sweepAngle = 190f,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                        size = Size(size.width * .76f, size.height * .76f),
                        topLeft = Offset(size.width * .12f, size.height * .12f)
                    )
                }
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.router_skeuomorphic_v3),
                    contentDescription = "路由器",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Column(Modifier.weight(.95f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = LabV2.Green, modifier = Modifier.size(26.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ui.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF10264F),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.Edit,
                        null,
                        Modifier.size(16.dp).clickable(onClick = onEdit),
                        tint = Color(0xFF8B99B2)
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = if (ui.online) Color(0xFFD9F8E5) else Color(0xFFFFE5E8)
                    ) {
                        Text(
                            if (ui.online) "在线" else "离线",
                            Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = if (ui.online) LabV2.Green else LabV2.Red
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeedValue(Icons.Rounded.South, ui.downloadBps, "下载速率", LabV2.Green, Modifier.weight(1f))
                    SpeedValue(Icons.Rounded.North, ui.uploadBps, "上传速率", LabV2.Primary, Modifier.weight(1f))
                }
                if (ui.telemetryStale) {
                    Text("实时数据稍旧，等待 Agent 更新", fontSize = 8.6.sp, color = LabV2.Amber, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Surface(
            color = Color.White.copy(alpha = .72f),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroMetric(Icons.Rounded.DeviceThermostat, "温度", temperatureText(ui.temperature), LabV2.Green, Modifier.weight(1f))
                HeroDivider()
                HeroMetric(Icons.Rounded.Schedule, "运行时间", uptimeText(ui.uptimeSeconds), LabV2.Primary, Modifier.weight(1f))
                HeroDivider()
                HeroMetric(
                    Icons.Rounded.Group,
                    "连接数",
                    "${ui.onlineDevices} 台",
                    Color(0xFF22C9B5),
                    Modifier.weight(1f).clickable(onClick = onOpenDevices)
                )
                HeroDivider()
                Row(
                    Modifier.weight(1f).clickable(enabled = !refreshing, onClick = onRefresh),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LabV2.Primary)
                    else Icon(Icons.Rounded.Refresh, null, Modifier.size(22.dp), tint = LabV2.Primary)
                    Spacer(Modifier.width(5.dp))
                    Text(if (refreshing) "刷新中" else "刷新", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
                }
            }
        }
    }
}

@Composable
private fun SpeedValue(icon: ImageVector, bps: Long, label: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.2.dp, color), modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(17.dp), tint = color) }
            }
            Spacer(Modifier.width(5.dp))
            Text(formatBitRate(bps), fontSize = 14.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1)
        }
        Text(label, Modifier.padding(start = 33.dp), fontSize = 8.8.sp, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
    }
}

@Composable
private fun HeroMetric(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier) {
    Row(modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, fontSize = 8.8.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 12.4.sp, color = Color(0xFF10264F), fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun HeroDivider() = Box(Modifier.width(1.dp).height(38.dp).background(Color(0xFFDCE6F3)))

@Composable
private fun RouterRealtimeCard(ui: RouterDashboardUi) {
    RouterGlassCard {
        SectionHeader(Icons.Rounded.MonitorHeart, "实时状态", LabV2.Primary, ui.updatedAt)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RealtimeMetric(Icons.Rounded.Wifi, "2.4G", temperatureText(ui.temperature2g), LabV2.Green, ui.temperature2g / 100.0, Modifier.weight(1f))
            RealtimeMetric(Icons.Rounded.Wifi, "5G", temperatureText(ui.temperature5g), LabV2.Primary, ui.temperature5g / 100.0, Modifier.weight(1f))
            RealtimeMetric(Icons.Rounded.Memory, "CPU", percentText(ui.cpu), Color(0xFF7C3AED), ui.cpu / 100.0, Modifier.weight(1f))
            RealtimeMetric(Icons.Rounded.DeveloperBoard, "内存", percentText(ui.memory), Color(0xFFFF8A00), ui.memory / 100.0, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RealtimeMetric(icon: ImageVector, label: String, value: String, color: Color, progress: Double, modifier: Modifier) {
    val animated by animateFloatAsState(progress.toFloat().coerceIn(0f, 1f), tween(350), label = "routerMetric")
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = .82f),
        border = BorderStroke(1.dp, Color(0xFFEAF0F8))
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 8.8.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
            }
            Text(value, fontSize = 15.6.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F), maxLines = 1)
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(3.5.dp).clip(RoundedCornerShape(99.dp)),
                color = color,
                trackColor = Color(0xFFDCE6F3)
            )
        }
    }
}

@Composable
private fun RouterNetworkCard(ui: RouterDashboardUi) {
    var wanExpanded by rememberSaveable { mutableStateOf(false) }
    var lanExpanded by rememberSaveable { mutableStateOf(false) }
    var apExpanded by rememberSaveable { mutableStateOf(false) }
    RouterGlassCard {
        SectionHeader(Icons.Rounded.Public, "网络详情", LabV2.Primary)
        ExpandableNetworkRow(
            icon = Icons.Rounded.Dns,
            title = "WAN 信息",
            mainValue = ui.wanIpv4,
            middleLabel = "连接类型",
            middleValue = ui.connectionType,
            rightLabel = "DNS 服务器",
            rightValue = ui.dnsServers.ifEmpty { listOf("--") }.joinToString("\n"),
            expanded = wanExpanded,
            onToggle = { wanExpanded = !wanExpanded }
        ) {
            ExpandableInfoGrid(
                listOf(
                    "WAN IP" to ui.wanIpv4,
                    "网关" to ui.wanGateway,
                    "MTU" to ui.mtu,
                    "DNS" to ui.dnsServers.ifEmpty { listOf("--") }.joinToString(" / ")
                )
            )
        }
        HorizontalDivider(color = Color(0xFFE4EBF5))
        ExpandableNetworkRow(
            icon = Icons.Rounded.AccountTree,
            title = "网络配置",
            mainValue = ui.lanIpv4,
            middleLabel = "子网掩码",
            middleValue = ui.netmask,
            rightLabel = "VLAN ID",
            rightValue = ui.vlanId,
            expanded = lanExpanded,
            onToggle = { lanExpanded = !lanExpanded }
        ) {
            ExpandableInfoGrid(
                listOf(
                    "LAN IP" to ui.lanIpv4,
                    "子网掩码" to ui.netmask,
                    "DHCP 租期" to ui.dhcpLease,
                    "上网接口" to ui.uplink
                )
            )
        }
        HorizontalDivider(color = Color(0xFFE4EBF5))
        ExpandableNetworkRow(
            icon = Icons.Rounded.Wifi,
            title = "AP 信息",
            mainValue = ui.ssid,
            middleLabel = "设备名称 / 型号",
            middleValue = ui.model,
            rightLabel = "状态",
            rightValue = if (ui.apEnabled) "● ON" else "● OFF",
            rightColor = if (ui.apEnabled) LabV2.Green else LabV2.Red,
            expanded = apExpanded,
            onToggle = { apExpanded = !apExpanded }
        ) {
            ExpandableInfoGrid(
                listOf(
                    "网络名称 (SSID)" to ui.ssid,
                    "频段" to ui.bands,
                    "信道" to ui.channels,
                    "状态" to if (ui.apEnabled) "ON" else "OFF"
                )
            )
        }
        if (ui.detailsStale) {
            Text("网络详情超过 120 秒未更新", fontSize = 8.8.sp, color = LabV2.Amber, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ExpandableNetworkRow(
    icon: ImageVector,
    title: String,
    mainValue: String,
    middleLabel: String,
    middleValue: String,
    rightLabel: String,
    rightValue: String,
    rightColor: Color = Color(0xFF10264F),
    expanded: Boolean,
    onToggle: () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.04f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp), tint = LabV2.Primary)
                    Spacer(Modifier.width(6.dp))
                    Text(title, fontSize = 12.4.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
                }
                Text(mainValue, Modifier.padding(start = 24.dp, top = 3.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF10264F), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            NetworkCell(middleLabel, middleValue, Modifier.weight(.9f))
            NetworkCell(rightLabel, rightValue, Modifier.weight(.9f), rightColor)
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                null,
                Modifier.size(22.dp).rotate(if (expanded) 180f else 0f),
                tint = Color(0xFF60728F)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            expandedContent()
        }
    }
}

@Composable
private fun ExpandableInfoGrid(items: List<Pair<String, String>>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF8FBFF)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 8.8.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
                        Text(value, fontSize = 10.2.sp, lineHeight = 13.sp, color = Color(0xFF10264F), fontWeight = FontWeight.Black)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NetworkCell(label: String, value: String, modifier: Modifier, valueColor: Color = Color(0xFF10264F)) {
    Column(modifier.padding(horizontal = 6.dp)) {
        Text(label, fontSize = 8.8.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 10.1.sp, lineHeight = 13.sp, color = valueColor, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RouterPortsCard(ui: RouterDashboardUi, onShowLegend: () -> Unit) {
    RouterGlassCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("端口状态", Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
            Icon(Icons.Rounded.HelpOutline, null, Modifier.size(22.dp).clickable(onClick = onShowLegend), tint = Color(0xFF8392AA))
        }
        val ports = if (ui.ports.isEmpty()) (1..9).map { RouterPortUi("LAN$it", false, false, false, false) } else ui.ports.sortedBy { portSortKey(it.name) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ports.forEach { RouterPortItem(it) }
        }
        HorizontalDivider(color = Color(0xFFE4EBF5))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("WAN口速率：", fontSize = 12.4.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
            Icon(Icons.Rounded.North, null, Modifier.size(16.dp), tint = LabV2.Primary)
            Text(formatBitRate(ui.uploadBps), fontSize = 12.4.sp, fontWeight = FontWeight.Black, color = LabV2.Primary)
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.South, null, Modifier.size(16.dp), tint = Color(0xFF7C3AED))
            Text(formatBitRate(ui.downloadBps), fontSize = 12.4.sp, fontWeight = FontWeight.Black, color = Color(0xFF7C3AED))
        }
    }
}

@Composable
private fun RouterPortItem(port: RouterPortUi) {
    val color = when {
        !port.connected -> Color(0xFF2F3540)
        port.isWan -> Color(0xFF7651E8)
        port.isGame -> Color(0xFFFFB400)
        port.isHybrid -> Color(0xFF0EA5E9)
        else -> Color(0xFF05C858)
    }
    Column(Modifier.widthIn(min = 52.dp).width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EthernetPortIcon(color, port.connected)
        Spacer(Modifier.height(4.dp))
        Text(
            port.name.replace("/", "/\n"),
            fontSize = 8.6.sp,
            lineHeight = 10.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF10264F),
            minLines = 2,
            maxLines = 2
        )
        if (port.speedLabel.isNotBlank() && port.connected) {
            Spacer(Modifier.height(2.dp))
            Text(port.speedLabel, fontSize = 7.8.sp, fontWeight = FontWeight.Black, color = color)
        }
    }
}

@Composable
private fun EthernetPortIcon(color: Color, active: Boolean) {
    Canvas(Modifier.size(width = 36.dp, height = 34.dp)) {
        val path = Path().apply {
            moveTo(size.width * .14f, size.height * .18f)
            lineTo(size.width * .34f, size.height * .18f)
            lineTo(size.width * .40f, size.height * .06f)
            lineTo(size.width * .60f, size.height * .06f)
            lineTo(size.width * .66f, size.height * .18f)
            lineTo(size.width * .86f, size.height * .18f)
            lineTo(size.width * .92f, size.height * .88f)
            lineTo(size.width * .08f, size.height * .88f)
            close()
        }
        drawPath(path, color)
        if (active) {
            repeat(4) { index ->
                drawRect(
                    Color.White.copy(alpha = .72f),
                    topLeft = Offset(size.width * (.30f + index * .11f), size.height * .48f),
                    size = Size(size.width * .055f, size.height * .18f)
                )
            }
        }
    }
}

@Composable
private fun PortLegendDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("端口状态说明", fontWeight = FontWeight.Black, fontSize = 18.sp, color = LabV2.Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                PortLegendRow(Color(0xFF2F3540), "黑色", "未连接 / 空闲端口")
                PortLegendRow(Color(0xFFFFB400), "黄色", "GAME 口或低速连接")
                PortLegendRow(Color(0xFF0EA5E9), "蓝色", "双WAN / 聚合口 / 特殊复用口")
                PortLegendRow(Color(0xFF7651E8), "紫色", "WAN 主口")
                PortLegendRow(Color(0xFF05C858), "绿色", "普通 LAN 已连接")
            }
        },
        confirmButton = { Button(onClick = onDismiss, shape = RoundedCornerShape(16.dp)) { Text("知道了", fontWeight = FontWeight.Black) } },
        shape = RoundedCornerShape(28.dp),
        containerColor = LAB_POPUP_SURFACE,
        tonalElevation = 0.dp
    )
}

@Composable
private fun PortLegendRow(color: Color, label: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.width(42.dp), fontSize = 11.2.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
        Text(desc, fontSize = 11.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RouterIdentityDialog(
    name: String,
    lanUrl: String,
    wanUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var displayName by remember(name) { mutableStateOf(name) }
    var lan by remember(lanUrl) { mutableStateOf(lanUrl) }
    var wan by remember(wanUrl) { mutableStateOf(wanUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("路由器备注与地址", fontWeight = FontWeight.Black, fontSize = 19.sp, color = LabV2.Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                CompactTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    placeholder = "例如：BE72",
                    leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp), tint = LabV2.Primary) },
                    modifier = Modifier.fillMaxWidth()
                )
                CompactTextField(
                    value = lan,
                    onValueChange = { lan = it },
                    placeholder = "192.168.5.1",
                    leadingIcon = { Icon(Icons.Rounded.Router, null, Modifier.size(16.dp), tint = LabV2.Primary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                CompactTextField(
                    value = wan,
                    onValueChange = { wan = it },
                    placeholder = "example.com",
                    leadingIcon = { Icon(Icons.Rounded.Public, null, Modifier.size(16.dp), tint = LabV2.Cyan) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("点击路由器图片或名称时，会按收藏夹的内外网切换设置优先打开对应地址。", fontSize = 10.4.sp, lineHeight = 14.sp, color = LabV2.InkMuted)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(displayName.trim(), lan.trim(), wan.trim()) }, shape = RoundedCornerShape(16.dp)) {
                Text("保存", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(16.dp)) { Text("取消", fontWeight = FontWeight.Bold) } },
        shape = RoundedCornerShape(28.dp),
        containerColor = LAB_POPUP_SURFACE,
        tonalElevation = 0.dp
    )
}

@Composable
private fun RouterGlassCard(contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(25.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(5.dp, shape, clip = false),
        shape = shape,
        color = Color.White.copy(alpha = .92f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .95f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, color: Color, trailing: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(25.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(15.dp), tint = Color.White) }
        }
        Spacer(Modifier.width(6.dp))
        Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
        if (trailing.isNotBlank()) Text("更新于 ${timeOnly(trailing)}", fontSize = 8.8.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp), tint = Color(0xFF8290A7))
    }
}

private fun timeOnly(value: String): String = value.substringAfter('T', value).substringAfter(' ', value).take(8).ifBlank { "--" }
private fun temperatureText(value: Double): String = if (value <= 0.0) "--" else "${if (value % 1.0 == 0.0) value.roundToInt() else String.format("%.1f", value)}°C"
private fun percentText(value: Double): String = if (value < 0.0) "--" else "${value.roundToInt()}%"
private fun uptimeText(seconds: Long): String = when {
    seconds <= 0 -> "--"
    seconds >= 86_400 -> "${seconds / 86_400} 天"
    seconds >= 3_600 -> "${seconds / 3_600} 小时"
    else -> "${seconds / 60} 分"
}
private fun formatBitRate(bps: Long): String = when {
    bps <= 0 -> "0 Kbps"
    bps < 1_000 -> "$bps bps"
    bps < 1_000_000 -> String.format("%.2f Kbps", bps / 1_000.0)
    bps < 1_000_000_000 -> String.format("%.2f Mbps", bps / 1_000_000.0)
    else -> String.format("%.2f Gbps", bps / 1_000_000_000.0)
}
private fun portSortKey(name: String): Int = when {
    name.equals("WAN", true) -> 5
    name.contains("LAN6", true) -> 1
    name.contains("LAN7", true) -> 2
    name.contains("LAN8", true) -> 3
    name.contains("LAN9", true) -> 4
    name.contains("LAN2", true) -> 6
    name.contains("LAN3", true) -> 7
    name.contains("LAN4", true) -> 8
    name.contains("LAN5", true) -> 9
    else -> 99
}
