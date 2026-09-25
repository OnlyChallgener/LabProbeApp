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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
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
)

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
    RouterWorkspace(DEFAULT_ROUTER_WORKSPACE_ID, "默认路由器", "", "", false, "")

fun parseRouterWorkspaces(root: JSONObject): List<RouterWorkspace> {
    val rows = root.optJSONArray("routers") ?: return listOf(defaultRouterWorkspace())
    val defaultRouterId = root.optString("defaultRouterId").trim().takeIf(::validRouterWorkspaceId)
    // 缺字段和 0 不是一回事：0 是「一台设备都没有」，缺才是「还不知道」。
    fun count(item: JSONObject, key: String): Int? =
        if (!item.has(key) || item.isNull(key)) null else item.optInt(key, -1).takeIf { it >= 0 }
    val parsed = buildList {
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val serverId = item.optString("routerId").trim()
            if (!validRouterWorkspaceId(serverId)) continue
            val id = if (serverId == defaultRouterId) DEFAULT_ROUTER_WORKSPACE_ID else serverId
            val expectedPath = routerWorkspacePath(id)
            val suppliedPath = item.optString("basePath").trim().trimEnd('/')
            // The Hub may identify its root worker by a real router ID. It is still
            // the legacy/default workspace in the App, not a third router card.
            val acceptedPaths = if (serverId == defaultRouterId) {
                setOf("", routerWorkspacePath(serverId))
            } else setOf(expectedPath)
            if (suppliedPath !in acceptedPaths) continue
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
                )
            )
        }
    }.distinctBy { it.routerId }
    val default = parsed.firstOrNull { it.routerId == DEFAULT_ROUTER_WORKSPACE_ID } ?: defaultRouterWorkspace()
    return listOf(default) + parsed.filterNot { it.routerId == DEFAULT_ROUTER_WORKSPACE_ID }
}

fun selectableWorkspaceId(workspaces: List<RouterWorkspace>, requested: String, listingConfirmed: Boolean): String =
    if (listingConfirmed && workspaces.any { it.routerId == requested }) requested else DEFAULT_ROUTER_WORKSPACE_ID

private fun workspaceDigest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun routerWorkspacePreferencesName(routerId: String, hubRoot: String): String {
    require(validRouterWorkspaceId(routerId))
    val identity = normalizeHubBaseUrl(hubRoot) + "|" + routerId
    return "labprobe_workspace_${workspaceDigest(identity)}"
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

class RouterWorkspaceApi(private val defaultPrefs: AppPrefs) {
    suspend fun list(): List<RouterWorkspace> = withContext(Dispatchers.IO) {
        parseRouterWorkspaces(HubApi(defaultPrefs).requestJson("/api/routers"))
    }
}

/** Per-router Android Keystore storage for tokens and optional SSH passwords. */
internal class SecureWorkspaceStringStore(context: Context, routerId: String, hubRoot: String, purpose: String) {
    private val identity = normalizeHubBaseUrl(hubRoot) + "|" + routerId
    private val prefs = context.applicationContext.getSharedPreferences(
        "labprobe_workspace_secure_${workspaceDigest(identity)}", Context.MODE_PRIVATE
    )
    private val alias = "labprobe_workspace_${workspaceDigest(identity)}_${workspaceDigest(purpose)}"
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
    require(routerId != DEFAULT_ROUTER_WORKSPACE_ID) { "默认路由器不能删除" }
    val app = context.applicationContext
    val digest = workspaceDigest(normalizeHubBaseUrl(hubRoot) + "|" + routerId)
    app.getSharedPreferences(routerWorkspacePreferencesName(routerId, hubRoot), Context.MODE_PRIVATE)
        .edit().clear().commit()
    app.getSharedPreferences("labprobe_workspace_secure_$digest", Context.MODE_PRIVATE)
        .edit().clear().commit()
    runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.aliases().toList()
            .filter { it.startsWith("labprobe_workspace_${digest}_") }
            .forEach { runCatching { store.deleteEntry(it) } }
    }
    val selection = app.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE)
    val key = "selected_${workspaceDigest(normalizeHubBaseUrl(hubRoot))}"
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
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 整块透明，让里面那张圆角卡片自己浮着 —— 官方那台列表就是两边留空的卡片，
        // 不是贴满屏幕宽度的抽屉。抽屉把手也去掉，标题行本身就是抓手。
        containerColor = Color.Transparent,
        dragHandle = null,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
        ) {
            // 照官方那样：一行标题、一台一行，点那行就切过去。
            // 原来底部那个通栏「关闭」按钮删了 —— 下滑和点遮罩本来就能关，它只是把弹层
            // 撑成一整屏，中间全是留白。
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("切换路由器", style = LabTypography.SectionTitle, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = LabV2.Primary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (loading) "刷新中…" else "刷新", style = LabTypography.Button)
                }
            }
            if (error.isNotBlank()) Text(error, style = LabTypography.Caption, color = LabV2.Red)
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                workspaces.forEach { item ->
                    val selected = item.routerId == selectedId
                    val selectable = listingConfirmed && !selected
                    val accent = if (item.online) LabV2.Green else LabV2.InkMuted
                    Surface(
                        modifier = Modifier.fillMaxWidth().then(
                            if (selectable) Modifier.clickable { onSelect(item.routerId) } else Modifier
                        ),
                        shape = RoundedCornerShape(13.dp),
                        color = if (selected) Color(0xFFEAF6FF) else Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, if (selected) Color(0xFF57A8D7) else Color(0xFFE3E9EF)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 11.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Router, null, Modifier.size(20.dp), tint = Color(0xFF2381AE))
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(item.name, style = LabTypography.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // 一条副行就够：状态点和数字在前，型号丢了不影响判断。
                                val detail = listOf(item.site, item.model).filter(String::isNotBlank).joinToString(" · ")
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Box(Modifier.size(6.dp).background(accent, CircleShape))
                                    Text(
                                        routerWorkspaceCountsLine(item) + if (detail.isBlank()) "" else " · $detail",
                                        style = LabTypography.Caption,
                                        color = accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (selected) Icon(Icons.Rounded.CheckCircle, "当前路由器", Modifier.size(19.dp), tint = LabV2.Green)
                            // 官方的铅笔：嵌套 clickable 会吃掉这一格的点击，不会顺手切换。
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onEdit(item) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Edit, "编辑${item.name}", Modifier.size(16.dp), tint = LabV2.InkMuted)
                            }
                        }
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


