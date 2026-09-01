package com.labprobe.app

import androidx.compose.ui.draw.clip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
private const val STUN_MAPPING_FRESH_SECONDS = 90L

data class StunRuntime(
    val state: String = "stopped",
    val resolvedTarget: String = "",
    val publicEndpoint: String = "",
    val publicIp: String = "",
    val publicPort: Int = 0,
    val mappingUpdatedAt: Long? = null,
    val mappingFresh: Boolean = false,
    val mappingAgeSeconds: Long? = null,
    val activeConnections: Long = 0,
    val activePeers: Long = 0,
    val totalUploadBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
    val lastError: String = "",
)

data class StunAddressLayer(
    val host: String = "",
    val port: Int = 0,
    val endpoint: String = "",
)

data class StunAddressLayers(
    val target: StunAddressLayer = StunAddressLayer(),
    val channel: StunAddressLayer = StunAddressLayer(),
    val public: StunAddressLayer = StunAddressLayer(),
    val publicReachabilityState: String = "unknown",
)

data class StunRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val listenPort: Int,
    val targetType: String = "manual",
    val targetIpv4: String,
    val targetPort: Int,
    val serviceType: String,
    val transportProtocol: String,
    val forwardMode: String,
    val actualState: String,
    val firewallState: String,
    val firewallMessage: String = "",
    val firewallOwner: String = "",
    val nativeMappingState: String,
    val nativeMappingMessage: String = "",
    val syncError: String = "",
    val runtime: StunRuntime,
    val addresses: StunAddressLayers = StunAddressLayers(),
) {
    val usesRouterNativeMapping: Boolean get() = forwardMode == "router_native"
    val ready: Boolean get() = enabled && actualState == "mapped" && runtime.mappingFresh && runtime.publicEndpoint.isNotBlank() && if (usesRouterNativeMapping) nativeMappingState == "ready" else firewallState == "ready"
}

data class StunAddressRecord(val endpoint: String, val updatedAt: Long)

data class StunSnapshot(
    val rules: List<StunRule> = emptyList(),
    val rulesLoaded: Boolean = false,
    val agentOnline: Boolean = false,
    val agentLastSeenAt: String = "",
)

internal fun parseStunSnapshot(root: JSONObject): StunSnapshot {
    val array = root.optJSONArray("rules")
    val parsedRules = if (array == null) emptyList() else (0 until array.length()).mapNotNull {
        array.optJSONObject(it)?.let(::parseStunRule)
    }
    val rulesLoaded = array != null && parsedRules.size == array.length() && parsedRules.all { it.id.isNotBlank() }
    return StunSnapshot(
        rules = parsedRules.filter { it.id.isNotBlank() },
        rulesLoaded = rulesLoaded,
        agentOnline = root.optBoolean("agentOnline", false),
        agentLastSeenAt = cleanApiText(root.optString("agentLastSeenAt")),
    )
}

private fun parseEpoch(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    val value = obj.optLong(key)
    return value.takeIf { it > 0L }?.let { if (it > 10_000_000_000L) it / 1000L else it }
}

