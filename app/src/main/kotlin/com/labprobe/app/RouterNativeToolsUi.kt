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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private val NativeBlue = LabV2.Primary
private val NativeCyan = LabV2.Cyan
private val NativeGreen = LabV2.Green
private val NativeAmber = LabV2.Amber
private val NativeRed = LabV2.Red
private val NativeInk = LabV2.Ink
private val NativeMuted = LabV2.InkMuted
private val NativeBorder = LabCoreSurface.Border

private object RouterNativeMemoryCache {
    var natResult: RouterNatResult? = null
}

private fun Modifier.nativeCardShadow(shape: RoundedCornerShape, elevation: androidx.compose.ui.unit.Dp = 2.dp): Modifier =
    shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = LabV2.ShadowAmbient,
        spotColor = LabV2.ShadowSpot
    )

private fun natTypeZh(value: String): String = when (value.trim().lowercase()) {
    "open internet", "open-internet" -> "开放互联网"
    "full cone", "full-cone", "full cone nat" -> "完全锥形 NAT"
    "restricted cone", "restricted-cone", "restricted cone nat" -> "受限锥形 NAT"
    "port-restricted cone", "port restricted cone", "port-restricted cone nat" -> "端口受限锥形 NAT"
    "symmetric", "symmetric nat" -> "对称型 NAT"
    "symmetric udp firewall" -> "对称 UDP 防火墙"
    "udp blocked", "blocked" -> "UDP 被阻断"
    "unknown", "" -> "--"
    else -> if (value.any { it.code > 127 }) value else "未知 NAT 类型"
}

private fun natStatusZh(value: String): String = when (value.trim().lowercase()) {
    "idle" -> "等待检测"
    "running", "detecting", "started" -> "检测中"
    "completed", "success" -> "检测完成"
    "failed", "error" -> "检测失败"
    else -> if (value.any { it.code > 127 }) value else "状态未知"
}


private fun natErrorZh(raw: String?): String {
    val text = raw.orEmpty().trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> "NAT 检测失败"
        "timeout" in lower || "timed out" in lower -> "检测请求超时，请更换 STUN 服务器或 WAN 类型后重试"
        "failed to connect" in lower || "connection refused" in lower -> "无法连接 Hub，请检查网络"
        "unknown host" in lower || "resolve" in lower || "dns" in lower -> "STUN 服务器域名解析失败"
        else -> if (text.any { it.code > 127 }) text else "NAT 检测失败，请稍后重试"
    }
}

