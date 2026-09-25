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
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
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
    /**
     * User-authored routes only. The home LAN /24 is appended at connect time by
     * [homeLanRouteCidrs]; a guessed literal here once silently killed LAN access
     * for every network that was not 192.168.1.x.
     */
    val allowedIps: List<String> = listOf("10.77.0.0/24"),
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
            val source = WireGuardEndpointSource.fromWireValue(value.optString("endpointSource"))
            return WireGuardProfile(
                id = id,
                name = value.optString("name").trim().ifBlank { "WireGuard" },
                endpointSource = source,
                endpointHost = value.optString("endpointHost").trim(),
                endpointPort = value.optInt("endpointPort", DEFAULT_WIREGUARD_PORT).coerceIn(1, 65535),
                followsServerPort = value.takeIf { it.has("followsServerPort") && !it.isNull("followsServerPort") }
                    ?.optBoolean("followsServerPort"),
                mtu = value.optInt("mtu", DEFAULT_WIREGUARD_MTU).coerceIn(1280, 1500),
                interfaceAddresses = jsonStringList(value.optJSONArray("interfaceAddresses")).ifEmpty { listOf("10.77.0.2/32") },
                dnsServers = jsonStringList(value.optJSONArray("dnsServers")),
                serverPublicKey = value.optString("serverPublicKey").trim(),
                allowedIps = jsonStringList(value.optJSONArray("allowedIps")).let { routes ->
                    if (source == WireGuardEndpointSource.MANUAL) routes else
                        routes.filterNot { it == LEGACY_GUESSED_HOME_LAN }.ifEmpty { listOf("10.77.0.0/24") }
                },
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
                WireGuardEndpointSource.MANUAL -> emptyList()
                WireGuardEndpointSource.DDNS -> listOf("10.77.0.2/32")
                WireGuardEndpointSource.STUN -> listOf("10.77.0.3/32")
            },
            // A manual server's routes are unknown. Require an explicit choice.
            allowedIps = if (source == WireGuardEndpointSource.MANUAL) emptyList() else listOf("10.77.0.0/24"),
        )
    }
}

const val DEFAULT_WIREGUARD_PORT = 51820
const val DEFAULT_WIREGUARD_MTU = 1420

/** Old hard-coded default that silently routed a LAN almost nobody has. */
private const val LEGACY_GUESSED_HOME_LAN = "192.168.1.0/24"

/**
 * Home LANs the tunnel should reach, derived from the router's own measured
 * address instead of a literal. Only IPv4 hosts are used; host names yield nothing.
 */
internal fun homeLanRouteCidrs(vararg sources: String?): List<String> = sources
    .asSequence()
    .mapNotNull { it?.trim() }
    .map { it.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':') }
    .filter { host -> host.split('.').size == 4 && host.split('.').all { it.toIntOrNull() != null } }
    .map { host -> host.split('.').take(3).joinToString(".") + ".0/24" }
    .distinct()
    .toList()

/** A private Hub address is a usable LAN-route hint even before the Hub can be reached. */
internal fun privateHubLanRouteCidrs(hubUrl: String): List<String> {
    val host = runCatching { java.net.URI(hubUrl.trim()).host }.getOrNull().orEmpty()
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return emptyList()
    val first = octets[0]
    val second = octets[1]
    if (first != 10 && !(first == 172 && second in 16..31) && !(first == 192 && second == 168)) return emptyList()
    return homeLanRouteCidrs(host)
}
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

/**
 * 这次编辑要不要路由器重新下发。
 *
 * 隧道地址、服务端公钥、端点来源/绑定会修改路由器 peer；名称先保存在
 * 手机，下次同步自动配置时再更新远端展示名。AllowedIPs、隧道 DNS、MTU
 * 只决定手机自己把哪些包塞进隧道，不能被 Hub 是否可达挡住 ——
 * 之前正是这个耦合让"已握手但内网不通"变成"什么都改不了"。
 */
internal fun wireGuardEditNeedsRouter(original: WireGuardProfile, edited: WireGuardProfile): Boolean =
    original.interfaceAddresses != edited.interfaceAddresses ||
        original.serverPublicKey != edited.serverPublicKey ||
        original.endpointSource != edited.endpointSource ||
        original.endpointBindingId != edited.endpointBindingId

