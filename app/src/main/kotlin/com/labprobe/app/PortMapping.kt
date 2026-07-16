package com.labprobe.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

private val PortBlue = Color(0xFF1677F2)
private val PortCyan = Color(0xFF13B8D4)
private val PortGreen = Color(0xFF12B981)
private val PortRed = Color(0xFFEF5350)
private val PortSlate = Color(0xFF718096)

 data class PortMapRuntime(
    val state: String = "stopped",
    val resolvedTarget: String = "",
    val activeConnections: Long = 0,
    val totalUploadBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
    val startedAt: Long? = null,
    val expiresAt: Long? = null,
    val lastResolvedAt: Long? = null,
    val lastError: String = ""
)

 data class PortMapRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val mode: String,
    val listenPort: Int,
    val targetMode: String,
    val targetIpv4: String,
    val targetIpv6: String,
    val targetIpv6Suffix: String,
    val targetMac: String,
    val targetPort: Int,
    val preferCurrentPrefix: Boolean,
    val expiresAt: Long?,
    val maxConnections: Int,
    val idleTimeoutSec: Int,
    val runtime: PortMapRuntime = PortMapRuntime()
) {
    val isRunning: Boolean get() = runtime.state == "running" || runtime.state == "starting"
    val modeText: String get() = if (mode == "6to4") "6→4" else "6→6"
    val targetText: String get() = when {
        mode == "6to4" -> "$targetIpv4:$targetPort"
        targetMode == "ipv6_suffix" -> "${targetMac.ifBlank { "任意设备" }} · $targetIpv6Suffix:$targetPort"
        else -> "[$targetIpv6]:$targetPort"
    }
}

 data class PortMapAgentInfo(
    val online: Boolean,
    val router: String,
    val lastSeenAt: String,
    val portMin: Int,
    val portMax: Int
)

 data class PortMapHistoryPoint(
    val time: Long,
    val activeConnections: Long,
    val uploadBytes: Long,
    val downloadBytes: Long
)

private fun parsePortMapRule(o: JSONObject): PortMapRule {
    val r = o.optJSONObject("runtime") ?: JSONObject()
    fun nullableLong(obj: JSONObject, key: String): Long? = if (!obj.has(key) || obj.isNull(key) || obj.optLong(key) <= 0) null else obj.optLong(key)
    return PortMapRule(
        id = cleanApiText(o.optString("id")),
        name = cleanApiText(o.optString("name")),
        enabled = o.optBoolean("enabled", false),
        mode = cleanApiText(o.optString("mode", "6to4")),
        listenPort = o.optInt("listenPort"),
        targetMode = cleanApiText(o.optString("targetMode")),
        targetIpv4 = cleanApiText(o.optString("targetIpv4")),
        targetIpv6 = cleanApiText(o.optString("targetIpv6")),
        targetIpv6Suffix = cleanApiText(o.optString("targetIpv6Suffix")),
        targetMac = cleanMac(o.optString("targetMac")),
        targetPort = o.optInt("targetPort"),
        preferCurrentPrefix = o.optBoolean("preferCurrentPrefix", true),
        expiresAt = nullableLong(o, "expiresAt"),
        maxConnections = o.optInt("maxConnections", 32),
        idleTimeoutSec = o.optInt("idleTimeoutSec", 300),
        runtime = PortMapRuntime(
            state = cleanApiText(r.optString("state", if (o.optBoolean("enabled")) "waiting_agent" else "stopped")),
            resolvedTarget = cleanApiText(r.optString("resolvedTarget")),
            activeConnections = r.optLong("activeConnections"),
            totalUploadBytes = r.optLong("totalUploadBytes"),
            totalDownloadBytes = r.optLong("totalDownloadBytes"),
            startedAt = nullableLong(r, "startedAt"),
            expiresAt = nullableLong(r, "expiresAt") ?: nullableLong(o, "expiresAt"),
            lastResolvedAt = nullableLong(r, "lastResolvedAt"),
            lastError = cleanApiText(r.optString("lastError"))
        )
    )
}

