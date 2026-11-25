package com.labprobe.app

import com.labprobe.app.ui.design.LabActionIcons

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val StunBlue = LabV2.Primary
private val StunGreen = LabV2.Green
private val StunAmber = LabV2.Amber
private val StunRed = LabV2.Red
private const val STUN_MAPPING_FRESH_SECONDS = 90L
internal const val STUN_OPERATION_PREFIX = "stun:"

internal fun isStunOperationTarget(targetId: String): Boolean = targetId.startsWith(STUN_OPERATION_PREFIX)

internal data class StunWireGuardDependencySnapshot(
    val localProfileNames: List<String> = emptyList(),
    val remoteProfileNames: List<String> = emptyList(),
)

internal enum class StunWireGuardMutationPolicy {
    ALLOW,
    BLOCK_LOCAL_REFERENCE,
    CONFIRM_REMOTE_RESIDUE,
}

internal fun stunWireGuardMutationPolicy(dependencies: StunWireGuardDependencySnapshot): StunWireGuardMutationPolicy = when {
    dependencies.localProfileNames.isNotEmpty() -> StunWireGuardMutationPolicy.BLOCK_LOCAL_REFERENCE
    dependencies.remoteProfileNames.isNotEmpty() -> StunWireGuardMutationPolicy.CONFIRM_REMOTE_RESIDUE
    else -> StunWireGuardMutationPolicy.ALLOW
}

internal fun isWireGuardManagedStunRule(
    ruleId: String,
    localBindingIds: Set<String>,
    remoteManagedRuleIds: Set<String>,
): Boolean = ruleId.isNotBlank() && ruleId in localBindingIds + remoteManagedRuleIds

internal fun stunResidualConfirmationText(action: String): String =
    "Hub 仍记录 WireGuard 对此规则的绑定，但本机没有对应配置。确认清理残留 WG 绑定后再${action}？"

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
    val wanId: String = "",
    val egressVerification: String = "",
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
    val wanId: String = "",
) {
    val usesRouterNativeMapping: Boolean get() = forwardMode == "router_native"
    val ready: Boolean get() = stunWanVerified(wanId, runtime.wanId, runtime.egressVerification) && enabled && actualState == "mapped" && runtime.mappingFresh && runtime.publicEndpoint.isNotBlank() && if (usesRouterNativeMapping) nativeMappingState == "ready" else firewallState == "ready"
}

data class StunAddressRecord(val endpoint: String, val updatedAt: Long, val wanId: String = "")

internal fun normalizedStunWan(value: String): String = value.trim().lowercase(Locale.ROOT).ifBlank { "wan" }

internal fun stunWanVerified(expected: String, reported: String, verification: String): Boolean {
    val line = normalizedStunWan(expected)
    if (reported.isNotBlank() && normalizedStunWan(reported) != line) return false
    if (line != "wan" && (reported.isBlank() || !verification.equals("verified", true))) return false
    return verification.isBlank() || verification.equals("verified", true)
}

internal fun stunWanOptions(configured: List<WanInfo>, current: String): List<String> =
    (configured.filter { it.configured }.map { normalizedStunWan(it.wanId) } + normalizedStunWan(current))
        .distinct().sortedWith(compareBy({ it != "wan" }, { it }))

internal fun stunWanSelectable(id: String, available: Set<String>): Boolean =
    normalizedStunWan(id) == "wan" || normalizedStunWan(id) in available


/** 顶部五分类筛选：HTTP/HTTPS/SSH 按应用层协议精确区分，其余全部归入 MORE。 */
internal enum class StunProtocolFilter(val label: String) {
    ALL("全部"), HTTPS("HTTPS"), HTTP("HTTP"), SSH("SSH"), MORE("更多");

    internal fun matches(serviceType: String): Boolean = when (this) {
        ALL -> true
        HTTPS -> serviceType.equals("HTTPS", true)
        HTTP -> serviceType.equals("HTTP", true)
        SSH -> serviceType.equals("SSH", true)
        MORE -> serviceType.equals("HTTPS", true).not() &&
            serviceType.equals("HTTP", true).not() &&
            serviceType.equals("SSH", true).not()
    }
}

internal fun stunFilterCounts(rules: List<StunRule>): Map<StunProtocolFilter, Int> =
    StunProtocolFilter.entries.associateWith { filter -> rules.count { filter.matches(it.serviceType) } }

data class StunSnapshot(
    val rules: List<StunRule> = emptyList(),
    val rulesLoaded: Boolean = false,
    val agentOnline: Boolean = false,
    val agentLastSeenAt: String = "",
    val availableWanIds: Set<String> = emptySet(),
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
        availableWanIds = root.optJSONArray("availableWanIds")?.let { ids ->
            (0 until ids.length()).map { ids.optString(it).trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
        }.orEmpty(),
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
            wanId = cleanApiText(runtime.optString("wanId")),
            egressVerification = cleanApiText(runtime.optString("egressVerification")),
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
        wanId = cleanApiText(json.optString("wanId")),
    )
}

private data class StunHubIdentity(val hub: String, val token: String, val hubDns: String)

private fun stunHubIdentity(prefs: AppPrefs): StunHubIdentity = StunHubIdentity(
    hub = prefs.hub.trim().trimEnd('/').lowercase(Locale.ROOT),
    token = prefs.token.trim(),
    hubDns = prefs.hubDns.trim().trimEnd('.').lowercase(Locale.ROOT),
)

class StunApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)
    private val identity = stunHubIdentity(prefs)

    private fun checkIdentity() {
        check(stunHubIdentity(prefs) == identity) { "Hub 连接已改变，操作结果请在原连接核对" }
    }

    suspend fun list(): StunSnapshot = withContext(Dispatchers.IO) {
        checkIdentity()
        val response = hubApi.requestText("/api/stun")
        checkIdentity()
        parseStunSnapshot(JSONObject(response))
    }
    suspend fun create(draft: StunDraft): StunRule = withContext(Dispatchers.IO) {
        checkIdentity()
        val response = hubApi.requestText("/api/stun", "POST", draft.toJson().toString())
        checkIdentity()
        parseStunRule(JSONObject(response).getJSONObject("rule"))
    }
    suspend fun update(id: String, draft: StunDraft): StunRule = withContext(Dispatchers.IO) {
        checkIdentity()
        val response = hubApi.requestText("/api/stun/$id", "PUT", draft.toJson().toString())
        checkIdentity()
        parseStunRule(JSONObject(response).getJSONObject("rule"))
    }
    suspend fun action(id: String, action: String) = withContext(Dispatchers.IO) {
        checkIdentity()
        val response = hubApi.requestText("/api/stun/$id/$action", "POST", "{}")
        checkIdentity()
        response
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        checkIdentity()
        val response = hubApi.requestText("/api/stun/$id", "DELETE")
        checkIdentity()
        response
    }
    suspend fun addresses(id: String): List<StunAddressRecord> = withContext(Dispatchers.IO) {
        checkIdentity()
        val response = hubApi.requestText("/api/stun/$id/addresses")
        checkIdentity()
        val array = JSONObject(response).optJSONArray("addresses") ?: JSONArray()
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { StunAddressRecord(cleanApiText(it.optString("endpoint")), parseEpoch(it, "updatedAt") ?: 0L, cleanApiText(it.optString("wanId"))) } }.filter { it.endpoint.isNotBlank() }
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
    val wanId: String = "wan",
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
        put("wanId", normalizedStunWan(wanId))
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
            wanId = normalizedStunWan(rule.wanId),
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
    if (!Regex("wan[0-9]*").matches(normalizedStunWan(draft.wanId))) return "线路 ID 格式无效"
    if (draft.targetType !in setOf("router_self", "device", "manual")) return "请选择目标类型"
    val ipv4 = draft.targetIpv4.trim()
    val validIpv4 = ipv4.split('.').let { parts -> parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255 } }
    if (draft.targetType != "router_self" && !validIpv4) return "请输入有效的内网 IPv4 地址"
    if ((draft.targetPort.toIntOrNull() ?: 0) !in 1..65535) return "目标端口必须是 1–65535"
    if (draft.name.trim().length > 64) return "规则备注最多 64 个字符"
    return null
}
internal fun stunAddressForCopy(serviceType: String, endpoint: String): String {
    val address = serviceAddressForCopy(serviceType, endpoint)
    if (address.isBlank()) return address
    // STUN 的 HTTPS 规则复制出来要能直接粘进浏览器，必须带 https://；
    // 其余服务（SSH / WireGuard 等）仍只复制纯 host:port。
    return if (serviceType.trim().uppercase(Locale.ROOT) == "HTTPS") "https://$address" else address
}

internal fun stunTargetBindingChanged(rule: StunRule, draft: StunDraft): Boolean {
    val targetIpv4 = if (draft.targetType == "router_self") "127.0.0.1" else draft.targetIpv4.trim()
    return stunDraftBindingChanged(
        StunDraft(
            enabled = rule.enabled,
            transportProtocol = rule.transportProtocol,
            targetType = rule.targetType,
            targetIpv4 = rule.targetIpv4,
            targetPort = rule.targetPort.toString(),
            wanId = normalizedStunWan(rule.wanId),
        ),
        draft.copy(targetIpv4 = targetIpv4),
    )
}

internal fun stunDraftBindingChanged(previous: StunDraft, next: StunDraft): Boolean {
    val previousIpv4 = if (previous.targetType == "router_self") "127.0.0.1" else previous.targetIpv4.trim()
    val nextIpv4 = if (next.targetType == "router_self") "127.0.0.1" else next.targetIpv4.trim()
    return normalizedStunWan(previous.wanId) != normalizedStunWan(next.wanId) ||
        previous.targetType != next.targetType ||
        previousIpv4 != nextIpv4 ||
        previous.targetPort.toIntOrNull() != next.targetPort.toIntOrNull() ||
        !previous.transportProtocol.equals(next.transportProtocol, ignoreCase = true) ||
        (previous.enabled && !next.enabled)
}

internal fun stunRuleStateLabel(rule: StunRule, agentOnline: Boolean, deleting: Boolean = false): String = when {
    deleting -> "正在删除"
    rule.syncError.isNotBlank() -> "Agent 同步失败"
    !rule.enabled -> "已停止"
    !agentOnline && rule.runtime.publicEndpoint.isNotBlank() -> "Agent 离线 · 最近地址"
    !agentOnline -> "Agent 离线"
    rule.ready -> "地址已获取"
    rule.actualState == "router_mapping_error" -> "路由器映射未就绪"
    rule.actualState == "router_mapping" -> "正在同步路由器映射"
    rule.actualState == "firewall_error" -> "防火墙未就绪"
    rule.actualState == "error" -> "穿透异常"
    rule.actualState == "waiting_agent" -> "命令待 Agent 同步"
    rule.actualState == "mapped" || rule.actualState == "mapping" -> "已保存 / 正在确认穿透"
    else -> "正在同步"
}

private fun isUncertainStunCreateFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }
        .any { it is IOException || it.message.orEmpty().contains("timeout", ignoreCase = true) || it.message.orEmpty().contains("timed out", ignoreCase = true) }

private suspend fun stunWireGuardDependencySnapshot(
    prefs: AppPrefs,
    context: Context,
    ruleId: String,
): StunWireGuardDependencySnapshot {
    // The Hub is authoritative for endpointProfiles.  Do not continue if that
    // read fails; a local cache alone cannot safely prove a rule is unused.
    val remote = WireGuardHubApi(prefs).loadStunDependencySnapshot()
        .forRule(ruleId)
        .map { reference ->
            reference.endpointProfileId.ifBlank { reference.peerId }.ifBlank { "服务端 WireGuard 绑定" }
        }
    val local = withContext(Dispatchers.IO) {
        WireGuardProfileStore(context.applicationContext, prefs).load()
            .filter { it.endpointSource == WireGuardEndpointSource.STUN && it.endpointBindingId == ruleId }
            .map { it.name.ifBlank { it.id } }
    }
    return StunWireGuardDependencySnapshot(
        localProfileNames = local.distinct(),
        remoteProfileNames = remote.distinct(),
    )
}

private fun stunDependencyBlocker(names: List<String>): String =
    "以下 WireGuard 配置正在使用此穿透：${names.joinToString("、")}。请先在 WireGuard 中移除或改绑后再继续。"

private fun stunDependencyBlocker(dependencies: StunWireGuardDependencySnapshot): String =
    stunDependencyBlocker((dependencies.localProfileNames + dependencies.remoteProfileNames).distinct())
private fun formatStunBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0).replace(".0 ", " ")
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0).replace(".0 ", " ")
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
private fun formatStunTime(epoch: Long): String = if (epoch <= 0) "未知时间" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epoch * 1000L))
private fun copyStunAddress(context: Context, serviceType: String, endpoint: String) {
    val value = stunAddressForCopy(serviceType, endpoint)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("STUN 地址", value))
    toast(context, "地址已复制")
}

