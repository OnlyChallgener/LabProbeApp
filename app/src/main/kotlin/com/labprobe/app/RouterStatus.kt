package com.labprobe.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val status: Boolean,
    val isWan: Boolean,
    val isGame: Boolean
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
    val connectionType: String = "--",
    val dnsServers: List<String> = emptyList(),
    val lanIpv4: String = "--",
    val netmask: String = "--",
    val vlanId: String = "--",
    val ssid: String = "--",
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
    for (i in 0 until radios.length()) {
        val item = radios.optJSONObject(i) ?: continue
        if (item.optBoolean("enabled", item.optString("enabled").equals("true", true))) radioEnabled = true
    }

    val ports = buildList {
        val list = details.array("ports")
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val panelName = item.optString("panel_name").ifBlank { item.optString("name") }.ifBlank { "端口${i + 1}" }
            add(
                RouterPortUi(
                    name = panelName,
                    status = item.optString("status").equals("on", true) || item.optBoolean("status", false),
                    isWan = panelName.equals("WAN", true) || item.optString("name").equals("WAN", true),
                    isGame = panelName.contains("GAME", true)
                )
            )
        }
    }

    val netmask = recursiveString(network, setOf("netmask", "subnetmask", "mask")) { it.count { ch -> ch == '.' } == 3 }
    val vlan = recursiveString(network, setOf("vlanid", "vlan_id", "vid")) { value ->
        value.toIntOrNull()?.let { it in 0..4094 } == true
    }
    val lanIp = lan.optString("ipv4").ifBlank {
        recursiveString(network, setOf("lanip", "lan_ip", "ipaddr", "ipaddress")) { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    }

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
        connectionType = connectionType(network),
        dnsServers = recursiveDns(network),
        lanIpv4 = lanIp.ifBlank { "--" },
        netmask = netmask.ifBlank { "--" },
        vlanId = vlan.ifBlank { "--" },
        ssid = primarySsid.ifBlank { "--" },
        apEnabled = radioEnabled,
        ports = ports,
        updatedAt = root.optString("telemetryAt").ifBlank { root.optString("receivedAt") }.ifBlank { "--" },
        telemetryStale = root.optBoolean("telemetryStale", true),
        detailsStale = root.optBoolean("detailsStale", true),
        refreshCompletedNonce = root.optLong("refreshCompletedNonce", 0)
    )
}

@Composable
fun RouterStatusScreen(prefs: AppPrefs, state: AppState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val rawUi = remember(state.routerDashboard) { parseRouterDashboard(state.routerDashboard) }
    val ui = remember(rawUi, prefs.privacyMode) {
        if (prefs.privacyMode) rawUi.copy(wanIpv4 = maskAddressForUi(rawUi.wanIpv4, true)) else rawUi
    }

    LaunchedEffect(Unit) { state.refreshRouterDashboard(silent = true) }
    LaunchedEffect(state.mqttConnected) {
        while (isActive) {
            delay(if (state.mqttConnected) 15_000L else 5_000L)
            state.refreshRouterDashboard(silent = true)
        }
    }

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching { state.requestRouterDashboardRefresh() }
            refreshing = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Brush.verticalGradient(listOf(Color(0xFFF4F8FF), Color(0xFFF9FCFF), Color.White)))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RouterStatusHeader(onBack)
        RouterHeroCard(ui, refreshing, ::refresh)
        RouterRealtimeCard(ui)
        RouterNetworkCard(ui)
        RouterPortsCard(ui)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RouterStatusHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Rounded.ArrowBack, "返回", Modifier.size(27.dp), tint = Color(0xFF0D2858))
        }
        Text("路由器状态", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
        IconButton(onClick = {}, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(27.dp), tint = Color(0xFF10264F))
        }
    }
}

