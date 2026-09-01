package com.labprobe.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import kotlinx.coroutines.Dispatchers
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
)

private fun normalizeFavoriteType(value: String?): String = when {
    value?.trim()?.equals("mapping", ignoreCase = true) == true -> "mapping"
    value?.trim()?.equals("stun", ignoreCase = true) == true -> "stun"
    else -> "manual"
}

private fun optionalFavoriteId(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

internal fun parseFavoriteShortcutsJson(raw: String): List<FavoriteShortcut> {
    val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    return (0 until array.length()).mapNotNull { index ->
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
        )
    }.sortedBy { it.order }
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
        item.localEndpoint.trim().takeIf { it.isNotBlank() }?.let { json.put("localEndpoint", it) }
        item.remoteEndpoint.trim().takeIf { it.isNotBlank() }?.let { json.put("remoteEndpoint", it) }
        item.serviceType.trim().takeIf { it.isNotBlank() }?.let { json.put("serviceType", it) }
        array.put(json)
    }
    return array.toString()
}

data class FavoriteMappingResolution(
    val rule: PortMapRule?,
    val missing: Boolean,
)

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

private fun favoriteIpv6Snapshot(vararg values: String): String? = values.asSequence()
    .map { it.trim().removePrefix("[").removeSuffix("]") }
    .filter { it.isNotBlank() }
    .mapNotNull { normalizeIpv6(it) }
    .firstOrNull { !isInvalidIpv6(it) }

private fun favoriteLocalEndpoint(rule: PortMapRule, fallbackIpv6: String = ""): String = when {
    rule.mode == "6to4" && rule.targetIpv4.isNotBlank() -> "${favoriteServiceScheme(rule)}://${rule.targetIpv4}:${rule.targetPort}"
    rule.mode == "6to6" -> favoriteIpv6Snapshot(
        rule.targetIpv6Snapshot,
        rule.targetIpv6,
        fallbackIpv6,
    )?.let { "${favoriteServiceScheme(rule)}://[$it]:${rule.targetPort}" }.orEmpty()
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
        description = "$serviceType 服务",
        iconType = "builtin",
        iconValue = "server",
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
        val remote = old.remoteEndpoint.takeUnless(::isWildcardServiceEndpoint).orEmpty()
        val wan = old.wanUrl.takeUnless(::isWildcardServiceEndpoint).orEmpty()
        val hostname = favoriteDdnsHostname(old.ddnsRecordId, ddnsSnapshot, nativeDdnsRecords)
        val syncedRemote = if (hostname != null && rule.listenPort in 1..65535) {
            when {
                remote.isNotBlank() -> replaceFavoriteUrlHost(
                    remote,
                    hostname,
                    rule.listenPort,
                    favoriteServiceScheme(rule),
                ).orEmpty().ifBlank { remote }
                wan.isNotBlank() -> replaceFavoriteUrlHost(
                    wan,
                    hostname,
                    rule.listenPort,
                    favoriteServiceScheme(rule),
                ).orEmpty().ifBlank {
                    URI(favoriteServiceScheme(rule), null, hostname, rule.listenPort, null, null, null).toString()
                }
                else -> URI(favoriteServiceScheme(rule), null, hostname, rule.listenPort, null, null, null).toString()
            }
        } else ""
        val local = syncFavoriteLocalEndpoint(
            old.localEndpoint.ifBlank { old.lanUrl },
            generated.localEndpoint,
            rule.targetPort,
            favoriteServiceScheme(rule),
        )
        old.copy(
            type = "mapping",
            mappingId = rule.id,
            deviceId = generated.deviceId,
            localEndpoint = local,
            remoteEndpoint = syncedRemote.ifBlank { generated.remoteEndpoint },
            serviceType = generated.serviceType,
            lanUrl = local,
            wanUrl = wan.ifBlank { generated.wanUrl },
        ).also { current[index] = it }
    } else {
        current += generated
        generated
    }
    prefs.saveFavoriteShortcuts(current.mapIndexed { order, item -> item.copy(order = order) })
    return saved
}