class PortMapApi(private val prefs: AppPrefs) {
    private val client = OkHttpClient.Builder()
        .dns(CustomDns(prefs.hubDns))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun list(): Pair<List<PortMapRule>, PortMapAgentInfo> = withContext(Dispatchers.IO) {
        val root = JSONObject(get("/api/portmaps"))
        val range = root.optJSONObject("portRange") ?: JSONObject()
        val rules = root.optJSONArray("rules") ?: JSONArray()
        val rows = (0 until rules.length()).mapNotNull { rules.optJSONObject(it)?.let(::parsePortMapRule) }
        rows to PortMapAgentInfo(
            online = root.optBoolean("agentOnline", false),
            router = cleanApiText(root.optString("router", "BE72Pro")),
            lastSeenAt = cleanApiText(root.optString("agentLastSeenAt")),
            portMin = range.optInt("min", 20000),
            portMax = range.optInt("max", 20020)
        )
    }

    suspend fun create(draft: PortMapDraft): PortMapRule = withContext(Dispatchers.IO) {
        parsePortMapRule(JSONObject(post("/api/portmaps", draft.toJson().toString())).getJSONObject("rule"))
    }

    suspend fun update(id: String, draft: PortMapDraft): PortMapRule = withContext(Dispatchers.IO) {
        parsePortMapRule(JSONObject(put("/api/portmaps/$id", draft.toJson().toString())).getJSONObject("rule"))
    }

    suspend fun action(id: String, action: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(post("/api/portmaps/$id/$action", "{}"))
    }

    suspend fun delete(id: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(deleteRequest("/api/portmaps/$id"))
    }

