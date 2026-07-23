#!/usr/bin/env python3
"""Apply the router settings/navigation/DDNS/diagnostic source fixes before build.

The project keeps a very large legacy MainActivity.kt.  This idempotent patcher
makes small, reviewable replacements without duplicating the whole file.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN_PATH = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
UI_PATH = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing patch pattern: {label}")
    return text.replace(old, new, 1)


def patch_main() -> None:
    main = MAIN_PATH.read_text(encoding="utf-8")
    main = replace_once(
        main,
        '            route == "router_status" -> "home"\n',
        '            route == "router_status" -> "home"\n            route == "router_settings" -> "home"\n',
        "router settings normalization",
    )
    main = replace_once(
        main,
        '            if (target.startsWith("tool_")) toolReturnRoute = if (route in mainRoutes) route else normalized\n',
        '            if (target.startsWith("tool_")) toolReturnRoute = when {\n                route == "router_settings" -> "router_settings"\n                route in mainRoutes -> route\n                else -> normalized\n            }\n',
        "tool return route",
    )
    main = replace_once(
        main,
        '        BackHandler(route.startsWith("tool_") || route == "daily" || route == "health_score" || route == "router_status" || route == "wol" || route == "device_traffic" || route == "device_detail" || route == "settings") {\n',
        '        BackHandler(route.startsWith("tool_") || route == "daily" || route == "health_score" || route == "router_status" || route == "router_settings" || route == "wol" || route == "device_traffic" || route == "device_detail" || route == "settings") {\n',
        "router settings back handler",
    )
    main = replace_once(
        main,
        '                "router_status" -> "home"\n',
        '                "router_status" -> "home"\n                "router_settings" -> "home"\n',
        "router settings back target",
    )
    main = replace_once(
        main,
        '                        "router_status" -> RouterStatusScreen(prefs, state, onBack = { route = "home" }, onOpenDevices = { route = "devices" })\n',
        '                        "router_status" -> RouterStatusScreen(prefs, state, onBack = { route = "home" }, onOpenDevices = { route = "devices" })\n                        "router_settings" -> RouterSettingsScreen(prefs, onBack = { route = "home" }) { target -> navigate(target) }\n',
        "router settings screen route",
    )

    old_small = '''                        HealthMiniCard(
                            title = "VPN / STUN",
                            value = "${vpnRows.size}",
                            unit = "条",
                            icon = Icons.Rounded.VpnKey,
                            accent = Color(0xFF7C3AED),
                            subtitle = vpnRows.firstOrNull()?.first ?: "暂无地址",
                            modifier = Modifier.weight(1f).clickable { onNavigate("events") }
                        )'''
    new_small = old_small.replace('onNavigate("events")', 'onNavigate("tool_router_ddns")')
    main = replace_once(main, old_small, new_small, "small VPN card")

    old_big = '''                    "vpn" -> if (vpnRows.isNotEmpty()) HealthVpnCard(
                        rows = vpnRows,
                        privacyMode = privacyMode,
                        onTogglePrivacy = {
                            privacyMode = !privacyMode
                            prefs.privacyMode = privacyMode
                        },
                        onClick = { onNavigate("events") }
                    )'''
    new_big = '                    "vpn" -> RouterSettingsHomeCard { onNavigate("router_settings") }'
    main = replace_once(main, old_big, new_big, "large router settings card")

    tools_start = main.index('@Composable\nfun ToolsHomeScreen')
    tools_end = main.index('\n@Composable\nfun ReorderableToolSection', tools_start)
    tools = main[tools_start:tools_end]
    if "var routerFirewallEnabled" in tools:
        tools = re.sub(
            r'(fun ToolsHomeScreen\(prefs: AppPrefs, topNav: @Composable \(\) -> Unit, open: \(String\) -> Unit\) = ScreenShell\("工具箱", "长按功能卡可调整分组顺序", topNav = topNav\) \{\n).*?(    val ctx = LocalContext.current\n)',
            r'\1\2',
            tools,
            count=1,
            flags=re.S,
        )
    if "RouterFeatureRail(" in tools:
        tools = re.sub(
            r'\n    RouterFeatureRail\(.*?\n    \)\n\n    val toolSections',
            '\n    val toolSections',
            tools,
            count=1,
            flags=re.S,
        )
    tools = tools.replace(
        '                ToolMosaicItem("端口映射", Icons.Rounded.SwapHoriz, LabV2.Primary, "tool_portmap")',
        '                ToolMosaicItem("WOL 唤醒", Icons.Rounded.PowerSettingsNew, LabV2.Primary, "wol")',
        1,
    )
    main = main[:tools_start] + tools + main[tools_end:]
    MAIN_PATH.write_text(main, encoding="utf-8")


def patch_router_ui() -> None:
    ui = UI_PATH.read_text(encoding="utf-8")
    if "显示上次结果 · 仅点击按钮时重新检测" in ui and "DdnsRecordsSection(prefs, Modifier.weight(1f))" in ui:
        return

    ddns_start = ui.index('@Composable\nfun RouterDdnsScreen')
    diag_start = ui.index('@Composable\nfun RouterDiagnosticScreen', ddns_start)
    ddns = r'''@Composable
fun RouterDdnsScreen(prefs: AppPrefs, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(containerColor = RouterPage, topBar = { CompactTopBar("DDNS 与证书", onBack, "动态域名 · 到期提醒") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactSegment("DDNS记录", tab == 0, Modifier.weight(1f)) { tab = 0 }
                CompactSegment("证书监控", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }
            if (tab == 0) DdnsRecordsSection(prefs, Modifier.weight(1f))
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CertificateExpirySection(prefs)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DdnsRecordsSection(prefs: AppPrefs, modifier: Modifier = Modifier) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DdnsRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DdnsRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DdnsRecord?>(null) }
    suspend fun refresh(force: Boolean = false) {
        if (!force) loading = true
        runCatching { api.ddns(force) }.onSuccess { rows = it; error = "" }.onFailure { error = it.message.orEmpty() }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }
    if (adding || editing != null) {
        DdnsEditorPage(editing ?: DdnsRecord(), onBack = { adding = false; editing = null }) { record, password ->
            scope.launch {
                runCatching { if (editing == null) api.addDdns(record, password.orEmpty()) else api.updateDdns(record, password) }
                    .onSuccess { rows = it; adding = false; editing = null; error = "" }
                    .onFailure { error = it.message.orEmpty() }
            }
        }
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${rows.count { it.enabled }} 条启用 · ${rows.count { it.status.contains("error", true) || it.status.contains("fail", true) }} 条异常", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { refresh(true) } }, modifier = Modifier.size(33.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp), tint = RouterBlue)
            }
            Surface(onClick = { adding = true }, shape = CircleShape, color = RouterBlue, modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
        if (error.isNotBlank()) CompactMessage(error, RouterRed)
        if (!loading && rows.isEmpty()) CompactEmpty("暂无DDNS记录", "新增后由路由器原生服务更新", RouterGlyph.Ddns) { adding = true }
        rows.forEach { record ->
            DdnsCard(record, onEdit = { editing = record }, onToggle = {
                scope.launch { runCatching { api.updateDdns(record.copy(enabled = !record.enabled), null) }.onSuccess { rows = it; error = "" }.onFailure { error = it.message.orEmpty() } }
            }, onDelete = { deleteTarget = record })
        }
        Spacer(Modifier.height(12.dp))
    }
    deleteTarget?.let { target ->
        ConfirmDialog("删除DDNS记录？", "确认删除 ${target.domain}？", "删除", {
            scope.launch { runCatching { api.deleteDdns(target.serviceId) }.onSuccess { rows = it; deleteTarget = null; error = "" }.onFailure { error = it.message.orEmpty() } }
        }) { deleteTarget = null }
    }
}

@Composable
private fun DdnsCard(record: DdnsRecord, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val failed = record.status.contains("error", true) || record.status.contains("fail", true)
    val stateAccent = if (failed) RouterRed else if (!record.enabled) RouterMuted else RouterGreen
    var menu by remember(record.serviceId, record.domain) { mutableStateOf(false) }
    PremiumCard(RouterCyan) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
                RouterGlyphIcon(RouterGlyph.Ddns, stateAccent, Modifier.size(27.dp)); Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(record.domain, fontSize = 11.9.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${record.provider} · ${if (record.useIpv6) "IPv6" else "IPv4"} · ${record.interfaceName.uppercase()}", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
                    Text(record.ip.ifBlank { record.status.ifBlank { "等待更新" } }, fontSize = 9.8.sp, fontWeight = FontWeight.SemiBold, color = if (record.ip.isBlank()) stateAccent else RouterBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Switch(checked = record.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.scale(.76f), colors = SwitchDefaults.colors(checkedTrackColor = RouterCyan))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.MoreVert, null, Modifier.size(17.dp), tint = RouterMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑", fontSize = 11.5.sp) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", fontSize = 11.5.sp, color = RouterRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun DdnsEditorPage(initial: DdnsRecord, onBack: () -> Unit, onSave: (DdnsRecord, String?) -> Unit) {
    var record by remember(initial.serviceId, initial.domain) { mutableStateOf(initial) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.ArrowBack, null, Modifier.size(20.dp), tint = RouterInk) }
            Spacer(Modifier.width(4.dp))
            Column { Text(if (initial.serviceId.isBlank()) "新增DDNS" else "编辑DDNS", fontSize = 14.5.sp, fontWeight = FontWeight.Black, color = RouterInk); Text("密钥由你输入；留空保持原值", fontSize = 9.2.sp, color = RouterMuted) }
        }
        CompactChoice("服务商", record.provider, listOf("aliyun.com", "oray.com", "no-ip.com", "dnspod.cn", "dyn.com")) { record = record.copy(provider = it) }
        CompactField("域名 / 记录", record.domain, "例如 rj.lab86@shinya.icu") { record = record.copy(domain = it.take(128)) }
        CompactField("用户名 / AccessKey", record.username, "AccessKey ID") { record = record.copy(username = it.take(160)) }
        CompactPasswordField(if (initial.passwordConfigured) "密码 / Secret（留空保持）" else "密码 / Secret", password, "请输入密钥", showPassword, { showPassword = !showPassword }) { password = it.take(256) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CompactChoice("接口", record.interfaceName, listOf("wan", "wan1"), Modifier.weight(1f)) { record = record.copy(interfaceName = it) }; CompactChoice("记录类型", if (record.useIpv6) "IPv6" else "IPv4", listOf("IPv6", "IPv4"), Modifier.weight(1f)) { record = record.copy(useIpv6 = it == "IPv6") } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("启用记录", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = RouterMuted); Spacer(Modifier.weight(1f)); Switch(record.enabled, { record = record.copy(enabled = it) }, modifier = Modifier.scale(.85f), colors = SwitchDefaults.colors(checkedTrackColor = RouterCyan)) }
        if (error.isNotBlank()) Text(error, fontSize = 10.5.sp, color = RouterRed)
        Button(onClick = { error = when { record.domain.isBlank() -> "请填写域名"; record.username.isBlank() -> "请填写账号/AccessKey"; initial.serviceId.isBlank() && password.isBlank() -> "请填写密码/Secret"; else -> "" }; if (error.isBlank()) onSave(record, password.ifBlank { null }) }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = RouterCyan)) { Text("保存并同步", fontSize = 11.5.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.height(12.dp))
    }
}

'''
    ui = ui[:ddns_start] + ddns + ui[diag_start:]

    diag_start = ui.index('@Composable\nfun RouterDiagnosticScreen')
    hub_start = ui.index('@Composable\nfun RouterHubStatusScreen', diag_start)
    diag = r'''private fun diagnosticChinese(text: String, port: String = ""): String {
    val clean = text.replace("{port}", port.ifBlank { "对应端口" }, true).trim()
    return when (clean.lowercase()) {
        "check external network port network cable" -> "外网口网线"
        "check internal network port network cable" -> "内网口网线"
        "check external network port network cable is ok" -> "WAN 口网线连接正常"
        "check internal network port network cable is ok" -> "LAN 口网线连接正常"
        "negotiating_speed" -> "端口协商速率异常"
        "network port negotiation rate is abnormal" -> "网络端口协商速率异常"
        "may cause slow access to the internet" -> "可能导致上网速度变慢"
        else -> when { clean.contains("Please try to change a network cable", true) -> "请更换网线，或检查交换机、AP 等中间设备是否被限制为 10Mbps。"; clean.startsWith("Problem interface:", true) -> "问题接口：${port.ifBlank { clean.substringAfter(':').trim() }}"; else -> clean }
    }
}

private fun collapseDiagnosticItems(items: List<RouterDiagnosticItem>): List<RouterDiagnosticItem> {
    val cableRows = items.filter { val text = "${it.title} ${it.type}".lowercase(); text.contains("external network port network cable") || text.contains("internal network port network cable") }
    val cableOk = cableRows.size >= 2 && cableRows.all { it.status.equals("success", true) || it.status.equals("ok", true) }
    if (!cableOk) return items
    return listOf(RouterDiagnosticItem(type = "network_cable", title = "网线连接", status = "success", result = "WAN 口与 LAN 口网线连接正常")) + items.filterNot { it in cableRows }
}

@Composable
fun RouterDiagnosticScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf(RouterDiagnostic()) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    suspend fun refresh() { runCatching { api.diagnostic() }.onSuccess { result = it; error = "" }.onFailure { error = it.message.orEmpty() } }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(running) { while (running) { refresh(); if (result.progress.startsWith("100")) running = false else delay(1000) } }
    val visibleItems = remember(result.items) { collapseDiagnosticItems(result.items) }
    Scaffold(containerColor = RouterPage, topBar = { CompactTopBar("网络自检", onBack, "手动检测 · 物理接线 · 协商速率") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { PremiumCard(if (result.errorCount == 0) RouterGreen else RouterAmber) { Row(verticalAlignment = Alignment.CenterVertically) { RouterGlyphIcon(RouterGlyph.Diagnostic, if (result.errorCount == 0) RouterGreen else RouterAmber, Modifier.size(31.dp)); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(if (running) "检测进行中" else if (visibleItems.isEmpty()) "尚未检测" else if (result.errorCount == 0) "网络状态正常" else "发现 ${result.errorCount} 项异常", fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = RouterInk); Text(if (running) "进度 ${result.progress}" else "显示上次结果 · 仅点击按钮时重新检测", fontSize = 9.7.sp, color = RouterMuted) }; Button(onClick = { scope.launch { running = true; runCatching { api.startDiagnostic() }.onFailure { error = it.message.orEmpty(); running = false } } }, enabled = !running, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(35.dp)) { Text(if (running) "检测中" else if (visibleItems.isEmpty()) "开始检测" else "重新检测", fontSize = 10.3.sp, fontWeight = FontWeight.Black) } } } }
            if (error.isNotBlank()) item { CompactMessage(error, RouterRed) }
            items(visibleItems) { item ->
                val success = item.status.equals("success", true) || item.status.equals("ok", true)
                val accent = if (success) RouterGreen else RouterAmber
                PremiumCard(accent) { Row(verticalAlignment = Alignment.Top) { Icon(if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(diagnosticChinese(item.title.ifBlank { item.type }, item.port), fontSize = 11.7.sp, fontWeight = FontWeight.Black, color = RouterInk); diagnosticChinese(item.result, item.port).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.2.sp, fontWeight = FontWeight.SemiBold, color = RouterInk) }; if (item.port.isNotBlank()) Text("问题接口：${item.port}", fontSize = 9.7.sp, color = RouterRed); diagnosticChinese(item.tips, item.port).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 9.5.sp, color = RouterMuted) }; diagnosticChinese(item.advise, item.port).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 9.5.sp, color = RouterMuted, lineHeight = 13.sp) } } } }
            }
        }
    }
}

'''
    ui = ui[:diag_start] + diag + ui[hub_start:]
    UI_PATH.write_text(ui, encoding="utf-8")


if __name__ == "__main__":
    patch_main()
    patch_router_ui()
    print("router UI fixes applied")