private fun parseStunRule(json: JSONObject): StunRule {
    val runtime = json.optJSONObject("runtime") ?: JSONObject()
    val addresses = json.optJSONObject("addresses") ?: JSONObject()
    val targetAddress = addresses.optJSONObject("target") ?: JSONObject()
    val channelAddress = addresses.optJSONObject("channel") ?: JSONObject()
    val publicAddress = addresses.optJSONObject("public") ?: JSONObject()
    val mappingUpdatedAt = parseEpoch(runtime, "mappingUpdatedAt")
    val mappingAgeSeconds = if (runtime.has("mappingAgeSeconds") && !runtime.isNull("mappingAgeSeconds")) {
        runtime.optLong("mappingAgeSeconds").coerceAtLeast(0L)
    } else {
        mappingUpdatedAt?.let { (System.currentTimeMillis() / 1000L - it).coerceAtLeast(0L) }
    }
    val mappingFresh = if (runtime.has("mappingFresh")) {
        runtime.optBoolean("mappingFresh", false)
    } else {
        mappingAgeSeconds != null && mappingAgeSeconds <= STUN_MAPPING_FRESH_SECONDS
    }
    return StunRule(
        id = cleanApiText(json.optString("id")),
        name = cleanApiText(json.optString("name")),
        enabled = json.optBoolean("enabled", false),
        listenPort = json.optInt("listenPort"),
        targetType = cleanApiText(json.optString("targetType")).ifBlank { if (cleanApiText(json.optString("targetIpv4")) == "127.0.0.1") "router_self" else "manual" },
        targetIpv4 = cleanApiText(json.optString("targetIpv4")),
        targetPort = json.optInt("targetPort"),
        serviceType = cleanApiText(json.optString("serviceType", "Custom")).ifBlank { "Custom" },
        transportProtocol = cleanApiText(json.optString("transportProtocol", "TCP")).uppercase(Locale.ROOT),
        forwardMode = cleanApiText(json.optString("forwardMode")).ifBlank {
            "router_native"
        },
        actualState = cleanApiText(json.optString("actualState", runtime.optString("state", "stopped"))),
        firewallState = cleanApiText(json.optString("firewallState", "pending")),
        firewallMessage = cleanApiText(json.optString("firewallMessage")),
        firewallOwner = cleanApiText(json.optString("firewallOwner")),
        nativeMappingState = cleanApiText(json.optString("nativeMappingState", "pending")),
        nativeMappingMessage = cleanApiText(json.optString("nativeMappingMessage")),
        syncError = cleanApiText(json.optString("syncError")),
        runtime = StunRuntime(
            state = cleanApiText(runtime.optString("state", "stopped")),
            resolvedTarget = cleanApiText(runtime.optString("resolvedTarget")),
            publicEndpoint = cleanApiText(runtime.optString("publicEndpoint")),
            publicIp = cleanApiText(runtime.optString("publicIp")),
            publicPort = runtime.optInt("publicPort"),
            mappingUpdatedAt = mappingUpdatedAt,
            mappingFresh = mappingFresh,
            mappingAgeSeconds = mappingAgeSeconds,
            activeConnections = runtime.optLong("activeConnections"),
            activePeers = runtime.optLong("activePeers"),
            totalUploadBytes = runtime.optLong("totalUploadBytes"),
            totalDownloadBytes = runtime.optLong("totalDownloadBytes"),
            lastError = cleanApiText(runtime.optString("lastError")),
        ),
        addresses = StunAddressLayers(
            target = StunAddressLayer(
                host = cleanApiText(targetAddress.optString("host", json.optString("targetIpv4"))),
                port = targetAddress.optInt("port", json.optInt("targetPort")),
                endpoint = cleanApiText(targetAddress.optString("endpoint")),
            ),
            channel = StunAddressLayer(
                host = cleanApiText(channelAddress.optString("host", "0.0.0.0")),
                port = channelAddress.optInt("port", json.optInt("listenPort")),
                endpoint = cleanApiText(channelAddress.optString("endpoint")),
            ),
            public = StunAddressLayer(
                host = cleanApiText(publicAddress.optString("host", runtime.optString("publicIp"))),
                port = publicAddress.optInt("port", runtime.optInt("publicPort")),
                endpoint = cleanApiText(publicAddress.optString("endpoint", runtime.optString("publicEndpoint"))),
            ),
            publicReachabilityState = cleanApiText(publicAddress.optString("reachabilityState", "unknown")),
        ),
    )
}

class StunApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)
    suspend fun list(): StunSnapshot = withContext(Dispatchers.IO) {
        parseStunSnapshot(JSONObject(hubApi.requestText("/api/stun")))
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
    val enabled: Boolean = true,
    val serviceType: String = "HTTPS",
    val transportProtocol: String = "TCP",
    val targetType: String = "manual",
    val targetIpv4: String = "",
    val targetPort: String = "443",
    val name: String = "",
) {
    fun toJson() = JSONObject().apply {
        if (id.isNotBlank()) put("id", id)
        put("serviceType", serviceType)
        put("transportProtocol", transportProtocol)
        put("targetType", targetType)
        put("targetIpv4", targetIpv4.trim())
        put("targetPort", targetPort.toIntOrNull() ?: 0)
        put("name", name.trim())
        put("enabled", enabled)
    }
    companion object {
        fun from(rule: StunRule) = StunDraft(
            id = rule.id,
            enabled = rule.enabled,
            serviceType = rule.serviceType,
            transportProtocol = rule.transportProtocol,
            targetType = rule.targetType,
            targetIpv4 = rule.targetIpv4,
            targetPort = rule.targetPort.toString(),
            name = rule.name.takeUnless(::isGeneratedStunRuleName).orEmpty(),
        )
    }
}

