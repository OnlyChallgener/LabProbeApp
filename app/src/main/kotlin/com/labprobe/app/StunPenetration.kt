package com.labprobe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StunBlue = LabV2.Primary
private val StunGreen = LabV2.Green
private val StunAmber = LabV2.Amber
private val StunRed = LabV2.Red

data class StunRuntime(
    val state: String = "stopped",
    val resolvedTarget: String = "",
    val publicEndpoint: String = "",
    val publicIp: String = "",
    val publicPort: Int = 0,
    val mappingUpdatedAt: Long? = null,
    val activeConnections: Long = 0,
    val activePeers: Long = 0,
    val totalUploadBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
    val lastError: String = "",
)

data class StunRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val listenPort: Int,
    val targetIpv4: String,
    val targetPort: Int,
    val serviceType: String,
    val transportProtocol: String,
    val actualState: String,
    val firewallState: String,
    val runtime: StunRuntime,
) {
    val ready: Boolean get() = actualState == "mapped" && firewallState == "ready" && runtime.publicEndpoint.isNotBlank()
}

data class StunAddressRecord(val endpoint: String, val updatedAt: Long)

data class StunSnapshot(
    val rules: List<StunRule> = emptyList(),
    val agentOnline: Boolean = false,
    val agentLastSeenAt: String = "",
)

private fun parseEpoch(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    val value = obj.optLong(key)
    return value.takeIf { it > 0L }?.let { if (it > 10_000_000_000L) it / 1000L else it }
}

private fun parseStunRule(json: JSONObject): StunRule {
    val runtime = json.optJSONObject("runtime") ?: JSONObject()
    return StunRule(
        id = cleanApiText(json.optString("id")),
        name = cleanApiText(json.optString("name")),
        enabled = json.optBoolean("enabled", false),
        listenPort = json.optInt("listenPort"),
        targetIpv4 = cleanApiText(json.optString("targetIpv4")),
        targetPort = json.optInt("targetPort"),
        serviceType = cleanApiText(json.optString("serviceType", "Custom")).ifBlank { "Custom" },
        transportProtocol = cleanApiText(json.optString("transportProtocol", "TCP")).uppercase(Locale.ROOT),
        actualState = cleanApiText(json.optString("actualState", runtime.optString("state", "stopped"))),
        firewallState = cleanApiText(json.optString("firewallState", "pending")),
        runtime = StunRuntime(
            state = cleanApiText(runtime.optString("state", "stopped")),
            resolvedTarget = cleanApiText(runtime.optString("resolvedTarget")),
            publicEndpoint = cleanApiText(runtime.optString("publicEndpoint")),
            publicIp = cleanApiText(runtime.optString("publicIp")),
            publicPort = runtime.optInt("publicPort"),
            mappingUpdatedAt = parseEpoch(runtime, "mappingUpdatedAt"),
            activeConnections = runtime.optLong("activeConnections"),
            activePeers = runtime.optLong("activePeers"),
            totalUploadBytes = runtime.optLong("totalUploadBytes"),
            totalDownloadBytes = runtime.optLong("totalDownloadBytes"),
            lastError = cleanApiText(runtime.optString("lastError")),
        ),
    )
}

class StunApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)
    suspend fun list(): StunSnapshot = withContext(Dispatchers.IO) {
        val root = JSONObject(hubApi.requestText("/api/stun"))
        val array = root.optJSONArray("rules") ?: JSONArray()
        StunSnapshot(
            rules = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parseStunRule) },
            agentOnline = root.optBoolean("agentOnline", false),
            agentLastSeenAt = cleanApiText(root.optString("agentLastSeenAt")),
        )
    }
    suspend fun create(draft: StunDraft): StunRule = withContext(Dispatchers.IO) {
        parseStunRule(JSONObject(hubApi.requestText("/api/stun", "POST", draft.toJson().toString())).getJSONObject("rule"))
    }
    suspend fun update(id: String, draft: StunDraft): StunRule = withContext(Dispatchers.IO) {
        parseStunRule(JSONObject(hubApi.requestText("/api/stun/$id", "PUT", draft.toJson().toString())).getJSONObject("rule"))
    }
    suspend fun action(id: String, action: String) = withContext(Dispatchers.IO) { hubApi.requestText("/api/stun/$id/$action", "POST", "{}") }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { hubApi.requestText("/api/stun/$id", "DELETE") }
    suspend fun addresses(id: String): List<StunAddressRecord> = withContext(Dispatchers.IO) {
        val array = JSONObject(hubApi.requestText("/api/stun/$id/addresses")).optJSONArray("addresses") ?: JSONArray()
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { StunAddressRecord(cleanApiText(it.optString("endpoint")), parseEpoch(it, "updatedAt") ?: 0L) } }.filter { it.endpoint.isNotBlank() }
    }
}

