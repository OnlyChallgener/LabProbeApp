package com.labprobe.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * A profile has exactly one endpoint authority. DDNS profiles always retain a
 * hostname, while STUN profiles accept only the Agent's latest IP:port. This
 * makes it impossible for the two automatic updaters to overwrite one config.
 */
enum class WireGuardEndpointSource(val wireValue: String, val displayName: String) {
    MANUAL("manual", "我的手动配置"),
    DDNS("ddns", "DDNS 固定端口"),
    STUN("stun", "STUN 动态地址");

    companion object {
        fun fromWireValue(value: String): WireGuardEndpointSource =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() } ?: DDNS
    }
}

data class WireGuardProfile(
    val id: String,
    val name: String,
    val endpointSource: WireGuardEndpointSource,
    /** DDNS hostname or current STUN IPv4/IPv6 host; never combines host and port. */
    val endpointHost: String,
    val endpointPort: Int = DEFAULT_WIREGUARD_PORT,
    /** Null migrates legacy DDNS conservatively using the previous gateway port. */
    val followsServerPort: Boolean? = null,
    val mtu: Int = DEFAULT_WIREGUARD_MTU,
    val interfaceAddresses: List<String> = listOf("10.77.0.2/32"),
    val dnsServers: List<String> = emptyList(),
    val serverPublicKey: String = "",
    /** MVP deliberately routes only the selected home LAN, never all Internet traffic. */
    val allowedIps: List<String> = listOf("192.168.1.0/24"),
    val persistentKeepalive: Int = DEFAULT_WIREGUARD_KEEPALIVE,
    /** Optional Hub record / STUN rule identity. It is metadata, not an endpoint. */
    val endpointBindingId: String = "",
    /** User-authored config revision. Endpoint refreshes must never alter it. */
    val profileRevision: Long = 1L,
    /** Agent/DDNS endpoint event revision. It is intentionally independent from profileRevision. */
    val endpointRevision: Long = 0L,
    val endpointUpdatedAt: Long = 0L,
    val endpointUpdateError: String = "",
) {
    val endpoint: String get() = formatWireGuardEndpoint(endpointHost, endpointPort)
    val isComplete: Boolean get() = endpointHost.isNotBlank() && serverPublicKey.isNotBlank() && interfaceAddresses.isNotEmpty() &&
        allowedIps.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("endpointSource", endpointSource.wireValue)
        put("endpointHost", endpointHost)
        put("endpointPort", endpointPort)
        followsServerPort?.let { put("followsServerPort", it) }
        put("mtu", mtu)
        put("interfaceAddresses", JSONArray(interfaceAddresses))
        put("dnsServers", JSONArray(dnsServers))
        put("serverPublicKey", serverPublicKey)
        put("allowedIps", JSONArray(allowedIps))
        put("persistentKeepalive", persistentKeepalive)
        put("endpointBindingId", endpointBindingId)
        put("profileRevision", profileRevision)
        put("endpointRevision", endpointRevision)
        put("endpointUpdatedAt", endpointUpdatedAt)
        put("endpointUpdateError", endpointUpdateError)
    }

    companion object {
        fun fromJson(value: JSONObject): WireGuardProfile? {
            val id = value.optString("id").trim().takeIf { it.isNotBlank() } ?: return null
            return WireGuardProfile(
                id = id,
                name = value.optString("name").trim().ifBlank { "WireGuard" },
                endpointSource = WireGuardEndpointSource.fromWireValue(value.optString("endpointSource")),
                endpointHost = value.optString("endpointHost").trim(),
                endpointPort = value.optInt("endpointPort", DEFAULT_WIREGUARD_PORT).coerceIn(1, 65535),
                followsServerPort = value.takeIf { it.has("followsServerPort") && !it.isNull("followsServerPort") }
                    ?.optBoolean("followsServerPort"),
                mtu = value.optInt("mtu", DEFAULT_WIREGUARD_MTU).coerceIn(1280, 1500),
                interfaceAddresses = jsonStringList(value.optJSONArray("interfaceAddresses")).ifEmpty { listOf("10.77.0.2/32") },
                dnsServers = jsonStringList(value.optJSONArray("dnsServers")),
                serverPublicKey = value.optString("serverPublicKey").trim(),
                allowedIps = jsonStringList(value.optJSONArray("allowedIps")).ifEmpty { listOf("192.168.1.0/24") },
                persistentKeepalive = value.optInt("persistentKeepalive", DEFAULT_WIREGUARD_KEEPALIVE).coerceIn(0, 65535),
                endpointBindingId = value.optString("endpointBindingId").trim(),
                profileRevision = value.optLong("profileRevision", 1L).coerceAtLeast(1L),
                endpointRevision = value.optLong("endpointRevision", 0L).coerceAtLeast(0L),
                endpointUpdatedAt = value.optLong("endpointUpdatedAt", 0L).coerceAtLeast(0L),
                endpointUpdateError = value.optString("endpointUpdateError").trim(),
            )
        }

        fun newProfile(source: WireGuardEndpointSource): WireGuardProfile = WireGuardProfile(
            id = "wg-${source.wireValue}-${UUID.randomUUID().toString().take(8)}",
            name = when (source) {
                WireGuardEndpointSource.MANUAL -> "我的 WireGuard"
                WireGuardEndpointSource.DDNS -> "家庭 WireGuard（DDNS）"
                WireGuardEndpointSource.STUN -> "家庭 WireGuard（STUN）"
            },
            endpointSource = source,
            followsServerPort = source == WireGuardEndpointSource.DDNS,
            endpointHost = "",
            interfaceAddresses = when (source) {
                WireGuardEndpointSource.MANUAL -> listOf("10.66.0.2/32")
                WireGuardEndpointSource.DDNS -> listOf("10.77.0.2/32")
                WireGuardEndpointSource.STUN -> listOf("10.77.0.3/32")
            },
        )
    }
}

const val DEFAULT_WIREGUARD_PORT = 51820
const val DEFAULT_WIREGUARD_MTU = 1420
const val DEFAULT_WIREGUARD_KEEPALIVE = 25

internal fun followsWireGuardServerPort(profile: WireGuardProfile, previousListenPort: Int): Boolean =
    profile.endpointSource == WireGuardEndpointSource.DDNS &&
        (profile.followsServerPort ?: (profile.endpointPort == previousListenPort))

internal fun applyWireGuardServerConfig(
    profile: WireGuardProfile,
    listenPort: Int,
    mtu: Int,
    previousListenPort: Int,
): WireGuardProfile = when (profile.endpointSource) {
    WireGuardEndpointSource.MANUAL -> profile
    WireGuardEndpointSource.DDNS -> {
        val follows = followsWireGuardServerPort(profile, previousListenPort)
        profile.copy(endpointPort = if (follows) listenPort else profile.endpointPort, mtu = mtu, followsServerPort = follows)
    }
    WireGuardEndpointSource.STUN -> profile.copy(mtu = mtu)
}

internal fun editedWireGuardProfile(old: WireGuardProfile?, edited: WireGuardProfile): WireGuardProfile {
    val rebind = old != null && (old.endpointSource != edited.endpointSource ||
        (edited.endpointSource == WireGuardEndpointSource.STUN && old.endpointBindingId != edited.endpointBindingId))
    val preserveStunEndpoint = old != null && !rebind && edited.endpointSource == WireGuardEndpointSource.STUN
    return edited.copy(
        profileRevision = (old?.profileRevision ?: 0L) + 1L,
        endpointRevision = if (rebind) 0L else old?.endpointRevision ?: edited.endpointRevision,
        endpointUpdatedAt = if (rebind) 0L else if (preserveStunEndpoint) old!!.endpointUpdatedAt else edited.endpointUpdatedAt,
        endpointHost = when {
            rebind && edited.endpointSource == WireGuardEndpointSource.STUN -> ""
            preserveStunEndpoint -> old!!.endpointHost
            else -> edited.endpointHost
        },
        endpointPort = if (preserveStunEndpoint) old!!.endpointPort else edited.endpointPort,
        endpointUpdateError = when {
            rebind && edited.endpointSource == WireGuardEndpointSource.STUN -> "正在确认新绑定的 STUN 地址"
            preserveStunEndpoint -> old!!.endpointUpdateError
            else -> edited.endpointUpdateError
        },
    )
}

private val wireGuardProfileStoreLock = Any()

private fun jsonStringList(array: JSONArray?): List<String> = buildList {
    if (array == null) return@buildList
    for (index in 0 until array.length()) {
        array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
    }
}.distinct()

/** Splits Agent STUN addresses such as 203.0.113.8:51820 and [2001:db8::1]:51820. */
internal fun parseWireGuardEndpoint(value: String, defaultPort: Int = DEFAULT_WIREGUARD_PORT): Pair<String, Int>? {
    val raw = value.trim()
    if (raw.isBlank()) return null
    if (raw.startsWith("[")) {
        val closing = raw.indexOf(']')
        if (closing <= 1) return null
        val host = raw.substring(1, closing).trim()
        val port = raw.removePrefix(raw.substring(0, closing + 1)).removePrefix(":").toIntOrNull() ?: defaultPort
        return host.takeIf { it.isNotBlank() }?.let { it to port.coerceIn(1, 65535) }
    }
    val lastColon = raw.lastIndexOf(':')
    if (lastColon > 0 && raw.indexOf(':') == lastColon) {
        val port = raw.substring(lastColon + 1).toIntOrNull()
        if (port != null) return raw.substring(0, lastColon).trim().takeIf { it.isNotBlank() }?.let { it to port.coerceIn(1, 65535) }
    }
    return raw to defaultPort.coerceIn(1, 65535)
}

internal fun formatWireGuardEndpoint(host: String, port: Int): String {
    val normalized = host.trim().removePrefix("[").removeSuffix("]")
    if (normalized.isBlank()) return ""
    return if (normalized.contains(':')) "[$normalized]:${port.coerceIn(1, 65535)}" else "$normalized:${port.coerceIn(1, 65535)}"
}

internal fun canApplyWireGuardEndpointUpdate(
    profile: WireGuardProfile,
    source: WireGuardEndpointSource,
    incomingEndpointRevision: Long,
): Boolean = source != WireGuardEndpointSource.MANUAL && profile.endpointSource == source &&
    (incomingEndpointRevision <= 0L || incomingEndpointRevision > profile.endpointRevision)

