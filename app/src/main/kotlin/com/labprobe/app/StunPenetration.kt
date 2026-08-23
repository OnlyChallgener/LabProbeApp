package com.labprobe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StunBlue = Color(0xFF1677F2)
private val StunGreen = Color(0xFF12B981)
private val StunAmber = Color(0xFFF59E0B)
private val StunRed = Color(0xFFEF5350)
private val StunCard = Color(0xFFFFFFFF)

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

private fun stunTemplate(type: String): PortMapServiceTemplate = PORT_MAP_SERVICE_TEMPLATES.firstOrNull { it.serviceType.equals(type, true) } ?: PORT_MAP_SERVICE_TEMPLATES.last()
private fun applyStunService(draft: StunDraft, template: PortMapServiceTemplate) = draft.copy(serviceType = template.serviceType, transportProtocol = template.defaultProtocol, targetPort = template.targetPort?.toString().orEmpty())
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
            snapshot = it
            error = ""
            it.rules.filter { rule -> rule.ready }.forEach { rule -> upsertStunFavorite(prefs, rule) }
        }.onFailure { error = it.message ?: "无法读取 STUN 穿透状态" }
        loading = false
    }
    LaunchedEffect(Unit) {
        refresh()
        while (true) { delay(5_000); refresh(true) }
    }
    Surface(color = Color(0xFFF5F8FC), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CompactTopBar("STUN 穿透", onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    StunIntro(agentOnline = snapshot.agentOnline, loading = loading, onRefresh = { refresh() }, onAdd = { editor = StunDraft() })
                }
                if (error.isNotBlank()) item {
                    Text(error, color = StunRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
                if (!loading && snapshot.rules.isEmpty()) item {
                    StunEmpty { editor = StunDraft() }
                }
                items(snapshot.rules, key = { it.id }) { rule ->
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
    }
    editor?.let { draft -> StunEditorDialog(draft, onDismiss = { editor = null }) { saved ->
        scope.launch {
            val result = if (saved.id.isBlank()) runCatching { api.create(saved) } else runCatching { api.update(saved.id, saved) }
            result.onSuccess { editor = null; refresh() }.onFailure { error = it.message ?: "保存失败" }
        }
    } }
    historyTarget?.let { rule -> StunAddressHistoryDialog(rule, history, onDismiss = { historyTarget = null }, onCopy = { copyStunAddress(context, it) }) }
}

@Composable private fun StunIntro(agentOnline: Boolean, loading: Boolean, onRefresh: () -> Unit, onAdd: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = StunCard)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = StunBlue.copy(alpha = .11f), modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Public, null, tint = StunBlue) } }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("内网服务 STUN 穿透", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(if (agentOnline) "Relay 在线 · 创建后自动放行 Hub 防火墙" else "等待 Relay 在线后开始穿透", color = if (agentOnline) StunGreen else StunAmber, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Rounded.Refresh, "刷新", tint = StunBlue) }
            Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Rounded.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("新建") }
        }
    }
}

@Composable private fun StunEmpty(onAdd: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Public, null, tint = StunBlue.copy(alpha = .65f), modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp)); Text("还没有 STUN 穿透", fontWeight = FontWeight.Bold)
            Text("选择服务与内网地址即可开始", color = Color(0xFF718096), fontSize = 12.sp)
            Spacer(Modifier.height(13.dp)); OutlinedButton(onClick = onAdd) { Text("创建穿透") }
        }
    }
}

