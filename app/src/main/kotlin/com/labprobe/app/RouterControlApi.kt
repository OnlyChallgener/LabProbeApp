package com.labprobe.app

import com.labprobe.app.feature.router.ipv6.Dhcpv6Client
import com.labprobe.app.feature.router.ipv6.Ipv6Config
import com.labprobe.app.feature.router.ipv6.Ipv6Status
import com.labprobe.app.feature.router.ipv6.parseDhcpv6Clients
import com.labprobe.app.feature.router.ipv6.parseIpv6Config
import com.labprobe.app.feature.router.ipv6.parseIpv6Status
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** App-facing state. Router credentials and eWeb session details remain in Hub only. */
data class RouterConnectionSnapshot(
    val connected: Boolean = false,
    val statusText: String = "正在等待 Hub 状态",
    val lastSuccessAt: Long = 0L,
    val lastError: String = ""
)

data class RouterHubStatus(
    val state: String = "checking",
    val connected: Boolean = false,
    val sessionConnected: Boolean = false,
    val dataAvailable: Boolean = false,
    val source: String = "",
    val message: String = "正在准备路由控制数据",
    val errorCode: String = "",
    val lastSuccessAt: Long = 0L
)

private fun routerApiMessageZh(raw: String): String {
    val text = raw.trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> "正在检查路由器状态"
        "hub is online, but router data is unavailable" in lower -> "Hub 已连接，暂未取得路由控制数据"
        "waiting for hub status" in lower -> "正在等待 Hub 状态"
        "timeout" in lower || "timed out" in lower -> "请求超时，请稍后重试"
        "login" in lower && ("failed" in lower || "error" in lower) -> "Hub 登录路由器失败"
        else -> text
    }
}

object RouterConnectionStore {
    var snapshot by mutableStateOf(RouterConnectionSnapshot())
        private set

    fun reset() {
        snapshot = RouterConnectionSnapshot()
    }

    fun apply(status: RouterHubStatus) {
        val agentSample = status.source == "agent_dashboard_push"
        val sessionConnected = status.sessionConnected || (status.connected && !agentSample)
        val localized = when {
            agentSample && !sessionConnected -> "BE50 状态采样正常，管理连接待恢复"
            sessionConnected && status.dataAvailable -> "路由控制链路正常"
            sessionConnected -> "路由器会话正常，控制数据正在同步"
            status.state == "router_login_failed" -> "路由器连接异常，请检查密码或网络"
            else -> routerApiMessageZh(status.message)
        }
        snapshot = RouterConnectionSnapshot(
            connected = sessionConnected,
            statusText = localized,
            lastSuccessAt = status.lastSuccessAt,
            lastError = if (sessionConnected) "" else localized
        )
    }

    fun markSuccess() {
        snapshot = snapshot.copy(
            connected = true,
            statusText = "路由控制链路正常",
            lastSuccessAt = System.currentTimeMillis() / 1000L,
            lastError = ""
        )
    }

    fun markFailure(message: String) {
        val localized = routerApiMessageZh(message)
        snapshot = snapshot.copy(
            connected = false,
            statusText = localized,
            lastError = localized
        )
    }
}

