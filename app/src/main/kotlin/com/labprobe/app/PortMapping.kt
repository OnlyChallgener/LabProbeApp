package com.labprobe.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import kotlin.math.min
import kotlin.math.roundToInt

private val PortBlue = Color(0xFF1677F2)
private val PortCyan = Color(0xFF13B8D4)
private val PortGreen = Color(0xFF12B981)
private val PortRed = Color(0xFFEF5350)
private val PortSlate = Color(0xFF718096)
private val PortSheetBg = Color(0xFFFFFFFF)
private val PortPopupBg = Color(0xFFFFFFFF)

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
    val leaseSeconds: Long,
    val maxConnections: Int,
    val idleTimeoutSec: Int,
    val desiredState: String = "",
    val actualState: String = "",
    val syncState: String = "",
    val revision: Long = 0L,
    val runtime: PortMapRuntime = PortMapRuntime()
) {
    val effectiveActualState: String get() = actualState.ifBlank { runtime.state }
    val effectiveDesiredState: String get() = desiredState.ifBlank { if (enabled) "running" else "stopped" }
    val isRunning: Boolean get() = effectiveActualState == "running"
    val isActiveOrPending: Boolean get() = effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || syncState == "syncing"
    val shouldStop: Boolean get() = effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || (syncState == "syncing" && effectiveDesiredState == "running")
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
    val portMax: Int,
    val protocolVersion: String = "",
    val hubVersion: String = "",
    val agentVersion: String = "",
    val relayVersion: String = "",
    val capabilities: String = ""
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
        leaseSeconds = o.optLong("leaseSeconds", 0L).coerceAtLeast(0L),
        maxConnections = o.optInt("maxConnections", 32),
        idleTimeoutSec = o.optInt("idleTimeoutSec", 300),
        desiredState = cleanApiText(o.optString("desiredState", if (o.optBoolean("enabled")) "running" else "stopped")),
        actualState = cleanApiText(o.optString("actualState", r.optString("state"))),
        syncState = cleanApiText(o.optString("syncState", "synced")),
        revision = o.optLong("revision", 0L),
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
            router = cleanApiText(root.optString("router", "router")),
            lastSeenAt = cleanApiText(root.optString("agentLastSeenAt")),
            portMin = range.optInt("min", 20000),
            portMax = range.optInt("max", 20020),
            protocolVersion = cleanApiText(root.optString("protocolVersion")),
            hubVersion = cleanApiText(root.optString("hubVersion")),
            agentVersion = cleanApiText(root.optString("agentVersion")),
            relayVersion = cleanApiText(root.optString("relayVersion")),
            capabilities = compactPortCapabilities(root.opt("capabilities"))
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

private fun compactPortCapabilities(raw: Any?): String = when (raw) {
    is JSONArray -> (0 until raw.length()).map { cleanApiText(raw.optString(it)) }.filter { it.isNotBlank() }.joinToString(" · ")
    is JSONObject -> raw.keys().asSequence().filter { raw.optBoolean(it, false) }.toList().joinToString(" · ")
    else -> cleanApiText(raw?.toString())
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
    val leaseSeconds: Long = 0L,
    val maxConnections: String = "32",
    val idleTimeoutSec: String = "300"
) {
    fun toJson(): JSONObject {
        val now = System.currentTimeMillis() / 1000L
        val selectedLease = when (duration) {
            "保持原有效期" -> leaseSeconds.coerceAtLeast(0L)
            "1小时" -> 3600L
            "6小时" -> 6L * 3600L
            "24小时" -> 24L * 3600L
            else -> 0L
        }
        val expires = when (duration) {
            "保持原有效期" -> originalExpiresAt
            "永久" -> null
            else -> now + selectedLease
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
            put("leaseSeconds", selectedLease)
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
            leaseSeconds = rule.leaseSeconds,
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
    var agent by remember { mutableStateOf(PortMapAgentInfo(false, "router", "", 20000, 20020)) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editDraft by remember { mutableStateOf<PortMapDraft?>(null) }

    fun markSyncing(rule: PortMapRule, action: String) {
        rules = rules.map {
            if (it.id == rule.id) it.copy(
                desiredState = if (action == "start") "running" else "stopped",
                syncState = "syncing"
            ) else it
        }
    }

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
            "运行中" -> it.effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || it.syncState == "syncing"
            "已停止" -> it.effectiveActualState == "stopped" && it.syncState != "syncing"
            "已到期" -> it.effectiveActualState == "expired"
            else -> true
        }
    }
    val selected = selectedId?.let { id -> rules.firstOrNull { it.id == id } }

    if (selected != null) {
        BackHandler { selectedId = null }
        PortMapDetailPage(
            rule = selected,
            api = api,
            onDismiss = { selectedId = null },
            onEdit = { editDraft = PortMapDraft.from(selected); selectedId = null },
            onToggle = {
                val action = if (selected.shouldStop) "stop" else "start"
                markSyncing(selected, action)
                scope.launch {
                    runCatching { api.action(selected.id, action) }
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
        return
    }

    DetailShell("端口映射", "IPv6 入口 · Rust 四层反代 · 6→4 / 6→6", onBack) {
        PortMapAgentCard(agent, loading) { scope.launch { refresh() } }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf("全部", "运行中", "已停止", "已到期").forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.padding(end = 5.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PortBlue, selectedLabelColor = Color.White)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
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
                        val action = if (rule.shouldStop) "stop" else "start"
                        markSyncing(rule, action)
                        scope.launch {
                            runCatching { api.action(rule.id, action) }
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

}

private fun nextPort(rules: List<PortMapRule>, agent: PortMapAgentInfo): Int {
    val used = rules.map { it.listenPort }.toSet()
    return (agent.portMin..agent.portMax).firstOrNull { it !in used } ?: agent.portMin
}

@Composable
private fun PortMapAgentCard(agent: PortMapAgentInfo, loading: Boolean, onRefresh: () -> Unit) {
    val color = if (agent.online) PortGreen else PortRed
    LabV2Card(compact = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabV2ToolIcon(Icons.Rounded.SwapHoriz, PortBlue, size = 46)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.router.ifBlank { "路由器" }, fontWeight = FontWeight.Black, fontSize = 14.5.sp, color = LabV2.Ink)
                val versions = listOfNotNull(
                    agent.hubVersion.takeIf { it.isNotBlank() }?.let { "Hub $it" },
                    agent.agentVersion.takeIf { it.isNotBlank() }?.let { "Agent $it" },
                    agent.relayVersion.takeIf { it.isNotBlank() }?.let { "Relay $it" }
                ).joinToString(" · ")
                Text(versions.ifBlank { "Rust LabRelay · TCP ${agent.portMin}–${agent.portMax}" }, fontSize = 10.3.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(if (agent.online) "Agent 在线" else "Agent 未连接", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (agent.lastSeenAt.isNotBlank()) Text(" · ${agent.lastSeenAt}", fontSize = 9.3.sp, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (agent.protocolVersion.isNotBlank() || agent.capabilities.isNotBlank()) {
                    Text(listOfNotNull(agent.protocolVersion.takeIf { it.isNotBlank() }?.let { "协议 $it" }, agent.capabilities.takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = 9.2.sp, lineHeight = 11.sp, color = LabV2.InkFaint, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    LabV2Card {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LabV2ToolIcon(Icons.Rounded.SwapHoriz, PortBlue, size = 52)
            Spacer(Modifier.height(10.dp))
            Text("暂无端口映射", fontWeight = FontWeight.Black, color = LabV2.Ink)
            Text("创建 6→4 或 6→6 TCP 四层反代规则", fontSize = 10.5.sp, color = LabV2.InkMuted)
            Spacer(Modifier.height(13.dp))
            Button(onClick = onAdd, shape = LabV2.ButtonShape) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(5.dp))
                Text("新建映射")
            }
        }
    }
}

@Composable
private fun PortMapRuleCard(rule: PortMapRule, onOpen: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit) {
    val status = portMapStatus(rule)
    LabV2Card(modifier = Modifier.clickable(onClick = onOpen), compact = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(status.color, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(rule.name, Modifier.weight(1f), fontSize = 14.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(99.dp), color = status.color.copy(alpha = .10f)) {
                    Text(status.text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = status.color, fontSize = 10.3.sp, lineHeight = 12.sp, fontWeight = FontWeight.Black)
                }
            }
            Text("TCP · ${rule.modeText} · :${rule.listenPort}${if (rule.targetMode == "ipv6_suffix") " · 后缀匹配" else ""}", fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted)
            Text("→ ${rule.targetText}", fontSize = 11.2.sp, lineHeight = 13.5.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = if (rule.mode == "6to4") 1 else 2, overflow = TextOverflow.Clip)
            if (rule.runtime.resolvedTarget.isNotBlank() && rule.targetMode == "ipv6_suffix") {
                Text("实际目标 ${rule.runtime.resolvedTarget}", color = PortBlue, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(portMapStateTrail(rule), fontSize = 9.8.sp, lineHeight = 11.sp, color = status.color, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PortMapCompactMetric("连接", rule.runtime.activeConnections.toString(), PortCyan, Modifier.weight(1f))
                PortMapCompactMetric("上传", formatPortBytes(rule.runtime.totalUploadBytes), PortBlue, Modifier.weight(1f))
                PortMapCompactMetric("下载", formatPortBytes(rule.runtime.totalDownloadBytes), PortGreen, Modifier.weight(1f))
            }
            val error = portMapErrorText(rule.runtime.lastError)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (error.isNotBlank() && (rule.effectiveActualState in setOf("error", "expired") || rule.syncState == "error")) Text(error, color = PortRed, fontSize = 10.2.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(portMapTimeText(rule), fontSize = 10.2.sp, lineHeight = 12.sp, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
                OutlinedButton(onClick = onToggle, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (rule.shouldStop) PortRed else PortBlue)) {
                    Text(if (rule.shouldStop) "停止" else "启动", fontSize = 10.8.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(5.dp))
                OutlinedButton(onClick = onEdit, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp)) {
                    Text("编辑", fontSize = 10.8.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun PortMapCompactMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.height(46.dp), shape = RoundedCornerShape(13.dp), color = color.copy(alpha = .075f), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .10f))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center) {
            Text(label, fontSize = 9.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
            Text(value, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
}

private data class PortMapStatusUi(val text: String, val color: Color)
private fun portMapStatus(rule: PortMapRule): PortMapStatusUi = when {
    rule.syncState == "agent_offline" -> PortMapStatusUi("路由器 Agent 离线", PortRed)
    rule.syncState == "syncing" -> PortMapStatusUi("正在同步", PortBlue)
    rule.syncState == "error" -> PortMapStatusUi("同步失败", PortRed)
    rule.effectiveActualState == "starting" -> PortMapStatusUi("启动中", PortBlue)
    rule.effectiveActualState == "running" -> PortMapStatusUi("运行中", PortGreen)
    rule.effectiveActualState == "waiting_target" -> PortMapStatusUi("等待目标 IPv6", Color(0xFFF59E0B))
    rule.effectiveActualState == "waiting_agent" -> PortMapStatusUi("等待 Agent", Color(0xFFF59E0B))
    rule.effectiveActualState == "draining" -> PortMapStatusUi("正在停止现有连接", Color(0xFFF59E0B))
    rule.effectiveActualState == "expired" -> PortMapStatusUi("已到期", PortSlate)
    rule.effectiveActualState == "error" -> PortMapStatusUi("执行失败", PortRed)
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
    var showDevicePicker by remember { mutableStateOf(false) }
    val selectedDevice = remember(draft.targetMac, devices) {
        devices.firstOrNull { cleanMac(it.mac).equals(cleanMac(draft.targetMac), ignoreCase = true) }
    }

    LabBottomSheet(onDismiss = onDismiss, scrollable = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (draft.id.isBlank()) "新建映射" else "编辑映射", fontSize = 20.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
                Text("TCP IPv6 入口 · Rust 四层反代 · 不修改防火墙", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
            }
            TextButton(onClick = {
                error = validateDraft(draft, portRange)
                if (error.isBlank()) onSave(draft)
            }) { Text("保存", fontWeight = FontWeight.Black) }
        }

        LabV2Card(compact = true) {
            Text("基础设置", fontSize = 12.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted)
            PortMapV2Field("规则名称", draft.name, "例如：NAS HTTPS") { draft = draft.copy(name = it) }
            Text("映射类型", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted)
            LabV2SegmentedControl(
                options = listOf("IPv6 → IPv4", "IPv6 → IPv6"),
                selected = if (draft.mode == "6to4") "IPv6 → IPv4" else "IPv6 → IPv6",
                onSelect = { selected ->
                    draft = draft.copy(mode = if (selected.endsWith("IPv4")) "6to4" else "6to6")
                }
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 330.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        PortMapV2Field("监听端口", draft.listenPort, "${portRange.first}-${portRange.last}", keyboardType = KeyboardType.Number) {
                            draft = draft.copy(listenPort = it.filter(Char::isDigit))
                        }
                        PortMapV2ReadOnly("协议", "TCP")
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        PortMapV2Field("监听端口", draft.listenPort, "${portRange.first}-${portRange.last}", Modifier.weight(1.25f), KeyboardType.Number) {
                            draft = draft.copy(listenPort = it.filter(Char::isDigit))
                        }
                        PortMapV2ReadOnly("协议", "TCP", Modifier.weight(.75f))
                    }
                }
            }
            PortMapV2ReadOnly("监听地址", "[::]:${draft.listenPort.ifBlank { "—" }} · IPv6 only", copyable = true, accent = PortBlue)
        }

        LabV2Card(compact = true) {
            Text("目标设备", fontSize = 12.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted)
            PortMapSelectedDevice(
                device = selectedDevice,
                mode = draft.mode,
                targetMode = draft.targetMode,
                fallbackMac = draft.targetMac,
                onClick = { showDevicePicker = true }
            )

            if (draft.mode == "6to4") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 340.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            PortMapV2Field("目标 IPv4", draft.targetIpv4, "192.168.5.46") { draft = draft.copy(targetIpv4 = it) }
                            PortMapV2Field("目标端口", draft.targetPort, "443", keyboardType = KeyboardType.Number) {
                                draft = draft.copy(targetPort = it.filter(Char::isDigit))
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            PortMapV2Field("目标 IPv4", draft.targetIpv4, "192.168.5.46", Modifier.weight(1.45f)) { draft = draft.copy(targetIpv4 = it) }
                            PortMapV2Field("目标端口", draft.targetPort, "443", Modifier.weight(.75f), KeyboardType.Number) {
                                draft = draft.copy(targetPort = it.filter(Char::isDigit))
                            }
                        }
                    }
                }
            } else {
                Text("目标方式", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted)
                LabV2SegmentedControl(
                    options = listOf("后缀匹配", "完整 IPv6"),
                    selected = if (draft.targetMode == "ipv6_suffix") "后缀匹配" else "完整 IPv6",
                    onSelect = { selected -> draft = draft.copy(targetMode = if (selected == "后缀匹配") "ipv6_suffix" else "ipv6_full") }
                )
                if (draft.targetMode == "ipv6_suffix") {
                    PortMapV2Field("目标 MAC", draft.targetMac, "6c:1f:f7:76:71:04") { draft = draft.copy(targetMac = it) }
                    PortMapV2Field("IPv6 后缀", draft.targetIpv6Suffix, "例如 ::8dc0:a9e5:169d:a7c") { draft = draft.copy(targetIpv6Suffix = it) }
                    Text(
                        "按 MAC + 后 64 位 + 当前 LAN 前缀解析。目标消失时保持等待，不继续使用历史地址。",
                        fontSize = 9.6.sp,
                        lineHeight = 13.sp,
                        color = LabV2.InkMuted
                    )
                } else {
                    PortMapV2Field("目标 IPv6", draft.targetIpv6, "2409:...::1234") { draft = draft.copy(targetIpv6 = it) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 330.dp) {
                        PortMapV2Field("目标端口", draft.targetPort, "443", keyboardType = KeyboardType.Number) {
                            draft = draft.copy(targetPort = it.filter(Char::isDigit))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            PortMapV2Field("目标端口", draft.targetPort, "443", Modifier.weight(.72f), KeyboardType.Number) {
                                draft = draft.copy(targetPort = it.filter(Char::isDigit))
                            }
                            PortMapV2ReadOnly(
                                "解析策略",
                                if (draft.targetMode == "ipv6_suffix") "当前前缀优先" else "固定地址",
                                Modifier.weight(1.28f),
                                accent = if (draft.targetMode == "ipv6_suffix") PortGreen else PortSlate
                            )
                        }
                    }
                }
            }
        }

        LabV2Card(compact = true) {
            Text("运行策略", fontSize = 12.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted)
            val durationOptions = buildList {
                if (draft.originalExpiresAt != null) add("保持原有效期")
                addAll(listOf("1小时", "6小时", "24小时", "永久"))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 340.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        PortMapV2Select("有效期", draft.duration, durationOptions) { draft = draft.copy(duration = it) }
                        PortMapV2Field("最大连接", draft.maxConnections, "32", keyboardType = KeyboardType.Number) {
                            draft = draft.copy(maxConnections = it.filter(Char::isDigit))
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        PortMapV2Select("有效期", draft.duration, durationOptions, Modifier.weight(1.25f)) { draft = draft.copy(duration = it) }
                        PortMapV2Field("最大连接", draft.maxConnections, "32", Modifier.weight(.75f), KeyboardType.Number) {
                            draft = draft.copy(maxConnections = it.filter(Char::isDigit))
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 330.dp) {
                    PortMapV2Field("空闲超时", draft.idleTimeoutSec, "300", keyboardType = KeyboardType.Number, suffix = "秒") {
                        draft = draft.copy(idleTimeoutSec = it.filter(Char::isDigit))
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        PortMapV2Field("空闲超时", draft.idleTimeoutSec, "300", Modifier.weight(.75f), KeyboardType.Number, suffix = "秒") {
                            draft = draft.copy(idleTimeoutSec = it.filter(Char::isDigit))
                        }
                        PortMapV2ReadOnly("到期重启", "沿用本次时长", Modifier.weight(1.25f), accent = PortGreen)
                    }
                }
            }
        }

        AnimatedVisibility(error.isNotBlank()) {
            Surface(shape = RoundedCornerShape(16.dp), color = PortRed.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, PortRed.copy(alpha = .13f))) {
                Text(error, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), color = PortRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Button(
            onClick = {
                error = validateDraft(draft, portRange)
                if (error.isBlank()) onSave(draft)
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = LabV2.ButtonShape,
            colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary)
        ) { Text("保存映射", fontWeight = FontWeight.Black) }
        Spacer(Modifier.height(8.dp))
    }

    if (showDevicePicker) {
        PortMapDevicePickerDialog(
            devices = devices,
            mode = draft.mode,
            targetMode = draft.targetMode,
            selectedMac = draft.targetMac,
            onDismiss = { showDevicePicker = false },
            onPick = { device ->
                val bestIpv6 = device.pickIpv6().best.orEmpty()
                draft = draft.copy(
                    targetMac = device.mac,
                    targetIpv4 = device.ip.ifBlank { draft.targetIpv4 },
                    targetIpv6 = bestIpv6.ifBlank { draft.targetIpv6 },
                    targetIpv6Suffix = ipv6Suffix64(bestIpv6).ifBlank { draft.targetIpv6Suffix }
                )
                showDevicePicker = false
            }
        )
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
private fun PortMapV2Field(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String = "",
    onChange: (String) -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 10.4.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
        Surface(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = LabV2.FieldShape,
            color = LabV2.Field,
            border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.BorderStrong.copy(alpha = .82f)),
            tonalElevation = 0.dp
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = TextStyle(fontSize = 12.8.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isBlank()) Text(hint, fontSize = 11.5.sp, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    }
                )
                if (suffix.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(suffix, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PortMapV2ReadOnly(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    copyable: Boolean = false,
    accent: Color = LabV2.Ink
) {
    val ctx = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 10.4.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
        Surface(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = LabV2.FieldShape,
            color = accent.copy(alpha = .055f),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .11f)),
            tonalElevation = 0.dp
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 13.dp).horizontalScroll(rememberScrollState()).clickable(enabled = copyable) { copy(ctx, value) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortMapV2Select(
    label: String,
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 10.4.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            Surface(
                modifier = Modifier.menuAnchor().fillMaxWidth().height(48.dp),
                shape = LabV2.FieldShape,
                color = LabV2.Field,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (expanded) LabV2.Primary.copy(alpha = .65f) else LabV2.BorderStrong.copy(alpha = .82f)),
                tonalElevation = 0.dp
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f), fontSize = 12.6.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp), tint = LabV2.InkMuted)
                }
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = Color.White,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 0.dp,
                shadowElevation = 7.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = if (option == value) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = LabV2.Primary) }) else null,
                        onClick = { onPick(option); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun PortMapSelectedDevice(
    device: DeviceItem?,
    mode: String,
    targetMode: String,
    fallbackMac: String,
    onClick: () -> Unit
) {
    val address = when {
        device == null -> ""
        mode == "6to4" -> cleanApiText(device.ip)
        else -> device.pickIpv6().best.orEmpty()
    }
    val profile = device?.let(::inferDeviceProfile)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = LabV2.Field,
        border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.BorderStrong.copy(alpha = .78f)),
        tonalElevation = 0.dp
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (device != null && profile != null) {
                LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = 36)
            } else {
                LabV2ToolIcon(Icons.Rounded.Devices, LabV2.Primary, size = 36)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device?.remark?.ifBlank { device.name }?.ifBlank { "已选设备" } ?: "从在线设备填充",
                    fontSize = 12.2.sp,
                    fontWeight = FontWeight.Black,
                    color = LabV2.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = when {
                    device != null && address.isNotBlank() -> if (mode == "6to6" && targetMode == "ipv6_suffix") "$address · 后缀 ${ipv6Suffix64(address)}" else address
                    fallbackMac.isNotBlank() -> fallbackMac
                    else -> if (mode == "6to6") "仅显示已获取可用 IPv6 的设备" else "显示设备 IPv4 与 MAC"
                }
                Text(detail, fontSize = 9.7.sp, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = LabV2.InkMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PortMapDevicePickerDialog(
    devices: List<DeviceItem>,
    mode: String,
    targetMode: String,
    selectedMac: String,
    onDismiss: () -> Unit,
    onPick: (DeviceItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val rows = remember(devices, mode, query) {
        devices.filter { d ->
            val address = if (mode == "6to4") cleanApiText(d.ip) else d.pickIpv6().best.orEmpty()
            val text = "${d.remark} ${d.name} ${d.hostName} ${d.mac} $address".lowercase(Locale.getDefault())
            address.isNotBlank() && (query.isBlank() || text.contains(query.lowercase(Locale.getDefault())))
        }
    }
    val unavailable = remember(devices, mode) {
        if (mode == "6to6") devices.filter { it.pickIpv6().best.isNullOrBlank() } else emptyList()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.84f),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFFFAFCFF),
            border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border),
            shadowElevation = 12.dp
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择目标设备", fontSize = 18.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
                        Text(
                            if (mode == "6to4") "当前显示设备 IPv4 地址" else if (targetMode == "ipv6_suffix") "当前显示全局 IPv6 与后 64 位" else "当前显示设备完整 IPv6 地址",
                            fontSize = 10.4.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LabV2.InkMuted
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null) }
                }
                PortMapV2Field("搜索", query, "设备名称 / IPv6 / MAC") { query = it }
                if (rows.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (mode == "6to6") "没有获取到可用 IPv6 的匹配设备" else "没有匹配设备", color = LabV2.InkMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rows, key = { cleanMac(it.mac).ifBlank { it.name + it.ip } }) { device ->
                            val address = if (mode == "6to4") cleanApiText(device.ip) else device.pickIpv6().best.orEmpty()
                            val profile = inferDeviceProfile(device)
                            val selected = cleanMac(device.mac).equals(cleanMac(selectedMac), ignoreCase = true)
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { onPick(device) },
                                shape = RoundedCornerShape(18.dp),
                                color = if (selected) LabV2.Primary.copy(alpha = .07f) else Color.White,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) LabV2.Primary.copy(alpha = .22f) else LabV2.Border),
                                tonalElevation = 0.dp
                            ) {
                                Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = 38)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(device.remark.ifBlank { device.name }.ifBlank { device.mac }, fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(address, fontSize = 9.7.sp, fontWeight = FontWeight.SemiBold, color = if (mode == "6to6") PortBlue else LabV2.InkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val extra = if (mode == "6to6" && targetMode == "ipv6_suffix") "后缀 ${ipv6Suffix64(address)} · ${device.mac}" else device.mac
                                        Text(extra, fontSize = 9.sp, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = LabV2.Primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                if (unavailable.isNotEmpty()) {
                    Text("另有 ${unavailable.size} 台设备尚未获取可用 IPv6，已自动隐藏。", fontSize = 9.8.sp, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortMapDetailPage(
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
    DetailShell(rule.name, "TCP · ${rule.modeText}${if (rule.targetMode == "ipv6_suffix") " · IPv6 后缀匹配" else ""}", onDismiss) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(99.dp), color = portMapStatus(rule).color.copy(alpha = .10f)) {
                    Text(portMapStatus(rule).text, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = portMapStatus(rule).color, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("编辑") }
            }

            LabV2Card(compact = true) {
                PortMapDetailLine("状态", portMapStatus(rule).text, portMapStatus(rule).color)
                PortMapDetailLine("期望 / 同步", "${portMapDesiredText(rule)} · ${portMapSyncText(rule)}")
                PortMapDetailLine("监听", "[::]:${rule.listenPort}")
                PortMapDetailLine("配置目标", rule.targetText)
                if (rule.runtime.resolvedTarget.isNotBlank()) PortMapDetailLine("实际目标", rule.runtime.resolvedTarget, PortBlue)
                PortMapDetailLine("运行时间", formatPortDuration(rule.runtime.startedAt?.let { max(0, System.currentTimeMillis() / 1000 - it) }))
                PortMapDetailLine("剩余时间", portMapRemainingText(rule))
                PortMapDetailLine("启动有效期", if (rule.leaseSeconds > 0) "每次启动 ${formatPortDuration(rule.leaseSeconds)}" else "永久")
                PortMapDetailLine("最近解析", formatEpoch(rule.runtime.lastResolvedAt))
                if (rule.revision > 0L) PortMapDetailLine("配置版本", "revision ${rule.revision}")
            }

            LabV2Card(compact = true, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("流量统计", fontSize = 12.sp, lineHeight = 13.sp, fontWeight = FontWeight.Black)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PortMapBigMetric("上传", formatPortBytes(rule.runtime.totalUploadBytes), PortBlue, Modifier.weight(1f))
                        PortMapBigMetric("下载", formatPortBytes(rule.runtime.totalDownloadBytes), PortGreen, Modifier.weight(1f))
                        PortMapBigMetric("当前连接", rule.runtime.activeConnections.toString(), PortCyan, Modifier.weight(1f))
                        PortMapBigMetric("最大连接", rule.maxConnections.toString(), PortSlate, Modifier.weight(1f))
                    }
                }
            }

            LabV2Card(compact = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("近 1 小时吞吐", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Black)
                    Text("60 秒采样", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .42f))
                }
                PortMapTrafficChart(history, Modifier.fillMaxWidth().height(184.dp))
            }

            if (rule.runtime.lastError.isNotBlank() && (rule.effectiveActualState in setOf("error", "expired") || rule.syncState == "error")) {
                Surface(shape = RoundedCornerShape(18.dp), color = PortRed.copy(alpha = .08f)) {
                    Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("最近错误", color = PortRed, fontWeight = FontWeight.Black, fontSize = 11.5.sp)
                        Text(portMapErrorText(rule.runtime.lastError), color = PortRed, fontSize = 10.8.sp, lineHeight = 13.sp)
                    }
                }
            }

        Row(Modifier.fillMaxWidth()) {
            Button(onClick = onToggle, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape, colors = ButtonDefaults.buttonColors(containerColor = if (rule.shouldStop) PortRed else PortBlue)) {
                Icon(if (rule.shouldStop) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(5.dp))
                Text(if (rule.shouldStop) "停止映射" else "启动映射", fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.height(48.dp), shape = LabV2.ButtonShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = PortRed)) {
                Icon(Icons.Rounded.Delete, null)
            }
        }
        Spacer(Modifier.height(2.dp))
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
private fun PortMapDetailLine(label: String, value: String, color: Color = LabV2.Ink) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(76.dp).padding(top = 1.dp), fontSize = 10.8.sp, lineHeight = 14.sp, color = LabV2.InkMuted, fontWeight = FontWeight.Bold)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 15.sp, color = color, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun PortMapBigMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    PortMapCompactMetric(label = label, value = value, color = color, modifier = modifier)
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
        Box(modifier.background(Color(0xFFF7FAFE), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text("等待流量采样", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f), fontSize = 10.2.sp)
        }
        return
    }
    val maxValue = rates.maxOf { max(it.second, it.third) }.coerceAtLeast(1f)
    var selectedIndex by remember(rates) { mutableStateOf<Int?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFF8FBFF), RoundedCornerShape(14.dp))
                .pointerInput(rates) {
                    detectTapGestures { point ->
                        val left = 36.dp.toPx()
                        val right = 7.dp.toPx()
                        val plotWidth = (size.width - left - right).coerceAtLeast(1f)
                        selectedIndex = (((point.x - left) / plotWidth).coerceIn(0f, 1f) * rates.lastIndex).roundToInt()
                    }
                }
        ) {
            val left = 36.dp.toPx()
            val right = 7.dp.toPx()
            val top = 9.dp.toPx()
            val bottom = 21.dp.toPx()
            val plotWidth = size.width - left - right
            val plotHeight = size.height - top - bottom
            val axisColor = Color(0xFF94A3B8).copy(alpha = .72f)
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(100, 116, 139)
                textSize = 8.5.sp.toPx()
            }

            drawLine(axisColor, Offset(left, top), Offset(left, top + plotHeight), 0.8.dp.toPx())
            drawLine(axisColor, Offset(left, top + plotHeight), Offset(left + plotWidth, top + plotHeight), 0.8.dp.toPx())

            val yTicks = listOf(0f, maxValue / 2f, maxValue)
            yTicks.forEachIndexed { index, value ->
                val y = top + plotHeight - (index / 2f) * plotHeight
                drawLine(axisColor, Offset(left - 3.dp.toPx(), y), Offset(left, y), 0.8.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(formatPortRate(value), 1.dp.toPx(), y + 3.dp.toPx(), labelPaint)
            }

            val xLabels = listOf("60分", "40分", "20分", "现在")
            xLabels.forEachIndexed { index, label ->
                val x = left + plotWidth * index / 3f
                drawLine(axisColor, Offset(x, top + plotHeight), Offset(x, top + plotHeight + 3.dp.toPx()), 0.8.dp.toPx())
                val textWidth = labelPaint.measureText(label)
                val drawX = when (index) { 0 -> x; 3 -> x - textWidth; else -> x - textWidth / 2f }
                drawContext.canvas.nativeCanvas.drawText(label, drawX, size.height - 3.dp.toPx(), labelPaint)
            }

            fun seriesPoints(selector: (Triple<Long, Float, Float>) -> Float): List<Offset> = rates.mapIndexed { index, row ->
                val x = left + plotWidth * index / rates.lastIndex.toFloat()
                val y = top + plotHeight - (selector(row) / maxValue) * plotHeight
                Offset(x, y)
            }
            fun linePath(values: List<Offset>) = Path().apply {
                if (values.isEmpty()) return@apply
                moveTo(values.first().x, values.first().y)
                values.drop(1).forEach { lineTo(it.x, it.y) }
            }

            val upload = seriesPoints { it.second }
            val download = seriesPoints { it.third }
            drawPath(linePath(upload), PortBlue, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(linePath(download), PortGreen, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            selectedIndex?.coerceIn(0, rates.lastIndex)?.let { index ->
                val x = upload[index].x
                drawLine(Color(0xFF64748B).copy(alpha = .55f), Offset(x, top), Offset(x, top + plotHeight), 0.8.dp.toPx())
                drawCircle(PortBlue, 2.8.dp.toPx(), upload[index])
                drawCircle(PortGreen, 2.8.dp.toPx(), download[index])
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            ChartLegendDot(PortBlue, "上传")
            Spacer(Modifier.width(14.dp))
            ChartLegendDot(PortGreen, "下载")
            selectedIndex?.coerceIn(0, rates.lastIndex)?.let { index ->
                Spacer(Modifier.width(12.dp))
                Text("↑ ${formatPortRate(rates[index].second)}  ↓ ${formatPortRate(rates[index].third)}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
            }
        }
    }
}

private fun formatPortRate(value: Float): String = when {
    value >= 1024f * 1024f -> String.format(Locale.US, "%.1fMB/s", value / 1024f / 1024f)
    value >= 1024f -> String.format(Locale.US, "%.1fKB/s", value / 1024f)
    else -> "${value.roundToInt()}B/s"
}

@Composable
private fun ChartLegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape)); Spacer(Modifier.width(4.dp)); Text(text, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
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

private fun portMapDesiredText(rule: PortMapRule): String = when (rule.effectiveDesiredState) {
    "running" -> "期望启动"
    "stopped" -> "期望停止"
    else -> rule.effectiveDesiredState.ifBlank { "期望未知" }
}

private fun portMapSyncText(rule: PortMapRule): String = when (rule.syncState) {
    "synced" -> "已同步"
    "syncing" -> "正在同步"
    "agent_offline" -> "路由器 Agent 离线"
    "error" -> "同步失败"
    else -> rule.syncState.ifBlank { "同步状态未知" }
}

private fun portMapStateTrail(rule: PortMapRule): String = buildList {
    add(portMapDesiredText(rule))
    add(portMapSyncText(rule))
    if (rule.revision > 0L) add("r${rule.revision}")
}.joinToString(" · ")

private fun portMapErrorText(raw: String): String {
    val value = cleanApiText(raw)
    if (value.isBlank()) return ""
    val mappings = linkedMapOf(
        "PORT_IN_USE" to "监听端口已被占用",
        "LISTEN_PERMISSION" to "无权限监听该端口",
        "TARGET_TIMEOUT" to "目标连接超时",
        "TARGET_REFUSED" to "目标拒绝连接",
        "IPV6_NOT_FOUND" to "未找到设备 IPv6",
        "IPV6_AMBIGUOUS" to "IPv6 后缀对应多个设备",
        "TARGET_OUTSIDE_LAN" to "目标不在允许的 LAN 路由中",
        "MAX_CONNECTIONS" to "已达到最大连接数",
        "RULE_EXPIRED" to "规则已到期",
        "VERSION_MISMATCH" to "组件版本不兼容"
    )
    val upper = value.uppercase(Locale.US)
    mappings.entries.firstOrNull { upper.contains(it.key) }?.let { return it.value }
    if (upper.contains("RULE EXPIRED") || upper == "EXPIRED") return "规则已到期"
    return value
}

private fun portMapRemainingText(rule: PortMapRule): String {
    val expiry = rule.runtime.expiresAt ?: rule.expiresAt
    if (expiry == null) return "永久"
    val remain = expiry - System.currentTimeMillis() / 1000L
    if (remain > 0L) return formatPortDuration(remain)
    return if (rule.effectiveActualState in setOf("starting", "running") || rule.syncState == "syncing") "等待 Hub 更新" else "已到期"
}

private fun portMapTimeText(rule: PortMapRule): String = when {
    rule.syncState == "agent_offline" -> "等待路由器 Agent 恢复"
    rule.syncState == "syncing" -> if (rule.effectiveDesiredState == "stopped") "停止命令已提交 · 正在同步" else "启动命令已提交 · 正在同步"
    rule.effectiveActualState == "starting" -> "启动中 · 等待 Hub 返回实际状态"
    rule.effectiveActualState == "running" -> "已运行 ${formatPortDuration(rule.runtime.startedAt?.let { max(0, System.currentTimeMillis() / 1000 - it) })} · 剩余 ${portMapRemainingText(rule)}"
    rule.effectiveActualState == "waiting_target" -> "等待目标 IPv6 · 每 30 秒重试"
    rule.effectiveActualState == "waiting_agent" -> "命令等待路由器领取"
    rule.effectiveActualState == "draining" -> "正在停止现有连接"
    rule.effectiveActualState == "expired" -> "已到期${if (rule.leaseSeconds > 0) " · 再次启动后按 ${formatPortDuration(rule.leaseSeconds)} 重新计时" else ""}"
    rule.effectiveActualState == "error" -> portMapErrorText(rule.runtime.lastError).ifBlank { "执行失败" }
    else -> "尚未启动"
}