data class StunDraft(
    val id: String = "",
    val serviceType: String = "HTTPS",
    val transportProtocol: String = "TCP",
    val targetIpv4: String = "",
    val targetPort: String = "443",
    val name: String = "",
) {
    fun toJson() = JSONObject().apply {
        if (id.isNotBlank()) put("id", id)
        put("serviceType", serviceType)
        put("transportProtocol", transportProtocol)
        put("targetIpv4", targetIpv4.trim())
        put("targetPort", targetPort.toIntOrNull() ?: 0)
        name.trim().takeIf { it.isNotBlank() }?.let { put("name", it) }
        put("enabled", true)
    }
    companion object {
        fun from(rule: StunRule) = StunDraft(rule.id, rule.serviceType, rule.transportProtocol, rule.targetIpv4, rule.targetPort.toString(), rule.name)
    }
}

internal fun stunTemplate(type: String): PortMapServiceTemplate = PORT_MAP_SERVICE_TEMPLATES.firstOrNull { it.serviceType.equals(type, true) } ?: PORT_MAP_SERVICE_TEMPLATES.last()
internal fun applyStunService(draft: StunDraft, template: PortMapServiceTemplate) = draft.copy(serviceType = template.serviceType, transportProtocol = template.defaultProtocol, targetPort = template.targetPort?.toString().orEmpty())
private fun formatStunBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
private fun formatStunTime(epoch: Long): String = if (epoch <= 0) "未知时间" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epoch * 1000L))
private fun copyStunAddress(context: Context, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("STUN 地址", value))
    toast(context, "地址已复制")
}