/** Product-level client for Hub router-control endpoints. All transport and auth go through HubApi. */
class RouterControlApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)
    private val workspaceActivation = RouterWorkspaceStore.activationVersion()

    private fun execute(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        // A DDNS/firewall/UPnP read failure is a resource refresh failure, not a
        // global Hub/router disconnect. Only /status owns connection semantics.
        val root = if (method.equals("GET", ignoreCase = true)) hubApi.requestJsonShort(path)
        else hubApi.requestJson(path, method, body)
        if (root.has("ok") && !root.optBoolean("ok")) {
            val err = root.optString("error").ifBlank { root.optString("message") }.ifBlank { "Hub 在线，但没有路由器数据" }
            throw RouterStatusUnavailableException(err)
        }
        return root
    }
    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        execute(path)
    }

    private suspend fun send(path: String, method: String, body: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            execute(path, method, body)
        }

    suspend fun capabilities(): RouterCapabilities {
        val root = get("/api/router/capabilities")
        val f = root.optJSONObject("features") ?: JSONObject()
        return RouterCapabilities(
            configured = root.optBoolean("configured"),
            dashboard = f.optBoolean("dashboard"),
            devices = f.optBoolean("devices"),
            firewall = f.optBoolean("firewall"),
            nativePortMapping = f.optBoolean("nativePortMapping"),
            upnp = f.optBoolean("upnp"),
            ddns = f.optBoolean("ddns"),
            wireguard = f.optBoolean("wireguard"),
            diagnostic = f.optBoolean("diagnostic")
        )
    }

    suspend fun wans(force: Boolean = false): WanInventory =
        parseWanInventory(get("/api/router/wans${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject())

    suspend fun hubStatus(): RouterHubStatus {
        val root = get("/api/router/status")
        return RouterHubStatus(
            state = cleanApiText(root.optString("state", "checking")),
            connected = root.optBoolean("connected", false),
            sessionConnected = root.optBoolean("sessionConnected", root.optBoolean("connected", false)),
            dataAvailable = root.optBoolean("dataAvailable", root.optBoolean("connected", false)),
            source = cleanApiText(root.optString("source", "")),
            message = cleanApiText(root.optString("message", "正在准备路由控制数据")),
            errorCode = cleanApiText(root.optString("errorCode", "")),
            lastSuccessAt = root.optLong("lastSuccessAt", 0L)
        ).also { status ->
            if (RouterWorkspaceStore.isActive(prefs.workspaceId, workspaceActivation)) {
                RouterConnectionStore.apply(status)
            }
        }
    }
    suspend fun nativePortMappings(force: Boolean = false): List<NativePortMapRule> {
        val data = get("/api/router/port-mapping${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject()
        return parseNativePortRules(data)
    }

    suspend fun addNativePortMapping(rule: NativePortMapRule): List<NativePortMapRule> =
        parseNativePortRules(send("/api/router/port-mapping", "POST", rule.toJson()).optJSONObject("data") ?: JSONObject())

    suspend fun updateNativePortMapping(oldName: String, rule: NativePortMapRule): List<NativePortMapRule> {
        val safe = URLEncoder.encode(oldName, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        return parseNativePortRules(send("/api/router/port-mapping/$safe", "PUT", rule.toJson()).optJSONObject("data") ?: JSONObject())
    }

    suspend fun deleteNativePortMapping(ruleName: String): List<NativePortMapRule> {
        val safe = URLEncoder.encode(ruleName, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        return parseNativePortRules(send("/api/router/port-mapping/$safe", "DELETE").optJSONObject("data") ?: JSONObject())
    }

    suspend fun upnp(force: Boolean = false): UpnpState =
        parseUpnp(get("/api/router/upnp${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject())

    suspend fun setUpnp(enabled: Boolean, wan: String): UpnpState =
        parseUpnp(send("/api/router/upnp", "PUT", JSONObject().put("enabled", enabled).put("wan", wan)).optJSONObject("data") ?: JSONObject())

    suspend fun firewall(force: Boolean = false): FirewallState =
        parseFirewall(get("/api/router/firewall${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject())

    suspend fun addFirewallRule(rule: FirewallRule): FirewallState =
        parseFirewall(send("/api/router/firewall/rules", "POST", rule.toJson(false)).optJSONObject("data") ?: JSONObject())

    suspend fun updateFirewallRule(rule: FirewallRule): FirewallState =
        parseFirewall(send("/api/router/firewall/rules/${rule.uuid}", "PUT", rule.toJson(true)).optJSONObject("data") ?: JSONObject())

    suspend fun setFirewallEnabled(uuid: String, enabled: Boolean): FirewallState =
        parseFirewall(send("/api/router/firewall/rules/$uuid/enabled", "PATCH", JSONObject().put("enabled", enabled)).optJSONObject("data") ?: JSONObject())

    suspend fun deleteFirewallRule(uuid: String): FirewallState =
        parseFirewall(send("/api/router/firewall/rules/$uuid", "DELETE").optJSONObject("data") ?: JSONObject())

    suspend fun reorderFirewall(scope: String, uuids: List<String>): FirewallState =
        parseFirewall(send("/api/router/firewall/reorder", "POST", JSONObject().put("scope", scope).put("uuids", JSONArray(uuids))).optJSONObject("data") ?: JSONObject())

    suspend fun ddns(force: Boolean = false): List<DdnsRecord> =
        parseDdnsList(ddnsPayload(force))

    /** Raw DDNS data envelope, carrying top-level nativeProtocol for new firmware. */
    suspend fun ddnsPayload(force: Boolean = false): JSONObject =
        get("/api/router/ddns${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject()

    internal fun ddnsProtocol(payload: JSONObject): String = payload.ddnsText("nativeProtocol")

    suspend fun addDdns(record: DdnsRecord, password: String): List<DdnsRecord> =
        parseDdnsList(send("/api/router/ddns", "POST", record.toJson(password)).optJSONObject("data") ?: JSONObject())

    suspend fun updateDdns(record: DdnsRecord, password: String?): List<DdnsRecord> {
        require(record.serviceId.isNotBlank()) { "DDNS 记录 ID 不能为空" }
        val safe = URLEncoder.encode(record.serviceId, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        return parseDdnsList(send("/api/router/ddns/$safe", "PUT", record.toJson(password)).optJSONObject("data") ?: JSONObject())
    }

    suspend fun deleteDdns(serviceId: String): List<DdnsRecord> {
        require(serviceId.isNotBlank()) { "DDNS 记录 ID 不能为空" }
        val safe = URLEncoder.encode(serviceId, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        return parseDdnsList(send("/api/router/ddns/$safe", "DELETE").optJSONObject("data") ?: JSONObject())
    }

    /** LabProbe-owned DDNS API. Kept separate from the router-native DDNS model above. */
    suspend fun labProbeDdns(force: Boolean = false): LabProbeDdnsSnapshot =
        parseLabProbeDdns(get("/api/ddns${if (force) "?force=1" else ""}"))

    suspend fun labProbeDdnsProviders(): List<LabProbeDdnsProvider> =
        parseLabProbeDdnsProviders(get("/api/ddns/providers"))

    suspend fun addLabProbeDdns(record: LabProbeDdnsRecord, credentials: Map<String, String>): LabProbeDdnsSnapshot =
        parseLabProbeDdns(send("/api/ddns", "POST", record.toJson(credentials)))

    suspend fun updateLabProbeDdns(record: LabProbeDdnsRecord, credentials: Map<String, String>): LabProbeDdnsSnapshot =
        parseLabProbeDdns(send("/api/ddns/${record.id}", "PUT", record.toJson(credentials)))

    suspend fun deleteLabProbeDdns(recordId: String): LabProbeDdnsSnapshot =
        parseLabProbeDdns(send("/api/ddns/$recordId", "DELETE"))

    /** Provider failures are valid DDNS results, not router-control failures. */
    suspend fun updateLabProbeDdnsNow(recordId: String): JSONObject = withContext(Dispatchers.IO) {
        hubApi.requestJson("/api/ddns/$recordId/update", "POST", JSONObject().put("force", true))
    }

    internal fun labProbeUpdateError(root: JSONObject): String {
        val results = root.optJSONObject("results")
        if (results != null) {
            val keys = results.keys()
            while (keys.hasNext()) {
                val item = results.optJSONObject(keys.next()) ?: continue
                if (!item.optBoolean("success", false)) {
                    item.optString("errorMessage").trim().takeIf { it.isNotBlank() }?.let { return it }
                    item.optString("errorCode").trim().takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return root.optString("message").trim().ifBlank { root.optString("error").trim() }.ifBlank { "DDNS 更新失败" }
    }

    suspend fun refreshLabProbeDdnsAddress(): Long = hubApi.requestRouterDashboardRefresh()

    suspend fun ipv6Status(wanId: String? = null): Ipv6Status =
        parseIpv6Status(get("/api/router/ipv6/status${wanId?.let { "?wanId=${URLEncoder.encode(it, StandardCharsets.UTF_8.toString())}" }.orEmpty()}").optJSONObject("data") ?: JSONObject())

    suspend fun ipv6Config(wanId: String? = null): Ipv6Config =
        parseIpv6Config(get("/api/router/ipv6/config${wanId?.let { "?wanId=${URLEncoder.encode(it, StandardCharsets.UTF_8.toString())}" }.orEmpty()}").optJSONObject("data") ?: JSONObject())

    suspend fun dhcpv6Clients(): List<Dhcpv6Client> =
        parseDhcpv6Clients(get("/api/router/ipv6/clients").optJSONObject("data") ?: JSONObject())

    suspend fun saveIpv6Config(body: JSONObject): Ipv6Config =
        parseIpv6Config(send("/api/router/ipv6/config", "PUT", body).optJSONObject("data") ?: JSONObject())

    suspend fun diagnostic(): RouterDiagnostic =
        parseDiagnostic(get("/api/router/diagnostic").optJSONObject("data") ?: JSONObject())

    suspend fun startDiagnostic(): RouterDiagnostic {
        send("/api/router/diagnostic", "POST")
        return diagnostic()
    }
}

data class RouterCapabilities(
    val configured: Boolean = false,
    val dashboard: Boolean = false,
    val devices: Boolean = false,
    val firewall: Boolean = false,
    val nativePortMapping: Boolean = false,
    val upnp: Boolean = false,
    val ddns: Boolean = false,
    val wireguard: Boolean = false,
    val diagnostic: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("configured", configured)
        put("dashboard", dashboard)
        put("devices", devices)
        put("firewall", firewall)
        put("nativePortMapping", nativePortMapping)
        put("upnp", upnp)
        put("ddns", ddns)
        put("wireguard", wireguard)
        put("diagnostic", diagnostic)
    }

    companion object {
        fun fromJson(value: String?): RouterCapabilities? {
            if (value.isNullOrBlank()) return null
            val o = runCatching { JSONObject(value) }.getOrNull() ?: return null
            return RouterCapabilities(
                configured = o.optBoolean("configured"),
                dashboard = o.optBoolean("dashboard"),
                devices = o.optBoolean("devices"),
                firewall = o.optBoolean("firewall"),
                nativePortMapping = o.optBoolean("nativePortMapping"),
                upnp = o.optBoolean("upnp"),
                ddns = o.optBoolean("ddns"),
                wireguard = o.optBoolean("wireguard"),
                diagnostic = o.optBoolean("diagnostic")
            )
        }
    }
}

data class WanInfo(
    val wanId: String = "",
    val logicalInterface: String = "",
    val displayName: String = "",
    val physicalPortId: Int = 0,
    val physicalPortName: String = "",
    val role: String = "",
    val supported: Boolean = false,
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val online: Boolean = false,
    val defaultEgress: Boolean = false,
    val families: List<String> = emptyList(),
    val proto: String = "",
    val ipv4: String = "",
    val ipv6: String = "",
    val gateway: String = "",
    val gateway6: String = "",
    val dns: List<String> = emptyList(),
    val uptimeSeconds: Long = 0L,
    val linkSpeedMbps: Int = 0,
    val capabilities: Map<String, Boolean> = emptyMap(),
    val source: String = "firmware",
) {
    val selectable: Boolean get() = supported && configured && enabled
    fun supports(capability: String): Boolean = capabilities[capability] == true
}

data class WanInventory(
    val wans: List<WanInfo> = emptyList(),
    val supported: Boolean = false,
    val configuredCount: Int = 0,
    val onlineCount: Int = 0,
    val multiWanMode: String = "",
    val updatedAt: Long = 0L,
) {
    val multiWanConfigured: Boolean get() = configuredCount > 1
    fun find(wanId: String?): WanInfo? = wans.firstOrNull { it.wanId.equals(wanId, true) }
    fun selectable(capability: String): List<WanInfo> = wans.filter { it.selectable && it.supports(capability) }
    fun preferred(capability: String): WanInfo? = selectable(capability).firstOrNull { it.defaultEgress } ?: selectable(capability).firstOrNull()
}

internal fun parseWanInventory(data: JSONObject): WanInventory {
    val array = data.optJSONArray("wans") ?: JSONArray()
    val wans = (0 until array.length()).mapNotNull { index ->
        val row = array.optJSONObject(index) ?: return@mapNotNull null
        val capabilities = row.optJSONObject("capabilities") ?: JSONObject()
        val wanId = cleanApiText(row.optString("wanId", row.optString("logicalInterface")))
        val ipv4 = cleanApiText(row.optString("ipv4"))
        val ipv6 = cleanApiText(row.optString("ipv6"))
        val configured = if (row.has("configured")) row.optBoolean("configured") else true
        val enabled = if (row.has("enabled")) row.optBoolean("enabled") else configured
        WanInfo(
            wanId = wanId,
            logicalInterface = cleanApiText(row.optString("logicalInterface", wanId)),
            displayName = cleanApiText(row.optString("displayName")).ifBlank { wanId.uppercase(Locale.ROOT) },
            physicalPortId = row.optInt("physicalPortId"),
            physicalPortName = cleanApiText(row.optString("physicalPortName")),
            role = cleanApiText(row.optString("role")),
            supported = if (row.has("supported")) row.optBoolean("supported") else configured,
            configured = configured,
            enabled = enabled,
            online = if (row.has("online")) row.optBoolean("online") else ipv4.isNotBlank() || ipv6.isNotBlank(),
            defaultEgress = row.optBoolean("defaultEgress"),
            families = row.optJSONArray("families")?.let { values ->
                (0 until values.length()).mapNotNull { cleanApiText(values.optString(it)).takeIf { value -> value.isNotBlank() } }
            }.orEmpty(),
            proto = cleanApiText(row.optString("proto")),
            ipv4 = ipv4,
            ipv6 = ipv6,
            gateway = cleanApiText(row.optString("gateway")),
            gateway6 = cleanApiText(row.optString("gateway6")),
            dns = row.optJSONArray("dns")?.let { values ->
                (0 until values.length()).mapNotNull { cleanApiText(values.optString(it)).takeIf { value -> value.isNotBlank() } }
            }.orEmpty(),
            uptimeSeconds = row.optLong("uptimeSeconds"),
            linkSpeedMbps = row.optInt("linkSpeedMbps"),
            capabilities = buildMap {
                val keys = capabilities.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, capabilities.optBoolean(key))
                }
            },
            source = cleanApiText(row.optString("source", "firmware")),
        )
    }.filter { it.wanId.isNotBlank() }
    return WanInventory(
        wans = wans,
        supported = data.optBoolean("supported", wans.isNotEmpty()),
        configuredCount = data.optInt("configuredCount", wans.count { it.configured }),
        onlineCount = data.optInt("onlineCount", wans.count { it.online }),
        multiWanMode = cleanApiText(data.optString("multiWanMode")),
        updatedAt = data.optLong("updatedAt"),
    )
}

data class NativePortMapRule(
    val ruleName: String = "",
    val src: String = "wan",
    val srcIp: String = "",
    val srcPort: String = "",
    val destIp: String = "",
    val destPort: String = "",
    val proto: String = "tcp"
) {
    val wanId: String
        get() = src.trim().lowercase(Locale.ROOT).ifBlank { "all" }

    fun toJson() = JSONObject()
        .put("ruleName", ruleName.trim())
        .put("wanId", wanId)
        .put("src", src)
        .put("srcIp", srcIp.trim())
        .put("srcPort", srcPort.trim())
        .put("destIp", destIp.trim())
        .put("destPort", destPort.trim())
        .put("proto", proto)
}

internal fun nativePortWanLabel(rule: NativePortMapRule): String = when (rule.wanId) {
    "all" -> "全部WAN"
    "wan1" -> "WAN1"
    "wan" -> "WAN"
    else -> rule.wanId.uppercase(Locale.ROOT)
}

internal fun parseNativePortRules(data: JSONObject): List<NativePortMapRule> {
    val arr = data.optJSONArray("portMapping") ?: data.optJSONArray("list") ?: JSONArray()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { o ->
            NativePortMapRule(
                ruleName = cleanApiText(o.optString("ruleName")),
                src = cleanApiText(o.optString("src", "wan")),
                srcIp = cleanApiText(o.optString("srcIp")),
                srcPort = cleanApiText(o.optString("srcPort")),
                destIp = cleanApiText(o.optString("destIp")),
                destPort = cleanApiText(o.optString("destPort")),
                proto = cleanApiText(o.optString("proto", "tcp"))
            )
        }
    }
}

data class UpnpMapping(
    val name: String = "",
    val clientIp: String = "",
    val protocol: String = "",
    val internalPort: String = "",
    val externalPort: String = ""
)

data class UpnpState(
    val enabled: Boolean = false,
    val wan: String = "AUTO",
    val mappings: List<UpnpMapping> = emptyList()
)

internal fun upnpWanOptions(inventory: WanInventory?, current: String): List<String> {
    if (inventory == null) return (listOf("AUTO", "WAN") + current).filter { it.isNotBlank() }.distinct()
    val available = inventory.selectable("upnp").map { it.wanId.uppercase(Locale.ROOT) }
    return ((if (available.isNotEmpty()) listOf("AUTO") else emptyList()) + available + current)
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun firewallWanOptions(inventory: WanInventory?, current: String): List<String> {
    if (inventory == null) return (listOf("wan") + current).filter { it.isNotBlank() }.distinct()
    return (inventory.wans.filter { it.configured }.map { it.logicalInterface.ifBlank { it.wanId } } + current)
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun upnpMappingStatus(state: UpnpState?, stale: Boolean, error: String): String = when {
    state == null || stale || error.isNotBlank() -> "待同步"
    !state.enabled -> "已停用"
    else -> "已映射"
}

internal fun parseUpnp(o: JSONObject): UpnpState {
    val arr = o.optJSONArray("upnpds") ?: JSONArray()
    return UpnpState(
        enabled = o.optString("enable_upnp").equals("true", true),
        wan = cleanApiText(o.optString("wan", o.optString("wanId", "AUTO"))).uppercase(),
        mappings = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                UpnpMapping(
                    name = cleanApiText(it.optString("name")),
                    clientIp = cleanApiText(it.optString("sip")),
                    protocol = cleanApiText(it.optString("proto")).uppercase(),
                    internalPort = cleanApiText(it.optString("sport")),
                    externalPort = cleanApiText(it.optString("dport"))
                )
            }
        }
    )
}

data class FirewallStats(val packets: Long = 0L, val bytes: Long = 0L)

data class FirewallRule(
    val uuid: String = "",
    val ruleName: String = "",
    val direction: String = "forward",
    val ipVersion: String = "ipv4",
    val proto: String = "tcp",
    val srcIP: String = "",
    val destIP: String = "",
    val srcPort: String = "",
    val destPort: String = "",
    val target: String = "ACCEPT",
    val enabled: Boolean = true,
    val ipv6SuffixSrc: String = "",
    val ipv6SuffixDest: String = "",
    val inIface: String = "wan",
    val outIface: String = "lan",
    val stats: FirewallStats = FirewallStats()
) {
    fun toJson(includeUuid: Boolean) = JSONObject().apply {
        if (includeUuid && uuid.isNotBlank()) put("uuid", uuid)
        put("ruleName", ruleName.trim())
        put("direction", direction)
        put("ipVersion", ipVersion)
        put("proto", proto)
        put("srcIP", srcIP.trim())
        put("destIP", destIP.trim())
        put("srcPort", srcPort.trim())
        put("destPort", destPort.trim())
        put("target", target)
        put("enable", if (enabled) "1" else "0")
        put("ipv6SuffixSrc", ipv6SuffixSrc.trim())
        put("ipv6SuffixDest", ipv6SuffixDest.trim())
        put("inIface", inIface)
        put("outIface", outIface)
    }
}

data class FirewallState(
    val rules: List<FirewallRule> = emptyList(),
    val order: JSONObject = JSONObject(),
    val maxRules: Int = 20
)

internal fun parseFirewall(data: JSONObject): FirewallState {
    val arr = data.optJSONArray("list") ?: JSONArray()
    val rules = (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { o ->
            val stats = o.optJSONObject("stats") ?: JSONObject()
            FirewallRule(
                uuid = cleanApiText(o.optString("uuid")),
                ruleName = cleanApiText(o.optString("ruleName")),
                direction = cleanApiText(o.optString("direction", "forward")),
                ipVersion = cleanApiText(o.optString("ipVersion", "ipv4")),
                proto = cleanApiText(o.optString("proto", "tcp")),
                srcIP = cleanApiText(o.optString("srcIP")),
                destIP = cleanApiText(o.optString("destIP")),
                srcPort = cleanApiText(o.optString("srcPort")),
                destPort = cleanApiText(o.optString("destPort")),
                target = cleanApiText(o.optString("target", "ACCEPT")),
                enabled = o.optString("enable", "1") != "0",
                ipv6SuffixSrc = cleanApiText(o.optString("ipv6SuffixSrc")),
                ipv6SuffixDest = cleanApiText(o.optString("ipv6SuffixDest")),
                inIface = cleanApiText(o.optString("inIface")),
                outIface = cleanApiText(o.optString("outIface")),
                stats = FirewallStats(stats.optLong("packets"), stats.optLong("bytes"))
            )
        }
    }
    return FirewallState(rules, data.optJSONObject("order") ?: JSONObject(), data.optInt("maxLen", 20))
}

data class DdnsRecord(
    val serviceId: String = "",
    val provider: String = "aliyun.com",
    val domain: String = "",
    val username: String = "",
    val enabled: Boolean = true,
    val useIpv6: Boolean = true,
    val interfaceName: String = "wan",
    val status: String = "",
    val ip: String = "",
    val lastUpdatedAt: Long = 0L,
    val passwordConfigured: Boolean = false,
    // New firmware (ddns_view) fields. Empty nativeProtocol means the legacy ddnsCfg protocol.
    val nativeProtocol: String = "",
    val recordType: String = "",
    val zone: String = "",
    val zoneId: String = "",
    val region: String = "",
    val txtTemplate: String = "",
    val txtFamily: String = "ipv4",
    val externalPort: Int = 0,
) {
    /** New ReyeeOS firmware exposes ddns_view; old firmware only has ddnsCfg. */
    val modernNative: Boolean get() = nativeProtocol.equals("ddns_view", ignoreCase = true)
    val wanId: String
        get() = interfaceName.trim().lowercase(Locale.ROOT)

    fun toJson(password: String?) = JSONObject().apply {
        put("wanId", wanId)
        if (modernNative) {
            // HUB maps service_name -> provider and merges all public fields on update.
            put("provider", provider)
            put("service_name", provider)
            put("domain", domain.trim())
            put("username", username.trim())
            put("interface", interfaceName)
            put("record_type", effectiveRecordType)
            put("enabled", if (enabled) "1" else "0")
            if (zone.isNotBlank()) put("zone", zone)
            if (zoneId.isNotBlank()) put("zone_id", zoneId)
            if (region.isNotBlank()) put("region", region)
            if (effectiveRecordType.equals("TXT", ignoreCase = true)) {
                put("txt_template", txtTemplate)
                put("txt_family", txtFamily.ifBlank { "ipv4" })
                put("external_port", externalPort)
            }
            if (password != null) put("password", password)
        } else {
            put("service_name", provider)
            put("domain", domain.trim())
            put("username", username.trim())
            put("enable", if (enabled) "1" else "0")
            put("use_ipv6", if (useIpv6) "1" else "0")
            put("interface", interfaceName)
            if (password != null) put("password", password)
        }
    }

    /** Effective record type for the new firmware, derived from useIpv6 when absent. */
    val effectiveRecordType: String
        get() = recordType.ifBlank { if (useIpv6) "AAAA" else "A" }
}

internal fun nativeDdnsStatusLabel(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
    "success", "succeed", "ok", "normal" -> "更新成功"
    "error", "fail", "failed" -> "更新失败"
    "waiting", "pending" -> "等待更新"
    else -> raw.trim()
}

internal fun nativeDdnsDetailText(record: DdnsRecord): String =
    record.ip.trim().ifBlank { nativeDdnsStatusLabel(record.status).ifBlank { "等待更新" } }

/** Capability bits per new-firmware provider: 1=TXT, 2=AAAA, 4=zone, 8=zone_id, 16=region. */
internal fun nativeDdnsProviderFlags(provider: String): Int = when (provider.trim().lowercase(Locale.ROOT)) {
    "aliyun.com", "dnspod.cn" -> 0x27
    "tencentcloud.com" -> 0x07
    "no-ip.com", "dyn.com" -> 0x22
    "oray.com" -> 0x20
    "cloudflare.com" -> 0x0b
    "huaweicloud.com" -> 0x1b
    "duckdns.org" -> 0x03
    else -> 0x07
}

/**
 * 固件原生 DDNS（`ddnsCfg`）的服务商全集，顺序照固件选择器。
 *
 * 每一项都是固件认的 provider 串（域名形式，和 `/etc/config/ddns` 里的
 * `service_name` 同源）。注意 dynv6 / deSEC / Dynu / IPv64 属于 LabProbe 自己的
 * DDNS（`labProbeProviderIds`），固件原生这边没有，别混进来。
 *
 * 之前「新增只给三项」不是因为这张表短，而是编辑器拿记录上的协议字段判断新旧固件，
 * 新建记录没有该字段，于是永远掉进 legacy 分支。
 */
internal val nativeDdnsProviders = listOf(
    "aliyun.com", "dnspod.cn", "tencentcloud.com", "no-ip.com",
    "oray.com", "dyn.com", "cloudflare.com", "huaweicloud.com", "duckdns.org"
)

internal fun nativeDdnsProviderLabel(id: String): String = when (id.trim().lowercase(Locale.ROOT)) {
    "aliyun.com" -> "阿里云"
    "dnspod.cn" -> "DNSPod"
    "tencentcloud.com" -> "腾讯云 API3"
    "no-ip.com" -> "No-IP"
    "oray.com" -> "花生壳"
    "dyn.com" -> "DynDNS"
    "cloudflare.com" -> "Cloudflare"
    "huaweicloud.com" -> "华为云"
    "duckdns.org" -> "DuckDNS"
    else -> id
}

/**
 * 这张表单该用新固件（`ddnsCfg`）还是老固件（`ddns.service`）的 schema。
 *
 * 记录自带协议就听它的；新建记录没有协议字段，只能看设备能力 —— Hub 的 DDNS 快照
 * 并不下发 `nativeProtocol`，光看记录的话新增永远只会给三个服务商。
 */
internal fun ddnsEditorUsesModernForm(nativeProtocol: String, nativeDdns: Boolean): Boolean =
    nativeProtocol.trim().equals("ddns_view", ignoreCase = true) ||
        (nativeProtocol.isBlank() && nativeDdns)

internal fun nativeDdnsWanOptions(inventory: WanInventory?, current: String): List<String> {
    if (inventory == null) return (listOf("wan") + current).filter { it.isNotBlank() }.distinct()
    return (inventory.selectable("nativeDdns").map { it.wanId } + current)
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun nativeDdnsSupportsRecordType(provider: String, recordType: String): Boolean {
    val flags = nativeDdnsProviderFlags(provider)
    return when (recordType.uppercase(Locale.ROOT)) {
        "A" -> true
        "AAAA" -> flags and 0x02 != 0
        "TXT" -> flags and 0x01 != 0
        else -> false
    }
}

private fun JSONObject.ddnsText(vararg keys:String):String{
    for(key in keys){
        if(!has(key)||isNull(key))continue
        val text=cleanApiText(opt(key)?.toString())
        if(text.isNotBlank())return text
    }
    return ""
}

private fun JSONObject.ddnsValueMap(key: String): Map<String, String> {
    val values = optJSONObject(key) ?: return emptyMap()
    return values.keys().asSequence().filter { it == "CNAME" || it == "TXT" }.associateWith { values.ddnsText(it) }
}

private fun JSONObject.ddnsFlag(default:Boolean,vararg keys:String):Boolean{
    for(key in keys){
        if(!has(key)||isNull(key))continue
        return when(val value=opt(key)){
            is Boolean->value
            is Number->value.toInt()!=0
            else->when(cleanApiText(value?.toString()).lowercase(Locale.ROOT)){
                "1","true","yes","on","enabled","enable","ipv6"->true
                "0","false","no","off","disabled","disable","ipv4"->false
                else->default
            }
        }
    }
    return default
}

private fun JSONObject.ddnsEpoch(vararg keys:String):Long{
    for(key in keys){
        if(!has(key)||isNull(key))continue
        val epoch=when(val value=opt(key)){
            is Number->value.toLong()
            else->value?.toString()?.trim()?.toLongOrNull()
        } ?: 0L
        if(epoch>0L)return epoch
    }
    return 0L
}

internal fun parseDdnsList(data: JSONObject): List<DdnsRecord> {
    val arrayKey = listOf("list", "services", "data", "records", "entries")
        .firstOrNull { data.has(it) && !data.isNull(it) }
        ?: throw org.json.JSONException("DDNS 响应缺少记录数组")
    val arr = data.optJSONArray(arrayKey)
        ?: throw org.json.JSONException("DDNS 记录数组结构异常")
    val topProtocol = data.ddnsText("nativeProtocol")
    val ids = mutableSetOf<String>()
    return (0 until arr.length()).map { i ->
        val o = arr.optJSONObject(i)
            ?: throw org.json.JSONException("DDNS 包含无效记录")
        val protocol = o.ddnsText("nativeProtocol").ifBlank { topProtocol }
        val serviceId = o.ddnsText("serviceId", "service_id", "id", "service")
        if (serviceId.isBlank() || !ids.add(serviceId)) {
            throw org.json.JSONException("DDNS 记录 ID 缺失或重复")
        }
        val useIpv6 = o.ddnsFlag(true, "use_ipv6", "useIpv6", "ipv6", "ipVersion")
        val recordType = o.ddnsText("record_type", "recordType")
        val effectiveType = recordType.ifBlank { if (useIpv6) "AAAA" else "A" }
        val fallbackIp = o.ddnsText("ip", "currentIp", "current_ip", "address")
        val content = o.ddnsText("content")
        val detectedIp = o.ddnsText("detected_ip", "detectedIp")
        val resolvedIp = if (effectiveType.equals("TXT", ignoreCase = true)) {
            content.ifBlank { fallbackIp }
        } else {
            detectedIp.ifBlank { content }.ifBlank { fallbackIp }
        }
        DdnsRecord(
            serviceId = serviceId,
            provider = o.ddnsText("service_name", "serviceName", "provider", "providerName").ifBlank { "aliyun.com" },
            domain = o.ddnsText("domain", "host", "hostname", "record"),
            username = o.ddnsText("username", "user", "accessKey", "accessKeyId", "access_key_id"),
            enabled = o.ddnsFlag(true, "enable", "enabled", "isEnabled"),
            useIpv6 = useIpv6,
            interfaceName = o.ddnsText("interface", "interfaceName", "wan", "iface")
                .ifBlank { o.ddnsText("wanId") }
                .ifBlank { "wan" },
            status = o.ddnsText("status", "state", "message", "msg"),
            ip = resolvedIp,
            lastUpdatedAt = o.ddnsEpoch("last_success", "lastSuccessAt", "last_updated_at", "lastUpdatedAt", "lastUpdate", "last_log"),
            passwordConfigured = o.ddnsFlag(false, "passwordConfigured", "password_configured", "hasPassword", "has_password"),
            nativeProtocol = protocol,
            recordType = recordType,
            zone = o.ddnsText("zone"),
            zoneId = o.ddnsText("zone_id"),
            region = o.ddnsText("region"),
            txtTemplate = o.ddnsText("txt_template"),
            txtFamily = o.ddnsText("txt_family").ifBlank { "ipv4" },
            externalPort = o.optInt("external_port", 0),
        )
    }
}

data class LabProbeDdnsProvider(
    val id: String = "",
    val supportsA: Boolean = true,
    val supportsAAAA: Boolean = true,
    val recordTypes: List<String> = listOf("A", "AAAA"),
) {
    fun supports(recordType: String): Boolean = when (recordType.uppercase(Locale.ROOT)) {
        "A" -> supportsA && recordTypes.contains("A")
        "AAAA" -> supportsAAAA && recordTypes.contains("AAAA")
        else -> recordTypes.contains(recordType.uppercase(Locale.ROOT))
    }
}

data class LabProbeDdnsAddress(
    val detectedIpv4: String = "",
    val detectedIpv6: String = "",
    val ipv4State: String = "unavailable",
    val ipv6State: String = "unavailable",
    val ipv4Source: String = "",
    val ipv6Source: String = "",
    val detectedAt: Long = 0L,
)

data class LabProbeDdnsSnapshot(
    val records: List<LabProbeDdnsRecord> = emptyList(),
    val address: LabProbeDdnsAddress = LabProbeDdnsAddress(),
    val providers: List<LabProbeDdnsProvider> = emptyList(),
    val wanSupported: Boolean = false,
)

data class LabProbeDdnsRecord(
    val id: String = "",
    val wanId: String = "",
    val provider: String = "",
    val hostname: String = "",
    val recordTypes: List<String> = listOf("A", "AAAA"),
    val ttl: Int = 300,
    val enabled: Boolean = true,
    val detectedIpv4: String = "",
    val detectedIpv6: String = "",
    val ipv4State: String = "unavailable",
    val ipv6State: String = "unavailable",
    val ipv4Source: String = "",
    val ipv6Source: String = "",
    val publishedIpv4: String = "",
    val publishedIpv6: String = "",
    val recordValues: Map<String, String> = emptyMap(),
    val publishedValues: Map<String, String> = emptyMap(),
    val status: String = "waiting",
    val lastDetectedAt: Long = 0L,
    val lastUpdatedAt: Long = 0L,
    val lastError: String = "",
    val credentialsConfigured: Boolean = false,
) {
    fun toJson(credentials: Map<String, String> = emptyMap()): JSONObject = JSONObject().apply {
        put("wanId", wanId.trim())
        put("provider", provider.trim())
        put("hostname", hostname.trim())
        put("recordTypes", JSONArray(normalizeLabProbeRecordTypes(recordTypes)))
        put("ttl", ttl.coerceIn(60, 86400))
        put("enabled", enabled)
        val values = JSONObject()
        normalizeLabProbeRecordTypes(recordTypes).filter { it == "CNAME" || it == "TXT" }.forEach { type ->
            recordValues[type]?.let { value ->
                val normalized = if (type == "CNAME") value.trim().trimEnd('.') else value
                normalized.takeIf { it.isNotBlank() }?.let { values.put(type, it) }
            }
        }
        if (values.length() > 0) put("recordValues", values)
        if (credentials.isNotEmpty()) {
            put("credentials", JSONObject().apply {
                credentials.forEach { (key, value) -> if (value.isNotBlank()) put(key, value) }
            })
        }
    }
}

internal fun normalizeLabProbeRecordTypes(values: List<String>): List<String> {
    val normalized = values.map { it.trim().uppercase(Locale.ROOT) }.filter { it in setOf("A", "AAAA", "CNAME", "TXT") }.distinct()
    return if ("CNAME" in normalized) listOf("CNAME") else normalized.ifEmpty { listOf("A", "AAAA") }
}

internal data class LabProbeDdnsWanEditorState(
    val selectedWanId: String,
    val options: List<String>,
    val showSelector: Boolean,
)

internal fun labProbeDdnsWanEditorState(record: LabProbeDdnsRecord, inventory: WanInventory?, wanSupported: Boolean): LabProbeDdnsWanEditorState {
    val configuredWans = if (wanSupported) inventory?.wans.orEmpty().filter { it.configured && it.wanId.isNotBlank() } else emptyList()
    val currentWanId = record.wanId.trim()
    val selectedWanId = when {
        currentWanId.isNotBlank() -> currentWanId
        configuredWans.size == 1 -> configuredWans.single().wanId
        record.id.isNotBlank() -> ""
        else -> configuredWans.firstOrNull { it.defaultEgress }?.wanId
            ?: configuredWans.firstOrNull()?.wanId
            ?: ""
    }
    val options = (configuredWans.map { it.wanId } + selectedWanId)
        .filter { it.isNotBlank() }
        .distinct()
    return LabProbeDdnsWanEditorState(
        selectedWanId = selectedWanId,
        options = options,
        showSelector = configuredWans.size > 1,
    )
}

private fun normalizeLabProbeProviderRecordTypes(values: List<String>): List<String> {
    val normalized = values.map { it.trim().uppercase(Locale.ROOT) }.filter { it in setOf("A", "AAAA", "CNAME", "TXT") }.distinct()
    return normalized.ifEmpty { listOf("A", "AAAA") }
}

internal fun labProbeCnameTargetIsValid(hostname: String, target: String): Boolean {
    val owner = hostname.trim().trimEnd('.').lowercase(Locale.ROOT)
    val value = target.trim().trimEnd('.').lowercase(Locale.ROOT)
    if (value.isBlank() || owner.isBlank() || value == owner || value.contains("://") || value.contains('/') || value.any(Char::isWhitespace)) return false
    val labels = value.split('.')
    return labels.size >= 2 && labels.all { label ->
        label.isNotEmpty() && label.length <= 63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' }
    }
}

internal fun labProbeRecordValidationError(record: LabProbeDdnsRecord, provider: LabProbeDdnsProvider? = null): String? {
    val types = normalizeLabProbeRecordTypes(record.recordTypes)
    if ("CNAME" in types && types.size > 1) return "CNAME 不能与其他记录类型共存"
    if (provider != null && types.any { !provider.supports(it) }) return "当前服务商不支持所选记录类型"
    if ("CNAME" in types && !labProbeCnameTargetIsValid(record.hostname, record.recordValues["CNAME"].orEmpty())) return "请填写有效的 CNAME 目标域名"
    if ("TXT" in types && record.recordValues["TXT"].orEmpty().isBlank()) return "请填写 TXT 内容"
    return null
}

internal fun parseLabProbeDdns(root: JSONObject): LabProbeDdnsSnapshot {
    val addressObject = root.optJSONObject("address") ?: JSONObject()
    val address = LabProbeDdnsAddress(
        detectedIpv4 = addressObject.ddnsText("detectedIpv4"),
        detectedIpv6 = addressObject.ddnsText("detectedIpv6"),
        ipv4State = labProbeIpv4State(addressObject.ddnsText("ipv4State")),
        ipv6State = labProbeIpv6State(addressObject.ddnsText("ipv6State")),
        ipv4Source = addressObject.ddnsText("ipv4Source"),
        ipv6Source = addressObject.ddnsText("ipv6Source"),
        detectedAt = addressObject.optLong("detectedAt", 0L),
    )
    val recordsArray = root.optJSONArray("records")
        ?: root.optJSONObject("record")?.let { JSONArray().put(it) }
        ?: JSONArray()
    val records = recordsArray.let { array ->
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val recordTypes = item.optJSONArray("recordTypes")?.let { types ->
                    (0 until types.length()).map { types.optString(it) }
                }?.let(::normalizeLabProbeRecordTypes) ?: listOf("A", "AAAA")
                LabProbeDdnsRecord(
                    id = item.ddnsText("id", "recordId"),
                    wanId = item.ddnsText("wanId"),
                    provider = item.ddnsText("provider"),
                    hostname = item.ddnsText("hostname", "domain"),
                    recordTypes = recordTypes,
                    ttl = item.optInt("ttl", 300),
                    enabled = item.ddnsFlag(true, "enabled", "enable"),
                    detectedIpv4 = item.ddnsText("detectedIpv4").ifBlank { address.detectedIpv4 },
                    detectedIpv6 = item.ddnsText("detectedIpv6").ifBlank { address.detectedIpv6 },
                    ipv4State = labProbeIpv4State(item.ddnsText("ipv4State").ifBlank { address.ipv4State }),
                    ipv6State = labProbeIpv6State(item.ddnsText("ipv6State").ifBlank { address.ipv6State }),
                    ipv4Source = item.ddnsText("ipv4Source").ifBlank { address.ipv4Source },
                    ipv6Source = item.ddnsText("ipv6Source").ifBlank { address.ipv6Source },
                    publishedIpv4 = item.ddnsText("publishedIpv4"),
                    publishedIpv6 = item.ddnsText("publishedIpv6"),
                    recordValues = item.ddnsValueMap("recordValues"),
                    publishedValues = item.ddnsValueMap("publishedValues"),
                    status = labProbeFlowStatus(item.ddnsText("status")),
                    lastDetectedAt = item.optLong("lastDetectedAt", 0L),
                    lastUpdatedAt = item.optLong("lastUpdatedAt", 0L),
                    lastError = item.ddnsText("lastError"),
                    credentialsConfigured = item.ddnsFlag(false, "credentialsConfigured"),
                )
            }
        }
    }
    return LabProbeDdnsSnapshot(records = records, address = address, providers = parseLabProbeDdnsProviders(root), wanSupported = root.optBoolean("wanSupported", false))
}

private fun labProbeFlowStatus(value: String): String = when (value.lowercase(Locale.ROOT)) {
    "disabled", "waiting", "detected", "updating", "published", "error" -> value.lowercase(Locale.ROOT)
    else -> "waiting"
}

private fun labProbeIpv4State(value: String): String = when (value.lowercase(Locale.ROOT)) {
    "public", "cgnat", "unavailable", "ambiguous" -> value.lowercase(Locale.ROOT)
    else -> "unavailable"
}

private fun labProbeIpv6State(value: String): String = when (value.lowercase(Locale.ROOT)) {
    "public", "unavailable", "ambiguous" -> value.lowercase(Locale.ROOT)
    else -> "unavailable"
}

private fun parseLabProbeDdnsProviders(root: JSONObject): List<LabProbeDdnsProvider> {
    val array = root.optJSONArray("providers") ?: JSONArray()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { item ->
            LabProbeDdnsProvider(
                id = item.ddnsText("id", "provider"),
                supportsA = item.optBoolean("supportsA", true),
                supportsAAAA = item.optBoolean("supportsAAAA", true),
                recordTypes = item.optJSONArray("recordTypes")?.let { types ->
                    normalizeLabProbeProviderRecordTypes((0 until types.length()).map { types.optString(it) })
                } ?: listOf("A", "AAAA"),
            )
        }
    }
}

private fun routerDiagnosticTitleZh(type: String, raw: String): String {
    val text = cleanApiText(raw).trim()
    if (text.any { it.code > 127 }) return text
    val lower = (type + " " + text).lowercase()
    return when {
        "wan" in lower || "external network port" in lower -> "外网口连接"
        "lan" in lower || "internal network" in lower -> "局域网连接"
        "dns" in lower -> "DNS 解析"
        "gateway" in lower -> "网关连接"
        "internet" in lower || "network access" in lower -> "互联网连接"
        "speed" in lower || "negotiation" in lower -> "端口协商速率"
        "cable" in lower || "link" in lower -> "网线连接"
        else -> "网络状态检查"
    }
}

private fun routerDiagnosticTextZh(raw: String): String {
    var text = cleanApiText(raw).replace("<br>", "\n", true).trim()
    if (text.isBlank() || text.any { it.code > 127 } || !Regex("[A-Za-z]{3,}").containsMatchIn(text)) return text
    val replacements = listOf(
        "check external network port network cable is OK" to "请检查外网口网线连接是否正常",
        "external network port network cable is OK" to "外网口网线连接正常",
        "check wan port network cable" to "请检查 WAN 口网线连接",
        "network cable is unplugged" to "网线未连接",
        "network cable is connected" to "网线已连接",
        "link is normal" to "链路正常",
        "network is normal" to "网络状态正常",
        "internet access is normal" to "互联网连接正常",
        "dns is normal" to "DNS 解析正常",
        "gateway is reachable" to "网关可达",
        "negotiation speed" to "协商速率",
        "please check" to "请检查",
        "success" to "正常",
        "failed" to "失败",
        "failure" to "失败",
        "abnormal" to "异常",
        "normal" to "正常"
    )
    replacements.forEach { (old, new) -> text = text.replace(old, new, ignoreCase = true) }
    if (!Regex("[A-Za-z]{3,}").containsMatchIn(text)) return text
    val lower = text.lowercase()
    return when {
        "cable" in lower || "port" in lower && "link" in lower -> "请检查对应接口的网线连接"
        "speed" in lower || "negotiation" in lower -> "请检查端口协商速率"
        "dns" in lower -> "请检查 DNS 配置和解析状态"
        "gateway" in lower -> "请检查网关配置和连通性"
        "internet" in lower || "network" in lower -> "请检查互联网连接状态"
        "ok" in lower || "success" in lower || "normal" in lower -> "检测正常"
        "fail" in lower || "error" in lower || "abnormal" in lower -> "检测异常"
        else -> "请检查该项网络状态"
    }
}

data class RouterDiagnosticItem(
    val type: String = "",
    val title: String = "",
    val status: String = "",
    val result: String = "",
    val tips: String = "",
    val advise: String = "",
    val port: String = ""
)

data class RouterDiagnostic(
    val progress: String = "0%",
    val errorCount: Int = 0,
    val items: List<RouterDiagnosticItem> = emptyList()
)

internal fun parseDiagnostic(data: JSONObject): RouterDiagnostic {
    val groups = data.optJSONArray("list") ?: JSONArray()
    val rows = mutableListOf<RouterDiagnosticItem>()
    for (i in 0 until groups.length()) {
        val group = groups.optJSONObject(i) ?: continue
        val children = group.optJSONArray("list") ?: JSONArray()
        if (children.length() == 0) {
            rows += RouterDiagnosticItem(
                type = cleanApiText(group.optString("type")),
                title = routerDiagnosticTitleZh(group.optString("type"), group.optString("item")),
                status = cleanApiText(group.optString("status")),
                result = routerDiagnosticTextZh(group.optString("result")),
                tips = routerDiagnosticTextZh(group.optString("tips")),
                advise = routerDiagnosticTextZh(group.optString("advise"))
            )
        }
        for (j in 0 until children.length()) {
            val child = children.optJSONObject(j) ?: continue
            val childData = child.optJSONObject("data") ?: JSONObject()
            rows += RouterDiagnosticItem(
                type = cleanApiText(group.optString("type")),
                title = routerDiagnosticTitleZh(group.optString("type"), child.optString("item")),
                status = cleanApiText(child.optString("status")),
                result = routerDiagnosticTextZh(child.optString("result")),
                tips = routerDiagnosticTextZh(child.optString("tips")),
                advise = routerDiagnosticTextZh(child.optString("advise")),
                port = cleanApiText(childData.optString("port"))
            )
        }
    }
    return RouterDiagnostic(
        progress = cleanApiText(data.optString("process", "0%")),
        errorCount = data.optString("error_count", "0").toIntOrNull() ?: 0,
        items = rows
    )
}
