package com.labprobe.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    val wanNetmask: String = "--",
    val connectionType: String = "--",
    val mtu: String = "--",
    val dnsServers: List<String> = emptyList(),
    val wanOperator: String = "--",
    val wanInterfaceDisplay: String = "WAN",
    val lanIpv4: String = "--",
    val lanMac: String = "--",
    val broadbandUser: String = "--",
    val broadbandPassword: String = "--",
    val netmask: String = "--",
    val vlanId: String = "--",
    val dhcpLease: String = "--",
    val uplink: String = "--",
    val apName: String = "--",
    val apHostName: String = "--",
    val apManagementIp: String = "--",
    val apSoftware: String = "--",
    val apStations: String = "--",
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


private fun JSONObject.stringList(name: String): List<String> {
    val array = optJSONArray(name)
    if (array != null) {
        return (0 until array.length())
            .map { array.optString(it).trim() }
            .filter { it.isNotBlank() && it != "null" }
            .distinct()
    }
    return optString(name)
        .split(',', ';')
        .map(String::trim)
        .filter { it.isNotBlank() && it != "null" }
        .distinct()
}

private fun protocolLabel(value: String): String = when (value.trim().lowercase()) {
    "pppoe" -> "PPPoE"
    "dhcp", "dynamic", "auto" -> "DHCP"
    "static", "manual" -> "静态 IP"
    else -> value.trim().ifBlank { "--" }
}

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

