package com.labprobe.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun JSONArray.luckyRows(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal class LuckyApi(prefs: AppPrefs) {
    private val hub = HubApi(prefs)
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        hub.requestJson("/api/lucky$path", method, body).also {
            check(it.optBoolean("ok")) { it.optString("error", "Lucky 操作失败") }
        }
    }
}

/** Independent connection state; never writes router/SSH/Hub preferences. */
@Composable
internal fun LuckyManagementScreen(prefs: AppPrefs, sourceOptions: List<FavoriteAddressOption> = emptyList(), onBack: () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = LabV2.Primary, onPrimary = Color.White,
        primaryContainer = LabV2.Field, onPrimaryContainer = LabV2.PrimaryStrong,
        secondary = LabV2.Primary, secondaryContainer = LabV2.Field, onSecondaryContainer = LabV2.PrimaryStrong,
        tertiary = LabV2.Cyan, tertiaryContainer = LabV2.Field, onTertiaryContainer = LabV2.PrimaryStrong)) {
        LuckyManagementContent(prefs, sourceOptions, onBack)
    }
}

@Composable private fun LuckyManagementContent(prefs: AppPrefs, sourceOptions: List<FavoriteAddressOption>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val api = remember(prefs.hub, prefs.token) { LuckyApi(prefs) }
    val snapshots = remember(context, prefs.hub, prefs.token) { LuckySnapshotStore(context, prefs.hub, prefs.token) }
    val initialConnections = remember(snapshots) { snapshots.connections() }
    val initialSelected = remember(snapshots) { initialConnections.firstOrNull { it.optString("id") == snapshots.selectedId } ?: initialConnections.firstOrNull() }
    val initialSnapshot = remember(snapshots) { initialSelected?.optString("id")?.let(snapshots::instance) }
    val scope = rememberCoroutineScope()
    var connections by remember(snapshots) { mutableStateOf(initialConnections) }
    var selected by remember(snapshots) { mutableStateOf(initialSelected) }
    var rules by remember(snapshots) { mutableStateOf(initialSnapshot?.rules.orEmpty()) }
    var certs by remember(snapshots) { mutableStateOf(initialSnapshot?.certificates.orEmpty()) }
    var accounts by remember(snapshots) { mutableStateOf(initialSnapshot?.accounts.orEmpty()) }
    var tab by remember(snapshots) { mutableIntStateOf(snapshots.selectedTab) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var connectionEditor by remember { mutableStateOf(false) }
    var newConnection by remember { mutableStateOf(false) }
    var ruleEditor by remember { mutableStateOf<JSONObject?>(null) }
    var subruleEditor by remember { mutableStateOf<JSONObject?>(null) }
    var applyCertificate by remember { mutableStateOf(false) }
    var accountEditor by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = ""
            try { action() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败，请刷新核对" }
            finally { busy = false }
        }
    }
    suspend fun refresh() {
        val newConnections = api.call("/connections").getJSONArray("items").luckyRows()
        connections = newConnections
        snapshots.saveConnections(newConnections)
        val active = newConnections.firstOrNull { it.optString("id") == selected?.optString("id") } ?: newConnections.firstOrNull()
        if (active?.optString("id") != selected?.optString("id")) {
            val cached = active?.optString("id")?.let(snapshots::instance)
            rules = cached?.rules.orEmpty(); certs = cached?.certificates.orEmpty(); accounts = cached?.accounts.orEmpty()
        }
        selected = active
        if (active != null) {
            val id = active.getString("id")
            // Assign only after complete successful reads; startup failures retain the displayed snapshot.
            val nextRules = api.call("/$id/rules").getJSONArray("items").luckyRows()
            val nextCerts = api.call("/$id/certificates")
            rules = nextRules
            certs = nextCerts.getJSONArray("items").luckyRows()
            accounts = nextCerts.getJSONArray("accounts").luckyRows()
            snapshots.saveInstance(id, LuckyInstanceSnapshot(rules, certs, accounts))
        }
    }
    LaunchedEffect(selected?.optString("id"), tab) { snapshots.select(selected?.optString("id").orEmpty(), tab) }
    LaunchedEffect(api) { run { refresh() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        LuckyPageHeader(busy, onBack, { run { refresh() } }, { newConnection = false; connectionEditor = true })
        var picker by remember { mutableStateOf(false) }
        if (connections.isNotEmpty()) Box(Modifier.padding(top = 14.dp, bottom = 10.dp)) {
            Surface(onClick = { picker = true }, enabled = !busy, shape = LabV2.CompactCardShape,
                color = LabV2.CardTop, border = BorderStroke(1.dp, LabV2.Border)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LuckyBrandIcon(34)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(selected?.optString("name").orEmpty(), style = LabTypography.Body.copy(fontWeight = FontWeight.SemiBold))
                        Text(selected?.optString("url").orEmpty(), style = LabTypography.Supporting)
                    }
                    Icon(Icons.Rounded.KeyboardArrowDown, "切换 Lucky", Modifier.size(20.dp), tint = LabV2.Primary)
                }
            }
            LabDropdownMenu(picker, { picker = false }, minWidth = 68.dp) {
                connections.forEach { c -> LabDropdownMenuItem({ Text(c.optString("name")) }, {
                    picker = false
                    if (selected?.optString("id") != c.optString("id")) {
                        selected = c
                        val cached = snapshots.instance(c.optString("id"))
                        rules = cached?.rules.orEmpty(); certs = cached?.certificates.orEmpty(); accounts = cached?.accounts.orEmpty()
                    }
                    run { refresh() }
                }) }
                LabDropdownMenuItem({ Text("新增连接") }, { picker = false; newConnection = true; connectionEditor = true })
            }
        }
        if (error.isNotBlank()) Surface(color = LabV2.Red.copy(alpha = .06f), shape = LabPopupShapes.Card,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(error, style = LabTypography.DialogBody.copy(color = LabV2.Red), modifier = Modifier.padding(12.dp))
        }
        if (connections.isEmpty()) {
            LuckyEmptyConnectionState(busy, Modifier.weight(1f)) { newConnection = true; connectionEditor = true }
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = LabV2.Field, shape = LabPopupShapes.Card, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("反向代理", "证书管理").forEachIndexed { index, title ->
                            Surface(onClick = { tab = index }, shape = LabPopupShapes.Field,
                                color = if (tab == index) LabV2.CardTop else androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.weight(1f)) {
                                Box(Modifier.heightIn(min = 34.dp).padding(horizontal = 8.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                                    Text(title, style = LabTypography.Body.copy(color = if (tab == index) LabV2.Primary else LabV2.InkMuted,
                                        fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal))
                                }
                            }
                        }
                    }
                }
                FilledTonalButton(onClick = {
                    if (tab == 0) ruleEditor = JSONObject() else applyCertificate = true
                }, enabled = selected != null && !busy, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    shape = LabPopupShapes.Field, colors = ButtonDefaults.filledTonalButtonColors(containerColor = LabV2.Field,
                        contentColor = LabV2.Primary)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(3.dp))
                    Text(if (tab == 0) "新建" else "申请", style = LabTypography.Button)
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab == 0) items(rules, key = { it.getString("id") }) { rule ->
                    LuckyRuleCard(rule, busy, onEdit = {
                        if (!rule.optBoolean("editable")) {
                            error = "此规则包含多域名或多后端，请在 Lucky 后台编辑"
                        } else run {
                            val version = api.call("/${selected!!.getString("id")}/rules/${rule.getString("id")}").get("version")
                            ruleEditor = JSONObject(rule.toString()).put("version", version)
                        }
                    }, onToggle = { run {
                        val path = "/${selected!!.getString("id")}/rules/${rule.getString("id")}"
                        val detail = api.call(path)
                        api.call(path, "PUT", JSONObject().put("version", detail.get("version")).put("enabled", !rule.optBoolean("enabled")))
                        refresh()
                    } }, onDelete = { confirm = "删除反代 ${rule.optString("name")}及其全部子规则？" to { run {
                        api.call("/${selected!!.getString("id")}/rules/${rule.getString("id")}", "DELETE"); refresh()
                    } } }, onSubEdit = { route -> run {
                        val path = "/${selected!!.getString("id")}/rules/${rule.getString("id")}/subrules/${route.id}"
                        val data = api.call(path)
                        if (!data.getJSONObject("item").optBoolean("editable")) error = "此子规则使用高级服务或后端池，请在 Lucky 后台编辑"
                        else subruleEditor = data.put("path", path)
                    } }, onSubToggle = { route -> run {
                        val path = "/${selected!!.getString("id")}/rules/${rule.getString("id")}/subrules/${route.id}"
                        val data = api.call(path)
                        api.call(path, "PUT", JSONObject().put("version", data.get("version")).put("enabled", !route.enabled))
                        refresh()
                    } }, onSubDelete = { route -> run {
                        val path = "/${selected!!.getString("id")}/rules/${rule.getString("id")}/subrules/${route.id}"
                        val data = api.call(path)
                        val label = route.name.ifBlank { route.domains.joinToString(", ") }.ifBlank { "此子规则" }
                        confirm = "删除子规则 $label？" to { run {
                            api.call(path, "DELETE", JSONObject().put("ruleVersion", data.get("ruleVersion")))
                            refresh()
                        } }
                    } })
                }
                if (tab == 1) items(certs, key = { it.getString("id") }) { cert ->
                    var menu by remember { mutableStateOf(false) }
                    LuckyCard(luckyCertificateCopyText(cert)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cert.optJSONArray("domains")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty().ifBlank { cert.optString("name") }, Modifier.weight(1f), fontSize = 14.sp)
                            Box { IconButton({ menu = true }, enabled = !busy, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "证书操作", Modifier.size(19.dp)) }
                                LabDropdownMenu(menu, { menu = false }, minWidth = 68.dp, actionMenu = true) {
                                    LabDropdownMenuItem({ Text("检查续签") }, { menu = false; run {
                                        api.call("/${selected!!.getString("id")}/certificates/${cert.getString("id")}/renew", "POST"); refresh()
                                    } }, enabled = cert.optBoolean("automatic"))
                                    LabDropdownMenuItem({ Text(if (cert.optBoolean("enabled")) "停用" else "启用") }, { menu = false; run {
                                        api.call("/${selected!!.getString("id")}/certificates/${cert.getString("id")}/enabled", "POST", JSONObject().put("enabled", !cert.optBoolean("enabled"))); refresh()
                                    } })
                                    LabDropdownMenuItem({ Text("删除", color = LabV2.Red) }, { menu = false; confirm = "删除此证书？可能影响使用它的反代。" to { run {
                                        api.call("/${selected!!.getString("id")}/certificates/${cert.getString("id")}/delete", "POST"); refresh()
                                    } } })
                                    LabDropdownMenuItem({ Text("复制全部") }, { menu = false; copy(context, luckyCertificateCopyText(cert)) })
                                }
                            }
                        }
                        Text(when { cert.optBoolean("issuing") -> "申请中"; cert.optBoolean("failed") -> "申请异常，请查看 Lucky 日志"; !cert.optBoolean("enabled") -> "已停用"; else -> "到期：${cert.optString("expires").ifBlank { "待签发" }}" }, fontSize = 12.sp)
                        Text(if (cert.optBoolean("automatic")) "Lucky 自动续签" else "导入证书", color = LabV2.InkMuted, fontSize = 12.sp)
                    }
                }
                item {
                    if (tab == 1) TextButton({ accountEditor = true }, enabled = !busy) { Text("添加 DNS 账户", fontSize = 13.sp) }
                    if ((if (tab == 0) rules else certs).isEmpty() && !busy) Text("暂无${if (tab == 0) "反代规则" else "证书"}", fontSize = 13.sp)
                    TextButton(onClick = { selected?.optString("url")?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }) { Text("打开 Lucky 后台", fontSize = 13.sp) }
                }
            }
        }
    }
    if (connectionEditor) LuckyConnectionEditor(if (newConnection) null else selected, api, onDismiss = { connectionEditor = false }, onSaved = {
        connectionEditor = false; run { refresh() }
    })
    ruleEditor?.let { draft -> LuckyRuleEditor(draft, busy, error, sourceOptions, onDismiss = { ruleEditor = null }, onSave = { body -> run {
        val base = "/${selected!!.getString("id")}/rules"
        if (draft.has("id")) api.call("$base/${draft.getString("id")}", "PUT", body.put("version", draft.get("version")))
        else api.call(base, "POST", body)
        ruleEditor = null; refresh()
    } }) }
    subruleEditor?.let { draft -> LuckySubruleEditor(draft.getJSONObject("item"), busy, error,
        onDismiss = { subruleEditor = null }, onSave = { body -> run {
            api.call(draft.getString("path"), "PUT", body.put("version", draft.get("version")))
            subruleEditor = null; refresh()
        } }) }
    if (applyCertificate) LuckyCertificateEditor(accounts, busy, error, { applyCertificate = false }) { body -> run {
        api.call("/${selected!!.getString("id")}/certificates", "POST", body)
        applyCertificate = false; refresh()
    } }
    if (accountEditor) LuckyAccountEditor(busy, error, { accountEditor = false }) { body -> run {
        api.call("/${selected!!.getString("id")}/accounts", "POST", body)
        accountEditor = false; refresh()
    } }
    confirm?.let { (message, action) ->
        LabEditorDialog("确认删除", { confirm = null }, actions = {
            OutlinedButton({ confirm = null }, Modifier.weight(1f)) { Text("取消") }
            Button({ confirm = null; action() }, Modifier.weight(1f)) { Text("删除") }
        }) { Text(message) }
    }
}

