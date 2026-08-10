package com.labprobe.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
    val ddnsRecordId: String? = null,
    val deviceId: String? = null,
    val localEndpoint: String = "",
    val remoteEndpoint: String = "",
    val serviceType: String = "",
)

private fun normalizeFavoriteType(value: String?): String =
    if (value?.trim()?.equals("mapping", ignoreCase = true) == true) "mapping" else "manual"

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

private fun favoriteServiceType(rule: PortMapRule): String = when (rule.targetPort) {
    22 -> "SSH"
    80 -> "HTTP"
    443 -> "HTTPS"
    3389 -> "RDP"
    else -> "TCP"
}

private fun favoriteServiceScheme(serviceType: String): String = when (serviceType) {
    "HTTPS" -> "https"
    "HTTP" -> "http"
    "SSH" -> "ssh"
    "RDP" -> "rdp"
    "Telnet" -> "telnet"
    else -> "tcp"
}

private fun favoriteServiceScheme(rule: PortMapRule): String = favoriteServiceScheme(favoriteServiceType(rule))

private fun favoriteLocalEndpoint(rule: PortMapRule): String = when {
    rule.mode == "6to4" && rule.targetIpv4.isNotBlank() -> "${favoriteServiceScheme(rule)}://${rule.targetIpv4}:${rule.targetPort}"
    rule.mode == "6to6" && rule.targetMode == "ipv6_full" && rule.targetIpv6.isNotBlank() ->
        "${favoriteServiceScheme(rule)}://[${rule.targetIpv6.trim().removePrefix("[").removeSuffix("]")}]:${rule.targetPort}"
    else -> ""
}

/** Builds the lightweight favorite payload for an existing mapping rule. */
internal fun favoriteFromPortMapRule(rule: PortMapRule, order: Int = 0): FavoriteShortcut {
    val serviceType = favoriteServiceType(rule)
    return FavoriteShortcut(
        id = "mapping-${rule.id}",
        title = rule.name.ifBlank { "IPv6 映射 ${rule.listenPort}" },
        description = "$serviceType 服务",
        iconType = "builtin",
        iconValue = "server",
        lanUrl = favoriteLocalEndpoint(rule),
        wanUrl = "",
        order = order,
        type = "mapping",
        mappingId = rule.id,
        deviceId = optionalFavoriteId(rule.targetMac),
        localEndpoint = favoriteLocalEndpoint(rule),
        // [::] is a listener wildcard, never a remote client address. A linked
        // DDNS hostname or explicit external endpoint is required before opening.
        remoteEndpoint = "",
        serviceType = serviceType,
    )
}

internal fun upsertMappingFavorite(prefs: AppPrefs, rule: PortMapRule): FavoriteShortcut {
    val current = prefs.favoriteShortcuts().toMutableList()
    val index = current.indexOfFirst { it.mappingId == rule.id }
    val generated = favoriteFromPortMapRule(rule, if (index >= 0) current[index].order else current.size)
    val saved = if (index >= 0) {
        val old = current[index]
        old.copy(
            type = "mapping",
            mappingId = rule.id,
            deviceId = generated.deviceId,
            localEndpoint = generated.localEndpoint,
            remoteEndpoint = old.remoteEndpoint.ifBlank { generated.remoteEndpoint },
            serviceType = generated.serviceType,
            lanUrl = old.lanUrl.ifBlank { generated.lanUrl },
            wanUrl = old.wanUrl.ifBlank { generated.wanUrl },
        ).also { current[index] = it }
    } else {
        current += generated
        generated
    }
    prefs.saveFavoriteShortcuts(current.mapIndexed { order, item -> item.copy(order = order) })
    return saved
}

