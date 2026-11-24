package com.labprobe.app

import com.labprobe.app.ui.design.LabActionIcons

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.labprobe.app.labCardCombinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class FavoriteShortcut(
    val id: String,
    val title: String,
    val description: String,
    val iconType: String,
    val iconValue: String,
    val lanUrl: String,
    val wanUrl: String,
    val order: Int,
    val type: String = "manual",
    val mappingId: String? = null,
    val stunRuleId: String? = null,
    val ddnsRecordId: String? = null,
    val deviceId: String? = null,
    val localEndpoint: String = "",
    val remoteEndpoint: String = "",
    val serviceType: String = "",
    val localBinding: FavoriteEndpointBinding? = null,
    val remoteBinding: FavoriteEndpointBinding? = null,
    val iconAutomatic: Boolean = false,
)

private fun normalizeFavoriteType(value: String?): String = when {
    value?.trim()?.equals("mapping", ignoreCase = true) == true -> "mapping"
    value?.trim()?.equals("stun", ignoreCase = true) == true -> "stun"
    else -> "manual"
}

private fun optionalFavoriteId(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

/** Serializes every favorites read-modify-write, including STUN polling. */
private val favoriteShortcutPersistenceLock = Any()

private data class FavoriteShortcutDocument(
    val items: List<FavoriteShortcut>,
    val dismissedStunRuleIds: Set<String> = emptySet(),
)

internal fun parseFavoriteShortcutsJson(raw: String): List<FavoriteShortcut> {
    return parseFavoriteShortcutDocument(raw).items
}

private fun parseFavoriteShortcutDocument(raw: String): FavoriteShortcutDocument {
    val root = runCatching { JSONObject(raw) }.getOrNull()
    val array = root?.optJSONArray("items") ?: runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    val dismissed = root?.optJSONArray("dismissedStunRuleIds")
        ?.let { ids ->
            (0 until ids.length()).mapNotNull { index -> optionalFavoriteId(ids.optString(index)) }.toSet()
        }
        .orEmpty()
    val items = (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        if (id.isBlank() || title.isBlank()) return@mapNotNull null
        FavoriteShortcut(
            id = id,
            title = title,
            description = item.optString("description").trim(),
            iconType = item.optString("iconType", "builtin").trim().ifBlank { "builtin" },
            iconValue = item.optString("iconValue", "web").trim().ifBlank { "web" },
            lanUrl = item.optString("lanUrl").trim(),
            wanUrl = item.optString("wanUrl").trim(),
            order = item.optInt("order", index),
            type = normalizeFavoriteType(item.optString("type", "manual")),
            mappingId = if (item.has("mappingId") && !item.isNull("mappingId")) optionalFavoriteId(item.optString("mappingId")) else null,
            stunRuleId = if (item.has("stunRuleId") && !item.isNull("stunRuleId")) optionalFavoriteId(item.optString("stunRuleId")) else null,
            ddnsRecordId = if (item.has("ddnsRecordId") && !item.isNull("ddnsRecordId")) optionalFavoriteId(item.optString("ddnsRecordId")) else null,
            deviceId = if (item.has("deviceId") && !item.isNull("deviceId")) optionalFavoriteId(item.optString("deviceId")) else null,
            localEndpoint = item.optString("localEndpoint").trim(),
            remoteEndpoint = item.optString("remoteEndpoint").trim(),
            serviceType = item.optString("serviceType").trim(),
            localBinding = parseFavoriteBinding(item.optJSONObject("localBinding")),
            remoteBinding = parseFavoriteBinding(item.optJSONObject("remoteBinding")),
            iconAutomatic = item.optBoolean("iconAutomatic", false),
        )
    }.sortedBy { it.order }
    return FavoriteShortcutDocument(items = items, dismissedStunRuleIds = dismissed)
}

internal fun serializeFavoriteShortcutsJson(items: List<FavoriteShortcut>): String {
    val array = JSONArray()
    items.forEachIndexed { index, item ->
        val json = JSONObject()
            .put("id", item.id)
            .put("title", item.title)
            .put("description", item.description)
            .put("iconType", item.iconType)
            .put("iconValue", item.iconValue)
            .put("lanUrl", item.lanUrl)
            .put("wanUrl", item.wanUrl)
            .put("order", index)
            .put("type", normalizeFavoriteType(item.type))
        optionalFavoriteId(item.mappingId)?.let { json.put("mappingId", it) }
        optionalFavoriteId(item.stunRuleId)?.let { json.put("stunRuleId", it) }
        optionalFavoriteId(item.ddnsRecordId)?.let { json.put("ddnsRecordId", it) }
        optionalFavoriteId(item.deviceId)?.let { json.put("deviceId", it) }
        item.localBinding?.let { json.put("localBinding", it.toJson()) }
        item.remoteBinding?.let { json.put("remoteBinding", it.toJson()) }
        json.put("iconAutomatic", item.iconAutomatic)
        item.localEndpoint.trim().takeIf { it.isNotBlank() }?.let { json.put("localEndpoint", it) }
        item.remoteEndpoint.trim().takeIf { it.isNotBlank() }?.let { json.put("remoteEndpoint", it) }
        item.serviceType.trim().takeIf { it.isNotBlank() }?.let { json.put("serviceType", it) }
        array.put(json)
    }
    return array.toString()
}

private fun serializeFavoriteShortcutDocument(
    items: List<FavoriteShortcut>,
    dismissedStunRuleIds: Set<String>,
): String {
    if (dismissedStunRuleIds.isEmpty()) return serializeFavoriteShortcutsJson(items)
    return JSONObject()
        .put("items", JSONArray(serializeFavoriteShortcutsJson(items)))
        .put("dismissedStunRuleIds", JSONArray(dismissedStunRuleIds.sorted()))
        .toString()
}

data class FavoriteMappingResolution(
    val rule: PortMapRule?,
    val missing: Boolean,
)

data class FavoriteStunResolution(
    val rule: StunRule?,
    val known: Boolean,
) {
    val missing: Boolean get() = known && rule == null
}

internal fun resolveFavoriteMapping(favorite: FavoriteShortcut, rules: List<PortMapRule>): FavoriteMappingResolution {
    if (normalizeFavoriteType(favorite.type) != "mapping") return FavoriteMappingResolution(rule = null, missing = false)
    val id = optionalFavoriteId(favorite.mappingId)
    if (id == null) return FavoriteMappingResolution(rule = null, missing = true)
    return FavoriteMappingResolution(rule = rules.firstOrNull { it.id == id }, missing = rules.none { it.id == id })
}

private fun favoriteServiceType(rule: PortMapRule): String =
    rule.serviceType.ifBlank { defaultPortMapServiceType(rule.targetPort, rule.transportProtocol) }

private fun favoriteServiceScheme(serviceType: String): String = when (serviceType.trim().uppercase(Locale.ROOT)) {
    "HTTPS" -> "https"
    "HTTP" -> "http"
    "SSH" -> "ssh"
    "RDP" -> "rdp"
    "TELNET" -> "telnet"
    else -> "tcp"
}

private fun favoriteServiceScheme(rule: PortMapRule): String = favoriteServiceScheme(favoriteServiceType(rule))

/** Only client-addressable protocols can use Android's generic open action. */
private fun favoriteServiceSupportsDirectOpen(serviceType: String): Boolean = when (serviceType.trim().uppercase(Locale.ROOT)) {
    "" -> true
    else -> serviceSupportsQuickAccess(serviceType)
}

internal fun resolveFavoriteStun(favorite: FavoriteShortcut, rules: List<StunRule>?): FavoriteStunResolution {
    if (normalizeFavoriteType(favorite.type) != "stun") return FavoriteStunResolution(rule = null, known = false)
    val id = optionalFavoriteId(favorite.stunRuleId) ?: return FavoriteStunResolution(rule = null, known = rules != null)
    return FavoriteStunResolution(rule = rules?.firstOrNull { it.id == id }, known = rules != null)
}

private const val ROUTER_DDNS_ID_PREFIX = "router:"

private fun routerDdnsId(record: DdnsRecord): String =
    ROUTER_DDNS_ID_PREFIX + (record.serviceId.trim().ifBlank { record.domain.trim() })

private fun routerDdnsRecord(id: String?, records: List<DdnsRecord>): DdnsRecord? {
    val value = id?.trim().orEmpty()
    if (!value.startsWith(ROUTER_DDNS_ID_PREFIX)) return null
    val key = value.removePrefix(ROUTER_DDNS_ID_PREFIX)
    return records.firstOrNull { routerDdnsId(it) == value || it.serviceId == key || it.domain == key }
}

private fun routerDdnsHostname(record: DdnsRecord): String? {
    val raw = record.domain.trim()
    validFavoriteHostname(raw)?.let { return it }
    val at = raw.indexOf('@')
    if (at > 0 && at < raw.lastIndex) {
        val rr = raw.substring(0, at).trim().trim('.')
        val zone = raw.substring(at + 1).trim().trim('.')
        validFavoriteHostname("$rr.$zone")?.let { return it }
    }
    return null
}

private fun favoriteDdnsSupportsIpv4(record: LabProbeDdnsRecord): Boolean =
    record.enabled && record.recordTypes.any { it.equals("A", ignoreCase = true) }

private fun favoriteDdnsSupportsIpv4(record: DdnsRecord): Boolean =
    record.enabled && !record.useIpv6

private fun favoriteDdnsHostname(
    id: String?,
    snapshot: LabProbeDdnsSnapshot?,
    nativeDdnsRecords: List<DdnsRecord>,
): String? {
    val value = id?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return null
    return snapshot?.records?.firstOrNull { it.id == value }?.hostname?.let(::validFavoriteHostname)
        ?: routerDdnsRecord(value, nativeDdnsRecords)?.let(::routerDdnsHostname)
}

internal fun cleanFavoriteEndpoint(raw: String, serviceType: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val isHttps = serviceType.trim().equals("HTTPS", ignoreCase = true) ||
        (trimmed.startsWith("https://", ignoreCase = true) && !serviceType.trim().equals("HTTP", ignoreCase = true))
    if (isHttps) {
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }
    // All other protocols (HTTP, SSH, RDP, TCP, UDP, etc.): strictly NO protocol prefix!
    return if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
}

private fun favoriteIpv6Snapshot(vararg values: String): String? = values.asSequence()
    .map { it.trim().removePrefix("[").removeSuffix("]") }
    .filter { it.isNotBlank() }
    .mapNotNull { normalizeIpv6(it) }
    .firstOrNull { !isInvalidIpv6(it) }

internal fun favoriteLocalEndpoint(rule: PortMapRule, fallbackIpv6: String = ""): String = when {
    rule.mode == "6to4" && rule.targetIpv4.isNotBlank() -> {
        cleanFavoriteEndpoint("${rule.targetIpv4}:${rule.targetPort}", favoriteServiceType(rule))
    }
    rule.mode == "6to6" -> favoriteIpv6Snapshot(
        rule.targetIpv6Snapshot,
        rule.targetIpv6,
        fallbackIpv6,
    )?.let {
        cleanFavoriteEndpoint("[$it]:${rule.targetPort}", favoriteServiceType(rule))
    }.orEmpty()
    else -> ""
}

/** Keeps a manually-entered URL suffix while refreshing the mapping-owned host, port and scheme. */
internal fun syncFavoriteLocalEndpoint(existing: String, generated: String, targetPort: Int, scheme: String): String {
    if (existing.isBlank() || generated.isBlank()) return generated.ifBlank { existing }
    val generatedHost = runCatching { URI(generated) }.getOrNull()?.host ?: return generated
    return replaceFavoriteUrlHost(existing, generatedHost, targetPort, scheme) ?: generated
}

/** Builds the lightweight favorite payload for an existing mapping rule. */
internal fun favoriteFromPortMapRule(rule: PortMapRule, order: Int = 0, fallbackIpv6: String = ""): FavoriteShortcut {
    val serviceType = favoriteServiceType(rule)
    val local = favoriteLocalEndpoint(rule, fallbackIpv6)
    return FavoriteShortcut(
        id = "mapping-${rule.id}",
        title = rule.name.ifBlank { "IPv6 映射 ${rule.listenPort}" },
        description = "IPv6 映射 · $serviceType 服务",
        iconType = "builtin",
        iconValue = suggestedFavoriteIcon(rule.name, serviceType),
        lanUrl = local,
        wanUrl = "",
        order = order,
        type = "mapping",
        mappingId = rule.id,
        deviceId = optionalFavoriteId(rule.targetMac),
        localEndpoint = local,
        // [::] is a listener wildcard, never a remote client address. A linked
        // DDNS hostname or explicit external endpoint is required before opening.
        remoteEndpoint = "",
        serviceType = serviceType,
        iconAutomatic = true,
    )
}

internal fun upsertMappingFavorite(
    prefs: AppPrefs,
    rule: PortMapRule,
    devices: List<DeviceItem> = emptyList(),
    ddnsSnapshot: LabProbeDdnsSnapshot? = null,
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
): FavoriteShortcut {
    val current = prefs.favoriteShortcuts().toMutableList()
    val index = current.indexOfFirst { it.mappingId == rule.id }
    val fallbackIpv6 = devices.firstOrNull { cleanMac(it.mac) == cleanMac(rule.targetMac) }?.pickIpv6()?.best.orEmpty()
    val generated = favoriteFromPortMapRule(rule, if (index >= 0) current[index].order else current.size, fallbackIpv6)
    val saved = if (index >= 0) {
        val old = current[index]
        val desc = when {
            old.description.startsWith("IPv6 映射") -> old.description
            old.description.isBlank() -> "IPv6 映射 · ${generated.serviceType} 服务"
            else -> "IPv6 映射 · ${old.description}"
        }
        val remote = old.remoteEndpoint.takeUnless(::isWildcardServiceEndpoint).orEmpty()
        val wan = old.wanUrl.takeUnless(::isWildcardServiceEndpoint).orEmpty()
        val hostname = favoriteDdnsHostname(old.ddnsRecordId, ddnsSnapshot, nativeDdnsRecords)
        val syncedRemote = if (hostname != null && rule.listenPort in 1..65535) {
            cleanFavoriteEndpoint("$hostname:${rule.listenPort}", generated.serviceType)
        } else ""
        val local = syncFavoriteLocalEndpoint(
            old.localEndpoint.ifBlank { old.lanUrl },
            generated.localEndpoint,
            rule.targetPort,
            favoriteServiceScheme(rule),
        )
        old.copy(
            type = "mapping",
            description = desc,
            mappingId = rule.id,
            deviceId = generated.deviceId,
            localEndpoint = cleanFavoriteEndpoint(local, generated.serviceType),
            remoteEndpoint = cleanFavoriteEndpoint(syncedRemote.ifBlank { generated.remoteEndpoint }, generated.serviceType),
            serviceType = generated.serviceType,
            lanUrl = cleanFavoriteEndpoint(local, generated.serviceType),
            wanUrl = cleanFavoriteEndpoint(wan.ifBlank { generated.wanUrl }, generated.serviceType),
        ).also { current[index] = it }
    } else {
        current += generated
        generated
    }
    prefs.saveFavoriteShortcuts(current.mapIndexed { order, item -> item.copy(order = order) })
    return saved
}

/**
 * A STUN shortcut owns only its association and live remote endpoint.  The
 * rest is user-authored metadata and must survive routine endpoint refreshes.
 */
private fun favoriteFromStunRule(
    rule: StunRule,
    existing: FavoriteShortcut? = null,
    order: Int = 0,
    ddnsSnapshot: LabProbeDdnsSnapshot? = null,
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
): FavoriteShortcut? {
    if (existing == null && (!rule.ready || rule.runtime.publicEndpoint.isBlank())) return null
    val localAuthority = formatServiceHostPort(rule.targetIpv4, rule.targetPort)
    val local = cleanFavoriteEndpoint(localAuthority, rule.serviceType)
    val ddnsRecordId = existing?.ddnsRecordId
    val hostname = favoriteDdnsHostname(ddnsRecordId, ddnsSnapshot, nativeDdnsRecords)
    val parsedPublicEndpoint = parseServiceEndpoint(rule.runtime.publicEndpoint)
    val publicPort = rule.runtime.publicPort.takeIf { it in 1..65535 } ?: parsedPublicEndpoint?.port
    val publicAuthority = parsedPublicEndpoint?.let {
        formatServiceHostPort(it.host, publicPort)
    }.orEmpty()
    val publicEndpoint = cleanFavoriteEndpoint(publicAuthority, rule.serviceType)
    val previousRemoteForNewFavorite = existing?.remoteEndpoint?.ifBlank { existing.wanUrl }.orEmpty()
    val remote = if (rule.ready && publicEndpoint.isNotBlank()) {
        if (hostname != null) {
            val hostPort = if (publicPort != null) "$hostname:$publicPort" else hostname
            cleanFavoriteEndpoint(hostPort, rule.serviceType)
        } else {
            publicEndpoint
        }
    } else {
        cleanFavoriteEndpoint(previousRemoteForNewFavorite, rule.serviceType)
    }
    if (existing == null) {
        return FavoriteShortcut(
            id = "stun-${rule.id}",
            title = rule.name.ifBlank { "STUN ${rule.serviceType}" },
            description = "STUN 穿透 · ${rule.serviceType}",
            iconType = "builtin",
            iconValue = "server",
            lanUrl = local,
            wanUrl = remote,
            order = order,
            type = "stun",
            stunRuleId = rule.id,
            ddnsRecordId = ddnsRecordId,
            localEndpoint = local,
            remoteEndpoint = remote,
            serviceType = rule.serviceType,
        )
    }

    val previousRemote = existing.remoteEndpoint.ifBlank { existing.wanUrl }
    val liveRemote = if (rule.ready && publicAuthority.isNotBlank()) {
        val liveHost = hostname ?: parsedPublicEndpoint?.host.orEmpty()
        if (liveHost.isBlank()) previousRemote else {
            replaceFavoriteUrlHost(previousRemote, liveHost, publicPort)
                ?: cleanFavoriteEndpoint(formatServiceHostPort(liveHost, publicPort), existing.serviceType.ifBlank { rule.serviceType })
        }
    } else {
        previousRemote
    }
    // Do not regenerate title, icon, local address, scheme, path/query, or
    // DDNS association.  Only the confirmed live remote endpoint is mutable.
    return existing.copy(
        type = "stun",
        stunRuleId = rule.id,
        remoteEndpoint = liveRemote,
        wanUrl = liveRemote,
    )
}

internal fun reconcileStunFavoriteItems(
    current: List<FavoriteShortcut>,
    rules: List<StunRule>,
    ddnsSnapshot: LabProbeDdnsSnapshot? = null,
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
    dismissedStunRuleIds: Set<String> = emptySet(),
): List<FavoriteShortcut> {
    val ruleIds = rules.mapTo(hashSetOf()) { it.id }
    val updated = current.toMutableList()
    rules.forEach { rule ->
        val index = updated.indexOfFirst { it.stunRuleId == rule.id }
        val existing = updated.getOrNull(index)
        // Sources are opt-in: synchronization may never create a favorite.
        if (existing == null) return@forEach
        val synced = favoriteFromStunRule(rule, existing, updated.size, ddnsSnapshot, nativeDdnsRecords)
        if (synced != null) {
            if (index >= 0) updated[index] = synced else updated += synced
        }
    }
    return updated.mapIndexed { order, item -> item.copy(order = order) }
}

internal fun reconcileStunFavorites(
    prefs: AppPrefs,
    rules: List<StunRule>,
    ddnsSnapshot: LabProbeDdnsSnapshot? = null,
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
): Boolean {
    return synchronized(favoriteShortcutPersistenceLock) {
        val document = parseFavoriteShortcutDocument(prefs.favoriteShortcutsJson)
        val updated = reconcileStunFavoriteItems(
            current = document.items,
            rules = rules,
            ddnsSnapshot = ddnsSnapshot,
            nativeDdnsRecords = nativeDdnsRecords,
            dismissedStunRuleIds = document.dismissedStunRuleIds,
        )
        if (updated == document.items) return@synchronized false
        prefs.saveFavoriteShortcuts(updated)
        true
    }
}

internal fun removeStunFavorite(prefs: AppPrefs, ruleId: String, rememberDismissal: Boolean = false): Int {
    return synchronized(favoriteShortcutPersistenceLock) {
        val current = prefs.favoriteShortcuts()
        val updated = current.filterNot { it.stunRuleId == ruleId }
        if (updated.size != current.size || rememberDismissal) {
            prefs.saveFavoriteShortcuts(
                updated.mapIndexed { order, item -> item.copy(order = order) },
                dismissedStunRuleIds = if (rememberDismissal) setOf(ruleId) else emptySet(),
            )
        }
        current.size - updated.size
    }
}

internal fun resolveFavoriteLocalEndpoint(
    favorite: FavoriteShortcut,
    mappingRules: List<PortMapRule> = emptyList(),
    devices: List<DeviceItem> = emptyList(),
): String {
    val mapping = resolveFavoriteMapping(favorite, mappingRules).rule
    val fallbackIpv6 = mapping?.let { rule -> devices.firstOrNull { cleanMac(it.mac) == cleanMac(rule.targetMac) }?.pickIpv6()?.best }.orEmpty()
    return if (mapping != null) favoriteLocalEndpoint(mapping, fallbackIpv6).ifBlank {
        favorite.localEndpoint.ifBlank { favorite.lanUrl }
    } else {
        favorite.localEndpoint.ifBlank { favorite.lanUrl }
    }
}

internal fun favoriteServiceStatus(
    favorite: FavoriteShortcut,
    mode: String,
    mapping: FavoriteMappingResolution? = null,
    devices: List<DeviceItem> = emptyList(),
    stun: FavoriteStunResolution? = null,
): String {
    favoriteLinkedRuleStatus(favorite, mapping, stun)?.let { return it }
    val endpoint = if (mode == "wan") {
        if (normalizeFavoriteType(favorite.type) == "mapping") {
            favorite.remoteEndpoint
        } else {
            favorite.remoteEndpoint.ifBlank { favorite.wanUrl }
        }.takeUnless(::isWildcardServiceEndpoint).orEmpty()
    } else {
        mapping?.rule?.let { rule ->
            val fallback = devices.firstOrNull { cleanMac(it.mac) == cleanMac(rule.targetMac) }?.pickIpv6()?.best.orEmpty()
            favoriteLocalEndpoint(rule, fallback)
        }.orEmpty().ifBlank { favorite.localEndpoint.ifBlank { favorite.lanUrl } }
    }
    return when {
        mode == "wan" && (endpoint.isNotBlank() || (favorite.ddnsRecordId != null && mapping?.rule?.listenPort?.let { it in 1..65535 } == true)) -> "外网"
        mode != "wan" && endpoint.isNotBlank() -> "内网"
        else -> "当前不可达"
    }
}

internal fun favoriteLinkedRuleStatus(
    favorite: FavoriteShortcut,
    mapping: FavoriteMappingResolution? = null,
    stun: FavoriteStunResolution? = null,
): String? {
    if (mapping?.missing == true || stun?.missing == true) return "当前不可达"
    if (mapping?.rule?.enabled == false || stun?.rule?.enabled == false) return "已停止"
    if (stun?.known == false && normalizeFavoriteType(favorite.type) == "stun") return "状态待同步"
    if (stun?.rule != null && !stun.rule.ready) return "当前不可达"
    return null
}

private fun favoriteAccessStatus(report: ServiceAccessReport): String = when {
    !report.reachable -> "当前不可达"
    report.path == "内网直连" -> "内网"
    report.path == "外网访问" -> "外网"
    else -> "当前不可达"
}

private fun validFavoriteHostname(raw: String): String? {
    val value = raw.trim().trimEnd('.')
    if (value.isBlank() || value.length > 253 || value.any { it.isWhitespace() || it in "/@?#[]:" }) return null
    val labels = value.split('.')
    if (labels.any { label ->
            label.isBlank() || label.length > 63 || label.first() == '-' || label.last() == '-' ||
                label.any { ch -> !(ch.isLetterOrDigit() || ch == '-') }
        }) return null
    return value
}

private fun replaceFavoriteUrlHost(rawUrl: String, hostname: String, portOverride: Int? = null, schemeOverride: String? = null): String? {
    val source = runCatching { URI(normalizeFavoriteUrl(rawUrl)) }.getOrNull() ?: return null
    if (source.scheme.isNullOrBlank() || source.rawAuthority.isNullOrBlank()) return null
    val authority = buildString {
        source.rawUserInfo?.let { append(it).append('@') }
        if (hostname.contains(':') && !hostname.startsWith('[')) append('[').append(hostname).append(']') else append(hostname)
        val port = portOverride ?: source.port
        if (port >= 0) append(':').append(port)
    }
    return buildString {
        append(schemeOverride?.takeIf { it.isNotBlank() } ?: source.scheme).append("://").append(authority)
        source.rawPath?.let { append(it) }
        source.rawQuery?.let { append('?').append(it) }
        source.rawFragment?.let { append('#').append(it) }
    }
}

private fun replaceFavoriteUrlPort(rawUrl: String, port: Int, schemeOverride: String? = null): String? {
    val source = runCatching { URI(normalizeFavoriteUrl(rawUrl)) }.getOrNull() ?: return null
    val host = source.host?.takeIf { it.isNotBlank() } ?: return null
    return replaceFavoriteUrlHost(rawUrl, host, port, schemeOverride)
}

/** Updates only an already-linked favorite after its mapping changes. */
internal fun syncExistingMappingFavorite(
    prefs: AppPrefs,
    rule: PortMapRule,
    devices: List<DeviceItem> = emptyList(),
    ddnsSnapshot: LabProbeDdnsSnapshot? = null,
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
): Boolean {
    val current = prefs.favoriteShortcuts().toMutableList()
    val index = current.indexOfFirst { it.mappingId == rule.id }
    if (index < 0) return false
    val old = current[index]
    val fallbackIpv6 = devices.firstOrNull { cleanMac(it.mac) == cleanMac(rule.targetMac) }?.pickIpv6()?.best.orEmpty()
    val generatedLocal = favoriteLocalEndpoint(rule, fallbackIpv6)
    val serviceScheme = favoriteServiceScheme(rule)
    val existingLocal = old.localEndpoint.ifBlank { old.lanUrl }
    val local = syncFavoriteLocalEndpoint(existingLocal, generatedLocal, rule.targetPort, serviceScheme)
    val wan = old.wanUrl.takeIf { it.isNotBlank() }?.takeUnless(::isWildcardServiceEndpoint).orEmpty()
    val hostname = favoriteDdnsHostname(old.ddnsRecordId, ddnsSnapshot, nativeDdnsRecords)
    val syncedRemote = if (hostname != null && rule.listenPort in 1..65535) {
        cleanFavoriteEndpoint("$hostname:${rule.listenPort}", favoriteServiceType(rule))
    } else ""
    val desc = when {
        old.description.startsWith("IPv6 映射") -> old.description
        old.description.isBlank() -> "IPv6 映射 · ${favoriteServiceType(rule)} 服务"
        else -> "IPv6 映射 · ${old.description}"
    }
    val updated = old.copy(
        type = "mapping",
        description = desc,
        mappingId = rule.id,
        deviceId = optionalFavoriteId(rule.targetMac),
        localEndpoint = cleanFavoriteEndpoint(local, favoriteServiceType(rule)),
        lanUrl = cleanFavoriteEndpoint(local, favoriteServiceType(rule)),
        remoteEndpoint = cleanFavoriteEndpoint(syncedRemote, favoriteServiceType(rule)),
        wanUrl = cleanFavoriteEndpoint(wan, favoriteServiceType(rule)),
        serviceType = favoriteServiceType(rule),
    )
    if (updated == old) return false
    current[index] = updated
    prefs.saveFavoriteShortcuts(current.mapIndexed { order, item -> item.copy(order = order) })
    return true
}

/**
 * Resolves only the host portion of a linked DDNS favorite. Network/DNS checks
 * intentionally do not happen here; callers can use wanUrl unchanged on any
 * missing or invalid association.
 */
internal fun resolveFavoriteRemoteEndpoint(
    favorite: FavoriteShortcut,
    snapshot: LabProbeDdnsSnapshot?,
    mappingRules: List<PortMapRule> = emptyList(),
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
): String {
    val mappingResolution = resolveFavoriteMapping(favorite, mappingRules)
    val mapping = mappingResolution.rule
    val isMapping = normalizeFavoriteType(favorite.type) == "mapping"
    if (isMapping && mappingResolution.missing) return ""
    val raw = if (isMapping) favorite.remoteEndpoint else favorite.remoteEndpoint.ifBlank { favorite.wanUrl }
        .takeUnless(::isWildcardServiceEndpoint).orEmpty()
    val fallback = favorite.wanUrl.ifBlank { favorite.remoteEndpoint }
        .takeUnless(::isWildcardServiceEndpoint).orEmpty()
    val recordId = optionalFavoriteId(favorite.ddnsRecordId) ?: return if (isMapping) raw else fallback
    val hostname = snapshot?.records?.firstOrNull { it.id == recordId }?.hostname?.let(::validFavoriteHostname)
        ?: routerDdnsRecord(recordId, nativeDdnsRecords)?.domain?.let(::validFavoriteHostname)
        ?: return if (isMapping) raw else fallback
    val mappingPort = mapping?.listenPort?.takeIf { it in 1..65535 }
    if (raw.isBlank()) {
        val rule = mapping ?: return raw
        if (rule.listenPort !in 1..65535) return raw
        val type = favorite.serviceType.ifBlank { favoriteServiceType(rule) }
        return URI(favoriteServiceScheme(type), null, hostname, rule.listenPort, null, null, null).toString()
    }
    return if (isMapping) {
        replaceFavoriteUrlHost(raw, hostname, mappingPort, mapping?.let { favoriteServiceScheme(it) }) ?: raw
    } else {
        replaceFavoriteUrlHost(raw, hostname) ?: raw
    }
}

internal fun resolveFavoriteRemoteUrl(favorite: FavoriteShortcut, snapshot: LabProbeDdnsSnapshot?): String =
    resolveFavoriteRemoteEndpoint(favorite.copy(remoteEndpoint = favorite.wanUrl), snapshot)

internal fun withoutMappingFavorites(current: List<FavoriteShortcut>, mappingId: String): List<FavoriteShortcut> =
    current.filterNot { it.mappingId == mappingId }.mapIndexed { order, item -> item.copy(order = order) }

internal fun removeMappingFavorites(prefs: AppPrefs, mappingId: String): Int {
    val current = prefs.favoriteShortcuts()
    val updated = withoutMappingFavorites(current, mappingId)
    if (updated.size != current.size) prefs.saveFavoriteShortcuts(updated)
    return current.size - updated.size
}

internal fun removeMissingMappingFavorites(prefs: AppPrefs, ruleIds: Set<String>): Int {
    val current = prefs.favoriteShortcuts()
    val updated = current.filterNot { favorite ->
        optionalFavoriteId(favorite.mappingId)?.let { it !in ruleIds } == true
    }.mapIndexed { order, item -> item.copy(order = order) }
    if (updated.size != current.size) prefs.saveFavoriteShortcuts(updated)
    return current.size - updated.size
}

private data class FavoriteDraft(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val description: String = "",
    val iconType: String = "builtin",
    val iconValue: String = "web",
    val lanUrl: String = "",
    val wanUrl: String = "",
    val type: String = "manual",
    val mappingId: String? = null,
    val stunRuleId: String? = null,
    val ddnsRecordId: String? = null,
    val deviceId: String? = null,
    val localEndpoint: String = "",
    val remoteEndpoint: String = "",
    val serviceType: String = "",
    val localBinding: FavoriteEndpointBinding? = null,
    val remoteBinding: FavoriteEndpointBinding? = null,
    val iconAutomatic: Boolean = true,
)

private val favoriteImageClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .build()

internal fun isSourceLinkedFavorite(item: FavoriteShortcut): Boolean =
    item.localBinding != null || item.remoteBinding != null || item.type in setOf("mapping", "stun") ||
        !item.mappingId.isNullOrBlank() || !item.stunRuleId.isNullOrBlank() || item.id.startsWith("webhook-")

/** One-time, workspace-scoped reset explicitly requested for existing source-linked cards. */
fun AppPrefs.favoriteShortcuts(): List<FavoriteShortcut> = synchronized(favoriteShortcutPersistenceLock) {
    val document = parseFavoriteShortcutDocument(favoriteShortcutsJson)
    if (favoriteSourceResetVersion >= 1) return@synchronized document.items
    val kept = document.items.filterNot(::isSourceLinkedFavorite).mapIndexed { index, item -> item.copy(order = index) }
    favoriteShortcutsJson = serializeFavoriteShortcutDocument(kept, emptySet())
    favoriteSourceResetVersion = 1
    kept
}

fun AppPrefs.saveFavoriteShortcuts(
    items: List<FavoriteShortcut>,
    dismissedStunRuleIds: Set<String> = emptySet(),
) {
    synchronized(favoriteShortcutPersistenceLock) {
        if (favoriteSourceResetVersion < 1) favoriteShortcuts()
        val existing = parseFavoriteShortcutDocument(favoriteShortcutsJson)
        val activeStunIds = items.mapNotNull { optionalFavoriteId(it.stunRuleId) }.toSet()
        val dismissed = (existing.dismissedStunRuleIds + dismissedStunRuleIds) - activeStunIds
        favoriteShortcutsJson = serializeFavoriteShortcutDocument(items, dismissed)
    }
}

fun AppPrefs.syncWebhookFavoriteShortcuts(events: List<EventItem>): Int {
    val markers = events.asSequence()
        .mapNotNull(::webhookFavoriteMarker)
        .distinctBy { it.first.lowercase(Locale.ROOT) }
        .toList()
    if (markers.isEmpty()) return 0

    val current = favoriteShortcuts().toMutableList()
    var changes = 0
    markers.forEach { (title, address) ->
        val generatedWanUrl = webhookFavoriteOrigin(address)
        if (generatedWanUrl.isBlank()) return@forEach
        val generatedId = "webhook-${Integer.toHexString(title.lowercase(Locale.ROOT).hashCode())}"
        val index = current.indexOfFirst { it.id == generatedId }
        if (index >= 0) {
            val old = current[index]
            val previousRemote = old.remoteEndpoint.ifBlank { old.wanUrl }
            val wanUrl = mergeWebhookFavoriteUrl(generatedWanUrl, previousRemote)
            val description = old.description.takeUnless { it == "Webhook 自动同步" }.orEmpty()
            if (
                old.wanUrl != wanUrl ||
                old.remoteEndpoint != wanUrl || old.description != description
            ) {
                current[index] = old.copy(
                    description = description,
                    wanUrl = wanUrl,
                    remoteEndpoint = wanUrl,
                )
                changes++
            }

        }
    }
    if (changes > 0) saveFavoriteShortcuts(current)
    return changes
}

/**
 * Event-driven favorite refresh for the same VPN/STUN rows shown on Home.
 * No polling is introduced: callers invoke this only after a successful data sync.
 * Only webhook-managed favorites with an exact service-title match are updated,
 * preserving any user-entered path/query/fragment suffix.
 */
fun AppPrefs.syncHomeVpnFavoriteShortcuts(rows: List<Pair<String, String>>): Int {
    if (rows.isEmpty()) return 0
    val liveByTitle = LinkedHashMap<String, String>()
    rows.forEach { (title, address) ->
        val key = title.trim().lowercase(Locale.ROOT)
        val value = address.trim()
        if (key.isNotBlank() && value.isNotBlank()) liveByTitle[key] = value
    }
    if (liveByTitle.isEmpty()) return 0

    val current = favoriteShortcuts().toMutableList()
    var changes = 0
    current.forEachIndexed { index, old ->
        if (!old.id.startsWith("webhook-")) return@forEachIndexed
        val liveAddress = liveByTitle[old.title.trim().lowercase(Locale.ROOT)] ?: return@forEachIndexed
        val liveOrigin = webhookFavoriteOrigin(liveAddress)
        if (liveOrigin.isBlank()) return@forEachIndexed
        val previousRemote = old.remoteEndpoint.ifBlank { old.wanUrl }
        val syncedRemote = mergeWebhookFavoriteUrl(liveOrigin, previousRemote)
        if (syncedRemote.isBlank()) return@forEachIndexed
        val updated = old.copy(remoteEndpoint = syncedRemote, wanUrl = syncedRemote)
        if (updated != old) {
            current[index] = updated
            changes++
        }
    }
    if (changes > 0) saveFavoriteShortcuts(current)
    return changes
}

private fun webhookFavoriteMarker(event: EventItem): Pair<String, String>? {
    if (!event.type.contains("webhook", ignoreCase = true) &&
        listOf(event.newValue, event.oldValue, event.name, event.title).none { it.contains('*') }
    ) return null
    val text = listOf(event.newValue, event.oldValue, event.name, event.title).joinToString("\n")
    val direct = Regex("""\*([^*：:\r\n\"']{1,40})[：:]\s*([^\s\"'}\\,]+)""").find(text)
    val title = direct?.groupValues?.get(1)?.trim()?.trim('*', ' ', '#')
        ?: listOf(event.name, event.title).firstOrNull { it.trimStart().startsWith('*') }?.trim()?.trimStart('*')?.trim()
        ?: return null
    val address = (direct?.groupValues?.get(2)
        ?: listOf(event.newValue, event.oldValue).firstOrNull { value ->
            val clean = value.trim().trimStart('#')
            !clean.any(Char::isWhitespace) && (clean.contains('.') || clean.contains(':'))
        })?.trim()?.trimStart('#')?.trimEnd('.', ';', '；') ?: return null
    if (title.isBlank() || address.isBlank() || address.contains("{ipAddr}", ignoreCase = true)) return null
    if (!address.contains('.') && !address.contains(':')) return null
    return title to address
}

private fun webhookBuiltinIcon(title: String): String {
    val value = title.lowercase(Locale.ROOT)
    return when {
        value.contains("lucky") || value.contains("proxy") || value.contains("cloud") -> "cloud"
        value.contains("plex") || value.contains("media") || value.contains("影视") -> "media"
        value.contains("router") || value.contains("路由") -> "router"
        value.contains("download") || value.contains("qb") || value.contains("aria") || value.contains("下载") -> "download"
        value.contains("home") || value.contains("ha") || value.contains("家庭") -> "home"
        value.contains("nas") || value.contains("server") || value.contains("服务") -> "server"
        else -> "web"
    }
}

private fun normalizeFavoriteUrl(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return ""
    return if (value.contains("://")) value else "https://$value"
}

private fun webhookFavoriteOrigin(raw: String): String {
    val normalized = normalizeFavoriteUrl(raw)
    if (normalized.isBlank()) return ""
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return normalized
    val scheme = uri.scheme.orEmpty()
    val authority = uri.encodedAuthority.orEmpty()
    return if (scheme.isNotBlank() && authority.isNotBlank()) "$scheme://$authority" else normalized
}

private fun mergeWebhookFavoriteUrl(origin: String, previous: String): String {
    val old = normalizeFavoriteUrl(previous)
    if (old.isBlank()) return origin
    val uri = runCatching { Uri.parse(old) }.getOrNull() ?: return origin
    val suffix = buildString {
        uri.encodedPath.orEmpty().takeIf { it.isNotBlank() && it != "/" }?.let { append(it) }
        uri.encodedQuery?.takeIf { it.isNotBlank() }?.let { append('?').append(it) }
        uri.encodedFragment?.takeIf { it.isNotBlank() }?.let { append('#').append(it) }
    }
    return origin.trimEnd('/') + suffix
}

private fun FavoriteShortcut.openUrl(mode: String): String = when (mode) {
    "wan" -> wanUrl.ifBlank { lanUrl }
    else -> lanUrl.ifBlank { wanUrl }
}

internal fun favoriteAddressForCopy(rawAddress: String, serviceType: String = ""): String {
    return cleanFavoriteEndpoint(rawAddress, serviceType)
}

private fun openFavorite(context: Context, shortcut: FavoriteShortcut, mode: String) {
    val target = normalizeFavoriteUrl(shortcut.openUrl(mode))
    if (target.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context, "无法打开该地址") }
}

