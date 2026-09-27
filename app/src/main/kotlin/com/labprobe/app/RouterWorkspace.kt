package com.labprobe.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

const val DEFAULT_ROUTER_WORKSPACE_ID = "default"

data class RouterWorkspace(
    val routerId: String,
    val name: String,
    val site: String,
    val model: String,
    val online: Boolean,
    val basePath: String,
    val deviceCount: Int? = null,
    val onlineDeviceCount: Int? = null,
    val followedOnlineCount: Int? = null,
    val localDraft: Boolean = false,
    val isDefault: Boolean = false,
    val serverRouterId: String = routerId,
    val wireguardSupported: Boolean = true,
)

fun isModelWireGuardSupported(model: String, name: String, routerId: String): Boolean {
    val m = model.trim().lowercase()
    val n = name.trim().lowercase()
    val id = routerId.trim().lowercase()
    return !m.contains("be50") && !n.contains("be50") && !id.contains("be50")
}

fun validRouterWorkspaceId(value: String): Boolean =
    value == DEFAULT_ROUTER_WORKSPACE_ID || Regex("[A-Za-z0-9_-]{1,64}").matches(value)

fun routerWorkspacePath(routerId: String): String {
    require(validRouterWorkspaceId(routerId)) { "无效的路由器标识" }
    return if (routerId == DEFAULT_ROUTER_WORKSPACE_ID) "" else "/r/$routerId"
}

fun workspaceHubUrl(hubRoot: String, routerId: String): String {
    val root = normalizeHubBaseUrl(hubRoot)
    return if (root.isBlank()) "" else root + routerWorkspacePath(routerId)
}

fun defaultRouterWorkspace(): RouterWorkspace =
    RouterWorkspace(DEFAULT_ROUTER_WORKSPACE_ID, "默认路由器", "", "", false, "", isDefault = true, wireguardSupported = true)

fun parseRouterWorkspaces(root: JSONObject, legacyServerId: String? = null): List<RouterWorkspace> {
    val rows = root.optJSONArray("routers") ?: return listOf(defaultRouterWorkspace())
    val defaultRouterId = root.optString("defaultRouterId").trim().takeIf(::validRouterWorkspaceId)
    val originalRouterId = legacyServerId?.takeIf(::validRouterWorkspaceId) ?: defaultRouterId
    // 缺字段和 0 不是一回事：0 是「一台设备都没有」，缺才是「还不知道」。
    fun count(item: JSONObject, key: String): Int? =
        if (!item.has(key) || item.isNull(key)) null else item.optInt(key, -1).takeIf { it >= 0 }
    val parsed = buildList {
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val serverId = item.optString("routerId").trim()
            if (!validRouterWorkspaceId(serverId)) continue
            val id = if (serverId == originalRouterId) DEFAULT_ROUTER_WORKSPACE_ID else serverId
            val canonicalPath = "/r/$serverId"
            val expectedPath = if (serverId == defaultRouterId) "" else canonicalPath
            val suppliedPath = item.optString("basePath").trim().trimEnd('/')
            // Keep the original router attached to its legacy local preferences even
            // after another worker becomes the Hub's default root route.
            val acceptedPaths = if (id == DEFAULT_ROUTER_WORKSPACE_ID || serverId == defaultRouterId) {
                setOf("", canonicalPath)
            } else setOf(canonicalPath)
            if (suppliedPath !in acceptedPaths) continue
            val wgSupported = if (item.has("wireguardSupported")) {
                item.optBoolean("wireguardSupported")
            } else {
                isModelWireGuardSupported(item.optString("model"), item.optString("name"), serverId)
            }
            add(
                RouterWorkspace(
                    routerId = id,
                    name = item.optString("name").trim().ifBlank { if (id == DEFAULT_ROUTER_WORKSPACE_ID) "默认路由器" else id },
                    site = item.optString("site").trim(),
                    model = item.optString("model").trim(),
                    online = item.optBoolean("online"),
                    basePath = expectedPath,
                    deviceCount = count(item, "deviceCount"),
                    onlineDeviceCount = count(item, "onlineDeviceCount"),
                    isDefault = if (defaultRouterId == null) id == DEFAULT_ROUTER_WORKSPACE_ID else serverId == defaultRouterId,
                    serverRouterId = serverId,
                    wireguardSupported = wgSupported,
                )
            )
        }
    }.distinctBy { it.routerId }
    val legacy = parsed.firstOrNull { it.routerId == DEFAULT_ROUTER_WORKSPACE_ID }
        ?: defaultRouterWorkspace().copy(isDefault = parsed.none { it.isDefault })
    return (listOf(legacy) + parsed.filterNot { it.routerId == DEFAULT_ROUTER_WORKSPACE_ID })
        .sortedWith(compareByDescending<RouterWorkspace> { it.isDefault })
}