    suspend fun history(id: String, minutes: Int = 60): List<PortMapHistoryPoint> = withContext(Dispatchers.IO) {
        val root = JSONObject(get("/api/portmaps/$id/history?minutes=$minutes"))
        val arr = root.optJSONArray("samples") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                PortMapHistoryPoint(
                    time = it.optLong("time"),
                    activeConnections = it.optLong("activeConnections"),
                    uploadBytes = it.optLong("uploadBytes"),
                    downloadBytes = it.optLong("downloadBytes")
                )
            }
        }
    }

    private fun requestBuilder(path: String): Request.Builder = Request.Builder()
        .url(joinUrl(prefs.hub, path))
        .apply { if (prefs.token.isNotBlank()) header("Authorization", "Bearer ${prefs.token}") }

    private fun execute(req: Request): String {
        val response = client.newCall(req).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val reason = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty().ifBlank { text }
            throw RuntimeException("HTTP ${response.code}: ${reason.ifBlank { "请求失败" }}")
        }
        return text
    }

    private fun get(path: String): String = execute(requestBuilder(path).get().build())
    private fun post(path: String, json: String): String = execute(requestBuilder(path).post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
    private fun put(path: String, json: String): String = execute(requestBuilder(path).put(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
    private fun deleteRequest(path: String): String = execute(requestBuilder(path).delete().build())
}

 data class PortMapDraft(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = false,
    val mode: String = "6to4",
    val listenPort: String = "20001",
    val targetMode: String = "ipv6_suffix",
    val targetIpv4: String = "192.168.5.46",
    val targetIpv6: String = "",
    val targetIpv6Suffix: String = "",
    val targetMac: String = "",
    val targetPort: String = "443",
    val duration: String = "永久",
    val originalExpiresAt: Long? = null,
    val maxConnections: String = "32",
    val idleTimeoutSec: String = "300"
) {
    fun toJson(): JSONObject {
        val now = System.currentTimeMillis() / 1000L
        val expires = when (duration) {
            "保持原有效期" -> originalExpiresAt
            "1小时" -> now + 3600
            "6小时" -> now + 6 * 3600
            "24小时" -> now + 24 * 3600
            else -> null
        }
        return JSONObject().apply {
            if (id.isNotBlank()) put("id", id)
            put("name", name.trim())
            put("enabled", enabled)
            put("mode", mode)
            put("listenPort", listenPort.toIntOrNull() ?: 0)
            put("targetMode", if (mode == "6to4") "ipv4" else targetMode)
            put("targetIpv4", targetIpv4.trim())
            put("targetIpv6", targetIpv6.trim().removePrefix("[").removeSuffix("]"))
            put("targetIpv6Suffix", targetIpv6Suffix.trim())
            put("targetMac", cleanMac(targetMac))
            put("targetPort", targetPort.toIntOrNull() ?: 0)
            put("preferCurrentPrefix", true)
            if (expires == null) put("expiresAt", JSONObject.NULL) else put("expiresAt", expires)
            put("maxConnections", maxConnections.toIntOrNull() ?: 32)
            put("idleTimeoutSec", idleTimeoutSec.toIntOrNull() ?: 300)
        }
    }

    companion object {
        fun from(rule: PortMapRule): PortMapDraft = PortMapDraft(
            id = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            mode = rule.mode,
            listenPort = rule.listenPort.toString(),
            targetMode = rule.targetMode.ifBlank { if (rule.mode == "6to6") "ipv6_suffix" else "ipv4" },
            targetIpv4 = rule.targetIpv4,
            targetIpv6 = rule.targetIpv6,
            targetIpv6Suffix = rule.targetIpv6Suffix,
            targetMac = rule.targetMac,
            targetPort = rule.targetPort.toString(),
            duration = if (rule.expiresAt == null) "永久" else "保持原有效期",
            originalExpiresAt = rule.expiresAt,
            maxConnections = rule.maxConnections.toString(),
            idleTimeoutSec = rule.idleTimeoutSec.toString()
        )
    }
}

@Composable
fun PortMappingScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { PortMapApi(prefs) }
    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<PortMapRule>>(emptyList()) }
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var agent by remember { mutableStateOf(PortMapAgentInfo(false, "BE72Pro", "", 20000, 20020)) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editDraft by remember { mutableStateOf<PortMapDraft?>(null) }

    suspend fun refresh(silent: Boolean = false) {
        if (!silent) loading = true
        runCatching {
            val (newRules, newAgent) = api.list()
            rules = newRules
            agent = newAgent
            if (devices.isEmpty()) devices = deviceApi.getDevices(true)
            message = ""
        }.onFailure { message = it.message ?: "加载失败" }
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(10_000)
            refresh(true)
        }
    }

    val visible = rules.filter {
        when (filter) {
            "运行中" -> it.isRunning || it.runtime.state == "waiting_target" || it.runtime.state == "waiting_agent"
            "已停止" -> !it.isRunning && it.runtime.state != "waiting_target" && it.runtime.state != "waiting_agent"
            else -> true
        }
    }
    val selected = selectedId?.let { id -> rules.firstOrNull { it.id == id } }

    DetailShell("端口映射", "IPv6 入口 · Rust 四层反代 · 6→4 / 6→6", onBack) {
        PortMapAgentCard(agent, loading) { scope.launch { refresh() } }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("全部", "运行中", "已停止").forEach { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { filter = item },
                    label = { Text(item, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.padding(end = 5.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PortBlue, selectedLabelColor = Color.White)
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = { editDraft = PortMapDraft(listenPort = nextPort(rules, agent).toString()) },
                shape = CircleShape,
                color = PortBlue,
                shadowElevation = 5.dp,
                modifier = Modifier.size(42.dp)
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White) } }
        }

        AnimatedVisibility(message.isNotBlank()) {
            Surface(shape = RoundedCornerShape(18.dp), color = PortRed.copy(alpha = .09f), border = androidx.compose.foundation.BorderStroke(1.dp, PortRed.copy(alpha = .16f))) {
                Text(message, Modifier.padding(12.dp), color = PortRed, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (loading && rules.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (visible.isEmpty()) {
            PortMapEmptyCard { editDraft = PortMapDraft(listenPort = nextPort(rules, agent).toString()) }
        } else {
            visible.forEach { rule ->
                PortMapRuleCard(
                    rule = rule,
                    onOpen = { selectedId = rule.id },
                    onEdit = { editDraft = PortMapDraft.from(rule) },
                    onToggle = {
                        scope.launch {
                            runCatching { api.action(rule.id, if (rule.isRunning || rule.enabled) "stop" else "start") }
                                .onFailure { message = it.message ?: "操作失败" }
                            refresh(true)
                        }
                    }
                )
            }
        }
    }

    if (editDraft != null) {
        PortMapEditorSheet(
            initial = editDraft!!,
            devices = devices,
            portRange = agent.portMin..agent.portMax,
            onDismiss = { editDraft = null },
            onSave = { draft ->
                scope.launch {
                    runCatching {
                        if (draft.id.isBlank()) api.create(draft) else api.update(draft.id, draft)
                    }.onSuccess {
                        editDraft = null
                        refresh(true)
                    }.onFailure { message = it.message ?: "保存失败" }
                }
            }
        )
    }

    if (selected != null) {
        PortMapDetailSheet(
            rule = selected,
            api = api,
            onDismiss = { selectedId = null },
            onEdit = { editDraft = PortMapDraft.from(selected); selectedId = null },
            onToggle = {
                scope.launch {
                    runCatching { api.action(selected.id, if (selected.isRunning || selected.enabled) "stop" else "start") }
                        .onFailure { message = it.message ?: "操作失败" }
                    refresh(true)
                }
            },
            onDelete = {
                scope.launch {
                    runCatching { api.delete(selected.id) }
                        .onSuccess { selectedId = null }
                        .onFailure { message = it.message ?: "删除失败" }
                    refresh(true)
                }
            }
        )
    }
}

private fun nextPort(rules: List<PortMapRule>, agent: PortMapAgentInfo): Int {
    val used = rules.map { it.listenPort }.toSet()
    return (agent.portMin..agent.portMax).firstOrNull { it !in used } ?: agent.portMin
}

@Composable
private fun PortMapAgentCard(agent: PortMapAgentInfo, loading: Boolean, onRefresh: () -> Unit) {
    val color = if (agent.online) PortGreen else PortRed
    Surface(
        shape = RoundedCornerShape(25.dp),
        color = Color.White.copy(alpha = .96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
        shadowElevation = 2.dp
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).background(Brush.linearGradient(listOf(PortBlue.copy(alpha = .18f), PortCyan.copy(alpha = .08f))), RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.SwapHoriz, null, tint = PortBlue, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.router.ifBlank { "BE72Pro" }, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Text("Rust LabRelay · TCP ${agent.portMin}–${agent.portMax}", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .50f), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(if (agent.online) "Agent 在线" else "Agent 未连接", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (agent.lastSeenAt.isNotBlank()) Text(" · ${agent.lastSeenAt}", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
                }
            }
            IconButton(onClick = onRefresh) {
                if (loading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null, tint = PortBlue)
            }
        }
    }
}

@Composable
private fun PortMapEmptyCard(onAdd: () -> Unit) {
    Surface(shape = RoundedCornerShape(25.dp), color = Color.White.copy(alpha = .94f), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.SwapHoriz, null, tint = PortBlue, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(9.dp))
            Text("暂无端口映射", fontWeight = FontWeight.Black)
            Text("创建 6→4 或 6→6 TCP 反代规则", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .46f))
            Spacer(Modifier.height(13.dp))
            Button(onClick = onAdd, shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(5.dp)); Text("新建映射") }
        }
    }
}