/** Copy is intentionally independent of the card's LAN/WAN display mode. */
internal fun favoriteExternalAddressForCopy(
    favorite: FavoriteShortcut,
    snapshot: LabProbeDdnsSnapshot? = null,
    mappingRules: List<PortMapRule> = emptyList(),
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
): String = favoriteAddressForCopy(resolveFavoriteRemoteEndpoint(favorite, snapshot, mappingRules, nativeDdnsRecords), favorite.serviceType)

@Composable
fun FavoritesScreen(
    prefs: AppPrefs,
    syncVersion: Int = 0,
    topNav: @Composable () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenDns: () -> Unit,
    onOpenPortMapping: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSsh: (String, Int) -> Unit = { _, _ -> },
    onOpenWireGuard: () -> Unit = {},
    onBeforeOpenShortcut: () -> Unit = {},
) {
    if (onBack != null) {
        BackHandler { onBack() }
    }
    var luckyOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mappingCache = remember(prefs.hub, prefs.token) { PortMappingMemoryCache.forScope(prefs.hub, prefs.token) }
    var mappingRules by remember(mappingCache, prefs.hubDns) { mutableStateOf(PortMappingRuleStore.load(context, prefs).rules) }
    val routerRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val mappingApi = remember(prefs.hub, prefs.token, prefs.hubDns) { PortMapApi(prefs) }
    val stunApi = remember(prefs.hub, prefs.token, prefs.hubDns) { StunApi(prefs) }
    val operations = remember(prefs.hub, prefs.token, prefs.hubDns) { NetworkOperationRegistry.get(prefs) }
    val operationState by operations.state.collectAsState()
    val latestOperationState by rememberUpdatedState(operationState)
    val ddnsResource by routerRepository.labProbeDdns.collectAsState()
    val nativeDdnsResource by routerRepository.ddns.collectAsState()
    var ddnsSnapshot by remember(routerRepository) { mutableStateOf(ddnsResource.value) }
    var nativeDdnsSnapshot by remember(routerRepository) { mutableStateOf(nativeDdnsResource.value.orEmpty()) }
    var pageRefreshing by remember { mutableStateOf(false) }
    val refreshMutex = remember { Mutex() }
    var mappingDevices by remember(mappingCache, prefs.hubDns) { mutableStateOf(emptyList<DeviceItem>()) }
    var stunRules by remember(mappingCache, prefs.hubDns) { mutableStateOf<List<StunRule>?>(null) }
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(if (prefs.favoriteNetworkMode == "wan") "wan" else "lan") }
    var query by rememberSaveable { mutableStateOf("") }
    var shortcuts by remember { mutableStateOf(prefs.favoriteShortcuts()) }
    var editing by remember { mutableStateOf<FavoriteShortcut?>(null) }
    var adding by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dropJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    val latestShortcuts by rememberUpdatedState(shortcuts)
    BackHandler(arranging) { arranging = false }
    val accessReports = remember { mutableStateMapOf<String, ServiceAccessReport>() }

    suspend fun refreshPage(force: Boolean) = refreshMutex.withLock {
        val before = latestOperationState
        if (before?.running == true) return@withLock
        if (force || stunRules == null) pageRefreshing = true
        try {
            coroutineScope {
                val mappings = async { runCatching { kotlinx.coroutines.withTimeout(4_000) { mappingApi.list() } }.getOrNull() }
                val stuns = async { runCatching { kotlinx.coroutines.withTimeout(4_000) { stunApi.list() } }.getOrNull() }
                val devices = async { runCatching { kotlinx.coroutines.withTimeout(4_000) { deviceApi.getDevices(false) } }.getOrNull() }
                val customDdns = async { routerRepository.refreshLabProbeDdns(force) }
                val nativeDdns = async { routerRepository.refreshDdns(force) }
                val mapping = mappings.await()
                val stun = stuns.await()
                val deviceRows = devices.await()
                customDdns.await(); nativeDdns.await()
                if (mappingCache !== PortMappingMemoryCache.forScope(prefs.hub, prefs.token)) return@coroutineScope
                val after = latestOperationState
                if (after?.running == true || after?.completedVersion != before?.completedVersion) return@coroutineScope
                // Commit a page snapshot together. No item loading/scale animations.
                if (mapping != null) {
                    val persisted = PortMappingRuleStore.load(context, prefs)
                    if (shouldAcceptPortMapSnapshot(mapping, mappingRules,
                            maxOf(mappingCache.rulesRevision, persisted.revision), mappingCache.snapshotRevision, persisted.hasDocument)) {
                        mappingRules = mapping.rules
                        mappingCache.rules = mapping.rules
                        mappingCache.rulesRevision = mapping.rulesRevision
                        mappingCache.rulesUpdatedAt = mapping.rulesUpdatedAt
                        mappingCache.snapshotRevision = mapping.revision
                        PortMappingRuleStore.save(context, prefs, mapping.rules, mapping.rulesRevision, mapping.rulesUpdatedAt)
                    }
                }
                if (deviceRows != null) mappingDevices = deviceRows
                if (stun?.rulesLoaded == true) stunRules = stun.rules
                ddnsSnapshot = routerRepository.labProbeDdns.value.value
                nativeDdnsSnapshot = routerRepository.ddns.value.value.orEmpty()
                mappingRules.forEach { syncExistingMappingFavorite(prefs, it, mappingDevices, ddnsSnapshot, nativeDdnsSnapshot) }
                stunRules?.let { reconcileStunFavorites(prefs, it, ddnsSnapshot, nativeDdnsSnapshot) }
                val current = prefs.favoriteShortcuts()
                val followed = followFavoriteAddressOptions(current, favoriteAddressOptions(prefs, mappingRules, stunRules.orEmpty(), mappingDevices))
                if (current != followed) prefs.saveFavoriteShortcuts(followed)
                shortcuts = prefs.favoriteShortcuts()
            }
        } finally { pageRefreshing = false }
    }
    LaunchedEffect(mappingApi, syncVersion, operationState?.completedVersion) {
        while (true) {
            refreshPage(false)
            delay(5_000)
        }
    }

    fun persist(items: List<FavoriteShortcut>) {
        val normalized = items.mapIndexed { index, item -> item.copy(order = index) }
        shortcuts = normalized
        prefs.saveFavoriteShortcuts(normalized)
    }

    val visible = remember(shortcuts, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) shortcuts else shortcuts.filter {
            it.title.contains(keyword, true) || it.description.contains(keyword, true) ||
                it.serviceType.contains(keyword, true) || it.localEndpoint.contains(keyword, true) ||
                it.remoteEndpoint.contains(keyword, true) || it.lanUrl.contains(keyword, true) || it.wanUrl.contains(keyword, true)
        }
    }

    if (luckyOpen) {
        LuckyManagementScreen(prefs, favoriteAddressOptions(prefs, mappingRules, stunRules.orEmpty(), mappingDevices)) { luckyOpen = false }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 720.dp) 3 else 2
        val searchWidth = when {
            maxWidth >= 500.dp -> 190.dp
            maxWidth >= 400.dp -> 150.dp
            else -> 120.dp
        }
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val baseStyle = LocalTextStyle.current
        val cardWidth = (maxWidth - LabV2.PageHorizontal * 2 - LabV2.CardGap * (columns - 1)) / columns
        val textWidth = with(density) { (cardWidth - 82.dp).roundToPx().coerceAtLeast(1) }
        val equalHeight = with(density) {
            (visible.maxOfOrNull { card ->
                val mapping = resolveFavoriteMapping(card, mappingRules)
                val stun = resolveFavoriteStun(card, stunRules)
                val address = if (mode == "wan") resolveFavoriteRemoteEndpoint(card, ddnsSnapshot, mappingRules, nativeDdnsSnapshot)
                    else resolveFavoriteLocalEndpoint(card, mappingRules, mappingDevices)
                val status = favoriteBoundSourceIssue(card, mode, mappingRules, stunRules) ?: favoriteLinkedRuleStatus(card, mapping, stun)
                    ?: accessReports[card.id]?.let(::favoriteAccessStatus) ?: favoriteServiceStatus(card, mode, mapping, mappingDevices, stun)
                val titleHeight = measurer.measure(card.title, baseStyle.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
                    constraints = Constraints(maxWidth = (textWidth - if (arranging) 16.dp.roundToPx() else 0).coerceAtLeast(1)), maxLines = 2, overflow = TextOverflow.Ellipsis).size.height
                val addressHeight = measurer.measure(favoriteAddressForCopy(address, card.serviceType).ifBlank { if (mode == "wan") "未设置外网地址" else "未设置内网地址" },
                    baseStyle.copy(fontSize = 12.sp, lineHeight = 17.sp), constraints = Constraints(maxWidth = textWidth)).size.height
                val statusHeight = if (status in setOf("内网", "外网")) 0 else measurer.measure(status,
                    baseStyle.copy(fontSize = 11.sp, lineHeight = 15.sp), constraints = Constraints(maxWidth = textWidth)).size.height + 5.dp.roundToPx()
                maxOf(48.dp.roundToPx(), titleHeight + addressHeight + 5.dp.roundToPx() + statusHeight) + 18.dp.roundToPx()
            } ?: 66.dp.roundToPx()).toDp()
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = LabV2.PageHorizontal, vertical = LabV2.PageTop),
            horizontalArrangement = Arrangement.spacedBy(LabV2.CardGap),
            verticalArrangement = Arrangement.spacedBy(LabV2.ListGap)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                    CompactPageHeader(
                        title = "收藏",
                        subtitle = "常用服务与网页入口",
                        onBack = onBack,
                        action = {
                            if (arranging) TextButton(onClick = { arranging = false }) { Text("完成", fontSize = 13.sp) }
                            IconButton(onClick = { scope.launch { refreshPage(true) } }, enabled = !pageRefreshing, modifier = Modifier.size(38.dp)) {
                                LabRefreshIcon(pageRefreshing, Modifier.size(19.dp), description = "刷新收藏")
                            }
                            CompactHeaderAction(Icons.Rounded.Add, "添加") { adding = true }
                            IconButton(onClick = { luckyOpen = true }, modifier = Modifier.size(34.dp)) {
                                FavoriteIcon("builtin", "lucky", 22)
                            }
                            Spacer(Modifier.width(4.dp))
                            CompactHeaderAction(Icons.Rounded.Person, "我的") { onOpenSettings() }
                        }
                    )
                    topNav()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(15.dp), color = LabV2.FieldSoft, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
                            Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                FavoriteModeButton(Icons.Rounded.Router, "内网", mode == "lan") {
                                    if (mode != "lan") accessReports.clear()
                                    mode = "lan"
                                    prefs.favoriteNetworkMode = "lan"
                                }
                                FavoriteModeButton(Icons.Rounded.Public, "外网", mode == "wan") {
                                    if (mode != "wan") accessReports.clear()
                                    mode = "wan"
                                    prefs.favoriteNetworkMode = "wan"
                                }
                            }
                        }
                        CompactTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.width(searchWidth),
                            placeholder = "搜索收藏",
                            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = LabV2.InkMuted) },
                            trailingIcon = if (query.isNotBlank()) ({
                                IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Rounded.Close, null, Modifier.size(16.dp), tint = LabV2.InkMuted)
                                }
                            }) else null
                        )
                        Surface(onClick = onOpenDns, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(15.dp), color = LabV2.Cyan.copy(alpha = .09f), border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Cyan.copy(alpha = .12f))) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Dns, "DNS 查询", Modifier.size(21.dp), tint = LabV2.Cyan) }
                        }
                        Surface(onClick = onOpenPortMapping, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(15.dp), color = LabV2.Primary.copy(alpha = .10f), border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Primary.copy(alpha = .12f))) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SwapHoriz, "端口映射", Modifier.size(21.dp), tint = LabV2.Primary) }
                        }
                    }
                }
            }

            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FavoriteEmptyState(hasSearch = query.isNotBlank()) { query = ""; adding = true }
                }
            } else {
                items(visible, key = { it.id }) { shortcut ->
                    val dragging = draggedId == shortcut.id
                    val mappingResolution = resolveFavoriteMapping(shortcut, mappingRules)
                    val stunResolution = resolveFavoriteStun(shortcut, stunRules)
                    FavoriteShortcutCard(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(280), placementSpec = if (dragging) null else tween(320), fadeOutSpec = tween(240)
                        ).zIndex(if (dragging) 1f else 0f).graphicsLayer {
                            val position = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == shortcut.id }?.offset
                            translationX = if (dragging) dragOrigin.x + dragDelta.x - (position?.x ?: dragOrigin.x.toInt()) else 0f
                            translationY = if (dragging) dragOrigin.y + dragDelta.y - (position?.y ?: dragOrigin.y.toInt()) else 0f
                        }.labCardHeldHalo(dragging).pointerInput(shortcut.id, query.isBlank()) {
                            if (query.isBlank()) detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dropJob?.cancel()
                                    arranging = true
                                    draggedId = shortcut.id
                                    dragDelta = Offset.Zero
                                    val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == shortcut.id }
                                    dragOrigin = item?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) } ?: Offset.Zero
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDelta += amount
                                    val y = dragOrigin.y + dragDelta.y
                                    val layout = gridState.layoutInfo
                                    val dragged = layout.visibleItemsInfo.firstOrNull { it.key == shortcut.id }
                                    val center = dragOrigin + dragDelta + Offset((dragged?.size?.width ?: 0) / 2f, (dragged?.size?.height ?: 0) / 2f)
                                    val hovered = layout.visibleItemsInfo.firstOrNull { info ->
                                        info.key != shortcut.id && latestShortcuts.any { it.id == info.key } &&
                                            center.x >= info.offset.x && center.x < info.offset.x + info.size.width &&
                                            center.y >= info.offset.y && center.y < info.offset.y + info.size.height
                                    }
                                    val from = latestShortcuts.indexOfFirst { it.id == shortcut.id }
                                    val to = latestShortcuts.indexOfFirst { it.id == hovered?.key }
                                    if (from >= 0 && to >= 0 && from != to) {
                                        val next = latestShortcuts.toMutableList()
                                        next.add(to, next.removeAt(from))
                                        persist(next)
                                    }
                                    val step = when {
                                        y < layout.viewportStartOffset + 80 -> -18f
                                        y > layout.viewportEndOffset - 160 -> 18f
                                        else -> 0f
                                    }
                                    if (step != 0f) scope.launch { gridState.scrollBy(step) }
                                },
                                onDragCancel = { draggedId = null; dragDelta = Offset.Zero },
                                onDragEnd = {
                                    val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == shortcut.id }
                                    val center = dragOrigin + dragDelta + Offset((item?.size?.width ?: 0) / 2f, (item?.size?.height ?: 0) / 2f)
                                    val target = gridState.layoutInfo.visibleItemsInfo.filter { info -> latestShortcuts.any { it.id == info.key } }
                                        .minByOrNull { info ->
                                            (center - Offset(info.offset.x + info.size.width / 2f, info.offset.y + info.size.height / 2f)).getDistanceSquared()
                                        }
                                    val from = latestShortcuts.indexOfFirst { it.id == shortcut.id }
                                    val to = latestShortcuts.indexOfFirst { it.id == target?.key }
                                    if (from >= 0 && to >= 0 && from != to) {
                                        val next = latestShortcuts.toMutableList()
                                        next.add(to, next.removeAt(from))
                                        persist(next)
                                    }
                                    // Keep the dragged card under the finger while the grid lays out;
                                    // then ease the remaining distance into its new cell.
                                    dropJob = scope.launch {
                                        withFrameNanos { }
                                        val placed = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == shortcut.id }
                                        val newOrigin = placed?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) } ?: dragOrigin
                                        val returning = Animatable(dragDelta, Offset.VectorConverter)
                                        returning.animateTo(newOrigin - dragOrigin, tween(320)) { dragDelta = value }
                                        if (draggedId == shortcut.id) { draggedId = null; dragDelta = Offset.Zero }
                                    }
                                }
                            )
                        },
                        arranging = arranging,
                        // The outer reorder detector owns long press. A nested
                        // combinedClickable long-click would consume the drag stream.
                        onArrange = if (query.isBlank()) null else ({ arranging = true }),
                        shortcut = shortcut,
                        minimumHeight = equalHeight,
                        mode = mode,
                        address = if (mode == "wan") resolveFavoriteRemoteEndpoint(shortcut, ddnsSnapshot, mappingRules, nativeDdnsSnapshot)
                            else resolveFavoriteLocalEndpoint(shortcut, mappingRules, mappingDevices),
                        columns = columns,
                        mapping = mappingResolution,
                        stun = stunResolution,
                        devices = mappingDevices,
                        accessReport = accessReports[shortcut.id],
                        sourceIssue = favoriteBoundSourceIssue(shortcut, mode, mappingRules, stunRules),
                        dragEnabled = query.isBlank(),
                        onOpen = {
                            onBeforeOpenShortcut()
                            mappingRules = PortMappingRuleStore.load(context, prefs).rules
                            val currentMapping = resolveFavoriteMapping(shortcut, mappingRules)
                            val currentStun = resolveFavoriteStun(shortcut, stunRules)
                            val linkedRule = currentMapping.rule
                            val serviceType = shortcut.serviceType.ifBlank { linkedRule?.let(::favoriteServiceType).orEmpty() }
                            val sourceIssue = favoriteBoundSourceIssue(shortcut, mode, mappingRules, stunRules)
                            if (latestOperationState?.running == true && latestOperationState?.targetId?.startsWith("stun:") == true) {
                                toast(context, "规则更新中，请稍后再试")
                            } else if (sourceIssue != null) {
                                toast(context, sourceIssue)
                            } else if (currentMapping.missing) {
                                toast(context, "关联映射已删除，收藏入口已失效")
                            } else if (currentStun.missing) {
                                toast(context, "关联 STUN 规则已删除，收藏入口已失效")
                            } else if (linkedRule?.enabled == false) {
                                toast(context, "关联映射已停止")
                            } else if (currentStun.rule?.enabled == false) {
                                toast(context, "关联 STUN 规则已停止")
                            } else if (normalizeFavoriteType(shortcut.type) == "stun" && !currentStun.known) {
                                toast(context, "STUN 状态尚未同步，请稍后再试")
                            } else if (currentStun.rule != null && !currentStun.rule.ready) {
                                toast(context, "STUN 穿透当前不可用")
                            } else if (!favoriteServiceSupportsDirectOpen(serviceType)) {
                                toast(context, "该服务请复制地址并使用对应客户端连接")
                            } else if (serviceType.equals("WireGuard", true)) {
                                val target = serviceQuickAccessTarget(serviceType, shortcut.openUrl(mode))
                                if (target == null) toast(context, "暂未取得可用的快捷访问地址")
                                else launchServiceQuickAccess(context, target, onOpenSsh, onOpenWireGuard)
                            } else if (shortcut.localEndpoint.isBlank() && shortcut.remoteEndpoint.isBlank() && shortcut.ddnsRecordId == null) {
                                if (serviceType.isBlank()) {
                                    openFavorite(context, shortcut, mode)
                                } else {
                                    val target = serviceQuickAccessTarget(serviceType, shortcut.openUrl(mode))
                                    if (target == null) toast(context, "暂未取得可用的快捷访问地址")
                                    else launchServiceQuickAccess(context, target, onOpenSsh, onOpenWireGuard)
                                }
                            } else {
                                scope.launch {
                                    val remoteEndpoint = resolveFavoriteRemoteEndpoint(shortcut, ddnsSnapshot, mappingRules, nativeDdnsSnapshot)
                                    val decision = chooseServiceAccess(
                                        localEndpoint = resolveFavoriteLocalEndpoint(shortcut, mappingRules, mappingDevices),
                                        remoteEndpoint = remoteEndpoint,
                                        mode = mode,
                                        serviceType = serviceType,
                                        transportProtocol = linkedRule?.transportProtocol?.ifBlank { "TCP" } ?: "TCP",
                                    )
                                    accessReports[shortcut.id] = decision.report
                                    decision.endpoint?.let { endpoint ->
                                        serviceQuickAccessTarget(serviceType, endpoint)?.let {
                                            launchServiceQuickAccess(context, it, onOpenSsh, onOpenWireGuard)
                                        } ?: toast(context, "暂未取得可用的快捷访问地址")
                                    } ?: run {
                                        val fallbackEndpoint = if (mode == "wan") remoteEndpoint
                                            else resolveFavoriteLocalEndpoint(shortcut, mappingRules, mappingDevices)
                                        if (serviceType.equals("HTTP", true) || serviceType.equals("HTTPS", true)) {
                                            if (fallbackEndpoint.isNotBlank()) {
                                                toast(context, decision.report.reason.ifBlank { "快速检测未确认，继续在浏览器尝试" })
                                                serviceQuickAccessTarget(serviceType, fallbackEndpoint)?.let {
                                                    launchServiceQuickAccess(context, it, onOpenSsh, onOpenWireGuard)
                                                }
                                            } else toast(context, decision.report.reason.ifBlank { "未配置可打开地址" })
                                        } else toast(context, decision.report.reason.ifBlank { "服务不可达" })
                                    }
                                }
                            }
                        },
                        onEdit = { editing = shortcut },
                        onCopyAddress = {
                            val address = favoriteExternalAddressForCopy(shortcut, ddnsSnapshot, mappingRules, nativeDdnsSnapshot)
                            if (address.isBlank()) toast(context, "未配置外网地址")
                            else copy(context, address)
                        },
                        onViewMapping = {
                            if (resolveFavoriteMapping(shortcut, mappingRules).missing) toast(context, "关联映射不存在") else onOpenPortMapping()
                        },
                        onDelete = {
                            if (normalizeFavoriteType(shortcut.type) == "stun") {
                                val ruleId = optionalFavoriteId(shortcut.stunRuleId)
                                if (ruleId != null) {
                                    removeStunFavorite(prefs, ruleId, rememberDismissal = true)
                                    shortcuts = prefs.favoriteShortcuts()
                                } else {
                                    persist(shortcuts.filterNot { it.id == shortcut.id })
                                }
                            } else {
                                persist(shortcuts.filterNot { it.id == shortcut.id })
                            }
                        },
                        onMoveBy = { delta ->
                            val from = shortcuts.indexOfFirst { it.id == shortcut.id }
                            if (from >= 0) {
                                val to = (from + delta).coerceIn(0, shortcuts.lastIndex)
                                if (to != from) {
                                    val next = shortcuts.toMutableList()
                                    next.add(to, next.removeAt(from))
                                    persist(next)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (adding || editing != null) {
        FavoriteEditorSheet(
            existing = editing,
            ddnsRecords = ddnsSnapshot?.records.orEmpty(),
            nativeDdnsRecords = nativeDdnsSnapshot,
            mappingRules = mappingRules,
            addressOptions = favoriteAddressOptions(prefs, mappingRules, stunRules.orEmpty(), mappingDevices),
            onDismiss = { adding = false; editing = null },
            onSave = { saved ->
                val oldIndex = shortcuts.indexOfFirst { it.id == saved.id }
                val next = shortcuts.toMutableList()
                if (oldIndex >= 0) next[oldIndex] = saved.copy(order = oldIndex) else next += saved.copy(order = next.size)
                persist(next)
                adding = false
                editing = null
            }
        )
    }
}

@Composable
private fun CompactHeaderAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.size(38.dp), shape = CircleShape, color = LabV2.Field, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(19.dp), tint = LabV2.Primary) }
    }
}