internal fun wireGuardProfileError(profile: WireGuardProfile, privateKey: String): String = when {
    profile.endpointHost.isBlank() -> "请填写 ${profile.endpointSource.displayName} 地址"
    profile.serverPublicKey.isBlank() -> "请填写 Agent 服务端公钥"
    profile.interfaceAddresses.isEmpty() -> "请填写客户端隧道地址"
    profile.allowedIps.isEmpty() -> "请选择或填写路由网段"
    privateKey.isBlank() -> "客户端私钥不可用，请重新创建配置"
    else -> ""
}


/** Metadata lives in AppPrefs; only private keys use SecureWireGuardKeyStore. */
class WireGuardProfileStore(context: Context, private val prefs: AppPrefs) {
    private val keyStore = SecureWireGuardKeyStore(context.applicationContext)

    fun load(): List<WireGuardProfile> = runCatching {
        val array = JSONArray(prefs.wireGuardProfilesJson)
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(WireGuardProfile::fromJson)?.let(::add)
        }
    }.getOrDefault(emptyList()).distinctBy { it.id }

    fun save(profiles: List<WireGuardProfile>) {
        prefs.wireGuardProfilesJson = JSONArray().apply { profiles.distinctBy { it.id }.forEach { put(it.toJson()) } }.toString()
        if (profiles.none { it.id == prefs.wireGuardActiveProfileId }) prefs.wireGuardActiveProfileId = ""
    }

    fun privateKey(profileId: String): String = keyStore.get(profileId)

    fun create(profile: WireGuardProfile): WireGuardProfile {
        val privateKey = KeyPair().privateKey.toBase64()
        synchronized(wireGuardProfileStoreLock) {
            require(load().none { it.id == profile.id }) { "配置已存在，请编辑现有配置" }
            keyStore.put(profile.id, privateKey)
            save(load() + profile)
        }
        return profile
    }

    /** Explicit user edit: bump config revision but retain endpoint event ordering. */
    fun saveProfileEdit(profile: WireGuardProfile) {
        synchronized(wireGuardProfileStoreLock) {
            val old = load().firstOrNull { it.id == profile.id }
            require(old != null) { "配置已被删除，请刷新后重新新增" }
            val revised = editedWireGuardProfile(old, profile)
            save(load().filterNot { it.id == profile.id } + revised)
        }
    }

    fun delete(profileId: String) {
        synchronized(wireGuardProfileStoreLock) {
            save(load().filterNot { it.id == profileId })
            keyStore.remove(profileId)
        }
    }

    /**
     * The caller must label every event. A DDNS event can only touch a DDNS
     * profile and a STUN event can only touch a STUN profile, preventing the
     * endpoint collision the product explicitly avoids.
     */
    fun applyEndpointUpdate(
        profileId: String,
        source: WireGuardEndpointSource,
        endpoint: String,
        endpointRevision: Long = 0L,
        now: Long = System.currentTimeMillis(),
        expectedBindingId: String? = null,
    ): WireGuardProfile? {
        synchronized(wireGuardProfileStoreLock) {
            val existing = load().firstOrNull { it.id == profileId } ?: return null
            if (expectedBindingId != null && existing.endpointBindingId != expectedBindingId) return existing
            if (!canApplyWireGuardEndpointUpdate(existing, source, endpointRevision)) {
                // A recovered mapping can have the same address/revision as before the outage.
                if (source != WireGuardEndpointSource.MANUAL && existing.endpointSource == source &&
                    endpointRevision == existing.endpointRevision && endpoint == existing.endpoint && existing.endpointUpdateError.isNotBlank()) {
                    val recovered = existing.copy(endpointUpdateError = "")
                    save(load().map { if (it.id == existing.id) recovered else it })
                    return recovered
                }
                return if (source != WireGuardEndpointSource.MANUAL && existing.endpointSource == source) existing else null
            }
            val parsed = parseWireGuardEndpoint(endpoint, existing.endpointPort) ?: return null
            val updated = existing.copy(
                endpointHost = parsed.first,
                endpointPort = parsed.second,
                endpointRevision = maxOf(existing.endpointRevision, endpointRevision),
                endpointUpdatedAt = now,
                endpointUpdateError = "",
            )
            // Do not route this through saveProfileEdit: endpoint events must not
            // invalidate an in-flight profile edit by changing profileRevision.
            save(load().filterNot { it.id == updated.id } + updated)
            return updated
        }
    }

    fun markEndpointError(profileId: String, source: WireGuardEndpointSource, message: String, expectedBindingId: String? = null): WireGuardProfile? {
        synchronized(wireGuardProfileStoreLock) {
            val existing = load().firstOrNull { it.id == profileId && it.endpointSource == source } ?: return null
            if (expectedBindingId != null && existing.endpointBindingId != expectedBindingId) return existing
            val updated = existing.copy(endpointUpdateError = message.trim())
            save(load().filterNot { it.id == updated.id } + updated)
            return updated
        }
    }

    /** Agent provisioning is runtime metadata, not a user edit. */
    fun applyProvisioningResult(profileId: String, serverPublicKey: String, endpointBindingId: String = ""): WireGuardProfile? {
        synchronized(wireGuardProfileStoreLock) {
            val existing = load().firstOrNull { it.id == profileId } ?: return null
            if (existing.endpointSource == WireGuardEndpointSource.MANUAL) return existing
            val updated = existing.copy(
                serverPublicKey = serverPublicKey.trim().ifBlank { existing.serverPublicKey },
                endpointBindingId = endpointBindingId.trim().ifBlank { existing.endpointBindingId },
                endpointUpdateError = "",
            )
            save(load().filterNot { it.id == updated.id } + updated)
            return updated
        }
    }

    fun applyServerConfig(listenPort: Int, mtu: Int, previousListenPort: Int? = null) {
        synchronized(wireGuardProfileStoreLock) {
            save(load().map { applyWireGuardServerConfig(it, listenPort, mtu, previousListenPort ?: listenPort) })
        }
    }

    fun rememberServerPortAuthority(previousListenPort: Int) {
        synchronized(wireGuardProfileStoreLock) {
            save(load().map { profile ->
                if (profile.endpointSource == WireGuardEndpointSource.DDNS && profile.followsServerPort == null)
                    profile.copy(followsServerPort = followsWireGuardServerPort(profile, previousListenPort)) else profile
            })
        }
    }

    fun applyProvisionedProfile(profileId: String, provisioned: WireGuardProfile): WireGuardProfile? {
        synchronized(wireGuardProfileStoreLock) {
            val existing = load().firstOrNull { it.id == profileId } ?: return null
            if (existing.endpointSource == WireGuardEndpointSource.MANUAL || existing.endpointSource != provisioned.endpointSource) return existing
            val updated = existing.copy(
                serverPublicKey = provisioned.serverPublicKey,
                endpointBindingId = provisioned.endpointBindingId,
                endpointHost = if (existing.endpointSource == WireGuardEndpointSource.DDNS) provisioned.endpointHost else existing.endpointHost,
                endpointPort = if (existing.endpointSource == WireGuardEndpointSource.DDNS) provisioned.endpointPort else existing.endpointPort,
                followsServerPort = provisioned.followsServerPort,
                mtu = provisioned.mtu,
                endpointUpdateError = if (existing.endpointSource == WireGuardEndpointSource.STUN) existing.endpointUpdateError else "",
            )
            save(load().filterNot { it.id == profileId } + updated)
            return updated
        }
    }
}

internal fun wireGuardPublicKey(privateKey: String): String = runCatching {
    KeyPair(Key.fromBase64(privateKey.trim())).publicKey.toBase64()
}.getOrDefault("")

internal fun wireGuardQuickConfig(profile: WireGuardProfile, privateKey: String): String {
    val safe = privateKey.trim()
    require(wireGuardProfileError(profile, safe).isBlank()) { "WireGuard 配置不完整" }
    fun lines(values: List<String>) = values.map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
    val autoSubnet = profile.interfaceAddresses.firstOrNull()?.split('/')?.firstOrNull()?.let { ip ->
        val parts = ip.split('.')
        if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0/24" else null
    }
    val effectiveAllowedIps = (profile.allowedIps + listOfNotNull(autoSubnet, "10.77.0.0/24")).distinct()
    return buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $safe")
        appendLine("Address = ${lines(profile.interfaceAddresses)}")
        appendLine("MTU = ${profile.mtu.coerceIn(1280, 1500)}")
        profile.dnsServers.takeIf { it.isNotEmpty() }?.let { appendLine("DNS = ${lines(it)}") }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${profile.serverPublicKey.trim()}")
        appendLine("AllowedIPs = ${lines(effectiveAllowedIps)}")
        appendLine("Endpoint = ${profile.endpoint}")
        if (profile.persistentKeepalive > 0) appendLine("PersistentKeepalive = ${profile.persistentKeepalive}")
    }
}



private class LabProbeTunnel(private val profileId: String) : Tunnel {
    @Volatile var state: Tunnel.State = Tunnel.State.DOWN
    override fun getName(): String = ("lpwg-" + profileId.takeLast(9)).take(Tunnel.NAME_MAX_LENGTH)
    override fun onStateChange(newState: Tunnel.State) { state = newState }
}

data class WireGuardRuntimeStatus(
    val profileId: String = "",
    val running: Boolean = false,
    val receivedBytes: Long = 0L,
    val sentBytes: Long = 0L,
    val latestHandshakeAt: Long = 0L,
    val lastError: String = "",
)

sealed interface WireGuardStartResult {
    data object Started : WireGuardStartResult
    data class PermissionRequired(val intent: Intent) : WireGuardStartResult
    data class Failed(val message: String) : WireGuardStartResult
}

/** Thin adapter around the official backend; no packet processing occurs in App code. */
class WireGuardTunnelController private constructor(context: Context, private val prefs: AppPrefs) {
    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private var tunnel: LabProbeTunnel? = null
    private var tunnelProfileId: String = ""
    @Volatile private var lastError: String = ""