/** A STUN shortcut is system-owned: it always follows the Agent's latest public endpoint. */
internal fun upsertStunFavorite(
    prefs: AppPrefs,
    rule: StunRule,
    ddnsSnapshot: LabProbeDdnsSnapshot? = null,
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
): FavoriteShortcut? {
    if (!rule.ready || rule.runtime.publicEndpoint.isBlank()) return null
    val current = prefs.favoriteShortcuts().toMutableList()
    val index = current.indexOfFirst { it.stunRuleId == rule.id }
    val scheme = favoriteServiceScheme(rule.serviceType)
    val local = "$scheme://${rule.targetIpv4}:${rule.targetPort}"
    val existing = current.getOrNull(index)
    val ddnsRecordId = existing?.ddnsRecordId
    val hostname = favoriteDdnsHostname(ddnsRecordId, ddnsSnapshot, nativeDdnsRecords)
    val publicPort = rule.runtime.publicPort.takeIf { it in 1..65535 }
        ?: rule.runtime.publicEndpoint.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
    val publicEndpoint = "$scheme://${rule.runtime.publicEndpoint}"
    val remote = if (hostname != null) {
        replaceFavoriteUrlHost(
            publicEndpoint,
            hostname,
            publicPort,
            scheme,
        ).orEmpty().ifBlank {
            if (publicPort != null) "$scheme://$hostname:$publicPort" else "$scheme://$hostname"
        }
    } else publicEndpoint
    val generated = FavoriteShortcut(
        id = "stun-${rule.id}",
        title = rule.name.ifBlank { "STUN ${rule.serviceType}" },
        description = "STUN 穿透 · ${rule.serviceType}",
        iconType = "builtin",
        iconValue = "server",
        lanUrl = local,
        wanUrl = remote,
        order = if (index >= 0) current[index].order else current.size,
        type = "stun",
        stunRuleId = rule.id,
        ddnsRecordId = ddnsRecordId,
        localEndpoint = local,
        remoteEndpoint = remote,
        serviceType = rule.serviceType,
    )
    val saved = if (index >= 0) {
        current[index] = generated
        generated
    } else {
        current += generated
        generated
    }
    prefs.saveFavoriteShortcuts(current.mapIndexed { order, item -> item.copy(order = order) })
    return saved
}