private fun natLogZh(raw: String): String {
    var text = raw
    val replacements = listOf(
        "[NAT Detection] Starting RFC 3489 classic detection" to "[NAT 检测] 开始 RFC 3489 经典检测",
        "[NAT Detection] Starting RFC 5780 detection" to "[NAT 检测] 开始 RFC 5780 检测",
        "[Configuration] STUN server:" to "[配置] STUN 服务器：",
        "[Configuration] Local address:" to "[配置] 本地地址：",
        "[Test I] Sending Binding Request to" to "[测试 I] 正在发送绑定请求到",
        "[Test I] Mapped address:" to "[测试 I] 映射地址：",
        "[Test I] Changed address:" to "[测试 I] 变更地址：",
        "[Test II] Sending ChangeIP+ChangePort request" to "[测试 II] 正在发送更改 IP 和端口请求",
        "[Test III] Sending ChangePort request" to "[测试 III] 正在发送更改端口请求",
        "[STUN Request] Response timeout" to "[STUN 请求] 响应超时",
        "[STUN Request] Retry attempt" to "[STUN 请求] 重试次数",
        "[STUN Request] Failed after" to "[STUN 请求] 多次尝试后失败：",
        "[Detection] Local IP:" to "[检测] 本地 IP：",
        "Mapped IP:" to "映射 IP：",
        "No response, NAT detected, performing further tests" to "无响应，检测到 NAT，继续执行后续测试",
        "Sending Binding Request to alternate server" to "正在向备用服务器发送绑定请求",
        "Same mapping - consistent mapping behavior" to "映射一致，映射行为稳定",
        "No response - Port Restricted Cone NAT" to "无响应：端口受限锥形 NAT",
        "Detection completed successfully" to "检测成功完成",
        "NAT Type:" to "NAT 类型：",
        "External:" to "外网地址：",
        "Result: port-restricted cone" to "结果：端口受限锥形 NAT",
        "Result: restricted cone" to "结果：受限锥形 NAT",
        "Result: full cone" to "结果：完全锥形 NAT",
        "Result: symmetric" to "结果：对称型 NAT"
    )
    replacements.forEach { (old, new) -> text = text.replace(old, new, ignoreCase = true) }
    text = text
        .replace("[Test I#2]", "[测试 I#2]", ignoreCase = true)
        .replace("[Test I]", "[测试 I]", ignoreCase = true)
        .replace("[Test II]", "[测试 II]", ignoreCase = true)
        .replace("[Test III]", "[测试 III]", ignoreCase = true)
        .replace("[Detection]", "[检测]", ignoreCase = true)
        .replace("[Configuration]", "[配置]", ignoreCase = true)
        .replace("[NAT Detection]", "[NAT 检测]", ignoreCase = true)
        .replace("Sending Binding Request", "正在发送绑定请求", ignoreCase = true)
        .replace("Sending ChangePort request", "正在发送更改端口请求", ignoreCase = true)
        .replace("to alternate server", "到备用服务器", ignoreCase = true)
        .replace("Mapped address", "映射地址", ignoreCase = true)
        .replace("Changed address", "变更地址", ignoreCase = true)
        .replace("No response", "无响应", ignoreCase = true)
        .replace("NAT detected", "检测到 NAT", ignoreCase = true)
        .replace("performing further tests", "继续后续测试", ignoreCase = true)
        .replace("Same mapping", "映射一致", ignoreCase = true)
        .replace("consistent mapping behavior", "映射行为稳定", ignoreCase = true)
        .replace("Detection completed successfully", "检测成功完成", ignoreCase = true)
    return text
}

@Composable
private fun NativeSelector(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier) {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.fillMaxWidth().height(52.dp).nativeCardShadow(shape, 1.dp),
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
            border = BorderStroke(1.dp, NativeBlue.copy(alpha = .32f)),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(label, fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, color = NativeMuted, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(value, fontSize = LabTypography.SectionTitle.fontSize, lineHeight = LabTypography.SectionTitle.lineHeight, color = NativeInk, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Icon(Icons.Rounded.ArrowDropDown, null, tint = NativeBlue)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(min = 156.dp).padding(vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp
        ) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title, color = NativeInk, fontSize = LabTypography.SectionTitle.fontSize, fontWeight = FontWeight.SemiBold) },
                    onClick = { onSelect(key); onExpandedChange(false) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun NativeCompactPortField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.height(52.dp).nativeCardShadow(shape, 4.dp),
        shape = shape,
        color = Color.White,
        border = BorderStroke(1.dp, NativeBlue.copy(alpha = .30f))
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("STUN 端口", fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, color = NativeMuted, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = LabTypography.FieldValue.copy(textAlign = TextAlign.Start),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                cursorBrush = SolidColor(NativeBlue)
            )
        }
    }
}

private data class RouterNatResult(
    val timestamp: Long = 0L,
    val status: String = "idle",
    val mode: String = "classic",
    val natType: String = "",
    val mappingBehavior: String = "",
    val filteringBehavior: String = "",
    val externalIp: String = "",
    val externalPort: Int = 0,
    val externalAddress: String = "",
    val otherAddress: String = "",
    val stunPort: Int = 0,
    val log: String = "",
    val taskState: String = "idle",
    val stageText: String = "尚未开始",
    val elapsedSeconds: Long = 0L,
    val lastRouterResponseAt: Long = 0L
) {
    val completed: Boolean get() =
        status.equals("completed", true) || status.equals("success", true) ||
            (mode.equals("5780", true) && mappingBehavior.isNotBlank() && filteringBehavior.isNotBlank())
    val cancelled: Boolean get() = status.equals("cancelled", true) || status.equals("canceled", true)
    val running: Boolean get() = taskState in setOf("queued", "running") || status.equals("running", true) || status.equals("detecting", true) || status.equals("started", true)

    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("status", status)
        .put("mode", mode)
        .put("natType", natType)
        .put("mappingBehavior", mappingBehavior)
        .put("filteringBehavior", filteringBehavior)
        .put("externalIp", externalIp)
        .put("externalPort", externalPort)
        .put("externalAddress", externalAddress)
        .put("otherAddress", otherAddress)
        .put("stunPort", stunPort)
}

