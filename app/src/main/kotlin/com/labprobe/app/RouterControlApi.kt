package com.labprobe.app

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

    fun apply(status: RouterHubStatus) {
        val sessionConnected = status.sessionConnected || status.connected
        val localized = when {
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

    private fun execute(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        // A DDNS/firewall/UPnP read failure is a resource refresh failure, not a
        // global Hub/router disconnect. Only /status owns connection semantics.
        val root = hubApi.requestJson(path, method, body)
        if (root.has("ok") && !root.optBoolean("ok")) {
            throw RouterStatusUnavailableException()
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
            diagnostic = f.optBoolean("diagnostic")
        )
    }

    suspend fun hubStatus(): RouterHubStatus {
        val root = get("/api/router/status")
        return RouterHubStatus(
            state = cleanApiText(root.optString("state", "checking")),
            connected = root.optBoolean("connected", false),
            sessionConnected = root.optBoolean("sessionConnected", root.optBoolean("connected", false)),
            dataAvailable = root.optBoolean("dataAvailable", root.optBoolean("connected", false)),
            message = cleanApiText(root.optString("message", "正在准备路由控制数据")),
            errorCode = cleanApiText(root.optString("errorCode", "")),
            lastSuccessAt = root.optLong("lastSuccessAt", 0L)
        ).also(RouterConnectionStore::apply)
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
        parseDdnsList(get("/api/router/ddns${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject())

    suspend fun addDdns(record: DdnsRecord, password: String): List<DdnsRecord> =
        parseDdnsList(send("/api/router/ddns", "POST", record.toJson(password)).optJSONObject("data") ?: JSONObject())

    suspend fun updateDdns(record: DdnsRecord, password: String?): List<DdnsRecord> =
        parseDdnsList(send("/api/router/ddns/${record.serviceId}", "PUT", record.toJson(password)).optJSONObject("data") ?: JSONObject())

    suspend fun deleteDdns(serviceId: String): List<DdnsRecord> =
        parseDdnsList(send("/api/router/ddns/$serviceId", "DELETE").optJSONObject("data") ?: JSONObject())

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

    suspend fun updateLabProbeDdnsNow(recordId: String): JSONObject =
        send("/api/ddns/$recordId/update", "POST", JSONObject().put("force", true))

    suspend fun refreshLabProbeDdnsAddress(): Long = hubApi.requestRouterDashboardRefresh()

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
    val diagnostic: Boolean = false
)

data class NativePortMapRule(
    val ruleName: String = "",
    val src: String = "wan",
    val srcIp: String = "",
    val srcPort: String = "",
    val destIp: String = "",
    val destPort: String = "",
    val proto: String = "tcp"
) {
    fun toJson() = JSONObject()
        .put("ruleName", ruleName.trim())
        .put("src", src)
        .put("srcIp", srcIp.trim())
        .put("srcPort", srcPort.trim())
        .put("destIp", destIp.trim())
        .put("destPort", destPort.trim())
        .put("proto", proto)
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

internal fun parseUpnp(o: JSONObject): UpnpState {
    val arr = o.optJSONArray("upnpds") ?: JSONArray()
    return UpnpState(
        enabled = o.optString("enable_upnp").equals("true", true),
        wan = cleanApiText(o.optString("wan", "AUTO")).uppercase(),
        mappings = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                UpnpMapping(
                    name = cleanApiText(it.optString("name")).ifBlank { "UPnP 映射" },
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
    val passwordConfigured: Boolean = false
) {
    fun toJson(password: String?) = JSONObject().apply {
        put("service_name", provider)
        put("domain", domain.trim())
        put("username", username.trim())
        put("enable", if (enabled) "1" else "0")
        put("use_ipv6", if (useIpv6) "1" else "0")
        put("interface", interfaceName)
        if (password != null) put("password", password)
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

internal fun parseDdnsList(data: JSONObject): List<DdnsRecord> {
    val arr = data.optJSONArray("list") ?: data.optJSONArray("data") ?: data.optJSONArray("records") ?: JSONArray()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { o ->
            DdnsRecord(
                serviceId = o.ddnsText("service", "serviceId", "service_id", "id", "uuid"),
                provider = o.ddnsText("service_name", "serviceName", "provider", "providerName").ifBlank { "aliyun.com" },
                domain = o.ddnsText("domain", "host", "hostname", "record"),
                username = o.ddnsText("username", "user", "accessKey", "accessKeyId", "access_key_id"),
                enabled = o.ddnsFlag(true, "enable", "enabled", "isEnabled"),
                useIpv6 = o.ddnsFlag(true, "use_ipv6", "useIpv6", "ipv6", "ipVersion"),
                interfaceName = o.ddnsText("interface", "interfaceName", "wan", "iface").ifBlank { "wan" },
                status = o.ddnsText("status", "state", "message", "msg"),
                ip = o.ddnsText("ip", "currentIp", "current_ip", "address"),
                passwordConfigured = o.ddnsFlag(false, "passwordConfigured", "password_configured", "hasPassword", "has_password")
            )
        }
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
)

data class LabProbeDdnsRecord(
    val id: String = "",
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
    return LabProbeDdnsSnapshot(records = records, address = address, providers = parseLabProbeDdnsProviders(root))
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
