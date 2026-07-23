package com.labprobe.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** App-facing state. Router credentials and eWeb session details remain in Hub only. */
data class RouterConnectionSnapshot(
    val connected: Boolean = false,
    val statusText: String = "Waiting for Hub status",
    val lastSuccessAt: Long = 0L,
    val lastError: String = ""
)

data class RouterHubStatus(
    val state: String = "no_router_data",
    val connected: Boolean = false,
    val message: String = "Hub is online, but router data is unavailable",
    val errorCode: String = "HUB_NO_ROUTER_DATA",
    val lastSuccessAt: Long = 0L
)

object RouterConnectionStore {
    var snapshot by mutableStateOf(RouterConnectionSnapshot())
        private set

    fun apply(status: RouterHubStatus) {
        snapshot = RouterConnectionSnapshot(
            connected = status.connected,
            statusText = status.message,
            lastSuccessAt = status.lastSuccessAt,
            lastError = if (status.connected) "" else status.message
        )
    }

    fun markSuccess() {
        snapshot = snapshot.copy(
            connected = true,
            statusText = "Hub router data is available",
            lastSuccessAt = System.currentTimeMillis() / 1000L,
            lastError = ""
        )
    }

    fun markFailure(message: String) {
        snapshot = snapshot.copy(
            connected = false,
            statusText = message,
            lastError = message
        )
    }
}

/** Product-level client for Hub router-control endpoints. All transport and auth go through HubApi. */
class RouterControlApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)

    private fun execute(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        return try {
            val root = hubApi.requestJson(path, method, body)
            if (root.has("ok") && !root.optBoolean("ok")) {
                throw RouterStatusUnavailableException()
            }
            if (!path.substringBefore('?').endsWith("/status")) {
                RouterConnectionStore.markSuccess()
            }
            root
        } catch (error: HubAuthenticationException) {
            throw error
        } catch (error: HubRouterNoDataException) {
            RouterConnectionStore.markFailure(error.message ?: "Hub is online, but router data is unavailable")
            throw error
        } catch (error: HubRouterLoginException) {
            RouterConnectionStore.markFailure(error.message ?: "Hub could not log in to the router")
            throw error
        } catch (error: Exception) {
            RouterConnectionStore.markFailure(error.message ?: "Hub router request failed")
            throw error
        }
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
            state = cleanApiText(root.optString("state", "no_router_data")),
            connected = root.optBoolean("connected", false),
            message = cleanApiText(root.optString("message", "Hub is online, but router data is unavailable")),
            errorCode = cleanApiText(root.optString("errorCode", "HUB_NO_ROUTER_DATA")),
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

private fun parseNativePortRules(data: JSONObject): List<NativePortMapRule> {
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

private fun parseUpnp(o: JSONObject): UpnpState {
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

private fun parseFirewall(data: JSONObject): FirewallState {
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

private fun parseDdnsList(data: JSONObject): List<DdnsRecord> {
    val arr = data.optJSONArray("list") ?: data.optJSONArray("data") ?: JSONArray()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { o ->
            DdnsRecord(
                serviceId = cleanApiText(o.optString("service")).ifBlank { cleanApiText(o.optString("id")) },
                provider = cleanApiText(o.optString("service_name")).ifBlank { cleanApiText(o.optString("provider", "aliyun.com")) },
                domain = cleanApiText(o.optString("domain")),
                username = cleanApiText(o.optString("username")),
                enabled = o.optString("enable", "1") != "0",
                useIpv6 = o.optString("use_ipv6", "1") == "1",
                interfaceName = cleanApiText(o.optString("interface")).ifBlank { cleanApiText(o.optString("wan", "wan")) },
                status = cleanApiText(o.optString("status")),
                ip = cleanApiText(o.optString("ip")),
                passwordConfigured = o.optBoolean("passwordConfigured")
            )
        }
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

private fun parseDiagnostic(data: JSONObject): RouterDiagnostic {
    val groups = data.optJSONArray("list") ?: JSONArray()
    val rows = mutableListOf<RouterDiagnosticItem>()
    for (i in 0 until groups.length()) {
        val group = groups.optJSONObject(i) ?: continue
        val children = group.optJSONArray("list") ?: JSONArray()
        if (children.length() == 0) {
            rows += RouterDiagnosticItem(
                type = cleanApiText(group.optString("type")),
                title = cleanApiText(group.optString("item")),
                status = cleanApiText(group.optString("status")),
                result = cleanApiText(group.optString("result")),
                tips = cleanApiText(group.optString("tips")),
                advise = cleanApiText(group.optString("advise"))
            )
        }
        for (j in 0 until children.length()) {
            val child = children.optJSONObject(j) ?: continue
            val childData = child.optJSONObject("data") ?: JSONObject()
            rows += RouterDiagnosticItem(
                type = cleanApiText(group.optString("type")),
                title = cleanApiText(child.optString("item")),
                status = cleanApiText(child.optString("status")),
                result = cleanApiText(child.optString("result")),
                tips = cleanApiText(child.optString("tips")),
                advise = cleanApiText(child.optString("advise")).replace("<br>", "\n", true),
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