fun selectableWorkspaceId(workspaces: List<RouterWorkspace>, requested: String, listingConfirmed: Boolean): String =
    if (workspaces.any { it.routerId == requested && (listingConfirmed || it.localDraft) }) requested
    else workspaces.firstOrNull { it.isDefault }?.routerId ?: DEFAULT_ROUTER_WORKSPACE_ID

/** New cards live on the phone until a Hub worker with the same ID is registered. */
object LocalRouterWorkspaceRegistry {
    private fun key(hubRoot: String) = "local_router_cards_v1"
    fun list(context: Context, hubRoot: String): List<RouterWorkspace> {
        val raw = context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .getString(key(hubRoot), "[]") ?: "[]"
        val rows = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (!validRouterWorkspaceId(id) || id == DEFAULT_ROUTER_WORKSPACE_ID) continue
                val wgSupported = if (row.has("wireguardSupported")) {
                    row.optBoolean("wireguardSupported")
                } else {
                    isModelWireGuardSupported("", row.optString("name"), id)
                }
                add(RouterWorkspace(id, row.optString("name").ifBlank { id }, "", "", false,
                    routerWorkspacePath(id), localDraft = true, wireguardSupported = wgSupported))
            }
        }.distinctBy { it.routerId }
    }
    fun add(context: Context, hubRoot: String, name: String): RouterWorkspace {
        val wgSupported = isModelWireGuardSupported("", name, "")
        val item = RouterWorkspace("local_" + UUID.randomUUID().toString().replace("-", ""),
            name.trim(), "", "", false, "", localDraft = true, wireguardSupported = wgSupported)
        save(context, hubRoot, list(context, hubRoot) + item)
        return item.copy(basePath = routerWorkspacePath(item.routerId))
    }
    private fun hiddenKey(hubRoot: String) = "hidden_${workspaceDigest(normalizeHubBaseUrl(hubRoot))}"
    fun hidden(context: Context, hubRoot: String): Set<String> {
        val raw = context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .getString(hiddenKey(hubRoot), "[]").orEmpty()
        val rows = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until rows.length()).map { rows.optString(it) }.filter(::validRouterWorkspaceId).toSet()
    }
    fun hide(context: Context, hubRoot: String, id: String) {
        val rows = JSONArray()
        (hidden(context, hubRoot) + id).forEach(rows::put)
        context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .edit().putString(hiddenKey(hubRoot), rows.toString()).apply()
    }

    fun rename(context: Context, hubRoot: String, id: String, name: String) {
        save(context, hubRoot, list(context, hubRoot).map {
            if (it.routerId == id) it.copy(name = name.trim()) else it
        })
    }
    fun remove(context: Context, hubRoot: String, id: String) {
        save(context, hubRoot, list(context, hubRoot).filterNot { it.routerId == id })
    }
    private fun save(context: Context, hubRoot: String, items: List<RouterWorkspace>) {
        val rows = JSONArray()
        items.forEach { rows.put(JSONObject().put("id", it.routerId).put("name", it.name)) }
        context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .edit().putString(key(hubRoot), rows.toString()).apply()
    }
}

fun mergeRouterWorkspaces(
    remote: List<RouterWorkspace>, local: List<RouterWorkspace>,
    boundRoutes: Map<String, String> = emptyMap(), hiddenIds: Set<String> = emptySet(),
): List<RouterWorkspace> {
    val linked = local.map { draft ->
        val worker = remote.firstOrNull { it.serverRouterId == boundRoutes[draft.routerId] }
        if (worker == null) draft else draft.copy(
            online = worker.online, deviceCount = worker.deviceCount,
            onlineDeviceCount = worker.onlineDeviceCount, isDefault = worker.isDefault,
            serverRouterId = worker.serverRouterId, basePath = worker.basePath)
    }
    val boundIds = boundRoutes.values.toSet()
    return (remote.filterNot { it.routerId in hiddenIds || it.serverRouterId in boundIds } +
        linked.filterNot { it.routerId in hiddenIds || remote.any { row -> row.routerId == it.routerId } })
        .sortedWith(compareByDescending<RouterWorkspace> { it.isDefault })
}

