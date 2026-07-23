package com.labprobe.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val NativeBlue = Color(0xFF2563EB)
private val NativeCyan = Color(0xFF0891B2)
private val NativeGreen = Color(0xFF16A36A)
private val NativeAmber = Color(0xFFF59E0B)
private val NativeRed = Color(0xFFDC2626)
private val NativeInk = Color(0xFF17233A)
private val NativeMuted = Color(0xFF687890)
private val NativeBorder = Color(0xFFE3EAF4)

private data class RouterNatResult(
    val timestamp: Long = 0L,
    val status: String = "idle",
    val mode: String = "classic",
    val natType: String = "",
    val externalIp: String = "",
    val externalPort: Int = 0,
    val externalAddress: String = "",
    val log: String = ""
) {
    val completed: Boolean get() = status.equals("completed", true)
    val running: Boolean get() = status.equals("running", true) || status.equals("detecting", true) || status.equals("started", true)

    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("status", status)
        .put("mode", mode)
        .put("natType", natType)
        .put("externalIp", externalIp)
        .put("externalPort", externalPort)
        .put("externalAddress", externalAddress)
}

private data class RouterBetaInfo(
    val current: String = "",
    val totalCount: Int = 0,
    val message: String = "",
    val versions: List<String> = emptyList()
)

private class RouterNativeApi(private val prefs: AppPrefs) {
    private val hub = HubApi(prefs)

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) { hub.requestJson(path, method, body) }

    suspend fun natStatus(): RouterNatResult {
        val data = request("/api/router/nat-diagnostic").optJSONObject("data") ?: JSONObject()
        return RouterNatResult(
            timestamp = data.optLong("timestamp", 0L),
            status = data.optString("status", "idle"),
            mode = data.optString("mode", "classic"),
            natType = data.optString("nat_type"),
            externalIp = data.optString("external_ip"),
            externalPort = data.optInt("external_port", 0),
            externalAddress = data.optString("external_address"),
            log = data.optString("log")
        )
    }

    suspend fun startNat(host: String, port: Int, interfaceName: String, mode: String) {
        request(
            "/api/router/nat-diagnostic",
            "POST",
            JSONObject()
                .put("host", host)
                .put("port", port)
                .put("interface", interfaceName)
                .put("mode", mode)
        )
    }

    suspend fun betaInfo(): RouterBetaInfo {
        val data = request("/api/router/beta-upgrade").optJSONObject("data") ?: JSONObject()
        val next = data.optJSONObject("new") ?: JSONObject()
        val versions = mutableListOf<String>()
        when (val firmware = next.opt("firmwareList")) {
            is JSONArray -> for (i in 0 until firmware.length()) {
                val item = firmware.opt(i)
                when (item) {
                    is JSONObject -> versions += item.optString("version").ifBlank { item.toString() }
                    null -> Unit
                    else -> versions += item.toString()
                }
            }
            is JSONObject -> {
                val keys = firmware.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = firmware.opt(key)
                    versions += if (item is JSONObject) item.optString("version").ifBlank { key } else key
                }
            }
        }
        return RouterBetaInfo(
            current = data.optString("cur").trim(),
            totalCount = next.optInt("totalCount", versions.size),
            message = next.optString("msg").ifBlank { if (versions.isEmpty()) "当前没有可用Beta版本" else "发现可用Beta版本" },
            versions = versions.distinct()
        )
    }
}

@Composable
fun HomeDdnsMiniCard(prefs: AppPrefs, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    var rows by remember { mutableStateOf<List<DdnsRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
        runCatching { api.ddns() }.onSuccess { rows = it }
        loaded = true
    }
    val enabled = rows.count { it.enabled }
    val failed = rows.count { it.status.contains("error", true) || it.status.contains("fail", true) }
    HealthMiniCard(
        title = "DDNS",
        value = if (loaded) rows.size.toString() else "--",
        unit = "条",
        icon = Icons.Rounded.CloudSync,
        accent = NativeCyan,
        subtitle = when {
            !loaded -> "正在读取路由器记录"
            rows.isEmpty() -> "暂无记录"
            failed > 0 -> "启用 $enabled · 异常 $failed"
            else -> "启用 $enabled · 状态正常"
        },
        modifier = modifier.clickable(onClick = onClick)
    )
}