@Composable
private fun FavoriteModeButton(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = if (selected) LabV2.Primary else Color.Transparent) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(20.dp), tint = if (selected) Color.White else LabV2.InkMuted)
        }
    }
}

@Composable
private fun FavoriteListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = LabV2.CompactCardShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabV2.Border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            content = content
        )
    }
}

@Composable
internal fun FavoriteShortcutCard(
    shortcut: FavoriteShortcut,
    mode: String,
    modifier: Modifier = Modifier,
    arranging: Boolean = false,
    onArrange: (() -> Unit)? = null,
    minimumHeight: Dp = 0.dp,
    address: String = if (mode == "wan") shortcut.remoteEndpoint.ifBlank { shortcut.wanUrl } else shortcut.localEndpoint.ifBlank { shortcut.lanUrl },
    columns: Int,
    mapping: FavoriteMappingResolution,
    stun: FavoriteStunResolution,
    devices: List<DeviceItem> = emptyList(),
    accessReport: ServiceAccessReport?,
    sourceIssue: String? = null,
    dragEnabled: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onCopyAddress: () -> Unit,
    onViewMapping: () -> Unit,
    onDelete: () -> Unit,
    onMoveBy: (Int) -> Unit,
) {
    val onAddressClick = { if (!arranging) { onCopyAddress(); onOpen() } }
    val status = sourceIssue ?: favoriteLinkedRuleStatus(shortcut, mapping, stun)
        ?: accessReport?.let(::favoriteAccessStatus)
        ?: favoriteServiceStatus(shortcut, mode, mapping, devices, stun)
    Box(modifier.fillMaxWidth().testTag("favorite-card-${shortcut.id}")) {
        FavoriteListCard(Modifier.fillMaxWidth().heightIn(min = minimumHeight).labCardCombinedClickable(
            onClick = onAddressClick, onLongClick = onArrange, onLongClickLabel = "整理收藏")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).labCardCombinedClickable(
                    halo = false, onClick = { if (!arranging) onEdit() }, onClickLabel = "编辑收藏", onLongClick = onArrange), contentAlignment = Alignment.Center) {
                    FavoriteIcon(shortcut.iconType, shortcut.iconValue, 42)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).labCardCombinedClickable(
                    halo = false, onClick = onAddressClick, onClickLabel = "复制外网地址并跳转", onLongClick = onArrange),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(shortcut.title, modifier = Modifier.padding(end = if (arranging) 16.dp else 0.dp), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                        color = LabV2.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(favoriteAddressForCopy(address, shortcut.serviceType).ifBlank { if (mode == "wan") "未设置外网地址" else "未设置内网地址" },
                        fontSize = 12.sp, lineHeight = 17.sp, color = LabV2.InkMuted)
                    if (status !in setOf("内网", "外网")) {
                        Text(status, fontSize = 11.sp, lineHeight = 15.sp, color = LabV2.Amber)
                    }
                }
            }
        }
        if (arranging) {
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                Icon(Icons.Rounded.Close, "移除收藏", Modifier.size(15.dp), tint = LabV2.InkMuted)
            }
        }
    }
}