@Composable internal fun LuckyPageHeader(busy: Boolean, onBack: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    CompactPageHeader("Lucky", "反向代理与证书", onBack = onBack,
        titleStyle = LabTypography.PageTitle.copy(fontWeight = FontWeight.SemiBold), action = {
            IconButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.size(38.dp)) {
                LabRefreshIcon(busy, Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings, enabled = !busy, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.Settings, "连接设置", Modifier.size(20.dp), tint = LabV2.Primary)
            }
        })
}

@Composable private fun LuckyBrandIcon(size: Int) {
    Image(painterResource(R.drawable.favorite_lucky), "Lucky", Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)))
}

@Composable internal fun LuckyEmptyConnectionState(loading: Boolean, modifier: Modifier = Modifier, onConnect: () -> Unit) {
    Box(modifier.fillMaxWidth().padding(top = 36.dp), contentAlignment = Alignment.TopCenter) {
        Surface(shape = LabV2.CardShape, color = LabV2.CardTop, border = BorderStroke(1.dp, LabV2.Border),
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LuckyBrandIcon(48)
                Spacer(Modifier.height(12.dp))
                Text(if (loading) "正在读取连接" else "连接 Lucky", style = LabTypography.DialogTitle.copy(fontSize = 17.sp))
                Spacer(Modifier.height(6.dp))
                Text("读取已有反代与证书", style = LabTypography.DialogBody.copy(color = LabV2.InkMuted))
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LuckyCapabilityTile(Icons.Rounded.Public, "反向代理", "域名与后端", Modifier.weight(1f))
                    LuckyCapabilityTile(Icons.Rounded.VerifiedUser, "SSL 证书", "申请与自动续签", Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Button(onConnect, enabled = !loading, shape = LabV2.ButtonShape, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary, contentColor = androidx.compose.ui.graphics.Color.White),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)) {
                    if (loading) Text("正在读取", style = LabTypography.Button) else {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("添加连接", style = LabTypography.Button)
                    }
                }
            }
        }
    }
}