private fun natResultFromTask(task: RouterTaskSnapshot): RouterNatResult {
    val data = task.result
    fun textOf(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        data.optString(key).trim().takeIf(String::isNotBlank)
    }.orEmpty()
    val mode = textOf("mode", "requested_mode").takeIf { it == "classic" || it == "5780" } ?: "classic"
    val mapping = textOf("mapping_behavior", "mappingBehavior", "mapping")
    val filtering = textOf("filtering_behavior", "filteringBehavior", "filtering")
    val status = when {
        task.succeeded -> "completed"
        task.failed -> task.state
        task.active -> "running"
        else -> textOf("status").ifBlank { "idle" }
    }
    val routerLog = textOf("log")
    val combinedLog = (task.log + routerLog.lines().filter(String::isNotBlank)).distinct().joinToString("\n")
    return RouterNatResult(
        timestamp = data.optLong("timestamp", task.updatedAt), status = status, mode = mode,
        natType = textOf("nat_type", "natType", "classic_type", "classicType"),
        mappingBehavior = mapping, filteringBehavior = filtering,
        externalIp = textOf("external_ip", "externalIp"),
        externalPort = data.optInt("external_port", data.optInt("externalPort", 0)),
        externalAddress = textOf("external_address", "externalAddress", "mapped_address", "mappedAddress"),
        otherAddress = textOf("other_address", "otherAddress"),
        stunPort = data.optInt("requested_port", data.optInt("stun_port", data.optInt("port", 0))),
        log = combinedLog, taskState = task.state, stageText = task.stageText,
        elapsedSeconds = task.elapsedSeconds, lastRouterResponseAt = task.lastRouterResponseAt
    )
}

private data class RouterBetaInfo(
    val current: String = "",
    val totalCount: Int = 0,
    val message: String = "",
    val versions: List<String> = emptyList(),
    val checkedAt: Long = 0L
) {
    val hasSnapshot: Boolean get() = checkedAt > 0L || current.isNotBlank() || message.isNotBlank() || versions.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("current", current)
        .put("totalCount", totalCount)
        .put("message", message)
        .put("versions", JSONArray().apply { versions.forEach(::put) })
        .put("checkedAt", checkedAt)
}

private class RouterNativeApi(private val prefs: AppPrefs) {
    private val hub = HubApi(prefs)

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) { hub.requestJson(path, method, body) }

    private fun parseNatResult(data: JSONObject): RouterNatResult {
        fun textOf(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            data.optString(key).trim().takeIf(String::isNotBlank)
        }.orEmpty()

        val mode = textOf("mode", "requested_mode").takeIf { it == "classic" || it == "5780" } ?: "classic"
        val mapping = textOf("mapping_behavior", "mappingBehavior", "mapping")
        val filtering = textOf("filtering_behavior", "filteringBehavior", "filtering")
        val rawStatus = textOf("status").ifBlank { "idle" }
        val status = if (mode == "5780" && mapping.isNotBlank() && filtering.isNotBlank() &&
            rawStatus !in listOf("cancelled", "canceled", "error", "failed", "timeout")) {
            "completed"
        } else rawStatus

        return RouterNatResult(
            timestamp = data.optLong("timestamp", data.optLong("updatedAt", 0L)),
            status = status,
            mode = mode,
            natType = textOf("nat_type", "natType", "classic_type", "classicType"),
            mappingBehavior = mapping,
            filteringBehavior = filtering,
            externalIp = textOf("external_ip", "externalIp"),
            externalPort = data.optInt("external_port", data.optInt("externalPort", 0)),
            externalAddress = textOf("external_address", "externalAddress", "mapped_address", "mappedAddress"),
            otherAddress = textOf("other_address", "otherAddress"),
            stunPort = data.optInt("requested_port", data.optInt("stun_port", data.optInt("port", 0))),
            log = textOf("log")
        )
    }

    suspend fun natStatus(): RouterNatResult {
        val data = request("/api/router/nat-diagnostic").optJSONObject("data") ?: JSONObject()
        return parseNatResult(data)
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
        val data = request("/api/router/beta-upgrade?force=1").optJSONObject("data") ?: JSONObject()
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
            message = next.optString("msg").ifBlank {
                if (versions.isEmpty()) "当前没有可用 Beta 版本" else "发现可用 Beta 版本"
            },
            versions = versions.distinct(),
            checkedAt = data.optLong("checkedAt", data.optLong("updatedAt", System.currentTimeMillis() / 1000L))
        )
    }
}