internal fun stunTemplate(type: String): PortMapServiceTemplate = PORT_MAP_SERVICE_TEMPLATES.firstOrNull { it.serviceType.equals(type, true) } ?: PORT_MAP_SERVICE_TEMPLATES.last()
internal fun applyStunService(draft: StunDraft, template: PortMapServiceTemplate) = draft.copy(serviceType = template.serviceType, transportProtocol = template.defaultProtocol, targetPort = template.targetPort?.toString().orEmpty())
internal fun switchStunTargetType(draft: StunDraft, targetType: String): StunDraft = when (targetType) {
    "router_self" -> draft.copy(targetType = "router_self", targetIpv4 = "127.0.0.1")
    "device" -> draft.copy(targetType = "device", targetIpv4 = if (draft.targetType == "router_self") "" else draft.targetIpv4)
    else -> draft.copy(targetType = "manual", targetIpv4 = if (draft.targetType == "router_self") "" else draft.targetIpv4)
}
private val generatedStunRuleNamePattern = Regex("^.+ · (?:(?:\\d{1,3}\\.){3}\\d{1,3}|路由器本机):\\d+$")
private fun isGeneratedStunRuleName(value: String): Boolean = generatedStunRuleNamePattern.matches(value.trim())
internal fun stunRuleTitle(rule: StunRule): String = rule.name.trim().let {
    if (it.isBlank() || isGeneratedStunRuleName(it)) {
        "${rule.serviceType.trim().ifBlank { "自定义" }} 穿透"
    } else {
        it
    }
}
internal fun stunDraftValidationError(draft: StunDraft): String? {
    if (draft.targetType !in setOf("router_self", "device", "manual")) return "请选择目标类型"
    val ipv4 = draft.targetIpv4.trim()
    val validIpv4 = ipv4.split('.').let { parts -> parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255 } }
    if (draft.targetType != "router_self" && !validIpv4) return "请输入有效的内网 IPv4 地址"
    if ((draft.targetPort.toIntOrNull() ?: 0) !in 1..65535) return "目标端口必须是 1–65535"
    if (draft.name.trim().length > 64) return "规则备注最多 64 个字符"
    return null
}
internal fun stunAddressForCopy(serviceType: String, endpoint: String): String {
    return serviceAddressForCopy(serviceType, endpoint)
}
private fun formatStunBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
private fun formatStunTime(epoch: Long): String = if (epoch <= 0) "未知时间" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epoch * 1000L))
private fun copyStunAddress(context: Context, serviceType: String, endpoint: String) {
    val value = stunAddressForCopy(serviceType, endpoint)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("STUN 地址", value))
    toast(context, "地址已复制")
}