private data class StunResidualConfirmation(
    val ruleId: String,
    val actionLabel: String,
    val draft: StunDraft? = null,
    val continueMutation: () -> Unit,
)

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
    val operations = remember(prefs.hub, prefs.token, prefs.hubDns) { NetworkOperationRegistry.get(prefs) }
    val operationState by operations.state.collectAsState()
    val latestOperationState by rememberUpdatedState(operationState)
    val presenceStore = remember(prefs.hub, prefs.token, prefs.hubDns) { AgentPresenceStoreRegistry.get(prefs) }
    val routerRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val liveAgent by presenceStore.state.collectAsState()
    val wanResource by routerRepository.wans.collectAsState()
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
    var uncertainCreateRuleId by remember { mutableStateOf<String?>(null) }
    var pendingCreateRequest by remember { mutableStateOf<StunDraft?>(null) }
    var failedEditorDraft by remember { mutableStateOf<StunDraft?>(null) }
    var acknowledgedOperationVersion by remember { mutableStateOf(0L) }
    var shownStunErrorVersion by remember { mutableStateOf(0L) }
    var historyTarget by remember { mutableStateOf<StunRule?>(null) }
    var history by remember { mutableStateOf<List<StunAddressRecord>>(emptyList()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var mutationGeneration by remember { mutableStateOf(0L) }
    var deletingRuleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var wireGuardManagedRuleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dependencyCheckingRuleId by remember { mutableStateOf<String?>(null) }
    var residualConfirmation by remember { mutableStateOf<StunResidualConfirmation?>(null) }
    var stunFilter by remember { mutableStateOf(StunProtocolFilter.ALL) }
    val refreshMutex = remember { Mutex() }
    val activeStunOperation = operationState?.takeIf { isStunOperationTarget(it.targetId) }
    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
        val localBindingIds = withContext(Dispatchers.IO) {
            WireGuardProfileStore(context.applicationContext, prefs).load()
                .filter { it.endpointSource == WireGuardEndpointSource.STUN }
                .map { it.endpointBindingId.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }
        val remoteBindingIds = try {
            WireGuardHubApi(prefs).loadStunDependencySnapshot().references
                .map { it.ruleId }
                .filter { it.isNotBlank() }
                .toSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
        wireGuardManagedRuleIds = localBindingIds + remoteBindingIds
    }
    fun beginStunDependencyCheck(
        ruleId: String,
        actionLabel: String,
        draft: StunDraft? = null,
        onAllowed: () -> Unit,
    ) {
        if (dependencyCheckingRuleId != null || latestOperationState?.running == true) {
            error = "已有网络操作正在进行，请完成后再试"
            return
        }
        dependencyCheckingRuleId = ruleId
        scope.launch {
            try {
                val dependencies = stunWireGuardDependencySnapshot(prefs, context, ruleId)
                if (dependencies.localProfileNames.isNotEmpty() || dependencies.remoteProfileNames.isNotEmpty()) {
                    wireGuardManagedRuleIds = wireGuardManagedRuleIds + ruleId
                } else {
                    wireGuardManagedRuleIds = wireGuardManagedRuleIds - ruleId
                }
                when (stunWireGuardMutationPolicy(dependencies)) {
                    StunWireGuardMutationPolicy.ALLOW -> onAllowed()
                    StunWireGuardMutationPolicy.BLOCK_LOCAL_REFERENCE -> {
                        error = stunDependencyBlocker(dependencies)
                        editorSaving = false
                    }
                    StunWireGuardMutationPolicy.CONFIRM_REMOTE_RESIDUE -> {
                        residualConfirmation = StunResidualConfirmation(
                            ruleId = ruleId,
                            actionLabel = actionLabel,
                            draft = draft,
                            continueMutation = onAllowed,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // A failed Hub/Agent dependency read proves nothing. Keep all
                // local and remote references untouched and make the error
                // dismissible instead of attempting a best-effort cleanup.
                error = uiMessageZh(failure.message).ifBlank { "无法核实 WireGuard 绑定，未修改 STUN 规则" }
                editorSaving = false
            } finally {
                dependencyCheckingRuleId = null
            }
        }
    }
    suspend fun refresh(silent: Boolean = false, allowRunningTargetId: String? = null) {
        while (!leaving) {
            val activeBeforeLock = latestOperationState
            if (activeBeforeLock?.running == true && activeBeforeLock.targetId != allowRunningTargetId) {
                delay(200L)
                continue
            }
            val refreshed = refreshMutex.withLock {
                val activeAtLock = latestOperationState
                if (activeAtLock?.running == true && activeAtLock.targetId != allowRunningTargetId) {
                    false
                } else {
                    val refreshGeneration = mutationGeneration
                    val operationVersion = activeAtLock?.completedVersion
                    try {
                        if (leaving) return@withLock true
                        if (!silent) loading = true
                        val latest = api.list()
                        if (leaving) return@withLock true
                        val effectiveAgent = liveAgent
                        val effectiveAgentOnline = effectiveAgent?.online == true || latest.agentOnline
                        val operationAfter = latestOperationState
                        val operationChanged = operationAfter?.completedVersion != operationVersion ||
                            (operationAfter?.running == true && operationAfter.targetId != allowRunningTargetId)
                        val acceptsRules = latest.rulesLoaded && refreshGeneration == mutationGeneration && !operationChanged
                        snapshot = snapshot.copy(
                            rules = if (acceptsRules) latest.rules else snapshot.rules,
                            rulesLoaded = if (acceptsRules) true else snapshot.rulesLoaded,
                            agentOnline = effectiveAgentOnline,
                            agentLastSeenAt = effectiveAgent?.lastSeenAt?.ifBlank { latest.agentLastSeenAt } ?: latest.agentLastSeenAt,
                            availableWanIds = latest.availableWanIds,
                        )
                        val completedStunError = operationAfter?.takeIf {
                            !it.running && isStunOperationTarget(it.targetId) &&
                                it.completedVersion > acknowledgedOperationVersion &&
                                it.completedVersion > shownStunErrorVersion &&
                                !it.error.isNullOrBlank()
                        }
                        val completedStunErrorText = operationAfter?.error?.let(::uiMessageZh).orEmpty()
                        val isSuccessfulStunRefresh = acceptsRules && completedStunError == null
                        error = when {
                            !latest.rulesLoaded -> "Hub 本次未返回 STUN 规则，已保留现有设置"
                            completedStunError != null -> {
                                shownStunErrorVersion = completedStunError.completedVersion
                                completedStunErrorText.ifBlank { "STUN 操作失败" }
                            }
                            isSuccessfulStunRefresh && shownStunErrorVersion > 0L -> {
                                operations.acknowledgeCompletedError(STUN_OPERATION_PREFIX)
                                acknowledgedOperationVersion = maxOf(acknowledgedOperationVersion, shownStunErrorVersion)
                                shownStunErrorVersion = 0L
                                if (uncertainCreateRuleId == null) "" else error
                            }
                            isSuccessfulStunRefresh && uncertainCreateRuleId == null -> ""
                            else -> error
                        }
                        if (acceptsRules) {
                            withContext(Dispatchers.IO) {
                                reconcileStunFavorites(prefs, latest.rules, ddnsSnapshot, nativeDdnsRecords)
                            }
                            // Absence in one GET does not prove an in-flight POST failed.
                            if (uncertainCreateRuleId != null && latest.rules.any { it.id == uncertainCreateRuleId }) {
                                uncertainCreateRuleId = null
                                pendingCreateRequest = null
                                acknowledgedOperationVersion = operationAfter?.completedVersion ?: 0L
                                error = "上一次创建已确认，请在列表查看"
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        if (!leaving) {
                            snapshot = snapshot.copy(availableWanIds = emptySet())
                            val operationAfter = latestOperationState
                            val unrelatedOperationChanged = operationAfter?.let {
                                it.completedVersion != operationVersion && !isStunOperationTarget(it.targetId)
                            } == true
                            error = if (unrelatedOperationChanged) {
                                // A WG/gateway failure is not a STUN page error.
                                error
                            } else if (snapshot.rules.isNotEmpty() && (liveAgent?.online == true || snapshot.agentOnline)) {
                                "Agent 在线，状态暂未同步；已保留全部穿透设置"
                            } else {
                                uiMessageZh(failure.message).ifBlank { "无法读取 STUN 穿透状态" }
                            }
                        }
                    } finally {
                        if (!leaving) loading = false
                    }
                    true
                }
            }
            if (refreshed) return
        }
    }
    val leavePage: () -> Unit = {
        if (!leaving) {
            leaving = true
            loading = false
            scope.coroutineContext.cancelChildren()
            onBack()
        }
    }
    BackHandler(enabled = true) {
        when {
            editor != null -> {
                editor = null
                editorError = ""
            }
            historyTarget != null -> {
                historyTarget = null
                history = emptyList()
            }
            menuFor != null -> {
                menuFor = null
            }
            else -> leavePage()
        }
    }
    LaunchedEffect(Unit) {
        routerRepository.refreshWans()
        routerRepository.refreshLabProbeDdns(false)
        routerRepository.refreshDdns(false)
        refresh()
    }
    LaunchedEffect(liveAgent?.lastSeenAt, snapshot.agentOnline) {
        while (!leaving) {
            delay(if (liveAgent?.online == true || snapshot.agentOnline) 15_000L else 30_000L)
            if (!leaving) refresh(true)
        }
    }
    LaunchedEffect(operationState?.completedVersion) {
        val completed = operationState ?: return@LaunchedEffect
        if (!completed.running && completed.completedVersion > 0L) {
            if (isStunOperationTarget(completed.targetId)) {
                deletingRuleIds = deletingRuleIds - completed.targetId.removePrefix("stun:")
                completed.error?.let { error = uiMessageZh(it).ifBlank { "STUN 操作失败" } }
                if (completed.error?.contains("创建结果尚未确认") == true) {
                    uncertainCreateRuleId = completed.targetId.removePrefix("stun:")
                    failedEditorDraft = null
                } else if (completed.error != null && failedEditorDraft != null) {
                    editorSaving = false
                    editorError = uiMessageZh(completed.error).ifBlank { "保存失败" }
                    editor = failedEditorDraft
                }
            }
            refresh(true)
        }
    }
    DetailShell(
        title = "STUN 穿透",
        subtitle = "",
        onBack = leavePage,
        unifiedTypography = true,
        sectionGap = LabV2.SectionGap,
    ) {
        StunIntro(
            agentOnline = snapshot.agentOnline,
            agentLastSeenAt = snapshot.agentLastSeenAt,
            loading = loading,
            onRefresh = { scope.launch { refresh() } },
            onAdd = {
                if (uncertainCreateRuleId != null) {
                    error = "上一创建结果待确认，正在刷新核对；请勿重复新建。"
                    scope.launch { refresh(true) }
                } else {
                    editorError = ""
                    editor = StunDraft()
                }
            },
        )
        activeStunOperation?.takeIf { it.running }?.let { operation ->
            Surface(
                shape = LabV2.CompactCardShape,
                color = StunAmber.copy(alpha = .08f),
                border = BorderStroke(1.dp, StunAmber.copy(alpha = .18f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = StunAmber)
                    Spacer(Modifier.width(8.dp))
                    Text(operation.label, color = StunAmber, style = LabTypography.Supporting, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (uncertainCreateRuleId != null && snapshot.rulesLoaded && operationState?.running != true) {
            Column {
                Text("创建结果仍待确认。请检查列表；重复提交可能生成重复规则。", style = LabTypography.Supporting, color = StunAmber)
                TextButton(onClick = {
                    val previousDraft = pendingCreateRequest?.copy(id = "")
                    uncertainCreateRuleId = null
                    pendingCreateRequest = null
                    acknowledgedOperationVersion = operationState?.completedVersion ?: 0L
                    error = ""
                    editorError = ""
                    editor = previousDraft ?: StunDraft()
                }) { Text("已核对列表，重新填写", style = LabTypography.CompactButton) }
            }
        }
        if (error.isNotBlank()) {
            val informationalError = error.startsWith("Agent 在线") || error.contains("已保留")
            Surface(
                shape = LabV2.CompactCardShape,
                color = if (informationalError) StunAmber.copy(alpha = .08f) else StunRed.copy(alpha = .08f),
                border = BorderStroke(1.dp, (if (informationalError) StunAmber else StunRed).copy(alpha = .18f)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        color = if (informationalError) StunAmber else StunRed,
                        fontSize = LabTypography.Supporting.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            operations.acknowledgeCompletedError(STUN_OPERATION_PREFIX)
                            val completedVersion = operationState
                                ?.takeIf { !it.running && isStunOperationTarget(it.targetId) }
                                ?.completedVersion
                                ?: 0L
                            acknowledgedOperationVersion = maxOf(
                                acknowledgedOperationVersion,
                                shownStunErrorVersion,
                                completedVersion,
                            )
                            shownStunErrorVersion = 0L
                            error = ""
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("关闭", style = LabTypography.CompactButton, color = if (informationalError) StunAmber else StunRed)
                    }
                }
            }
        }
        val filterCounts = stunFilterCounts(snapshot.rules)
        val visibleRules = snapshot.rules.filter { stunFilter.matches(it.serviceType) }
        if (snapshot.rules.isNotEmpty()) {
            StunProtocolFilterBar(
                counts = filterCounts,
                selected = stunFilter,
                onSelect = { stunFilter = it },
            )
        }
        if (loading && snapshot.rules.isEmpty()) {
            StunLoadingCards()
        } else if (!loading && snapshot.rulesLoaded && snapshot.rules.isEmpty()) {
            StunEmpty {
                if (uncertainCreateRuleId != null) {
                    error = "上一创建结果待确认，正在刷新核对；请勿重复新建。"
                    scope.launch { refresh(true) }
                } else {
                    editorError = ""
                    editor = StunDraft()
                }
            }
        } else if (!loading && !snapshot.rulesLoaded && snapshot.rules.isEmpty()) {
            LabCoreCard(compact = true) {
                Text("STUN 规则状态暂未同步", style = LabTypography.Supporting, color = LabV2.InkMuted)
            }
        } else {
            visibleRules.forEach { rule ->
                StunRuleCard(
                    rule = rule,
                    showWan = wanResource.value?.multiWanConfigured == true || normalizedStunWan(rule.wanId) != "wan",
                    agentOnline = snapshot.agentOnline,
                    wireGuardManaged = isWireGuardManagedStunRule(
                        rule.id,
                        wireGuardManagedRuleIds,
                        emptySet(),
                    ),
                    deleting = rule.id in deletingRuleIds,
                    operationLabel = activeStunOperation?.takeIf { it.running && it.targetId == "stun:${rule.id}" }?.label,
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
                        val action = if (rule.enabled) "stop" else "start"
                        val startToggle = {
                            if (!operations.launch(STUN_OPERATION_PREFIX + rule.id, if (rule.enabled) "正在停止穿透…" else "正在开始穿透…") { report ->
                                report(if (rule.enabled) "正在停止穿透…" else "正在开始穿透…")
                                mutationGeneration++
                                api.action(rule.id, action)
                                mutationGeneration++
                                report(if (rule.enabled) "已停止，正在确认状态…" else "已提交，正在确认穿透…")
                                refresh(true, STUN_OPERATION_PREFIX + rule.id)
                            }) {
                                error = "已有网络操作正在进行，请完成后再试"
                            }
                        }
                        if (rule.enabled) {
                            beginStunDependencyCheck(rule.id, "停用", onAllowed = startToggle)
                        } else {
                            startToggle()
                        }
                    },
                    onDelete = {
                        menuFor = null
                        val deletedId = rule.id
                        val deleteRule = {
                            if (!operations.launch(STUN_OPERATION_PREFIX + deletedId, "正在删除穿透…") { report ->
                                deletingRuleIds = deletingRuleIds + deletedId
                                report("正在删除穿透…")
                                mutationGeneration++
                                api.delete(deletedId)
                                mutationGeneration++
                                snapshot = snapshot.copy(rules = snapshot.rules.filterNot { it.id == deletedId })
                                // A deleted source leaves its user-owned favorite visible and marked missing.
                                report("已删除，正在确认列表…")
                                refresh(true, STUN_OPERATION_PREFIX + deletedId)
                            }) {
                                error = "已有网络操作正在进行，请完成后再试"
                            }
                        }
                        beginStunDependencyCheck(deletedId, "删除", onAllowed = deleteRule)
                    },
                )
            }
        }
    }
    editor?.let { draft ->
        StunEditorDialog(
            initial = draft,
            prefs = prefs,
            wanOptions = stunWanOptions(wanResource.value?.wans.orEmpty(), draft.wanId),
            availableWanIds = snapshot.availableWanIds,
            error = editorError,
            saving = editorSaving,
            onDismiss = { if (!editorSaving) { editor = null; editorError = "" } },
        ) { saved ->
            val old = snapshot.rules.firstOrNull { it.id == saved.id }
            val lineChanged = old == null || normalizedStunWan(old.wanId) != normalizedStunWan(saved.wanId) || (!old.enabled && saved.enabled)
            val validationError = stunDraftValidationError(saved) ?: if (saved.enabled && lineChanged && !stunWanSelectable(saved.wanId, snapshot.availableWanIds)) "所选线路暂不可用" else null
            if (validationError != null) {
                editorError = validationError
            } else {
                val isCreate = saved.id.isBlank()
                if (isCreate && uncertainCreateRuleId != null) {
                    editorError = "上一创建结果尚未确认；请先刷新核对，避免重复创建。"
                } else {
                    val request = if (isCreate) saved.copy(id = UUID.randomUUID().toString()) else saved
                    editorError = ""
                    failedEditorDraft = saved
                    // Publish a local save state before handing off to the shared,
                    // account-wide operation runner so the button cannot be raced.
                    editorSaving = true
                    val targetId = "stun:${request.id.ifBlank { "new" }}"
                    val startSave = {
                        if (operations.launch(targetId, "正在保存 STUN 穿透…") { report ->
                        try {
                            if (!isCreate) {
                                report("正在核实 STUN 规则…")
                                val authoritative = api.list()
                                require(authoritative.rulesLoaded) { "STUN 规则未完整加载，未保存修改" }
                                val current = authoritative.rules.firstOrNull { it.id == request.id }
                                    ?: throw IllegalStateException("该 STUN 规则已不存在，请刷新核对")
                            }
                            report("正在保存 STUN 穿透…")
                            mutationGeneration++
                            val returned = if (isCreate) api.create(request) else api.update(request.id, request)
                            mutationGeneration++
                            snapshot = snapshot.copy(
                                rules = snapshot.rules.filterNot { it.id == returned.id } + returned,
                            )
                            failedEditorDraft = null
                            report(if (returned.ready) "已保存，穿透已就绪" else "已保存，正在确认穿透…")
                            refresh(true, targetId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            if (isCreate && isUncertainStunCreateFailure(failure)) {
                                pendingCreateRequest = request
                                throw IllegalStateException("创建结果尚未确认，请刷新核对；请勿重复新建")
                            }
                            throw failure
                        }
                        }) {
                            editor = null
                            editorSaving = false
                        } else {
                            editorSaving = false
                            editorError = "已有网络操作正在进行，请完成后再试"
                        }
                    }
                    if (!isCreate && stunDraftBindingChanged(draft, saved)) {
                        beginStunDependencyCheck(
                            ruleId = request.id,
                            actionLabel = "保存修改",
                            draft = saved,
                            onAllowed = startSave,
                        )
                    } else {
                        startSave()
                    }
                }
            }
        }
    }
    historyTarget?.let { rule -> StunAddressHistoryDialog(rule, history, showWan = wanResource.value?.multiWanConfigured == true || normalizedStunWan(rule.wanId) != "wan" || history.any { it.wanId.isNotBlank() && normalizedStunWan(it.wanId) != "wan" }, onDismiss = { historyTarget = null }, onCopy = { copyStunAddress(context, rule.serviceType, it) }) }
    residualConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                residualConfirmation = null
                if (pending.draft != null) editorSaving = false
            },
            title = { Text("发现 WireGuard 残留绑定", style = LabTypography.CardTitle) },
            text = { Text(stunResidualConfirmationText(pending.actionLabel), style = LabTypography.Supporting) },
            confirmButton = {
                TextButton(
                    onClick = {
                        residualConfirmation = null
                        dependencyCheckingRuleId = pending.ruleId
                        scope.launch {
                            try {
                                val localProfiles = withContext(Dispatchers.IO) {
                                    WireGuardProfileStore(context.applicationContext, prefs).load()
                                }
                                when (val cleanup = WireGuardHubApi(prefs).cleanupOrphanedStunBinding(
                                    pending.ruleId,
                                    localProfiles,
                                )) {
                                    is WireGuardRemoteMutationResult.Applied -> Unit
                                    is WireGuardRemoteMutationResult.PendingVerification -> {
                                        throw IllegalStateException("WireGuard 残留绑定尚未确认清理：${cleanup.message}")
                                    }
                                    is WireGuardRemoteMutationResult.NotSubmitted -> {
                                        throw IllegalStateException("WireGuard 残留绑定未清理：${cleanup.message}")
                                    }
                                }
                                wireGuardManagedRuleIds = wireGuardManagedRuleIds - pending.ruleId
                                pending.continueMutation()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                error = uiMessageZh(failure.message).ifBlank {
                                    "残留 WG 绑定未清理，STUN 规则保持不变"
                                }
                                editorSaving = false
                            } finally {
                                dependencyCheckingRuleId = null
                            }
                        }
                    },
                    enabled = dependencyCheckingRuleId == null,
                ) { Text("清理残留并继续", style = LabTypography.Button) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        residualConfirmation = null
                        if (pending.draft != null) editorSaving = false
                    },
                ) { Text("取消", style = LabTypography.Button) }
            },
        )
    }
}

@Composable private fun StunIntro(agentOnline: Boolean, agentLastSeenAt: String, loading: Boolean, onRefresh: () -> Unit, onAdd: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFFE8F4FD), Color(0xFFF5FAFF)),
            ),
        ).border(1.dp, Color(0xFFDCEAF6), RoundedCornerShape(20.dp)),
    ) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val wave = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .55f, size.height)
                quadraticTo(size.width * .72f, size.height * .42f, size.width * .86f, size.height * .72f)
                quadraticTo(size.width * .95f, size.height * .92f, size.width, size.height * .55f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(wave, Color(0xFFD4E9FB).copy(alpha = .55f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                    val shield = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * .5f, size.height * .10f)
                        lineTo(size.width * .86f, size.height * .28f)
                        lineTo(size.width * .86f, size.height * .56f)
                        quadraticTo(size.width * .86f, size.height * .82f, size.width * .5f, size.height * .94f)
                        quadraticTo(size.width * .14f, size.height * .82f, size.width * .14f, size.height * .56f)
                        lineTo(size.width * .14f, size.height * .28f)
                        close()
                    }
                    drawPath(shield, StunBlue)
                    val check = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * .33f, size.height * .52f)
                        lineTo(size.width * .46f, size.height * .65f)
                        lineTo(size.width * .70f, size.height * .38f)
                    }
                    drawPath(
                        check,
                        Color.White,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.8.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    )
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("内网服务 STUN 穿透", style = LabTypography.SectionTitle)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (agentOnline) StunGreen else StunAmber, androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (agentOnline) "Agent 在线" else "Agent 未同步",
                        color = if (agentOnline) StunGreen else StunAmber,
                        style = LabTypography.Caption,
                        maxLines = 1,
                    )
                }
                if (agentLastSeenAt.isNotBlank() && !agentOnline) {
                    Text("最近上报 $agentLastSeenAt", style = LabTypography.Caption, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(34.dp)) {
                LabRefreshIcon(loading, Modifier.size(20.dp), StunBlue)
            }
            Box(
                Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(StunBlue).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, "新增", Modifier.size(19.dp), tint = Color.White)
            }
        }
    }
}