fun loadVisibleRouterWorkspaces(context: Context, hubRoot: String, remote: List<RouterWorkspace>): List<RouterWorkspace> {
    val local = LocalRouterWorkspaceRegistry.list(context, hubRoot)
    val bound = local.associate { it.routerId to AppPrefs(context, it.routerId).routerHubId }
    return mergeRouterWorkspaces(remote, local, bound, LocalRouterWorkspaceRegistry.hidden(context, hubRoot))
}

data class WorkspaceDeviceCounts(
    val deviceCount: Int? = null,
    val onlineDeviceCount: Int? = null,
    val followedOnlineCount: Int? = null,
)

fun workspaceDeviceCounts(context: Context, routerId: String): WorkspaceDeviceCounts? {
    val prefs = AppPrefs(context.applicationContext, routerId)
    if (prefs.cacheDevices.isBlank() && prefs.cacheOnlineDevices.isBlank() && prefs.cacheOfflineDevices.isBlank()) return null
    val overrides = parseDeviceOverrides(prefs.deviceOverridesJson)
    val watched = applyDeviceOverrides(parseDeviceArray(prefs.cacheDevices), overrides)
    val online = applyDeviceOverrides(parseDeviceArray(prefs.cacheOnlineDevices), overrides)
    val offline = applyDeviceOverrides(parseDeviceArray(prefs.cacheOfflineDevices), overrides)
    val shared = mergeSharedDeviceState(watched + offline, online)
    val total = shared.size.takeIf { it > 0 || prefs.cacheDevices.isNotBlank() || prefs.cacheOnlineDevices.isNotBlank() }
    val onlineCount = online.size.takeIf { prefs.cacheOnlineDevices.isNotBlank() } ?: shared.count { it.online }
    val followedOnline = followedDeviceList(shared).count { it.online }
    return WorkspaceDeviceCounts(
        deviceCount = total,
        onlineDeviceCount = onlineCount,
        followedOnlineCount = followedOnline,
    )
}

fun workspaceFollowedOnlineCount(context: Context, routerId: String): Int? =
    workspaceDeviceCounts(context, routerId)?.followedOnlineCount


fun workspaceDigest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun routerWorkspacePreferencesName(routerId: String, hubRoot: String = ""): String {
    require(validRouterWorkspaceId(routerId))
    return "labprobe_workspace_${workspaceDigest(routerId)}"
}

/** The active ID is intentionally process-local until a Hub list confirms it. */
object RouterWorkspaceStore {
    @Volatile private var activeId = DEFAULT_ROUTER_WORKSPACE_ID
    @Volatile private var activation = 0L
    var connectionEpoch by mutableIntStateOf(0)
        private set

    fun activeWorkspaceId(context: Context): String = activeId
    fun activationVersion(): Long = activation
    fun isActive(routerId: String, expectedActivation: Long): Boolean =
        activeId == routerId && activation == expectedActivation

    @Synchronized
    fun hubConnectionChanged() {
        // Invalidate the old route immediately, before Compose reloads the Hub list.
        activeId = DEFAULT_ROUTER_WORKSPACE_ID
        activation += 1L
        connectionEpoch += 1
    }

    @Synchronized
    fun activate(context: Context, hubRoot: String, routerId: String) {
        require(validRouterWorkspaceId(routerId))
        if (activeId != routerId) activation += 1L
        activeId = routerId
        context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .edit().putString("selected_${workspaceDigest(normalizeHubBaseUrl(hubRoot))}", routerId).apply()
    }

    private fun identityKey(prefix: String, hubRoot: String): String =
        "${prefix}_${workspaceDigest(normalizeHubBaseUrl(hubRoot))}"

    fun legacyServerId(context: Context, hubRoot: String): String =
        context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .getString(identityKey("legacy_server", hubRoot), "").orEmpty()

    fun serverDefaultId(context: Context, hubRoot: String): String =
        context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .getString(identityKey("server_default", hubRoot), "").orEmpty()

    fun recordServerDefault(context: Context, hubRoot: String, serverId: String) {
        require(validRouterWorkspaceId(serverId))
        val prefs = context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
        val legacyKey = identityKey("legacy_server", hubRoot)
        val editor = prefs.edit()
        if (!prefs.contains(legacyKey)) editor.putString(legacyKey, serverId)
        editor.putString(identityKey("server_default", hubRoot), serverId).apply()
    }

