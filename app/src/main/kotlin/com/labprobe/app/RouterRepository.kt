package com.labprobe.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Single APP-side source of truth for router-control data.
 *
 * Pages only render these memory snapshots. Reads are coalesced, preload is
 * sequential, refresh failures preserve the last successful value, and older
 * reads are forbidden from overwriting a newer user mutation.
 */
data class RouterResource<T>(
    val value: T? = null,
    val updatedAt: Long = 0L,
    val refreshing: Boolean = false,
    val mutating: Boolean = false,
    val stale: Boolean = false,
    val error: String = "",
    val generation: Long = 0L,
) {
    val hasValue: Boolean get() = value != null
    val initialLoading: Boolean get() = value == null && refreshing
}

/** A polling response may only publish while no later DDNS mutation owns the snapshot. */
internal fun isCurrentLabProbeDdnsGeneration(expected: Long, current: Long): Boolean = expected == current

class RouterRepository internal constructor(private val prefs: AppPrefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = RouterControlApi(prefs)
    private val preloadStarted = AtomicBoolean(false)
    private val fallbackScheduled = AtomicBoolean(false)
    private val lastReconnectRefreshAt = AtomicLong(0L)
    private val preloadMutex = Mutex()
    private val mutationMutex = Mutex()
    private val labProbeMutationMutex = Mutex()
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Any?>>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val configRevisions = ConcurrentHashMap<String, AtomicLong>()

    private val _status = MutableStateFlow(
        RouterResource(value = RouterSlowDataCache.hubStatus, updatedAt = RouterSlowDataCache.hubStatusAt)
    )
    val status: StateFlow<RouterResource<RouterHubStatus>> = _status.asStateFlow()

    private val _capabilities = MutableStateFlow(
        RouterResource(value = RouterSlowDataCache.capabilities, updatedAt = RouterSlowDataCache.capabilitiesAt)
    )
    val capabilities: StateFlow<RouterResource<RouterCapabilities>> = _capabilities.asStateFlow()

    private val _ddns = MutableStateFlow(
        RouterResource(
            value = RouterSlowDataCache.ddnsRows.takeIf { RouterSlowDataCache.ddnsLoaded },
            updatedAt = RouterSlowDataCache.ddnsAt,
        )
    )
    val ddns: StateFlow<RouterResource<List<DdnsRecord>>> = _ddns.asStateFlow()

    private val _labProbeDdns = MutableStateFlow(RouterResource<LabProbeDdnsSnapshot>())
    val labProbeDdns: StateFlow<RouterResource<LabProbeDdnsSnapshot>> = _labProbeDdns.asStateFlow()

    private val _upnp = MutableStateFlow(
        RouterResource(value = RouterSlowDataCache.upnpState, updatedAt = RouterSlowDataCache.upnpAt)
    )
    val upnp: StateFlow<RouterResource<UpnpState>> = _upnp.asStateFlow()

    private val _portMappings = MutableStateFlow(
        RouterResource(
            value = RouterSlowDataCache.portMappings.takeIf { RouterSlowDataCache.portMappingsLoaded },
            updatedAt = RouterSlowDataCache.portMappingsAt,
        )
    )
    val portMappings: StateFlow<RouterResource<List<NativePortMapRule>>> = _portMappings.asStateFlow()

    private val _firewall = MutableStateFlow(
        RouterResource(value = RouterSlowDataCache.firewallState, updatedAt = RouterSlowDataCache.firewallAt)
    )
    val firewall: StateFlow<RouterResource<FirewallState>> = _firewall.asStateFlow()

    fun acceptConfigRealtime(raw: String) {
        scope.launch {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            val resource = root.optString("resource")
            val data = root.optJSONObject("data") ?: return@launch
            val source = root.optString("source", "sync")
            val revision = root.optLong("revision", 0L)
            val frameAt = root.optLong("updatedAt", 0L).let { if (it > 0L) it * 1000L else System.currentTimeMillis() }
            val revisionKey = when (resource) {
                "portMappings" -> "portMappings"
                "firewall", "ddns", "upnp" -> resource
                else -> return@launch
            }
            val pendingCommand = when (resource) {
                "ddns" -> _ddns.value.mutating
                "upnp" -> _upnp.value.mutating
                "portMappings" -> _portMappings.value.mutating
                "firewall" -> _firewall.value.mutating
                else -> false
            }
            val seen = configRevisions.getOrPut(revisionKey) { AtomicLong(0L) }
            if (revision > 0L && revision <= seen.get() && !(source == "command" && pendingCommand)) return@launch
            when (resource) {
                "ddns" -> {
                    val old = _ddns.value
                    if (old.mutating && source != "command") return@launch
                    if (source != "command" && old.updatedAt > frameAt) return@launch
                    val seq = sequence("ddns").incrementAndGet()
                    applyDdnsRead(seq, parseDdnsList(data))
                }
                "upnp" -> {
                    val old = _upnp.value
                    if (old.mutating && source != "command") return@launch
                    if (source != "command" && old.updatedAt > frameAt) return@launch
                    val seq = sequence("upnp").incrementAndGet()
                    applyUpnp(seq, parseUpnp(data))
                }
                "portMappings" -> {
                    val old = _portMappings.value
                    if (old.mutating && source != "command") return@launch
                    if (source != "command" && old.updatedAt > frameAt) return@launch
                    val seq = sequence("portMappings").incrementAndGet()
                    applyPortMappings(seq, parseNativePortRules(data))
                }
                "firewall" -> {
                    val old = _firewall.value
                    if (old.mutating && source != "command") return@launch
                    if (source != "command" && old.updatedAt > frameAt) return@launch
                    val seq = sequence("firewall").incrementAndGet()
                    applyFirewall(seq, parseFirewall(data))
                }
            }
            if (revision > 0L) seen.updateAndGet { previous -> maxOf(previous, revision) }
        }
    }

    fun start() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        if (!fallbackScheduled.compareAndSet(false, true)) return
        scope.launch {
            // WSS ready is the preferred trigger. This fallback covers Hub versions
            // that cannot deliver the ready event, without exposing a reconnect loop.
            delay(3_000L)
            beginInitialPreload()
        }
    }

    fun onRealtimeReady(reconnect: Boolean) {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        if (!preloadStarted.get()) {
            beginInitialPreload()
            return
        }
        if (!reconnect) return
        val now = System.currentTimeMillis()
        val previous = lastReconnectRefreshAt.get()
        if (now - previous < 15_000L || !lastReconnectRefreshAt.compareAndSet(previous, now)) return
        scope.launch {
            // Reconnection refreshes only lightweight/visible essentials. It never
            // fans out all slow settings and never touches the realtime WSS collector.
            preloadMutex.withLock {
                refreshStatus(force = true)
                refreshCapabilities()
                refreshDdns()
            }
        }
    }

    private fun beginInitialPreload() {
        if (!preloadStarted.compareAndSet(false, true)) return
        scope.launch {
            preload()
            if (_status.value.value == null || _ddns.value.value == null) {
                delay(5_000L)
                preloadMissing()
            }
        }
    }

    private suspend fun preload() = preloadMutex.withLock {
        // Hub-local/lightweight reads first, then homepage DDNS, then the remaining
        // router-control resources one by one. Never fan out slow router RPC reads.
        refreshStatus()
        refreshCapabilities()
        refreshDdns()
        delay(80L)
        refreshUpnp()
        delay(80L)
        refreshPortMappings()
        delay(80L)
        refreshFirewall()
    }

    private suspend fun preloadMissing() = preloadMutex.withLock {
        if (_status.value.value == null) refreshStatus(force = true)
        if (_capabilities.value.value == null) refreshCapabilities(force = true)
        if (_ddns.value.value == null) refreshDdns(force = true)
        if (_upnp.value.value == null) refreshUpnp(force = true)
        if (_portMappings.value.value == null) refreshPortMappings(force = true)
        if (_firewall.value.value == null) refreshFirewall(force = true)
    }

    private fun sequence(key: String): AtomicLong = generations.getOrPut(key) { AtomicLong(0L) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> coalesced(key: String, timeoutMs: Long = 7_000L, block: suspend () -> T): T {
        val task = inFlightMutex.withLock {
            (inFlight[key] as? Deferred<T>) ?: scope.async {
                withTimeout(timeoutMs) { block() }
            }.also { inFlight[key] = it as Deferred<Any?> }
        }
        return try {
            task.await()
        } finally {
            inFlightMutex.withLock {
                if (inFlight[key] === task) inFlight.remove(key)
            }
        }
    }

    private fun message(error: Throwable, fallback: String): String = when {
        error.message.isNullOrBlank() -> fallback
        error.message!!.contains("timeout", true) || error.message!!.contains("timed out", true) -> "后台同步较慢，已保留上次数据"
        else -> error.message!!
    }

    private suspend fun <T> executeCommand(block: suspend () -> T): Result<T> = try {
        Result.success(withTimeout(45_000L) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    suspend fun refreshStatus(force: Boolean = false) {
        val key = "status"
        val seq = sequence(key).incrementAndGet()
        val old = _status.value
        if (!force && old.value != null && RouterSlowDataCache.isFresh(old.updatedAt, RouterSlowDataCache.STATUS_TTL_MS)) return
        _status.value = old.copy(refreshing = true, error = "", generation = seq)
        runCatching { coalesced(key) { api.hubStatus() } }
            .onSuccess { latest ->
                if (sequence(key).get() != seq) return@onSuccess
                val now = System.currentTimeMillis()
                RouterSlowDataCache.hubStatus = latest
                RouterSlowDataCache.hubStatusAt = now
                _status.value = RouterResource(latest, now, generation = seq)
            }
            .onFailure { failure ->
                if (sequence(key).get() != seq) return@onFailure
                _status.value = old.copy(refreshing = false, stale = old.value != null, error = message(failure, "Hub 状态同步失败"), generation = seq)
            }
    }

    suspend fun refreshCapabilities(force: Boolean = false) {
        val key = "capabilities"
        val seq = sequence(key).incrementAndGet()
        val old = _capabilities.value
        if (!force && old.value != null && RouterSlowDataCache.isFresh(old.updatedAt, RouterSlowDataCache.SETTINGS_TTL_MS)) return
        _capabilities.value = old.copy(refreshing = true, error = "", generation = seq)
        runCatching { coalesced(key) { api.capabilities() } }
            .onSuccess { latest ->
                if (sequence(key).get() != seq) return@onSuccess
                val now = System.currentTimeMillis()
                RouterSlowDataCache.capabilities = latest
                RouterSlowDataCache.capabilitiesAt = now
                _capabilities.value = RouterResource(latest, now, generation = seq)
            }
            .onFailure { failure ->
                if (sequence(key).get() != seq) return@onFailure
                _capabilities.value = old.copy(refreshing = false, stale = old.value != null, error = message(failure, "功能能力同步失败"), generation = seq)
            }
    }

    suspend fun refreshDdns(force: Boolean = false) {
        val key = "ddns"
        if (_ddns.value.mutating) return
        val seq = sequence(key).incrementAndGet()
        val old = _ddns.value
        if (!force && old.value != null && RouterSlowDataCache.isFresh(old.updatedAt, RouterSlowDataCache.SETTINGS_TTL_MS)) return
        _ddns.value = old.copy(refreshing = true, error = "", generation = seq)
        runCatching { coalesced(key) { api.ddns(force) } }
            .onSuccess { latest -> applyDdnsRead(seq, latest) }
            .onFailure { failure -> if (sequence(key).get() == seq) _ddns.value = old.copy(refreshing = false, stale = old.value != null, error = message(failure, "DDNS 同步失败"), generation = seq) }
    }

    suspend fun refreshLabProbeDdns(force: Boolean = false) {
        val key = "labProbeDdns"
        val old = _labProbeDdns.value
        if (old.mutating && !force) return
        val seq = sequence(key).incrementAndGet()
        if (!force && old.value != null && RouterSlowDataCache.isFresh(old.updatedAt, RouterSlowDataCache.SETTINGS_TTL_MS)) return
        _labProbeDdns.value = old.copy(refreshing = true, error = "", generation = seq)
        runCatching { coalesced(key) { api.labProbeDdns(force) } }
            .onSuccess { latest ->
                if (sequence(key).get() != seq) return@onSuccess
                _labProbeDdns.value = RouterResource(latest, System.currentTimeMillis(), generation = seq)
            }
            .onFailure { failure ->
                if (sequence(key).get() == seq) {
                    _labProbeDdns.value = old.copy(refreshing = false, stale = old.value != null, error = message(failure, "LabProbe DDNS 同步失败"), generation = seq)
                }
            }
    }

    suspend fun refreshLabProbeDdnsAddress(): Result<Unit> = executeCommand {
        val key = "labProbeDdns"
        val seq = sequence(key).incrementAndGet()
        val before = _labProbeDdns.value.value?.address?.detectedAt ?: 0L
        api.refreshLabProbeDdnsAddress()
        var observed = false
        for (attempt in 0 until 8) {
            delay(1_000L)
            val latest = api.labProbeDdns(true)
            val applied = labProbeMutationMutex.withLock {
                if (!isCurrentLabProbeDdnsGeneration(seq, sequence(key).get())) {
                    false
                } else {
                    _labProbeDdns.value = RouterResource(latest, System.currentTimeMillis(), generation = seq)
                    true
                }
            }
            // A later add/update/delete/update-now owns the snapshot. Its result
            // must remain visible instead of being replaced by this old poll.
            if (!applied) return@executeCommand
            if (latest.address.detectedAt > before || latest.records.any { it.lastDetectedAt > before }) {
                observed = true
                break
            }
        }
        if (!observed) throw IllegalStateException("已请求刷新，正在等待 Relay 上报新的检测结果")
    }.map { Unit }

    private fun applyDdnsRead(seq: Long, latest: List<DdnsRecord>) {
        if (sequence("ddns").get() != seq) return
        val now = System.currentTimeMillis()
        RouterSlowDataCache.ddnsRows = latest
        RouterSlowDataCache.ddnsLoaded = true
        RouterSlowDataCache.ddnsAt = now
        _ddns.value = RouterResource(latest, now, generation = seq)
    }

    suspend fun refreshUpnp(force: Boolean = false) {
        val key = "upnp"
        if (_upnp.value.mutating) return
        val seq = sequence(key).incrementAndGet()
        val old = _upnp.value
        if (!force && old.value != null && RouterSlowDataCache.isFresh(old.updatedAt, RouterSlowDataCache.MAPPING_TTL_MS)) return
        _upnp.value = old.copy(refreshing = true, error = "", generation = seq)
        runCatching { coalesced(key) { api.upnp(force) } }
            .onSuccess { latest -> applyUpnp(seq, latest) }
            .onFailure { failure -> if (sequence(key).get() == seq) _upnp.value = old.copy(refreshing = false, stale = old.value != null, error = message(failure, "UPnP 同步失败"), generation = seq) }
    }

    private fun applyUpnp(seq: Long, latest: UpnpState) {
        if (sequence("upnp").get() != seq) return
        val now = System.currentTimeMillis()
        RouterSlowDataCache.upnpState = latest
        RouterSlowDataCache.upnpAt = now
        _upnp.value = RouterResource(latest, now, generation = seq)
    }

    suspend fun refreshPortMappings(force: Boolean = false) {
        val key = "portMappings"
        if (_portMappings.value.mutating) return
        val seq = sequence(key).incrementAndGet()
        val old = _portMappings.value
        if (!force && old.value != null && RouterSlowDataCache.isFresh(old.updatedAt, RouterSlowDataCache.MAPPING_TTL_MS)) return
        _portMappings.value = old.copy(refreshing = true, error = "", generation = seq)
        runCatching { coalesced(key) { api.nativePortMappings(force) } }
            .onSuccess { latest -> applyPortMappings(seq, latest) }
            .onFailure { failure -> if (sequence(key).get() == seq) _portMappings.value = old.copy(refreshing = false, stale = old.value != null, error = message(failure, "端口映射同步失败"), generation = seq) }
    }

    private fun applyPortMappings(seq: Long, latest: List<NativePortMapRule>) {
        if (sequence("portMappings").get() != seq) return
        val now = System.currentTimeMillis()
        RouterSlowDataCache.portMappings = latest
        RouterSlowDataCache.portMappingsLoaded = true
        RouterSlowDataCache.portMappingsAt = now
        _portMappings.value = RouterResource(latest, now, generation = seq)
    }

    suspend fun refreshFirewall(force: Boolean = false) {
        val key = "firewall"
        if (_firewall.value.mutating) return
        val seq = sequence(key).incrementAndGet()
        val old = _firewall.value
        if (!force && old.value != null && RouterSlowDataCache.isFresh(old.updatedAt, RouterSlowDataCache.SETTINGS_TTL_MS)) return
        _firewall.value = old.copy(refreshing = true, error = "", generation = seq)
        runCatching { coalesced(key) { api.firewall(force) } }
            .onSuccess { latest -> applyFirewall(seq, latest) }
            .onFailure { failure -> if (sequence(key).get() == seq) _firewall.value = old.copy(refreshing = false, stale = old.value != null, error = message(failure, "防火墙同步失败"), generation = seq) }
    }

    private fun applyFirewall(seq: Long, latest: FirewallState) {
        if (sequence("firewall").get() != seq) return
        val now = System.currentTimeMillis()
        RouterSlowDataCache.firewallState = latest
        RouterSlowDataCache.firewallAt = now
        _firewall.value = RouterResource(latest, now, generation = seq)
    }

    suspend fun setUpnp(enabled: Boolean, wan: String): Result<UpnpState> = mutationMutex.withLock {
        val key = "upnp"
        val seq = sequence(key).incrementAndGet()
        val old = _upnp.value
        _upnp.value = old.copy(mutating = true, error = "", generation = seq)
        executeCommand { api.setUpnp(enabled, wan) }
            .onSuccess { applyUpnp(seq, it) }
            .onFailure { failure ->
                if (sequence(key).get() == seq) {
                    _upnp.value = old.copy(mutating = false, error = message(failure, "UPnP 设置失败"), generation = seq)
                }
            }
    }

    suspend fun addPortMapping(rule: NativePortMapRule): Result<List<NativePortMapRule>> = mutatePorts { api.addNativePortMapping(rule) }
    suspend fun updatePortMapping(oldName: String, rule: NativePortMapRule): Result<List<NativePortMapRule>> = mutatePorts { api.updateNativePortMapping(oldName, rule) }
    suspend fun deletePortMapping(name: String): Result<List<NativePortMapRule>> = mutatePorts { api.deleteNativePortMapping(name) }

    private suspend fun mutatePorts(block: suspend () -> List<NativePortMapRule>): Result<List<NativePortMapRule>> = mutationMutex.withLock {
        val key = "portMappings"
        val seq = sequence(key).incrementAndGet()
        val old = _portMappings.value
        _portMappings.value = old.copy(mutating = true, error = "", generation = seq)
        executeCommand { block() }
            .onSuccess { applyPortMappings(seq, it) }
            .onFailure { failure ->
                if (sequence(key).get() == seq) {
                    _portMappings.value = old.copy(mutating = false, error = message(failure, "端口映射设置失败"), generation = seq)
                }
            }
    }

    suspend fun addDdns(record: DdnsRecord, password: String): Result<List<DdnsRecord>> = mutateDdns { api.addDdns(record, password) }
    suspend fun updateDdns(record: DdnsRecord, password: String?): Result<List<DdnsRecord>> = mutateDdns { api.updateDdns(record, password) }
    suspend fun deleteDdns(serviceId: String): Result<List<DdnsRecord>> = mutateDdns { api.deleteDdns(serviceId) }

    private suspend fun mutateDdns(block: suspend () -> List<DdnsRecord>): Result<List<DdnsRecord>> = mutationMutex.withLock {
        val key = "ddns"
        val seq = sequence(key).incrementAndGet()
        val old = _ddns.value
        _ddns.value = old.copy(mutating = true, error = "", generation = seq)
        executeCommand { block() }
            .onSuccess { applyDdnsRead(seq, it) }
            .onFailure { failure ->
                if (sequence(key).get() == seq) {
                    _ddns.value = old.copy(mutating = false, error = message(failure, "DDNS 设置失败"), generation = seq)
                }
            }
    }

    suspend fun addLabProbeDdns(record: LabProbeDdnsRecord, credentials: Map<String, String>): Result<LabProbeDdnsSnapshot> =
        mutateLabProbe { api.addLabProbeDdns(record, credentials); api.labProbeDdns(true) }

    suspend fun updateLabProbeDdns(record: LabProbeDdnsRecord, credentials: Map<String, String>): Result<LabProbeDdnsSnapshot> =
        mutateLabProbe { api.updateLabProbeDdns(record, credentials); api.labProbeDdns(true) }

    suspend fun deleteLabProbeDdns(recordId: String): Result<LabProbeDdnsSnapshot> =
        mutateLabProbe { api.deleteLabProbeDdns(recordId); api.labProbeDdns(true) }

    suspend fun updateLabProbeDdnsNow(recordId: String): Result<Unit> = labProbeMutationMutex.withLock {
        val key = "labProbeDdns"
        val seq = sequence(key).incrementAndGet()
        val old = _labProbeDdns.value
        _labProbeDdns.value = old.copy(mutating = true, error = "", generation = seq)
        val result = executeCommand { api.updateLabProbeDdnsNow(recordId) }
        if (result.isSuccess) {
            val response = result.getOrThrow()
            // Refresh even on a Provider error: Hub has persisted the redacted
            // record-level status and lastError, which is more useful than a
            // generic router-control failure.
            refreshLabProbeDdns(force = true)
            if (response.optBoolean("ok", false)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(api.labProbeUpdateError(response)))
            }
        } else {
            val failure = result.exceptionOrNull()
            if (sequence(key).get() == seq) {
                _labProbeDdns.value = old.copy(mutating = false, error = message(failure ?: RuntimeException(), "LabProbe DDNS 更新失败"), generation = seq)
            }
            Result.failure<Unit>(failure ?: RuntimeException("LabProbe DDNS 更新失败"))
        }
    }

    private suspend fun mutateLabProbe(block: suspend () -> LabProbeDdnsSnapshot): Result<LabProbeDdnsSnapshot> = labProbeMutationMutex.withLock {
        val key = "labProbeDdns"
        val seq = sequence(key).incrementAndGet()
        val old = _labProbeDdns.value
        _labProbeDdns.value = old.copy(mutating = true, error = "", generation = seq)
        executeCommand { block() }
            .onSuccess { latest ->
                if (sequence(key).get() == seq) _labProbeDdns.value = RouterResource(latest, System.currentTimeMillis(), generation = seq)
            }
            .onFailure { failure ->
                if (sequence(key).get() == seq) {
                    _labProbeDdns.value = old.copy(mutating = false, error = message(failure, "LabProbe DDNS 设置失败"), generation = seq)
                }
            }
    }

    suspend fun addFirewallRule(rule: FirewallRule): Result<FirewallState> = mutateFirewall { api.addFirewallRule(rule) }
    suspend fun updateFirewallRule(rule: FirewallRule): Result<FirewallState> = mutateFirewall { api.updateFirewallRule(rule) }
    suspend fun setFirewallEnabled(uuid: String, enabled: Boolean): Result<FirewallState> = mutateFirewall { api.setFirewallEnabled(uuid, enabled) }
    suspend fun deleteFirewallRule(uuid: String): Result<FirewallState> = mutateFirewall { api.deleteFirewallRule(uuid) }
    suspend fun reorderFirewall(scope: String, uuids: List<String>): Result<FirewallState> = mutateFirewall { api.reorderFirewall(scope, uuids) }

    private suspend fun mutateFirewall(block: suspend () -> FirewallState): Result<FirewallState> = mutationMutex.withLock {
        val key = "firewall"
        val seq = sequence(key).incrementAndGet()
        val old = _firewall.value
        _firewall.value = old.copy(mutating = true, error = "", generation = seq)
        executeCommand { block() }
            .onSuccess { applyFirewall(seq, it) }
            .onFailure { failure ->
                if (sequence(key).get() == seq) {
                    _firewall.value = old.copy(mutating = false, error = message(failure, "防火墙设置失败"), generation = seq)
                }
            }
    }

    fun close() {
        scope.cancel()
        commandScope.cancel()
    }

}

object RouterRepositoryRegistry {
    @Volatile private var key: String = ""
    @Volatile private var instance: RouterRepository? = null

    @Synchronized
    fun get(prefs: AppPrefs): RouterRepository {
        RouterSlowDataCache.ensureScope(prefs.hub, prefs.token)
        val next = prefs.hub.trim().trimEnd('/') + "|" + prefs.token.hashCode() + "|" + prefs.hubDns.trim()
        if (instance == null || key != next) {
            val previous = instance
            key = next
            instance = RouterRepository(prefs)
            previous?.close()
        }
        return instance!!.also(RouterRepository::start)
    }
}