@Composable
fun StunPenetrationScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { StunApi(prefs) }
    val presenceStore = remember(prefs.hub, prefs.token, prefs.hubDns) { AgentPresenceStoreRegistry.get(prefs) }
    val liveAgent by presenceStore.state.collectAsState()
    var snapshot by remember { mutableStateOf(StunSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<StunDraft?>(null) }
    var historyTarget by remember { mutableStateOf<StunRule?>(null) }
    var history by remember { mutableStateOf<List<StunAddressRecord>>(emptyList()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    fun refresh(silent: Boolean = false) = scope.launch {
        if (!silent) loading = true
        runCatching { api.list() }.onSuccess {
            val effectiveAgent = liveAgent
            snapshot = it.copy(
                agentOnline = effectiveAgent?.online == true || it.agentOnline,
                agentLastSeenAt = effectiveAgent?.lastSeenAt?.ifBlank { it.agentLastSeenAt } ?: it.agentLastSeenAt,
            )
            error = ""
            it.rules.filter { rule -> rule.ready }.forEach { rule -> upsertStunFavorite(prefs, rule) }
        }.onFailure {
            error = if (snapshot.rules.isNotEmpty() && (liveAgent?.online == true || snapshot.agentOnline)) {
                "Agent 在线，状态暂未同步；已保留全部穿透设置"
            } else {
                it.message ?: "无法读取 STUN 穿透状态"
            }
        }
        loading = false
    }
    LaunchedEffect(Unit) {
        refresh()
    }
    LaunchedEffect(liveAgent?.lastSeenAt, snapshot.agentOnline) {
        while (true) {
            delay(if (liveAgent?.online == true || snapshot.agentOnline) 3_000L else 8_000L)
            refresh(true)
        }
    }
    DetailShell(
        title = "STUN 穿透",
        subtitle = "公网 IPv4 · NAT 映射 · Agent 自动保活",
        onBack = onBack,
        unifiedTypography = true,
        sectionGap = LabV2.SectionGap,
    ) {
        StunIntro(
            agentOnline = snapshot.agentOnline,
            agentLastSeenAt = snapshot.agentLastSeenAt,
            loading = loading,
            onRefresh = { refresh() },
            onAdd = { editor = StunDraft() },
        )
        if (error.isNotBlank()) {
            Surface(
                shape = LabV2.CompactCardShape,
                color = if (error.startsWith("Agent 在线")) StunAmber.copy(alpha = .08f) else StunRed.copy(alpha = .08f),
                border = BorderStroke(1.dp, (if (error.startsWith("Agent 在线")) StunAmber else StunRed).copy(alpha = .18f)),
            ) {
                Text(error, color = if (error.startsWith("Agent 在线")) StunAmber else StunRed, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
            }
        }
        if (loading && snapshot.rules.isEmpty()) {
            LabCoreCard(compact = true) {
                Text("正在同步 STUN 设置，页面可以继续操作", style = LabTypography.Supporting)
            }
        } else if (!loading && snapshot.rules.isEmpty()) {
            StunEmpty { editor = StunDraft() }
        } else {
            snapshot.rules.forEach { rule ->
                StunRuleCard(
                    rule = rule,
                    menuOpen = menuFor == rule.id,
                    onMenu = { menuFor = if (menuFor == rule.id) null else rule.id },
                    onCopy = { rule.runtime.publicEndpoint.takeIf { it.isNotBlank() }?.let { copyStunAddress(context, it) } },
                    onHistory = {
                        historyTarget = rule
                        scope.launch { history = runCatching { api.addresses(rule.id) }.getOrDefault(emptyList()) }
                    },
                    onEdit = { menuFor = null; editor = StunDraft.from(rule) },
                    onToggle = {
                        menuFor = null
                        scope.launch { runCatching { api.action(rule.id, if (rule.enabled) "stop" else "start") }.onFailure { error = it.message ?: "操作失败" }; refresh() }
                    },
                    onDelete = {
                        menuFor = null
                        scope.launch { runCatching { api.delete(rule.id) }.onFailure { error = it.message ?: "删除失败" }; removeStunFavorite(prefs, rule.id); refresh() }
                    },
                )
            }
        }
    }
    editor?.let { draft -> StunEditorDialog(draft, onDismiss = { editor = null }) { saved ->
        scope.launch {
            val result = if (saved.id.isBlank()) runCatching { api.create(saved) } else runCatching { api.update(saved.id, saved) }
            result.onSuccess { editor = null; refresh() }.onFailure { error = it.message ?: "保存失败" }
        }
    } }
    historyTarget?.let { rule -> StunAddressHistoryDialog(rule, history, onDismiss = { historyTarget = null }, onCopy = { copyStunAddress(context, it) }) }
}

@Composable private fun StunIntro(agentOnline: Boolean, agentLastSeenAt: String, loading: Boolean, onRefresh: () -> Unit, onAdd: () -> Unit) {
    LabCoreCard(compact = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabV2ToolIcon(Icons.Rounded.Public, StunBlue, size = 46)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("内网服务 STUN 穿透", style = LabTypography.CardTitle)
                val presenceText = if (agentOnline) "Agent 在线 · LabRelay 自动转发至内网终端" else "Agent 状态暂未同步 · 设置可直接创建"
                Text(presenceText, color = if (agentOnline) StunGreen else StunAmber, style = LabTypography.Supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (agentLastSeenAt.isNotBlank() && !agentOnline) {
                    Text("最近上报 $agentLastSeenAt", style = LabTypography.Caption, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = StunBlue)
                else Icon(Icons.Rounded.Refresh, "刷新", tint = StunBlue)
            }
            Button(
                onClick = onAdd,
                shape = LabV2.ButtonShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StunBlue),
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("新建", style = LabTypography.Button)
            }
        }
    }
}

@Composable private fun StunEmpty(onAdd: () -> Unit) {
    LabCoreCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LabV2ToolIcon(Icons.Rounded.Public, StunBlue, size = 52, muted = true)
            Spacer(Modifier.height(8.dp)); Text("还没有 STUN 穿透", style = LabTypography.CardTitle)
            Text("选择服务与内网地址即可开始", style = LabTypography.Supporting, color = LabV2.InkMuted)
            Spacer(Modifier.height(13.dp)); OutlinedButton(onClick = onAdd, shape = LabV2.ButtonShape) { Text("创建穿透", style = LabTypography.Button) }
        }
    }
}