private fun parseRouterDashboard(root: JSONObject?, credentials: JSONObject? = null): RouterDashboardUi {
    if (root == null) return RouterDashboardUi()
    val telemetry = root.obj("telemetry")
    val wanTelemetry = telemetry.obj("wan")
    val details = root.obj("details")
    val identity = details.obj("identity")
    val wan = details.obj("wan")
    val lan = details.obj("lan")
    val ap = details.obj("ap")
    val wireless = details.obj("wireless")
    val network = details.obj("network")

    // Backward compatibility for dashboards produced before ipinfo/ap_list were added.
    val ssids = wireless.array("ssidList")
    var legacySsid = ""
    for (i in 0 until ssids.length()) {
        val item = ssids.optJSONObject(i) ?: continue
        if (!item.optBoolean("enabled", item.optString("enabled").equals("true", true))) continue
        legacySsid = item.optString("ssidName").trim()
        if (legacySsid.isNotBlank()) break
    }
    val radios = wireless.array("radioList")
    var legacyRadioEnabled = false
    val legacyChannels = mutableListOf<String>()
    val legacyBands = mutableListOf<String>()
    for (i in 0 until radios.length()) {
        val item = radios.optJSONObject(i) ?: continue
        if (item.optBoolean("enabled", item.optString("enabled").equals("true", true))) legacyRadioEnabled = true
        item.optString("band").ifBlank { item.optString("name") }.ifBlank { item.optString("radio") }
            .takeIf(String::isNotBlank)?.let(legacyBands::add)
        item.optString("channel").ifBlank { item.optString("channelText") }
            .takeIf(String::isNotBlank)?.let(legacyChannels::add)
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

    val fallbackNetmask = recursiveString(network, setOf("netmask", "subnetmask", "mask")) { it.count { ch -> ch == '.' } == 3 }
    val fallbackVlan = recursiveString(network, setOf("vlanid", "vlan_id", "vid")) { value -> value.toIntOrNull()?.let { it in 0..4094 } == true }
    val fallbackLanIp = recursiveString(network, setOf("lanip", "lan_ip", "ipaddr", "ipaddress")) {
        it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")
    }
    val fallbackGateway = recursiveString(network, setOf("gateway", "gw", "gatewayip")) { isIpText(it) }
    val fallbackMtu = recursiveString(network, setOf("mtu")) { it.toIntOrNull() != null }
    val fallbackLease = recursiveString(network, setOf("leasetime", "lease", "lease_time", "dhcp_lease", "leaseText"))
    val fallbackUplink = recursiveString(network, setOf("wanif", "wan_if", "interface", "uplink", "upstream"))

    val wanDns = wan.stringList("dnsServers").ifEmpty { recursiveDns(network) }
    val wanOperator = wan.optString("operator").ifBlank { "--" }
    val wanInterfaceDisplay = wan.optString("interfaceDisplay").ifBlank { "WAN" }
    val lanMac = credentials?.optString("lanMac").orEmpty().ifBlank { lan.optString("mac") }.ifBlank { "--" }
    val broadbandUser = credentials?.optString("username").orEmpty().ifBlank { "--" }
    val broadbandPassword = credentials?.optString("password").orEmpty().ifBlank { "--" }
    val apBands = ap.stringList("bands").ifEmpty { legacyBands.distinct() }
    val apChannels = ap.stringList("channels").ifEmpty { legacyChannels.distinct() }
    val apStatus = ap.optString("status").trim()
    val apOnline = when {
        apStatus.isNotBlank() -> apStatus.equals("ON", true) || apStatus.equals("online", true) || apStatus == "1"
        else -> legacyRadioEnabled
    }
    val model = ap.optString("model").ifBlank { identity.optString("model") }.ifBlank { "--" }
    val hostName = ap.optString("hostName").ifBlank { identity.optString("hostname") }.ifBlank { "--" }
    val displayName = identity.optString("hostname").ifBlank { hostName }.ifBlank { root.optString("router") }.ifBlank { "路由器" }

    return RouterDashboardUi(
        name = displayName,
        model = model,
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
        // WAN must use dev_sta ipinfo normalized by LabRelay.
        wanIpv4 = wan.optString("ipv4").ifBlank { "--" },
        wanGateway = wan.optString("gateway").ifBlank { fallbackGateway }.ifBlank { "--" },
        wanNetmask = wan.optString("netmask").ifBlank { "--" },
        connectionType = protocolLabel(wan.optString("proto").ifBlank { connectionType(network) }),
        mtu = wan.optString("mtu").ifBlank { fallbackMtu }.ifBlank { "--" },
        dnsServers = wanDns,
        wanOperator = wanOperator,
        wanInterfaceDisplay = wanInterfaceDisplay,
        lanIpv4 = lan.optString("ipv4").ifBlank { fallbackLanIp }.ifBlank { "--" },
        lanMac = lanMac,
        broadbandUser = broadbandUser,
        broadbandPassword = broadbandPassword,
        netmask = lan.optString("netmask").ifBlank { fallbackNetmask }.ifBlank { "--" },
        vlanId = lan.optString("vlanId").ifBlank { fallbackVlan }.ifBlank { "--" },
        dhcpLease = lan.optString("dhcpLease").ifBlank { fallbackLease }.ifBlank { "--" },
        uplink = lan.optString("uplink").ifBlank { fallbackUplink }.ifBlank { "--" },
        // AP must use dev_sta ap_list normalized by LabRelay.
        apName = ap.optString("networkName").ifBlank { legacySsid }.ifBlank { "--" },
        apHostName = hostName,
        apManagementIp = ap.optString("managementIp").ifBlank { "--" },
        apSoftware = ap.optString("software").ifBlank { "--" },
        apStations = ap.optString("stationCount").ifBlank { "--" },
        bands = apBands.joinToString(" / ").ifBlank { "--" },
        channels = apChannels.joinToString(" / ").ifBlank { "--" },
        apEnabled = apOnline,
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

    val rawUi = remember(state.routerDashboard, state.routerCredentials) { parseRouterDashboard(state.routerDashboard, state.routerCredentials) }
    val displayName = prefs.routerDisplayName.ifBlank { rawUi.name }
    val maskedWan = if (prefs.privacyMode) maskAddressForUi(rawUi.wanIpv4, true) else rawUi.wanIpv4
    val ui = rawUi.copy(name = displayName, wanIpv4 = maskedWan)

    LaunchedEffect(Unit) {
        state.refreshRouterDashboard(silent = true)
        runCatching { state.requestRouterCredentialsRefresh() }
    }
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
            runCatching { state.requestRouterCredentialsRefresh() }
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
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.weight(.98f).height(160.dp).clickable(onClick = onOpenRouter),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    drawCircle(Color(0x1673A7FF), radius = size.minDimension * .43f, center = c)
                    drawCircle(Color(0x0F73A7FF), radius = size.minDimension * .33f, center = c)
                    drawCircle(Color(0x0873A7FF), radius = size.minDimension * .24f, center = c)
                    drawArc(
                        color = Color(0x2073A7FF),
                        startAngle = -28f,
                        sweepAngle = 112f,
                        useCenter = false,
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                        size = Size(size.width * .80f, size.height * .80f),
                        topLeft = Offset(size.width * .10f, size.height * .10f)
                    )
                    drawArc(
                        color = Color(0x1438D9C5),
                        startAngle = 146f,
                        sweepAngle = 76f,
                        useCenter = false,
                        style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
                        size = Size(size.width * .66f, size.height * .66f),
                        topLeft = Offset(size.width * .17f, size.height * .17f)
                    )
                    drawLine(Color(0x1273A7FF), Offset(size.width * .08f, size.height * .70f), Offset(size.width * .36f, size.height * .89f), 1.1.dp.toPx())
                    drawLine(Color(0x0D73A7FF), Offset(size.width * .70f, size.height * .08f), Offset(size.width * .93f, size.height * .27f), 1.1.dp.toPx())
                }
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.router_skeuomorphic_v3),
                    contentDescription = "路由器",
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Column(Modifier.weight(1.02f).padding(start = 1.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = LabV2.Green, modifier = Modifier.size(25.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Bolt, null, Modifier.size(15.dp), tint = Color.White)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        ui.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF10264F),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Rounded.Edit,
                        null,
                        Modifier.size(15.dp).clickable(onClick = onEdit),
                        tint = Color(0xFF8B99B2)
                    )
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = if (ui.online) Color(0xFFD9F8E5) else Color(0xFFFFE5E8)
                    ) {
                        Text(
                            if (ui.online) "在线" else "离线",
                            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            fontSize = 9.6.sp,
                            fontWeight = FontWeight.Black,
                            color = if (ui.online) LabV2.Green else LabV2.Red
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeedValue(Icons.Rounded.South, ui.downloadBps, "下载速率", LabV2.Green, Modifier.weight(1f))
                    SpeedValue(Icons.Rounded.North, ui.uploadBps, "上传速率", LabV2.Primary, Modifier.weight(1f))
                }
                if (ui.telemetryStale) {
                    Text("实时数据稍旧，等待 Agent 更新", fontSize = 8.6.sp, color = LabV2.Amber, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFF8FBFF), Color(0xFFF2F7FF), Color(0xFFF8FBFF))))
                .border(1.dp, Color(0xFFE2EAF5), RoundedCornerShape(21.dp))
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