@Composable
private fun FavoriteEmptyState(hasSearch: Boolean, onAction: () -> Unit) {
    FavoriteListCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            LabV2ToolIcon(if (hasSearch) Icons.Rounded.SearchOff else Icons.Rounded.Bookmarks, LabV2.Primary, size = 48)
            Text(if (hasSearch) "没有匹配的收藏" else "还没有收藏", fontSize = 14.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
            Text(if (hasSearch) "清除搜索后继续浏览" else "添加常用服务或网页入口", fontSize = 10.5.sp, color = LabV2.InkMuted)
            TextButton(onClick = onAction) { Text(if (hasSearch) "清除并添加" else "添加收藏", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
internal fun FavoriteIcon(type: String, iconValue: String, size: Int) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, type, iconValue) {
        this.value = if (type == "local" || type == "url") {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = if (type == "local") {
                        File(iconValue).takeIf { it.isFile }?.readBytes()
                    } else {
                        val request = Request.Builder().url(normalizeFavoriteUrl(iconValue)).build()
                        favoriteImageClient.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.bytes() else null }
                    }
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }.getOrNull()
            }
        } else null
    }
    if (type == "builtin" && FavoritePresetIcon(iconValue, size)) return
    val shape = RoundedCornerShape((size * .22f).dp)
    Box(Modifier.size(size.dp).clip(shape).background(LabV2.Primary.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            val (icon, color) = favoriteBuiltinIcon(if (type == "builtin") iconValue else "web")
            Icon(icon, null, Modifier.size((size * .52f).dp), tint = color)
        }
    }
}