internal fun favoriteServiceStatus(favorite: FavoriteShortcut, mode: String, mapping: FavoriteMappingResolution? = null): String {
    if (mapping?.missing == true) return "当前不可达"
    if (mapping?.rule?.enabled == false) return "当前不可达"
    val endpoint = if (mode == "wan") {
        favorite.remoteEndpoint.ifBlank { favorite.wanUrl }
    } else {
        favorite.localEndpoint.ifBlank { favorite.lanUrl }
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

private fun replaceFavoriteUrlHost(rawUrl: String, hostname: String): String? {
    val source = runCatching { URI(normalizeFavoriteUrl(rawUrl)) }.getOrNull() ?: return null
    if (source.scheme.isNullOrBlank() || source.rawAuthority.isNullOrBlank()) return null
    val authority = buildString {
        source.rawUserInfo?.let { append(it).append('@') }
        if (hostname.contains(':') && !hostname.startsWith('[')) append('[').append(hostname).append(']') else append(hostname)
        if (source.port >= 0) append(':').append(source.port)
    }
    return buildString {
        append(source.scheme).append("://").append(authority)
        source.rawPath?.let { append(it) }
        source.rawQuery?.let { append('?').append(it) }
        source.rawFragment?.let { append('#').append(it) }
    }
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
): String {
    val raw = favorite.remoteEndpoint.ifBlank { favorite.wanUrl }
    val recordId = optionalFavoriteId(favorite.ddnsRecordId) ?: return raw
    val hostname = snapshot?.records?.firstOrNull { it.id == recordId }?.hostname?.let(::validFavoriteHostname) ?: return raw
    if (raw.isBlank()) {
        val rule = resolveFavoriteMapping(favorite, mappingRules).rule ?: return raw
        if (rule.listenPort !in 1..65535) return raw
        val type = favorite.serviceType.ifBlank { favoriteServiceType(rule) }
        return URI(favoriteServiceScheme(type), null, hostname, rule.listenPort, null, null, null).toString()
    }
    return replaceFavoriteUrlHost(raw, hostname) ?: raw
}

internal fun resolveFavoriteRemoteUrl(favorite: FavoriteShortcut, snapshot: LabProbeDdnsSnapshot?): String =
    resolveFavoriteRemoteEndpoint(favorite.copy(remoteEndpoint = favorite.wanUrl), snapshot)

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
            val wanUrl = mergeWebhookFavoriteUrl(generatedWanUrl, old.wanUrl)
            val description = old.description.takeUnless { it == "Webhook 自动同步" }.orEmpty()
            if (old.id != generatedId || old.title != title || old.wanUrl != wanUrl || old.description != description) {
                current[index] = old.copy(id = generatedId, title = title, description = description, wanUrl = wanUrl)
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
                order = current.size
            )
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

private fun FavoriteShortcut.addressForCopy(mode: String): String = when (mode) {
    "wan" -> remoteEndpoint.ifBlank { wanUrl }.ifBlank { lanUrl }
    else -> localEndpoint.ifBlank { lanUrl }.ifBlank { wanUrl }
}

private fun openFavorite(context: Context, shortcut: FavoriteShortcut, mode: String) {
    val target = normalizeFavoriteUrl(shortcut.openUrl(mode))
    if (target.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context, "无法打开该地址") }
}

private fun openFavoriteEndpoint(context: Context, endpoint: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endpoint)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context, "未找到可打开该服务的应用") }
}