@Composable private fun LuckyCapabilityTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier) {
    Surface(modifier, shape = LabPopupShapes.Card, color = LabV2.FieldSoft) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = LabV2.Primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = LabTypography.Body)
                Text(subtitle, style = LabTypography.Supporting)
            }
        }
    }
}

@Composable private fun LuckyCard(copyText: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color.White, Color(0xFFF2FAFF))))) {
            LuckyCopySelection(copyText) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            }
        }
    }
}

@Composable internal fun LuckyRuleCard(rule: JSONObject, busy: Boolean, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit,
    onSubEdit: (LuckyRouteDisplay) -> Unit = {}, onSubToggle: (LuckyRouteDisplay) -> Unit = {}, onSubDelete: (LuckyRouteDisplay) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LuckyCard(luckyRuleCopyText(rule)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rule.optString("name"), Modifier.weight(1f), fontSize = 14.sp)
            DisableSelection { Row(verticalAlignment = Alignment.CenterVertically) {
            LuckyRuleSwitch(rule.optBoolean("enabled"), !busy, "规则组开关", onToggle)
            Box {
                IconButton({ menu = true }, enabled = !busy, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "规则操作", Modifier.size(19.dp)) }
                LabDropdownMenu(menu, { menu = false }, minWidth = 68.dp, actionMenu = true) {
                    LabDropdownMenuItem({ Text("编辑") }, { menu = false; onEdit() })
                    LabDropdownMenuItem({ Text("复制") }, { menu = false; copy(context, luckyRuleCopyText(rule)) })
                    LabDropdownMenuItem({ Text("删除", color = LabV2.Red) }, { menu = false; onDelete() })
                }
            }
            } }
        }
        luckyRouteDisplays(rule).forEachIndexed { routeIndex, route ->
            if (routeIndex > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp), color = LabV2.Border.copy(alpha = .65f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    route.domains.ifEmpty { listOf("未匹配的域名") }.forEach { domain ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (domain == "未匹配的域名") domain else luckyPublicUrl(rule, domain), fontSize = 13.sp, lineHeight = 18.sp,
                            color = if (route.enabled) LabV2.Primary else LabV2.InkMuted)
                            if (route.backends.isEmpty()) Text(luckyRouteBackendLabel(route), fontSize = 12.sp, lineHeight = 17.sp, color = LabV2.InkMuted)
                            else route.backends.forEach { backend -> Text("→ $backend", fontSize = 12.sp, lineHeight = 17.sp, color = LabV2.InkMuted) }
                        }
                    }
                }
                if (route.id.isNotBlank()) DisableSelection {
                    var subMenu by remember(route.id) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.Top) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LuckyRuleSwitch(route.enabled, !busy, "子规则开关 ${route.id}") { onSubToggle(route) }
                            if (!route.enabled) Text("已停用", style = LabTypography.Caption)
                        }
                        Box {
                            IconButton({ subMenu = true }, enabled = !busy, modifier = Modifier.size(width = 28.dp, height = 36.dp)) {
                                Icon(Icons.Rounded.MoreVert, "子规则操作 ${route.id}", Modifier.size(16.dp), tint = LabV2.InkMuted)
                            }
                            LabDropdownMenu(subMenu, { subMenu = false }, minWidth = 68.dp, actionMenu = true) {
                                LabDropdownMenuItem({ Text("编辑") }, { subMenu = false; onSubEdit(route) })
                                LabDropdownMenuItem({ Text("复制") }, { subMenu = false; copy(context, luckyRouteCopyText(rule, route)) })
                                LabDropdownMenuItem({ Text("删除", color = LabV2.Red) }, { subMenu = false; onSubDelete(route) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LuckyRuleSwitch(checked: Boolean, enabled: Boolean, label: String, onToggle: () -> Unit) {
    Box(Modifier.size(width = 40.dp, height = 36.dp), contentAlignment = Alignment.Center) {
        Switch(checked, { onToggle() }, enabled = enabled,
            modifier = Modifier.requiredSize(48.dp).scale(.7f).semantics { contentDescription = label },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LabV2.Primary,
                uncheckedThumbColor = LabV2.InkMuted, uncheckedTrackColor = LabV2.Field,
                uncheckedBorderColor = LabV2.BorderStrong))
    }
}

@Composable internal fun LuckySubruleEditor(item: JSONObject, busy: Boolean, error: String, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    fun lines(key: String) = item.optJSONArray(key)?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty()
    var name by remember(item.optString("id")) { mutableStateOf(item.optString("name")) }
    var domains by remember(item.optString("id")) { mutableStateOf(lines("domains")) }
    var backends by remember(item.optString("id")) { mutableStateOf(lines("backends")) }
    LabEditorDialog("编辑子规则", { if (!busy) onDismiss() }, actions = {
        OutlinedButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text("取消") }
        Button({ onSave(JSONObject().put("name", name)
            .put("domains", JSONArray(domains.lines().map(String::trim).filter(String::isNotBlank)))
            .put("backends", JSONArray(backends.lines().map(String::trim).filter(String::isNotBlank)))) }, Modifier.weight(1f), enabled = !busy) { Text("保存") }
    }) {
        LuckyConnectionField("备注名称", name, { name = it })
        LuckyMultilineField("域名（每行一个）", domains, { domains = it })
        LuckyMultilineField("后端地址（每行一个）", backends, { backends = it })
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}

@Composable private fun LuckyMultilineField(label: String, value: String, onChange: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LabTypography.FieldLabel)
        Surface(shape = LabPopupShapes.Field, color = Color.Transparent,
            border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) LabV2.Primary else LabV2.BorderStrong)) {
            BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 9.dp),
                minLines = 1, maxLines = 4, textStyle = LabTypography.DialogBody,
                interactionSource = interaction, cursorBrush = androidx.compose.ui.graphics.SolidColor(LabV2.Primary))
        }
    }
}