/** Changes to the effective local tunnel config require a running tunnel to reload. */
internal fun wireGuardLocalTunnelConfigChanged(original: WireGuardProfile, edited: WireGuardProfile): Boolean =
    original.endpoint != edited.endpoint ||
        original.mtu != edited.mtu ||
        original.interfaceAddresses != edited.interfaceAddresses ||
        original.dnsServers != edited.dnsServers ||
        original.serverPublicKey != edited.serverPublicKey ||
        original.allowedIps != edited.allowedIps ||
        original.persistentKeepalive != edited.persistentKeepalive

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


/** Keep old default-profile keys readable while namespacing every new Hub/router. */
internal fun wireGuardPrivateKeyStorageId(
    workspaceId: String,
    hubRoot: String,
    legacyHubRoot: String,
    profileId: String,
): String = if (workspaceId == DEFAULT_ROUTER_WORKSPACE_ID &&
    normalizeHubBaseUrl(hubRoot) == normalizeHubBaseUrl(legacyHubRoot)
) profileId else routerWorkspacePreferencesName(workspaceId, hubRoot) + "|" + profileId

/** Metadata lives in AppPrefs; only private keys use SecureWireGuardKeyStore. */
class WireGuardProfileStore(context: Context, private val prefs: AppPrefs) {
    private val keyStore = SecureWireGuardKeyStore(context.applicationContext)
    private val workspaceHubRoot = prefs.hubRoot
    private val legacyHubRoot = context.applicationContext
        .getSharedPreferences("labprobe", Context.MODE_PRIVATE)
        .getString("legacy_default_hub_root_v1", "").orEmpty()

    private fun secureKeyId(profileId: String): String =
        wireGuardPrivateKeyStorageId(prefs.workspaceId, workspaceHubRoot, legacyHubRoot, profileId)

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

    fun privateKey(profileId: String): String = keyStore.get(secureKeyId(profileId))

