package com.labprobe.app.feature.router.firewall

import com.labprobe.app.AppPrefs
import com.labprobe.app.HubApi
import com.labprobe.app.PortMapApi
import com.labprobe.app.RouterControlApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FirewallAutomationBinding(
    val firewallUuid: String = "",
    val enabled: Boolean = true,
    val targetType: String = "mapping",
    val mappingKind: String = "relay",
    val mappingId: String = "",
    val addressFamily: String = "ipv4",
    val matchField: String = "destIP",
    val targetName: String = "",
    val ruleName: String = "",
    val direction: String = "",
    val currentAddress: String = "",
    val desiredAddress: String = "",
    val status: String = "",
    val statusMessage: String = "",
    val suspended: Boolean = false,
    val suspendedReason: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("targetType", "mapping")
        .put("mappingKind", mappingKind)
        .put("mappingId", mappingId)
        .put("addressFamily", addressFamily)
        .put("matchField", matchField)
}

data class FirewallAutomationResource(
    val bindings: List<FirewallAutomationBinding> = emptyList(),
    val refreshing: Boolean = false,
    val mutating: Boolean = false,
    val error: String = "",
)

data class FirewallAutomationTargets(
    val mappings: List<FirewallMappingTarget> = emptyList(),
)

data class FirewallMappingTarget(
    val kind: String,
    val id: String,
    val name: String,
    val addressFamily: String,
    val modeText: String,
    val protocol: String,
    val externalPort: String,
    val targetPort: String,
)

private fun parseBinding(root: JSONObject): FirewallAutomationBinding = FirewallAutomationBinding(
    firewallUuid = root.optString("firewallUuid").trim(),
    enabled = root.optBoolean("enabled", true),
    targetType = root.optString("targetType", "mapping").trim().lowercase(),
    mappingKind = root.optString("mappingKind", "relay").trim().lowercase(),
    mappingId = root.optString("mappingId").trim(),
    addressFamily = root.optString("addressFamily", "ipv4").trim().lowercase(),
    matchField = root.optString("matchField", "destIP").trim(),
    targetName = root.optString("targetName").trim(),
    ruleName = root.optString("ruleName").trim(),
    direction = root.optString("direction").trim(),
    currentAddress = root.optString("currentAddress").trim(),
    desiredAddress = root.optString("desiredAddress").trim(),
    status = root.optString("status").trim().lowercase(),
    statusMessage = root.optString("statusMessage").trim(),
    suspended = root.optBoolean("suspended", false),
    suspendedReason = root.optString("suspendedReason").trim(),
)

private class FirewallAutomationApi(prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)

    suspend fun list(): List<FirewallAutomationBinding> = withContext(Dispatchers.IO) {
        val data = hubApi.requestJson("/api/router/firewall/automation").optJSONObject("data") ?: JSONObject()
        val rows = data.optJSONArray("bindings") ?: JSONArray()
        (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.let(::parseBinding) }
    }

    suspend fun put(binding: FirewallAutomationBinding): FirewallAutomationBinding = withContext(Dispatchers.IO) {
        val data = hubApi.requestJson(
            "/api/router/firewall/automation/${binding.firewallUuid}",
            "PUT",
            binding.toJson(),
        ).optJSONObject("data") ?: JSONObject()
        parseBinding(data)
    }

    suspend fun remove(firewallUuid: String) = withContext(Dispatchers.IO) {
        hubApi.requestJson("/api/router/firewall/automation/$firewallUuid", "DELETE", JSONObject())
    }

    suspend fun sync(firewallUuid: String): FirewallAutomationBinding = withContext(Dispatchers.IO) {
        val data = hubApi.requestJson(
            "/api/router/firewall/automation/$firewallUuid/sync",
            "POST",
            JSONObject(),
        ).optJSONObject("data") ?: JSONObject()
        parseBinding(data)
    }
}

/** Screen-scoped state only; Hub and the router remain the persistent authorities. */
class FirewallAutomationRepository(private val prefs: AppPrefs) {
    private val api = FirewallAutomationApi(prefs)
    private val portMapApi = PortMapApi(prefs)
    private val routerApi = RouterControlApi(prefs)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(FirewallAutomationResource())
    val state: StateFlow<FirewallAutomationResource> = _state.asStateFlow()

    suspend fun refresh() {
        if (_state.value.mutating) return
        _state.value = _state.value.copy(refreshing = true, error = "")
        runCatching { api.list() }
            .onSuccess { _state.value = FirewallAutomationResource(bindings = it) }
            .onFailure { error ->
                _state.value = _state.value.copy(
                    refreshing = false,
                    error = error.message ?: "自动跟随状态读取失败",
                )
            }
    }

    suspend fun save(binding: FirewallAutomationBinding): Result<FirewallAutomationBinding> = mutex.withLock {
        _state.value = _state.value.copy(mutating = true, error = "")
        runCatching { api.put(binding) }
            .onSuccess { saved ->
                val rows = _state.value.bindings
                    .filterNot { it.firewallUuid == saved.firewallUuid }
                    .plus(saved)
                _state.value = FirewallAutomationResource(bindings = rows)
            }
            .onFailure { error ->
                _state.value = _state.value.copy(
                    mutating = false,
                    error = error.message ?: "自动跟随保存失败",
                )
            }
    }

    suspend fun remove(firewallUuid: String): Result<Unit> = mutex.withLock {
        _state.value = _state.value.copy(mutating = true, error = "")
        runCatching {
            api.remove(firewallUuid)
            Unit
        }.onSuccess {
            _state.value = FirewallAutomationResource(
                bindings = _state.value.bindings.filterNot { it.firewallUuid == firewallUuid },
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                mutating = false,
                error = error.message ?: "停止自动跟随失败",
            )
        }
    }

    suspend fun sync(firewallUuid: String): Result<FirewallAutomationBinding> = mutex.withLock {
        _state.value = _state.value.copy(mutating = true, error = "")
        runCatching { api.sync(firewallUuid) }
            .onSuccess { synced ->
                val rows = _state.value.bindings
                    .filterNot { it.firewallUuid == synced.firewallUuid }
                    .plus(synced)
                _state.value = FirewallAutomationResource(bindings = rows)
            }
            .onFailure { error ->
                _state.value = _state.value.copy(
                    mutating = false,
                    error = error.message ?: "立即核对失败",
                )
            }
    }

    suspend fun loadTargets(): FirewallAutomationTargets = coroutineScope {
        val relay = async { runCatching { portMapApi.list().rules }.getOrDefault(emptyList()) }
        val native = async { runCatching { routerApi.nativePortMappings(false) }.getOrDefault(emptyList()) }
        val relayRows = relay.await().map { rule ->
            FirewallMappingTarget(
                kind = "relay",
                id = rule.id,
                name = rule.name,
                addressFamily = if (rule.mode == "6to4") "ipv4" else "ipv6",
                modeText = "IPv6 映射 · ${rule.modeText}",
                protocol = rule.transportProtocol,
                externalPort = rule.listenPort.toString(),
                targetPort = rule.targetPort.toString(),
            )
        }
        val nativeRows = native.await().map { rule ->
            FirewallMappingTarget(
                kind = "native",
                id = rule.ruleName,
                name = rule.ruleName,
                addressFamily = "ipv4",
                modeText = "路由器端口映射",
                protocol = rule.proto.uppercase(),
                externalPort = rule.srcPort,
                targetPort = rule.destPort,
            )
        }
        FirewallAutomationTargets((relayRows + nativeRows).sortedWith(compareBy<FirewallMappingTarget> { it.kind }.thenBy { it.name.lowercase() }))
    }
}