    fun cacheList(context: Context, hubRoot: String, root: JSONObject) {
        context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .edit().putString(identityKey("router_list", hubRoot), root.toString()).apply()
    }

    fun cachedList(context: Context, hubRoot: String): List<RouterWorkspace> {
        val raw = context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .getString(identityKey("router_list", hubRoot), "").orEmpty()
        return runCatching { parseRouterWorkspaces(JSONObject(raw), legacyServerId(context, hubRoot)) }
            .getOrDefault(listOf(defaultRouterWorkspace()))
    }

    fun updateCachedDefault(context: Context, hubRoot: String, serverId: String) {
        val prefs = context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
        val key = identityKey("router_list", hubRoot)
        val root = runCatching { JSONObject(prefs.getString(key, "").orEmpty()) }.getOrNull() ?: return
        root.put("defaultRouterId", serverId)
        prefs.edit().putString(key, root.toString()).apply()
    }

    fun remembered(context: Context, hubRoot: String): String {
        val id = context.applicationContext.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
            .getString("selected_${workspaceDigest(normalizeHubBaseUrl(hubRoot))}", DEFAULT_ROUTER_WORKSPACE_ID)
            .orEmpty()
        return id.takeIf(::validRouterWorkspaceId) ?: DEFAULT_ROUTER_WORKSPACE_ID
    }

    @Synchronized
    fun resetActive() {
        if (activeId != DEFAULT_ROUTER_WORKSPACE_ID) activation += 1L
        activeId = DEFAULT_ROUTER_WORKSPACE_ID
    }
}

class RouterWorkspaceApi(private val context: Context, private val defaultPrefs: AppPrefs) {
    suspend fun list(): List<RouterWorkspace> = withContext(Dispatchers.IO) {
        val root = HubApi(defaultPrefs, defaultPrefs.hubRoot).requestJson("/api/routers")
        val serverDefault = root.optString("defaultRouterId").trim().takeIf(::validRouterWorkspaceId)
        val previousLegacy = RouterWorkspaceStore.legacyServerId(context, defaultPrefs.hubRoot)
        val parsed = parseRouterWorkspaces(root, previousLegacy.ifBlank { serverDefault })
        if (serverDefault != null) RouterWorkspaceStore.recordServerDefault(context, defaultPrefs.hubRoot, serverDefault)
        RouterWorkspaceStore.cacheList(context, defaultPrefs.hubRoot, root)
        parsed
    }

    suspend fun setDefault(serverRouterId: String) = withContext(Dispatchers.IO) {
        require(validRouterWorkspaceId(serverRouterId))
        val root = HubApi(defaultPrefs, defaultPrefs.hubRoot).requestJson(
            "/api/routers/default", "POST", JSONObject().put("routerId", serverRouterId))
        if (!root.optBoolean("ok") || root.optString("defaultRouterId") != serverRouterId)
            error("Hub 未确认默认路由器变更")
        RouterWorkspaceStore.recordServerDefault(context, defaultPrefs.hubRoot, serverRouterId)
        RouterWorkspaceStore.updateCachedDefault(context, defaultPrefs.hubRoot, serverRouterId)
    }
}

/** Per-router Android Keystore storage for tokens and optional SSH passwords. */
internal class SecureWorkspaceStringStore(context: Context, routerId: String, hubRoot: String = "", purpose: String) {
    private val identity = routerId
    private val prefs = context.applicationContext.getSharedPreferences(
        "labprobe_ws_sec_${workspaceDigest(identity)}", Context.MODE_PRIVATE
    )
    private val alias = "labprobe_ws_${workspaceDigest(identity)}_${workspaceDigest(purpose)}"
    private val ivKey = "${purpose}_iv"
    private val cipherKey = "${purpose}_cipher"
    private var cachedCipher: String? = null
    private var cachedIv: String? = null
    private var cachedPlain = ""

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun get(): String {
        val encrypted = prefs.getString(cipherKey, null) ?: return ""
        val iv = prefs.getString(ivKey, null) ?: return ""
        if (encrypted == cachedCipher && iv == cachedIv) return cachedPlain
        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
        cachedCipher = encrypted
        cachedIv = iv
        cachedPlain = plain
        return plain
    }