    suspend fun start(profile: WireGuardProfile, privateKey: String): WireGuardStartResult = withContext(Dispatchers.IO) {
        val validation = wireGuardProfileError(profile, privateKey)
        if (validation.isNotBlank()) return@withContext WireGuardStartResult.Failed(validation)
        val permission = VpnService.prepare(appContext)
        if (permission != null) return@withContext WireGuardStartResult.PermissionRequired(permission)
        runCatching {
            val config = Config.parse(ByteArrayInputStream(wireGuardQuickConfig(profile, privateKey).toByteArray(Charsets.UTF_8)))
            val next = if (tunnelProfileId == profile.id) tunnel ?: LabProbeTunnel(profile.id) else LabProbeTunnel(profile.id)
            backend.setState(next, Tunnel.State.UP, config)
            tunnel = next
            tunnelProfileId = profile.id
            prefs.wireGuardActiveProfileId = profile.id
            lastError = ""
        }.fold(
            onSuccess = { WireGuardStartResult.Started },
            onFailure = { error -> lastError = error.message ?: "WireGuard 启动失败"; WireGuardStartResult.Failed(lastError) }
        )
    }

    suspend fun stop(): WireGuardRuntimeStatus = withContext(Dispatchers.IO) {
        runCatching { tunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) } }
            .onFailure { lastError = it.message ?: "WireGuard 停止失败" }
        prefs.wireGuardActiveProfileId = ""
        status()
    }

    suspend fun status(): WireGuardRuntimeStatus = withContext(Dispatchers.IO) {
        val current = tunnel ?: return@withContext WireGuardRuntimeStatus(lastError = lastError)
        runCatching {
            val running = backend.getState(current) == Tunnel.State.UP
            val stats: Statistics = backend.getStatistics(current)
            val latestHandshake = stats.peers().maxOfOrNull { key -> stats.peer(key)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
            WireGuardRuntimeStatus(
                profileId = tunnelProfileId,
                running = running,
                receivedBytes = stats.totalRx(),
                sentBytes = stats.totalTx(),
                latestHandshakeAt = latestHandshake,
                lastError = lastError,
            )
        }.getOrElse { error ->
            lastError = error.message ?: "无法读取 WireGuard 状态"
            WireGuardRuntimeStatus(profileId = tunnelProfileId, lastError = lastError)
        }
    }

    companion object {
        @Volatile private var instance: WireGuardTunnelController? = null
        fun get(context: Context, prefs: AppPrefs): WireGuardTunnelController = instance ?: synchronized(this) {
            instance ?: WireGuardTunnelController(context, prefs).also { instance = it }
        }
    }
}

/**
 * Source-specific automatic endpoint updates. Callers feed this coordinator
 * data they already retrieved for their own page; it does not add a polling
 * loop or make DDNS and STUN depend on one another.
 */
internal object WireGuardEndpointCoordinator {
    fun applyDdnsSnapshot(
        store: WireGuardProfileStore,
        profiles: List<WireGuardProfile>,
        managedRecords: List<LabProbeDdnsRecord>,
        nativeRecords: List<DdnsRecord>,
    ): List<WireGuardProfile> = profiles.map { profile ->
        if (profile.endpointSource != WireGuardEndpointSource.DDNS) return@map profile
        val managed = managedRecords.firstOrNull {
            it.enabled && (it.id == profile.endpointBindingId || it.hostname.equals(profile.endpointHost, ignoreCase = true))
        }
        val native = nativeRecords.firstOrNull {
            it.enabled && (it.serviceId == profile.endpointBindingId || it.domain.equals(profile.endpointHost, ignoreCase = true))
        }
        val hostname = managed?.hostname ?: native?.domain ?: profile.endpointHost
        if (hostname.isBlank()) return@map profile
        // Keep a hostname for DDNS. Never replace it with a transient A record.
        store.applyEndpointUpdate(
            profile.id,
            WireGuardEndpointSource.DDNS,
            hostname,
            endpointRevision = maxOf(managed?.lastUpdatedAt ?: 0L, profile.endpointRevision),
            expectedBindingId = profile.endpointBindingId,
        ) ?: profile
    }

    fun applyStunSnapshot(
        store: WireGuardProfileStore,
        profiles: List<WireGuardProfile>,
        rules: List<StunRule>,
        listenPort: Int,
        routerIp: String,
    ): List<WireGuardProfile> = profiles.map { profile ->
        if (profile.endpointSource != WireGuardEndpointSource.STUN) return@map profile
        val rule = boundWireGuardStunRule(profile, rules)
        if (rule?.ready != true || rule.runtime.publicEndpoint.isBlank() ||
            rule.targetPort != listenPort || !isWireGuardStunTarget(rule, routerIp)) {
            // Preserve the last working endpoint, so a temporary STUN probe
            // failure cannot turn a working profile into an empty one.
            val error = when {
                rule == null -> "绑定的 STUN 规则不存在，请重新选择；旧地址不代表可用"
                rule.targetPort != listenPort -> "穿透目标端口尚未同步至网关 $listenPort，旧地址仅供参考"
                !isWireGuardStunTarget(rule, routerIp) -> "穿透目标并非当前网关，旧地址不可用于连接"
                else -> "STUN 尚未就绪，旧地址仅供参考"
            }
            return@map store.markEndpointError(profile.id, WireGuardEndpointSource.STUN, error, profile.endpointBindingId) ?: profile
        }
        store.applyEndpointUpdate(
            profile.id,
            WireGuardEndpointSource.STUN,
            rule.runtime.publicEndpoint,
            endpointRevision = rule.runtime.mappingUpdatedAt ?: 0L,
            expectedBindingId = profile.endpointBindingId,
        ) ?: profile
    }
}

internal fun boundWireGuardStunRule(profile: WireGuardProfile, rules: List<StunRule>): StunRule? =
    if (profile.endpointSource != WireGuardEndpointSource.STUN || profile.endpointBindingId.isBlank()) null
    else rules.firstOrNull { it.id == profile.endpointBindingId && it.transportProtocol == "UDP" }

data class WireGuardProvisionResult(
    val profile: WireGuardProfile,
    val desiredRevision: Long,
    val appliedRevision: Long,
    val message: String,
)

data class WireGuardStunServerReference(
    val ruleId: String,
    val endpointProfileId: String,
    val peerId: String,
)

data class WireGuardStunDependencySnapshot(
    val references: List<WireGuardStunServerReference>,
) {
    fun forRule(ruleId: String): List<WireGuardStunServerReference> =
        references.filter { it.ruleId == ruleId.trim() }

    fun forEndpointProfile(endpointProfileId: String): WireGuardStunServerReference? =
        references.firstOrNull { it.endpointProfileId == endpointProfileId.trim() }
}

data class WireGuardBindingCleanupResult(
    val ruleId: String,
    val removedEndpointProfileIds: List<String>,
    val removedPeerIds: List<String>,
    val appliedRevision: Long,
)

data class WireGuardProfileTransitionResult(
    val profile: WireGuardProfile,
    val removedEndpointProfileIds: List<String>,
    val removedPeerIds: List<String>,
    val appliedRevision: Long,
)

sealed interface WireGuardRemoteMutationResult<out T> {
    data class Applied<T>(val value: T) : WireGuardRemoteMutationResult<T>
    data class PendingVerification(
        val submittedRevision: Long?,
        val message: String,
    ) : WireGuardRemoteMutationResult<Nothing>
    data class NotSubmitted(val message: String) : WireGuardRemoteMutationResult<Nothing>
}

class WireGuardPendingVerificationException(
    val submittedRevision: Long?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal data class WireGuardServerRemovalPlan(
    val payload: JSONObject,
    val removedEndpointProfileIds: List<String>,
    val removedPeerIds: List<String>,
)

internal enum class WireGuardMutationStage { READ_BEFORE_SUBMIT, SUBMIT, WAIT_AGENT }

internal fun isWireGuardSubmissionUncertain(error: Throwable): Boolean =
    error is IOException || (error is HubHttpException && (error.statusCode == 408 || error.statusCode >= 500))

internal fun wireGuardMutationFailureMessage(stage: WireGuardMutationStage, error: Throwable): String {
    val reason = error.message?.trim().orEmpty().ifBlank { "上游服务暂不可用" }
    return when (stage) {
        WireGuardMutationStage.READ_BEFORE_SUBMIT -> "读取 WireGuard 配置失败，尚未提交：$reason"
        WireGuardMutationStage.SUBMIT -> if (isWireGuardSubmissionUncertain(error)) {
            "提交 WireGuard 修改时 Hub 未确认接收；请先刷新核对，不要重复操作：$reason"
        } else {
            "Hub 拒绝 WireGuard 修改，未更改：$reason"
        }
        WireGuardMutationStage.WAIT_AGENT -> "Hub 已接受 WireGuard 修改，但等待 Agent 应用回执失败；请刷新核对，不要重复提交：$reason"
    }
}

internal fun wireGuardPeerId(profileId: String): String =
    ("app-" + profileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }).take(48)

internal fun wireGuardEndpointProfileId(profileId: String): String =
    ("app-" + profileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }).take(48)

internal fun wireGuardServerPublicKey(root: JSONObject): String =
    root.optJSONObject("agentStatus")?.optJSONObject("applyResult")?.optString("publicKey").orEmpty().trim()

private val wireGuardServerReadOnlyFields = setOf(
    "revision", "agentStatus", "runtime", "status", "applyResult", "capability",
    "serverPublicKey", "publicKey", "createdAt", "updatedAt", "lastAppliedAt",
)

/** Keeps forward-compatible configuration fields but never echoes known runtime/read-only state into PUT. */
internal fun copyWireGuardServerForMutation(server: JSONObject): JSONObject = JSONObject().apply {
    val copy = JSONObject(server.toString())
    val keys = copy.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key !in wireGuardServerReadOnlyFields) put(key, copy.get(key))
    }
}