@Composable
fun HomeDdnsMiniCard(prefs: AppPrefs, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.ddns.collectAsState()
    val rows = resource.value.orEmpty()
    val enabled = rows.count { it.enabled }
    val failed = rows.count { it.status.contains("error", true) || it.status.contains("fail", true) }
    HealthMiniCard(
        title = "DDNS",
        value = resource.value?.size?.toString() ?: "--",
        unit = "条",
        icon = Icons.Rounded.CloudSync,
        glyph = RouterGlyph.Ddns,
        accent = NativeCyan,
        subtitle = when {
            resource.value == null -> "后台预加载中"
            resource.error.isNotBlank() -> "已保留上次快照"
            rows.isEmpty() -> "暂无记录"
            failed > 0 -> "启用 $enabled · 异常 $failed"
            else -> "启用 $enabled · 状态正常"
        },
        modifier = modifier,
        onClick = onClick
    )
}


private fun mergeNatLog(previous: String, incoming: String): String {
    val oldText = previous.trim()
    val newText = incoming.trim()
    if (newText.isBlank()) return oldText
    if (oldText.isBlank()) return newText
    if (newText == oldText || oldText.startsWith(newText)) return oldText
    if (newText.startsWith(oldText)) return newText

    val lines = oldText.lines().map(String::trimEnd).filter(String::isNotBlank).toMutableList()
    val known = lines.toMutableSet()
    newText.lines().map(String::trimEnd).filter(String::isNotBlank).forEach { line ->
        if (known.add(line)) lines += line
    }
    return lines.takeLast(300).joinToString("\n")
}