@Composable
fun StunPenetrationScreen(
    prefs: AppPrefs,
    onBack: () -> Unit,
    onOpenSsh: (String, Int) -> Unit = { _, _ -> },
    onOpenWireGuard: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { StunApi(prefs) }
    val presenceStore = remember(prefs.hub, prefs.token, prefs.hubDns) { AgentPresenceStoreRegistry.get(prefs) }
    val routerRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val liveAgent by presenceStore.state.collectAsState()
    val ddnsResource by routerRepository.labProbeDdns.collectAsState()
    val nativeDdnsResource by routerRepository.ddns.collectAsState()
    val ddnsSnapshot = ddnsResource.value
    val nativeDdnsRecords = nativeDdnsResource.value.orEmpty()
    var snapshot by remember { mutableStateOf(StunSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<StunDraft?>(null) }
    var editorError by remember { mutableStateOf("") }
    var editorSaving by remember { mutableStateOf(false) }
    var historyTarget by remember { mutableStateOf<StunRule?>(null) }
    var history by remember { mutableStateOf<List<StunAddressRecord>>(emptyList()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val refreshMutex = remember { Mutex() }
    fun refresh(silent: Boolean = false) = scope.launch {
        if (!refreshMutex.tryLock()) return@launch
        try {
            if (!silent) loading = true
            val latest = api.list()
            val effectiveAgent = liveAgent
            val effectiveAgentOnline = effectiveAgent?.online == true || latest.agentOnline
            snapshot = latest.copy(
                rules = if (latest.rulesLoaded) latest.rules else snapshot.rules,
                agentOnline = effectiveAgentOnline,
                agentLastSeenAt = effectiveAgent?.lastSeenAt?.ifBlank { latest.agentLastSeenAt } ?: latest.agentLastSeenAt,
            )
            error = if (!latest.rulesLoaded) {
                "Hub 本次未返回 STUN 规则，已保留现有设置"
            } else {
                ""
            }
            if (latest.rulesLoaded) {
                reconcileStunFavorites(prefs, latest.rules, ddnsSnapshot, nativeDdnsRecords)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = if (snapshot.rules.isNotEmpty() && (liveAgent?.online == true || snapshot.agentOnline)) {
                "Agent 在线，状态暂未同步；已保留全部穿透设置"
            } else {
                uiMessageZh(failure.message).ifBlank { "无法读取 STUN 穿透状态" }
            }
        } finally {
            loading = false
            refreshMutex.unlock()
        }
    }
    BackHandler(enabled = editor == null && historyTarget == null, onBack = onBack)
    LaunchedEffect(Unit) {
        routerRepository.refreshLabProbeDdns(false)
        routerRepository.refreshDdns(false)
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
            onAdd = { editorError = ""; editor = StunDraft() },
        )
        if (error.isNotBlank()) {
            val informationalError = error.startsWith("Agent 在线") || error.contains("已保留")
            Surface(
                shape = LabV2.CompactCardShape,
                color = if (informationalError) StunAmber.copy(alpha = .08f) else StunRed.copy(alpha = .08f),
                border = BorderStroke(1.dp, (if (informationalError) StunAmber else StunRed).copy(alpha = .18f)),
            ) {
                Text(error, color = if (informationalError) StunAmber else StunRed, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
            }
        }
        if (loading && snapshot.rules.isEmpty()) {
            LabCoreCard(compact = true) {
                Text("正在同步 STUN 设置，页面可以继续操作", style = LabTypography.Supporting)
            }
        } else if (!loading && snapshot.rulesLoaded && snapshot.rules.isEmpty()) {
            StunEmpty { editorError = ""; editor = StunDraft() }
        } else if (!loading && !snapshot.rulesLoaded && snapshot.rules.isEmpty()) {
            LabCoreCard(compact = true) {
                Text("STUN 规则状态暂未同步", style = LabTypography.Supporting, color = LabV2.InkMuted)
            }
        } else {
            snapshot.rules.forEach { rule ->
                StunRuleCard(
                    rule = rule,
                    agentOnline = snapshot.agentOnline,
                    menuOpen = menuFor == rule.id,
                    onOpenSsh = onOpenSsh,
                    onOpenWireGuard = onOpenWireGuard,
                    onMenu = { menuFor = if (menuFor == rule.id) null else rule.id },
                    onCopy = { rule.runtime.publicEndpoint.takeIf { it.isNotBlank() }?.let { copyStunAddress(context, rule.serviceType, it) } },
                    onHistory = {
                        historyTarget = rule
                        scope.launch { history = runCatching { api.addresses(rule.id) }.getOrDefault(emptyList()) }
                    },
                    onEdit = { menuFor = null; editorError = ""; editor = StunDraft.from(rule) },
                    onToggle = {
                        menuFor = null
                        scope.launch {
                            runCatching { api.action(rule.id, if (rule.enabled) "stop" else "start") }
                                .onSuccess { refresh() }
                                .onFailure { error = uiMessageZh(it.message).ifBlank { "操作失败" } }
                        }
                    },
                    onDelete = {
                        menuFor = null
                        scope.launch {
                            runCatching { api.delete(rule.id) }
                                .onSuccess { removeStunFavorite(prefs, rule.id); refresh() }
                                .onFailure { error = uiMessageZh(it.message).ifBlank { "删除失败" } }
                        }
                    },
                )
            }
        }
    }
    editor?.let { draft ->
        StunEditorDialog(
            initial = draft,
            prefs = prefs,
            error = editorError,
            saving = editorSaving,
            onDismiss = { if (!editorSaving) { editor = null; editorError = "" } },
        ) { saved ->
            val validationError = stunDraftValidationError(saved)
            if (validationError != null) {
                editorError = validationError
            } else {
                scope.launch {
                    editorError = ""
                    editorSaving = true
                    val result = if (saved.id.isBlank()) runCatching { api.create(saved) } else runCatching { api.update(saved.id, saved) }
                    result.onSuccess {
                        editor = null
                        refresh()
                    }.onFailure {
                        editorError = uiMessageZh(it.message).ifBlank { "保存失败" }
                    }
                    editorSaving = false
                }
            }
        }
    }
    historyTarget?.let { rule -> StunAddressHistoryDialog(rule, history, onDismiss = { historyTarget = null }, onCopy = { copyStunAddress(context, rule.serviceType, it) }) }
}

@Composable private fun StunIntro(agentOnline: Boolean, agentLastSeenAt: String, loading: Boolean, onRefresh: () -> Unit, onAdd: () -> Unit) {
    LabCoreCard(compact = true, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabV2ToolIcon(Icons.Rounded.Public, StunBlue, size = 40)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("内网服务 STUN 穿透", style = LabTypography.SectionTitle)
                val presenceText = if (agentOnline) "Agent 在线 · 路由器原生映射，Agent 自动保活" else "Agent 状态暂未同步 · 设置可直接创建"
                Text(presenceText, color = if (agentOnline) StunGreen else StunAmber, style = LabTypography.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (agentLastSeenAt.isNotBlank() && !agentOnline) {
                    Text("最近上报 $agentLastSeenAt", style = LabTypography.Caption, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(34.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = StunBlue)
                else Icon(Icons.Rounded.Refresh, "刷新", tint = StunBlue, modifier = Modifier.size(20.dp))
            }
            OutlinedButton(
                onClick = onAdd,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                border = BorderStroke(1.dp, StunBlue.copy(alpha = .36f)),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp), tint = StunBlue); Spacer(Modifier.width(3.dp)); Text("新建", style = LabTypography.CompactButton.copy(color = StunBlue))
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

@Composable private fun StunRuleCard(
    rule: StunRule,
    agentOnline: Boolean,
    menuOpen: Boolean,
    onOpenSsh: (String, Int) -> Unit,
    onOpenWireGuard: () -> Unit,
    onMenu: () -> Unit,
    onCopy: () -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val liveReady = agentOnline && rule.ready
    val stateText = when {
        rule.syncError.isNotBlank() -> "Agent 同步失败"
        !rule.enabled -> "已停止"
        !agentOnline && rule.runtime.publicEndpoint.isNotBlank() -> "Agent 离线 · 最近地址"
        !agentOnline -> "Agent 离线"
        liveReady -> "STUN 地址已获取"
        rule.actualState == "router_mapping_error" -> "路由器映射未就绪"
        rule.actualState == "router_mapping" -> "正在同步路由器映射"
        rule.actualState == "firewall_error" -> "防火墙未就绪"
        rule.actualState == "waiting_agent" -> "命令待 Agent 同步"
        rule.actualState == "mapped" || rule.actualState == "mapping" -> "正在校验公网地址"
        else -> "正在同步"
    }
    val stateColor = when {
        rule.syncError.isNotBlank() -> StunRed
        !rule.enabled || !agentOnline -> LabV2.InkMuted
        rule.actualState == "firewall_error" || rule.actualState == "router_mapping_error" -> StunRed
        liveReady -> StunGreen
        else -> StunAmber
    }
    LabCoreCard(compact = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(stateColor, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stunRuleTitle(rule),
                        style = LabTypography.CardTitle.copy(fontSize = 14.sp, lineHeight = 19.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${rule.serviceType} · ${rule.transportProtocol}", style = LabTypography.Supporting, color = LabV2.InkMuted)
                }
                Surface(shape = RoundedCornerShape(99.dp), color = stateColor.copy(alpha = .10f)) {
                    Text(stateText, color = stateColor, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
                Box {
                    IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "更多", tint = LabV2.InkMuted, modifier = Modifier.size(18.dp)) }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = onMenu,
                        modifier = Modifier.width(154.dp),
                        shape = LabV2.CompactCardShape,
                        containerColor = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, LabV2.Border),
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑", style = LabTypography.Supporting, fontWeight = FontWeight.SemiBold) },
                            onClick = onEdit,
                        )
                        DropdownMenuItem(
                            text = { Text(if (rule.enabled) "停止穿透" else "开始穿透", style = LabTypography.Supporting, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(if (rule.enabled) Icons.Rounded.PauseCircleOutline else Icons.Rounded.PlayCircleOutline, null, tint = LabV2.InkMuted) },
                            onClick = onToggle,
                        )
                        DropdownMenuItem(
                            text = { Text("删除", style = LabTypography.Supporting, fontWeight = FontWeight.SemiBold, color = StunRed) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = StunRed) },
                            onClick = onDelete,
                        )
                    }
                }
            }
            val endpoint = rule.runtime.publicEndpoint
            Surface(
                shape = LabCoreSurface.InnerShape,
                color = if (liveReady) StunGreen.copy(alpha = .07f) else LabCoreSurface.Inner,
                border = BorderStroke(1.dp, if (liveReady) StunGreen.copy(alpha = .16f) else LabCoreSurface.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (endpoint.isBlank()) "公网地址获取中" else endpoint,
                                fontWeight = FontWeight.Bold,
                                color = if (endpoint.isBlank() || !liveReady) LabV2.InkMuted else StunGreen,
                                fontSize = 14.sp,
                                lineHeight = 19.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (endpoint.isNotBlank()) Surface(shape = RoundedCornerShape(99.dp), color = StunAmber.copy(alpha = .12f)) {
                                Text("动态地址", color = StunAmber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                            }
                        }
                        val targetText = if (rule.targetType == "router_self") "路由器本机:${rule.targetPort}" else "${rule.targetIpv4}:${rule.targetPort}"
                        Text(
                            if (!agentOnline && endpoint.isNotBlank()) "上次映射至 $targetText · 当前未验证"
                            else if (rule.usesRouterNativeMapping) "路由器直连至 $targetText · 外网可达性取决于上级 NAT"
                            else "LabRelay 本地代理至 $targetText",
                            color = LabV2.InkMuted,
                            fontSize = LabTypography.Caption.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (rule.usesRouterNativeMapping) {
                        Text(
                            "路由器直连 · 流量不经过 LabRelay",
                            color = LabV2.InkMuted,
                            fontSize = LabTypography.Caption.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        StunTraffic(Icons.Rounded.Download, "下载", rule.runtime.totalDownloadBytes, StunBlue)
                        Spacer(Modifier.width(12.dp))
                        StunTraffic(Icons.Rounded.Upload, "上传", rule.runtime.totalUploadBytes, StunGreen)
                    }
                }
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onHistory),
                    shape = RoundedCornerShape(12.dp),
                    color = StunBlue.copy(alpha = .06f),
                    border = BorderStroke(1.dp, StunBlue.copy(alpha = .14f)),
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, null, tint = StunBlue, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("地址记录", style = LabTypography.Caption.copy(color = StunBlue, fontWeight = FontWeight.SemiBold))
                    }
                }
                if (serviceSupportsQuickAccess(rule.serviceType)) {
                    Spacer(Modifier.width(2.dp))
                    ServiceQuickAccessIconButton(
                        serviceType = rule.serviceType,
                        endpoint = endpoint,
                        tint = StunBlue,
                        onOpenSsh = onOpenSsh,
                        onOpenWireGuard = onOpenWireGuard,
                    )
                }
                if (endpoint.isNotBlank()) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.ContentCopy, "复制", tint = StunGreen, modifier = Modifier.size(18.dp))
                    }
                }
            }
            sequenceOf(rule.syncError, rule.nativeMappingMessage.takeIf { rule.enabled }.orEmpty(), rule.firewallMessage.takeIf { rule.enabled }.orEmpty(), rule.runtime.lastError.takeIf { rule.enabled }.orEmpty())
                .firstOrNull { it.isNotBlank() && !liveReady }
                ?.let { Text(uiMessageZh(it), color = StunRed, fontSize = LabTypography.Caption.fontSize, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable private fun StunTraffic(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bytes: Long, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("$label ${formatStunBytes(bytes)}", color = LabV2.InkMuted, fontSize = LabTypography.Caption.fontSize) }
}

@Composable private fun StunEditorDialog(initial: StunDraft, prefs: AppPrefs, error: String, saving: Boolean, onDismiss: () -> Unit, onSave: (StunDraft) -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    val cachedDevices = remember(prefs.cacheDevices, prefs.cacheOnlineDevices) {
        val overrides = parseDeviceOverrides(prefs.deviceOverridesJson)
        val mem = PortMappingMemoryCache.devices
        if (mem.isNotEmpty()) {
            mem.filter { it.ip.isNotBlank() }
        } else {
            val online = applyDeviceOverrides(parseDeviceArray(prefs.cacheOnlineDevices), overrides)
            val all = applyDeviceOverrides(parseDeviceArray(prefs.cacheDevices), overrides)
            (online + all).distinctBy { it.mac.ifBlank { "${it.name}-${it.ip}" } }.filter { it.ip.isNotBlank() }
        }
    }
    var devices by remember { mutableStateOf(cachedDevices) }
    var devicesLoading by remember { mutableStateOf(cachedDevices.isEmpty()) }
    var showDevicePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val template = stunTemplate(draft.serviceType)
    val selectedDevice = if (draft.targetType != "device" || draft.targetIpv4.isBlank()) null else devices.firstOrNull { it.ip.isNotBlank() && it.ip == draft.targetIpv4.trim() }
    fun refreshDevices(force: Boolean = false) {
        scope.launch {
            if (devices.isEmpty() || force) {
                devicesLoading = true
            }
            val loaded = runCatching { loadCanonicalPortMappingDevices(HubApi(prefs), forceRefresh = force) }.getOrDefault(emptyList())
            if (loaded.isNotEmpty()) {
                devices = loaded
            }
            devicesLoading = false
        }
    }
    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) { refreshDevices(force = false) }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                Text("自动建立路由器映射；Agent 用同端口保活并更新公网地址。", style = LabTypography.Supporting, color = LabV2.InkMuted)
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
                Text("目标", style = LabTypography.SectionTitle)
                val targetTypeLabel = when (draft.targetType) {
                    "router_self" -> "路由器本机"
                    "device" -> "内网设备"
                    else -> "手动目标"
                }
                LabV2SegmentedControl(
                    options = listOf("路由器本机", "内网设备", "手动目标"),
                    selected = targetTypeLabel,
                    onSelect = { selected ->
                        draft = switchStunTargetType(
                            draft,
                            when (selected) {
                                "路由器本机" -> "router_self"
                                "内网设备" -> "device"
                                else -> "manual"
                            },
                        )
                    },
                    textStyle = LabTypography.CompactButton,
                )
                when (draft.targetType) {
                    "router_self" -> {
                        Surface(shape = LabV2.FieldShape, color = LabCoreSurface.Inner, border = BorderStroke(1.dp, LabCoreSurface.Border)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LabV2ToolIcon(Icons.Rounded.Router, StunBlue, size = 34)
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("路由器本机", style = LabTypography.Value, fontWeight = FontWeight.SemiBold)
                                    Text("由 LabRelay 本地代理处理，不作为普通 LAN 设备", style = LabTypography.Caption, color = LabV2.InkMuted)
                                }
                            }
                        }
                    }
                    "device" -> {
                        StunSelectedDevice(
                            device = selectedDevice,
                            loading = devicesLoading,
                            onClick = { showDevicePicker = true },
                        )
                        Text("从在线设备选择 IPv4；设备暂时离线不会被当作目标已删除。", style = LabTypography.Caption, color = LabV2.InkMuted)
                    }
                    else -> Text("手动目标不依赖设备列表，请填写已确认的内网 IPv4 地址。", style = LabTypography.Caption, color = LabV2.InkMuted)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.targetType != "router_self") {
                        OutlinedTextField(
                            value = draft.targetIpv4,
                            onValueChange = { draft = draft.copy(targetIpv4 = it) },
                            label = { Text(if (draft.targetType == "device") "设备 IPv4" else "内网地址") },
                            placeholder = { Text("192.168.5.46") },
                            singleLine = true,
                            readOnly = draft.targetType == "device",
                            enabled = !saving,
                            shape = LabV2.FieldShape,
                            modifier = Modifier.weight(1.65f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = LabCoreSurface.Inner,
                                focusedBorderColor = StunBlue,
                                unfocusedBorderColor = LabCoreSurface.Border,
                            ),
                        )
                    }
                    OutlinedTextField(
                        value = draft.targetPort,
                        onValueChange = { draft = draft.copy(targetPort = it.filter(Char::isDigit)) },
                        label = { Text("目标端口") },
                        placeholder = { Text("443") },
                        singleLine = true,
                        enabled = !saving,
                        shape = LabV2.FieldShape,
                        modifier = Modifier.weight(if (draft.targetType == "router_self") 1f else .85f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = LabCoreSurface.Inner,
                            focusedBorderColor = StunBlue,
                            unfocusedBorderColor = LabCoreSurface.Border,
                        ),
                    )
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(64)) },
                    label = { Text("规则备注（可选）") },
                    placeholder = { Text("例如 家庭 NAS") },
                    supportingText = { Text("卡片标题优先显示备注；留空则显示“${draft.serviceType} 穿透”") },
                    singleLine = true,
                    enabled = !saving,
                    shape = LabV2.FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = LabCoreSurface.Inner,
                        focusedBorderColor = StunBlue,
                        unfocusedBorderColor = LabCoreSurface.Border,
                    ),
                )
                if (error.isNotBlank()) {
                    Surface(
                        shape = LabV2.CompactCardShape,
                        color = StunRed.copy(alpha = .08f),
                        border = BorderStroke(1.dp, StunRed.copy(alpha = .18f)),
                    ) {
                        Text(error, color = StunRed, style = LabTypography.Supporting, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, enabled = !saving, shape = LabV2.ButtonShape, modifier = Modifier.weight(1f)) { Text("取消", style = LabTypography.Button) }
                    OutlinedButton(
                        onClick = { onSave(draft) },
                        enabled = !saving,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1.35f),
                        border = BorderStroke(1.dp, StunBlue.copy(alpha = .36f)),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = StunBlue)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (saving) "保存中" else if (draft.id.isBlank()) "开始穿透" else "保存", style = LabTypography.Button.copy(color = StunBlue))
                    }
                }
            }
        }
    }
    if (showDevicePicker && draft.targetType == "device") {
        StunDevicePickerDialog(
            devices = devices,
            loading = devicesLoading,
            onRefresh = { refreshDevices(force = true) },
            onDismiss = { showDevicePicker = false },
            onPick = { device ->
                draft = draft.copy(targetType = "device", targetIpv4 = device.ip)
                showDevicePicker = false
            },
        )
    }
}