internal fun buildWireGuardServerPayload(
    root: JSONObject,
    profile: WireGuardProfile,
    clientPublicKey: String,
    replaceMatchingPublicKey: Boolean = true,
): JSONObject {
    require(profile.endpointSource != WireGuardEndpointSource.MANUAL) { "手动配置不会上传 Hub" }
    require(clientPublicKey.isNotBlank()) { "客户端公钥不可用" }
    require(profile.interfaceAddresses.isNotEmpty()) { "客户端隧道地址不能为空" }
    if (profile.endpointSource == WireGuardEndpointSource.DDNS) require(profile.endpointHost.isNotBlank()) { "请填写 DDNS 域名" }
    if (profile.endpointSource == WireGuardEndpointSource.STUN) require(profile.endpointBindingId.isNotBlank()) { "请先绑定 WireGuard UDP 穿透" }

    val existing = root.optJSONObject("server") ?: JSONObject()
    val listenPort = existing.optInt("listenPort", DEFAULT_WIREGUARD_PORT).takeIf { it in 1..65535 } ?: DEFAULT_WIREGUARD_PORT
    val peerId = wireGuardPeerId(profile.id)
    val endpointProfileId = wireGuardEndpointProfileId(profile.id)
    val peers = JSONArray()
    val oldPeers = existing.optJSONArray("peers") ?: JSONArray()
    for (index in 0 until oldPeers.length()) {
        val row = oldPeers.optJSONObject(index) ?: continue
        if (row.optString("id") != peerId && (!replaceMatchingPublicKey || row.optString("publicKey") != clientPublicKey)) {
            peers.put(JSONObject(row.toString()))
        }
    }
    peers.put(JSONObject().apply {
        put("id", peerId)
        put("name", profile.name.take(64))
        put("publicKey", clientPublicKey)
        put("allowedIps", JSONArray(profile.interfaceAddresses))
        put("persistentKeepaliveSeconds", profile.persistentKeepalive.coerceIn(0, 600))
    })

    val endpointProfiles = JSONArray()
    val oldProfiles = existing.optJSONArray("endpointProfiles") ?: JSONArray()
    var previousEndpointRevision = 0L
    var previousResolvedEndpoint = ""
    for (index in 0 until oldProfiles.length()) {
        val row = oldProfiles.optJSONObject(index) ?: continue
        if (row.optString("id") == endpointProfileId) {
            previousEndpointRevision = row.optLong("endpointRevision", 0L)
            previousResolvedEndpoint = row.optString("resolvedEndpoint")
        } else {
            endpointProfiles.put(JSONObject(row.toString()))
        }
    }
    endpointProfiles.put(JSONObject().apply {
        put("id", endpointProfileId)
        put("name", profile.name.take(64))
        put("endpointSource", profile.endpointSource.wireValue)
        put("enabled", true)
        put("port", if (profile.endpointSource == WireGuardEndpointSource.DDNS &&
            !followsWireGuardServerPort(profile, listenPort)) profile.endpointPort else listenPort)
        put("endpointRevision", previousEndpointRevision)
        put("resolvedEndpoint", profile.endpoint.ifBlank { previousResolvedEndpoint })
        when (profile.endpointSource) {
            WireGuardEndpointSource.DDNS -> put("hostname", profile.endpointHost.trim().lowercase())
            WireGuardEndpointSource.STUN -> put("stunRuleId", profile.endpointBindingId.trim())
            WireGuardEndpointSource.MANUAL -> Unit
        }
    })

    return copyWireGuardServerForMutation(JSONObject(existing.toString())).apply {
        put("expectedRevision", root.optLong("revision", 0L))
        put("interfaceName", existing.optString("interfaceName", "labwg0").ifBlank { "labwg0" })
        put("address", existing.optString("address", "10.77.0.1/24").ifBlank { "10.77.0.1/24" })
        put("listenPort", listenPort)
        put("enabled", existing.optBoolean("enabled", true))
        put("peers", peers)
        put("endpointProfiles", endpointProfiles)
    }
}

internal fun findRouterLanIpv4(vararg roots: JSONObject?): String {
    fun valid(value: String): String {
        val parts = value.trim().split('.')
        if (parts.size != 4 || parts.any { part -> part.toIntOrNull()?.let { it !in 0..255 } ?: true }) return ""
        return value.trim().takeIf {
            it.startsWith("10.") || it.startsWith("192.168.") ||
                (it.startsWith("172.") && (it.substringAfter("172.").substringBefore('.').toIntOrNull() ?: 0) in 16..31)
        }.orEmpty()
    }
    fun candidates(root: JSONObject?): List<String> {
        if (root == null) return emptyList()
        val router = root.optJSONObject("router")
        val data = root.optJSONObject("data")
        val details = root.optJSONObject("details") ?: data?.optJSONObject("details")
        val lan = root.optJSONObject("lan") ?: details?.optJSONObject("lan") ?: data?.optJSONObject("lan")
        return listOf(
            router?.optString("lanIp").orEmpty(),
            router?.optString("lan_ip").orEmpty(),
            lan?.optString("ipv4").orEmpty(),
            lan?.optString("ip").orEmpty(),
            details?.optString("managementIp").orEmpty(),
            data?.optString("lanIp").orEmpty(),
        )
    }
    return roots.asSequence().flatMap { candidates(it).asSequence() }.map(::valid).firstOrNull { it.isNotBlank() }.orEmpty()
}

data class WireGuardServerConfig(
    val listenPort: Int = DEFAULT_WIREGUARD_PORT,
    val mtu: Int = DEFAULT_WIREGUARD_MTU,
    val address: String = "10.77.0.1/24",
    val interfaceName: String = "labwg0",
    val serverPublicKey: String = "",
    val revision: Long = 0L,
    val enabled: Boolean = true,
)

data class WireGuardServerUpdateResult(
    val config: WireGuardServerConfig,
    val applied: Boolean,
    val warnings: List<String> = emptyList(),
    val pendingStunRuleIds: List<String> = emptyList(),
    val previousListenPort: Int = config.listenPort,
)

data class WireGuardServerState(
    val config: WireGuardServerConfig,
    val agentRevision: Long = 0L,
    val applyResultRevision: Long? = null,
    val applyResultOk: Boolean? = null,
    val applyResultEnabled: Boolean? = null,
    val applyResultInterfaceName: String = "",
    val applyResultControlBackend: String = "",
    val capabilityWgToolAvailable: Boolean? = null,
    val capabilityProvisioningReady: Boolean = false,
    val capabilityControlBackend: String = "",
    val capabilityRunning: Boolean = false,
    val interfaceRunning: Boolean? = null,
    val applyError: String = "",
    val capabilityError: String = "",
)

internal fun parseWireGuardServerState(root: JSONObject): WireGuardServerState {
    val server = root.optJSONObject("server") ?: throw IllegalStateException("WireGuard 网关配置不存在")
    val agentStatus = root.optJSONObject("agentStatus")
    val applyResult = agentStatus?.optJSONObject("applyResult")
    val capability = agentStatus?.optJSONObject("capability")
    val interfaceName = server.optString("interfaceName", "labwg0").ifBlank { "labwg0" }
    val interfaces = capability?.optJSONArray("interfaces")
    val matchingInterface = interfaces?.let { rows ->
        (0 until rows.length())
            .mapNotNull(rows::optJSONObject)
            .firstOrNull { it.optString("name") == interfaceName }
    }
    val serverPublicKey = agentStatus?.optString("publicKey").orEmpty().ifBlank {
        wireGuardServerPublicKey(root).ifBlank { server.optString("serverPublicKey") }
    }
    return WireGuardServerState(
        config = WireGuardServerConfig(
            listenPort = server.optInt("listenPort", DEFAULT_WIREGUARD_PORT),
            mtu = server.optInt("mtu", DEFAULT_WIREGUARD_MTU),
            address = server.optString("address", "10.77.0.1/24").ifBlank { "10.77.0.1/24" },
            interfaceName = interfaceName,
            serverPublicKey = serverPublicKey,
            revision = root.optLong("revision", 0L),
            enabled = server.optBoolean("enabled", true),
        ),
        agentRevision = agentStatus?.optLong("revision", 0L) ?: 0L,
        applyResultRevision = applyResult?.takeIf { it.has("revision") }?.optLong("revision"),
        applyResultOk = applyResult?.takeIf { it.has("ok") }?.optBoolean("ok"),
        applyResultEnabled = applyResult?.takeIf { it.has("enabled") }?.optBoolean("enabled"),
        applyResultInterfaceName = applyResult?.optNullableString("interfaceName").orEmpty(),
        applyResultControlBackend = applyResult?.optNullableString("controlBackend").orEmpty(),
        capabilityWgToolAvailable = capability?.takeIf { it.has("wgToolAvailable") }?.optBoolean("wgToolAvailable"),
        capabilityProvisioningReady = capability?.optBoolean("provisioningReady", false) ?: false,
        capabilityControlBackend = capability?.optNullableString("controlBackend").orEmpty(),
        capabilityRunning = capability?.optBoolean("running", false) ?: false,
        interfaceRunning = matchingInterface?.takeIf { it.has("running") }?.optBoolean("running"),
        applyError = applyResult?.optNullableString("error").orEmpty(),
        capabilityError = capability?.optNullableString("error").orEmpty(),
    )
}

private fun JSONObject.optNullableString(name: String): String =
    if (isNull(name)) "" else optString(name).trim()

internal fun buildWireGuardServerEnablePayload(root: JSONObject): JSONObject {
    val server = root.optJSONObject("server") ?: throw IllegalStateException("WireGuard 网关配置不存在")
    return copyWireGuardServerForMutation(server)
        .put("expectedRevision", root.optLong("revision", 0L))
        .put("enabled", true)
}