@Composable
private fun PortMapRuleCard(rule: PortMapRule, onOpen: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit) {
    val status = portMapStatus(rule)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = .96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, status.color.copy(alpha = .10f)),
        shadowElevation = 2.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(status.color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(rule.name, Modifier.weight(1f), fontSize = 14.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("● ${status.text}", color = status.color, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            Text(
                "TCP · ${rule.modeText}${if (rule.targetMode == "ipv6_suffix") " · 后缀匹配" else ""}   [::]:${rule.listenPort} → ${rule.targetText}",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (rule.runtime.resolvedTarget.isNotBlank() && rule.targetMode == "ipv6_suffix") {
                Text("已解析 ${rule.runtime.resolvedTarget}", color = PortBlue, fontSize = 9.8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PortMapMiniMetric(Icons.Rounded.Link, "${rule.runtime.activeConnections} 连接", Modifier.weight(1f))
                PortMapMiniMetric(Icons.Rounded.ArrowUpward, formatPortBytes(rule.runtime.totalUploadBytes), Modifier.weight(1f))
                PortMapMiniMetric(Icons.Rounded.ArrowDownward, formatPortBytes(rule.runtime.totalDownloadBytes), Modifier.weight(1f))
            }
            if (rule.runtime.lastError.isNotBlank() && !rule.isRunning) {
                Text(rule.runtime.lastError, color = PortRed, fontSize = 9.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(portMapTimeText(rule), Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f), fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onToggle, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (rule.isRunning || rule.enabled) PortRed else PortBlue)) {
                    Text(if (rule.isRunning || rule.enabled) "停止" else "启动", fontSize = 10.5.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("编辑", fontSize = 10.5.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

private data class PortMapStatusUi(val text: String, val color: Color)
private fun portMapStatus(rule: PortMapRule): PortMapStatusUi = when (rule.runtime.state) {
    "running", "starting" -> PortMapStatusUi("运行中", PortGreen)
    "waiting_target" -> PortMapStatusUi("等待目标", Color(0xFFF59E0B))
    "waiting_agent" -> PortMapStatusUi("等待 Agent", Color(0xFFF59E0B))
    "expired" -> PortMapStatusUi("已到期", PortSlate)
    "error" -> PortMapStatusUi("异常", PortRed)
    else -> PortMapStatusUi("已停止", PortSlate)
}

@Composable
private fun PortMapMiniMetric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = PortBlue.copy(alpha = .70f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 9.8.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .57f), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortMapEditorSheet(
    initial: PortMapDraft,
    devices: List<DeviceItem>,
    portRange: IntRange,
    onDismiss: () -> Unit,
    onSave: (PortMapDraft) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFF7FAFE), dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (draft.id.isBlank()) "新建映射" else "编辑映射", fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("TCP IPv6 入口 · 不修改防火墙", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
                }
                TextButton(onClick = {
                    error = validateDraft(draft, portRange)
                    if (error.isBlank()) onSave(draft)
                }) { Text("保存", fontWeight = FontWeight.Black) }
            }

            PortMapFormCard {
                PortMapTextField("规则名称", draft.name, "例如：NAS HTTPS") { draft = draft.copy(name = it) }
                PortMapChoice("映射类型", if (draft.mode == "6to4") "IPv6 → IPv4" else "IPv6 → IPv6", listOf("IPv6 → IPv4", "IPv6 → IPv6")) {
                    draft = draft.copy(mode = if (it.endsWith("IPv4")) "6to4" else "6to6")
                }
                PortMapTextField("监听端口", draft.listenPort, "${portRange.first}-${portRange.last}", KeyboardType.Number) { draft = draft.copy(listenPort = it.filter(Char::isDigit)) }
                PortMapReadOnlyRow("监听地址", "[::]:${draft.listenPort.ifBlank { "—" }} · IPv6 only")
                PortMapReadOnlyRow("协议", "TCP")
            }

            PortMapFormCard {
                PortMapDevicePicker(devices) { device ->
                    val bestIpv6 = device.pickIpv6().best.orEmpty()
                    draft = draft.copy(
                        targetMac = device.mac,
                        targetIpv4 = device.ip.ifBlank { draft.targetIpv4 },
                        targetIpv6 = bestIpv6.ifBlank { draft.targetIpv6 },
                        targetIpv6Suffix = ipv6Suffix64(bestIpv6).ifBlank { draft.targetIpv6Suffix }
                    )
                }
                if (draft.mode == "6to4") {
                    PortMapTextField("目标 IPv4", draft.targetIpv4, "192.168.5.46") { draft = draft.copy(targetIpv4 = it) }
                } else {
                    PortMapChoice("目标方式", if (draft.targetMode == "ipv6_suffix") "IPv6 后缀匹配" else "完整 IPv6", listOf("IPv6 后缀匹配", "完整 IPv6")) {
                        draft = draft.copy(targetMode = if (it.startsWith("IPv6 后缀")) "ipv6_suffix" else "ipv6_full")
                    }
                    if (draft.targetMode == "ipv6_suffix") {
                        PortMapTextField("目标 MAC", draft.targetMac, "6c:1f:f7:76:71:04") { draft = draft.copy(targetMac = it) }
                        PortMapTextField("IPv6 后缀", draft.targetIpv6Suffix, "例如 ::dead:beef") { draft = draft.copy(targetIpv6Suffix = it) }
                        Text("按 MAC + 后缀 + 当前 LAN 前缀解析；找不到目标时规则保持等待，不连接历史地址。", fontSize = 9.5.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .43f))
                    } else {
                        PortMapTextField("目标 IPv6", draft.targetIpv6, "2409:...::1234") { draft = draft.copy(targetIpv6 = it) }
                    }
                }
                PortMapTextField("目标端口", draft.targetPort, "443", KeyboardType.Number) { draft = draft.copy(targetPort = it.filter(Char::isDigit)) }
            }

            PortMapFormCard {
                val durationOptions = buildList {
                    if (draft.originalExpiresAt != null) add("保持原有效期")
                    addAll(listOf("1小时", "6小时", "24小时", "永久"))
                }
                PortMapChoice("有效期", draft.duration, durationOptions) { draft = draft.copy(duration = it) }
                PortMapTextField("最大连接", draft.maxConnections, "32", KeyboardType.Number) { draft = draft.copy(maxConnections = it.filter(Char::isDigit)) }
                PortMapTextField("空闲超时", draft.idleTimeoutSec, "300 秒", KeyboardType.Number) { draft = draft.copy(idleTimeoutSec = it.filter(Char::isDigit)) }
            }

            AnimatedVisibility(error.isNotBlank()) {
                Text(error, color = PortRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    error = validateDraft(draft, portRange)
                    if (error.isBlank()) onSave(draft)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text("保存映射", fontWeight = FontWeight.Black) }
        }
    }
}

private fun validateDraft(draft: PortMapDraft, range: IntRange): String {
    val listen = draft.listenPort.toIntOrNull()
    val target = draft.targetPort.toIntOrNull()
    if (draft.name.trim().isBlank()) return "请输入规则名称"
    if (listen == null || listen !in range) return "监听端口必须在 ${range.first}-${range.last}"
    if (target == null || target !in 1..65535) return "目标端口无效"
    if (draft.mode == "6to4" && draft.targetIpv4.isBlank()) return "请输入目标 IPv4"
    if (draft.mode == "6to6" && draft.targetMode == "ipv6_full" && draft.targetIpv6.isBlank()) return "请输入目标 IPv6"
    if (draft.mode == "6to6" && draft.targetMode == "ipv6_suffix" && draft.targetIpv6Suffix.isBlank()) return "请输入 IPv6 后缀"
    if ((draft.maxConnections.toIntOrNull() ?: 0) !in 1..256) return "最大连接数应为 1-256"
    if ((draft.idleTimeoutSec.toIntOrNull() ?: 0) !in 30..3600) return "空闲超时应为 30-3600 秒"
    return ""
}

@Composable
private fun PortMapFormCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(23.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE9F0F8)), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun PortMapTextField(label: String, value: String, hint: String, keyboardType: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text(hint, fontSize = 11.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(15.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f).height(50.dp)
        )
    }
}

@Composable
private fun PortMapReadOnlyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PortBlue)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortMapChoice(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                shape = RoundedCornerShape(15.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.menuAnchor().fillMaxWidth().height(50.dp)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { onPick(item); expanded = false }) }
            }
        }
    }
}