    fun create(profile: WireGuardProfile): WireGuardProfile {
        val privateKey = KeyPair().privateKey.toBase64()
        synchronized(wireGuardProfileStoreLock) {
            require(load().none { it.id == profile.id }) { "配置已存在，请编辑现有配置" }
            keyStore.put(secureKeyId(profile.id), privateKey)
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
            keyStore.remove(secureKeyId(profileId))
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

/** The router's peer AllowedIPs identify individual clients, not their interface subnet. */
internal fun wireGuardPeerHostRoutes(addresses: List<String>): List<String> = addresses.map { raw ->
    val parts = raw.trim().split('/')
    require(parts.size in 1..2) { "客户端隧道地址格式无效：$raw" }
    val host = parts[0].trim()
    val bits = if (host.contains(':')) {
        require(host.matches(Regex("[0-9a-fA-F:.]+")) &&
            runCatching { java.net.InetAddress.getByName(host) is java.net.Inet6Address }.getOrDefault(false)) {
            "客户端 IPv6 地址无效：$raw"
        }
        128
    } else {
        val octets = host.split('.')
        require(octets.size == 4 && octets.all { part ->
            val octet = part.toIntOrNull()
            octet != null && octet in 0..255
        }) {
            "客户端 IPv4 地址无效：$raw"
        }
        32
    }
    if (parts.size == 2) {
        val prefix = parts[1].toIntOrNull()
        require(prefix != null && prefix in 0..bits) { "客户端隧道前缀无效：$raw" }
    }
    "$host/$bits"
}.distinct()

internal fun wireGuardEffectiveAllowedIps(
    profile: WireGuardProfile,
    homeLanRoutes: List<String> = emptyList(),
): List<String> {
    // A manual profile must use exactly the routes entered by the user.
    if (profile.endpointSource == WireGuardEndpointSource.MANUAL) return profile.allowedIps
    val autoSubnet = profile.interfaceAddresses.firstOrNull()?.split('/')?.firstOrNull()?.let { ip ->
        val parts = ip.split('.')
        if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0/24" else null
    }
    return (profile.allowedIps + homeLanRoutes + listOfNotNull(autoSubnet, "10.77.0.0/24")).distinct()
}

internal fun wireGuardQuickConfig(
    profile: WireGuardProfile,
    privateKey: String,
    homeLanRoutes: List<String> = emptyList(),
): String {
    val safe = privateKey.trim()
    require(wireGuardProfileError(profile, safe).isBlank()) { "WireGuard 配置不完整" }
    fun lines(values: List<String>) = values.map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
    val effectiveAllowedIps = wireGuardEffectiveAllowedIps(profile, homeLanRoutes)
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
    val otherWorkspaceActive: Boolean = false,
)

sealed interface WireGuardStartResult {
    data object Started : WireGuardStartResult
    data class PermissionRequired(val intent: Intent) : WireGuardStartResult
    data class Failed(val message: String) : WireGuardStartResult
}

/** Thin adapter around the official backend; no packet processing occurs in App code. */
class WireGuardTunnelController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private val operationMutex = Mutex()
    private var tunnel: LabProbeTunnel? = null
    private var tunnelProfileId: String = ""
    private var tunnelWorkspace: String = ""
    private var tunnelPrefs: AppPrefs? = null
    private val errorsByWorkspace = ConcurrentHashMap<String, String>()

    private fun errorFor(workspace: String): String = errorsByWorkspace[workspace].orEmpty()

    private fun workspaceKey(value: AppPrefs): String = value.hub + "|" + value.workspaceId

    suspend fun start(
        profile: WireGuardProfile,
        privateKey: String,
        operationPrefs: AppPrefs,
    ): WireGuardStartResult = withContext(Dispatchers.IO) {
        val operationWorkspace = workspaceKey(operationPrefs)
        val validation = wireGuardProfileError(profile, privateKey)
        if (validation.isNotBlank()) return@withContext WireGuardStartResult.Failed(validation)
        val permission = VpnService.prepare(appContext)
        if (permission != null) return@withContext WireGuardStartResult.PermissionRequired(permission)
        runCatching {
            val config = Config.parse(
                ByteArrayInputStream(
                    wireGuardQuickConfig(
                        profile,
                        privateKey,
                        (homeLanRouteCidrs(operationPrefs.wgHomeLanIpv4, operationPrefs.routerLanUrl) +
                            privateHubLanRouteCidrs(operationPrefs.hub)).distinct(),
                    ).toByteArray(Charsets.UTF_8)
                )
            )
            operationMutex.withLock {
                // Android has one VPN slot. Replacing a tunnel clears only its
                // owning router's saved active-profile marker.
                if (tunnel != null &&
                    (tunnelWorkspace != operationWorkspace || tunnelProfileId != profile.id)
                ) {
                    backend.setState(tunnel!!, Tunnel.State.DOWN, null)
                    tunnelPrefs?.wireGuardActiveProfileId = ""
                    tunnel = null
                    tunnelProfileId = ""
                    tunnelWorkspace = ""
                    tunnelPrefs = null
                }
                val next = tunnel ?: LabProbeTunnel(profile.id)
                backend.setState(next, Tunnel.State.UP, config)
                tunnel = next
                tunnelProfileId = profile.id
                tunnelWorkspace = operationWorkspace
                tunnelPrefs = operationPrefs
                operationPrefs.wireGuardActiveProfileId = profile.id
                errorsByWorkspace.remove(operationWorkspace)
            }
        }.fold(
            onSuccess = { WireGuardStartResult.Started },
            onFailure = { error ->
                val message = error.message ?: "WireGuard 启动失败"
                errorsByWorkspace[operationWorkspace] = message
                WireGuardStartResult.Failed(message)
            }
        )
    }

    suspend fun stop(operationPrefs: AppPrefs): WireGuardRuntimeStatus = withContext(Dispatchers.IO) {
        val operationWorkspace = workspaceKey(operationPrefs)
        operationMutex.withLock {
            if (tunnelWorkspace != operationWorkspace || tunnel == null) {
                operationPrefs.wireGuardActiveProfileId = ""
                return@withLock WireGuardRuntimeStatus()
            }
            runCatching { backend.setState(tunnel!!, Tunnel.State.DOWN, null) }
                .onSuccess {
                    operationPrefs.wireGuardActiveProfileId = ""
                    tunnel = null
                    tunnelProfileId = ""
                    tunnelWorkspace = ""
                    tunnelPrefs = null
                    errorsByWorkspace.remove(operationWorkspace)
                }
                .onFailure { errorsByWorkspace[operationWorkspace] = it.message ?: "WireGuard 停止失败" }
            readStatusFor(operationWorkspace)
        }
    }

    suspend fun status(operationPrefs: AppPrefs): WireGuardRuntimeStatus = withContext(Dispatchers.IO) {
        val operationWorkspace = workspaceKey(operationPrefs)
        operationMutex.withLock { readStatusFor(operationWorkspace) }
    }

    private fun readStatusFor(operationWorkspace: String): WireGuardRuntimeStatus {
        if (tunnelWorkspace != operationWorkspace) {
            return WireGuardRuntimeStatus(otherWorkspaceActive = tunnel != null)
        }
        val current = tunnel ?: return WireGuardRuntimeStatus(lastError = errorFor(operationWorkspace))
        return runCatching {
            val running = backend.getState(current) == Tunnel.State.UP
            val stats: Statistics = backend.getStatistics(current)
            val latestHandshake = stats.peers().maxOfOrNull { key -> stats.peer(key)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
            WireGuardRuntimeStatus(
                profileId = tunnelProfileId,
                running = running,
                receivedBytes = stats.totalRx(),
                sentBytes = stats.totalTx(),
                latestHandshakeAt = latestHandshake,
                lastError = errorFor(operationWorkspace),
            )
        }.getOrElse { error ->
            val message = error.message ?: "无法读取 WireGuard 状态"
            errorsByWorkspace[operationWorkspace] = message
            WireGuardRuntimeStatus(profileId = tunnelProfileId, lastError = message)
        }
    }

    companion object {
        @Volatile private var instance: WireGuardTunnelController? = null
        fun get(context: Context): WireGuardTunnelController = instance ?: synchronized(this) {
            instance ?: WireGuardTunnelController(context).also { instance = it }
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
        if (rule == null) {
            return@map store.markEndpointError(
                profile.id,
                WireGuardEndpointSource.STUN,
                "绑定的 STUN 规则不存在，请重新选择；旧地址不代表可用",
                profile.endpointBindingId,
            ) ?: profile
        }
        // 绑错规则是配置问题，要说；地址当下通不通不是配置问题，交给「连一下」裁决。
        if (rule.transportProtocol != "UDP" || !rule.usesRouterNativeMapping || rule.targetType == "router_self") {
            return@map store.markEndpointError(
                profile.id,
                WireGuardEndpointSource.STUN,
                "绑定的穿透规则不是路由器原生 UDP 映射，请重新选择",
                profile.endpointBindingId,
            ) ?: profile
        }
        // STUN 探测是瞬时状态，用它判死一个正在工作的地址，就会出现「能连上却提示不可用」。
        // 规则还没就绪 / 端口或网关对不上时：保留上次地址，并清掉更早版本留下的告警残留。
        val crossChecked = listenPort > 0 && routerIp.isNotBlank()
        val usable = rule.ready == true && rule.runtime.publicEndpoint.isNotBlank() &&
            (!crossChecked || (rule.targetPort == listenPort && rule.targetIpv4 == routerIp))
        if (!usable) {
            return@map if (profile.endpointUpdateError.isBlank()) profile
            else store.markEndpointError(profile.id, WireGuardEndpointSource.STUN, "", profile.endpointBindingId) ?: profile
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

internal data class WireGuardRouterPeer(
    val id: String,
    val name: String,
    val publicKey: String,
    val allowedIps: List<String>,
    /** True when an endpoint profile still owns this peer; false means it is an orphan. */
    val referenced: Boolean,
    /** When the Hub first recorded this peer, as ISO-8601 UTC; blank means unknown. */
    val createdAt: String = "",
)

internal fun routerPeersToJson(peers: List<WireGuardRouterPeer>): String = JSONArray().apply {
    peers.forEach {
        put(
            JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("publicKey", it.publicKey)
                .put("allowedIps", JSONArray(it.allowedIps))
                .put("referenced", it.referenced)
                .put("createdAt", it.createdAt),
        )
    }
}.toString()

internal fun decodeRouterPeers(raw: String): List<WireGuardRouterPeer> = runCatching {
    val array = JSONArray(raw.ifBlank { "[]" })
    (0 until array.length()).mapNotNull { index ->
        val row = array.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optString("id").trim()
        if (id.isBlank()) return@mapNotNull null
        WireGuardRouterPeer(
            id = id,
            name = row.optString("name").trim(),
            publicKey = row.optString("publicKey").trim(),
            allowedIps = jsonStringList(row.optJSONArray("allowedIps")),
            referenced = row.optBoolean("referenced", false),
            createdAt = row.optString("createdAt").trim(),
        )
    }
}.getOrDefault(emptyList())

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
    // 底层异常原文是英文（Software caused connection abort 之类），必须先翻成中文再拼进
    // 提示，否则用户看到的是一句没有上下文的英文。
    val reason = uiMessageZh(error.message).ifBlank { "上游服务暂不可用" }
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

/** Do not bootstrap a missing Hub document over a router that still has this managed peer. */
internal fun requireManagedWireGuardServerForExistingProfile(root: JSONObject, profile: WireGuardProfile) {
    if (profile.endpointSource != WireGuardEndpointSource.MANUAL &&
        profile.serverPublicKey.isNotBlank() && root.optJSONObject("server") == null) {
        throw IllegalStateException("Hub 缺少 WireGuard 网关配置；已阻止覆盖路由器现有隧道。请先核对或恢复网关配置")
    }
}

/** Compare only this managed peer and endpoint; the resolved public endpoint changes independently. */
internal fun wireGuardProvisionTargetMatches(root: JSONObject, profile: WireGuardProfile, clientPublicKey: String): Boolean {
    val server = root.optJSONObject("server") ?: return false
    if (root.optLong("revision", 0L) <= 0L) return false
    val desired = buildWireGuardServerPayload(root, profile, clientPublicKey)
    fun uniqueRow(rows: JSONArray?, id: String): JSONObject? {
        val matches = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
            .filter { it.optString("id") == id }
        return matches.singleOrNull()
    }
    val peerId = wireGuardPeerId(profile.id)
    val actualPeer = uniqueRow(server.optJSONArray("peers"), peerId) ?: return false
    val desiredPeer = uniqueRow(desired.optJSONArray("peers"), peerId) ?: return false
    val actualPeers = server.optJSONArray("peers") ?: return false
    if ((0 until actualPeers.length()).count { index ->
            actualPeers.optJSONObject(index)?.optString("publicKey") == clientPublicKey
        } != 1) return false
    if (actualPeer.optString("name") != desiredPeer.optString("name") ||
        actualPeer.optString("publicKey") != desiredPeer.optString("publicKey") ||
        jsonStringList(actualPeer.optJSONArray("allowedIps")).sorted() !=
            jsonStringList(desiredPeer.optJSONArray("allowedIps")).sorted() ||
        actualPeer.optInt("persistentKeepaliveSeconds", DEFAULT_WIREGUARD_KEEPALIVE) !=
            desiredPeer.optInt("persistentKeepaliveSeconds", DEFAULT_WIREGUARD_KEEPALIVE)) return false

    val endpointId = wireGuardEndpointProfileId(profile.id)
    val actualEndpoint = uniqueRow(server.optJSONArray("endpointProfiles"), endpointId) ?: return false
    val desiredEndpoint = uniqueRow(desired.optJSONArray("endpointProfiles"), endpointId) ?: return false
    if (actualEndpoint.optString("name") != desiredEndpoint.optString("name") ||
        actualEndpoint.optString("endpointSource") != desiredEndpoint.optString("endpointSource") ||
        !actualEndpoint.optBoolean("enabled", false) ||
        actualEndpoint.optInt("port") != desiredEndpoint.optInt("port")) return false
    return when (profile.endpointSource) {
        WireGuardEndpointSource.STUN -> actualEndpoint.optString("stunRuleId") == desiredEndpoint.optString("stunRuleId")
        WireGuardEndpointSource.DDNS -> actualEndpoint.optString("hostname").trimEnd('.').lowercase() ==
            desiredEndpoint.optString("hostname").trimEnd('.').lowercase()
        WireGuardEndpointSource.MANUAL -> false
    }
}

/** A lost status response after a PUT is pending verification, never permission to PUT again. */
internal suspend fun awaitWireGuardProvision(
    expected: WireGuardServerConfig,
    profile: WireGuardProfile,
    clientPublicKey: String,
    load: suspend () -> JSONObject,
    pause: suspend () -> Unit = { delay(1_000L) },
    attempts: Int = 6,
    initial: JSONObject? = null,
): WireGuardProvisionResult {
    require(expected.revision > 0L) { "Hub 未返回 WireGuard 配置版本" }
    var lastReadError: Exception? = null
    repeat(attempts.coerceAtLeast(1)) { attempt ->
        val latest = if (attempt == 0 && initial != null) initial else try {
            load().also { lastReadError = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastReadError = error
            null
        }
        if (latest != null) {
            val state = try {
                parseWireGuardServerState(latest)
            } catch (error: Exception) {
                lastReadError = error
                null
            }
            if (state != null && state.config.revision >= expected.revision) {
                if (!wireGuardProvisionTargetMatches(latest, profile, clientPublicKey)) {
                    throw WireGuardPendingVerificationException(
                        expected.revision,
                        "WireGuard 修改已提交（修订 " + expected.revision +
                            "），但 Hub 当前 peer 或端点已变化；请刷新核对，不要重复提交",
                    )
                }
                val publicKey = wireGuardServerPublicKey(latest)
                if (isWireGuardServerConfigApplied(state, expected) && publicKey.isNotBlank()) {
                    return WireGuardProvisionResult(
                        profile.copy(serverPublicKey = publicKey), expected.revision, state.agentRevision,
                        "Agent 已应用并返回服务端公钥",
                    )
                }
                wireGuardServerErrorForRevision(state, expected.revision).takeIf { it.isNotBlank() }?.let { error ->
                    throw WireGuardPendingVerificationException(
                        expected.revision,
                        "WireGuard 修改已提交（修订 " + expected.revision +
                            "），但 Agent 应用失败：" + error + "；请先核对，不要重复提交",
                    )
                }
            }
        }
        if (attempt < attempts - 1) pause()
    }
    val reason = lastReadError?.let { uiMessageZh(it.message).ifBlank { "Hub 状态暂不可读" } }
        ?: "Agent 尚未确认应用"
    throw WireGuardPendingVerificationException(
        expected.revision,
        "WireGuard 修改已提交（修订 " + expected.revision +
            "），但结果待核对：" + reason + "；请刷新核对，不要重复提交",
        lastReadError,
    )
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
        put("allowedIps", JSONArray(wireGuardPeerHostRoutes(profile.interfaceAddresses)))
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
    if (newProfile.endpointSource != WireGuardEndpointSource.MANUAL &&
        oldProfile.interfaceAddresses != newProfile.interfaceAddresses) {
        // Keep the old tunnel address valid until the phone has switched to its
        // new local config. Otherwise a Hub reachable only through this tunnel
        // becomes unreachable between the server PUT and the local restart.
        // Old server rows may have normalized 10.77.0.6/24 into the entire
        // 10.77.0.0/24. Use the phone's actual old address, not that broad row.
        val stagedIps = wireGuardPeerHostRoutes(oldProfile.interfaceAddresses + newProfile.interfaceAddresses)
        val peers = payload.getJSONArray("peers")
        for (index in 0 until peers.length()) {
            val row = peers.getJSONObject(index)
            if (row.optString("id") == ownedId) row.put("allowedIps", JSONArray(stagedIps))
        }
    }
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
    // Keep the post-submit check bounded even when the tunnel carrying Hub traffic stalls.
    private val verificationClient = OkHttpClient.Builder()
        .dns(CustomDns(prefs.hubDns))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = prefs.token.trim()
            if (token.isBlank()) throw HubAuthenticationException(0, "Hub API认证失败：APP_TOKEN 为空")
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer " + token).build())
        }
        .build()

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

    private fun getServerForVerification(): JSONObject {
        requireOriginalConnection()
        val response = hubApi.requestText("/api/wireguard/server", requestClient = verificationClient)
        requireOriginalConnection()
        return JSONObject(response)
    }

    suspend fun loadServerState(): WireGuardServerState = withContext(Dispatchers.IO) {
        parseWireGuardServerState(getServer())
    }

    suspend fun loadServerConfig(): WireGuardServerConfig = withContext(Dispatchers.IO) {
        parseWireGuardServerState(getServer()).config
    }

    suspend fun loadRouterLanIp(): String = withContext(Dispatchers.IO) { routerLanIp() }

    internal suspend fun loadRouterPeers(): List<WireGuardRouterPeer> = withContext(Dispatchers.IO) {
        val root = JSONObject(requestText("/api/wireguard/peers"))
        decodeRouterPeers(root.optJSONArray("peers")?.toString().orEmpty())
    }

    /** Shared router config: removing a tunnel here affects every client, hence the explicit UI. */
    internal suspend fun removeRouterPeer(peerId: String) {
        withContext(Dispatchers.IO) {
            requestText("/api/wireguard/peers/${java.net.URLEncoder.encode(peerId, "UTF-8")}", "DELETE")
            Unit
        }
    }

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
            var lastConflict: Throwable? = null
            for (attempt in 0 until 3) {
                val before = try {
                    getServer()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw IllegalStateException(
                        wireGuardMutationFailureMessage(WireGuardMutationStage.READ_BEFORE_SUBMIT, error), error,
                    )
                }
                requireManagedWireGuardServerForExistingProfile(before, profile)
                val state = parseWireGuardServerState(before)
                val server = state.config
                val updatedProfile = applyWireGuardServerConfig(profile, server.listenPort, server.mtu, server.listenPort)
                // The previous PUT may have succeeded even when its reply or Agent poll was lost.
                // An identical desired revision must be verified, not submitted again.
                if (wireGuardProvisionTargetMatches(before, updatedProfile, clientPublicKey)) {
                    val publicKey = wireGuardServerPublicKey(before)
                    if (isWireGuardServerConfigApplied(state, server) && publicKey.isNotBlank()) {
                        return@withLock WireGuardProvisionResult(
                            updatedProfile.copy(serverPublicKey = publicKey), server.revision,
                            state.agentRevision, "Agent 已应用并返回服务端公钥",
                        )
                    }
                    return@withLock awaitWireGuardProvision(
                        server, updatedProfile, clientPublicKey,
                        load = { getServerForVerification() }, initial = before,
                    )
                }

                val capability = before.optJSONObject("agentStatus")?.optJSONObject("capability")
                if (capability != null && capability.length() > 0 && !capability.optBoolean("provisioningReady", false)) {
                    throw IllegalStateException(capability.optString("error").ifBlank { "Agent 的 WireGuard 内核能力不可用" })
                }
                val payload = buildWireGuardServerPayload(before, updatedProfile, clientPublicKey)
                val response = try {
                    requestText("/api/wireguard/server", "PUT", payload.toString())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: HubHttpException) {
                    if (error.statusCode == 409 && attempt < 2) {
                        lastConflict = error
                        continue
                    }
                    if (isWireGuardSubmissionUncertain(error)) {
                        throw WireGuardPendingVerificationException(
                            null, wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error), error,
                        )
                    }
                    throw error
                } catch (error: Exception) {
                    if (isWireGuardSubmissionUncertain(error)) {
                        throw WireGuardPendingVerificationException(
                            null, wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, error), error,
                        )
                    }
                    throw error
                }
                val expected = try {
                    parseWireGuardServerState(JSONObject(response)).config
                } catch (error: Exception) {
                    throw WireGuardPendingVerificationException(
                        null, "Hub 已回复 WireGuard 修改，但返回内容无法核对；请刷新核对，不要重复提交", error,
                    )
                }
                if (expected.revision <= 0L) {
                    throw WireGuardPendingVerificationException(
                        null, "Hub 返回 WireGuard 修改结果，但未提供修订号；请刷新核对，不要重复提交",
                    )
                }
                return@withLock awaitWireGuardProvision(
                    expected, updatedProfile, clientPublicKey,
                    load = { getServerForVerification() },
                )
            }
            throw lastConflict ?: IllegalStateException("WireGuard 配置冲突，请刷新后重试")
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