internal fun buildWireGuardServerSettingsPayload(
    root: JSONObject,
    listenPort: Int,
    mtu: Int,
    address: String,
    enabled: Boolean,
    profiles: List<WireGuardProfile> = emptyList(),
): JSONObject {
    require(listenPort in 1..65535) { "监听端口必须是 1–65535" }
    require(mtu in 1280..1500) { "MTU 必须是 1280–1500" }
    val server = root.optJSONObject("server") ?: throw IllegalStateException("无法读取网关配置，未提交修改")
    val oldPort = server.optInt("listenPort", DEFAULT_WIREGUARD_PORT)
    val payload = copyWireGuardServerForMutation(JSONObject(server.toString()))
        .put("expectedRevision", root.getLong("revision"))
        .put("listenPort", listenPort)
        .put("mtu", mtu)
        .put("address", address.trim().ifBlank { "10.77.0.1/24" })
        .put("enabled", enabled)
    val endpoints = payload.optJSONArray("endpointProfiles") ?: JSONArray()
    for (index in 0 until endpoints.length()) {
        val row = endpoints.optJSONObject(index) ?: continue
        val local = profiles.firstOrNull { wireGuardEndpointProfileId(it.id) == row.optString("id") }
        when (row.optString("endpointSource")) {
            "ddns" -> {
                val follows = local?.let { followsWireGuardServerPort(it, oldPort) }
                    ?: (row.optInt("port", oldPort) == oldPort)
                if (follows) {
                    row.put("port", listenPort)
                    val host = row.optString("hostname").trim()
                    if (host.isNotBlank()) row.put("resolvedEndpoint", formatWireGuardEndpoint(host, listenPort))
                }
            }
            // This is service metadata; the STUN public Endpoint is NOT listenPort.
            "stun" -> row.put("port", listenPort)
        }
    }

    return payload
}

internal fun wireGuardStunDependents(root: JSONObject, ruleId: String): List<String> {
    if (ruleId.isBlank()) return emptyList()
    val endpoints = root.optJSONObject("server")?.optJSONArray("endpointProfiles") ?: return emptyList()
    return (0 until endpoints.length()).mapNotNull { index ->
        val row = endpoints.optJSONObject(index) ?: return@mapNotNull null
        if (row.optString("endpointSource") != "stun" || row.optString("stunRuleId") != ruleId) null
        else row.optString("name").ifBlank { row.optString("id", "WireGuard 配置") }
    }.distinct()
}

internal fun parseWireGuardStunDependencySnapshot(root: JSONObject): WireGuardStunDependencySnapshot {
    val server = root.optJSONObject("server")
        ?: throw IllegalStateException("无法读取 WireGuard 网关配置")
    val endpoints = server.optJSONArray("endpointProfiles") ?: JSONArray()
    val references = buildList {
        for (index in 0 until endpoints.length()) {
            val row = endpoints.optJSONObject(index) ?: continue
            if (row.optString("endpointSource") != WireGuardEndpointSource.STUN.wireValue) continue
            val ruleId = row.optString("stunRuleId").trim()
            val endpointProfileId = row.optString("id").trim()
            if (ruleId.isBlank() || endpointProfileId.isBlank()) continue
            add(WireGuardStunServerReference(ruleId, endpointProfileId, endpointProfileId))
        }
    }
    return WireGuardStunDependencySnapshot(references.distinct())
}

internal fun wireGuardBoundStunIds(root: JSONObject, profiles: List<WireGuardProfile>): Set<String> {
    val endpoints = root.optJSONObject("server")?.optJSONArray("endpointProfiles") ?: JSONArray()
    return buildSet {
        for (index in 0 until endpoints.length()) {
            val row = endpoints.optJSONObject(index) ?: continue
            if (row.optString("endpointSource") == "stun") row.optString("stunRuleId").takeIf { it.isNotBlank() }?.let(::add)
        }
        profiles.filter { it.endpointSource == WireGuardEndpointSource.STUN }
            .map { it.endpointBindingId }.filter { it.isNotBlank() }.forEach(::add)
    }
}

internal fun isWireGuardStunTarget(rule: StunRule, routerIp: String): Boolean =
    rule.transportProtocol == "UDP" && rule.usesRouterNativeMapping &&
        rule.targetType != "router_self" && routerIp.isNotBlank() && rule.targetIpv4 == routerIp

internal fun isLegacyWireGuardRelayStunTarget(rule: StunRule, listenPort: Int): Boolean =
    rule.enabled && rule.serviceType.equals("WireGuard", ignoreCase = true) &&
        rule.transportProtocol == "UDP" && rule.targetPort == listenPort &&
        !rule.usesRouterNativeMapping && rule.targetType == "router_self" && rule.targetIpv4 == "127.0.0.1"

internal fun compatibleNewWireGuardStunRules(
    beforeIds: Set<String>,
    after: StunSnapshot,
    listenPort: Int,
    routerIp: String,
): List<StunRule> = if (!after.rulesLoaded) emptyList() else after.rules.filter {
    it.id !in beforeIds && it.enabled && it.serviceType.equals("WireGuard", ignoreCase = true) &&
        it.targetPort == listenPort && isWireGuardStunTarget(it, routerIp)
}

internal fun buildWireGuardProfileRemovalPayload(root: JSONObject, profile: WireGuardProfile): JSONObject {
    val server = root.optJSONObject("server") ?: throw IllegalStateException("无法读取网关配置，未删除")
    val id = wireGuardEndpointProfileId(profile.id)
    fun withoutOwnRows(name: String): JSONArray {
        val rows = server.optJSONArray(name) ?: JSONArray()
        return JSONArray().apply {
            for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { row ->
                // A shared STUN binding (or public key) never identifies ownership.
                if (row.optString("id") != id && row.optString("id") != profile.id) put(JSONObject(row.toString()))
            }
        }
    }
    return copyWireGuardServerForMutation(server)
        .put("expectedRevision", root.getLong("revision"))
        .put("peers", withoutOwnRows("peers"))
        .put("endpointProfiles", withoutOwnRows("endpointProfiles"))
}

internal fun isWireGuardServerConfigApplied(state: WireGuardServerState, expected: WireGuardServerConfig): Boolean {
    if (state.config.listenPort != expected.listenPort || state.config.mtu != expected.mtu ||
        state.config.address != expected.address || state.config.enabled != expected.enabled) return false
    if (state.agentRevision < expected.revision || state.applyResultRevision?.let { it >= expected.revision } != true ||
        state.applyResultOk != true || state.applyResultEnabled != expected.enabled || state.applyError.isNotBlank()) return false
    return if (expected.enabled) isWireGuardServerReady(state, expected.revision)
        else state.interfaceRunning != true
}

internal fun buildWireGuardRuleCleanupPlan(root: JSONObject, ruleId: String): WireGuardServerRemovalPlan {
    val exactRuleId = ruleId.trim()
    require(exactRuleId.isNotBlank()) { "必须明确指定 STUN 规则 ID" }
    val server = root.optJSONObject("server") ?: throw IllegalStateException("无法读取网关配置，未清理")
    val endpoints = server.optJSONArray("endpointProfiles") ?: JSONArray()
    val endpointIds = buildList {
        for (index in 0 until endpoints.length()) {
            val row = endpoints.optJSONObject(index) ?: continue
            if (row.optString("endpointSource") == WireGuardEndpointSource.STUN.wireValue &&
                row.optString("stunRuleId").trim() == exactRuleId) {
                val id = row.optString("id").trim()
                require(id.isNotBlank()) { "服务端存在无 ID 的绑定，无法安全清理" }
                add(id)
            }
        }
    }.distinct()
    val peerIds = buildList {
        val peers = server.optJSONArray("peers") ?: JSONArray()
        for (index in 0 until peers.length()) {
            val id = peers.optJSONObject(index)?.optString("id")?.trim().orEmpty()
            if (id in endpointIds) add(id)
        }
    }.distinct()
    fun filteredRows(name: String, removedIds: Set<String>): JSONArray {
        val rows = server.optJSONArray(name) ?: JSONArray()
        return JSONArray().apply {
            for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { row ->
                if (row.optString("id").trim() !in removedIds) put(JSONObject(row.toString()))
            }
        }
    }
    val payload = copyWireGuardServerForMutation(server)
        .put("expectedRevision", root.getLong("revision"))
        .put("endpointProfiles", filteredRows("endpointProfiles", endpointIds.toSet()))
        .put("peers", filteredRows("peers", peerIds.toSet()))
    return WireGuardServerRemovalPlan(payload, endpointIds, peerIds)
}

internal fun buildWireGuardProfileTransitionPlan(
    root: JSONObject,
    oldProfile: WireGuardProfile,
    newProfile: WireGuardProfile,
    clientPublicKey: String,
): WireGuardServerRemovalPlan {
    require(oldProfile.id == newProfile.id) { "配置身份已改变，不能作为同一事务提交" }
    require(oldProfile.endpointSource != WireGuardEndpointSource.MANUAL) { "旧配置不是自动配置" }
    if (newProfile.endpointSource != WireGuardEndpointSource.MANUAL) require(clientPublicKey.isNotBlank()) { "客户端公钥不可用" }
    val server = root.optJSONObject("server") ?: throw IllegalStateException("无法读取网关配置，未提交转换")
    val ownedId = wireGuardEndpointProfileId(oldProfile.id)
    fun withoutOwned(name: String): JSONArray {
        val rows = server.optJSONArray(name) ?: JSONArray()
        return JSONArray().apply {
            for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { row ->
                if (row.optString("id") != ownedId) put(JSONObject(row.toString()))
            }
        }
    }
    val endpointWasPresent = (server.optJSONArray("endpointProfiles") ?: JSONArray()).let { rows ->
        (0 until rows.length()).any { rows.optJSONObject(it)?.optString("id") == ownedId }
    }
    val peerWasPresent = (server.optJSONArray("peers") ?: JSONArray()).let { rows ->
        (0 until rows.length()).any { rows.optJSONObject(it)?.optString("id") == ownedId }
    }
    val strippedServer = copyWireGuardServerForMutation(server)
        .put("endpointProfiles", withoutOwned("endpointProfiles"))
        .put("peers", withoutOwned("peers"))
    val strippedRoot = JSONObject(root.toString()).put("server", strippedServer)
    val payload = if (newProfile.endpointSource == WireGuardEndpointSource.MANUAL) {
        JSONObject(strippedServer.toString()).put("expectedRevision", root.getLong("revision"))
    } else buildWireGuardServerPayload(strippedRoot, newProfile, clientPublicKey, replaceMatchingPublicKey = false)
    return WireGuardServerRemovalPlan(
        payload,
        if (endpointWasPresent) listOf(ownedId) else emptyList(),
        if (peerWasPresent) listOf(ownedId) else emptyList(),
    )
}