internal fun removeStunFavorite(prefs: AppPrefs, ruleId: String): Int {
    val current = prefs.favoriteShortcuts()
    val updated = current.filterNot { it.stunRuleId == ruleId }
    if (updated.size != current.size) prefs.saveFavoriteShortcuts(updated.mapIndexed { order, item -> item.copy(order = order) })
    return current.size - updated.size
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
): String {
    if (mapping?.missing == true) return "当前不可达"
    if (mapping?.rule?.enabled == false) return "当前不可达"
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
    val remote = old.remoteEndpoint.takeIf { it.isNotBlank() }?.takeUnless(::isWildcardServiceEndpoint).orEmpty()
    val wan = old.wanUrl.takeIf { it.isNotBlank() }?.takeUnless(::isWildcardServiceEndpoint).orEmpty()
    val hostname = favoriteDdnsHostname(old.ddnsRecordId, ddnsSnapshot, nativeDdnsRecords)
    val syncedRemote = if (hostname != null && rule.listenPort in 1..65535) {
        when {
            remote.isNotBlank() -> replaceFavoriteUrlHost(remote, hostname, rule.listenPort, serviceScheme)
                .orEmpty().ifBlank { remote }
            wan.isNotBlank() -> replaceFavoriteUrlHost(wan, hostname, rule.listenPort, serviceScheme)
                .orEmpty().ifBlank { URI(serviceScheme, null, hostname, rule.listenPort, null, null, null).toString() }
            else -> URI(serviceScheme, null, hostname, rule.listenPort, null, null, null).toString()
        }
    } else ""
    val updated = old.copy(
        type = "mapping",
        mappingId = rule.id,
        deviceId = optionalFavoriteId(rule.targetMac),
        localEndpoint = local,
        lanUrl = local,
        remoteEndpoint = syncedRemote,
        wanUrl = wan,
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
    val mapping = resolveFavoriteMapping(favorite, mappingRules).rule
    val isMapping = normalizeFavoriteType(favorite.type) == "mapping"
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

/** A deleted mapping leaves its shortcut usable as a normal, user-owned favorite. */
internal fun detachMappingFavorites(prefs: AppPrefs, mappingId: String): Int {
    val current = prefs.favoriteShortcuts()
    var detached = 0
    val updated = current.map { favorite ->
        if (favorite.mappingId == mappingId) {
            detached += 1
            favorite.copy(type = "manual", mappingId = null)
        } else {
            favorite
        }
    }
    if (detached > 0) prefs.saveFavoriteShortcuts(updated)
    return detached
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
)

private val favoriteImageClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .build()

fun AppPrefs.favoriteShortcuts(): List<FavoriteShortcut> {
    return parseFavoriteShortcutsJson(favoriteShortcutsJson)
}

fun AppPrefs.saveFavoriteShortcuts(items: List<FavoriteShortcut>) {
    favoriteShortcutsJson = serializeFavoriteShortcutsJson(items)
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
        val index = current.indexOfFirst { it.id == generatedId || it.title.equals(title, ignoreCase = true) }
        if (index >= 0) {
            val old = current[index]
            val previousRemote = old.remoteEndpoint.ifBlank { old.wanUrl }
            val wanUrl = mergeWebhookFavoriteUrl(generatedWanUrl, previousRemote)
            val description = old.description.takeUnless { it == "Webhook 自动同步" }.orEmpty()
            if (
                old.id != generatedId || old.title != title || old.wanUrl != wanUrl ||
                old.remoteEndpoint != wanUrl || old.description != description
            ) {
                current[index] = old.copy(
                    id = generatedId,
                    title = title,
                    description = description,
                    wanUrl = wanUrl,
                    remoteEndpoint = wanUrl,
                )
                changes++
            }
        } else {
            current += FavoriteShortcut(
                id = generatedId,
                title = title,
                description = "",
                iconType = "builtin",
                iconValue = webhookBuiltinIcon(title),
                lanUrl = "",
                wanUrl = generatedWanUrl,
                order = current.size,
                remoteEndpoint = generatedWanUrl,
            )
            changes++
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
    return serviceAddressForCopy(serviceType, rawAddress)
}

private fun FavoriteShortcut.addressForCopy(mode: String): String = when (mode) {
    "wan" -> favoriteAddressForCopy(remoteEndpoint.ifBlank { wanUrl }.ifBlank { lanUrl }, serviceType)
    else -> favoriteAddressForCopy(localEndpoint.ifBlank { lanUrl }.ifBlank { wanUrl }, serviceType)
}

private fun openFavorite(context: Context, shortcut: FavoriteShortcut, mode: String) {
    val target = normalizeFavoriteUrl(shortcut.openUrl(mode))
    if (target.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context, "无法打开该地址") }
}

@Composable
fun FavoritesScreen(
    prefs: AppPrefs,
    syncVersion: Int = 0,
    topNav: @Composable () -> Unit = {},
    onOpenDns: () -> Unit,
    onOpenPortMapping: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSsh: (String, Int) -> Unit = { _, _ -> },
    onOpenWireGuard: () -> Unit = {},
    onBeforeOpenShortcut: () -> Unit = {},
) {
    val context = LocalContext.current
    var mappingRules by remember(prefs.hub, prefs.hubDns) { mutableStateOf(PortMappingRuleStore.load(context, prefs).rules) }
    val routerRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val stunApi = remember(prefs.hub, prefs.token, prefs.hubDns) { StunApi(prefs) }
    val ddnsResource by routerRepository.labProbeDdns.collectAsState()
    val nativeDdnsResource by routerRepository.ddns.collectAsState()
    val ddnsSnapshot = ddnsResource.value
    var mappingDevices by remember(prefs.hub, prefs.hubDns) { mutableStateOf(emptyList<DeviceItem>()) }
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(if (prefs.favoriteNetworkMode == "wan") "wan" else "lan") }
    var query by rememberSaveable { mutableStateOf("") }
    var shortcuts by remember { mutableStateOf(prefs.favoriteShortcuts()) }
    var editing by remember { mutableStateOf<FavoriteShortcut?>(null) }
    var adding by remember { mutableStateOf(false) }
    val accessReports = remember { mutableStateMapOf<String, ServiceAccessReport>() }

    LaunchedEffect(routerRepository) {
        routerRepository.refreshLabProbeDdns(false)
        routerRepository.refreshDdns(false)
        mappingDevices = runCatching { deviceApi.getDevices(false) }.getOrDefault(mappingDevices)
    }
    LaunchedEffect(syncVersion) {
        if (syncVersion > 0) shortcuts = prefs.favoriteShortcuts()
        mappingRules = PortMappingRuleStore.load(context, prefs).rules
    }
    LaunchedEffect(stunApi, ddnsResource.updatedAt, nativeDdnsResource.updatedAt) {
        while (true) {
            runCatching { stunApi.list() }.getOrNull()?.rules?.filter { it.ready }?.forEach {
                upsertStunFavorite(prefs, it, ddnsSnapshot, nativeDdnsResource.value.orEmpty())
            }
            shortcuts = prefs.favoriteShortcuts()
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 720.dp) 3 else 2
        val searchWidth = when {
            maxWidth >= 500.dp -> 190.dp
            maxWidth >= 400.dp -> 150.dp
            else -> 120.dp
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
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
                        action = {
                            CompactHeaderAction(Icons.Rounded.Add, "添加") { adding = true }
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
                    FavoriteShortcutCard(
                        shortcut = shortcut,
                        mode = mode,
                        columns = columns,
                        mapping = resolveFavoriteMapping(shortcut, mappingRules),
                        devices = mappingDevices,
                        accessReport = accessReports[shortcut.id],
                        dragEnabled = query.isBlank(),
                        onOpen = {
                            onBeforeOpenShortcut()
                            mappingRules = PortMappingRuleStore.load(context, prefs).rules
                            val linkedRule = resolveFavoriteMapping(shortcut, mappingRules).rule
                            val serviceType = shortcut.serviceType.ifBlank { linkedRule?.let(::favoriteServiceType).orEmpty() }
                            if (!favoriteServiceSupportsDirectOpen(serviceType)) {
                                toast(context, "该服务请复制地址并使用对应客户端连接")
                            } else if (serviceType.equals("WireGuard", true)) {
                                launchServiceQuickAccess(context, ServiceQuickAccessTarget.WireGuard, onOpenSsh, onOpenWireGuard)
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
                                    val remoteEndpoint = resolveFavoriteRemoteEndpoint(shortcut, ddnsSnapshot, mappingRules, nativeDdnsResource.value.orEmpty())
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
                                        val fallbackEndpoint = if (mode == "wan") {
                                            remoteEndpoint
                                        } else {
                                            resolveFavoriteLocalEndpoint(shortcut, mappingRules, mappingDevices)
                                        }
                                        if (serviceType.equals("HTTP", true) || serviceType.equals("HTTPS", true)) {
                                            if (fallbackEndpoint.isNotBlank()) {
                                                toast(context, decision.report.reason.ifBlank { "快速检测未确认，继续在浏览器尝试" })
                                                serviceQuickAccessTarget(serviceType, fallbackEndpoint)?.let {
                                                    launchServiceQuickAccess(context, it, onOpenSsh, onOpenWireGuard)
                                                }
                                            } else {
                                                toast(context, decision.report.reason.ifBlank { "未配置可打开地址" })
                                            }
                                        } else {
                                            toast(context, decision.report.reason.ifBlank { "服务不可达" })
                                        }
                                    }
                                }
                            }
                        },
                        onEdit = { editing = shortcut },
                        onCopyAddress = {
                            val address = if (mode == "wan" && shortcut.ddnsRecordId != null) {
                                favoriteAddressForCopy(
                                    resolveFavoriteRemoteEndpoint(shortcut, ddnsSnapshot, mappingRules, nativeDdnsResource.value.orEmpty()),
                                    shortcut.serviceType,
                                )
                            } else {
                                shortcut.addressForCopy(mode)
                            }
                            copy(context, address)
                        },
                        onViewMapping = {
                            if (resolveFavoriteMapping(shortcut, mappingRules).missing) toast(context, "关联映射不存在") else onOpenPortMapping()
                        },
                        onDelete = { persist(shortcuts.filterNot { it.id == shortcut.id }) },
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
            nativeDdnsRecords = nativeDdnsResource.value.orEmpty(),
            mappingRules = mappingRules,
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
private fun FavoriteShortcutCard(
    shortcut: FavoriteShortcut,
    mode: String,
    columns: Int,
    mapping: FavoriteMappingResolution,
    devices: List<DeviceItem> = emptyList(),
    accessReport: ServiceAccessReport?,
    dragEnabled: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onCopyAddress: () -> Unit,
    onViewMapping: () -> Unit,
    onDelete: () -> Unit,
    onMoveBy: (Int) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var dragging by remember(shortcut.id) { mutableStateOf(false) }
    var dragX by remember(shortcut.id) { mutableFloatStateOf(0f) }
    var dragY by remember(shortcut.id) { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(if (dragging) .965f else 1f, label = "favorite-drag-scale")
    val density = LocalDensity.current
    val rowThreshold = with(density) { 110.dp.toPx() }
    val colThreshold = with(density) { 150.dp.toPx() }

    fun finishDrag() {
        val row = (dragY / rowThreshold).roundToInt()
        val col = (dragX / colThreshold).roundToInt()
        val delta = row * columns + col
        if (delta != 0) onMoveBy(delta)
        dragging = false
        dragX = 0f
        dragY = 0f
    }

    FavoriteListCard(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(if (dragging) dragX.roundToInt() else 0, if (dragging) dragY.roundToInt() else 0) }
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (dragging) .97f else 1f }
            .shadow(if (dragging) 14.dp else 0.dp, LabV2.CompactCardShape, clip = false)
            .pointerInput(shortcut.id, dragEnabled) {
                if (dragEnabled) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = true },
                        onDragEnd = ::finishDrag,
                        onDragCancel = { dragging = false; dragX = 0f; dragY = 0f },
                        onDrag = { change, amount -> change.consume(); dragX += amount.x; dragY += amount.y }
                    )
                }
            }
            .clip(LabV2.CompactCardShape)
            .combinedClickable(onClick = onOpen, onLongClick = {})
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FavoriteIcon(shortcut.iconType, shortcut.iconValue, 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(shortcut.title, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = accessReport?.let(::favoriteAccessStatus) ?: favoriteServiceStatus(shortcut, mode, mapping, devices)
                val statusLabel = when (status) {
                    "内网", "外网" -> "可达"
                    else -> "不可达"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        shortcut.serviceType.ifBlank { "网页入口" },
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = LabV2.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.size(3.dp).clip(CircleShape).background(LabV2.InkMuted.copy(alpha = .45f)))
                    Text(
                        statusLabel,
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            status == "内网" -> LabV2.Green
                            status == "外网" -> LabV2.Primary
                            else -> LabV2.Red
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                shortcut.description.takeIf { it.isNotBlank() && it != shortcut.serviceType }?.let { description ->
                    Text(description, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(18.dp), tint = LabV2.InkMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(12.dp), containerColor = Color.White) {
                    DropdownMenuItem(text = { Text("编辑", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("复制地址", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(17.dp)) }, onClick = { menu = false; onCopyAddress() })
                    if (shortcut.mappingId != null) {
                        DropdownMenuItem(text = { Text("查看关联映射", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(17.dp)) }, onClick = { menu = false; onViewMapping() })
                    }
                    DropdownMenuItem(text = { Text("删除", color = LabV2.Red, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = LabV2.Red) }, onClick = { menu = false; onDelete() })
                }
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
            TextButton(onClick = onAction) { Text(if (hasSearch) "清除并添加" else "添加收藏", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun FavoriteIcon(type: String, iconValue: String, size: Int) {
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
    val shape = RoundedCornerShape((size * .28f).dp)
    Box(Modifier.size(size.dp).clip(shape).background(LabV2.Primary.copy(alpha = .10f)).border(1.dp, LabV2.Primary.copy(alpha = .10f), shape), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
private fun FavoriteEditorSheet(
    existing: FavoriteShortcut?,
    ddnsRecords: List<LabProbeDdnsRecord>,
    nativeDdnsRecords: List<DdnsRecord> = emptyList(),
    mappingRules: List<PortMapRule> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (FavoriteShortcut) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(existing?.id) {
        mutableStateOf(
            existing?.let {
                val rawRemote = it.remoteEndpoint.ifBlank { it.wanUrl }
                FavoriteDraft(
                    id = it.id,
                    title = it.title,
                    description = it.description,
                    iconType = it.iconType,
                    iconValue = it.iconValue,
                    lanUrl = it.lanUrl,
                    wanUrl = it.wanUrl,
                    type = it.type,
                    mappingId = it.mappingId,
                    stunRuleId = it.stunRuleId,
                    ddnsRecordId = it.ddnsRecordId,
                    deviceId = it.deviceId,
                    localEndpoint = it.localEndpoint.ifBlank { it.lanUrl },
                    remoteEndpoint = rawRemote,
                    serviceType = it.serviceType,
                )
            }
                ?: FavoriteDraft()
        )
    }
    var error by remember { mutableStateOf("") }
    var ddnsMenu by remember { mutableStateOf(false) }
    var ddnsOriginalRemote by remember(existing?.id) {
        mutableStateOf((
            if (existing?.ddnsRecordId != null) {
                existing.wanUrl.ifBlank { existing.remoteEndpoint }
            } else {
                existing?.remoteEndpoint?.ifBlank { existing.wanUrl }.orEmpty()
            }
        ).takeUnless(::isWildcardServiceEndpoint).orEmpty())
    }
    LaunchedEffect(existing?.id, ddnsRecords, nativeDdnsRecords, mappingRules) {
        val current = existing ?: return@LaunchedEffect
        if (current.ddnsRecordId != null && draft.remoteEndpoint == ddnsOriginalRemote) {
            val resolved = resolveFavoriteRemoteEndpoint(
                current.copy(remoteEndpoint = ddnsOriginalRemote),
                LabProbeDdnsSnapshot(records = ddnsRecords),
                mappingRules,
                nativeDdnsRecords,
            ).ifBlank { ddnsOriginalRemote }
            if (resolved != ddnsOriginalRemote && resolved.isNotBlank()) {
                draft = draft.copy(remoteEndpoint = resolved)
            }
        }
    }
    fun selectDdns(id: String?, hostnameValue: String?) {
        val mapping = mappingRules.firstOrNull { it.id == draft.mappingId }
        val isMapping = normalizeFavoriteType(draft.type) == "mapping"
        if (id == null || hostnameValue.isNullOrBlank()) {
            draft = draft.copy(
                ddnsRecordId = null,
                remoteEndpoint = if (isMapping) "" else ddnsOriginalRemote,
            )
            ddnsMenu = false
            return
        }
        val hostname = validFavoriteHostname(hostnameValue) ?: return
        if (ddnsOriginalRemote.isBlank()) ddnsOriginalRemote = draft.remoteEndpoint
        val base = draft.remoteEndpoint.ifBlank { ddnsOriginalRemote }
        val replaced = when {
            base.isNotBlank() && isMapping -> replaceFavoriteUrlHost(
                base,
                hostname,
                portOverride = mapping?.listenPort?.takeIf { it in 1..65535 },
                schemeOverride = mapping?.let(::favoriteServiceScheme),
            ) ?: base
            base.isNotBlank() -> replaceFavoriteUrlHost(base, hostname) ?: base
            mapping != null && mapping.listenPort in 1..65535 -> URI(
                favoriteServiceScheme(draft.serviceType.ifBlank { favoriteServiceType(mapping) }),
                null,
                hostname,
                mapping.listenPort,
                null,
                null,
                null,
            ).toString()
            else -> base
        }
        draft = draft.copy(ddnsRecordId = id, remoteEndpoint = replaced)
        ddnsMenu = false
    }
    fun selectLabProbeDdns(record: LabProbeDdnsRecord?) =
        selectDdns(record?.id, record?.hostname)
    fun selectRouterDdns(record: DdnsRecord?) =
        selectDdns(record?.let(::routerDdnsId), record?.let(::routerDdnsHostname))
    val stunFavorite = normalizeFavoriteType(draft.type) == "stun"
    val selectableLabProbeDdns = if (stunFavorite) ddnsRecords.filter(::favoriteDdnsSupportsIpv4) else ddnsRecords
    val selectableNativeDdns = if (stunFavorite) nativeDdnsRecords.filter(::favoriteDdnsSupportsIpv4) else nativeDdnsRecords
    val webhookManaged = existing?.id?.startsWith("webhook-") == true
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { copyFavoriteImage(context, uri) }
                    .onSuccess { path -> draft = draft.copy(iconType = "local", iconValue = path); error = "" }
                    .onFailure { error = it.message ?: "图片导入失败" }
            }
        }
    }

    CompactPopup(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (existing == null) "添加收藏" else "编辑收藏", fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
                if (webhookManaged) Text("标题和外网基础地址自动维护，可手动添加路径后缀", fontSize = 9.5.sp, color = LabV2.InkMuted, maxLines = 1)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, "关闭", Modifier.size(18.dp), tint = LabV2.InkMuted) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FavoriteInlineField("名称", draft.title, { draft = draft.copy(title = it) }, "Home Assistant", Modifier.weight(1f), labelWidth = 48.dp, readOnly = webhookManaged)
            FavoriteInlineField("描述", draft.description, { draft = draft.copy(description = it) }, "简短说明", Modifier.weight(1f), labelWidth = 28.dp)
        }
        Row(Modifier.fillMaxWidth()) {
            FavoriteInlineField("内网地址", draft.localEndpoint, { draft = draft.copy(localEndpoint = it) }, "192.168.5.10:8123", Modifier.weight(1f), uri = true)
        }
        Row(Modifier.fillMaxWidth()) {
            FavoriteInlineField("外网地址", draft.remoteEndpoint, { draft = draft.copy(remoteEndpoint = it) }, "example.com/be72", Modifier.weight(1f), uri = true)
        }
        Row(Modifier.fillMaxWidth()) {
            FavoriteInlineField("服务类型", draft.serviceType, { draft = draft.copy(serviceType = it) }, "例如：HTTPS", Modifier.weight(1f))
        }
        if (selectableLabProbeDdns.any { it.hostname.isNotBlank() } || selectableNativeDdns.isNotEmpty()) {
            val selectedDdns = ddnsRecords.firstOrNull { it.id == draft.ddnsRecordId }
            val selectedRouterDdns = routerDdnsRecord(draft.ddnsRecordId, nativeDdnsRecords)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("关联", modifier = Modifier.width(48.dp), fontSize = 10.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { ddnsMenu = true },
                        modifier = Modifier.fillMaxWidth().height(LabV2.FieldHeight),
                        shape = LabV2.FieldShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LabV2.Ink),
                        border = BorderStroke(1.dp, LabV2.BorderStrong),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text(selectedDdns?.hostname ?: selectedRouterDdns?.domain ?: "不关联", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        Icon(Icons.Rounded.ArrowDropDown, null, tint = LabV2.Primary)
                    }
                    DropdownMenu(
                        expanded = ddnsMenu,
                        onDismissRequest = { ddnsMenu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp,
                    ) {
                        DropdownMenuItem(text = { Text("不关联", color = LabV2.Ink) }, onClick = { selectLabProbeDdns(null) })
                        if (ddnsRecords.any { it.hostname.isNotBlank() }) {
                            Text(if (stunFavorite) "LabProbe DDNS · A / IPv4" else "LabProbe DDNS", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = LabV2.InkMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            selectableLabProbeDdns.filter { it.hostname.isNotBlank() }.forEach { record ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(record.hostname, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val addresses = listOf(
                                                record.detectedIpv4.ifBlank { record.publishedIpv4 },
                                                record.detectedIpv6.ifBlank { record.publishedIpv6 },
                                            ).filter { it.isNotBlank() }.joinToString(" · ")
                                            if (addresses.isNotBlank()) Text(addresses, color = LabV2.InkMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    },
                                    onClick = { selectLabProbeDdns(record) },
                                )
                            }
                        }
                        if (selectableNativeDdns.isNotEmpty()) {
                            Text(if (stunFavorite) "路由器 DDNS · A / IPv4" else "路由器 DDNS", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = LabV2.InkMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            selectableNativeDdns.filter { it.domain.isNotBlank() }.forEach { record ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(record.domain, color = LabV2.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                record.ip.ifBlank { "路由器当前解析地址" },
                                                color = LabV2.InkFaint,
                                                fontSize = 9.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    onClick = { selectRouterDdns(record) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (webhookManaged) {
            Text("可在自动生成的端口后添加 /be72；后续同步会保留该后缀。", Modifier.padding(start = 53.dp), fontSize = 9.5.sp, color = LabV2.Primary)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("图标", modifier = Modifier.width(30.dp), fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted)
            CompactSegmentedControl(listOf("内置图标", "本地上传", "图片网址"), when (draft.iconType) { "local" -> "本地上传"; "url" -> "图片网址"; else -> "内置图标" }, {
                draft = when (it) {
                    "本地上传" -> draft.copy(iconType = "local", iconValue = if (draft.iconType == "local") draft.iconValue else "")
                    "图片网址" -> draft.copy(iconType = "url", iconValue = if (draft.iconType == "url") draft.iconValue else "")
                    else -> draft.copy(iconType = "builtin", iconValue = if (draft.iconType == "builtin") draft.iconValue else "web")
                }
            }, Modifier.weight(1f))
        }

        when (draft.iconType) {
            "local" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FavoriteIcon(draft.iconType, draft.iconValue, 46)
                OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape) {
                    Icon(Icons.Rounded.Image, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("选择 PNG / JPG / WEBP", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
            "url" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FavoriteIcon("url", draft.iconValue, 42)
                CompactTextField(draft.iconValue, { draft = draft.copy(iconValue = it) }, Modifier.weight(1f), placeholder = "https://example.com/icon.png", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
            else -> {
                val choices = listOf("web", "router", "server", "media", "cloud", "home", "download")
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    choices.forEach { value ->
                        val selected = draft.iconValue == value
                        Surface(onClick = { draft = draft.copy(iconValue = value) }, modifier = Modifier.size(46.dp), shape = RoundedCornerShape(15.dp), color = if (selected) LabV2.Primary.copy(alpha = .12f) else LabV2.FieldSoft, border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) LabV2.Primary.copy(alpha = .35f) else LabV2.Border)) {
                            Box(contentAlignment = Alignment.Center) { val icon = favoriteBuiltinIcon(value); Icon(icon.first, null, Modifier.size(21.dp), tint = icon.second) }
                        }
                    }
                }
            }
        }

        if (error.isNotBlank()) Text(error, color = LabV2.Red, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape) { Text("取消", fontWeight = FontWeight.Black) }
            Button(
                onClick = {
                    val lan = normalizeFavoriteUrl(draft.localEndpoint)
                    val wan = normalizeFavoriteUrl(draft.remoteEndpoint)
                    val wanFallback = if (draft.ddnsRecordId != null || normalizeFavoriteType(draft.type) == "mapping") {
                        normalizeFavoriteUrl(ddnsOriginalRemote.ifBlank { wan })
                    } else {
                        wan
                    }
                    error = when {
                        draft.title.trim().isBlank() -> "请填写名称"
                        lan.isBlank() && wan.isBlank() -> "请至少填写一个访问入口"
                        draft.iconType == "local" && draft.iconValue.isBlank() -> "请选择本地图标"
                        draft.iconType == "url" && draft.iconValue.isBlank() -> "请填写图片网址"
                        else -> ""
                    }
                    if (error.isBlank()) {
                        onSave(
                            FavoriteShortcut(
                                id = draft.id,
                                title = draft.title.trim(),
                                description = draft.description.trim(),
                                iconType = draft.iconType,
                                iconValue = draft.iconValue.trim(),
                                lanUrl = lan,
                                wanUrl = wanFallback,
                                order = existing?.order ?: 0,
                                type = normalizeFavoriteType(draft.type),
                                mappingId = draft.mappingId,
                                stunRuleId = draft.stunRuleId,
                                ddnsRecordId = draft.ddnsRecordId,
                                deviceId = draft.deviceId,
                                localEndpoint = lan,
                                remoteEndpoint = wan,
                                serviceType = draft.serviceType.trim(),
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = LabV2.ButtonShape,
                enabled = draft.title.trim().isNotBlank() &&
                    (draft.localEndpoint.trim().isNotBlank() || draft.remoteEndpoint.trim().isNotBlank()) &&
                    !(draft.iconType in setOf("local", "url") && draft.iconValue.trim().isBlank())
            ) { Text("保存", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun RowScope.FavoriteInlineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    labelWidth: Dp = 48.dp,
    uri: Boolean = false,
    readOnly: Boolean = false
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.width(labelWidth), fontSize = 10.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)
        CompactTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).height(LabV2.FieldHeight),
            placeholder = placeholder,
            keyboardOptions = if (uri) KeyboardOptions(keyboardType = KeyboardType.Uri) else KeyboardOptions.Default,
            readOnly = readOnly
        )
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