@Composable private fun StunSelectedDevice(device: DeviceItem?, loading: Boolean, onClick: () -> Unit) {
    val deviceLabel = device?.let { it.remark.ifBlank { it.name }.ifBlank { it.hostName }.ifBlank { "已选设备" } }
    val profile = if (device != null) inferDeviceProfile(device) else null
    Surface(
        modifier = Modifier.fillMaxWidth().clip(LabV2.FieldShape).clickable(onClick = onClick),
        shape = LabV2.FieldShape,
        color = LabCoreSurface.Inner,
        border = BorderStroke(1.dp, LabCoreSurface.Border),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (profile != null) {
                LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = 34)
            } else {
                LabV2ToolIcon(Icons.Rounded.Devices, StunBlue, size = 34, muted = device == null)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    deviceLabel ?: if (loading) "正在读取设备列表…" else "从在线设备填充",
                    style = LabTypography.Value,
                    fontWeight = FontWeight.SemiBold,
                    color = LabV2.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    device?.let { "${if (it.online) "在线" else "离线"} · ${it.ip}${if (it.mac.isNotBlank()) " · ${it.mac}" else ""}" } ?: "显示设备 IPv4 与 MAC",
                    style = LabTypography.Caption,
                    color = if (device?.online == true) StunGreen else LabV2.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = LabV2.InkMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable private fun StunDevicePickerDialog(
    devices: List<DeviceItem>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (DeviceItem) -> Unit,
) {
    val rows = remember(devices) {
        devices.filter { it.ip.isNotBlank() && isDeviceUsableForPublicEndpoint(it) }
            .sortedWith(compareByDescending<DeviceItem> { it.online }.thenBy { it.name.ifBlank { it.hostName }.lowercase(Locale.ROOT) })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).heightIn(min = 250.dp, max = 620.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            border = BorderStroke(1.dp, LabV2.Border),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择内网终端", style = LabTypography.PageTitle)
                        Text("仅展示适合公网访问的 IPv4 终端", style = LabTypography.Supporting, color = LabV2.InkMuted)
                    }
                    TextButton(onClick = onRefresh, enabled = !loading, shape = LabV2.ButtonShape) {
                        Text(if (loading) "读取中" else "刷新", style = LabTypography.CompactButton.copy(color = StunBlue))
                    }
                }
                if (rows.isEmpty()) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = StunBlue, strokeWidth = 2.dp)
                            Spacer(Modifier.height(10.dp))
                            Text("正在读取终端列表…", style = LabTypography.Supporting, color = LabV2.InkMuted)
                        } else {
                            Text("暂时没有可选终端", style = LabTypography.Supporting, color = LabV2.InkMuted)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(rows, key = { it.mac.ifBlank { "${it.name}-${it.ip}" } }) { device ->
                            val profile = inferDeviceProfile(device)
                            Surface(
                                modifier = Modifier.fillMaxWidth().clip(LabCoreSurface.InnerShape).clickable { onPick(device) },
                                shape = LabCoreSurface.InnerShape,
                                color = LabCoreSurface.Inner,
                                border = BorderStroke(1.dp, LabCoreSurface.Border),
                            ) {
                                Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = 34)
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(device.remark.ifBlank { device.name }.ifBlank { device.hostName }.ifBlank { device.mac }, style = LabTypography.Value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val subtitle = buildString {
                                            append(if (device.online) "在线" else "离线")
                                            append(" · ")
                                            append(device.ip)
                                            if (device.mac.isNotBlank()) {
                                                append(" · ")
                                                append(device.mac)
                                            }
                                        }
                                        Text(subtitle, style = LabTypography.Caption, color = if (device.online) StunGreen else LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, shape = LabV2.ButtonShape) { Text("取消", style = LabTypography.Button) }
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
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onCopy(row.endpoint) },
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