internal fun isWireGuardServerReady(state: WireGuardServerState, targetRevision: Long = state.config.revision): Boolean {
    val applyResultMatches = state.applyResultRevision?.let { it >= targetRevision } ?: true
    val applyResultSucceeded = state.applyResultOk != false && state.applyResultEnabled != false && state.applyError.isBlank()
    val runtimeObservable = state.capabilityWgToolAvailable == true || state.interfaceRunning != null
    val runtimeReady = if (runtimeObservable) {
        state.capabilityRunning && state.interfaceRunning == true
    } else {
        state.capabilityWgToolAvailable == false &&
            state.capabilityProvisioningReady &&
            state.capabilityControlBackend == "kernel-netlink" &&
            state.capabilityError.isBlank() &&
            state.applyResultRevision?.let { it >= targetRevision } == true &&
            state.applyResultOk == true &&
            state.applyResultEnabled == true &&
            state.applyResultInterfaceName == state.config.interfaceName &&
            state.applyResultControlBackend == "kernel-netlink"
    }
    return state.config.enabled &&
        state.agentRevision >= targetRevision &&
        applyResultMatches &&
        applyResultSucceeded &&
        runtimeReady
}

internal fun wireGuardServerErrorForRevision(state: WireGuardServerState, targetRevision: Long): String {
    val applyResultIsCurrent = state.applyResultRevision?.let { it >= targetRevision } ?: true
    if (applyResultIsCurrent && state.applyError.isNotBlank()) return state.applyError
    if (applyResultIsCurrent && state.applyResultOk == false) return "WireGuard 网关启动失败/未就绪"
    if (state.agentRevision >= targetRevision && state.capabilityError.isNotBlank()) return state.capabilityError
    return ""
}