private data class RouterInfoItem(
    val label: String,
    val value: String,
    val copyValue: String = value,
    val valueColor: Color = Color(0xFF10264F)
)

@Composable
private fun RouterNetworkCard(ui: RouterDashboardUi) {
    var wanExpanded by rememberSaveable { mutableStateOf(false) }
    var lanExpanded by rememberSaveable { mutableStateOf(false) }
    var apExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    RouterGlassCard {
        SectionHeader(Icons.Rounded.Public, "网络详情", LabV2.Primary)
        ExpandableNetworkRow(
            icon = Icons.Rounded.Dns,
            title = "WAN 信息",
            mainValue = ui.wanIpv4,
            middleLabel = "连接类型",
            middleValue = ui.connectionType,
            rightLabel = "运营商",
            rightValue = ui.wanOperator,
            expanded = wanExpanded,
            onToggle = { wanExpanded = !wanExpanded }
        ) {
            RouterInfoGrid(
                context = context,
                items = listOf(
                    RouterInfoItem("网关", ui.wanGateway),
                    RouterInfoItem("子网掩码", ui.wanNetmask),
                    RouterInfoItem("MTU", ui.mtu),
                    RouterInfoItem("宽带接口", ui.wanInterfaceDisplay)
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
            RouterInfoGrid(
                context = context,
                items = listOf(
                    RouterInfoItem("LAN IP", ui.lanIpv4),
                    RouterInfoItem("MAC", ui.lanMac),
                    RouterInfoItem("宽带账号", ui.broadbandUser),
                    RouterInfoItem(
                        "宽带密码",
                        if (ui.broadbandPassword == "--") "--" else "••••••••",
                        copyValue = ui.broadbandPassword
                    )
                )
            )
        }
        HorizontalDivider(color = Color(0xFFE4EBF5))
        ExpandableNetworkRow(
            icon = Icons.Rounded.Wifi,
            title = "AP 信息",
            mainValue = ui.apName,
            middleLabel = "主机名 / 型号",
            middleValue = listOf(ui.apHostName, ui.model).filter { it != "--" }.distinct().joinToString(" / ").ifBlank { "--" },
            rightLabel = "状态",
            rightValue = if (ui.apEnabled) "● ON" else "● OFF",
            rightColor = if (ui.apEnabled) LabV2.Green else LabV2.Red,
            expanded = apExpanded,
            onToggle = { apExpanded = !apExpanded }
        ) {
            RouterInfoGrid(
                context = context,
                items = listOf(
                    RouterInfoItem("管理 IP", ui.apManagementIp),
                    RouterInfoItem("频段", ui.bands),
                    RouterInfoItem("信道", ui.channels),
                    RouterInfoItem("软件版本", ui.apSoftware)
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
private fun RouterInfoGrid(context: android.content.Context, items: List<RouterInfoItem>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color(0xFFF8FBFF)).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { item ->
                    val canCopy = item.copyValue.isNotBlank() && item.copyValue != "--"
                    Surface(
                        modifier = Modifier.weight(1f).clickable(enabled = canCopy) { copy(context, item.copyValue) },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = .88f),
                        border = BorderStroke(1.dp, Color(0xFFE7EEF7))
                    ) {
                        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.label, fontSize = 8.5.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(item.value, fontSize = 10.2.sp, lineHeight = 12.5.sp, color = item.valueColor, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (canCopy) Text("点按复制", fontSize = 7.3.sp, color = LabV2.Primary.copy(alpha = .66f), fontWeight = FontWeight.SemiBold)
                        }
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
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ports.chunked(5).forEach { rowPorts ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    rowPorts.forEach { RouterPortItem(it) }
                    repeat(5 - rowPorts.size) { Spacer(Modifier.width(56.dp)) }
                }
            }
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
        !port.connected -> Color(0xFF9AA5B5)
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
            lineTo(size.width * .39f, size.height * .18f)
            lineTo(size.width * .43f, size.height * .09f)
            lineTo(size.width * .57f, size.height * .09f)
            lineTo(size.width * .61f, size.height * .18f)
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
                    topLeft = Offset(size.width * (.32f + index * .105f), size.height * .50f),
                    size = Size(size.width * .046f, size.height * .15f)
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
                PortLegendRow(Color(0xFF9AA5B5), "灰色", "未连接 / 空闲端口")
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFFFDFEFF),
                        Color(0xFFF7FAFF),
                        Color(0xFFFBFDFF)
                    )
                )
            )
            .border(1.dp, Color(0xFFE7EEF7), shape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0x0B4F8CFF), radius = size.minDimension * .42f, center = Offset(size.width * .90f, size.height * .04f))
            drawLine(Color(0x0C5F8FFF), Offset(size.width * .68f, 0f), Offset(size.width, size.height * .32f), 1.dp.toPx())
            drawLine(Color(0x0873A7FF), Offset(size.width * .78f, 0f), Offset(size.width, size.height * .22f), 1.dp.toPx())
        }
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