@Composable
fun RouterNatDiagnosticScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val tasks = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterTaskRepositoryRegistry.get(prefs) }
    val task by tasks.nat.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val servers = remember {
        listOf(
            "stun.voip.aebc.com",
            "stun.miwifi.com",
            "stun.hot-chilli.net",
            "stun.internetcalls.com",
            "stun.fitaauto.ru",
            "stun.voipbuster.com",
            "stun.voipstunt.com"
        )
    }
    var server by rememberSaveable { mutableStateOf(servers.first()) }
    var portText by rememberSaveable { mutableStateOf("3478") }
    var mode by rememberSaveable { mutableStateOf("classic") }
    var interfaceName by rememberSaveable { mutableStateOf("wan") }
    val cachedResult = RouterNativeMemoryCache.natResult
    var result by remember { mutableStateOf(cachedResult ?: RouterNatResult()) }
    var sessionLog by remember { mutableStateOf(cachedResult?.log.orEmpty()) }
    var running by remember { mutableStateOf(cachedResult?.running == true) }
    var loading by remember { mutableStateOf(cachedResult == null) }
    var error by remember { mutableStateOf("") }
    var serverMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var interfaceMenu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadNatHistory(context)) }

    fun publish(next: RouterNatResult) {
        val mergedLog = mergeNatLog(sessionLog, next.log)
        sessionLog = mergedLog
        result = next.copy(log = mergedLog)
        RouterNativeMemoryCache.natResult = result
    }

    LaunchedEffect(Unit) { tasks.ensure("nat") }
    LaunchedEffect(task.updatedAt, task.state) {
        val normalized = natResultFromTask(task).copy(
            mode = natResultFromTask(task).mode.takeIf { it == "classic" || it == "5780" } ?: mode,
            stunPort = natResultFromTask(task).stunPort.takeIf { it > 0 } ?: portText.toIntOrNull() ?: 0
        )
        publish(normalized)
        running = task.active
        loading = false
        error = if (task.failed) task.message.ifBlank { task.stageText } else ""
        if (task.succeeded && normalized.completed) history = saveNatHistory(context, normalized)
    }

    DetailShell("路由 NAT 诊断", "路由器原生 RFC3489 / RFC5780", onBack, compactHeader = true, unifiedTypography = true) {
        NativeCard {
            NativeTitle(Icons.Rounded.Radar, "检测参数", NativeBlue)
            val serverShape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1.35f)) {
                    OutlinedButton(
                        onClick = { serverMenu = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp).nativeCardShadow(serverShape, 1.dp),
                        shape = serverShape,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, NativeBlue.copy(alpha = .32f))
                    ) {
                        Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp), tint = NativeBlue)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text("STUN 服务器", fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, color = NativeMuted, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            Text(server, style = LabTypography.ValueStrong.copy(color = NativeInk), maxLines = 2, overflow = TextOverflow.Clip)
                        }
                        Icon(Icons.Rounded.ArrowDropDown, null, tint = NativeBlue)
                    }
                    DropdownMenu(
                        expanded = serverMenu,
                        onDismissRequest = { serverMenu = false },
                        modifier = Modifier.widthIn(min = 300.dp).padding(vertical = 5.dp),
                        shape = RoundedCornerShape(24.dp),
                        containerColor = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 4.dp
                    ) {
                        servers.forEach { host ->
                            DropdownMenuItem(
                                text = { Text(host, color = NativeInk, fontSize = LabTypography.SectionTitle.fontSize, fontWeight = FontWeight.SemiBold) },
                                onClick = { server = host; serverMenu = false },
                                modifier = Modifier.heightIn(min = 50.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                NativeCompactPortField(
                    value = portText,
                    onValueChange = { portText = it },
                    modifier = Modifier.weight(.85f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeSelector(
                    label = "STUN 类型",
                    value = if (mode == "5780") "RFC 5780" else "RFC 3489",
                    options = listOf("classic" to "RFC 3489", "5780" to "RFC 5780"),
                    expanded = modeMenu,
                    onExpandedChange = { modeMenu = it },
                    onSelect = { mode = it },
                    modifier = Modifier.weight(1.35f)
                )
                NativeSelector(
                    label = "WAN 类型",
                    value = interfaceName.uppercase(),
                    options = listOf("wan" to "WAN", "wan1" to "WAN1"),
                    expanded = interfaceMenu,
                    onExpandedChange = { interfaceMenu = it },
                    onSelect = { interfaceName = it },
                    modifier = Modifier.weight(.85f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            error = "请输入正确的 STUN 端口"
                        } else {
                            error = ""
                            running = true
                            sessionLog = listOf(
                                "Hub 正在提交 NAT 检测任务",
                                "STUN 服务器：$server:$port",
                                "WAN 接口：${interfaceName.uppercase()}",
                                "检测协议：${if (mode == "5780") "RFC 5780" else "RFC 3489"}"
                            ).joinToString("\n")
                            publish(RouterNatResult(status = "running", taskState = "queued", mode = mode, stunPort = port, log = sessionLog, stageText = "正在提交检测任务"))
                            tasks.startNat(server, port, interfaceName, mode)
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(44.dp).nativeCardShadow(RoundedCornerShape(14.dp), 2.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NativeBlue,
                        contentColor = Color.White,
                        disabledContainerColor = NativeBlue.copy(alpha = .62f),
                        disabledContentColor = Color.White
                    )
                ) {
                    if (running) CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White, trackColor = Color.Transparent
                    ) else Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (running) "检测中" else "开始检测", style = LabTypography.Button)
                }
            }
        }

        if (error.isNotBlank()) NativeMessage(error, NativeRed)

        NativeCard {
            NativeTitle(Icons.Rounded.Analytics, "分析结果", NativeGreen)
            NativeValueRow("检测状态", when {
                loading && result.status == "idle" -> "读取中"
                running -> result.stageText.ifBlank { "检测中" }
                result.completed -> "检测完成"
                else -> natStatusZh(result.status)
            })
            if (result.mode == "5780") {
                NativeValueRow("映射行为", natMappingBehaviorZh(result.mappingBehavior))
                NativeValueRow("过滤行为", natFilteringBehaviorZh(result.filteringBehavior))
                if (result.otherAddress.isNotBlank()) NativeValueRow("其他地址", result.otherAddress)
            } else {
                NativeValueRow("NAT类型", natTypeZh(result.natType))
            }
            NativeValueRow("外网地址", result.externalAddress.ifBlank {
                if (result.externalIp.isBlank()) "--" else result.externalIp +
                    if (result.externalPort > 0) ":${result.externalPort}" else ""
            })
            NativeValueRow("检测模式", if (result.mode == "5780") "RFC 5780" else "RFC 3489")
            if (running) {
                NativeValueRow("已耗时", "${result.elapsedSeconds} 秒")
            } else if (result.elapsedSeconds > 0L) {
                NativeValueRow("检测耗时", "${result.elapsedSeconds} 秒")
            }
            if (result.lastRouterResponseAt > 0L) {
                if (running) {
                    val age = (System.currentTimeMillis() / 1000L - result.lastRouterResponseAt).coerceAtLeast(0L)
                    NativeValueRow("路由器响应", if (age < 3L) "刚刚" else "${age} 秒前")
                } else {
                    NativeValueRow("最终响应", "已收到")
                }
            }
        }

        if (result.log.isNotBlank()) {
            NativeCard {
                NativeTitle(Icons.Rounded.Terminal, "检测日志", NativeCyan)
                SelectionContainer {
                    Text(
                        natLogZh(result.log),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = LabTypography.Caption.fontSize,
                        lineHeight = LabTypography.Caption.lineHeight,
                        color = NativeInk
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            NativeCard {
                NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)
                history.forEachIndexed { index, item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            if (item.mode == "5780") {
                                listOf(
                                    natMappingBehaviorZh(item.mappingBehavior),
                                    natFilteringBehaviorZh(item.filteringBehavior)
                                ).filter { it != "--" }.joinToString(" · ").ifBlank { "RFC5780 行为检测" }
                            } else natTypeZh(item.natType),
                            fontSize = LabTypography.Supporting.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            color = NativeInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOf(
                                item.externalAddress,
                                if (item.mode == "5780") "RFC5780" else "RFC3489",
                                item.stunPort.takeIf { it > 0 }?.let { "$it 端口" }.orEmpty()
                            ).filter(String::isNotBlank).joinToString(" · "),
                            fontSize = LabTypography.Caption.fontSize,
                            color = NativeMuted
                        )
                    }
                    if (index != history.lastIndex) HorizontalDivider(color = NativeBorder)
                }
            }
        }
    }
}


private const val ROUTER_BETA_SNAPSHOT_PREF = "router_beta_snapshot_v1"

private fun loadRouterBetaSnapshot(context: Context): RouterBetaInfo {
    val raw = context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .getString(ROUTER_BETA_SNAPSHOT_PREF, "")
        .orEmpty()
    if (raw.isBlank()) return RouterBetaInfo()
    return runCatching {
        val root = JSONObject(raw)
        val versions = root.optJSONArray("versions") ?: JSONArray()
        RouterBetaInfo(
            current = root.optString("current"),
            totalCount = root.optInt("totalCount", versions.length()),
            message = root.optString("message"),
            versions = (0 until versions.length()).mapNotNull { index ->
                versions.optString(index).trim().takeIf(String::isNotBlank)
            },
            checkedAt = root.optLong("checkedAt", 0L)
        )
    }.getOrDefault(RouterBetaInfo())
}

private fun saveRouterBetaSnapshot(context: Context, info: RouterBetaInfo) {
    if (!info.hasSnapshot) return
    context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .edit()
        .putString(ROUTER_BETA_SNAPSHOT_PREF, info.toJson().toString())
        .apply()
}

@Composable
fun RouterBetaUpgradeScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val tasks = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterTaskRepositoryRegistry.get(prefs) }
    val task by tasks.beta.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var info by remember { mutableStateOf(loadRouterBetaSnapshot(context)) }

    LaunchedEffect(Unit) { tasks.ensure("beta") }
    LaunchedEffect(task.updatedAt, task.state) {
        val data = task.result
        if (data.length() > 0) {
            val next = data.optJSONObject("new") ?: JSONObject()
            val firmware = next.optJSONArray("firmwareList") ?: JSONArray()
            val versions = (0 until firmware.length()).mapNotNull { index ->
                val item = firmware.opt(index)
                when (item) {
                    is JSONObject -> item.optString("version").ifBlank { item.toString() }
                    null -> null
                    else -> item.toString()
                }
            }
            val latest = RouterBetaInfo(
                current = data.optString("cur"),
                totalCount = next.optInt("totalCount", versions.size),
                message = task.message.ifBlank { next.optString("msg") }.let(::taskMessageZh),
                versions = versions.distinct(),
                checkedAt = data.optLong("checkedAt", task.updatedAt)
            )
            if (latest.hasSnapshot) {
                info = latest
                saveRouterBetaSnapshot(context, latest)
            }
        }
    }

    DetailShell("Beta 在线升级", "显示上次检查快照 · 仅手动检测", onBack, compactHeader = true, unifiedTypography = true) {
        NativeCard {
            NativeTitle(Icons.Rounded.SystemUpdateAlt, "固件版本", NativeCyan)
            NativeValueRow("当前版本", info.current.ifBlank { "--" }, stacked = true)
            NativeValueRow("可用版本", if (info.hasSnapshot) "${info.totalCount} 个" else "--")
            Text(
                when {
                    task.active -> task.stageText
                    task.failed -> task.message.ifBlank { task.stageText }
                    else -> info.message.ifBlank { "尚未检测，点击下方按钮开始" }
                },
                fontSize = LabTypography.Supporting.fontSize,
                color = if (task.failed) NativeRed else NativeMuted
            )
            if (task.active) {
                NativeValueRow("已耗时", "${task.elapsedSeconds} 秒")
                if (task.lastRouterResponseAt > 0L) NativeValueRow("路由器状态", "已返回响应")
            }
            if (info.checkedAt > 0L) Text(
                "上次检测：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(info.checkedAt * 1000L))}",
                fontSize = LabTypography.Caption.fontSize,
                color = NativeMuted
            )
            Button(
                onClick = { tasks.startBeta() },
                enabled = !task.active,
                modifier = Modifier.fillMaxWidth().height(42.dp).nativeCardShadow(RoundedCornerShape(14.dp), 2.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NativeCyan,
                    contentColor = Color.White,
                    disabledContainerColor = NativeCyan.copy(alpha = .72f),
                    disabledContentColor = Color.White
                )
            ) {
                if (task.active) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (task.active) "检测进行中" else "检测更新", style = LabTypography.Button)
            }
        }
        if (info.versions.isNotEmpty()) NativeCard {
            NativeTitle(Icons.Rounded.NewReleases, "可用版本", NativeAmber)
            info.versions.forEach { Text(it, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = NativeInk) }
        }
    }
}