@Composable private fun LuckyField(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = LabV2.InkMuted)
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, shape = LabPopupShapes.Field,
            trailingIcon = trailing, textStyle = LabTypography.Body.copy(fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = LabV2.BorderStrong),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    }
}

@Composable private fun LuckyConnectionEditor(existing: JSONObject?, api: LuckyApi, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(existing?.optString("name").orEmpty()) }
    var url by remember { mutableStateOf(existing?.optString("url").orEmpty()) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun body() = JSONObject().put("name", name).put("url", url).put("token", token).also { b -> existing?.let { b.put("id", it.getString("id")) } }
    LabEditorDialog("连接 Lucky", onDismiss = { if (!busy) onDismiss() }, actions = {
        OutlinedButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text("取消") }
        Button({ scope.launch {
            busy = true; message = "正在验证连接"
            try {
                api.call("/connections", "POST", body()); token = ""; onSaved()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "连接失败" }
            finally { busy = false }
        } }, Modifier.weight(1f), enabled = !busy) { Text("验证并保存") }
    }) {
        LuckyConnectionForm(name, url, token, existing != null, busy, message,
            { name = it }, { url = it }, { token = it }, { scope.launch {
            busy = true; message = "正在测试"
            try { api.call("/connections/test", "POST", body()); message = "连接正常，反代与证书可读取" }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "连接失败" }
            finally { busy = false }
        } })
    }
}