@Composable
fun FavoritesScreen(prefs: AppPrefs, syncVersion: Int = 0, topNav: @Composable () -> Unit = {}, onOpenDns: () -> Unit, onOpenPortMapping: () -> Unit, onOpenSettings: () -> Unit, onBeforeOpenShortcut: () -> Unit = {}) {
    val context = LocalContext.current
    var mappingRules by remember(prefs.hub, prefs.hubDns) { mutableStateOf(PortMappingRuleStore.load(context, prefs).rules) }
    val routerRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val ddnsResource by routerRepository.labProbeDdns.collectAsState()
    val ddnsSnapshot = ddnsResource.value
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(if (prefs.favoriteNetworkMode == "wan") "wan" else "lan") }
    var query by rememberSaveable { mutableStateOf("") }
    var shortcuts by remember { mutableStateOf(prefs.favoriteShortcuts()) }
    var editing by remember { mutableStateOf<FavoriteShortcut?>(null) }
    var adding by remember { mutableStateOf(false) }
    val accessReports = remember { mutableStateMapOf<String, ServiceAccessReport>() }

    LaunchedEffect(routerRepository) { routerRepository.refreshLabProbeDdns(false) }
    LaunchedEffect(syncVersion) {
        if (syncVersion > 0) shortcuts = prefs.favoriteShortcuts()
        mappingRules = PortMappingRuleStore.load(context, prefs).rules
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
                                FavoriteModeButton(Icons.Rounded.Router, "内网", mode == "lan") { mode = "lan"; prefs.favoriteNetworkMode = "lan" }
                                FavoriteModeButton(Icons.Rounded.Public, "外网", mode == "wan") { mode = "wan"; prefs.favoriteNetworkMode = "wan" }
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
                        accessReport = accessReports[shortcut.id],
                        dragEnabled = query.isBlank(),
                        onOpen = {
                            onBeforeOpenShortcut()
                            mappingRules = PortMappingRuleStore.load(context, prefs).rules
                            if (shortcut.localEndpoint.isBlank() && shortcut.remoteEndpoint.isBlank() && shortcut.ddnsRecordId == null) {
                                openFavorite(context, shortcut, mode)
                            } else {
                                scope.launch {
                                    val remoteEndpoint = resolveFavoriteRemoteEndpoint(shortcut, ddnsSnapshot, mappingRules)
                                    val decision = chooseServiceAccess(
                                        localEndpoint = shortcut.localEndpoint.ifBlank { shortcut.lanUrl },
                                        remoteEndpoint = remoteEndpoint,
                                    )
                                    accessReports[shortcut.id] = decision.report
                                    decision.endpoint?.let { openFavoriteEndpoint(context, it) }
                                        ?: toast(context, decision.report.reason.ifBlank { "服务不可达" })
                                }
                            }
                        },
                        onEdit = { editing = shortcut },
                        onCopyAddress = { copy(context, shortcut.addressForCopy(mode)) },
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
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = if (selected) LabV2.Primary else Color.Transparent) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(20.dp), tint = if (selected) Color.White else LabV2.InkMuted)
        }
    }
}