    @Synchronized
    fun set(value: String) {
        val clean = value.trim()
        cachedCipher = null
        cachedIv = null
        cachedPlain = ""
        if (clean.isBlank()) {
            prefs.edit().remove(ivKey).remove(cipherKey).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(cipherKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }
}


/**
 * 删掉一台路由器在本机的全部痕迹：工作区偏好、Keystore 里的令牌/密码、以及记住的选择。
 *
 * 只针对非默认工作区。默认那台的 `AppPrefs` 在 `usesLegacyDefault()` 时直接落在全局
 * `labprobe` 文件上（Hub 地址、令牌、收藏夹、AI 设置全在里面），删它等于清空整个 App。
 */
fun forgetRouterWorkspace(context: Context, hubRoot: String, routerId: String) {
    val app = context.applicationContext
    if (routerId == DEFAULT_ROUTER_WORKSPACE_ID) {
        val legacy = RouterWorkspaceStore.legacyServerId(app, hubRoot)
        val current = RouterWorkspaceStore.serverDefaultId(app, hubRoot)
        require(legacy.isNotBlank() && current != legacy) { "当前默认路由器不能删除" }
        // Keep the legacy management token: the gateway still needs it to list
        // and switch routes. The old router card is hidden only on this phone.
        LocalRouterWorkspaceRegistry.hide(app, hubRoot, routerId)
        return
    }
    val boundServerId = AppPrefs(app, routerId).routerHubId.takeIf {
        it != routerId && validRouterWorkspaceId(it)
    }
    val digest = workspaceDigest(routerId)
    app.getSharedPreferences(routerWorkspacePreferencesName(routerId), Context.MODE_PRIVATE)
        .edit().clear().commit()
    app.getSharedPreferences("labprobe_ws_sec_$digest", Context.MODE_PRIVATE)
        .edit().clear().commit()
    runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.aliases().toList()
            .filter { it.startsWith("labprobe_ws_${digest}_") }
            .forEach { runCatching { store.deleteEntry(it) } }
    }
    val selection = app.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
    val key = "selected_${workspaceDigest(normalizeHubBaseUrl(hubRoot))}"
    LocalRouterWorkspaceRegistry.remove(context, hubRoot, routerId)
    LocalRouterWorkspaceRegistry.hide(context, hubRoot, routerId)
    boundServerId?.let { LocalRouterWorkspaceRegistry.hide(context, hubRoot, it) }
    if (selection.getString(key, "") == routerId) {
        selection.edit().putString(key, DEFAULT_ROUTER_WORKSPACE_ID).apply()
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterWorkspacePickerDialog(
    workspaces: List<RouterWorkspace>,
    selectedId: String,
    listingConfirmed: Boolean,
    loading: Boolean,
    error: String,
    onSelect: (String) -> Unit,
    onEdit: (RouterWorkspace) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        dragHandle = null,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("切换路由器", style = LabTypography.SectionTitle, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).clickable { onAdd() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, "添加路由器", tint = LabV2.Ink) }
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    workspaces.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = LabV2.Border, thickness = 0.5.dp)
                        val counts = remember(item.routerId) { workspaceDeviceCounts(context, item.routerId) }
                        val effectiveItem = item.copy(
                            deviceCount = item.deviceCount ?: counts?.deviceCount,
                            onlineDeviceCount = item.onlineDeviceCount ?: counts?.onlineDeviceCount,
                            followedOnlineCount = item.followedOnlineCount ?: counts?.followedOnlineCount,
                        )
                        val selected = item.routerId == selectedId
                        val isItemBe50 = !effectiveItem.wireguardSupported || !isModelWireGuardSupported(effectiveItem.model, effectiveItem.name, effectiveItem.routerId)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (!selected) onSelect(item.routerId) else onDismiss()
                            }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(if (isItemBe50) R.drawable.router_be50 else R.drawable.router_skeuomorphic_v3),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(effectiveItem.name, style = LabTypography.CardTitle,
                                    color = if (selected) Color(0xFF20B8B8) else LabV2.Ink,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val prefs = if (effectiveItem.localDraft) remember(effectiveItem.routerId) { AppPrefs(context, effectiveItem.routerId) } else null
                                val unconfigured = prefs != null && (prefs.hub.isBlank() || prefs.token.isBlank())
                                if (unconfigured) {
                                    Text("未配置", style = LabTypography.Caption, color = LabV2.InkMuted)
                                } else {
                                    Text(routerWorkspaceSummaryLine(effectiveItem), style = LabTypography.Caption,
                                        color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Box(
                                Modifier.size(38.dp).clip(CircleShape).clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                ) { onEdit(effectiveItem) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Edit, "编辑${effectiveItem.name}", Modifier.size(18.dp), tint = LabV2.InkMuted) }
                        }
                    }
                }
            }
        }
    }
}