@Composable private fun StunRuleCard(rule: StunRule, menuOpen: Boolean, onMenu: () -> Unit, onCopy: () -> Unit, onHistory: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val stateText = when {
        rule.ready -> "已就绪"
        !rule.enabled -> "已停止"
        rule.actualState == "firewall_error" -> "防火墙未就绪"
        rule.actualState == "mapped" || rule.actualState == "mapping" -> "正在确认"
        else -> "正在连接"
    }
    val stateColor = when { rule.ready -> StunGreen; !rule.enabled -> Color(0xFF718096); rule.actualState == "firewall_error" -> StunRed; else -> StunAmber }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${rule.serviceType} · ${rule.transportProtocol}", color = Color(0xFF718096), fontSize = 12.sp)
                }
                AssistChip(onClick = {}, label = { Text(stateText, fontSize = 11.sp) }, colors = AssistChipDefaults.assistChipColors(labelColor = stateColor, containerColor = stateColor.copy(alpha = .10f)))
                Box { IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "更多") }; DropdownMenu(expanded = menuOpen, onDismissRequest = onMenu) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = onEdit)
                    DropdownMenuItem(text = { Text(if (rule.enabled) "停止穿透" else "开始穿透") }, leadingIcon = { Icon(if (rule.enabled) Icons.Rounded.PauseCircleOutline else Icons.Rounded.PlayCircleOutline, null) }, onClick = onToggle)
                    DropdownMenuItem(text = { Text("删除", color = StunRed) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = StunRed) }, onClick = onDelete)
                } }
            }
            Spacer(Modifier.height(11.dp))
            val endpoint = rule.runtime.publicEndpoint
            Surface(shape = RoundedCornerShape(12.dp), color = if (rule.ready) StunGreen.copy(alpha = .08f) else Color(0xFFF4F7FA), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (endpoint.isBlank()) "正在获取公网地址…" else endpoint, fontWeight = FontWeight.Bold, color = if (endpoint.isBlank()) Color(0xFF718096) else Color(0xFF147D50), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("内网 ${rule.targetIpv4}:${rule.targetPort}", color = Color(0xFF718096), fontSize = 11.sp)
                    }
                    if (endpoint.isNotBlank()) IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "复制", tint = StunGreen, modifier = Modifier.size(19.dp)) }
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StunTraffic(Icons.Rounded.Download, "下载", rule.runtime.totalDownloadBytes, StunBlue)
                Spacer(Modifier.width(16.dp)); StunTraffic(Icons.Rounded.Upload, "上传", rule.runtime.totalUploadBytes, StunGreen)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onHistory, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { Icon(Icons.Rounded.History, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("地址记录", fontSize = 12.sp) }
            }
            rule.runtime.lastError.takeIf { it.isNotBlank() && !rule.ready }?.let { Text(it, color = StunRed, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable private fun StunTraffic(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bytes: Long, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("$label ${formatStunBytes(bytes)}", color = Color(0xFF52606D), fontSize = 11.sp) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StunEditorDialog(initial: StunDraft, onDismiss: () -> Unit, onSave: (StunDraft) -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    val template = stunTemplate(draft.serviceType)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (draft.id.isBlank()) "新建 STUN 穿透" else "编辑 STUN 穿透") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PORT_MAP_SERVICE_TEMPLATES.take(5).forEach { item -> FilterChip(selected = draft.serviceType == item.serviceType, onClick = { draft = applyStunService(draft, item) }, label = { Text(item.label, fontSize = 11.sp) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PORT_MAP_SERVICE_TEMPLATES.drop(5).forEach { item -> FilterChip(selected = draft.serviceType == item.serviceType, onClick = { draft = applyStunService(draft, item) }, label = { Text(item.label, fontSize = 11.sp) }) }
            }
            if (template.protocols.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { template.protocols.sorted().forEach { protocol -> FilterChip(selected = draft.transportProtocol == protocol, onClick = { draft = draft.copy(transportProtocol = protocol) }, label = { Text(protocol) }) } }
            OutlinedTextField(value = draft.targetIpv4, onValueChange = { draft = draft.copy(targetIpv4 = it) }, label = { Text("内网地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.targetPort, onValueChange = { draft = draft.copy(targetPort = it.filter(Char::isDigit)) }, label = { Text("目标端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(onClick = { onSave(draft) }) { Text(if (draft.id.isBlank()) "开始穿透" else "保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun StunAddressHistoryDialog(rule: StunRule, addresses: List<StunAddressRecord>, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("最近 STUN 地址") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("仅保留最近 3 条；收藏页始终使用当前地址。", color = Color(0xFF718096), fontSize = 12.sp)
            val rows = addresses.ifEmpty { rule.runtime.publicEndpoint.takeIf { it.isNotBlank() }?.let { listOf(StunAddressRecord(it, rule.runtime.mappingUpdatedAt ?: 0L)) }.orEmpty() }
            if (rows.isEmpty()) Text("尚未获取到公网地址", color = Color(0xFF718096))
            rows.take(3).forEach { row -> Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFF4F7FA), modifier = Modifier.fillMaxWidth().clickable { onCopy(row.endpoint) }) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(row.endpoint, fontWeight = FontWeight.SemiBold); Text(formatStunTime(row.updatedAt), color = Color(0xFF718096), fontSize = 11.sp) }; Icon(Icons.Rounded.ContentCopy, "复制", tint = StunBlue, modifier = Modifier.size(18.dp)) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