@Composable
private fun PortMapDevicePicker(devices: List<DeviceItem>, onPick: (DeviceItem) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("目标设备", Modifier.width(78.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f))
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Rounded.Devices, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("从在线设备填充", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(device.remark.ifBlank { device.name }, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("${device.ip} · ${device.mac}", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                            }
                        },
                        onClick = { onPick(device); expanded = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortMapDetailSheet(
    rule: PortMapRule,
    api: PortMapApi,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var history by remember(rule.id) { mutableStateOf<List<PortMapHistoryPoint>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(rule.id) {
        while (true) {
            runCatching { api.history(rule.id, 60) }.onSuccess { history = it }
            delay(10_000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFF7FAFE), dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("TCP · ${rule.modeText}${if (rule.targetMode == "ipv6_suffix") " · IPv6 后缀匹配" else ""}", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
                }
                TextButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("编辑") }
            }

            PortMapFormCard {
                PortMapDetailLine("状态", portMapStatus(rule).text, portMapStatus(rule).color)
                PortMapDetailLine("监听", "[::]:${rule.listenPort}")
                PortMapDetailLine("配置目标", rule.targetText)
                if (rule.runtime.resolvedTarget.isNotBlank()) PortMapDetailLine("实际目标", rule.runtime.resolvedTarget, PortBlue)
                PortMapDetailLine("运行时间", formatPortDuration(rule.runtime.startedAt?.let { max(0, System.currentTimeMillis() / 1000 - it) }))
                PortMapDetailLine("剩余时间", remainingText(rule.expiresAt))
                PortMapDetailLine("最近解析", formatEpoch(rule.runtime.lastResolvedAt))
            }

            PortMapFormCard {
                Text("流量统计", fontSize = 13.5.sp, fontWeight = FontWeight.Black)
                Row {
                    PortMapBigMetric("上传", formatPortBytes(rule.runtime.totalUploadBytes), PortBlue, Modifier.weight(1f))
                    PortMapBigMetric("下载", formatPortBytes(rule.runtime.totalDownloadBytes), PortGreen, Modifier.weight(1f))
                }
                Row {
                    PortMapBigMetric("当前连接", rule.runtime.activeConnections.toString(), PortCyan, Modifier.weight(1f))
                    PortMapBigMetric("最大连接", rule.maxConnections.toString(), PortSlate, Modifier.weight(1f))
                }
            }

            PortMapFormCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("近 1 小时吞吐", Modifier.weight(1f), fontSize = 13.5.sp, fontWeight = FontWeight.Black)
                    Text("60 秒采样", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .42f))
                }
                PortMapTrafficChart(history, Modifier.fillMaxWidth().height(190.dp))
            }

            if (rule.runtime.lastError.isNotBlank()) {
                Surface(shape = RoundedCornerShape(18.dp), color = PortRed.copy(alpha = .08f)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("最近错误", color = PortRed, fontWeight = FontWeight.Black, fontSize = 11.sp)
                        Text(rule.runtime.lastError, color = PortRed, fontSize = 10.sp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth()) {
                Button(onClick = onToggle, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = if (rule.isRunning || rule.enabled) PortRed else PortBlue)) {
                    Icon(if (rule.isRunning || rule.enabled) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text(if (rule.isRunning || rule.enabled) "停止映射" else "启动映射", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = PortRed)) {
                    Icon(Icons.Rounded.Delete, null)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除端口映射？", fontWeight = FontWeight.Black) },
            text = { Text("删除后会通知路由器停止并移除该规则。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除", color = PortRed, fontWeight = FontWeight.Black) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PortMapDetailLine(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(74.dp), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f), fontWeight = FontWeight.Bold)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), fontSize = 11.5.sp, color = color, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PortMapBigMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f), fontWeight = FontWeight.Bold)
        Text(value, fontSize = 17.sp, color = color, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun PortMapTrafficChart(points: List<PortMapHistoryPoint>, modifier: Modifier = Modifier) {
    val rates = remember(points) {
        points.zipWithNext().mapNotNull { (a, b) ->
            val dt = (b.time - a.time).coerceAtLeast(1)
            val up = (b.uploadBytes - a.uploadBytes).coerceAtLeast(0) / dt.toFloat()
            val down = (b.downloadBytes - a.downloadBytes).coerceAtLeast(0) / dt.toFloat()
            Triple(b.time, up, down)
        }.takeLast(60)
    }
    if (rates.size < 2) {
        Box(modifier.background(Color(0xFFF4F8FC), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text("等待流量采样", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f), fontSize = 10.5.sp)
        }
        return
    }
    val maxValue = rates.maxOf { max(it.second, it.third) }.coerceAtLeast(1f)
    Canvas(modifier.background(Color(0xFFF7FAFE), RoundedCornerShape(16.dp)).padding(10.dp)) {
        val w = size.width
        val h = size.height
        repeat(4) { i ->
            val y = h * i / 3f
            drawLine(Color(0xFFCBD5E1).copy(alpha = .35f), Offset(0f, y), Offset(w, y), 1.dp.toPx())
        }
        fun linePath(index: Int): Path = Path().apply {
            rates.forEachIndexed { i, row ->
                val value = if (index == 1) row.second else row.third
                val x = if (rates.lastIndex == 0) 0f else w * i / rates.lastIndex.toFloat()
                val y = h - (value / maxValue) * (h - 5.dp.toPx())
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(linePath(1), PortBlue, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(linePath(2), PortGreen, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        ChartLegendDot(PortBlue, "上传")
        Spacer(Modifier.width(16.dp))
        ChartLegendDot(PortGreen, "下载")
    }
}

@Composable
private fun ChartLegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape)); Spacer(Modifier.width(4.dp)); Text(text, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
    }
}

private fun ipv6Suffix64(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val address = InetAddress.getByName(raw.substringBefore('%').substringBefore('/')) as Inet6Address
        val b = address.address
        val groups = (8 until 16 step 2).map { i -> ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF) }
        "::" + groups.joinToString(":") { it.toString(16) }
    }.getOrDefault("")
}

private fun formatPortBytes(value: Long): String {
    if (value < 1024) return "${value}B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var idx = -1
    while (v >= 1024 && idx < units.lastIndex) { v /= 1024; idx++ }
    return String.format(Locale.US, if (v >= 100) "%.0f%s" else "%.1f%s", v, units[idx.coerceAtLeast(0)])
}

private fun formatPortDuration(seconds: Long?): String {
    val sec = seconds ?: return "—"
    val day = sec / 86400
    val hour = (sec % 86400) / 3600
    val minute = (sec % 3600) / 60
    return when {
        day > 0 -> "${day}天${hour}小时"
        hour > 0 -> "${hour}小时${minute}分"
        else -> "${minute}分"
    }
}

private fun remainingText(epoch: Long?): String {
    if (epoch == null) return "永久"
    val remain = epoch - System.currentTimeMillis() / 1000L
    return if (remain <= 0) "已到期" else formatPortDuration(remain)
}

private fun formatEpoch(epoch: Long?): String {
    if (epoch == null) return "—"
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epoch * 1000))
}

private fun portMapTimeText(rule: PortMapRule): String = when {
    rule.isRunning -> "已运行 ${formatPortDuration(rule.runtime.startedAt?.let { max(0, System.currentTimeMillis() / 1000 - it) })} · 剩余 ${remainingText(rule.expiresAt)}"
    rule.runtime.state == "waiting_target" -> "目标未解析 · 每 30 秒重试"
    rule.runtime.state == "waiting_agent" -> "命令等待路由器领取"
    else -> "尚未启动"
}