@Composable private fun StunProtocolFilterBar(
    counts: Map<StunProtocolFilter, Int>,
    selected: StunProtocolFilter,
    onSelect: (StunProtocolFilter) -> Unit,
) {
    val order = listOf(StunProtocolFilter.ALL, StunProtocolFilter.HTTPS, StunProtocolFilter.HTTP, StunProtocolFilter.SSH, StunProtocolFilter.MORE)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(99.dp)).background(Color.White)
            .border(1.dp, Color(0xFFE2ECF5), RoundedCornerShape(99.dp))
            .padding(horizontal = 5.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        order.forEachIndexed { index, filter ->
            val active = filter == selected
            val count = counts[filter] ?: 0
            val itemBase = Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(99.dp))
            val itemModifier = if (active) {
                itemBase.background(Brush.linearGradient(listOf(Color(0xFF3A8EE6), Color(0xFF1C6FC4))))
            } else {
                itemBase
            }.clickable { onSelect(filter) }
            Row(modifier = itemModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(
                    filter.label,
                    color = if (active) Color.White else Color(0xFF61758A),
                    style = LabTypography.Caption.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                    maxLines = 1,
                )
                Spacer(Modifier.width(3.dp))
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(99.dp))
                        .background(if (active) Color.White.copy(alpha = .88f) else Color(0xFFEFF4F9)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$count",
                        color = if (active) Color(0xFF2F86E0) else Color(0xFF61758A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (index < order.lastIndex) Box(Modifier.size(1.dp, 20.dp).background(Color(0xFFE7EEF6)))
        }
    }
}