@Composable
private fun RouterHeroCard(ui: RouterDashboardUi, refreshing: Boolean, onRefresh: () -> Unit) {
    RouterGlassCard(contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1.02f).height(190.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color(0x1A73A7FF), radius = size.minDimension * .46f, center = center)
                    drawCircle(Color(0x1073A7FF), radius = size.minDimension * .36f, center = center)
                }
                Image(
                    painter = painterResource(R.drawable.router_skeuomorphic_v3),
                    contentDescription = "路由器",
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Column(Modifier.weight(.98f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = LabV2.Green, modifier = Modifier.size(28.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp), tint = Color.White) }
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(ui.name, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp), tint = Color(0xFF8B99B2))
                    Spacer(Modifier.width(7.dp))
                    Surface(shape = RoundedCornerShape(99.dp), color = if (ui.online) Color(0xFFD9F8E5) else Color(0xFFFFE5E8)) {
                        Text(if (ui.online) "在线" else "离线", Modifier.padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.Black, color = if (ui.online) LabV2.Green else LabV2.Red)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeedValue(Icons.Rounded.South, ui.downloadBps, "下载速率", LabV2.Green, Modifier.weight(1f))
                    SpeedValue(Icons.Rounded.North, ui.uploadBps, "上传速率", LabV2.Primary, Modifier.weight(1f))
                }
                if (ui.telemetryStale) Text("实时数据已过期，正在等待 Agent 更新", fontSize = 9.5.sp, color = LabV2.Amber, fontWeight = FontWeight.SemiBold)
            }
        }
        Surface(color = Color.White.copy(alpha = .72f), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroMetric(Icons.Rounded.DeviceThermostat, "温度", temperatureText(ui.temperature), LabV2.Green, Modifier.weight(1f))
                HeroDivider()
                HeroMetric(Icons.Rounded.Schedule, "运行时间", uptimeText(ui.uptimeSeconds), LabV2.Primary, Modifier.weight(1f))
                HeroDivider()
                HeroMetric(Icons.Rounded.Group, "连接数", "${ui.onlineDevices} 台", Color(0xFF22C9B5), Modifier.weight(1f))
                HeroDivider()
                Row(Modifier.weight(1f).clickable(enabled = !refreshing, onClick = onRefresh), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LabV2.Primary)
                    else Icon(Icons.Rounded.Refresh, null, Modifier.size(26.dp), tint = LabV2.Primary)
                    Spacer(Modifier.width(6.dp))
                    Text(if (refreshing) "刷新中" else "刷新", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
                }
            }
        }
    }
}

@Composable
private fun SpeedValue(icon: ImageVector, bps: Long, label: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.5.dp, color), modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = color) }
            }
            Spacer(Modifier.width(6.dp))
            Text(formatBitRate(bps), fontSize = 16.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1)
        }
        Text(label, Modifier.padding(start = 41.dp), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
    }
}

@Composable
private fun HeroMetric(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier) {
    Row(modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(25.dp), tint = color)
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, fontSize = 9.5.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 13.sp, color = Color(0xFF10264F), fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable private fun HeroDivider() = Box(Modifier.width(1.dp).height(42.dp).background(Color(0xFFDCE6F3)))

@Composable
private fun RouterRealtimeCard(ui: RouterDashboardUi) {
    RouterGlassCard {
        SectionHeader(Icons.Rounded.MonitorHeart, "实时状态", LabV2.Primary, ui.updatedAt)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RealtimeMetric(Icons.Rounded.Wifi, "2.4G 温度", temperatureText(ui.temperature2g), LabV2.Green, ui.temperature2g / 100.0, Modifier.weight(1f))
            RealtimeMetric(Icons.Rounded.Wifi, "5G 温度", temperatureText(ui.temperature5g), LabV2.Primary, ui.temperature5g / 100.0, Modifier.weight(1f))
            RealtimeMetric(Icons.Rounded.Memory, "CPU", percentText(ui.cpu), Color(0xFF7C3AED), ui.cpu / 100.0, Modifier.weight(1f))
            RealtimeMetric(Icons.Rounded.DeveloperBoard, "内存", percentText(ui.memory), Color(0xFFFF8A00), ui.memory / 100.0, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RealtimeMetric(icon: ImageVector, label: String, value: String, color: Color, progress: Double, modifier: Modifier) {
    val animated by animateFloatAsState(progress.toFloat().coerceIn(0f, 1f), tween(350), label = "routerMetric")
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .82f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEAF0F8))) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(22.dp), tint = color)
                Spacer(Modifier.width(5.dp))
                Text(label, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
            }
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F), maxLines = 1)
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)), color = color, trackColor = Color(0xFFDCE6F3))
        }
    }
}

