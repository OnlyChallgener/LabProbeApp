package com.labprobe.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Product-level client for Hub 0.9.8 router-control endpoints. */
class RouterControlApi(private val prefs: AppPrefs) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .dns(CustomDns(prefs.hubDns))
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .build()

    private fun builder(path: String): Request.Builder = Request.Builder()
        .url(joinUrl(prefs.hub, path))
        .header("Accept", "application/json")
        .apply { if (prefs.token.isNotBlank()) header("Authorization", "Bearer ${prefs.token}") }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrElse {
                if (!response.isSuccessful) error("HTTP ${response.code}")
                error("Hub 返回内容无法解析")
            }
            if (!response.isSuccessful || !root.optBoolean("ok", false)) {
                val message = cleanApiText(root.optString("message")).ifBlank {
                    cleanApiText(root.optString("error")).ifBlank { "HTTP ${response.code}" }
                }
                error(message)
            }
            return root
        }
    }

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        execute(builder(path).get().build())
    }

    private suspend fun send(path: String, method: String, body: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val requestBody = body.toString().toRequestBody(jsonType)
        val request = when (method) {
            "POST" -> builder(path).post(requestBody).build()
            "PUT" -> builder(path).put(requestBody).build()
            "PATCH" -> builder(path).patch(requestBody).build()
            "DELETE" -> builder(path).delete(requestBody).build()
            else -> error("Unsupported method $method")
        }
        execute(request)
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

    suspend fun routerConfig(): RouterLoginConfig {
        val root = get("/api/router/config")
        return RouterLoginConfig(
            address = cleanApiText(root.optString("address")),
            passwordConfigured = root.optBoolean("passwordConfigured"),
            sessionSeconds = root.optInt("sessionSeconds", 3600),
            sessionActive = root.optBoolean("sessionActive"),
            serialNumber = cleanApiText(root.optString("serialNumber"))
        )
    }

    suspend fun saveRouterConfig(address: String, password: String?, sessionSeconds: Int): RouterLoginConfig {
        val body = JSONObject()
            .put("address", address.trim())
            .put("sessionSeconds", sessionSeconds.coerceIn(600, 7200))
            .put("test", true)
        if (password != null) body.put("password", password)
        val root = send("/api/router/config", "PUT", body)
        return RouterLoginConfig(
            address = cleanApiText(root.optString("address")),
            passwordConfigured = root.optBoolean("passwordConfigured"),
            sessionSeconds = root.optInt("sessionSeconds", 3600),
            sessionActive = root.optBoolean("sessionActive"),
            serialNumber = cleanApiText(root.optString("serialNumber"))
        )
    }

    suspend fun nativePortMappings(force: Boolean = false): List<NativePortMapRule> {
        val data = get("/api/router/port-mapping${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("portMapping") ?: data.optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseNativePortRule) }
    }

    suspend fun addNativePortMapping(rule: NativePortMapRule): List<NativePortMapRule> {
        val root = send("/api/router/port-mapping", "POST", rule.toJson())
        return parseNativePortRulesFromWrite(root)
    }

    suspend fun updateNativePortMapping(oldName: String, rule: NativePortMapRule): List<NativePortMapRule> {
        val safe = URLEncoder.encode(oldName, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        val root = send("/api/router/port-mapping/$safe", "PUT", rule.toJson())
        return parseNativePortRulesFromWrite(root)
    }

    suspend fun deleteNativePortMapping(ruleName: String): List<NativePortMapRule> {
        val safe = URLEncoder.encode(ruleName, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        val root = send("/api/router/port-mapping/$safe", "DELETE")
        return parseNativePortRulesFromWrite(root)
    }

    private fun parseNativePortRulesFromWrite(root: JSONObject): List<NativePortMapRule> {
        val data = root.optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("portMapping") ?: data.optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseNativePortRule) }
    }

    suspend fun upnp(force: Boolean = false): UpnpState {
        val data = get("/api/router/upnp${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject()
        return parseUpnp(data)
    }

    suspend fun setUpnp(enabled: Boolean, wan: String): UpnpState {
        val root = send("/api/router/upnp", "PUT", JSONObject().put("enabled", enabled).put("wan", wan))
        return parseUpnp(root.optJSONObject("data") ?: JSONObject())
    }

    suspend fun firewall(force: Boolean = false): FirewallState {
        val data = get("/api/router/firewall${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject()
        return parseFirewall(data)
    }

    suspend fun addFirewallRule(rule: FirewallRule): FirewallState {
        val root = send("/api/router/firewall/rules", "POST", rule.toJson(includeUuid = false))
        return parseFirewall(root.optJSONObject("data") ?: JSONObject())
    }

    suspend fun updateFirewallRule(rule: FirewallRule): FirewallState {
        val root = send("/api/router/firewall/rules/${rule.uuid}", "PUT", rule.toJson(includeUuid = true))
        return parseFirewall(root.optJSONObject("data") ?: JSONObject())
    }

    suspend fun setFirewallEnabled(uuid: String, enabled: Boolean): FirewallState {
        val root = send("/api/router/firewall/rules/$uuid/enabled", "PATCH", JSONObject().put("enabled", enabled))
        return parseFirewall(root.optJSONObject("data") ?: JSONObject())
    }

    suspend fun deleteFirewallRule(uuid: String): FirewallState {
        val root = send("/api/router/firewall/rules/$uuid", "DELETE")
        return parseFirewall(root.optJSONObject("data") ?: JSONObject())
    }

    suspend fun reorderFirewall(scope: String, uuids: List<String>): FirewallState {
        val root = send("/api/router/firewall/reorder", "POST", JSONObject().put("scope", scope).put("uuids", JSONArray(uuids)))
        return parseFirewall(root.optJSONObject("data") ?: JSONObject())
    }

    suspend fun ddns(force: Boolean = false): List<DdnsRecord> {
        val data = get("/api/router/ddns${if (force) "?force=1" else ""}").optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseDdns) }
    }

    suspend fun addDdns(record: DdnsRecord, password: String): List<DdnsRecord> {
        val root = send("/api/router/ddns", "POST", record.toJson(password))
        return parseDdnsWrite(root)
    }

    suspend fun updateDdns(record: DdnsRecord, password: String?): List<DdnsRecord> {
        val root = send("/api/router/ddns/${record.serviceId}", "PUT", record.toJson(password))
        return parseDdnsWrite(root)
    }

    suspend fun deleteDdns(serviceId: String): List<DdnsRecord> {
        val root = send("/api/router/ddns/$serviceId", "DELETE")
        return parseDdnsWrite(root)
    }

    private fun parseDdnsWrite(root: JSONObject): List<DdnsRecord> {
        val data = root.optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseDdns) }
    }

    suspend fun diagnostic(): RouterDiagnostic {
        val data = get("/api/router/diagnostic").optJSONObject("data") ?: JSONObject()
        return parseDiagnostic(data)
    }

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