@Composable
private fun FavoriteShortcutCard(
    shortcut: FavoriteShortcut,
    mode: String,
    columns: Int,
    mapping: FavoriteMappingResolution,
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

    CompactListCard(
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
            .combinedClickable(onClick = onOpen, onLongClick = {})
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            FavoriteIcon(shortcut.iconType, shortcut.iconValue, 44)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(shortcut.title, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortcut.serviceType.ifBlank { shortcut.description.ifBlank { "网页入口" } }, fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = accessReport?.let(::favoriteAccessStatus) ?: favoriteServiceStatus(shortcut, mode, mapping)
                Text(status, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = when {
                    status == "内网" -> LabV2.Green
                    status == "外网" -> LabV2.Primary
                    else -> LabV2.Red
                }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(18.dp), tint = LabV2.InkMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = LabV2.Field) {
                    DropdownMenuItem(text = { Text("编辑", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("复制地址", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(17.dp)) }, onClick = { menu = false; onCopyAddress() })
                    if (shortcut.mappingId != null) {
                        DropdownMenuItem(text = { Text("查看关联映射", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(17.dp)) }, onClick = { menu = false; onViewMapping() })
                    }
                    DropdownMenuItem(text = { Text("删除", color = LabV2.Red, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = LabV2.Red) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(34.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary)) {
            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text("打开", fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun FavoriteEmptyState(hasSearch: Boolean, onAction: () -> Unit) {
    CompactListCard(Modifier.fillMaxWidth()) {
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
    "media" -> Icons.Rounded.PlayCircle to Color(0xFF7C3AED)
    "cloud" -> Icons.Rounded.Cloud to Color(0xFF0EA5E9)
    "home" -> Icons.Rounded.Home to Color(0xFF16A34A)
    "download" -> Icons.Rounded.Download to Color(0xFF14B8A6)
    else -> Icons.Rounded.Language to LabV2.Primary
}

@Composable
private fun FavoriteEditorSheet(
    existing: FavoriteShortcut?,
    ddnsRecords: List<LabProbeDdnsRecord>,
    onDismiss: () -> Unit,
    onSave: (FavoriteShortcut) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(existing?.id) {
        mutableStateOf(
            existing?.let {
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
                    ddnsRecordId = it.ddnsRecordId,
                    deviceId = it.deviceId,
                    localEndpoint = it.localEndpoint,
                    remoteEndpoint = it.remoteEndpoint,
                    serviceType = it.serviceType,
                )
            }
                ?: FavoriteDraft()
        )
    }
    var error by remember { mutableStateOf("") }
    var ddnsMenu by remember { mutableStateOf(false) }
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
            FavoriteInlineField("名称", draft.title, { draft = draft.copy(title = it) }, "Home Assistant", Modifier.weight(1f), readOnly = webhookManaged)
            FavoriteInlineField("描述", draft.description, { draft = draft.copy(description = it) }, "简短说明", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            FavoriteInlineField("内网地址", draft.lanUrl, { draft = draft.copy(lanUrl = it) }, "192.168.5.10:8123", Modifier.weight(1f), uri = true)
        }
        Row(Modifier.fillMaxWidth()) {
            FavoriteInlineField("外网地址", draft.wanUrl, { draft = draft.copy(wanUrl = it) }, "example.com/be72", Modifier.weight(1f), uri = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FavoriteInlineField("服务类型", draft.serviceType, { draft = draft.copy(serviceType = it) }, "例如：HTTPS", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FavoriteInlineField("本地入口", draft.localEndpoint, { draft = draft.copy(localEndpoint = it) }, "可选", Modifier.weight(1f), uri = true)
            FavoriteInlineField("远程入口", draft.remoteEndpoint, { draft = draft.copy(remoteEndpoint = it) }, "可选", Modifier.weight(1f), uri = true)
        }
        if (ddnsRecords.any { it.hostname.isNotBlank() }) {
            val selectedDdns = ddnsRecords.firstOrNull { it.id == draft.ddnsRecordId }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("关联 DDNS", modifier = Modifier.width(52.dp), fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted)
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { ddnsMenu = true },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = LabV2.ButtonShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LabV2.Ink),
                    ) {
                        Text(selectedDdns?.hostname ?: "不关联", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
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
                        DropdownMenuItem(text = { Text("不关联", color = LabV2.Ink) }, onClick = { draft = draft.copy(ddnsRecordId = null); ddnsMenu = false })
                        ddnsRecords.filter { it.hostname.isNotBlank() }.forEach { record ->
                            DropdownMenuItem(
                                text = { Text(record.hostname, color = LabV2.Ink) },
                                onClick = { draft = draft.copy(ddnsRecordId = record.id); ddnsMenu = false },
                            )
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
                    val lan = normalizeFavoriteUrl(draft.lanUrl)
                    val wan = normalizeFavoriteUrl(draft.wanUrl)
                    error = when {
                        draft.title.trim().isBlank() -> "请填写名称"
                        lan.isBlank() && wan.isBlank() && draft.localEndpoint.trim().isBlank() && draft.remoteEndpoint.trim().isBlank() -> "请至少填写一个访问入口"
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
                                wanUrl = wan,
                                order = existing?.order ?: 0,
                                type = normalizeFavoriteType(draft.type),
                                mappingId = draft.mappingId,
                                ddnsRecordId = draft.ddnsRecordId,
                                deviceId = draft.deviceId,
                                localEndpoint = draft.localEndpoint.trim(),
                                remoteEndpoint = draft.remoteEndpoint.trim(),
                                serviceType = draft.serviceType.trim(),
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = LabV2.ButtonShape,
                enabled = draft.title.trim().isNotBlank() &&
                    (draft.lanUrl.trim().isNotBlank() || draft.wanUrl.trim().isNotBlank() || draft.localEndpoint.trim().isNotBlank() || draft.remoteEndpoint.trim().isNotBlank()) &&
                    !(draft.iconType in setOf("local", "url") && draft.iconValue.trim().isBlank())
            ) { Text("保存", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun RowScope.FavoriteInlineField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier, uri: Boolean = false, readOnly: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, modifier = Modifier.width(if (label.length > 2) 48.dp else 28.dp), fontSize = 10.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)
        CompactTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
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
