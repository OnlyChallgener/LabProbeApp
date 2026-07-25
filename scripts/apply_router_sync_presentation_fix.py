#!/usr/bin/env python3
"""Final APP-only repair for router-control caching, diagnostics and NAT logs.

This patch intentionally keeps the existing HubApi and authenticated WSS client.
HTTP remains the command/request transport for router settings; the APP now uses
the same presentation rule as the terminal list: render cached data immediately,
refresh in the background and only replace fields after a successful response.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ROUTER_SETTINGS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt"
ROUTER_CONTROL = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
ROUTER_API = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlApi.kt"


def matching_brace(text: str, opening: int) -> int:
    if opening < 0 or text[opening] != "{":
        raise RuntimeError("invalid Kotlin brace start")
    depth = 0
    quote = ""
    escaped = False
    for index in range(opening, len(text)):
        ch = text[index]
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = ""
            continue
        if ch in ('"', "'"):
            quote = ch
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError("unterminated Kotlin block")


def function_bounds(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing Kotlin function: {signature}")
    opening = text.find("{", start + len(signature))
    return start, matching_brace(text, opening) + 1


def replace_function(text: str, signature: str, replacement: str) -> str:
    start, end = function_bounds(text, signature)
    return text[:start] + replacement.rstrip() + text[end:]


def edit_function(text: str, signature: str, editor) -> str:
    start, end = function_bounds(text, signature)
    current = text[start:end]
    updated = editor(current)
    return text[:start] + updated + text[end:]


def patch_router_settings() -> None:
    text = ROUTER_SETTINGS.read_text(encoding="utf-8")

    replacement = r'''fun RouterSettingsScreen(prefs: AppPrefs, onBack: () -> Unit, onOpen: (String) -> Unit) {
    RouterSlowDataCache.ensureScope(prefs.hub, prefs.token)
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val cachedStatus = RouterSlowDataCache.hubStatus
    val cachedCapabilities = RouterSlowDataCache.capabilities
    var status by remember(prefs.hub, prefs.token) {
        mutableStateOf(
            cachedStatus ?: RouterHubStatus(
                state = "checking",
                connected = false,
                message = "正在检查 Hub 路由控制连接",
                errorCode = ""
            )
        )
    }
    var capabilities by remember(prefs.hub, prefs.token) {
        mutableStateOf(
            cachedCapabilities ?: RouterCapabilities(
                configured = true,
                dashboard = true,
                devices = true,
                firewall = true,
                nativePortMapping = true,
                upnp = true,
                ddns = true,
                diagnostic = true
            )
        )
    }
    var loading by remember(prefs.hub, prefs.token) {
        mutableStateOf(cachedStatus == null && cachedCapabilities == null)
    }

    suspend fun refreshSlowData(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val statusFresh = RouterSlowDataCache.isFresh(
            RouterSlowDataCache.hubStatusAt,
            RouterSlowDataCache.STATUS_TTL_MS,
            now
        )
        val capabilitiesFresh = RouterSlowDataCache.isFresh(
            RouterSlowDataCache.capabilitiesAt,
            RouterSlowDataCache.SETTINGS_TTL_MS,
            now
        )
        if (!force && statusFresh && capabilitiesFresh) return

        val hadUsableData = RouterSlowDataCache.hubStatus != null || RouterSlowDataCache.capabilities != null
        if (!hadUsableData) loading = true

        if (force || !statusFresh) {
            runCatching { api.hubStatus() }.onSuccess { latest ->
                status = latest
                RouterSlowDataCache.hubStatus = latest
                RouterSlowDataCache.hubStatusAt = System.currentTimeMillis()
            }
        }
        if (force || !capabilitiesFresh) {
            runCatching { api.capabilities() }.onSuccess { latest ->
                capabilities = latest
                RouterSlowDataCache.capabilities = latest
                RouterSlowDataCache.capabilitiesAt = System.currentTimeMillis()
            }
        }
        loading = false
    }

    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
        refreshSlowData()
    }

    DetailShell(
        title = "路由设置",
        subtitle = "配置数据使用缓存静默更新 · 设置指令立即发送",
        onBack = onBack,
        compactHeader = true
    ) {
        RouterSettingsConnectionCard(status = status, loading = loading) { onOpen("tool_router_login") }

        RouterSettingsSection("转发与安全") {
            RouterSettingsTile(
                title = "映射与 UPnP",
                subtitle = "IPv6 映射、原生端口映射与 UPnP",
                icon = Icons.Rounded.AccountTree,
                color = SettingsBlue,
                enabled = capabilities.nativePortMapping || capabilities.upnp
            ) { onOpen("tool_portmap") }
            RouterSettingsTile(
                title = "防火墙",
                subtitle = "入站、出站与转发规则",
                icon = Icons.Rounded.Security,
                color = SettingsGreen,
                enabled = capabilities.firewall
            ) { onOpen("tool_router_firewall") }
        }

        RouterSettingsSection("远程访问") {
            RouterSettingsTile(
                title = "DDNS 与证书",
                subtitle = "动态域名、远程入口与证书提醒",
                icon = Icons.Rounded.CloudSync,
                color = SettingsCyan,
                enabled = capabilities.ddns
            ) { onOpen("tool_router_ddns") }
        }

        RouterSettingsSection("诊断与升级") {
            RouterSettingsTile(
                title = "网络自检",
                subtitle = "仅在手动点击时检测物理接线与协商速率",
                icon = Icons.Rounded.MonitorHeart,
                color = SettingsAmber,
                enabled = capabilities.diagnostic
            ) { onOpen("tool_router_diag") }
            RouterSettingsTile(
                title = "路由 NAT 诊断",
                subtitle = "路由器原生 RFC3489 / RFC5780 检测",
                icon = Icons.Rounded.Radar,
                color = SettingsPurple,
                enabled = true
            ) { onOpen("tool_router_nat") }
            RouterSettingsTile(
                title = "Beta 在线升级",
                subtitle = "检查 ReyeeOS Beta 版本，不自动安装",
                icon = Icons.Rounded.SystemUpdateAlt,
                color = SettingsCyan,
                enabled = true
            ) { onOpen("tool_router_beta") }
        }
    }
}'''
    text = replace_function(text, "fun RouterSettingsScreen(", replacement)

    text = text.replace('connected -> "路由器实时数据正常"', 'connected -> "路由控制链路正常"')
    text = text.replace('hubOnline -> "Hub 已连接"', 'hubOnline -> "Hub 已连接，等待路由控制数据"')
    text = text.replace(
        'Text(if (connected) "Hub 已连接路由器" else "检查 Hub 路由连接",',
        'Text(if (connected) "路由控制链路正常" else "检查 Hub 路由控制连接",'
    )
    text = text.replace(
        '"Hub 已连接，正在等待路由器实时数据"',
        '"Hub 已连接，暂未取得路由控制数据"'
    )
    ROUTER_SETTINGS.write_text(text, encoding="utf-8")


def cache_action_updates(section: str, variable: str, cache_value: str, loaded_flag: str | None, at_field: str) -> str:
    expressions = [
        rf'\.onSuccess\s*\{{\s*{variable}\s*=\s*it\s*\}}',
        rf'\.onSuccess\s*\{{\s*{variable}\s*=\s*it\s*;',
    ]
    update = f"{variable} = latest; {cache_value} = latest; "
    if loaded_flag:
        update += f"{loaded_flag} = true; "
    update += f"{at_field} = System.currentTimeMillis()"
    section = re.sub(expressions[0], f".onSuccess {{ latest -> {update} }}", section)
    section = re.sub(expressions[1], f".onSuccess {{ latest -> {update};", section)
    return section


def patch_port_mapping(section: str) -> str:
    old = '''    var rules by remember { mutableStateOf<List<NativePortMapRule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }'''
    new = '''    RouterSlowDataCache.ensureScope(prefs.hub, prefs.token)
    val cachedRules = RouterSlowDataCache.portMappings
    var rules by remember(prefs.hub, prefs.token) { mutableStateOf(cachedRules) }
    var loading by remember(prefs.hub, prefs.token) { mutableStateOf(!RouterSlowDataCache.portMappingsLoaded) }'''
    if old in section:
        section = section.replace(old, new, 1)

    refresh = '''    suspend fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val fresh = RouterSlowDataCache.portMappingsLoaded &&
            RouterSlowDataCache.isFresh(RouterSlowDataCache.portMappingsAt, RouterSlowDataCache.MAPPING_TTL_MS, now)
        if (!force && fresh) return
        loading = true
        val hadCache = RouterSlowDataCache.portMappingsLoaded
        runCatching { api.nativePortMappings(force) }
            .onSuccess { latest ->
                rules = latest
                RouterSlowDataCache.portMappings = latest
                RouterSlowDataCache.portMappingsLoaded = true
                RouterSlowDataCache.portMappingsAt = System.currentTimeMillis()
                error = ""
            }
            .onFailure { failure ->
                error = if (hadCache) "刷新失败，已保留上次端口映射" else failure.message.orEmpty()
            }
        loading = false
    }'''
    section = replace_function(section, "    suspend fun refresh(", refresh)
    return cache_action_updates(
        section,
        "rules",
        "RouterSlowDataCache.portMappings",
        "RouterSlowDataCache.portMappingsLoaded",
        "RouterSlowDataCache.portMappingsAt",
    )


def patch_upnp(section: str) -> str:
    old = '''    var state by remember { mutableStateOf(UpnpState()) }
    var loading by remember { mutableStateOf(true) }'''
    new = '''    RouterSlowDataCache.ensureScope(prefs.hub, prefs.token)
    val cachedState = RouterSlowDataCache.upnpState
    var state by remember(prefs.hub, prefs.token) { mutableStateOf(cachedState ?: UpnpState()) }
    var loading by remember(prefs.hub, prefs.token) { mutableStateOf(cachedState == null) }'''
    if old in section:
        section = section.replace(old, new, 1)

    refresh = '''    suspend fun refresh(force: Boolean = false) {
        val fresh = RouterSlowDataCache.isFresh(
            RouterSlowDataCache.upnpAt,
            RouterSlowDataCache.MAPPING_TTL_MS
        )
        if (!force && fresh) return
        loading = true
        val hadCache = RouterSlowDataCache.upnpState != null
        runCatching { api.upnp(force) }
            .onSuccess { latest ->
                state = latest
                RouterSlowDataCache.upnpState = latest
                RouterSlowDataCache.upnpAt = System.currentTimeMillis()
                error = ""
            }
            .onFailure { failure ->
                error = if (hadCache) "刷新失败，已保留上次 UPnP 数据" else failure.message.orEmpty()
            }
        loading = false
    }'''
    section = replace_function(section, "    suspend fun refresh(", refresh)
    return cache_action_updates(
        section,
        "state",
        "RouterSlowDataCache.upnpState",
        None,
        "RouterSlowDataCache.upnpAt",
    )


def patch_firewall(section: str) -> str:
    old = '''    var state by remember { mutableStateOf(FirewallState()) }
    var direction by remember { mutableStateOf("forward") }
    var loading by remember { mutableStateOf(true) }'''
    new = '''    RouterSlowDataCache.ensureScope(prefs.hub, prefs.token)
    val cachedState = RouterSlowDataCache.firewallState
    var state by remember(prefs.hub, prefs.token) { mutableStateOf(cachedState ?: FirewallState()) }
    var direction by remember { mutableStateOf("forward") }
    var loading by remember(prefs.hub, prefs.token) { mutableStateOf(cachedState == null) }'''
    if old in section:
        section = section.replace(old, new, 1)

    refresh = '''    suspend fun refresh(force: Boolean = false) {
        val fresh = RouterSlowDataCache.isFresh(
            RouterSlowDataCache.firewallAt,
            RouterSlowDataCache.SETTINGS_TTL_MS
        )
        if (!force && fresh) return
        loading = true
        val hadCache = RouterSlowDataCache.firewallState != null
        runCatching { api.firewall(force) }
            .onSuccess { latest ->
                state = latest
                RouterSlowDataCache.firewallState = latest
                RouterSlowDataCache.firewallAt = System.currentTimeMillis()
                error = ""
            }
            .onFailure { failure ->
                error = if (hadCache) "刷新失败，已保留上次防火墙规则" else failure.message.orEmpty()
            }
        loading = false
    }'''
    section = replace_function(section, "    suspend fun refresh(", refresh)
    return cache_action_updates(
        section,
        "state",
        "RouterSlowDataCache.firewallState",
        None,
        "RouterSlowDataCache.firewallAt",
    )


def patch_ddns(section: str) -> str:
    state_start = section.find("    var rows by remember")
    launch_start = section.find("    LaunchedEffect", state_start)
    if state_start < 0 or launch_start < 0:
        raise RuntimeError("missing DDNS state boundary")
    new_state = '''    RouterSlowDataCache.ensureScope(prefs.hub, prefs.token)
    var rows by remember(prefs.hub, prefs.token) { mutableStateOf(RouterSlowDataCache.ddnsRows) }
    var loading by remember(prefs.hub, prefs.token) { mutableStateOf(!RouterSlowDataCache.ddnsLoaded) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DdnsRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DdnsRecord?>(null) }
    suspend fun refresh(force: Boolean = false) {
        val fresh = RouterSlowDataCache.ddnsLoaded &&
            RouterSlowDataCache.isFresh(RouterSlowDataCache.ddnsAt, RouterSlowDataCache.SETTINGS_TTL_MS)
        if (!force && fresh) return
        loading = true
        val hadCache = RouterSlowDataCache.ddnsLoaded
        runCatching { api.ddns(force) }
            .onSuccess { latest ->
                rows = latest
                RouterSlowDataCache.ddnsRows = latest
                RouterSlowDataCache.ddnsLoaded = true
                RouterSlowDataCache.ddnsAt = System.currentTimeMillis()
                error = ""
            }
            .onFailure { failure ->
                error = if (hadCache) "刷新失败，已保留上次 DDNS 数据" else failure.message.orEmpty()
            }
        loading = false
    }
'''
    section = section[:state_start] + new_state + section[launch_start:]
    ddns_update = (
        ".onSuccess { latest -> "
        "rows = latest; "
        "RouterSlowDataCache.ddnsRows = latest; "
        "RouterSlowDataCache.ddnsLoaded = true; "
        "RouterSlowDataCache.ddnsAt = System.currentTimeMillis()"
    )
    return re.sub(
        r'\.onSuccess\s*\{\s*rows\s*=\s*it\s*'
        r'(?:;\s*RouterControlMemoryCache\.ddnsRows\s*=\s*it)?',
        ddns_update,
        section,
    )


def patch_router_control() -> None:
    text = ROUTER_CONTROL.read_text(encoding="utf-8")
    text = edit_function(text, "private fun NativePortMappingPage(", patch_port_mapping)
    text = edit_function(text, "private fun UpnpPage(", patch_upnp)
    text = edit_function(text, "fun RouterFirewallScreen(", patch_firewall)
    text = edit_function(text, "private fun DdnsRecordsSection(", patch_ddns)
    ROUTER_CONTROL.write_text(text, encoding="utf-8")


def patch_home_ddns(text: str) -> str:
    replacement = r'''fun HomeDdnsMiniCard(prefs: AppPrefs, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RouterSlowDataCache.ensureScope(prefs.hub, prefs.token)
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    var rows by remember(prefs.hub, prefs.token) { mutableStateOf(RouterSlowDataCache.ddnsRows) }
    var loaded by remember(prefs.hub, prefs.token) { mutableStateOf(RouterSlowDataCache.ddnsLoaded) }

    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
        val fresh = RouterSlowDataCache.ddnsLoaded &&
            RouterSlowDataCache.isFresh(RouterSlowDataCache.ddnsAt, RouterSlowDataCache.SETTINGS_TTL_MS)
        if (!fresh) {
            runCatching { api.ddns() }.onSuccess { latest ->
                rows = latest
                loaded = true
                RouterSlowDataCache.ddnsRows = latest
                RouterSlowDataCache.ddnsLoaded = true
                RouterSlowDataCache.ddnsAt = System.currentTimeMillis()
            }
        }
    }

    val enabled = rows.count { it.enabled }
    val failed = rows.count { it.status.contains("error", true) || it.status.contains("fail", true) }
    HealthMiniCard(
        title = "DDNS",
        value = if (loaded) rows.size.toString() else "--",
        unit = "条",
        icon = Icons.Rounded.CloudSync,
        accent = NativeCyan,
        subtitle = when {
            !loaded -> "等待首次同步"
            rows.isEmpty() -> "暂无记录"
            failed > 0 -> "启用 $enabled · 异常 $failed"
            else -> "启用 $enabled · 状态正常"
        },
        modifier = modifier.clickable(onClick = onClick)
    )
}'''
    return replace_function(text, "fun HomeDdnsMiniCard(", replacement)


def patch_nat_screen(text: str) -> str:
    helper = r'''
private fun mergeNatLog(previous: String, incoming: String): String {
    val oldText = previous.trim()
    val newText = incoming.trim()
    if (newText.isBlank()) return oldText
    if (oldText.isBlank()) return newText
    if (newText == oldText || oldText.startsWith(newText)) return oldText
    if (newText.startsWith(oldText)) return newText

    val lines = oldText.lines().map(String::trimEnd).filter(String::isNotBlank).toMutableList()
    val known = lines.toMutableSet()
    newText.lines().map(String::trimEnd).filter(String::isNotBlank).forEach { line ->
        if (known.add(line)) lines += line
    }
    return lines.takeLast(300).joinToString("\n")
}
'''
    if "private fun mergeNatLog" not in text:
        anchor = "@Composable\nfun RouterNatDiagnosticScreen"
        index = text.find(anchor)
        if index < 0:
            raise RuntimeError("missing NAT screen anchor")
        text = text[:index] + helper + "\n" + text[index:]

    replacement = r'''fun RouterNatDiagnosticScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterNativeApi(prefs) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = remember {
        listOf(
            "stun.voip.aebc.com",
            "stun.miwifi.com",
            "stun.hot-chilli.net",
            "stun.internetcalls.com",
            "stun.fitaauto.ru",
            "stun.voipbuster.com",
            "stun.voipstunt.com"
        )
    }
    var server by rememberSaveable { mutableStateOf(servers.first()) }
    var portText by rememberSaveable { mutableStateOf("3478") }
    var mode by rememberSaveable { mutableStateOf("classic") }
    var interfaceName by rememberSaveable { mutableStateOf("wan") }
    val cachedResult = RouterNativeMemoryCache.natResult
    var result by remember { mutableStateOf(cachedResult ?: RouterNatResult()) }
    var sessionLog by remember { mutableStateOf(cachedResult?.log.orEmpty()) }
    var running by remember { mutableStateOf(cachedResult?.running == true) }
    var activeRunStartedAt by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(cachedResult == null) }
    var error by remember { mutableStateOf("") }
    var serverMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var interfaceMenu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadNatHistory(context)) }

    fun publish(next: RouterNatResult) {
        val mergedLog = mergeNatLog(sessionLog, next.log)
        sessionLog = mergedLog
        result = next.copy(log = mergedLog)
        RouterNativeMemoryCache.natResult = result
    }

    suspend fun refresh() {
        runCatching { api.natStatus() }
            .onSuccess { latest ->
                val previousTask = running && latest.completed && activeRunStartedAt > 0L &&
                    (latest.timestamp <= 0L || latest.timestamp < activeRunStartedAt)
                if (!previousTask) {
                    val normalized = if (latest.completed) {
                        latest.copy(
                            timestamp = latest.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis() / 1000L,
                            mode = latest.mode.takeIf { it == "classic" || it == "5780" } ?: mode,
                            stunPort = latest.stunPort.takeIf { it > 0 } ?: portText.toIntOrNull() ?: 0
                        )
                    } else {
                        latest.copy(
                            mode = latest.mode.takeIf { it == "classic" || it == "5780" } ?: mode,
                            stunPort = latest.stunPort.takeIf { it > 0 } ?: portText.toIntOrNull() ?: 0
                        )
                    }
                    publish(normalized)
                    error = ""
                    when {
                        normalized.completed -> {
                            running = false
                            activeRunStartedAt = 0L
                            history = saveNatHistory(context, normalized)
                        }
                        normalized.status.contains("fail", true) ||
                            normalized.status.contains("error", true) -> {
                            running = false
                            activeRunStartedAt = 0L
                        }
                    }
                }
            }
            .onFailure { failure -> error = natErrorZh(failure.message) }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(running) {
        while (running && isActive) {
            delay(700L)
            refresh()
        }
    }

    DetailShell("路由 NAT 诊断", "路由器原生 RFC3489 / RFC5780", onBack, compactHeader = true) {
        NativeCard {
            NativeTitle(Icons.Rounded.Radar, "检测参数", NativeBlue)
            val serverShape = RoundedCornerShape(18.dp)
            Box {
                OutlinedButton(
                    onClick = { serverMenu = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp).nativeBlueShadow(serverShape, 5.dp),
                    shape = serverShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, NativeBlue.copy(alpha = .32f))
                ) {
                    Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp), tint = NativeBlue)
                    Spacer(Modifier.width(7.dp))
                    Text(server, Modifier.weight(1f), color = NativeInk, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = NativeBlue)
                }
                DropdownMenu(
                    expanded = serverMenu,
                    onDismissRequest = { serverMenu = false },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 7.dp
                ) {
                    servers.forEach { host ->
                        DropdownMenuItem(
                            text = { Text(host, color = NativeInk, fontWeight = FontWeight.Bold) },
                            onClick = { server = host; serverMenu = false },
                            modifier = Modifier.background(Color.White)
                        )
                    }
                }
            }
            NativeCompactPortField(portText) { portText = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeSelector(
                    label = "STUN 类型",
                    value = if (mode == "5780") "RFC 5780" else "RFC 3489",
                    options = listOf("classic" to "RFC 3489", "5780" to "RFC 5780"),
                    expanded = modeMenu,
                    onExpandedChange = { modeMenu = it },
                    onSelect = { mode = it },
                    modifier = Modifier.weight(1f)
                )
                NativeSelector(
                    label = "WAN 类型",
                    value = interfaceName.uppercase(),
                    options = listOf("wan" to "WAN", "wan1" to "WAN1"),
                    expanded = interfaceMenu,
                    onExpandedChange = { interfaceMenu = it },
                    onSelect = { interfaceName = it },
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                    if (port == null || port !in 1..65535) {
                        error = "请输入正确的 STUN 端口"
                    } else {
                        scope.launch {
                            error = ""
                            running = true
                            activeRunStartedAt = System.currentTimeMillis() / 1000L
                            sessionLog = listOf(
                                "[NAT 检测] 已发送检测任务",
                                "[配置] STUN 服务器：$server:$port",
                                "[配置] WAN 类型：${interfaceName.uppercase()}",
                                "[配置] 检测协议：${if (mode == "5780") "RFC 5780" else "RFC 3489"}"
                            ).joinToString("\n")
                            publish(
                                RouterNatResult(
                                    status = "running",
                                    mode = mode,
                                    stunPort = port,
                                    log = sessionLog
                                )
                            )
                            runCatching { api.startNat(server, port, interfaceName, mode) }
                                .onSuccess { refresh() }
                                .onFailure { failure ->
                                    error = natErrorZh(failure.message)
                                    running = false
                                    activeRunStartedAt = 0L
                                }
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(44.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NativeBlue,
                    contentColor = Color.White,
                    disabledContainerColor = NativeBlue.copy(alpha = .62f),
                    disabledContentColor = Color.White
                )
            ) {
                if (running) CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                    trackColor = Color.Transparent
                ) else Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (running) "检测中" else "开始检测", fontWeight = FontWeight.Black)
            }
        }

        if (error.isNotBlank()) NativeMessage(error, NativeRed)

        NativeCard {
            NativeTitle(Icons.Rounded.Analytics, "分析结果", NativeGreen)
            NativeValueRow("检测状态", when {
                loading -> "读取中"
                running -> "检测中"
                result.completed -> "检测完成"
                else -> natStatusZh(result.status)
            })
            NativeValueRow("NAT类型", natTypeZh(result.natType))
            NativeValueRow("外网地址", result.externalAddress.ifBlank {
                if (result.externalIp.isBlank()) "--" else result.externalIp +
                    if (result.externalPort > 0) ":${result.externalPort}" else ""
            })
            NativeValueRow("检测模式", if (result.mode == "5780") "RFC 5780" else "RFC 3489")
        }

        if (result.log.isNotBlank()) {
            NativeCard {
                NativeTitle(Icons.Rounded.Terminal, "检测日志", NativeCyan)
                SelectionContainer {
                    Text(
                        natLogZh(result.log),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = NativeInk
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            NativeCard {
                NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)
                history.forEachIndexed { index, item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(natTypeZh(item.natType), fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = NativeInk)
                        Text(
                            listOf(
                                item.externalAddress,
                                if (item.mode == "5780") "RFC5780" else "RFC3489",
                                item.stunPort.takeIf { it > 0 }?.let { "$it 端口" }.orEmpty()
                            ).filter(String::isNotBlank).joinToString(" · "),
                            fontSize = 9.8.sp,
                            color = NativeMuted
                        )
                    }
                    if (index != history.lastIndex) HorizontalDivider(color = NativeBorder)
                }
            }
        }
    }
}'''
    return replace_function(text, "fun RouterNatDiagnosticScreen(", replacement)


def patch_router_native() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")
    text = patch_home_ddns(text)
    text = patch_nat_screen(text)
    ROUTER_NATIVE.write_text(text, encoding="utf-8")


def patch_router_api() -> None:
    text = ROUTER_API.read_text(encoding="utf-8")

    helper = r'''
private fun routerDiagnosticTitleZh(type: String, raw: String): String {
    val text = cleanApiText(raw).trim()
    if (text.any { it.code > 127 }) return text
    val lower = (type + " " + text).lowercase()
    return when {
        "wan" in lower || "external network port" in lower -> "外网口连接"
        "lan" in lower || "internal network" in lower -> "局域网连接"
        "dns" in lower -> "DNS 解析"
        "gateway" in lower -> "网关连接"
        "internet" in lower || "network access" in lower -> "互联网连接"
        "speed" in lower || "negotiation" in lower -> "端口协商速率"
        "cable" in lower || "link" in lower -> "网线连接"
        else -> "网络状态检查"
    }
}

private fun routerDiagnosticTextZh(raw: String): String {
    var text = cleanApiText(raw).replace("<br>", "\n", true).trim()
    if (text.isBlank() || text.any { it.code > 127 } || !Regex("[A-Za-z]{3,}").containsMatchIn(text)) return text
    val replacements = listOf(
        "check external network port network cable is OK" to "请检查外网口网线连接是否正常",
        "external network port network cable is OK" to "外网口网线连接正常",
        "check wan port network cable" to "请检查 WAN 口网线连接",
        "network cable is unplugged" to "网线未连接",
        "network cable is connected" to "网线已连接",
        "link is normal" to "链路正常",
        "network is normal" to "网络状态正常",
        "internet access is normal" to "互联网连接正常",
        "dns is normal" to "DNS 解析正常",
        "gateway is reachable" to "网关可达",
        "negotiation speed" to "协商速率",
        "please check" to "请检查",
        "success" to "正常",
        "failed" to "失败",
        "failure" to "失败",
        "abnormal" to "异常",
        "normal" to "正常"
    )
    replacements.forEach { (old, new) -> text = text.replace(old, new, ignoreCase = true) }
    if (!Regex("[A-Za-z]{3,}").containsMatchIn(text)) return text
    val lower = text.lowercase()
    return when {
        "cable" in lower || "port" in lower && "link" in lower -> "请检查对应接口的网线连接"
        "speed" in lower || "negotiation" in lower -> "请检查端口协商速率"
        "dns" in lower -> "请检查 DNS 配置和解析状态"
        "gateway" in lower -> "请检查网关配置和连通性"
        "internet" in lower || "network" in lower -> "请检查互联网连接状态"
        "ok" in lower || "success" in lower || "normal" in lower -> "检测正常"
        "fail" in lower || "error" in lower || "abnormal" in lower -> "检测异常"
        else -> "请检查该项网络状态"
    }
}
'''
    if "private fun routerDiagnosticTitleZh" not in text:
        anchor = "data class RouterDiagnosticItem("
        index = text.find(anchor)
        if index < 0:
            raise RuntimeError("missing diagnostic model anchor")
        text = text[:index] + helper + "\n" + text[index:]

    text = text.replace(
        'title = cleanApiText(group.optString("item")),',
        'title = routerDiagnosticTitleZh(group.optString("type"), group.optString("item")),'
    )
    text = text.replace(
        'title = cleanApiText(child.optString("item")),',
        'title = routerDiagnosticTitleZh(group.optString("type"), child.optString("item")),'
    )
    text = text.replace(
        'result = cleanApiText(group.optString("result")),',
        'result = routerDiagnosticTextZh(group.optString("result")),'
    )
    text = text.replace(
        'tips = cleanApiText(group.optString("tips")),',
        'tips = routerDiagnosticTextZh(group.optString("tips")),'
    )
    text = text.replace(
        'advise = cleanApiText(group.optString("advise"))',
        'advise = routerDiagnosticTextZh(group.optString("advise"))'
    )
    text = text.replace(
        'result = cleanApiText(child.optString("result")),',
        'result = routerDiagnosticTextZh(child.optString("result")),'
    )
    text = text.replace(
        'tips = cleanApiText(child.optString("tips")),',
        'tips = routerDiagnosticTextZh(child.optString("tips")),'
    )
    text = text.replace(
        'advise = cleanApiText(child.optString("advise")).replace("<br>", "\\n", true),',
        'advise = routerDiagnosticTextZh(child.optString("advise")),'
    )

    replacements = {
        '"Hub 已连接，正在等待路由器实时数据"': '"Hub 已连接，暂未取得路由控制数据"',
        '"Hub 与路由器数据连接正常"': '"路由控制链路正常"',
        '"路由器已连接，实时数据正常"': '"路由控制链路正常"',
        '"路由器已登录，正在同步实时数据"': '"路由器会话已建立，正在同步控制数据"',
        '"Hub 在线，正在等待路由器数据"': '"Hub 已连接，暂未取得路由控制数据"',
    }
    for old, new in replacements.items():
        text = text.replace(old, new)

    ROUTER_API.write_text(text, encoding="utf-8")


def verify() -> None:
    settings = ROUTER_SETTINGS.read_text(encoding="utf-8")
    control = ROUTER_CONTROL.read_text(encoding="utf-8")
    native = ROUTER_NATIVE.read_text(encoding="utf-8")
    api = ROUTER_API.read_text(encoding="utf-8")

    checks = {
        "RouterSettingsUi.kt": (
            "RouterSlowDataCache.hubStatus",
            "RouterSlowDataCache.capabilities",
            "配置数据使用缓存静默更新",
            "路由控制链路正常",
        ),
        "RouterControlUi.kt": (
            "RouterSlowDataCache.portMappings",
            "RouterSlowDataCache.upnpState",
            "RouterSlowDataCache.firewallState",
            "RouterSlowDataCache.ddnsRows",
            "已保留上次",
        ),
        "RouterNativeToolsUi.kt": (
            "private fun mergeNatLog",
            "delay(700L)",
            "[NAT 检测] 已发送检测任务",
            "RouterSlowDataCache.ddnsRows",
        ),
        "RouterControlApi.kt": (
            "private fun routerDiagnosticTitleZh",
            "private fun routerDiagnosticTextZh",
            "路由控制链路正常",
            "暂未取得路由控制数据",
        ),
    }
    values = {
        "RouterSettingsUi.kt": settings,
        "RouterControlUi.kt": control,
        "RouterNativeToolsUi.kt": native,
        "RouterControlApi.kt": api,
    }
    missing = [
        f"{name}: {needle}"
        for name, needles in checks.items()
        for needle in needles
        if needle not in values[name]
    ]
    if missing:
        raise RuntimeError(f"router sync repair verification failed: {missing}")

    for forbidden in (
        "路由器实时数据正常",
        "路由器已连接，实时数据正常",
    ):
        if forbidden in settings or forbidden in api:
            raise RuntimeError(f"ambiguous router connection wording remains: {forbidden}")


def apply() -> None:
    patch_router_settings()
    patch_router_control()
    patch_router_native()
    patch_router_api()
    verify()
    print("APP router cache, NAT log and diagnostic Chinese repair applied")


if __name__ == "__main__":
    apply()