/** 地址记录：圆形时钟 + 回溯箭头。 */
@Composable private fun StunHistoryIcon(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawCircle(color, radius = size.minDimension * .38f, style = stroke)
        drawLine(color, Offset(size.width * .5f, size.height * .27f), Offset(size.width * .5f, size.height * .5f), strokeWidth = 1.8.dp.toPx())
        drawLine(color, Offset(size.width * .5f, size.height * .5f), Offset(size.width * .64f, size.height * .58f), strokeWidth = 1.8.dp.toPx())
        drawLine(color, Offset(size.width * .75f, size.height * .26f), Offset(size.width * .84f, size.height * .22f), strokeWidth = 1.8.dp.toPx())
        drawLine(color, Offset(size.width * .75f, size.height * .26f), Offset(size.width * .78f, size.height * .35f), strokeWidth = 1.8.dp.toPx())
    }
}

/** 快捷访问：圆形罗盘 + 方向指针。 */
@Composable private fun StunCompassIcon(outline: Color, needle: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx())
        drawCircle(outline, radius = size.minDimension * .38f, style = stroke)
        val pointer = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .62f, size.height * .36f)
            lineTo(size.width * .56f, size.height * .56f)
            lineTo(size.width * .36f, size.height * .62f)
            lineTo(size.width * .44f, size.height * .44f)
            close()
        }
        drawPath(pointer, needle)
    }
}

