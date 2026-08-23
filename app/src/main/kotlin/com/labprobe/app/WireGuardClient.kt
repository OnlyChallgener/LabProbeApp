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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

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
    val interfaceAddresses: List<String> = listOf("10.66.0.2/32"),
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
        allowedIps.isNotEmpty() && allowedIps.none { it.trim() in setOf("0.0.0.0/0", "::/0") }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("endpointSource", endpointSource.wireValue)
        put("endpointHost", endpointHost)
        put("endpointPort", endpointPort)
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
                interfaceAddresses = jsonStringList(value.optJSONArray("interfaceAddresses")).ifEmpty { listOf("10.66.0.2/32") },
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
            endpointHost = "",
        )
    }
}

const val DEFAULT_WIREGUARD_PORT = 51820
const val DEFAULT_WIREGUARD_KEEPALIVE = 25

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
    profile.allowedIps.isEmpty() -> "请填写家庭内网网段"
    profile.allowedIps.any { it.trim() in setOf("0.0.0.0/0", "::/0") } -> "MVP 仅支持家庭内网网段，不支持全局流量"
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
        keyStore.put(profile.id, privateKey)
        save(load().filterNot { it.id == profile.id } + profile)
        return profile
    }

    /** Explicit user edit: bump config revision but retain endpoint event ordering. */
    fun saveProfileEdit(profile: WireGuardProfile) {
        val old = load().firstOrNull { it.id == profile.id }
        val revised = profile.copy(
            profileRevision = (old?.profileRevision ?: 0L) + 1L,
            endpointRevision = old?.endpointRevision ?: profile.endpointRevision,
        )
        save(load().filterNot { it.id == profile.id } + revised)
    }

    fun delete(profileId: String) {
        save(load().filterNot { it.id == profileId })
        keyStore.remove(profileId)
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
    ): WireGuardProfile? {
        val existing = load().firstOrNull { it.id == profileId } ?: return null
        if (!canApplyWireGuardEndpointUpdate(existing, source, endpointRevision)) {
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

    fun markEndpointError(profileId: String, source: WireGuardEndpointSource, message: String): WireGuardProfile? {
        val existing = load().firstOrNull { it.id == profileId && it.endpointSource == source } ?: return null
        val updated = existing.copy(endpointUpdateError = message.trim())
        save(load().filterNot { it.id == updated.id } + updated)
        return updated
    }
}

internal fun wireGuardPublicKey(privateKey: String): String = runCatching {
    KeyPair(Key.fromBase64(privateKey.trim())).publicKey.toBase64()
}.getOrDefault("")

internal fun wireGuardQuickConfig(profile: WireGuardProfile, privateKey: String): String {
    val safe = privateKey.trim()
    require(wireGuardProfileError(profile, safe).isBlank()) { "WireGuard 配置不完整" }
    fun lines(values: List<String>) = values.map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
    return buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $safe")
        appendLine("Address = ${lines(profile.interfaceAddresses)}")
        profile.dnsServers.takeIf { it.isNotEmpty() }?.let { appendLine("DNS = ${lines(it)}") }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${profile.serverPublicKey.trim()}")
        appendLine("AllowedIPs = ${lines(profile.allowedIps)}")
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
        ) ?: profile
    }

    fun applyStunSnapshot(
        store: WireGuardProfileStore,
        profiles: List<WireGuardProfile>,
        rules: List<StunRule>,
    ): List<WireGuardProfile> = profiles.map { profile ->
        if (profile.endpointSource != WireGuardEndpointSource.STUN) return@map profile
        val rule = rules.firstOrNull { it.id == profile.endpointBindingId }
            ?: rules.firstOrNull {
                it.ready && it.transportProtocol == "UDP" &&
                    (it.serviceType.equals("WireGuard", ignoreCase = true) || it.targetPort == DEFAULT_WIREGUARD_PORT)
            }
        if (rule?.ready != true || rule.runtime.publicEndpoint.isBlank()) {
            // Preserve the last working endpoint, so a temporary STUN probe
            // failure cannot turn a working profile into an empty one.
            return@map store.markEndpointError(profile.id, WireGuardEndpointSource.STUN, "STUN 地址暂时不可用，已保留上一次地址") ?: profile
        }
        store.applyEndpointUpdate(
            profile.id,
            WireGuardEndpointSource.STUN,
            rule.runtime.publicEndpoint,
            endpointRevision = rule.runtime.mappingUpdatedAt ?: 0L,
        ) ?: profile
    }
}

/**
 * Optional future Hub contract. No request is made until the user taps sync;
 * current Hub versions that lack this endpoint simply return null.
 */
class WireGuardHubApi(private val prefs: AppPrefs) {
    private val hubApi = HubApi(prefs)

    suspend fun profilesIfSupported(): List<WireGuardProfile>? = withContext(Dispatchers.IO) {
        val root = runCatching { JSONObject(hubApi.requestText("/api/wireguard")) }.getOrNull() ?: return@withContext null
        val profiles = root.optJSONArray("profiles") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until profiles.length()) profiles.optJSONObject(index)?.let(WireGuardProfile::fromJson)?.let(::add)
        }
    }
}