data class RouterLoginConfig(
    val address: String = "",
    val passwordConfigured: Boolean = false,
    val sessionSeconds: Int = 3600,
    val sessionActive: Boolean = false,
    val serialNumber: String = ""
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

private fun parseNativePortRule(o: JSONObject) = NativePortMapRule(
    ruleName = cleanApiText(o.optString("ruleName")),
    src = cleanApiText(o.optString("src", "wan")),
    srcIp = cleanApiText(o.optString("srcIp")),
    srcPort = cleanApiText(o.optString("srcPort")),
    destIp = cleanApiText(o.optString("destIp")),
    destPort = cleanApiText(o.optString("destPort")),
    proto = cleanApiText(o.optString("proto", "tcp"))
)

data class UpnpMapping(
    val name: String,
    val clientIp: String,
    val protocol: String,
    val internalPort: String,
    val externalPort: String
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

data class FirewallStats(val packets: Long = 0, val bytes: Long = 0)

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
        put("srcPort", if (proto in setOf("tcp", "udp")) srcPort.trim() else "")
        put("destPort", if (proto in setOf("tcp", "udp")) destPort.trim() else "")
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
    val order: Map<String, List<String>> = emptyMap(),
    val maxRules: Int = 20,
    val defaultPolicy: String = "RETURN"
)

private fun parseFirewall(o: JSONObject): FirewallState {
    val arr = o.optJSONArray("list") ?: JSONArray()
    val rules = (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { r ->
            val s = r.optJSONObject("stats") ?: JSONObject()
            FirewallRule(
                uuid = cleanApiText(r.optString("uuid")),
                ruleName = cleanApiText(r.optString("ruleName")),
                direction = cleanApiText(r.optString("direction", "forward")),
                ipVersion = cleanApiText(r.optString("ipVersion", "ipv4")),
                proto = cleanApiText(r.optString("proto", "tcp")),
                srcIP = cleanApiText(r.optString("srcIP")),
                destIP = cleanApiText(r.optString("destIP")),
                srcPort = cleanApiText(r.optString("srcPort")),
                destPort = cleanApiText(r.optString("destPort")),
                target = cleanApiText(r.optString("target", "ACCEPT")),
                enabled = r.optString("enable", "1") == "1",
                ipv6SuffixSrc = cleanApiText(r.optString("ipv6SuffixSrc")),
                ipv6SuffixDest = cleanApiText(r.optString("ipv6SuffixDest")),
                inIface = cleanApiText(r.optString("inIface")),
                outIface = cleanApiText(r.optString("outIface")),
                stats = FirewallStats(s.optLong("packets"), s.optLong("bytes"))
            )
        }
    }
    val orderObject = o.optJSONObject("order") ?: JSONObject()
    val order = orderObject.keys().asSequence().associateWith { key ->
        val values = orderObject.optJSONArray(key) ?: JSONArray()
        (0 until values.length()).map { values.optString(it) }
    }
    return FirewallState(rules, order, o.optInt("maxLen", 20), cleanApiText(o.optString("defaultPolicy", "RETURN")))
}

data class DdnsRecord(
    val serviceId: String = "",
    val provider: String = "aliyun.com",
    val domain: String = "",
    val username: String = "",
    val useIpv6: Boolean = true,
    val enabled: Boolean = true,
    val interfaceName: String = "wan",
    val status: String = "",
    val ip: String = "",
    val passwordConfigured: Boolean = false
) {
    fun toJson(password: String?): JSONObject = JSONObject().apply {
        if (serviceId.isNotBlank()) put("service", serviceId)
        put("service_name", provider)
        put("domain", domain.trim())
        put("username", username.trim())
        if (password != null) put("password", password)
        put("interface", interfaceName)
        put("use_ipv6", if (useIpv6) "1" else "0")
        put("enabled", if (enabled) "1" else "0")
    }
}

private fun parseDdns(o: JSONObject) = DdnsRecord(
    serviceId = cleanApiText(o.optString("service")),
    provider = cleanApiText(o.optString("service_name")),
    domain = cleanApiText(o.optString("domain")),
    username = cleanApiText(o.optString("username")),
    useIpv6 = o.optString("use_ipv6", "0") == "1",
    enabled = o.optString("enabled", "1") == "1",
    interfaceName = cleanApiText(o.optString("interface", "wan")),
    status = cleanApiText(o.optString("status")),
    ip = cleanApiText(o.optString("ip")),
    passwordConfigured = o.optBoolean("passwordConfigured")
)

data class DiagnosticItem(
    val type: String,
    val status: String,
    val title: String,
    val result: String,
    val tips: String,
    val advise: String,
    val port: String
)

data class RouterDiagnostic(
    val progress: String = "0%",
    val errorCount: Int = 0,
    val startTime: String = "",
    val items: List<DiagnosticItem> = emptyList()
)

private fun parseDiagnostic(o: JSONObject): RouterDiagnostic {
    val groups = o.optJSONArray("list") ?: JSONArray()
    val rows = mutableListOf<DiagnosticItem>()
    for (i in 0 until groups.length()) {
        val group = groups.optJSONObject(i) ?: continue
        val children = group.optJSONArray("list") ?: JSONArray()
        for (j in 0 until children.length()) {
            val child = children.optJSONObject(j) ?: continue
            rows += DiagnosticItem(
                type = cleanApiText(group.optString("type")),
                status = cleanApiText(child.optString("status", group.optString("status"))),
                title = cleanApiText(child.optString("item")),
                result = cleanApiText(child.optString("result")),
                tips = cleanApiText(child.optString("tips")),
                advise = cleanApiText(child.optString("advise")).replace("<br>", "\n", true),
                port = cleanApiText(child.optJSONObject("data")?.optString("port"))
            )
        }
    }
    return RouterDiagnostic(
        progress = cleanApiText(o.optString("process", "0%")),
        errorCount = o.optString("error_count", "0").toIntOrNull() ?: 0,
        startTime = cleanApiText(o.optString("start_time")),
        items = rows
    )
}