/** 复制：两张错位圆角文档。 */
@Composable private fun StunCopyIcon(outline: Color, accent: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx())
        drawRoundRect(outline, androidx.compose.ui.geometry.Offset(size.width * .04f, size.height * .18f), Size(size.width * .62f, size.height * .68f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = stroke)
        drawRoundRect(accent, androidx.compose.ui.geometry.Offset(size.width * .32f, size.height * .04f), Size(size.width * .62f, size.height * .68f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = stroke)
    }
}

@Composable private fun StunLoadingCards() {
    repeat(3) {
        LabCoreCard(compact = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(132.dp).height(15.dp).clip(RoundedCornerShape(5.dp)).background(LabCoreSurface.Inner))
                Box(Modifier.width(88.dp).height(11.dp).clip(RoundedCornerShape(5.dp)).background(LabCoreSurface.Inner))
                Box(Modifier.fillMaxWidth().height(44.dp).clip(LabCoreSurface.InnerShape).background(LabCoreSurface.Inner))
                Box(Modifier.width(156.dp).height(12.dp).clip(RoundedCornerShape(5.dp)).background(LabCoreSurface.Inner))
            }
        }
    }
}

@Composable private fun StunEmpty(onAdd: () -> Unit) {
    LabCoreCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LabV2ToolIcon(Icons.Rounded.Public, StunBlue, size = 52, muted = true)
            Spacer(Modifier.height(8.dp)); Text("还没有 STUN 穿透", style = LabTypography.CardTitle)

            Spacer(Modifier.height(13.dp)); OutlinedButton(onClick = onAdd, shape = LabV2.ButtonShape) { Text("创建穿透", style = LabTypography.Button) }
        }
    }
}