/** 本机的路由器备注优先于 Hub 下发的名字；没改过就照原样显示。 */
fun RouterWorkspace.withLocalRouterName(context: Context): RouterWorkspace {
    val local = AppPrefs(context.applicationContext, routerId).routerDisplayName
    return if (local.isBlank() || local == name) this else copy(name = local)
}


fun routerWorkspaceDeviceCountsLine(item: RouterWorkspace): String = when {
    item.deviceCount != null && item.onlineDeviceCount != null ->
        "${item.deviceCount}台设备，${item.onlineDeviceCount}台在线"
    item.deviceCount != null -> "${item.deviceCount}台设备，在线数待同步"
    item.onlineDeviceCount != null -> "设备数待同步，${item.onlineDeviceCount}台在线"
    else -> "设备数待同步，在线数待同步"
}

fun routerWorkspaceSummaryLine(item: RouterWorkspace): String {
    val devPart = routerWorkspaceDeviceCountsLine(item)
    val followedPart = item.followedOnlineCount?.let { "关注设备在线 ${it} 台" } ?: "关注设备在线待同步"
    return "$devPart · $followedPart"
}

/** 一行讲清「通不通 + 有几台」；缺字段时说「待同步」，绝不拿 0 冒充已知。 */
fun routerWorkspaceCountsLine(item: RouterWorkspace): String {
    val total = item.deviceCount
    val online = item.onlineDeviceCount
    val counts = when {
        total != null && online != null -> "在线 $online / 共 $total 台"
        online != null -> "在线 $online 台"
        total != null -> "共 $total 台"
        else -> "设备数待同步"
    }
    return (if (item.online) "路由器在线" else "路由器离线") + " · " + counts
}


@Composable
fun AddRouterWorkspaceDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "添加路由器",
                        style = LabTypography.SectionTitle.copy(fontSize = 18.sp),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF3F4F6))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Close, "关闭", Modifier.size(16.dp), tint = LabV2.InkMuted)
                    }
                }

                Text(
                    "输入这台路由器的备注名称，添加后将直接进入连接配置。",
                    style = LabTypography.Body.copy(fontSize = 12.5.sp),
                    color = LabV2.InkMuted
                )

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFFF3F4F6),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                "例如：BE50、客厅路由",
                                style = LabTypography.Body.copy(fontSize = 14.sp),
                                color = LabV2.InkMuted.copy(alpha = 0.6f)
                            )
                        }
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = LabTypography.CardTitle.copy(fontSize = 15.sp, color = LabV2.Ink),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF3F4F6),
                            contentColor = LabV2.Ink
                        ),
                        elevation = null
                    ) {
                        Text("取消", style = LabTypography.Button)
                    }
                    Button(
                        onClick = {
                            val clean = name.trim()
                            if (clean.isNotEmpty()) onAdd(clean)
                        },
                        enabled = name.trim().isNotEmpty(),
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LabV2.Primary,
                            contentColor = Color.White
                        ),
                        elevation = null
                    ) {
                        Text("添加", style = LabTypography.Button)
                    }
                }
            }
        }
    }
}