@Composable
private fun RouterNetworkCard(ui: RouterDashboardUi) {
    RouterGlassCard {
        SectionHeader(Icons.Rounded.Public, "网络详情", LabV2.Primary)
        NetworkRow(Icons.Rounded.Dns, "WAN 信息", ui.wanIpv4, "连接类型", ui.connectionType, "DNS 服务器", ui.dnsServers.ifEmpty { listOf("--") }.joinToString("\n"))
        HorizontalDivider(color = Color(0xFFE4EBF5))
        NetworkRow(Icons.Rounded.AccountTree, "网络配置", ui.lanIpv4, "子网掩码", ui.netmask, "VLAN ID", ui.vlanId)
        HorizontalDivider(color = Color(0xFFE4EBF5))
        NetworkRow(Icons.Rounded.Wifi, "AP 信息", ui.ssid, "设备名称 / 型号", ui.model, "状态", if (ui.apEnabled) "● ON" else "● OFF", if (ui.apEnabled) LabV2.Green else LabV2.Red)
        if (ui.detailsStale) Text("网络详情超过 120 秒未更新", fontSize = 9.5.sp, color = LabV2.Amber, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NetworkRow(icon: ImageVector, title: String, mainValue: String, middleLabel: String, middleValue: String, rightLabel: String, rightValue: String, rightColor: Color = Color(0xFF10264F)) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.05f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(21.dp), tint = LabV2.Primary)
                Spacer(Modifier.width(7.dp))
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
            }
            Text(mainValue, Modifier.padding(start = 28.dp, top = 3.dp), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF10264F), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        NetworkCell(middleLabel, middleValue, Modifier.weight(.9f))
        NetworkCell(rightLabel, rightValue, Modifier.weight(.9f), rightColor)
        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(21.dp), tint = Color(0xFF60728F))
    }
}

@Composable
private fun NetworkCell(label: String, value: String, modifier: Modifier, valueColor: Color = Color(0xFF10264F)) {
    Column(modifier.padding(horizontal = 8.dp)) {
        Text(label, fontSize = 9.5.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 10.8.sp, lineHeight = 14.sp, color = valueColor, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RouterPortsCard(ui: RouterDashboardUi) {
    RouterGlassCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("端口状态", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
            Icon(Icons.Rounded.HelpOutline, null, Modifier.size(24.dp), tint = Color(0xFF8392AA))
        }
        val ports = if (ui.ports.isEmpty()) (1..9).map { RouterPortUi("LAN$it", false, false, false) } else ui.ports.sortedBy { portSortKey(it.name) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ports.forEach { RouterPortItem(it) }
        }
        HorizontalDivider(color = Color(0xFFE4EBF5))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("WAN口速率：", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
            Icon(Icons.Rounded.North, null, Modifier.size(18.dp), tint = LabV2.Primary)
            Text(formatBitRate(ui.uploadBps), fontSize = 13.sp, fontWeight = FontWeight.Black, color = LabV2.Primary)
            Spacer(Modifier.width(16.dp))
            Icon(Icons.Rounded.South, null, Modifier.size(18.dp), tint = Color(0xFF7C3AED))
            Text(formatBitRate(ui.downloadBps), fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF7C3AED))
        }
    }
}

@Composable
private fun RouterPortItem(port: RouterPortUi) {
    val color = when {
        !port.status -> Color(0xFF2F3540)
        port.isGame -> Color(0xFFFFB400)
        port.isWan -> Color(0xFF7651E8)
        else -> Color(0xFF05C858)
    }
    Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EthernetPortIcon(color, port.status)
        Spacer(Modifier.height(5.dp))
        Text(
            port.name.replace("/", "/\n"),
            fontSize = 9.5.sp,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF10264F),
            minLines = 2,
            maxLines = 2
        )
    }
}

@Composable
private fun EthernetPortIcon(color: Color, active: Boolean) {
    Canvas(Modifier.size(width = 45.dp, height = 42.dp)) {
        val path = Path().apply {
            moveTo(size.width * .14f, size.height * .15f)
            lineTo(size.width * .34f, size.height * .15f)
            lineTo(size.width * .40f, size.height * .04f)
            lineTo(size.width * .60f, size.height * .04f)
            lineTo(size.width * .66f, size.height * .15f)
            lineTo(size.width * .86f, size.height * .15f)
            lineTo(size.width * .92f, size.height * .88f)
            lineTo(size.width * .08f, size.height * .88f)
            close()
        }
        drawPath(path, color)
        if (active) {
            repeat(4) { index ->
                drawRect(Color.White.copy(alpha = .72f), topLeft = Offset(size.width * (.30f + index * .11f), size.height * .46f), size = Size(size.width * .055f, size.height * .20f))
            }
        }
    }
}

@Composable
private fun RouterGlassCard(contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(25.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(5.dp, shape, clip = false),
        shape = shape,
        color = Color.White.copy(alpha = .92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .95f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, color: Color, trailing: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(27.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(17.dp), tint = Color.White) } }
        Spacer(Modifier.width(7.dp))
        Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Black, color = Color(0xFF10264F))
        if (trailing.isNotBlank()) Text("更新于 ${timeOnly(trailing)}", fontSize = 9.5.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.Refresh, null, Modifier.size(19.dp), tint = Color(0xFF8290A7))
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