/**
 * 编辑一台路由器：改名字、删除。动作由弹层自己做，所以按钮能显示自己真实的进行中状态，
 * 结果再用浮层 toast 兜底 —— 静默两秒再突然关窗是最难看的反馈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterWorkspaceEditDialog(
    target: RouterWorkspace,
    hubRoot: String,
    onDismiss: () -> Unit,
    onNameSaved: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(target.routerId) { mutableStateOf(target.name) }
    var saving by remember(target.routerId) { mutableStateOf(false) }
    var deleting by remember(target.routerId) { mutableStateOf(false) }
    var confirmDelete by remember(target.routerId) { mutableStateOf(false) }
    val deletable = target.routerId != DEFAULT_ROUTER_WORKSPACE_ID
    ModalBottomSheet(
        onDismissRequest = { if (!saving && !deleting) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text("编辑", style = LabTypography.SectionTitle)
            OutlinedTextField(
                value = name,
                onValueChange = { if (!saving) name = it },
                label = { Text("路由器名称") },
                singleLine = true,
                enabled = !saving && !deleting,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val clean = name.trim()
                    if (clean.isBlank()) {
                        toast(context, "路由器名称不能为空")
                    } else {
                        saving = true
                        AppPrefs(context, target.routerId).routerDisplayName = clean
                        toast(context, "已保存路由器名称：$clean")
                        saving = false
                        onNameSaved()
                        onDismiss()
                    }
                },
                enabled = !deleting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary, contentColor = Color.White),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                Text(if (saving) "保存中…" else "完成修改", Modifier.padding(start = if (saving) 8.dp else 0.dp), style = LabTypography.Button)
            }
            if (deletable) {
                if (!confirmDelete) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                    ) { Text("删除路由器", style = LabTypography.Button, color = LabV2.Red) }
                } else {
                    Text("删除只清本机保存的令牌、隧道和缓存；Hub 上的数据要动它得重新连上这台路由。", style = LabTypography.Caption, color = LabV2.InkMuted)
                    Button(
                        onClick = {
                            deleting = true
                            scope.launch {
                                withContext(Dispatchers.IO) { forgetRouterWorkspace(context, hubRoot, target.routerId) }
                                toast(context, "已删除路由器：${target.name}")
                                deleting = false
                                onDeleted()
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = LabV2.Red),
                        border = BorderStroke(1.dp, LabV2.Border),
                    ) {
                        if (deleting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = LabV2.Red)
                        Text(if (deleting) "删除中…" else "确认删除", Modifier.padding(start = if (deleting) 8.dp else 0.dp), style = LabTypography.Button)
                    }
                    TextButton(onClick = { confirmDelete = false }, enabled = !deleting) { Text("取消删除") }
                }
            } else {
                Text("默认路由器不提供删除：它的设置和全局偏好存在同一个文件里。", style = LabTypography.Caption, color = LabV2.InkMuted)
            }
            TextButton(onClick = onDismiss, enabled = !saving && !deleting) { Text("关闭") }
        }
    }
}