@Composable
private fun NativeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CardShape,
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, NativeBorder),
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun NativeTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(color.copy(alpha = .10f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = color)
        }
        Spacer(Modifier.width(9.dp))
        Text(title, fontSize = LabTypography.SectionTitle.fontSize, fontWeight = FontWeight.SemiBold, color = NativeInk)
    }
}

@Composable
private fun NativeValueRow(label: String, value: String, stacked: Boolean = false) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = LabTypography.Supporting.copy(color = NativeMuted))
            SelectionContainer {
                Text(value, style = LabTypography.Value.copy(color = NativeInk), maxLines = 4, overflow = TextOverflow.Clip)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(label, Modifier.width(78.dp), style = LabTypography.Supporting.copy(color = NativeMuted))
            Text(value, Modifier.weight(1f), style = LabTypography.Value.copy(color = NativeInk), maxLines = 3, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun NativeMessage(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .08f), border = BorderStroke(1.dp, color.copy(alpha = .18f))) {
        Text(text, Modifier.fillMaxWidth().padding(10.dp), fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = color)
    }
}

private const val ROUTER_NAT_HISTORY_LIMIT = 5

private fun natMappingBehaviorZh(raw: String): String = when (raw.trim().lowercase()) {
    "endpoint independent", "endpoint-independent", "eim" -> "端点独立映射"
    "address dependent", "address-dependent", "adm" -> "地址相关映射"
    "address and port dependent", "addr & port dependent", "address-port dependent", "apdm" -> "地址和端口相关映射"
    "" -> "--"
    else -> raw
}