@Composable private fun StunRuleCard(rule: StunRule, menuOpen: Boolean, onMenu: () -> Unit, onCopy: () -> Unit, onHistory: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val stateText = when {
        !rule.enabled -> "已停止"
        rule.ready -> "运行中"
        rule.actualState == "firewall_error" -> "防火墙未就绪"
        rule.actualState == "waiting_agent" -> "命令待 Agent 同步"
        rule.actualState == "mapped" || rule.actualState == "mapping" -> "正在校验公网地址"
        else -> "正在同步"
    }
    val stateColor = when { rule.ready -> StunGreen; !rule.enabled -> LabV2.InkMuted; rule.actualState == "firewall_error" -> StunRed; else -> StunAmber }
    LabCoreCard(compact = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(stateColor, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = LabTypography.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${rule.serviceType} · ${rule.transportProtocol}", style = LabTypography.Supporting, color = LabV2.InkMuted)
                }
                Surface(shape = RoundedCornerShape(99.dp), color = stateColor.copy(alpha = .10f)) {
                    Text(stateText, color = stateColor, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
                Box { IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "更多") }; DropdownMenu(expanded = menuOpen, onDismissRequest = onMenu) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = onEdit)
                    DropdownMenuItem(text = { Text(if (rule.enabled) "停止穿透" else "开始穿透") }, leadingIcon = { Icon(if (rule.enabled) Icons.Rounded.PauseCircleOutline else Icons.Rounded.PlayCircleOutline, null) }, onClick = onToggle)
                    DropdownMenuItem(text = { Text("删除", color = StunRed) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = StunRed) }, onClick = onDelete)
                } }
            }
            val endpoint = rule.runtime.publicEndpoint
            Surface(
                shape = LabCoreSurface.InnerShape,
                color = if (rule.ready) StunGreen.copy(alpha = .07f) else LabCoreSurface.Inner,
                border = BorderStroke(1.dp, if (rule.ready) StunGreen.copy(alpha = .16f) else LabCoreSurface.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (endpoint.isBlank()) "公网地址获取中" else endpoint, fontWeight = FontWeight.Bold, color = if (endpoint.isBlank()) LabV2.InkMuted else StunGreen, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (endpoint.isNotBlank()) Surface(shape = RoundedCornerShape(99.dp), color = StunAmber.copy(alpha = .12f)) {
                                Text("动态地址", color = StunAmber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                            }
                        }
                        Text("LabRelay 转发至 ${rule.targetIpv4}:${rule.targetPort}", color = LabV2.InkMuted, fontSize = LabTypography.Caption.fontSize)
                    }
                    if (endpoint.isNotBlank()) IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "复制", tint = StunGreen, modifier = Modifier.size(19.dp)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StunTraffic(Icons.Rounded.Download, "下载", rule.runtime.totalDownloadBytes, StunBlue)
                Spacer(Modifier.width(16.dp)); StunTraffic(Icons.Rounded.Upload, "上传", rule.runtime.totalUploadBytes, StunGreen)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onHistory, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp), shape = LabV2.ButtonShape) { Icon(Icons.Rounded.History, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("地址记录", fontSize = 12.sp) }
            }
            rule.runtime.lastError.takeIf { it.isNotBlank() && !rule.ready }?.let { Text(it, color = StunRed, fontSize = LabTypography.Caption.fontSize, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable private fun StunTraffic(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bytes: Long, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("$label ${formatStunBytes(bytes)}", color = LabV2.InkMuted, fontSize = LabTypography.Caption.fontSize) }
}

@Composable private fun StunEditorDialog(initial: StunDraft, onDismiss: () -> Unit, onSave: (StunDraft) -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    val template = stunTemplate(draft.serviceType)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).heightIn(max = 680.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (draft.id.isBlank()) "新建 STUN 穿透" else "编辑 STUN 穿透", style = LabTypography.PageTitle)
                Text("只需填写服务协议和内网目标，Agent 会自动同步 LabRelay。", style = LabTypography.Supporting, color = LabV2.InkMuted)
                Text("服务", style = LabTypography.SectionTitle)
                PORT_MAP_SERVICE_TEMPLATES.chunked(3).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        group.forEach { item ->
                            FilterChip(
                                selected = draft.serviceType == item.serviceType,
                                onClick = { draft = applyStunService(draft, item) },
                                label = { Text(item.label, fontSize = LabTypography.Supporting.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StunBlue, selectedLabelColor = Color.White),
                            )
                        }
                        repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (template.protocols.size > 1) {
                    Text("传输协议", style = LabTypography.SectionTitle)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        template.protocols.sorted().forEach { protocol ->
                            FilterChip(
                                selected = draft.transportProtocol == protocol,
                                onClick = { draft = draft.copy(transportProtocol = protocol) },
                                label = { Text(protocol, fontSize = LabTypography.Supporting.fontSize) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StunBlue, selectedLabelColor = Color.White),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.targetIpv4,
                    onValueChange = { draft = draft.copy(targetIpv4 = it) },
                    label = { Text("内网地址") },
                    placeholder = { Text("例如 192.168.5.46") },
                    singleLine = true,
                    shape = LabV2.FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = LabCoreSurface.Inner,
                        focusedBorderColor = StunBlue,
                        unfocusedBorderColor = LabCoreSurface.Border,
                    ),
                )
                OutlinedTextField(
                    value = draft.targetPort,
                    onValueChange = { draft = draft.copy(targetPort = it.filter(Char::isDigit)) },
                    label = { Text("目标端口") },
                    placeholder = { Text("例如 443") },
                    singleLine = true,
                    shape = LabV2.FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = LabCoreSurface.Inner,
                        focusedBorderColor = StunBlue,
                        unfocusedBorderColor = LabCoreSurface.Border,
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, shape = LabV2.ButtonShape, modifier = Modifier.weight(1f)) { Text("取消", style = LabTypography.Button) }
                    Button(onClick = { onSave(draft) }, shape = LabV2.ButtonShape, modifier = Modifier.weight(1.35f), colors = ButtonDefaults.buttonColors(containerColor = StunBlue)) {
                        Text(if (draft.id.isBlank()) "开始穿透" else "保存", style = LabTypography.Button)
                    }
                }
            }
        }
    }
}