@Composable
fun RouterNatDiagnosticScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterNativeApi(prefs) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = remember {
        listOf(
            "stun.hot-chilli.net",
            "stun.fitaauto.ru",
            "stun.internetcalls.com",
            "stun.miwifi.com",
            "stun.voip.aebc.com",
            "stun.voipbuster.com",
            "stun.voipstunt.com"
        )
    }
    var server by rememberSaveable { mutableStateOf(servers.first()) }
    var portText by rememberSaveable { mutableStateOf("3478") }
    var mode by rememberSaveable { mutableStateOf("classic") }
    var interfaceName by rememberSaveable { mutableStateOf("wan") }
    var result by remember { mutableStateOf(RouterNatResult()) }
    var running by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var serverMenu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadNatHistory(context)) }

    suspend fun refresh() {
        runCatching { api.natStatus() }
            .onSuccess {
                result = it
                error = ""
                if (it.completed) {
                    running = false
                    history = saveNatHistory(context, it)
                } else if (it.status.contains("fail", true) || it.status.contains("error", true)) {
                    running = false
                }
            }
            .onFailure { error = it.message.orEmpty() }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(running) {
        while (running && isActive) {
            delay(2_000L)
            refresh()
        }
    }

    DetailShell("路由 NAT 诊断", "路由器原生 RFC3489 / RFC5780", onBack, compactHeader = true) {
        NativeCard {
            NativeTitle(Icons.Rounded.Radar, "检测参数", NativeBlue)
            Box {
                OutlinedButton(onClick = { serverMenu = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(server, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                    servers.forEach { host ->
                        DropdownMenuItem(text = { Text(host) }, onClick = { server = host; serverMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = { Text("STUN端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "classic", onClick = { mode = "classic" }, label = { Text("RFC 3489") })
                FilterChip(selected = mode == "5780", onClick = { mode = "5780" }, label = { Text("RFC 5780") })
                FilterChip(selected = interfaceName == "wan", onClick = { interfaceName = "wan" }, label = { Text("WAN") })
                FilterChip(selected = interfaceName == "wan1", onClick = { interfaceName = "wan1" }, label = { Text("WAN1") })
            }
            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                    if (port == null || port !in 1..65535) {
                        error = "请输入正确的STUN端口"
                    } else {
                        scope.launch {
                            error = ""
                            running = true
                            result = RouterNatResult(status = "running", mode = mode)
                            runCatching { api.startNat(server, port, interfaceName, mode) }
                                .onFailure { error = it.message.orEmpty(); running = false }
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (running) "检测中" else "开始检测", fontWeight = FontWeight.Black)
            }
        }

        if (error.isNotBlank()) NativeMessage(error, NativeRed)

        NativeCard {
            NativeTitle(Icons.Rounded.Analytics, "分析结果", NativeGreen)
            NativeValueRow("检测状态", when {
                loading -> "读取中"
                running -> "检测中"
                result.completed -> "检测完成"
                else -> result.status.ifBlank { "等待检测" }
            })
            NativeValueRow("NAT类型", result.natType.ifBlank { "--" })
            NativeValueRow("外网地址", result.externalAddress.ifBlank {
                if (result.externalIp.isBlank()) "--" else result.externalIp + if (result.externalPort > 0) ":${result.externalPort}" else ""
            })
            NativeValueRow("检测模式", if (result.mode == "5780") "RFC 5780" else "RFC 3489")
        }

        if (result.log.isNotBlank()) {
            NativeCard {
                NativeTitle(Icons.Rounded.Terminal, "检测日志", NativeCyan)
                SelectionContainer {
                    Text(
                        result.log,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = NativeInk
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            NativeCard {
                NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)
                history.take(10).forEachIndexed { index, item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(item.natType.ifBlank { "未知NAT" }, fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = NativeInk)
                        Text(
                            listOf(item.externalAddress, if (item.mode == "5780") "RFC5780" else "RFC3489").filter(String::isNotBlank).joinToString(" · "),
                            fontSize = 9.8.sp,
                            color = NativeMuted
                        )
                    }
                    if (index != history.take(10).lastIndex) HorizontalDivider(color = NativeBorder)
                }
            }
        }
    }
}

@Composable
fun RouterBetaUpgradeScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterNativeApi(prefs) }
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(RouterBetaInfo()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    suspend fun check() {
        loading = true
        runCatching { api.betaInfo() }
            .onSuccess { info = it; error = "" }
            .onFailure { error = it.message.orEmpty() }
        loading = false
    }
    LaunchedEffect(Unit) { check() }

    DetailShell("Beta 在线升级", "仅检查版本 · 安装接口确认后再开放", onBack, compactHeader = true) {
        NativeCard {
            NativeTitle(Icons.Rounded.SystemUpdateAlt, "固件版本", NativeCyan)
            NativeValueRow("当前版本", info.current.ifBlank { if (loading) "读取中" else "--" })
            NativeValueRow("可用版本", if (loading) "读取中" else "${info.totalCount} 个")
            Text(info.message.ifBlank { "等待检查" }, fontSize = 10.5.sp, color = NativeMuted)
            Button(
                onClick = { scope.launch { check() } },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NativeCyan)
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("检测更新", fontWeight = FontWeight.Black)
            }
        }

        if (error.isNotBlank()) NativeMessage(error, NativeRed)

        if (info.versions.isNotEmpty()) {
            NativeCard {
                NativeTitle(Icons.Rounded.NewReleases, "可用Beta版本", NativeGreen)
                info.versions.forEach { version -> Text(version, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NativeInk) }
            }
        }

        NativeCard {
            NativeTitle(Icons.Rounded.WarningAmber, "升级说明", NativeAmber)
            Text("Beta版本可能存在不稳定因素。升级期间不要断电，升级完成后路由器会重启。", fontSize = 10.5.sp, lineHeight = 15.sp, color = NativeMuted)
            Text("当前只开放安全的版本检查；尚未抓到实际安装请求前，不会猜测参数或执行升级。", fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, color = NativeAmber)
        }
    }
}

@Composable
private fun NativeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, NativeBorder),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
private fun NativeTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(color.copy(alpha = .10f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = color)
        }
        Spacer(Modifier.width(9.dp))
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = NativeInk)
    }
}

@Composable
private fun NativeValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(78.dp), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = NativeMuted)
        Text(value, Modifier.weight(1f), fontSize = 10.8.sp, fontWeight = FontWeight.Black, color = NativeInk)
    }
}