private fun natFilteringBehaviorZh(raw: String): String = when (raw.trim().lowercase()) {
    "endpoint independent", "endpoint-independent", "eif" -> "端点独立过滤"
    "address dependent", "address-dependent", "adf" -> "地址相关过滤"
    "address and port dependent", "addr & port dependent", "address-port dependent", "apdf" -> "地址和端口相关过滤"
    "" -> "--"
    else -> raw
}

private fun RouterNatResult.hasHistoryResult(): Boolean = completed && when {
    mode.equals("5780", true) -> mappingBehavior.isNotBlank() || filteringBehavior.isNotBlank()
    else -> natType.isNotBlank()
}

private fun RouterNatResult.historyKey(): String = listOf(
    timestamp.toString(), mode.lowercase(), natType.lowercase(), mappingBehavior.lowercase(),
    filteringBehavior.lowercase(), externalAddress.lowercase(), stunPort.toString()
).joinToString("|")

private fun normalizeNatHistory(rows: List<RouterNatResult>): List<RouterNatResult> = rows
    .filter(RouterNatResult::hasHistoryResult)
    .sortedByDescending { it.timestamp }
    .distinctBy(RouterNatResult::historyKey)
    .take(ROUTER_NAT_HISTORY_LIMIT)

private fun persistNatHistory(context: Context, rows: List<RouterNatResult>) {
    val array = JSONArray()
    rows.take(ROUTER_NAT_HISTORY_LIMIT).forEach { array.put(it.toJson()) }
    context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .edit()
        .putString("nat_history", array.toString())
        .apply()
}