private fun favoriteBuiltinIcon(value: String): Pair<ImageVector, Color> = when (value) {
    "router" -> Icons.Rounded.Router to Color(0xFF2563EB)
    "server" -> Icons.Rounded.Storage to Color(0xFF64748B)
    "media" -> Icons.Rounded.PlayCircle to Color(0xFF0EA5E9)
    "cloud" -> Icons.Rounded.Cloud to Color(0xFF0EA5E9)
    "home" -> Icons.Rounded.Home to Color(0xFF16A34A)
    "download" -> Icons.Rounded.Download to Color(0xFF14B8A6)
    else -> Icons.Rounded.Language to LabV2.Primary
}

@Composable
internal fun FavoriteEditorSheet(
    existing: FavoriteShortcut?,
    ddnsRecords: List<LabProbeDdnsRecord>,
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
    mappingRules: List<PortMapRule> = emptyList(),
    addressOptions: List<FavoriteAddressOption> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (FavoriteShortcut) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(existing?.id) { mutableStateOf(existing?.let {
        FavoriteDraft(id = it.id, title = it.title, description = it.description,
            iconType = it.iconType, iconValue = it.iconValue, iconAutomatic = it.iconAutomatic,
            lanUrl = it.lanUrl, wanUrl = it.wanUrl, type = it.type, mappingId = it.mappingId,
            stunRuleId = it.stunRuleId, ddnsRecordId = it.ddnsRecordId, deviceId = it.deviceId,
            localEndpoint = it.localEndpoint.ifBlank { it.lanUrl }, remoteEndpoint = it.remoteEndpoint.ifBlank { it.wanUrl },
            serviceType = it.serviceType, localBinding = it.localBinding, remoteBinding = it.remoteBinding)
    } ?: FavoriteDraft()) }
    var error by remember { mutableStateOf("") }
    var iconMenu by remember { mutableStateOf(false) }
    var remoteExplicit by remember(existing?.id) { mutableStateOf(
        draft.remoteEndpoint.isNotBlank() && (draft.localBinding == null || draft.remoteBinding != draft.localBinding?.copy(side = "wan"))) }

    fun update(next: FavoriteDraft) {
        draft = if (next.iconAutomatic && next.iconType == "builtin") next.copy(iconValue = suggestedFavoriteIcon(next.title, next.serviceType)) else next
    }
    fun choose(option: FavoriteAddressOption, side: String) {
        var next = draft.copy(title = draft.title.ifBlank { option.name }, serviceType = draft.serviceType.ifBlank { option.serviceType },
            type = "manual", mappingId = null, stunRuleId = null)
        if (side == "lan") {
            next = next.copy(localEndpoint = option.endpoint, localBinding = option.binding)
            val remote = favoritePairedRemoteOption(option, addressOptions)
            if (!remoteExplicit && remote != null) next = next.copy(
                remoteEndpoint = favoriteFollowEndpoint(draft.remoteEndpoint, remote.endpoint), remoteBinding = remote.binding, ddnsRecordId = null)
        } else {
            remoteExplicit = true
            next = next.copy(remoteEndpoint = option.endpoint, remoteBinding = option.binding, ddnsRecordId = null)
        }
        update(next)
    }
    LaunchedEffect(addressOptions) {
        val saved = FavoriteShortcut(draft.id, draft.title, draft.description, draft.iconType, draft.iconValue,
            draft.localEndpoint, draft.remoteEndpoint, 0, localEndpoint = draft.localEndpoint,
            remoteEndpoint = draft.remoteEndpoint, localBinding = draft.localBinding, remoteBinding = draft.remoteBinding)
        val followed = followFavoriteAddressOptions(listOf(saved), addressOptions).single()
        draft = draft.copy(localEndpoint = followed.localEndpoint, remoteEndpoint = followed.remoteEndpoint)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching { copyFavoriteImage(context, uri) }
                .onSuccess { path -> update(draft.copy(iconType = "local", iconValue = path, iconAutomatic = false)); error = "" }
                .onFailure { error = it.message ?: "图片导入失败" }
        }
    }
    fun save() {
        val lan = cleanFavoriteEndpoint(draft.localEndpoint, draft.serviceType)
        val wan = cleanFavoriteEndpoint(draft.remoteEndpoint, draft.serviceType)
        error = when {
            draft.title.isBlank() -> "请填写名称"
            lan.isBlank() && wan.isBlank() -> "请至少填写一个访问入口"
            draft.iconType in setOf("url", "local") && draft.iconValue.isBlank() -> "请选择或填写图标"
            else -> ""
        }
        if (error.isNotBlank()) return
        onSave(FavoriteShortcut(draft.id, draft.title.trim(), draft.description.trim(), draft.iconType, draft.iconValue,
            lan, wan, existing?.order ?: 0, draft.type, draft.mappingId, draft.stunRuleId, draft.ddnsRecordId,
            draft.deviceId, lan, wan, draft.serviceType.trim(), draft.localBinding, draft.remoteBinding, draft.iconAutomatic))
    }
    LabEditorDialog(title = if (existing == null) "添加收藏" else "编辑收藏", onDismiss = onDismiss, actions = {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 40.dp), shape = LabV2.ButtonShape) { Text("取消") }
        Button(onClick = ::save, modifier = Modifier.weight(1f).heightIn(min = 40.dp), shape = LabV2.ButtonShape) { Text("保存") }
    }) {
        FavoriteEditorField("名称", draft.title, { update(draft.copy(title = it)) }, "例如：绿联 NAS")
        FavoriteEditorField("描述", draft.description, { update(draft.copy(description = it)) }, "简短说明", multiline = true)
        FavoriteAddressField("内网地址", "lan", draft.localEndpoint, draft.localBinding, addressOptions,
            onChange = { update(draft.copy(localEndpoint = it, localBinding = null, type = "manual", mappingId = null, stunRuleId = null)) },
            onPick = { choose(it, "lan") }, onDetach = { draft = draft.copy(localBinding = null) })
        FavoriteAddressField("外网地址", "wan", draft.remoteEndpoint, draft.remoteBinding, addressOptions,
            onChange = {
                val suffixOnly = favoriteSameEndpointAuthority(draft.remoteEndpoint, it)
                if (!suffixOnly) remoteExplicit = true
                update(draft.copy(remoteEndpoint = it, remoteBinding = draft.remoteBinding.takeIf { _ -> suffixOnly },
                    ddnsRecordId = draft.ddnsRecordId.takeIf { _ -> suffixOnly }, type = "manual", mappingId = null, stunRuleId = null))
            },
            onPick = { choose(it, "wan") }, onDetach = { remoteExplicit = true; draft = draft.copy(remoteBinding = null) })
        FavoriteEditorField("协议类型", draft.serviceType, { update(draft.copy(serviceType = it)) }, "HTTP / HTTPS / SSH / RDP")
        if (ddnsRecords.isNotEmpty() || nativeDdnsRecords.isNotEmpty()) {
            val choices = ddnsRecords.map { it.id to it.hostname } + nativeDdnsRecords.mapNotNull { row -> routerDdnsHostname(row)?.let { routerDdnsId(row) to it } }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("关联域名", fontSize = 12.sp, color = LabV2.InkMuted)
                CompactDropdown(
                    value = favoriteDdnsHostname(draft.ddnsRecordId, LabProbeDdnsSnapshot(records = ddnsRecords), nativeDdnsRecords) ?: "不关联",
                    options = listOf("不关联") + choices.map { it.second }.filter { it.isNotBlank() }.distinct(),
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = { host ->
                        val id = choices.firstOrNull { it.second == host }?.first
                        val next = if (id == null) draft.remoteEndpoint else replaceFavoriteUrlHost(draft.remoteEndpoint, host) ?: draft.remoteEndpoint
                        draft = draft.copy(ddnsRecordId = id, remoteEndpoint = next)
                    },
                )
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("图标", fontSize = 12.sp, color = LabV2.InkMuted, modifier = Modifier.weight(1f))
                TextButton(onClick = { update(draft.copy(iconType = "builtin", iconAutomatic = true)) },
                    modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text(if (draft.iconAutomatic) "自动匹配中" else "自动匹配", fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FavoriteIcon(draft.iconType, draft.iconValue, 36)
                Box(Modifier.weight(1f)) {
                    val iconLabel = when {
                        draft.iconAutomatic -> "自动匹配"
                        draft.iconType == "url" -> "图片链接"
                        draft.iconType == "local" -> "已上传图标"
                        else -> favoriteIconPresets.firstOrNull { it.id == draft.iconValue }?.label
                            ?: when (draft.iconValue) { "router" -> "路由器"; "cloud" -> "云服务"; else -> "通用网页" }
                    }
                    Surface(onClick = { iconMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                        shape = LabPopupShapes.Field, color = LabV2.FieldSoft, border = BorderStroke(1.dp, LabV2.BorderStrong)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(iconLabel, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ArrowDropDown, "选择图标", Modifier.size(18.dp), tint = LabV2.InkMuted)
                        }
                    }
                    LabDropdownMenu(expanded = iconMenu, onDismissRequest = { iconMenu = false }, modifier = Modifier.heightIn(max = 320.dp).widthIn(max = 300.dp)) {
                        favoriteIconPresets.forEach { preset ->
                            LabDropdownMenuItem(text = { Text(preset.label) }, onClick = {
                                update(draft.copy(iconType = "builtin", iconValue = preset.id, iconAutomatic = false)); iconMenu = false
                            })
                        }
                        listOf("web" to "通用网页", "cloud" to "云服务").forEach { (id, label) ->
                            LabDropdownMenuItem(text = { Text(label) }, onClick = { update(draft.copy(iconType = "builtin", iconValue = id, iconAutomatic = false)); iconMenu = false })
                        }
                        LabDropdownMenuItem(text = { Text("图片链接") }, onClick = {
                            update(draft.copy(iconType = "url", iconValue = if (draft.iconType == "url") draft.iconValue else "", iconAutomatic = false)); iconMenu = false
                        })
                    }
                }
                TextButton(onClick = { imagePicker.launch("image/*") }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("上传", fontSize = 12.sp)
                }
            }
        }
        if (draft.iconType == "url") {
            FavoriteEditorField("图片网址", draft.iconValue, { update(draft.copy(iconValue = it)) }, "https://…")
        }
        if (error.isNotBlank()) Text(error, color = LabV2.Red, fontSize = 13.sp)
    }
}