@Composable private fun StunAddressHistoryDialog(rule: StunRule, addresses: List<StunAddressRecord>, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f),
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("最近 STUN 地址", style = LabTypography.PageTitle)
                Text("仅保留最近 3 条；收藏页始终使用当前地址。", style = LabTypography.Supporting, color = LabV2.InkMuted)
                val rows = addresses.ifEmpty { rule.runtime.publicEndpoint.takeIf { it.isNotBlank() }?.let { listOf(StunAddressRecord(it, rule.runtime.mappingUpdatedAt ?: 0L)) }.orEmpty() }
                if (rows.isEmpty()) Text("尚未获取到公网地址", style = LabTypography.Supporting, color = LabV2.InkMuted)
                rows.take(3).forEach { row ->
                    Surface(
                        shape = LabCoreSurface.InnerShape,
                        color = LabCoreSurface.Inner,
                        border = BorderStroke(1.dp, LabCoreSurface.Border),
                        modifier = Modifier.fillMaxWidth().clickable { onCopy(row.endpoint) },
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.endpoint, fontWeight = FontWeight.SemiBold, color = LabV2.Ink)
                                Text(formatStunTime(row.updatedAt), style = LabTypography.Caption, color = LabV2.InkMuted)
                            }
                            Icon(Icons.Rounded.ContentCopy, "复制", tint = StunBlue, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, shape = LabV2.ButtonShape) { Text("关闭", style = LabTypography.Button) }
                }
            }
        }
    }
}