/** Compact presentation confined to the Lucky connection dialog. */
@Composable internal fun LuckyConnectionForm(
    name: String, url: String, token: String, existing: Boolean, busy: Boolean, message: String,
    onName: (String) -> Unit, onUrl: (String) -> Unit, onToken: (String) -> Unit, onTest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LuckyConnectionField("连接名称", name, onName)
        LuckyConnectionField("Lucky 管理地址（含安全入口）", url, onUrl)
        LuckyConnectionField(if (existing) "OpenToken（留空保留）" else "OpenToken", token, onToken, secret = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("测试连接", style = LabTypography.Button.copy(color = if (busy) LabV2.InkMuted else LabV2.Primary),
                modifier = Modifier.clip(LabPopupShapes.Menu).clickable(enabled = !busy, role = Role.Button, onClick = onTest)
                    .heightIn(min = 30.dp).wrapContentHeight().padding(horizontal = 4.dp, vertical = 4.dp))
            if (message.isNotBlank()) Text(message, Modifier.weight(1f), style = LabTypography.DialogBody.copy(fontSize = 12.sp, lineHeight = 17.sp))
        }
    }
}

@Composable private fun LuckyConnectionField(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LabTypography.Body.copy(fontSize = 12.sp, lineHeight = 16.sp, color = LabV2.InkMuted))
        Surface(shape = LabPopupShapes.Field, color = androidx.compose.ui.graphics.Color.Transparent,
            border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) LabV2.Primary else LabV2.BorderStrong), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 12.dp, end = if (secret) 4.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onChange, Modifier.weight(1f).padding(vertical = 9.dp), singleLine = true,
                textStyle = LabTypography.DialogBody, interactionSource = interaction,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(LabV2.Primary),
                visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None)
            if (secret) IconButton({ visible = !visible }, Modifier.size(32.dp)) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    if (visible) "隐藏 OpenToken" else "显示 OpenToken", Modifier.size(18.dp), tint = LabV2.InkMuted)
            }
            }
        }
    }
}