@Composable
fun RouterWorkspaceEditDialog(
    target: RouterWorkspace,
    hubRoot: String,
    onDismiss: () -> Unit,
    onNameSaved: () -> Unit,
    onDeleted: () -> Unit,
    onSetDefault: suspend () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(target.routerId) { mutableStateOf(target.name) }
    var operation by remember(target.routerId) { mutableStateOf<String?>(null) }
    var result by remember(target.routerId) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmDelete by remember(target.routerId) { mutableStateOf(false) }
    val busy = operation != null
    fun finish(success: Boolean, message: String, afterSuccess: () -> Unit = {}) {
        operation = null
        result = success to message
        if (success) scope.launch {
            kotlinx.coroutines.delay(1_100L)
            if (result == (true to message)) {
                result = null
                afterSuccess()
            }
        } else scope.launch {
            kotlinx.coroutines.delay(2_000L)
            if (result == (false to message)) {
                result = null
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F5F8)) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                        Text("编辑", style = LabTypography.PageTitle.copy(fontSize = 17.sp))
                        Box(Modifier.align(Alignment.CenterStart).size(40.dp).clip(CircleShape)
                            .clickable(enabled = !busy, onClick = onDismiss), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ArrowBack, "返回", tint = LabV2.Ink)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("路由器名称", style = LabTypography.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = LabV2.InkMuted)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(value = name, onValueChange = { if (!busy) name = it },
                                    singleLine = true, enabled = !busy,
                                    textStyle = LabTypography.CardTitle.copy(fontSize = 17.5.sp, color = LabV2.Ink),
                                    modifier = Modifier.weight(1f))
                                if (name.isNotEmpty()) Box(
                                    Modifier.size(30.dp).clip(CircleShape).clickable(enabled = !busy) { name = "" },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Close, "清空名称", Modifier.size(18.dp), tint = LabV2.InkMuted) }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        val clean = name.trim()
                        if (clean.isBlank()) { result = false to "名称不能为空"; return@Button }
                        operation = "正在保存…"
                        scope.launch {
                            try {
                                kotlinx.coroutines.delay(300L)
                                AppPrefs(context, target.routerId).routerDisplayName = clean
                                if (target.localDraft) LocalRouterWorkspaceRegistry.rename(context, hubRoot, target.routerId, clean)
                                onNameSaved()
                                finish(true, "已保存", onDismiss)
                            } catch (error: Exception) {
                                finish(false, "保存失败：${error.message.orEmpty()}")
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary, contentColor = Color.White)) {
                        Text("完成修改", style = LabTypography.Button)
                    }
                    if (!target.isDefault) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                operation = "正在设置…"
                                scope.launch {
                                    try {
                                        onSetDefault()
                                        finish(true, "已设为默认", onDismiss)
                                    } catch (error: Exception) {
                                        finish(false, "设置失败：${error.message.orEmpty()}")
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE6F7F8),
                                contentColor = LabV2.Primary
                            ),
                            elevation = null
                        ) {
                            Text("设为默认", style = LabTypography.Button, color = LabV2.Primary)
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { confirmDelete = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFEE2E2),
                                contentColor = LabV2.Red
                            ),
                            elevation = null
                        ) {
                            Text("删除路由器", style = LabTypography.Button, color = LabV2.Red)
                        }
                    }
                }

                val toastText = operation ?: result?.second
                if (toastText != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xE6262626),
                            shadowElevation = 4.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                }
                                Text(
                                    text = toastText,
                                    color = Color.White,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) Dialog(
        onDismissRequest = { if (!busy) confirmDelete = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("删除路由器？", style = LabTypography.SectionTitle.copy(fontSize = 18.sp))
                Text(
                    "仅从本机列表中移除该路由器，Hub 上的历史数据与链路保持不变。",
                    style = LabTypography.Body.copy(fontSize = 13.sp),
                    color = LabV2.InkMuted
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { confirmDelete = false },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF3F4F6),
                            contentColor = LabV2.Ink
                        ),
                        elevation = null
                    ) {
                        Text("取消", style = LabTypography.Button)
                    }
                    Button(
                        onClick = {
                            confirmDelete = false
                            operation = "正在删除…"
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { forgetRouterWorkspace(context, hubRoot, target.routerId) }
                                    finish(true, "已删除", onDeleted)
                                } catch (error: Exception) {
                                    finish(false, "删除失败：${error.message.orEmpty()}")
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LabV2.Red, contentColor = Color.White),
                        elevation = null
                    ) {
                        Text("删除", style = LabTypography.Button)
                    }
                }
            }
        }
    }
}

@Composable
fun RouterWorkspaceSetupScreen(
    name: String,
    onOpenSettings: () -> Unit,
    onOpenSwitch: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F5F8)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            TextButton(onClick = onOpenSwitch) { Text("切换路由器") }
            Spacer(Modifier.weight(1f))
            Text(name, style = LabTypography.SectionTitle)
            Spacer(Modifier.size(8.dp))
            Text("这台路由器尚未配置", style = LabTypography.CardTitle, color = LabV2.InkMuted)
            Spacer(Modifier.size(20.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary)) {
                Text("打开 APP 设置")
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
