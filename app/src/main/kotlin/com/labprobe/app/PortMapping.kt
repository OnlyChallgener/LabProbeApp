package com.labprobe.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val PortBlue = Color(0xFF0284C7)
private val PortCyan = Color(0xFF0EA5E9)
private val PortGreen = Color(0xFF12B981)
private val PortRed = Color(0xFFEF5350)
private val PortSlate = Color(0xFF718096)
private val PortSheetBg = Color(0xFFFFFFFF)
private val PortPopupBg = Color(0xFFFFFFFF)

data class PortMapRuntime(
    val state: String = "stopped",
    val resolvedTarget: String = "",
    val activeConnections: Long = 0,
    val activePeers: Long = 0,
    val totalUploadBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
    val totalUploadPackets: Long = 0,
    val totalDownloadPackets: Long = 0,
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
    val targetIpv6Snapshot: String = "",
    val targetIpv6Suffix: String,
    val targetMac: String,
    val targetPort: Int,
    val serviceType: String = "",
    val transportProtocol: String = "TCP",
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
    val shouldStop: Boolean get() = effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || (syncState in setOf("syncing", "stale") && effectiveDesiredState == "running")
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
    val capabilities: String = "",
    val state: String = if (online) "online" else "offline",
    val ageSeconds: Long = 0L,
    val lastSeenEpoch: Long = 0L,
    val revision: Long = 0L,
)

data class PortMapHistoryPoint(
    val time: Long,
    val activeConnections: Long,
    val uploadBytes: Long,
    val downloadBytes: Long
)

private fun parsePortMapRule(o: JSONObject): PortMapRule {
    val r = o.optJSONObject("runtime") ?: JSONObject()
    fun nullableEpoch(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val raw = obj.optLong(key)
        if (raw <= 0L) return null
        return if (raw > 10_000_000_000L) raw / 1000L else raw
    }
    return PortMapRule(
        id = cleanApiText(o.optString("id")),
        name = cleanApiText(o.optString("name")),
        enabled = o.optBoolean("enabled", false),
        mode = cleanApiText(o.optString("mode", "6to4")),
        listenPort = o.optInt("listenPort"),
        targetMode = cleanApiText(o.optString("targetMode")),
        targetIpv4 = cleanApiText(o.optString("targetIpv4")),
        targetIpv6 = cleanApiText(o.optString("targetIpv6")),
        targetIpv6Snapshot = cleanApiText(o.optString("targetIpv6Snapshot")),
        targetIpv6Suffix = cleanApiText(o.optString("targetIpv6Suffix")),
        targetMac = cleanMac(o.optString("targetMac")),
        targetPort = o.optInt("targetPort"),
        serviceType = cleanApiText(o.optString("serviceType")),
        transportProtocol = cleanApiText(o.optString("transportProtocol", "TCP")).uppercase(Locale.ROOT).ifBlank { "TCP" },
        preferCurrentPrefix = o.optBoolean("preferCurrentPrefix", true),
        expiresAt = nullableEpoch(o, "expiresAt"),
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
            activePeers = r.optLong("activePeers"),
            totalUploadBytes = r.optLong("totalUploadBytes"),
            totalDownloadBytes = r.optLong("totalDownloadBytes"),
            totalUploadPackets = r.optLong("totalUploadPackets"),
            totalDownloadPackets = r.optLong("totalDownloadPackets"),
            startedAt = nullableEpoch(r, "startedAt"),
            expiresAt = nullableEpoch(r, "expiresAt") ?: nullableEpoch(o, "expiresAt"),
            lastResolvedAt = nullableEpoch(r, "lastResolvedAt"),
            lastError = cleanApiText(r.optString("lastError"))
        )
    )
}

data class PortMapListSnapshot(
    val rules: List<PortMapRule>,
    val agent: PortMapAgentInfo,
    val rulesLoaded: Boolean,
    val rulesRevision: Long,
    val rulesUpdatedAt: String,
    val revision: Long,
)

class PortMapApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)

    suspend fun list(): PortMapListSnapshot = withContext(Dispatchers.IO) {
        val root = JSONObject(get("/api/portmaps"))
        val range = root.optJSONObject("portRange") ?: JSONObject()
        val array = root.optJSONArray("rules") ?: JSONArray()
        val rows = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parsePortMapRule) }
        val agent = PortMapAgentInfo(
            online = root.optBoolean("agentOnline", false),
            router = cleanApiText(root.optString("router", "Router")),
            lastSeenAt = cleanApiText(root.optString("agentLastSeenAt")),
            portMin = range.optInt("min", 20000),
            portMax = range.optInt("max", 20020),
            protocolVersion = cleanApiText(root.optString("protocolVersion")),
            hubVersion = cleanApiText(root.optString("hubVersion")),
            agentVersion = cleanApiText(root.optString("agentVersion")),
            capabilities = compactPortCapabilities(root.opt("capabilities")),
            state = cleanApiText(root.optString("agentState")).ifBlank {
                if (root.optBoolean("agentOnline", false)) "online" else "offline"
            },
            ageSeconds = root.optLong("agentAgeSeconds", 0L),
            lastSeenEpoch = root.optLong("agentLastSeenEpoch", 0L),
            revision = root.optLong("agentRevision", 0L),
        )
        PortMapListSnapshot(
            rules = rows,
            agent = agent,
            rulesLoaded = root.optBoolean("rulesLoaded", false),
            rulesRevision = root.optLong("rulesRevision", 0L).coerceAtLeast(0L),
            rulesUpdatedAt = cleanApiText(root.optString("rulesUpdatedAt")),
            revision = root.optLong("revision", 0L).coerceAtLeast(0L),
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

    private fun get(path: String): String = hubApi.requestText(path)
    private fun post(path: String, json: String): String = hubApi.requestText(path, "POST", json)
    private fun put(path: String, json: String): String = hubApi.requestText(path, "PUT", json)
    private fun deleteRequest(path: String): String = hubApi.requestText(path, "DELETE")
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
    val targetIpv6Snapshot: String = "",
    val targetIpv6Suffix: String = "",
    val targetMac: String = "",
    val targetPort: String = "443",
    val serviceType: String = "",
    val transportProtocol: String = "TCP",
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
            put("targetIpv6Snapshot", targetIpv6Snapshot.trim().removePrefix("[").removeSuffix("]"))
            put("targetIpv6Suffix", targetIpv6Suffix.trim())
            put("targetMac", cleanMac(targetMac))
            put("targetPort", targetPort.toIntOrNull() ?: 0)
            serviceType.trim().takeIf { it.isNotBlank() }?.let { put("serviceType", it) }
            put("transportProtocol", transportProtocol.trim().uppercase(Locale.ROOT).ifBlank { "TCP" })
            put("preferCurrentPrefix", true)
            if (expires == null) put("expiresAt", JSONObject.NULL) else put("expiresAt", expires)
            put("leaseSeconds", selectedLease)
            put("maxConnections", maxConnections.toIntOrNull() ?: 32)
            put("idleTimeoutSec", idleTimeoutSec.toIntOrNull() ?: 300)
        }
    }

    companion object {
        fun new(listenPort: String): PortMapDraft = PortMapDraft(
            listenPort = listenPort,
            targetIpv4 = "",
            targetPort = ""
        )

        fun from(rule: PortMapRule): PortMapDraft = PortMapDraft(
            id = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            mode = rule.mode,
            listenPort = rule.listenPort.toString(),
            targetMode = rule.targetMode.ifBlank { if (rule.mode == "6to6") "ipv6_suffix" else "ipv4" },
            targetIpv4 = rule.targetIpv4,
            targetIpv6 = rule.targetIpv6,
            targetIpv6Snapshot = rule.targetIpv6Snapshot.ifBlank { rule.targetIpv6 },
            targetIpv6Suffix = rule.targetIpv6Suffix,
            targetMac = rule.targetMac,
            targetPort = rule.targetPort.toString(),
            serviceType = rule.serviceType.ifBlank { defaultPortMapServiceType(rule.targetPort, rule.transportProtocol) },
            transportProtocol = rule.transportProtocol.ifBlank { "TCP" },
            duration = if (rule.expiresAt == null) "永久" else "保持原有效期",
            originalExpiresAt = rule.expiresAt,
            leaseSeconds = rule.leaseSeconds,
            maxConnections = rule.maxConnections.toString(),
            idleTimeoutSec = rule.idleTimeoutSec.toString()
        )
    }
}