/** Real Hub/Agent control plane. Manual profile contents are never provisioned through this class. */
class WireGuardHubApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)
    private val connectionIdentity = listOf(prefs.hub, prefs.token, prefs.hubDns)

    private fun requireOriginalConnection() {
        check(connectionIdentity == listOf(prefs.hub, prefs.token, prefs.hubDns)) {
            "Hub 连接已改变，请切回原连接核对操作结果"
        }
    }

    private fun requestText(path: String, method: String = "GET", json: String? = null): String {
        requireOriginalConnection()
        val response = hubApi.requestText(path, method, json)
        requireOriginalConnection()
        return response
    }

    companion object {
        // Endpoint polling and explicit mutations must not PUT stale whole-server snapshots.
        private val serverMutationMutex = Mutex()
    }

    private fun getServer(): JSONObject = JSONObject(requestText("/api/wireguard/server"))

    suspend fun loadServerState(): WireGuardServerState = withContext(Dispatchers.IO) {
        parseWireGuardServerState(getServer())
    }

    suspend fun loadServerConfig(): WireGuardServerConfig = withContext(Dispatchers.IO) {
        parseWireGuardServerState(getServer()).config
    }

    suspend fun loadRouterLanIp(): String = withContext(Dispatchers.IO) { routerLanIp() }

    suspend fun stunRuleDependents(ruleId: String): List<String> = withContext(Dispatchers.IO) {
        val root = getServer()
        require(root.optJSONObject("server") != null) { "无法核实 WG 依赖，暂不修改穿透目标" }
        wireGuardStunDependents(root, ruleId)
    }

    suspend fun loadStunDependencySnapshot(): WireGuardStunDependencySnapshot = withContext(Dispatchers.IO) {
        val root = try {
            getServer()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException(wireGuardMutationFailureMessage(WireGuardMutationStage.READ_BEFORE_SUBMIT, error), error)
        }
        parseWireGuardStunDependencySnapshot(root)
    }

    private suspend fun submitRemovalPlan(
        buildPlan: (JSONObject) -> WireGuardServerRemovalPlan,
    ): WireGuardRemoteMutationResult<Pair<WireGuardServerRemovalPlan, WireGuardServerState>> {
        var lastConflict: HubHttpException? = null
        repeat(3) { attempt ->
            val before = try {
                getServer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return WireGuardRemoteMutationResult.NotSubmitted(
                    wireGuardMutationFailureMessage(WireGuardMutationStage.READ_BEFORE_SUBMIT, error),
                )
            }
            val plan = try {
                buildPlan(before)
            } catch (error: Exception) {
                return WireGuardRemoteMutationResult.NotSubmitted(error.message ?: "提交前校验失败")
            }
            val response = try {
                requestText("/api/wireguard/server", "PUT", plan.payload.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HubHttpException) {
                if (error.statusCode == 409 && attempt < 2) {
                    lastConflict = error
                    return@repeat
                }
                if (isWireGuardSubmissionUncertain(error)) {
                    return WireGuardRemoteMutationResult.PendingVerification(
                        submittedRevision = null,
                        message = wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error),
                    )
                }
                return WireGuardRemoteMutationResult.NotSubmitted(
                    wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error),
                )
            } catch (error: Exception) {
                if (isWireGuardSubmissionUncertain(error)) {
                    return WireGuardRemoteMutationResult.PendingVerification(
                        submittedRevision = null,
                        message = wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error),
                    )
                }
                return WireGuardRemoteMutationResult.NotSubmitted(
                    wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error),
                )
            }
            val expected = try {
                parseWireGuardServerState(JSONObject(response)).config.also {
                    require(it.revision > 0L) { "Hub 返回结果缺少配置版本" }
                }
            } catch (error: Exception) {
                return WireGuardRemoteMutationResult.PendingVerification(
                    submittedRevision = null,
                    message = wireGuardMutationFailureMessage(WireGuardMutationStage.WAIT_AGENT, error),
                )
            }
            val applied = try {
                awaitServerConfig(expected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return WireGuardRemoteMutationResult.PendingVerification(
                    submittedRevision = expected.revision,
                    message = wireGuardMutationFailureMessage(WireGuardMutationStage.WAIT_AGENT, error),
                )
            }
            return WireGuardRemoteMutationResult.Applied(plan to applied)
        }
        return WireGuardRemoteMutationResult.NotSubmitted(
            wireGuardMutationFailureMessage(
                WireGuardMutationStage.SUBMIT,
                lastConflict ?: IllegalStateException("配置版本持续冲突，请刷新后重试"),
            ),
        )
    }

    suspend fun cleanupOrphanedStunBinding(
        ruleId: String,
        localProfiles: List<WireGuardProfile>,
    ): WireGuardRemoteMutationResult<WireGuardBindingCleanupResult> = withContext(Dispatchers.IO) {
        serverMutationMutex.withLock {
            val exactRuleId = ruleId.trim()
            if (exactRuleId.isBlank()) return@withLock WireGuardRemoteMutationResult.NotSubmitted("必须明确指定 STUN 规则 ID")
            if (localProfiles.any {
                it.endpointSource == WireGuardEndpointSource.STUN && it.endpointBindingId == exactRuleId
            }) return@withLock WireGuardRemoteMutationResult.NotSubmitted("本机仍有 WireGuard 配置引用该穿透，不能作为残留清理")
            val localEndpointIds = localProfiles.map { wireGuardEndpointProfileId(it.id) }.toSet()
            val result = submitRemovalPlan { root ->
                buildWireGuardRuleCleanupPlan(root, exactRuleId).also { plan ->
                    require(plan.removedEndpointProfileIds.none { it in localEndpointIds }) {
                        "本机仍有对应的 WireGuard 配置，不能作为仅服务端残留清理"
                    }
                    require(plan.removedEndpointProfileIds.isNotEmpty()) { "服务端已没有该 STUN 规则的 WireGuard 引用" }
                }
            }
            val applied = when (result) {
                is WireGuardRemoteMutationResult.Applied -> result.value
                is WireGuardRemoteMutationResult.PendingVerification -> return@withLock result
                is WireGuardRemoteMutationResult.NotSubmitted -> return@withLock result
            }
            val plan = applied.first
            val state = applied.second
            val latest = try {
                getServer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withLock WireGuardRemoteMutationResult.PendingVerification(
                    state.config.revision,
                    wireGuardMutationFailureMessage(WireGuardMutationStage.WAIT_AGENT, error),
                )
            }
            if (parseWireGuardStunDependencySnapshot(latest).forRule(exactRuleId).isNotEmpty()) {
                return@withLock WireGuardRemoteMutationResult.PendingVerification(
                    state.config.revision,
                    "Hub 已接受清理，但该规则引用仍存在；请刷新核对",
                )
            }
            WireGuardRemoteMutationResult.Applied(WireGuardBindingCleanupResult(
                ruleId = exactRuleId,
                removedEndpointProfileIds = plan.removedEndpointProfileIds,
                removedPeerIds = plan.removedPeerIds,
                appliedRevision = state.config.revision,
            ))
        }
    }

    suspend fun transitionAutomaticProfile(
        oldProfile: WireGuardProfile,
        newProfile: WireGuardProfile,
        clientPublicKey: String,
    ): WireGuardRemoteMutationResult<WireGuardProfileTransitionResult> = withContext(Dispatchers.IO) {
        serverMutationMutex.withLock {
            val result = submitRemovalPlan { root ->
                buildWireGuardProfileTransitionPlan(root, oldProfile, newProfile, clientPublicKey)
            }
            val applied = when (result) {
                is WireGuardRemoteMutationResult.Applied -> result.value
                is WireGuardRemoteMutationResult.PendingVerification -> return@withLock result
                is WireGuardRemoteMutationResult.NotSubmitted -> return@withLock result
            }
            val (plan, state) = applied
            val latest = try {
                getServer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withLock WireGuardRemoteMutationResult.PendingVerification(
                    state.config.revision,
                    wireGuardMutationFailureMessage(WireGuardMutationStage.WAIT_AGENT, error),
                )
            }
            val server = latest.getJSONObject("server")
            val ownedId = wireGuardEndpointProfileId(oldProfile.id)
            val endpointRows = server.optJSONArray("endpointProfiles") ?: JSONArray()
            val peerRows = server.optJSONArray("peers") ?: JSONArray()
            val endpoint = (0 until endpointRows.length()).mapNotNull(endpointRows::optJSONObject)
                .firstOrNull { it.optString("id") == ownedId }
            val peerExists = (0 until peerRows.length()).any { peerRows.optJSONObject(it)?.optString("id") == ownedId }
            if (newProfile.endpointSource == WireGuardEndpointSource.MANUAL) {
                if (endpoint != null || peerExists) return@withLock WireGuardRemoteMutationResult.PendingVerification(
                    state.config.revision, "自动配置的服务端引用仍存在；本机尚未切换",
                )
            } else {
                if (endpoint == null || !peerExists) return@withLock WireGuardRemoteMutationResult.PendingVerification(
                    state.config.revision, "新自动配置未完整写入；本机尚未切换",
                )
                if (endpoint.optString("endpointSource") != newProfile.endpointSource.wireValue) {
                    return@withLock WireGuardRemoteMutationResult.PendingVerification(
                        state.config.revision, "服务端端点来源与新配置不一致；本机尚未切换",
                    )
                }
                if (newProfile.endpointSource == WireGuardEndpointSource.STUN) {
                    if (endpoint.optString("stunRuleId") != newProfile.endpointBindingId) {
                        return@withLock WireGuardRemoteMutationResult.PendingVerification(
                            state.config.revision, "服务端 STUN 绑定与新配置不一致；本机尚未切换",
                        )
                    }
                }
            }
            val publicKey = wireGuardServerPublicKey(latest).ifBlank { newProfile.serverPublicKey }
            WireGuardRemoteMutationResult.Applied(WireGuardProfileTransitionResult(
                profile = newProfile.copy(serverPublicKey = publicKey),
                removedEndpointProfileIds = plan.removedEndpointProfileIds,
                removedPeerIds = plan.removedPeerIds,
                appliedRevision = state.config.revision,
            ))
        }
    }

    suspend fun removeAutomaticProfileTransaction(
        profile: WireGuardProfile,
    ): WireGuardRemoteMutationResult<WireGuardProfileTransitionResult> {
        val localOnly = profile.copy(
            endpointSource = WireGuardEndpointSource.MANUAL,
            endpointBindingId = "",
            endpointRevision = 0L,
            endpointUpdatedAt = 0L,
            endpointUpdateError = "",
        )
        return transitionAutomaticProfile(profile, localOnly, clientPublicKey = "")
    }

    private fun routerLanIp(): String {
        requireOriginalConnection()
        val status = runCatching { hubApi.requestJson("/api/status") }.getOrNull()
        val dashboard = runCatching { hubApi.requestJson("/api/router/dashboard") }.getOrNull()
        val fromUrl = runCatching {
            URI(if (prefs.routerLanUrl.contains("://")) prefs.routerLanUrl else "https://${prefs.routerLanUrl}").host
        }.getOrNull().orEmpty()
        requireOriginalConnection()
        return findRouterLanIpv4(status, dashboard)
            .ifBlank { findRouterLanIpv4(JSONObject().put("router", JSONObject().put("lanIp", fromUrl))) }
    }

    private suspend fun awaitServerConfig(expected: WireGuardServerConfig): WireGuardServerState {
        repeat(12) { attempt ->
            val state = parseWireGuardServerState(getServer())
            if (isWireGuardServerConfigApplied(state, expected)) return state
            if (state.config.revision > expected.revision &&
                (state.config.listenPort != expected.listenPort || state.config.enabled != expected.enabled ||
                    state.config.mtu != expected.mtu || state.config.address != expected.address)) {
                throw IllegalStateException("网关已被其他操作修改，请刷新核对；未继续修改穿透规则")
            }
            wireGuardServerErrorForRevision(state, expected.revision).takeIf { it.isNotBlank() }?.let {
                throw IllegalStateException(it)
            }
            if (attempt < 11) delay(1_500L)
        }
        throw IllegalStateException("网关修改已提交，尚未确认生效；请刷新核对，不要重复新增")
    }

    suspend fun enableServerAndAwaitReady(): WireGuardServerState = withContext(Dispatchers.IO) {
        serverMutationMutex.withLock {
            val before = getServer()
            val currentState = parseWireGuardServerState(before)
            if (currentState.config.enabled) {
                if (isWireGuardServerReady(currentState)) return@withLock currentState
                val error = wireGuardServerErrorForRevision(currentState, currentState.config.revision)
                throw IllegalStateException(error.ifBlank { "WireGuard 网关尚未就绪" })
            }

            val payload = buildWireGuardServerEnablePayload(before)
            val saved = JSONObject(requestText("/api/wireguard/server", "PUT", payload.toString()))
            val targetRevision = saved.optLong("revision", 0L)
            if (targetRevision <= 0L) throw IllegalStateException("Hub 未接受 WireGuard 网关启用请求")

            var latestState = parseWireGuardServerState(getServer())
            repeat(12) { attempt ->
                if (isWireGuardServerReady(latestState, targetRevision)) return@withLock latestState
                wireGuardServerErrorForRevision(latestState, targetRevision).takeIf { it.isNotBlank() }?.let {
                    throw IllegalStateException(it)
                }
                if (attempt < 11) {
                    delay(1_500L)
                    latestState = parseWireGuardServerState(getServer())
                }
            }
            throw IllegalStateException("WireGuard 网关启动失败/未就绪")
        }
    }

    suspend fun updateServerConfig(
        listenPort: Int,
        mtu: Int,
        address: String = "10.77.0.1/24",
        enabled: Boolean = true,
    ): WireGuardServerConfig = withContext(Dispatchers.IO) {
        serverMutationMutex.withLock {
            val before = try {
                getServer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException(wireGuardMutationFailureMessage(WireGuardMutationStage.READ_BEFORE_SUBMIT, error), error)
            }
            val payload = buildWireGuardServerSettingsPayload(before, listenPort, mtu, address, enabled)
            val response = try {
                requestText("/api/wireguard/server", "PUT", payload.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isWireGuardSubmissionUncertain(error)) {
                    throw WireGuardPendingVerificationException(
                        null,
                        wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error),
                        error,
                    )
                }
                throw IllegalStateException(wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error), error)
            }
            try {
                parseWireGuardServerState(JSONObject(response)).config
            } catch (error: Exception) {
                throw WireGuardPendingVerificationException(
                    null,
                    wireGuardMutationFailureMessage(WireGuardMutationStage.WAIT_AGENT, error),
                    error,
                )
            }
        }
    }

    suspend fun updateServerConfigAndSync(
        listenPort: Int,
        mtu: Int,
        address: String,
        enabled: Boolean,
        profiles: List<WireGuardProfile>,
        resolveProfilesForPort: (Int) -> List<WireGuardProfile> = { profiles },
        onProgress: (String) -> Unit = {},
    ): WireGuardServerUpdateResult = withContext(Dispatchers.IO) {
        serverMutationMutex.withLock {
            onProgress("正在核对网关与穿透依赖…")
            val before = try {
                getServer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException(wireGuardMutationFailureMessage(WireGuardMutationStage.READ_BEFORE_SUBMIT, error), error)
            }
            val oldConfig = parseWireGuardServerState(before).config
            val authoritativeProfiles = resolveProfilesForPort(oldConfig.listenPort)
            // Disabling alone must remain possible even when a former STUN binding is missing.
            val boundIds = if (enabled || listenPort != oldConfig.listenPort) wireGuardBoundStunIds(before, authoritativeProfiles) else emptySet()
            val stunApi = StunApi(prefs)
            val boundRules = if (boundIds.isEmpty()) emptyList() else {
                val snapshot = stunApi.list()
                require(snapshot.rulesLoaded) { "穿透规则未完整加载，未提交网关修改" }
                val missing = boundIds - snapshot.rules.map { it.id }.toSet()
                require(missing.isEmpty()) { "存在已失效的 STUN 绑定，请先修复绑定：${missing.joinToString()}" }
                snapshot.rules.filter { it.id in boundIds }
            }
            val routerIp = if (boundRules.any { it.targetType != "router_self" && it.targetIpv4 != "127.0.0.1" }) routerLanIp() else ""
            require(boundRules.all { isWireGuardStunTarget(it, routerIp) }) {
                "关联穿透并非指向当前路由器的 UDP 服务，未自动改写，请先核实绑定"
            }
            val payload = buildWireGuardServerSettingsPayload(before, listenPort, mtu, address, enabled, authoritativeProfiles)
            onProgress("正在提交网关修改…")
            val response = try {
                requestText("/api/wireguard/server", "PUT", payload.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isWireGuardSubmissionUncertain(error)) {
                    throw WireGuardPendingVerificationException(
                        null,
                        wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error),
                        error,
                    )
                }
                throw IllegalStateException(wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error), error)
            }
            val config = try {
                parseWireGuardServerState(JSONObject(response)).config
            } catch (error: Exception) {
                throw WireGuardPendingVerificationException(
                    null,
                    wireGuardMutationFailureMessage(WireGuardMutationStage.WAIT_AGENT, error),
                    error,
                )
            }
            if (config.revision <= 0L) throw WireGuardPendingVerificationException(
                null,
                "Hub 已接受 WireGuard 修改，但返回结果缺少版本；请刷新核对，不要重复提交",
            )
            onProgress("网关已提交，正在等待 Agent 应用回执…")
            try {
                awaitServerConfig(config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withLock WireGuardServerUpdateResult(config, applied = false,
                    warnings = listOf(wireGuardMutationFailureMessage(WireGuardMutationStage.WAIT_AGENT, error)),
                    pendingStunRuleIds = boundIds.toList(),
                    previousListenPort = oldConfig.listenPort)
            }
            val warnings = mutableListOf<String>()
            val pending = mutableListOf<String>()
            for (rule in boundRules) {
                requireOriginalConnection()
                onProgress("网关已应用，正在同步穿透 ${rule.name.ifBlank { rule.id }}…")
                try {
                    // Preserve local channel, public mapping, enabled state and every other target attribute.
                    // Re-read immediately before PUT to avoid overwriting a concurrently edited rule.
                    val snapshot = stunApi.list()
                    require(snapshot.rulesLoaded) { "穿透列表不完整" }
                    val current = snapshot.rules.firstOrNull { it.id == rule.id }
                        ?: throw IllegalStateException("关联规则已删除")
                    require(isWireGuardStunTarget(current, routerIp)) { "规则目标已改变，未覆盖" }
                    require(current.targetPort == rule.targetPort || current.targetPort == config.listenPort) {
                        "穿透目标端口已被其他操作修改，未覆盖"
                    }
                    val updated = if (current.targetPort == config.listenPort) current else stunApi.update(current.id,
                        StunDraft.from(current).copy(targetPort = config.listenPort.toString(), name = current.name))
                    if (updated.enabled && !updated.ready) pending += updated.id
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    pending += rule.id
                    warnings += "${rule.name.ifBlank { rule.id }}：${error.message ?: "穿透同步结果待确认"}"
                }
            }
            WireGuardServerUpdateResult(config, applied = true, warnings = warnings, pendingStunRuleIds = pending,
                previousListenPort = oldConfig.listenPort)
        }
    }


    suspend fun ensureStunBinding(profile: WireGuardProfile): String = withContext(Dispatchers.IO) {
        require(profile.endpointSource == WireGuardEndpointSource.STUN)
        val listenPort = loadServerConfig().listenPort
        val api = StunApi(prefs)
        val snapshot = api.list()
        require(snapshot.rulesLoaded) { "穿透规则尚未完整加载，请稍后重试" }
        val rules = snapshot.rules
        val routerIp = routerLanIp()
        require(routerIp.isNotBlank() && routerIp != "127.0.0.1") {
            "无法确认当前路由器 LAN 地址，未创建 WireGuard STUN 规则"
        }
        requireOriginalConnection()
        profile.endpointBindingId.takeIf { it.isNotBlank() }?.let { id ->
            var selected = rules.firstOrNull { it.id == id } ?: throw IllegalArgumentException("绑定的 STUN 规则不存在")
            if (isLegacyWireGuardRelayStunTarget(selected, listenPort)) {
                val correctedDraft = StunDraft.from(selected).copy(
                    targetType = "manual",
                    targetIpv4 = routerIp,
                    targetPort = listenPort.toString(),
                )
                selected = try {
                    api.update(selected.id, correctedDraft)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!isWireGuardSubmissionUncertain(error)) throw error
                    val verified = runCatching { api.list() }.getOrNull()?.takeIf { it.rulesLoaded }
                        ?.rules?.firstOrNull { it.id == selected.id }
                    if (verified == null || !isWireGuardStunTarget(verified, routerIp) ||
                        verified.targetPort != listenPort) {
                        throw IllegalStateException(
                            "旧 WireGuard STUN 规则修正结果待核对；请刷新后再保存，不要重复操作",
                            error,
                        )
                    }
                    verified
                }
            }
            require(selected.enabled && selected.serviceType.equals("WireGuard", ignoreCase = true) &&
                isWireGuardStunTarget(selected, routerIp) && selected.targetPort == listenPort) {
                "请选择指向当前网关 UDP $listenPort 的已启用穿透规则"
            }
            return@withContext selected.id
        }
        val candidates = rules.filter {
            it.enabled && isWireGuardStunTarget(it, routerIp) && it.targetPort == listenPort &&
                it.serviceType.equals("WireGuard", ignoreCase = true)
        }
        require(candidates.isEmpty()) {
            "发现已有可用 WG 穿透规则；为避免把手动规则误认作自动规则，请明确选择绑定"
        }
        val draft = StunDraft(
            serviceType = "WireGuard",
            transportProtocol = "UDP",
            targetType = "manual",
            targetIpv4 = routerIp,
            targetPort = listenPort.toString(),
            name = "WireGuard",
        )
        try {
            api.create(draft).id.also { require(it.isNotBlank()) { "Hub 返回的 STUN 规则缺少 ID" } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val uncertain = error is IOException ||
                (error is HubHttpException && (error.statusCode == 408 || error.statusCode >= 500))
            if (!uncertain) throw error
            val after = try {
                api.list()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw IllegalStateException("STUN 创建结果待核对；请刷新列表，不要重复新增", error)
            }
            require(after.rulesLoaded) { "STUN 创建结果待核对；列表不完整，请刷新后明确选择，不要重复新增" }
            val created = compatibleNewWireGuardStunRules(rules.map { it.id }.toSet(), after, listenPort, routerIp)
            when (created.size) {
                1 -> created.single().id
                0 -> throw IllegalStateException("STUN 创建结果待核对；未发现可精确认领的新规则，请刷新后明确选择", error)
                else -> throw IllegalStateException("发现多条新 WireGuard STUN 规则，未自动认领；请刷新后按规则 ID 选择", error)
            }
        }
    }

    suspend fun provision(profile: WireGuardProfile, clientPublicKey: String): WireGuardProvisionResult = withContext(Dispatchers.IO) {
        serverMutationMutex.withLock {
            require(profile.endpointSource != WireGuardEndpointSource.MANUAL) { "手动配置由你自己维护，不会同步 Hub" }
            var desiredRevision = 0L
            var submittedConfig: WireGuardServerConfig? = null
            var updatedProfile = profile
            var lastConflict: Throwable? = null
            for (attempt in 0 until 3) {
                val before = getServer()
                val server = parseWireGuardServerState(before).config
                updatedProfile = applyWireGuardServerConfig(updatedProfile, server.listenPort, server.mtu, server.listenPort)
                val capability = before.optJSONObject("agentStatus")?.optJSONObject("capability")
                if (capability != null && capability.length() > 0 && !capability.optBoolean("provisioningReady", false)) {
                    throw IllegalStateException(capability.optString("error").ifBlank { "Agent 的 WireGuard 内核能力不可用" })
                }
                val payload = buildWireGuardServerPayload(before, updatedProfile, clientPublicKey)
                try {
                    val saved = JSONObject(requestText("/api/wireguard/server", "PUT", payload.toString()))
                    desiredRevision = saved.optLong("revision", 0L)
                    submittedConfig = parseWireGuardServerState(saved).config
                    lastConflict = null
                    break
                } catch (error: HubHttpException) {
                    if (error.statusCode != 409 || attempt == 2) throw error
                    lastConflict = error
                }
            }
            if (desiredRevision <= 0L) throw lastConflict ?: IllegalStateException("Hub 未接受 WireGuard 配置")
            val expected = submittedConfig ?: throw IllegalStateException("网关提交结果待确认")

            var latest = getServer()
            repeat(12) {
                val applied = latest.optJSONObject("agentStatus")?.optLong("revision", 0L) ?: 0L
                val publicKey = wireGuardServerPublicKey(latest)
                val state = parseWireGuardServerState(latest)
                val peers = latest.optJSONObject("server")?.optJSONArray("peers") ?: JSONArray()
                val ownPeerExists = (0 until peers.length()).any { index ->
                    peers.optJSONObject(index)?.let { row -> row.optString("id") == wireGuardPeerId(profile.id) &&
                        row.optString("publicKey") == clientPublicKey } == true
                }
                if (state.config.revision >= desiredRevision && !ownPeerExists) throw IllegalStateException("该配置已被其他操作删除或修改，请刷新核对")
                if (isWireGuardServerConfigApplied(state, expected) && publicKey.isNotBlank()) {
                    updatedProfile = updatedProfile.copy(serverPublicKey = publicKey)
                    return@withLock WireGuardProvisionResult(updatedProfile, desiredRevision, applied, "Agent 已应用并返回服务端公钥")
                }
                delay(1_500L)
                latest = getServer()
            }
            val status = latest.optJSONObject("agentStatus")
            val applyError = status?.optJSONObject("applyResult")?.optString("error").orEmpty()
            throw IllegalStateException(applyError.ifBlank { "Agent 未在 18 秒内完成 WireGuard 配置，请确认 Agent 已升级且在线" })
        }
    }

    suspend fun patchManagedEndpoint(profile: WireGuardProfile) = withContext(Dispatchers.IO) {
        if (profile.endpointSource == WireGuardEndpointSource.MANUAL || profile.endpoint.isBlank()) return@withContext
        serverMutationMutex.withLock {
            repeat(3) { attempt ->
                val root = getServer()
                val profiles = root.optJSONObject("server")?.optJSONArray("endpointProfiles")
                    ?: throw IllegalStateException("网关端点记录缺失，请重新同步配置")
                val id = wireGuardEndpointProfileId(profile.id)
                val row = (0 until profiles.length()).mapNotNull(profiles::optJSONObject).firstOrNull { it.optString("id") == id }
                    ?: throw IllegalStateException("该 WG 端点记录已删除，请重新同步配置")
                require(row.optString("endpointSource") == profile.endpointSource.wireValue) { "端点来源已改变，未覆盖" }
                if (profile.endpointSource == WireGuardEndpointSource.STUN) {
                    require(row.optString("stunRuleId") == profile.endpointBindingId) { "STUN 绑定已改变，未覆盖" }
                    require(profile.endpointUpdateError.isBlank()) { "STUN 地址未确认可用" }
                }
                if (row.optString("resolvedEndpoint") == profile.endpoint) return@withLock
                val source = profile.endpointSource.wireValue
                val owner = when (profile.endpointSource) {
                    WireGuardEndpointSource.DDNS -> "ddns:$id"
                    WireGuardEndpointSource.STUN -> "stun:${profile.endpointBindingId}"
                    WireGuardEndpointSource.MANUAL -> ""
                }
                val body = JSONObject()
                    .put("endpointSource", source)
                    .put("owner", owner)
                    .put("endpoint", profile.endpoint)
                    .put("expectedEndpointRevision", row.optLong("endpointRevision", 0L))
                try {
                    requestText("/api/wireguard/endpoints/$id", "PATCH", body.toString())
                    return@withLock
                } catch (error: HubHttpException) {
                    if (error.statusCode != 409 || attempt == 2) throw error
                }
            }
        }
    }

    suspend fun removeAutomaticProfile(profile: WireGuardProfile, clientPublicKey: String) = withContext(Dispatchers.IO) {
        if (profile.endpointSource == WireGuardEndpointSource.MANUAL) return@withContext
        when (val result = removeAutomaticProfileTransaction(profile)) {
            is WireGuardRemoteMutationResult.Applied -> Unit
            is WireGuardRemoteMutationResult.PendingVerification -> throw IllegalStateException(result.message)
            is WireGuardRemoteMutationResult.NotSubmitted -> throw IllegalStateException(result.message)
        }
    }
}