private fun loadNatHistory(context: Context): List<RouterNatResult> {
    val raw = context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .getString("nat_history", "[]") ?: "[]"
    val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    val normalized = normalizeNatHistory((0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let {
            RouterNatResult(
                timestamp = it.optLong("timestamp"),
                status = it.optString("status"),
                mode = it.optString("mode"),
                natType = it.optString("natType"),
                mappingBehavior = it.optString("mappingBehavior"),
                filteringBehavior = it.optString("filteringBehavior"),
                externalIp = it.optString("externalIp"),
                externalPort = it.optInt("externalPort"),
                externalAddress = it.optString("externalAddress"),
                otherAddress = it.optString("otherAddress"),
                stunPort = it.optInt("stunPort", 0)
            )
        }
    })
    if (normalized.size != array.length()) persistNatHistory(context, normalized)
    return normalized
}

private fun saveNatHistory(context: Context, result: RouterNatResult): List<RouterNatResult> {
    val normalizedResult = result.copy(
        timestamp = result.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis() / 1000L,
        mode = if (result.mode.equals("5780", ignoreCase = true)) "5780" else "classic",
        status = "completed"
    )
    if (!normalizedResult.hasHistoryResult()) return loadNatHistory(context)
    val next = normalizeNatHistory(listOf(normalizedResult) + loadNatHistory(context))
    persistNatHistory(context, next)
    return next
}