@Composable private fun LuckyAccountEditor(busy: Boolean, error: String, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf("") }
    var accessId by remember { mutableStateOf("") }
    var accessSecret by remember { mutableStateOf("") }
    LabEditorDialog("添加 DNS 账户", { if (!busy) onDismiss() }, actions = {
        OutlinedButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text("取消") }
        Button({ onSave(JSONObject().put("name", name).put("accessId", accessId).put("accessSecret", accessSecret)) }, Modifier.weight(1f), enabled = !busy) { Text("保存") }
    }) {
        Text("阿里云 DNS", fontSize = 13.sp)
        LuckyField("账户名称", name, { name = it })
        LuckyField("AccessKey ID", accessId, { accessId = it }, true)
        LuckyField("AccessKey Secret", accessSecret, { accessSecret = it }, true)
        Text("其他服务商可复用 Lucky 已有账户", fontSize = 12.sp, color = LabV2.InkMuted)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}

@Composable internal fun LuckyRuleEditor(existing: JSONObject, busy: Boolean, error: String, sourceOptions: List<FavoriteAddressOption> = emptyList(), onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf(existing.optString("name")) }
    var domain by remember { mutableStateOf(existing.optString("domain")) }
    var backend by remember { mutableStateOf(existing.optString("backend")) }
    var port by remember { mutableStateOf(existing.optInt("port", 8443).toString()) }
    var tls by remember { mutableStateOf(existing.optBoolean("tls", true)) }
    val operation = remember(name, domain, backend, port, tls) { UUID.randomUUID().toString() }
    LabEditorDialog(if (existing.has("id")) "编辑反向代理" else "新增反向代理", { if (!busy) onDismiss() }, actions = {
        OutlinedButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text("取消") }
        Button({ onSave(JSONObject().put("name", name).put("domain", domain).put("backend", backend).put("port", port.toIntOrNull() ?: 0).put("tls", tls).put("operationId", operation)) }, Modifier.weight(1f), enabled = !busy) { Text("保存") }
    }) {
        LuckyProxyField("服务名称", name, { name = it })
        LuckyProxyField("外部访问域名", domain, { domain = it })
        if (sourceOptions.isEmpty()) LuckyProxyField("后端地址", backend, { backend = it }) else {
            var sources by remember { mutableStateOf(false) }
            var group by remember { mutableStateOf<String?>(null) }
            Box {
                LuckyProxyField("后端地址", backend, { backend = it }, trailing = {
                    Box(Modifier.size(44.dp).clip(LabPopupShapes.Menu)
                        .clickable(role = Role.Button) { sources = true; group = null }
                        .semantics { contentDescription = "从已有地址选择" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(20.dp), tint = LabV2.InkMuted)
                    }
                })
                LabDropdownMenu(sources, { sources = false }, minWidth = 68.dp) {
                    if (group == null) sourceOptions.map { it.group }.distinct().forEach { name ->
                        LabDropdownMenuItem({ Text(name) }, { group = name })
                    } else {
                        LabDropdownMenuItem({ Text("返回") }, { group = null })
                        sourceOptions.filter { it.group == group }.forEach { option ->
                            LabDropdownMenuItem({ Text(favoriteSourceOptionLabel(option)) }, {
                                val next = if (option.endpoint.contains("://")) option.endpoint else "http://${option.endpoint}"
                                backend = if (backend.isBlank()) next else favoriteFollowEndpoint(backend, next)
                                sources = false
                            })
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("HTTPS", Modifier.weight(1f)); Switch(tls, { tls = it }) }
        LuckyProxyField("监听端口", port, { port = it })
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}

/** Short single-line fields used only by Lucky's basic reverse-proxy editor. */
@Composable private fun LuckyProxyField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LabTypography.Body.copy(fontSize = 12.sp, lineHeight = 16.sp, color = LabV2.InkMuted))
        Surface(shape = LabPopupShapes.Field, color = Color.Transparent,
            border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) LabV2.Primary else LabV2.BorderStrong),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .padding(start = 12.dp, end = if (trailing == null) 12.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value, onChange, Modifier.weight(1f).padding(vertical = 7.dp), singleLine = true,
                    textStyle = LabTypography.DialogBody.copy(lineHeight = 18.sp), interactionSource = interaction,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LabV2.Primary))
                trailing?.invoke()
            }
        }
    }
}

