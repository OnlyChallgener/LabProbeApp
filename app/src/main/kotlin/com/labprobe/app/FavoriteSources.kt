package com.labprobe.app

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Endpoint bindings are scoped by the owning AppPrefs (Hub + router workspace). */
data class FavoriteEndpointBinding(val source: String, val id: String, val side: String) {
    fun toJson(): JSONObject = JSONObject().put("source", source).put("id", id).put("side", side)
}

internal fun parseFavoriteBinding(json: JSONObject?): FavoriteEndpointBinding? {
    json ?: return null
    val source = json.optString("source")
    val id = json.optString("id")
    val side = json.optString("side")
    return if (source in setOf("stun", "mapping", "webhook", "home") && id.isNotBlank() && side in setOf("lan", "wan"))
        FavoriteEndpointBinding(source, id, side) else null
}

internal data class FavoriteAddressOption(
    val binding: FavoriteEndpointBinding,
    val group: String,
    val name: String,
    val endpoint: String,
    val serviceType: String,
    val protocol: String = "",
)

internal fun favoriteSourceOptionLabel(option: FavoriteAddressOption): String {
    val parsed = parseQuickAccessEndpoint(option.endpoint)
    val port = parsed?.port ?: when (option.serviceType.uppercase(Locale.ROOT)) {
        "HTTP" -> 80
        "HTTPS" -> 443
        "SSH" -> 22
        "RDP" -> 3389
        else -> null
    }
    val address = parsed?.let { formatServiceHostPort(it.host, port) } ?: option.endpoint.substringAfter("://")
    return "${option.name} - $address"
}

private fun favoriteEndpointUri(raw: String): URI? =
    runCatching { URI(if (raw.contains("://")) raw else "tcp://$raw") }.getOrNull()

/** A suffix edit stays bound; editing the host or port opts out of automatic following. */
internal fun favoriteSameEndpointAuthority(previous: String, edited: String): Boolean {
    val old = favoriteEndpointUri(previous) ?: return false
    val next = favoriteEndpointUri(edited) ?: return false
    return !old.host.isNullOrBlank() && old.host.equals(next.host, ignoreCase = true) && old.port == next.port
}

internal fun favoritePairedRemoteOption(local: FavoriteAddressOption, options: List<FavoriteAddressOption>): FavoriteAddressOption? =
    options.firstOrNull { it.binding == local.binding.copy(side = "wan") }

internal fun favoriteFollowEndpoint(previous: String, next: String): String {
    val incoming = favoriteEndpointUri(next) ?: return previous
    if (incoming.host.isNullOrBlank() || isWildcardServiceEndpoint(next)) return previous
    val old = favoriteEndpointUri(previous)?.takeIf { !it.host.isNullOrBlank() } ?: return next
    val authority = (old.rawUserInfo?.let { "$it@" } ?: "") + incoming.rawAuthority.substringAfterLast('@')
    val query = old.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = old.rawFragment?.let { "#$it" }.orEmpty()
    val scheme = if (previous.contains("://")) old.scheme?.let { "$it://" }.orEmpty() else ""
    return scheme + authority + old.rawPath.orEmpty() + query + fragment
}