@Composable
private fun FavoriteEditorField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, multiline: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 12.sp, color = LabV2.InkMuted, fontWeight = FontWeight.Medium)
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = 1, maxLines = if (multiline) 3 else 1,
            shape = LabPopupShapes.Field, textStyle = LabTypography.DialogBody,
            placeholder = { Text(placeholder, fontSize = 13.sp) },
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = LabV2.BorderStrong, focusedBorderColor = LabV2.Primary,
                unfocusedContainerColor = LabV2.FieldSoft, focusedContainerColor = LabV2.FieldSoft))
    }
}

@Composable
private fun FavoriteAddressField(
    label: String, side: String, value: String, binding: FavoriteEndpointBinding?, options: List<FavoriteAddressOption>,
    onChange: (String) -> Unit, onPick: (FavoriteAddressOption) -> Unit, onDetach: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<String?>(null) }
    val available = options.filter { it.binding.side == side }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 12.sp, color = LabV2.InkMuted)
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), textStyle = LabTypography.DialogBody,
                minLines = 1, maxLines = 3, shape = LabPopupShapes.Field,
                placeholder = { Text("手动填写或选择地址来源", fontSize = 13.sp) },
                trailingIcon = { IconButton(onClick = { group = null; expanded = true }) { Icon(Icons.Rounded.ArrowDropDown, "选择" + label + "来源") } },
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = LabV2.BorderStrong, unfocusedContainerColor = LabV2.FieldSoft))
            LabDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 300.dp),
                shape = LabPopupShapes.Menu, containerColor = Color.White, shadowElevation = 4.dp) {
                if (group == null) {
                    if (available.isEmpty()) LabDropdownMenuItem(text = { Text("暂无可用来源，可手动填写", fontSize = 13.sp) }, onClick = { expanded = false })
                    available.map { it.group }.distinct().forEach { title ->
                        LabDropdownMenuItem(text = { Text(title, fontSize = 13.sp) }, trailingIcon = { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp)) }, onClick = { group = title })
                    }
                } else {
                    LabDropdownMenuItem(text = { Text("返回来源分类", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.ArrowBack, null, Modifier.size(16.dp)) }, onClick = { group = null })
                    available.filter { it.group == group }.forEach { option ->
                        LabDropdownMenuItem(text = {
                            Text(favoriteSourceOptionLabel(option),
                                fontSize = 13.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }, onClick = { onPick(option); expanded = false })
                    }
                }
            }
        }
        if (binding != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val current = options.firstOrNull { it.binding == binding }
            Text(current?.let { "跟随 ${it.name}" } ?: "来源待更新", modifier = Modifier.weight(1f), fontSize = 11.sp, color = LabV2.Primary)
            Text("解除关联", fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onDetach).padding(horizontal = 4.dp, vertical = 3.dp))
        }
    }
}

private suspend fun copyFavoriteImage(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
    val extension = when {
        mime == "image/png" -> "png"
        mime == "image/jpeg" -> "jpg"
        mime == "image/webp" -> "webp"
        else -> uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
    }
    require(extension in setOf("png", "jpg", "jpeg", "webp")) { "仅支持 PNG、JPG、WEBP 图片" }
    val dir = File(context.filesDir, "favorite_icons").apply { mkdirs() }
    val target = File(dir, "${UUID.randomUUID()}.$extension")
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        ?: error("无法读取所选图片")
    require(target.length() in 1..(8L * 1024L * 1024L)) { target.delete(); "图片不能为空且不能超过 8MB" }
    target.absolutePath
}