@Composable
private fun NativeMessage(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .08f), border = BorderStroke(1.dp, color.copy(alpha = .18f))) {
        Text(text, Modifier.fillMaxWidth().padding(10.dp), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

private fun loadNatHistory(context: Context): List<RouterNatResult> {
    val raw = context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE).getString("nat_history", "[]") ?: "[]"
    val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let {
            RouterNatResult(
                timestamp = it.optLong("timestamp"),
                status = it.optString("status"),
                mode = it.optString("mode"),
                natType = it.optString("natType"),
                externalIp = it.optString("externalIp"),
                externalPort = it.optInt("externalPort"),
                externalAddress = it.optString("externalAddress")
            )
        }
    }
}

private fun saveNatHistory(context: Context, result: RouterNatResult): List<RouterNatResult> {
    if (!result.completed || result.natType.isBlank()) return loadNatHistory(context)
    val current = loadNatHistory(context)
    val duplicate = current.firstOrNull()?.let {
        it.timestamp == result.timestamp && it.natType == result.natType && it.externalAddress == result.externalAddress
    } == true
    val next = if (duplicate) current else (listOf(result) + current).take(10)
    val array = JSONArray()
    next.forEach { array.put(it.toJson()) }
    context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE).edit().putString("nat_history", array.toString()).apply()
    return next
}