internal fun favoriteAddressOptions(
    prefs: AppPrefs,
    mappings: List<PortMapRule>,
    stuns: List<StunRule>,
    devices: List<DeviceItem> = emptyList(),
): List<FavoriteAddressOption> {
    val options = mutableListOf<FavoriteAddressOption>()
    fun add(source: String, id: String, side: String, group: String, name: String, raw: String, type: String, protocol: String = "") {
        if (raw.isBlank()) return
        val endpoint = cleanFavoriteEndpoint(raw, type)
        if (endpoint.isBlank() || isWildcardServiceEndpoint(endpoint)) return
        val uri = favoriteEndpointUri(endpoint) ?: return
        if (uri.host.isNullOrBlank()) return
        options += FavoriteAddressOption(FavoriteEndpointBinding(source, id, side), group, name, endpoint, type, protocol)
    }
    stuns.forEach { rule ->
        val name = rule.name.ifBlank { "STUN ${rule.serviceType}" }
        add("stun", rule.id, "lan", "STUN 规则", name, formatServiceHostPort(rule.targetIpv4, rule.targetPort), rule.serviceType, rule.transportProtocol)
        if (rule.ready) add("stun", rule.id, "wan", "STUN 规则", name, rule.runtime.publicEndpoint, rule.serviceType, rule.transportProtocol)
    }
    val status = runCatching { JSONObject(prefs.cacheStatus) }.getOrNull()
    val data = status?.optJSONObject("data") ?: status
    val wan6 = data?.optJSONObject("router")?.optString("wan6").orEmpty().substringBefore('/').trim().removeSurrounding("[", "]")
    // Only a concrete globally-routable IPv6 listener can be offered, never [::].
    val publicV6 = wan6.takeIf { it.contains(':') && it.firstOrNull() in listOf('2', '3') }
    mappings.forEach { rule ->
        val type = rule.serviceType.ifBlank { defaultPortMapServiceType(rule.targetPort, rule.transportProtocol) }
        val fallback = devices.firstOrNull { cleanMac(it.mac) == cleanMac(rule.targetMac) }?.pickIpv6()?.best.orEmpty()
        add("mapping", rule.id, "lan", "IPv6 端口映射", rule.name, favoriteLocalEndpoint(rule, fallback), type, rule.transportProtocol)
        if (publicV6 != null) add("mapping", rule.id, "wan", "IPv6 端口映射", rule.name, "[$publicV6]:${rule.listenPort}", type, rule.transportProtocol)
    }
    val events = parseEvents(prefs.cacheEvents)
    parseWebhookEndpoints(status, events).forEach { row ->
        val name = webhookDisplayText(row.name).trim().trimStart('*')
        val id = name.lowercase(Locale.ROOT)
        val type = favoriteProtocolForSource(name, row.address)
        add("webhook", id, "wan", "Webhook 推送", name, row.address, type)
    }
    decodeHomeFavoriteSources(prefs).forEach { (name, raw) ->
        add("home", name.trim().lowercase(Locale.ROOT), "wan", "首页 VPN / 穿透", name, raw, favoriteProtocolForSource(name, raw))
    }
    return options.distinctBy { it.binding }
}

private fun decodeHomeFavoriteSources(prefs: AppPrefs): List<Pair<String, String>> {
    val cached = decodeHomeVpnRows(prefs.cacheVpnRowsJson)
    if (cached.isNotEmpty()) return cached
    val status = runCatching { JSONObject(prefs.cacheStatus) }.getOrNull()
    val data = status?.optJSONObject("data") ?: status
    return buildVpnRowsForHome(data, "", parseEvents(prefs.cacheEvents), wireguardSupported = !isWorkspaceBe50(prefs))
}

private fun favoriteProtocolForSource(name: String, raw: String): String = when {
    name.contains("wireguard", true) -> "WireGuard"
    name.contains("openvpn", true) -> "OpenVPN"
    Regex("(?i)(^|[^a-z])ssh([^a-z]|$)").containsMatchIn(name) -> "SSH"
    raw.startsWith("https://", true) -> "HTTPS"
    else -> "HTTP"
}

/** Synchronization only updates existing opt-in bindings. It never creates or removes cards. */
internal fun followFavoriteAddressOptions(items: List<FavoriteShortcut>, options: List<FavoriteAddressOption>): List<FavoriteShortcut> =
    items.map { item ->
        fun resolve(binding: FavoriteEndpointBinding?, old: String): String =
            options.firstOrNull { binding != null && it.binding == binding }?.let { favoriteFollowEndpoint(old, it.endpoint) } ?: old
        val local = resolve(item.localBinding, item.localEndpoint.ifBlank { item.lanUrl })
        val remote = resolve(item.remoteBinding, item.remoteEndpoint.ifBlank { item.wanUrl })
        item.copy(localEndpoint = local, lanUrl = local, remoteEndpoint = remote, wanUrl = remote)
    }

internal fun favoriteBoundSourceIssue(
    item: FavoriteShortcut, mode: String, mappings: List<PortMapRule>, stuns: List<StunRule>?,
): String? {
    val binding = if (mode == "wan") item.remoteBinding else item.localBinding
    return when (binding?.source) {
        "stun" -> {
            if (stuns == null) "状态待同步" else {
                val source = stuns.firstOrNull { it.id == binding.id }
                when {
                    source == null -> "来源已删除"
                    !source.enabled -> "已停止"
                    mode == "wan" && !source.ready -> "穿透当前不可用"
                    else -> null
                }
            }
        }
        "mapping" -> {
            val source = mappings.firstOrNull { it.id == binding.id }
            when {
                source == null -> "来源已删除或待同步"
                !source.enabled -> "已停止"
                else -> null
            }
        }
        else -> null
    }
}