@Composable internal fun StunRuleCard(
    rule: StunRule,
    agentOnline: Boolean,
    wireGuardManaged: Boolean,
    deleting: Boolean,
    operationLabel: String?,
    menuOpen: Boolean,
    onOpenSsh: (String, Int) -> Unit,
    onOpenWireGuard: () -> Unit,
    onMenu: () -> Unit,
    onCopy: () -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    showWan: Boolean = false,
) {
    val liveReady = agentOnline && rule.ready
    val stateText = operationLabel ?: stunRuleStateLabel(rule, agentOnline, deleting)
    val stateColor = when {
        deleting || operationLabel != null -> StunAmber
        rule.syncError.isNotBlank() -> StunRed
        !rule.enabled || !agentOnline -> LabV2.InkMuted
        rule.actualState in setOf("firewall_error", "router_mapping_error", "error") -> StunRed
        liveReady -> StunGreen
        else -> StunAmber
    }
    val recognition = remember(rule.name, rule.serviceType, rule.transportProtocol, rule.targetPort) {
        recognizeMappingService(
            name = rule.name,
            explicitType = rule.serviceType,
            protocol = rule.transportProtocol,
            internalPort = rule.targetPort.toString(),
            clientIp = rule.targetIpv4,
            devices = emptyList(),
            favoriteHints = emptyList(),
        )
    }
    val quickServiceType = recognition.serviceType.ifBlank {
        rule.serviceType.ifBlank { rule.transportProtocol }
    }
    val iconId = recognition.iconId.ifBlank { favoriteIconForServiceType(quickServiceType) }
    val subtitle = (when {
        wireGuardManaged -> "${rule.transportProtocol} · 自动管理"
        rule.serviceType.equals("WireGuard", true) -> rule.transportProtocol
        else -> "$quickServiceType · ${rule.transportProtocol}"
    }) + if (showWan) " · ${normalizedStunWan(rule.wanId).uppercase(Locale.ROOT)}" else ""
    val endpoint = rule.runtime.publicEndpoint
    val targetText = if (rule.targetType == "router_self") {
        "路由器本机:${rule.targetPort}"
    } else {
        "${rule.targetIpv4}:${rule.targetPort}"
    }
    val routeText = if (rule.usesRouterNativeMapping) "路由器直连 → $targetText" else "LabRelay → $targetText"

    LabCoreCard(compact = false, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StunApplicationIcon(iconId)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stunRuleTitle(rule),
                    style = LabTypography.CardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = LabTypography.Supporting,
                    color = LabV2.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                StunStatusPill(stateText, stateColor)
                Box {
                    IconButton(
                        onClick = onMenu,
                        enabled = !deleting && operationLabel == null,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Rounded.MoreVert, "更多", tint = LabV2.InkMuted, modifier = Modifier.size(18.dp))
                    }
                    LabDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = onMenu,
                        modifier = Modifier.widthIn(max = 240.dp),
                        shape = LabPopupShapes.Menu,
                        containerColor = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, LabV2.Border),
                        actionMenu = true,
                    ) {
                        LabDropdownMenuItem(
                            text = { Text("编辑", fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = LabV2.InkMuted) },
                            onClick = onEdit,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        )
                        LabDropdownMenuItem(
                            text = { Text(if (rule.enabled) "停止穿透" else "开始穿透", fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(
                                    if (rule.enabled) Icons.Rounded.PauseCircleOutline else Icons.Rounded.PlayCircleOutline,
                                    null,
                                    tint = LabV2.InkMuted,
                                )
                            },
                            onClick = onToggle,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        )
                        LabDropdownMenuItem(
                            text = { Text("删除", fontWeight = FontWeight.SemiBold, color = StunRed) },
                            leadingIcon = { Icon(LabActionIcons.Delete, null, tint = StunRed) },
                            onClick = onDelete,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        )
                    }
                }
            }
        }

        if (rule.usesRouterNativeMapping) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StunEndpointBlock(endpoint, routeText, Modifier.weight(1f))
                StunRuleActions(
                    serviceType = quickServiceType,
                    endpoint = endpoint,
                    onOpenSsh = onOpenSsh,
                    onOpenWireGuard = onOpenWireGuard,
                    onHistory = onHistory,
                    onCopy = onCopy,
                )
            }
        } else {
            StunEndpointBlock(endpoint, routeText, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(1.dp).background(LabV2.Border))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StunTraffic(Icons.Rounded.Download, rule.runtime.totalDownloadBytes, StunBlue, StunBlue.copy(alpha = .07f))
                    StunTraffic(Icons.Rounded.Upload, rule.runtime.totalUploadBytes, StunGreen, StunGreen.copy(alpha = .07f))
                }
                StunRuleActions(
                    serviceType = quickServiceType,
                    endpoint = endpoint,
                    onOpenSsh = onOpenSsh,
                    onOpenWireGuard = onOpenWireGuard,
                    onHistory = onHistory,
                    onCopy = onCopy,
                )
            }
        }

        sequenceOf(
            rule.syncError,
            rule.nativeMappingMessage.takeIf { rule.enabled }.orEmpty(),
            rule.firewallMessage.takeIf { rule.enabled }.orEmpty(),
            rule.runtime.lastError.takeIf { rule.enabled }.orEmpty(),
        )
            .firstOrNull { it.isNotBlank() && !liveReady }
            ?.let {
                Text(
                    uiMessageZh(it),
                    color = StunRed,
                    fontSize = LabTypography.Caption.fontSize,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}

@Composable private fun StunApplicationIcon(iconId: String) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(StunBlue.copy(alpha = .06f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!FavoritePresetIcon(iconId, 32)) {
            Icon(Icons.Rounded.Public, null, tint = StunBlue, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable private fun StunStatusPill(text: String, color: Color) {
    Surface(
        modifier = Modifier.widthIn(max = 108.dp),
        shape = RoundedCornerShape(99.dp),
        color = color.copy(alpha = .10f),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(color, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(
                text,
                color = color,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable private fun StunEndpointBlock(endpoint: String, routeText: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            endpoint.ifBlank { "公网地址获取中" },
            color = if (endpoint.isBlank()) LabV2.InkMuted else StunGreen,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
        Text(
            routeText,
            style = LabTypography.Supporting,
            color = LabV2.InkMuted,
            maxLines = 3,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable private fun StunRuleActions(
    serviceType: String,
    endpoint: String,
    onOpenSsh: (String, Int) -> Unit,
    onOpenWireGuard: () -> Unit,
    onHistory: () -> Unit,
    onCopy: () -> Unit,
) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StunActionButton(label = "地址记录", onClick = onHistory, background = StunBlue.copy(alpha = .06f)) {
            StunHistoryIcon(StunBlue, Modifier.size(16.dp))
        }
        if (serviceSupportsQuickAccess(serviceType)) {
            StunActionButton(
                label = "快捷访问",
                onClick = {
                    val target = serviceQuickAccessTarget(serviceType, endpoint)
                    if (target == null) toast(context, "暂未取得可用的快捷访问地址")
                    else launchServiceQuickAccess(context, target, onOpenSsh, onOpenWireGuard)
                },
                background = StunBlue.copy(alpha = .06f),
            ) {
                Icon(Icons.Rounded.ArrowOutward, null, tint = StunBlue, modifier = Modifier.size(17.dp))
            }
        }
        if (endpoint.isNotBlank()) {
            StunActionButton(label = "复制", onClick = onCopy, background = StunGreen.copy(alpha = .08f)) {
                StunCopyIcon(StunGreen, StunGreen, Modifier.size(16.dp))
            }
        }
    }
}

@Composable private fun StunActionButton(
    label: String,
    onClick: () -> Unit,
    background: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(32.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(10.dp),
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable private fun StunTraffic(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bytes: Long,
    color: Color,
    background: Color,
) {
    Surface(shape = RoundedCornerShape(99.dp), color = background) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text(
                formatStunBytes(bytes),
                color = color,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable private fun StunEditorDialog(initial: StunDraft, prefs: AppPrefs, wanOptions: List<String>, availableWanIds: Set<String>, error: String, saving: Boolean, onDismiss: () -> Unit, onSave: (StunDraft) -> Unit) {
    val mappingCache = remember(prefs.hub, prefs.token) { PortMappingMemoryCache.forScope(prefs.hub, prefs.token) }
    val deviceApi = remember(mappingCache, prefs.hubDns) { HubApi(prefs) }
    var draft by remember(initial.id, mappingCache) { mutableStateOf(initial) }
    val cachedDevices = remember(mappingCache, prefs.cacheDevices, prefs.cacheOnlineDevices) {
        val overrides = parseDeviceOverrides(prefs.deviceOverridesJson)
        val mem = mappingCache.devices
        if (mem.isNotEmpty()) {
            mem.filter { it.ip.isNotBlank() }
        } else {
            val online = applyDeviceOverrides(parseDeviceArray(prefs.cacheOnlineDevices), overrides)
            val all = applyDeviceOverrides(parseDeviceArray(prefs.cacheDevices), overrides)
            (online + all).distinctBy { it.mac.ifBlank { "${it.name}-${it.ip}" } }.filter { it.ip.isNotBlank() }
        }
    }
    var devices by remember(mappingCache) { mutableStateOf(cachedDevices) }
    var devicesLoading by remember(mappingCache) { mutableStateOf(cachedDevices.isEmpty()) }
    var showDevicePicker by remember(mappingCache) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val template = stunTemplate(draft.serviceType)
    val selectedDevice = if (draft.targetType != "device" || draft.targetIpv4.isBlank()) null else devices.firstOrNull { it.ip.isNotBlank() && it.ip == draft.targetIpv4.trim() }
    fun refreshDevices(force: Boolean = false) {
        scope.launch {
            if (devices.isEmpty() || force) {
                devicesLoading = true
            }
            val loaded = runCatching { loadCanonicalPortMappingDevices(deviceApi, mappingCache, forceRefresh = force) }.getOrDefault(emptyList())
            if (mappingCache !== PortMappingMemoryCache.forScope(prefs.hub, prefs.token)) return@launch
            if (loaded.isNotEmpty()) {
                devices = loaded
            }
            devicesLoading = false
        }
    }
    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) { refreshDevices(force = false) }

    LabEditorDialog(title = if (draft.id.isBlank()) "新建 STUN 穿透" else "编辑 STUN 穿透",
        onDismiss = { if (!saving) onDismiss() }, actions = {
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
    }) {
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
                if (wanOptions.size > 1 || normalizedStunWan(draft.wanId) != "wan") {
                    Text("线路", style = LabTypography.SectionTitle)
                    wanOptions.chunked(3).forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            group.forEach { id ->
                                FilterChip(
                                    selected = normalizedStunWan(draft.wanId) == id,
                                    onClick = { draft = draft.copy(wanId = id) },
                                    enabled = !saving && stunWanSelectable(id, availableWanIds),
                                    label = { Text(id.uppercase(Locale.ROOT), fontSize = LabTypography.Supporting.fontSize) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
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
                            placeholder = { Text("192.168.1.2") },
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
            LabNavigationIndicator()
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
            shape = LabPopupShapes.Card,
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

@Composable private fun StunAddressHistoryDialog(rule: StunRule, addresses: List<StunAddressRecord>, showWan: Boolean, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f),
            shape = LabPopupShapes.Card,
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
                val rows = addresses.ifEmpty { rule.runtime.publicEndpoint.takeIf { it.isNotBlank() }?.let { listOf(StunAddressRecord(it, rule.runtime.mappingUpdatedAt ?: 0L, rule.runtime.wanId)) }.orEmpty() }
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
                                Text(formatStunTime(row.updatedAt) + row.wanId.takeIf { showWan && it.isNotBlank() }?.let { " · ${it.uppercase(Locale.ROOT)}" }.orEmpty(), style = LabTypography.Caption, color = LabV2.InkMuted)
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