internal data class PortMapServiceTemplate(
    val label: String,
    val serviceType: String,
    val targetPort: Int?,
    val protocols: Set<String>,
    val defaultProtocol: String,
)

internal val PORT_MAP_SERVICE_TEMPLATES = listOf(
    PortMapServiceTemplate("HTTPS", "HTTPS", 443, setOf("TCP"), "TCP"),
    PortMapServiceTemplate("HTTP", "HTTP", 80, setOf("TCP"), "TCP"),
    PortMapServiceTemplate("SSH", "SSH", 22, setOf("TCP"), "TCP"),
    PortMapServiceTemplate("RDP", "RDP", 3389, setOf("TCP"), "TCP"),
    PortMapServiceTemplate("Telnet", "Telnet", 23, setOf("TCP"), "TCP"),
    PortMapServiceTemplate("OpenVPN", "OpenVPN", 1194, setOf("TCP", "UDP"), "UDP"),
    PortMapServiceTemplate("DNS", "DNS", 53, setOf("TCP", "UDP"), "UDP"),
    PortMapServiceTemplate("WireGuard", "WireGuard", 51820, setOf("UDP"), "UDP"),
    PortMapServiceTemplate("自定义", "Custom", null, setOf("TCP", "UDP"), "TCP")
)

/**
 * Compatibility inference for old rules which predate serviceType. Keep this
 * shared with Favorites so an empty UDP rule is never presented as TCP.
 */
internal fun defaultPortMapServiceType(targetPort: Int, transportProtocol: String = "TCP"): String {
    val protocol = transportProtocol.trim().uppercase(Locale.ROOT).ifBlank { "TCP" }
    return when {
        targetPort == 22 && protocol == "TCP" -> "SSH"
        targetPort == 23 && protocol == "TCP" -> "Telnet"
        targetPort == 53 -> "DNS"
        targetPort == 80 && protocol == "TCP" -> "HTTP"
        targetPort == 443 && protocol == "TCP" -> "HTTPS"
        targetPort == 1194 -> "OpenVPN"
        targetPort == 3389 && protocol == "TCP" -> "RDP"
        targetPort == 51820 && protocol == "UDP" -> "WireGuard"
        protocol == "UDP" -> "Custom"
        else -> "TCP"
    }
}

internal fun applyPortMapServiceTemplate(
    draft: PortMapDraft,
    template: PortMapServiceTemplate
): PortMapDraft = if (draft.id.isBlank()) {
    draft.copy(
        serviceType = template.serviceType,
        targetPort = template.targetPort?.toString().orEmpty(),
        transportProtocol = template.defaultProtocol,
    )
} else {
    draft.copy(serviceType = template.serviceType, transportProtocol = template.defaultProtocol)
}

internal fun portMapDraftForSave(draft: PortMapDraft): PortMapDraft = if (draft.id.isBlank()) {
    draft.copy(enabled = true)
} else {
    draft
}

/** Keeps a selected full address as a shortcut snapshot and derives the lower 64 bits on demand. */
internal fun switchPortMapTargetMode(draft: PortMapDraft, targetMode: String): PortMapDraft {
    val normalized = if (targetMode == "ipv6_full") "ipv6_full" else "ipv6_suffix"
    val full = draft.targetIpv6.trim().removePrefix("[").removeSuffix("]")
        .ifBlank { draft.targetIpv6Snapshot.trim().removePrefix("[").removeSuffix("]") }
    return draft.copy(
        targetMode = normalized,
        targetIpv6Snapshot = draft.targetIpv6Snapshot.ifBlank { full },
        targetIpv6Suffix = if (normalized == "ipv6_suffix") ipv6Suffix64(full).ifBlank { draft.targetIpv6Suffix } else draft.targetIpv6Suffix,
    )
}

internal fun portMapValidationField(message: String): String = when {
    message == "请输入规则名称" -> "service"
    message.contains("监听端口") -> "externalPort"
    message.contains("目标") || message.contains("IPv6 后缀") -> "target"
    message.contains("最大连接") || message.contains("空闲超时") -> "advanced"
    else -> "general"
}

internal object PortMappingMemoryCache {
    var rules: List<PortMapRule> = emptyList()
    var rulesRevision: Long = 0L
    var rulesUpdatedAt: String = ""
    var snapshotRevision: Long = 0L
    var devices: List<DeviceItem> = emptyList()
    var devicesUpdatedAt: Long = 0L
    var agent: PortMapAgentInfo? = null

    fun isDevicesFresh(maxAgeMs: Long = 60_000L): Boolean {
        return devices.isNotEmpty() && (System.currentTimeMillis() - devicesUpdatedAt < maxAgeMs)
    }

    fun updateFromApp(watched: List<DeviceItem>, online: List<DeviceItem>, offline: List<DeviceItem> = emptyList()) {
        val merged = mergeSharedDeviceState(watched + offline, online)
        if (merged.isNotEmpty()) {
            devices = merged
            devicesUpdatedAt = System.currentTimeMillis()
        }
    }
}

/**
 * Loads the same watched + online device snapshot used by the device page and
 * the IPv6 mapping picker. Reuses warm memory cache for 0ms load time and
 * queries single-snapshot fast path when network refresh is needed.
 */
internal suspend fun loadCanonicalPortMappingDevices(api: HubApi, forceRefresh: Boolean = false): List<DeviceItem> = coroutineScope {
    if (!forceRefresh && PortMappingMemoryCache.isDevicesFresh()) {
        return@coroutineScope PortMappingMemoryCache.devices
    }

    // Fast-path: 1 single snapshot HTTP request to fetch status + watched + online + offline devices.
    val snapshot = runCatching { api.getSyncSnapshot() }.getOrNull()
    if (snapshot != null) {
        val syncWatched = mergeIpv6NeighborsFromStatus(snapshot.statusRoot, snapshot.watchedDevices)
        val syncOnline = mergeIpv6NeighborsFromStatus(snapshot.statusRoot, snapshot.onlineDevices)
        val syncMerged = mergeSharedDeviceState(syncWatched, syncOnline).ifEmpty { snapshot.offlineDevices }
        if (syncMerged.isNotEmpty()) {
            PortMappingMemoryCache.devices = syncMerged
            PortMappingMemoryCache.devicesUpdatedAt = System.currentTimeMillis()
            return@coroutineScope syncMerged
        }
    }

    // Fallback path: concurrent status + watched + online devices query for older Hub versions.
    val statusRequest = async { runCatching { api.getStatus() }.getOrNull() }
    val watchedRequest = async { runCatching { api.getDevices(false) }.getOrDefault(emptyList()) }
    val onlineRequest = async { runCatching { api.getDevices(true) }.getOrDefault(emptyList()) }

    val status = statusRequest.await()
    val watchedList = watchedRequest.await()
    val onlineList = onlineRequest.await()

    val watched = mergeIpv6NeighborsFromStatus(status, watchedList)
    val online = mergeIpv6NeighborsFromStatus(status, onlineList)
    val merged = mergeSharedDeviceState(watched, online)
    if (merged.isNotEmpty()) {
        PortMappingMemoryCache.devices = merged
        PortMappingMemoryCache.devicesUpdatedAt = System.currentTimeMillis()
        merged
    } else {
        PortMappingMemoryCache.devices
    }
}