@Composable private fun LuckyCertificateEditor(accounts: List<JSONObject>, busy: Boolean, error: String, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var domains by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(accounts.firstOrNull()) }
    var menu by remember { mutableStateOf(false) }
    val operation = remember(domains, account?.optString("id")) { UUID.randomUUID().toString() }
    LabEditorDialog("申请证书", { if (!busy) onDismiss() }, actions = {
        OutlinedButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text("取消") }
        Button({ onSave(JSONObject().put("domains", domains).put("accountId", account?.optString("id")).put("operationId", operation)) }, Modifier.weight(1f), enabled = !busy && account != null) { Text("申请") }
    }) {
        LuckyField("域名（多个用英文逗号分隔）", domains, { domains = it })
        Box {
            OutlinedButton({ menu = true }, Modifier.fillMaxWidth(), shape = LabPopupShapes.Field) { Text(account?.optString("name") ?: "先在 Lucky 后台配置 DNS 账户") }
            LabDropdownMenu(menu, { menu = false }, minWidth = 68.dp) { accounts.forEach { row ->
                LabDropdownMenuItem({ Text("${row.optString("name")} · ${row.optString("provider")}") }, { account = row; menu = false })
            } }
        }
        Text("由 Lucky 自动续签", fontSize = 12.sp, color = LabV2.InkMuted)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}