@Composable
fun PortMappingScreen(prefs: AppPrefs, onBack: () -> Unit, embedded: Boolean = false) {
    val context = LocalContext.current
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { PortMapApi(prefs) }
    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val routerRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val ddnsResource by routerRepository.labProbeDdns.collectAsState()
    val ddnsSnapshot = ddnsResource.value
    val nativeDdnsResource by routerRepository.ddns.collectAsState()
    val presenceStore = remember(prefs.hub, prefs.token, prefs.hubDns) { AgentPresenceStoreRegistry.get(prefs) }
    val liveAgent by presenceStore.state.collectAsState()
    val persistentRules = remember(prefs.hub, prefs.hubDns) { PortMappingRuleStore.load(context, prefs) }
    val initialRules = remember(prefs.hub, prefs.hubDns) {
        PortMappingMemoryCache.rules.ifEmpty { persistentRules.rules }
    }
    val scope = rememberCoroutineScope()
    var rules by remember(prefs.hub, prefs.hubDns) { mutableStateOf(initialRules) }
    var rulesRevision by remember(prefs.hub, prefs.hubDns) {
        mutableLongStateOf(maxOf(PortMappingMemoryCache.rulesRevision, persistentRules.revision))
    }
    var rulesUpdatedAt by remember(prefs.hub, prefs.hubDns) {
        mutableStateOf(PortMappingMemoryCache.rulesUpdatedAt.ifBlank { persistentRules.updatedAt })
    }
    var snapshotRevision by remember(prefs.hub, prefs.hubDns) {
        mutableLongStateOf(PortMappingMemoryCache.snapshotRevision)
    }
    var devices by remember { mutableStateOf(PortMappingMemoryCache.devices) }
    var agent by remember { mutableStateOf(PortMappingMemoryCache.agent ?: PortMapAgentInfo(false, "Router", "", 20000, 20020)) }
    var canonicalDevicesLoaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(PortMappingMemoryCache.agent == null && initialRules.isEmpty()) }
    var refreshInFlight by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editDraft by remember { mutableStateOf<PortMapDraft?>(null) }

    LaunchedEffect(routerRepository) {
        routerRepository.refreshLabProbeDdns(false)
        routerRepository.refreshDdns(false)
    }
    LaunchedEffect(ddnsResource.updatedAt, nativeDdnsResource.updatedAt, rulesRevision) {
        if (rules.isNotEmpty()) {
            rules.forEach {
                syncExistingMappingFavorite(
                    prefs,
                    it,
                    devices,
                    ddnsSnapshot,
                    nativeDdnsResource.value.orEmpty(),
                )
            }
        }
    }

    fun commitRulesLocally(next: List<PortMapRule>, revision: Long = rulesRevision, updatedAt: String = rulesUpdatedAt, sourceRevision: Long = snapshotRevision) {
        rules = next
        rulesRevision = revision.coerceAtLeast(rulesRevision)
        snapshotRevision = sourceRevision.coerceAtLeast(snapshotRevision)
        rulesUpdatedAt = updatedAt.ifBlank { rulesUpdatedAt }
        PortMappingMemoryCache.rules = next
        PortMappingMemoryCache.rulesRevision = rulesRevision
        PortMappingMemoryCache.snapshotRevision = snapshotRevision
        PortMappingMemoryCache.rulesUpdatedAt = rulesUpdatedAt
        PortMappingRuleStore.save(context, prefs, next, rulesRevision, rulesUpdatedAt)
    }

    fun markSyncing(rule: PortMapRule, action: String) {
        rules = rules.map {
            if (it.id == rule.id) it.copy(
                desiredState = if (action == "start") "running" else "stopped",
                syncState = "syncing"
            ) else it
        }
    }

    suspend fun refresh(silent: Boolean = false) {
        if (refreshInFlight) return
        refreshInFlight = true
        if (!silent) loading = true
        try {
            val snapshot = kotlinx.coroutines.withTimeout(4_000L) { api.list() }
            val newAgent = snapshot.agent
            val explicitNewerEmpty = snapshot.rulesLoaded && snapshot.rules.isEmpty() && snapshot.rulesRevision > rulesRevision
            val sourceIsCurrent = snapshot.revision >= snapshotRevision
            val mayAccept = sourceIsCurrent && (snapshot.rules.isNotEmpty() || explicitNewerEmpty || (rules.isEmpty() && !persistentRules.hasDocument))
            if (mayAccept) {
                commitRulesLocally(snapshot.rules, snapshot.rulesRevision, snapshot.rulesUpdatedAt, snapshot.revision)
            }
            agent = newAgent
            PortMappingMemoryCache.agent = newAgent
            presenceStore.acceptHttp(newAgent)
            if (!canonicalDevicesLoaded) {
                runCatching { loadCanonicalPortMappingDevices(deviceApi) }.onSuccess {
                    devices = it
                    canonicalDevicesLoaded = true
                }
            }
            PortMappingMemoryCache.devices = devices
            if (mayAccept) snapshot.rules.forEach {
                syncExistingMappingFavorite(
                    prefs,
                    it,
                    devices,
                    ddnsSnapshot,
                    nativeDdnsResource.value.orEmpty(),
                )
            }
            message = if (!mayAccept && snapshot.rules.isEmpty() && rules.isNotEmpty()) {
                "Hub 本次未返回规则，已保留 APP 中的映射设置"
            } else ""
        } catch (error: Throwable) {
            val agentKnownOnline = liveAgent?.online == true || agent.online
            if (rules.isNotEmpty() && agentKnownOnline) {
                val staleRules = rules.map { it.copy(syncState = "stale") }
                rules = staleRules
                PortMappingMemoryCache.rules = staleRules
            }
            message = if (rules.isNotEmpty()) {
                if (agentKnownOnline) "Agent 在线，正在重新获取映射运行状态" else "映射状态暂未同步，已保留全部设置"
            } else (error.message ?: "加载失败")
        } finally {
            refreshInFlight = false
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh(silent = rules.isNotEmpty() || PortMappingMemoryCache.agent != null) }
    LaunchedEffect(liveAgent?.lastSeenAt) {
        liveAgent?.let {
            agent = it
            PortMappingMemoryCache.agent = it
            loading = false
            if (it.online) refresh(true)
        }
    }
    LaunchedEffect(agent.online) {
        while (true) {
            kotlinx.coroutines.delay(if (agent.online) 3_000L else 8_000L)
            refresh(true)
        }
    }

    val visible = rules.filter {
        when (filter) {
            "运行中" -> it.effectiveActualState in setOf("starting", "running", "waiting_target", "waiting_agent", "draining") || it.syncState == "syncing" || (it.syncState == "stale" && it.effectiveDesiredState == "running")
            "已停止" -> it.effectiveActualState == "stopped" && it.syncState != "syncing"
            "已到期" -> it.effectiveActualState == "expired"
            else -> true
        }
    }
    val selected = selectedId?.let { id -> rules.firstOrNull { it.id == id } }

    if (selected != null) {
        BackHandler { selectedId = null }
        val linkedFavorite = prefs.favoriteShortcuts().firstOrNull { it.id == "mapping-${selected.id}" }
            ?: prefs.favoriteShortcuts().firstOrNull { it.mappingId == selected.id }
        PortMapDetailPage(
            rule = selected,
            api = api,
            remoteEndpoint = linkedFavorite?.let { resolveFavoriteRemoteEndpoint(it, ddnsSnapshot, rules, nativeDdnsResource.value.orEmpty()) }.orEmpty(),
            onDismiss = { selectedId = null },
            onEdit = { editDraft = PortMapDraft.from(selected); selectedId = null },
            onAddFavorite = {
                upsertMappingFavorite(
                    prefs,
                    selected,
                    devices,
                    ddnsSnapshot,
                    nativeDdnsResource.value.orEmpty(),
                )
                toast(context, "已加入收藏")
            },
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
                        .onSuccess {
                            val detached = detachMappingFavorites(prefs, selected.id)
                            commitRulesLocally(rules.filterNot { it.id == selected.id })
                            selectedId = null
                            if (detached > 0) message = "已保留 $detached 个关联收藏"
                        }
                        .onFailure { message = it.message ?: "删除失败" }
                    refresh(true)
                }
            }
        )
        return
    }

    DetailShell("端口映射", "IPv6 入口 · Rust 四层反代 · 6→4 / 6→6", onBack, unifiedTypography = true, showHeader = !embedded, sectionGap = 6.dp) {
        PortMapAgentCard(agent, loading) { scope.launch { refresh() } }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf("全部", "运行中", "已停止", "已到期").forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.padding(end = 5.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PortBlue, selectedLabelColor = Color.White)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = { editDraft = PortMapDraft.new(nextPort(rules, agent).toString()) },
                shape = CircleShape,
                color = PortBlue,
                shadowElevation = 5.dp,
                modifier = Modifier.size(42.dp)
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White) } }
        }

        AnimatedVisibility(message.isNotBlank()) {
            val informational = message.startsWith("Agent 在线") || message.contains("已保留")
            val messageColor = if (informational) Color(0xFFF59E0B) else PortRed
            Surface(shape = RoundedCornerShape(18.dp), color = messageColor.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, messageColor.copy(alpha = .15f))) {
                Text(message, Modifier.padding(12.dp), color = messageColor, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
            }
        }

        if (loading && rules.isEmpty()) {
            LabV2Card(compact = true) {
                Text("正在后台同步映射快照，页面可以继续操作", color = LabV2.InkMuted, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
            }
        } else if (visible.isEmpty()) {
            PortMapEmptyCard { editDraft = PortMapDraft.new(nextPort(rules, agent).toString()) }
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
            refreshDevices = { loadCanonicalPortMappingDevices(deviceApi, forceRefresh = true) },
            onDismiss = { editDraft = null },
            onSave = { draft ->
                val saveDraft = portMapDraftForSave(draft)
                scope.launch {
                    runCatching {
                        if (saveDraft.id.isBlank()) api.create(saveDraft) else api.update(saveDraft.id, saveDraft)
                    }.onSuccess { saved ->
                        val next = if (rules.any { it.id == saved.id }) {
                            rules.map { if (it.id == saved.id) saved else it }
                        } else rules + saved
                        commitRulesLocally(next)
                        syncExistingMappingFavorite(
                            prefs,
                            saved,
                            devices,
                            ddnsSnapshot,
                            nativeDdnsResource.value.orEmpty(),
                        )
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
    val presenceState = agent.state.ifBlank { if (agent.online) "online" else "offline" }
    val color = when (presenceState) {
        "online" -> PortGreen
        "stale" -> Color(0xFFF59E0B)
        else -> PortRed
    }
    LabCoreCard(compact = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabV2ToolIcon(Icons.Rounded.CompareArrows, PortBlue, size = 46)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                val routerName = agent.router.ifBlank { "Router" }.let { if (it.equals("router", ignoreCase = true)) "Router" else it }
                Text(routerName, style = LabTypography.CardTitle.copy(color = LabV2.Ink))
                val versions = listOfNotNull(
                    agent.hubVersion.takeIf { it.isNotBlank() }?.let { "Hub $it" },
                    agent.agentVersion.takeIf { it.isNotBlank() }?.let { "Agent $it" }
                ).joinToString(" · ")
                Text(versions.ifBlank { "Rust LabRelay · TCP ${agent.portMin}–${agent.portMax}" }, style = LabTypography.Supporting, maxLines = 2, overflow = TextOverflow.Clip)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        when (presenceState) {
                            "online" -> "Agent 在线"
                            "stale" -> "Agent 状态稍旧"
                            else -> "Agent 未连接"
                        },
                        color = color,
                        fontSize = LabTypography.Caption.fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (agent.lastSeenAt.isNotBlank()) Text(" · ${agent.lastSeenAt}", fontSize = LabTypography.Caption.fontSize, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (agent.protocolVersion.isNotBlank() || agent.capabilities.isNotBlank()) {
                    Text(listOfNotNull(agent.protocolVersion.takeIf { it.isNotBlank() }?.let { "协议 $it" }, agent.capabilities.takeIf { it.isNotBlank() }).joinToString(" · "), style = LabTypography.Caption.copy(color = LabV2.InkFaint), maxLines = 2, overflow = TextOverflow.Clip)
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
    LabCoreCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LabV2ToolIcon(Icons.Rounded.CompareArrows, PortBlue, size = 52)
            Spacer(Modifier.height(10.dp))
            Text("暂无端口映射设置", style = LabTypography.CardTitle)
            Text("规则保存在 Hub 与 APP；Agent 离线不会删除设置", fontSize = LabTypography.Supporting.fontSize, color = LabV2.InkMuted)
            Spacer(Modifier.height(13.dp))
            Button(onClick = onAdd, shape = LabV2.ButtonShape) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(5.dp))
                Text("新建映射", style = LabTypography.Button)
            }
        }
    }
}

@Composable
private fun PortMapRuleCard(rule: PortMapRule, onOpen: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit) {
    val status = portMapStatus(rule)
    LabCoreCard(modifier = Modifier.clip(LabCoreSurface.CardShape).clickable(onClick = onOpen), compact = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(status.color, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(rule.name, Modifier.weight(1f), fontSize = LabTypography.CardTitle.fontSize, lineHeight = LabTypography.CardTitle.lineHeight, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(99.dp), color = status.color.copy(alpha = .10f)) {
                    Text(status.text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = status.color, fontSize = LabTypography.Supporting.fontSize, lineHeight = LabTypography.Supporting.lineHeight, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("${rule.serviceType.ifBlank { defaultPortMapServiceType(rule.targetPort, rule.transportProtocol) }} · ${rule.modeText} · :${rule.listenPort}${if (rule.targetMode == "ipv6_suffix") " · 后缀匹配" else ""}", fontSize = LabTypography.Supporting.fontSize, lineHeight = LabTypography.Supporting.lineHeight, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
            Text("→ ${rule.targetText}", fontSize = LabTypography.Supporting.fontSize, lineHeight = LabTypography.Supporting.lineHeight, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = if (rule.mode == "6to4") 1 else 2, overflow = TextOverflow.Clip)
            if (rule.runtime.resolvedTarget.isNotBlank() && rule.targetMode == "ipv6_suffix") {
                Text("实际目标 ${rule.runtime.resolvedTarget}", color = PortBlue, fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Clip)
            }
            Text(portMapStateTrail(rule), fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, color = status.color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PortMapCompactMetric("连接", rule.runtime.activeConnections.toString(), PortCyan, Modifier.weight(1f))
                PortMapCompactMetric("上传", formatPortBytes(rule.runtime.totalUploadBytes), PortBlue, Modifier.weight(1f))
                PortMapCompactMetric("下载", formatPortBytes(rule.runtime.totalDownloadBytes), PortGreen, Modifier.weight(1f))
            }
            val error = portMapErrorText(rule.runtime.lastError)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (error.isNotBlank() && rule.syncState != "stale" && (rule.effectiveActualState in setOf("error", "expired") || rule.syncState == "error")) Text(error, style = LabTypography.Supporting.copy(color = PortRed), maxLines = 3, overflow = TextOverflow.Clip)
                    Text(portMapTimeText(rule), fontSize = LabTypography.Supporting.fontSize, lineHeight = LabTypography.Supporting.lineHeight, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
                OutlinedButton(onClick = onToggle, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (rule.shouldStop) PortRed else PortBlue)) {
                    Text(if (rule.shouldStop) "停止" else "启动", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(5.dp))
                OutlinedButton(onClick = onEdit, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp)) {
                    Text("编辑", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PortMapCompactMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.height(42.dp), shape = LabCoreSurface.InnerShape, color = LabCoreSurface.Inner, border = androidx.compose.foundation.BorderStroke(1.dp, LabCoreSurface.Border)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center) {
            Text(label, fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 1)
            Text(value, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
}

private data class PortMapStatusUi(val text: String, val color: Color)
private fun portMapStatus(rule: PortMapRule): PortMapStatusUi = when {
    rule.syncState == "agent_offline" -> PortMapStatusUi("路由器 Agent 离线", PortRed)
    rule.syncState == "syncing" -> PortMapStatusUi("正在同步", PortBlue)
    rule.syncState == "error" -> PortMapStatusUi("同步失败", PortRed)
    rule.syncState == "stale" -> PortMapStatusUi("状态待同步", Color(0xFFF59E0B))
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
        Text(text, fontSize = LabTypography.Caption.fontSize, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortMapEditorSheet(
    initial: PortMapDraft,
    devices: List<DeviceItem>,
    portRange: IntRange,
    refreshDevices: (suspend () -> List<DeviceItem>)? = null,
    onDismiss: () -> Unit,
    onSave: (PortMapDraft) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    var showDevicePicker by remember { mutableStateOf(false) }
    var selectedTemplateLabel by remember(initial.id, initial.serviceType) {
        mutableStateOf(PORT_MAP_SERVICE_TEMPLATES.firstOrNull {
            it.serviceType == initial.serviceType || (initial.serviceType == "TCP" && it.serviceType == "Custom")
        }?.label)
    }
    var advancedExpanded by remember(initial.id) { mutableStateOf(false) }
    val isNew = draft.id.isBlank()
    var selectedDeviceSnapshot by remember(initial.id) { mutableStateOf<DeviceItem?>(null) }
    val selectedDevice = remember(draft.targetMac, devices, selectedDeviceSnapshot) {
        selectedDeviceSnapshot?.takeIf { cleanMac(it.mac).equals(cleanMac(draft.targetMac), ignoreCase = true) }
            ?: devices.firstOrNull { cleanMac(it.mac).equals(cleanMac(draft.targetMac), ignoreCase = true) }
    }
    fun fieldError(field: String): String = error.takeIf { it.isNotBlank() && portMapValidationField(it) == field }.orEmpty()
    fun submit() {
        error = validateDraft(draft, portRange)
        if (error.isBlank()) onSave(portMapDraftForSave(draft))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = PortSheetBg) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (isNew) "新建映射" else "编辑映射", fontSize = LabTypography.PageTitle.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.Ink)
                        Text("服务 → 目标设备 → 映射方式 → 外部访问", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    }
                    TextButton(onClick = ::submit) { Text(if (isNew) "保存并启动" else "保存修改", style = LabTypography.CompactButton) }
                }

                LabCoreCard(compact = true) {
                    Text("服务", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    PortMapV2Field("服务名称", draft.name, "例如：NAS HTTPS") { draft = draft.copy(name = it) }
                    fieldError("service").takeIf { it.isNotBlank() }?.let {
                        Text(it, color = PortRed, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                    }
                    Text("选择服务类型后会保存；新建时可同时填写建议端口。", fontSize = LabTypography.Caption.fontSize, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        PORT_MAP_SERVICE_TEMPLATES.forEach { template ->
                            val selected = selectedTemplateLabel == template.label || (selectedTemplateLabel == null && draft.serviceType == template.serviceType)
                            Surface(
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                    selectedTemplateLabel = template.label
                                    draft = applyPortMapServiceTemplate(draft, template)
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) LabV2.Primary.copy(alpha = .10f) else LabV2.Field,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) LabV2.Primary.copy(alpha = .45f) else LabV2.BorderStrong.copy(alpha = .75f))
                            ) {
                                Text(template.label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (selected) LabV2.Primary else LabV2.Ink, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    val selectedTemplate = PORT_MAP_SERVICE_TEMPLATES.firstOrNull { it.label == selectedTemplateLabel }
                    val supportedProtocols = selectedTemplate?.protocols ?: setOf("TCP", "UDP")
                    if (supportedProtocols.size > 1) {
                        Text("传输协议", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                        LabV2SegmentedControl(
                            options = listOf("TCP", "UDP"),
                            selected = draft.transportProtocol.takeIf { it in supportedProtocols } ?: selectedTemplate?.defaultProtocol.orEmpty().ifBlank { "TCP" },
                            onSelect = { protocol -> draft = draft.copy(transportProtocol = protocol) },
                            textStyle = LabTypography.CompactButton
                        )
                    }
                }

                LabCoreCard(compact = true) {
                    Text("目标设备", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    PortMapSelectedDevice(
                        device = selectedDevice,
                        mode = draft.mode,
                        targetMode = draft.targetMode,
                        selectedIpv6 = draft.targetIpv6,
                        fallbackMac = draft.targetMac,
                        onClick = { showDevicePicker = true }
                    )
                    Text("也可以手动填写地址；设备离线或不在列表时不会影响保存。", fontSize = LabTypography.Caption.fontSize, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)

                    if (draft.mode == "6to4") {
                        PortMapV2Field("目标 IPv4", draft.targetIpv4, "192.168.5.46") { draft = draft.copy(targetIpv4 = it) }
                    } else {
                        Text("目标地址方式", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                        LabV2SegmentedControl(
                            options = listOf("后缀匹配", "完整 IPv6"),
                            selected = if (draft.targetMode == "ipv6_suffix") "后缀匹配" else "完整 IPv6",
                            onSelect = { selected ->
                                draft = switchPortMapTargetMode(
                                    draft,
                                    if (selected == "后缀匹配") "ipv6_suffix" else "ipv6_full"
                                )
                            },
                            textStyle = LabTypography.CompactButton
                        )
                        if (draft.targetMode == "ipv6_suffix") {
                            PortMapV2Field("目标 MAC", draft.targetMac, "6c:1f:f7:76:71:04") { draft = draft.copy(targetMac = it) }
                            PortMapV2Field("IPv6 后缀", draft.targetIpv6Suffix, "例如 ::8dc0:a9e5:169d:a7c") { draft = draft.copy(targetIpv6Suffix = it) }
                            Text("按 MAC + 后 64 位 + 当前 LAN 前缀解析。目标消失时保持等待。", fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, color = LabV2.InkMuted)
                        } else {
                            PortMapV2Field("目标 IPv6", draft.targetIpv6, "2409:...::1234") { draft = draft.copy(targetIpv6 = it) }
                        }
                    }
                    PortMapV2Field("目标端口", draft.targetPort, "例如 443", keyboardType = KeyboardType.Number) {
                        draft = draft.copy(targetPort = it.filter(Char::isDigit))
                    }
                    fieldError("target").takeIf { it.isNotBlank() }?.let {
                        Text(it, color = PortRed, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                    }
                }

                LabCoreCard(compact = true) {
                    Text("映射方式", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    LabV2SegmentedControl(
                        options = listOf("IPv6 → IPv4", "IPv6 → IPv6"),
                        selected = if (draft.mode == "6to4") "IPv6 → IPv4" else "IPv6 → IPv6",
                        onSelect = { selected -> draft = draft.copy(mode = if (selected.endsWith("IPv4")) "6to4" else "6to6") },
                        textStyle = LabTypography.CompactButton
                    )
                    Text(
                        when {
                            selectedDevice == null -> "可先选择设备，也可以保留手动填写。"
                            selectedDevice?.pickIpv6()?.best.isNullOrBlank() -> "当前设备没有可用 IPv6，建议使用 IPv6 → IPv4。"
                            else -> "当前设备有可用 IPv6，可以选择 IPv6 → IPv6。"
                        },
                        fontSize = LabTypography.Caption.fontSize,
                        color = LabV2.InkMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                LabCoreCard(compact = true) {
                    Text("外部访问", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    PortMapV2Field("外部端口", draft.listenPort, "${portRange.first}-${portRange.last}", keyboardType = KeyboardType.Number) {
                        draft = draft.copy(listenPort = it.filter(Char::isDigit))
                    }
                    fieldError("externalPort").takeIf { it.isNotBlank() }?.let {
                        Text(it, color = PortRed, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                    }
                    Text("TCP · IPv6 监听 [::]:${draft.listenPort.ifBlank { "—" }}", fontSize = LabTypography.Supporting.fontSize, color = PortBlue, fontWeight = FontWeight.SemiBold)
                }

                val advancedSummary = "${draft.duration} · 最多 ${draft.maxConnections.ifBlank { "—" }} 连接 · 空闲 ${draft.idleTimeoutSec.ifBlank { "—" }} 秒"
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { advancedExpanded = !advancedExpanded },
                    shape = LabCoreSurface.InnerShape,
                    color = LabCoreSurface.Inner,
                    border = androidx.compose.foundation.BorderStroke(1.dp, LabCoreSurface.Border)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("高级设置", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                            Text(advancedSummary, fontSize = LabTypography.Supporting.fontSize, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(if (advancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = LabV2.Primary)
                    }
                }
                AnimatedVisibility(advancedExpanded) {
                    LabCoreCard(compact = true) {
                        val durationOptions = buildList {
                            if (draft.originalExpiresAt != null) add("保持原有效期")
                            addAll(listOf("1小时", "6小时", "24小时", "永久"))
                        }
                        PortMapV2Select("有效期", draft.duration, durationOptions) { draft = draft.copy(duration = it) }
                        PortMapV2Field("最大连接", draft.maxConnections, "32", keyboardType = KeyboardType.Number) {
                            draft = draft.copy(maxConnections = it.filter(Char::isDigit))
                        }
                        PortMapV2Field("空闲超时", draft.idleTimeoutSec, "300", keyboardType = KeyboardType.Number, suffix = "秒") {
                            draft = draft.copy(idleTimeoutSec = it.filter(Char::isDigit))
                        }
                        PortMapV2ReadOnly("到期行为", "沿用现有有效期语义", accent = PortGreen)
                        fieldError("advanced").takeIf { it.isNotBlank() }?.let {
                            Text(it, color = PortRed, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (fieldError("general").isNotBlank()) {
                    Surface(shape = RoundedCornerShape(16.dp), color = PortRed.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, PortRed.copy(alpha = .13f))) {
                        Text(fieldError("general"), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), color = PortRed, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(
                    onClick = ::submit,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = LabV2.ButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary)
                ) { Text(if (isNew) "保存并启动" else "保存修改", style = LabTypography.Button) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDevicePicker) {
        PortMapDevicePickerDialog(
            devices = devices,
            mode = draft.mode,
            targetMode = draft.targetMode,
            selectedMac = draft.targetMac,
            refreshDevices = refreshDevices,
            onDismiss = { showDevicePicker = false },
            onPick = { device ->
                selectedDeviceSnapshot = device
                val bestIpv6 = device.pickIpv6().best.orEmpty()
                draft = draft.copy(
                    targetMac = device.mac,
                    targetIpv4 = device.ip.ifBlank { draft.targetIpv4 },
                    targetIpv6 = bestIpv6.ifBlank { draft.targetIpv6 },
                    targetIpv6Snapshot = bestIpv6.ifBlank { draft.targetIpv6Snapshot },
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
    if (draft.transportProtocol.uppercase(Locale.ROOT) !in setOf("TCP", "UDP")) return "传输协议只能是 TCP 或 UDP"
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
        Text(label, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 1)
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
                    textStyle = TextStyle(fontSize = LabTypography.SectionTitle.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.Ink),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isBlank()) Text(hint, fontSize = LabTypography.Supporting.fontSize, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    }
                )
                if (suffix.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(suffix, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 1)
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
        Text(label, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 1)
        Surface(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = LabV2.FieldShape,
            color = accent.copy(alpha = .055f),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .11f)),
            tonalElevation = 0.dp
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 13.dp).horizontalScroll(rememberScrollState()).clickable(enabled = copyable, interactionSource = remember { MutableInteractionSource() }, indication = null) { copy(ctx, value) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1, overflow = TextOverflow.Clip)
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
        Text(label, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 1)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            Surface(
                modifier = Modifier.menuAnchor().fillMaxWidth().height(48.dp),
                shape = LabV2.FieldShape,
                color = LabV2.Field,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (expanded) LabV2.Primary.copy(alpha = .65f) else LabV2.BorderStrong.copy(alpha = .82f)),
                tonalElevation = 0.dp
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f), fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        text = { Text(option, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold) },
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
    selectedIpv6: String,
    fallbackMac: String,
    onClick: () -> Unit
) {
    val address = when {
        device == null -> ""
        mode == "6to4" -> cleanApiText(device.ip)
        else -> selectedIpv6.ifBlank { device.pickIpv6().best.orEmpty() }
    }
    val profile = device?.let(::inferDeviceProfile)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onClick() },
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
                    fontSize = LabTypography.Value.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = LabV2.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = when {
                    device != null && address.isNotBlank() -> if (mode == "6to6" && targetMode == "ipv6_suffix") "$address · 后缀 ${ipv6Suffix64(address)}" else address
                    fallbackMac.isNotBlank() -> fallbackMac
                    else -> if (mode == "6to6") "仅显示已获取可用 IPv6 的设备" else "显示设备 IPv4 与 MAC"
                }
                Text(
                    if (device != null) "${if (device.online) "在线" else "离线"} · $detail" else detail,
                    fontSize = LabTypography.Caption.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = if (device?.online == true) PortGreen else LabV2.InkMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Clip
                )
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
    refreshDevices: (suspend () -> List<DeviceItem>)? = null,
    onDismiss: () -> Unit,
    onPick: (DeviceItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var currentDevices by remember { mutableStateOf(devices) }
    var refreshing by remember { mutableStateOf(false) }
    var expandedMac by remember { mutableStateOf<String?>(null) }
    val refreshScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        refreshDevices?.let { loader ->
            refreshing = true
            try {
                currentDevices = loader()
            } catch (_: Throwable) {
                // Keep the last canonical snapshot when a refresh fails.
            }
            refreshing = false
        }
    }
    fun ipv6Candidates(device: DeviceItem): List<String> {
        val merged = mergeIpv6Candidates(
            device.ipv6Candidates,
            device.ipv6.map { Ipv6AddressCandidate(it) },
        )
        val eligible = merged.filter { candidate ->
            val state = candidate.state.lowercase(Locale.ROOT)
            !isInvalidIpv6(candidate.address) &&
                !isSuspectedTemporaryIpv6(candidate.address, candidate.source) &&
                !state.contains("temporary") && !state.contains("deprecated") &&
                !state.contains("tentative")
        }.map { it.address }
        val recommended = device.pickIpv6().best?.takeIf { it in eligible }
        return listOfNotNull(recommended) + eligible.filterNot { it == recommended }
    }
    val rows = remember(currentDevices, mode, query) {
        currentDevices.filter { d ->
            val addresses = if (mode == "6to4") listOf(cleanApiText(d.ip)) else ipv6Candidates(d)
            val text = "${d.remark} ${d.name} ${d.hostName} ${d.mac} ${addresses.joinToString(" ")}".lowercase(Locale.getDefault())
            query.isBlank() || text.contains(query.lowercase(Locale.getDefault()))
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).wrapContentHeight().heightIn(min = 260.dp, max = 680.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFFFAFCFF),
            border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border),
            shadowElevation = 12.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择目标设备", fontSize = LabTypography.PageTitle.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.Ink)
                        Text(
                            if (mode == "6to4") "当前显示设备 IPv4 地址" else if (targetMode == "ipv6_suffix") "当前显示全局 IPv6 与后 64 位" else "当前显示设备完整 IPv6 地址",
                            fontSize = LabTypography.Supporting.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            color = LabV2.InkMuted
                        )
                    }
                    IconButton(
                        onClick = {
                            refreshDevices?.let { loader ->
                                refreshScope.launch {
                                    refreshing = true
                                    try {
                                        currentDevices = loader()
                                    } catch (_: Throwable) {
                                        // Keep the last canonical snapshot when a refresh fails.
                                    }
                                    refreshing = false
                                }
                            }
                        },
                        enabled = !refreshing && refreshDevices != null,
                    ) { Icon(Icons.Rounded.Refresh, "刷新设备", tint = LabV2.Primary) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null) }
                }
                PortMapV2Field("搜索", query, "设备名称 / IPv6 / MAC") { query = it }
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.DevicesOther, null, tint = LabV2.InkMuted, modifier = Modifier.size(30.dp))
                            Text(if (mode == "6to6") "没有可用 IPv6 设备，请刷新后重试" else "没有匹配设备", color = LabV2.InkMuted, fontSize = LabTypography.Value.fontSize)
                            if (refreshDevices != null) {
                                TextButton(
                                    onClick = {
                                        refreshDevices?.let { loader ->
                                            refreshScope.launch {
                                                refreshing = true
                                                try {
                                                    currentDevices = loader()
                                                } catch (_: Throwable) {
                                                    // Keep the last canonical snapshot when a refresh fails.
                                                }
                                                refreshing = false
                                            }
                                        }
                                    },
                                    enabled = !refreshing,
                            ) { Text("刷新设备", style = LabTypography.CompactButton.copy(color = LabV2.Primary)) }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rows, key = { cleanMac(it.mac).ifBlank { it.name + it.ip } }) { device ->
                            val addresses = if (mode == "6to4") listOf(cleanApiText(device.ip)).filter { it.isNotBlank() } else ipv6Candidates(device)
                            val recommended = addresses.firstOrNull().orEmpty()
                            val profile = inferDeviceProfile(device)
                            val selected = cleanMac(device.mac).equals(cleanMac(selectedMac), ignoreCase = true)
                            val expanded = expandedMac == cleanMac(device.mac)
                            Surface(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = recommended.isNotBlank()) {
                                    onPick(if (mode == "6to6") device.copy(ipv6 = listOf(recommended), ipv6Candidates = listOf(Ipv6AddressCandidate(recommended, primary = true))) else device)
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = if (selected) LabV2.Primary.copy(alpha = .07f) else Color.White,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) LabV2.Primary.copy(alpha = .22f) else LabV2.Border),
                                tonalElevation = 0.dp
                            ) {
                                Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = 38)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(device.remark.ifBlank { device.name }.ifBlank { device.mac }, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (recommended.isBlank()) "暂无可用 IPv6" else recommended,
                                            style = LabTypography.Value.copy(
                                                color = if (recommended.isBlank()) LabV2.InkMuted else if (mode == "6to6") PortBlue else LabV2.InkMuted
                                            ),
                                            maxLines = 3,
                                            overflow = TextOverflow.Clip,
                                        )
                                        val extra = "${if (device.online) "在线" else "离线"} · " + if (mode == "6to6" && targetMode == "ipv6_suffix" && recommended.isNotBlank()) "后缀 ${ipv6Suffix64(recommended)} · ${device.mac}" else device.mac
                                        Text(extra, fontSize = LabTypography.Caption.fontSize, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (addresses.size > 1) {
                                        IconButton(onClick = { expandedMac = if (expanded) null else cleanMac(device.mac) }, modifier = Modifier.size(30.dp)) {
                                            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "选择 IPv6", tint = LabV2.Primary)
                                        }
                                    }
                                    if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = LabV2.Primary, modifier = Modifier.size(20.dp))
                                }
                            }
                            if (expanded && addresses.size > 1) {
                                Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    addresses.drop(1).forEach { address ->
                                        TextButton(onClick = { onPick(device.copy(ipv6 = listOf(address), ipv6Candidates = listOf(Ipv6AddressCandidate(address, primary = true)))) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                            Text(address, style = LabTypography.Value.copy(color = PortBlue), maxLines = 3, overflow = TextOverflow.Clip)
                                        }
                                    }
                                }
                            }
                        }
                    }
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
    remoteEndpoint: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onAddFavorite: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var history by remember(rule.id) { mutableStateOf<List<PortMapHistoryPoint>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var testingRemote by remember(rule.id, remoteEndpoint) { mutableStateOf(false) }
    var remoteTest by remember(rule.id, remoteEndpoint) { mutableStateOf<ServiceAccessReport?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(rule.id) {
        while (true) {
            runCatching { api.history(rule.id, 60) }.onSuccess { history = it }
            delay(10_000)
        }
    }
    DetailShell(
        rule.name,
        "${rule.serviceType.ifBlank { defaultPortMapServiceType(rule.targetPort, rule.transportProtocol) }} · ${rule.transportProtocol.ifBlank { "TCP" }} · ${rule.modeText}${if (rule.targetMode == "ipv6_suffix") " · IPv6 后缀匹配" else ""}",
        onDismiss,
        unifiedTypography = true,
        sectionGap = 6.dp,
        titleStyleOverride = LabTypography.CardTitle,
        subtitleStyleOverride = LabTypography.Caption
    ) {
            LabCoreCard(compact = true) {
                PortMapDetailLine("状态", portMapStatus(rule).text, portMapStatus(rule).color, compactValue = true)
                PortMapDetailLine("期望 / 同步", "${portMapDesiredText(rule)} · ${portMapSyncText(rule)}", compactValue = true)
                PortMapDetailLine("监听", "[::]:${rule.listenPort}", copyable = true, compactValue = true)
                PortMapDetailLine("配置目标", rule.targetText, copyable = true, compactValue = true)
                if (rule.runtime.resolvedTarget.isNotBlank()) PortMapDetailLine("实际目标", rule.runtime.resolvedTarget, PortBlue, copyable = true, compactValue = true)
                PortMapDetailLine("运行时间", portMapRunningText(rule), compactValue = true)
                PortMapDetailLine("剩余时间", portMapRemainingText(rule), compactValue = true)
                PortMapDetailLine("启动有效期", if (rule.leaseSeconds > 0) "每次启动 ${formatPortDuration(rule.leaseSeconds)}" else "永久", compactValue = true)
                PortMapDetailLine("最近解析", formatEpoch(rule.runtime.lastResolvedAt), compactValue = true)
                if (rule.revision > 0L) PortMapDetailLine("配置版本", "revision ${rule.revision}", compactValue = true)
            }

            LabCoreCard(compact = true, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("流量统计", fontSize = LabTypography.Value.fontSize, lineHeight = LabTypography.Value.lineHeight, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PortMapBigMetric("上传", formatPortBytes(rule.runtime.totalUploadBytes), PortBlue, Modifier.weight(1f))
                        PortMapBigMetric("下载", formatPortBytes(rule.runtime.totalDownloadBytes), PortGreen, Modifier.weight(1f))
                        PortMapBigMetric(if (rule.transportProtocol.equals("UDP", true)) "活跃 Peer" else "当前连接", if (rule.transportProtocol.equals("UDP", true)) rule.runtime.activePeers.toString() else rule.runtime.activeConnections.toString(), PortCyan, Modifier.weight(1f))
                        PortMapBigMetric("最大连接", rule.maxConnections.toString(), PortSlate, Modifier.weight(1f))
                    }
                    if (rule.transportProtocol.equals("UDP", true)) {
                        Text(
                            "上行包 ${rule.runtime.totalUploadPackets} · 下行包 ${rule.runtime.totalDownloadPackets}",
                            fontSize = LabTypography.Caption.fontSize,
                            color = LabV2.InkMuted,
                        )
                    }
                }
            }

            LabCoreCard(compact = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("近 1 小时吞吐", Modifier.weight(1f), fontSize = LabTypography.SectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
                    Text("60 秒采样", fontSize = LabTypography.Caption.fontSize, color = LabV2.InkFaint)
                }
                PortMapTrafficChart(history, Modifier.fillMaxWidth().height(184.dp))
            }

            if (rule.runtime.lastError.isNotBlank() && (rule.effectiveActualState in setOf("error", "expired") || rule.syncState == "error")) {
                Surface(shape = RoundedCornerShape(18.dp), color = PortRed.copy(alpha = .08f)) {
                    Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("最近错误", color = PortRed, fontWeight = FontWeight.SemiBold, fontSize = LabTypography.Supporting.fontSize)
                        Text(portMapErrorText(rule.runtime.lastError), color = PortRed, fontSize = LabTypography.Supporting.fontSize, lineHeight = LabTypography.Supporting.lineHeight)
                    }
                }
            }

            LabCoreCard(compact = true) {
                Text("远程访问诊断", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold)
                Text(
                    if (remoteEndpoint.isBlank()) "请先在关联收藏中填写可访问的远程入口。" else "由当前手机直接检测远程入口，不经过 Hub。",
                    fontSize = LabTypography.Caption.fontSize,
                    lineHeight = LabTypography.Caption.lineHeight,
                    color = LabV2.InkMuted,
                )
                Button(
                    onClick = {
                        scope.launch {
                            testingRemote = true
                            remoteTest = testServiceRemoteEndpoint(
                                remoteEndpoint,
                                ServiceAddressFamily.Any,
                                rule.serviceType.ifBlank { defaultPortMapServiceType(rule.targetPort, rule.transportProtocol) },
                                rule.transportProtocol.ifBlank { "TCP" },
                            )
                            testingRemote = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    enabled = !testingRemote && remoteEndpoint.isNotBlank(),
                    shape = LabV2.ButtonShape,
                ) {
                    if (testingRemote) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("正在测试", style = LabTypography.Button)
                    } else {
                        Icon(Icons.Rounded.Speed, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("测试远程访问", style = LabTypography.Button)
                    }
                }
                remoteTest?.let { report ->
                    PortMapDetailLine("DNS", report.dns, if (report.dns == "正常") PortGreen else PortRed)
                    PortMapDetailLine("IPv6", report.ipv6, when (report.ipv6) {
                        "可用" -> PortGreen
                        "—" -> PortSlate
                        else -> PortRed
                    })
                    if (rule.transportProtocol.equals("UDP", ignoreCase = true)) {
                        PortMapDetailLine("UDP", report.udp, when (report.udp) {
                            "可用" -> PortGreen
                            "未验证" -> PortSlate
                            else -> PortRed
                        })
                    } else {
                        PortMapDetailLine("TCP", report.tcp, if (report.tcp == "可达") PortGreen else PortRed)
                    }
                    if (report.https != "—") PortMapDetailLine("HTTPS", report.https, when (report.https) {
                        "正常" -> PortGreen
                        "证书警告" -> Color(0xFFF59E0B)
                        else -> PortRed
                    })
                    report.latencyMs?.let { PortMapDetailLine("延迟", "${it} ms", PortBlue) }
                    if (report.reason.isNotBlank()) PortMapDetailLine(
                        "结果",
                        report.reason,
                        if (report.reason.contains("证书")) Color(0xFFF59E0B) else if (report.reachable) PortGreen else PortRed
                    )
                }
            }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddFavorite, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = PortBlue)) {
                Icon(Icons.Rounded.Bookmark, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("加入收藏", style = LabTypography.Button)
            }
            Button(onClick = onToggle, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape, colors = ButtonDefaults.buttonColors(containerColor = if (rule.shouldStop) PortRed else PortBlue)) {
                Icon(if (rule.shouldStop) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(5.dp))
                Text(if (rule.shouldStop) "停止映射" else "启动映射", style = LabTypography.Button)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("编辑", style = LabTypography.Button)
            }
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = LabV2.ButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PortRed),
                border = androidx.compose.foundation.BorderStroke(1.dp, PortRed.copy(alpha = .55f)),
            ) {
                Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("删除", style = LabTypography.Button.copy(color = PortRed))
            }
        }
        Spacer(Modifier.height(2.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除端口映射？", style = LabTypography.CardTitle) },
            text = { Text("删除后会通知路由器停止并移除该规则。", style = LabTypography.Body) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除", style = LabTypography.CompactButton.copy(color = PortRed)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", style = LabTypography.CompactButton) } }
        )
    }
}

@Composable
private fun PortMapDetailLine(label: String, value: String, color: Color = LabV2.Ink, copyable: Boolean = false, compactValue: Boolean = false) {
    val context = LocalContext.current
    val valueStyle = if (compactValue) LabTypography.Body else LabTypography.Value
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(76.dp).padding(top = 1.dp), fontSize = LabTypography.Supporting.fontSize, lineHeight = LabTypography.Supporting.lineHeight, color = LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
        if (copyable && value.isNotBlank()) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(value, Modifier.fillMaxWidth(), fontSize = valueStyle.fontSize, lineHeight = valueStyle.lineHeight, color = color, fontWeight = FontWeight.SemiBold, softWrap = true)
            }
            IconButton(onClick = { copy(context, value) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.ContentCopy, "复制", Modifier.size(15.dp), tint = PortBlue)
            }
        } else {
            Text(value.ifBlank { "—" }, Modifier.weight(1f), fontSize = valueStyle.fontSize, lineHeight = valueStyle.lineHeight, color = color, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Clip)
        }
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
            Text("等待流量采样", color = LabV2.InkFaint, fontSize = LabTypography.Supporting.fontSize)
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
            val axisColor = Color(0xFF94A3B8)
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(148, 163, 184)
                textSize = LabTypography.Caption.fontSize.toPx()
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
                Text("↑ ${formatPortRate(rates[index].second)}  ↓ ${formatPortRate(rates[index].third)}", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 1)
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
        Box(Modifier.size(6.dp).background(color, CircleShape)); Spacer(Modifier.width(4.dp)); Text(text, fontSize = LabTypography.Caption.fontSize, color = LabV2.InkMuted)
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
        sec > 0 && minute == 0L -> "<1分"
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

private fun portMapStartedAt(rule: PortMapRule): Long? {
    rule.runtime.startedAt?.let { return it }
    val expiry = rule.runtime.expiresAt ?: rule.expiresAt
    if (expiry != null && rule.leaseSeconds > 0L) return (expiry - rule.leaseSeconds).takeIf { it > 0L }
    return null
}

private fun portMapRunningDuration(rule: PortMapRule): Long? {
    val now = System.currentTimeMillis() / 1000L
    val expiry = rule.runtime.expiresAt ?: rule.expiresAt
    val leaseDerived = if (expiry != null && rule.leaseSeconds > 0L) {
        val remaining = (expiry - now).coerceIn(0L, rule.leaseSeconds)
        rule.leaseSeconds - remaining
    } else null
    val startedDerived = portMapStartedAt(rule)?.let { max(0L, now - it) }
    return when {
        leaseDerived != null && startedDerived != null -> max(leaseDerived, startedDerived)
        leaseDerived != null -> leaseDerived
        else -> startedDerived
    }
}

private fun portMapRunningText(rule: PortMapRule): String {
    val duration = portMapRunningDuration(rule)
    return if (duration == null) "运行时间同步中" else "已运行 ${formatPortDuration(duration)}"
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
    "stale" -> "状态待同步"
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
    rule.syncState == "stale" -> "Agent 在线 · 正在重新获取运行状态"
    rule.effectiveActualState == "starting" -> "启动中 · 等待 Hub 返回实际状态"
    rule.effectiveActualState == "running" -> "${portMapRunningText(rule)} · 剩余 ${portMapRemainingText(rule)}"
    rule.effectiveActualState == "waiting_target" -> "等待目标 IPv6 · 每 30 秒重试"
    rule.effectiveActualState == "waiting_agent" -> "命令等待路由器领取"
    rule.effectiveActualState == "draining" -> "正在停止现有连接"
    rule.effectiveActualState == "expired" -> "已到期${if (rule.leaseSeconds > 0) " · 再次启动后按 ${formatPortDuration(rule.leaseSeconds)} 重新计时" else ""}"
    rule.effectiveActualState == "error" -> portMapErrorText(rule.runtime.lastError).ifBlank { "执行失败" }
    else -> "尚未启动"
}
