#!/usr/bin/env python3
"""Build165: finish NAT task timing, Agent checks and full-screen mapping editors."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
MAIN = SRC / "MainActivity.kt"
NATIVE = SRC / "RouterNativeToolsUi.kt"
ROUTER_CONTROL = SRC / "RouterControlUi.kt"
PORT_MAPPING = SRC / "PortMapping.kt"
GRADLE = ROOT / "app/build.gradle.kts"
VERIFIER = ROOT / "scripts/verify_build154_sources.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build165 anchor: {label}")
    return text.replace(old, new, 1)


def replace_section(text: str, start: str, end: str, new: str, label: str) -> str:
    begin = text.find(start)
    finish = text.find(end, begin + len(start))
    if begin < 0 or finish < 0:
        raise RuntimeError(f"missing build165 section: {label}")
    return text[:begin] + new + text[finish:]


def patch_native(text: str) -> str:
    selector = '''@Composable
private fun NativeSelector(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    Box(modifier) {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.fillMaxWidth().height(58.dp).nativeBlueShadow(shape, 5.dp),
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
            border = BorderStroke(1.dp, NativeBlue.copy(alpha = .32f)),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(label, fontSize = 9.sp, lineHeight = 11.sp, color = NativeMuted, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(value, fontSize = 13.sp, lineHeight = 16.sp, color = NativeInk, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Icon(Icons.Rounded.ArrowDropDown, null, tint = NativeBlue)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(min = 156.dp).padding(vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp
        ) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title, color = NativeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                    onClick = { onSelect(key); onExpandedChange(false) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)
                )
            }
        }
    }
}

'''
    text = replace_section(
        text,
        "@Composable\nprivate fun NativeSelector(",
        "@Composable\nprivate fun NativeCompactPortField(",
        selector,
        "NativeSelector",
    )

    port_field = '''@Composable
private fun NativeCompactPortField(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().height(58.dp).nativeBlueShadow(shape, 4.dp),
        shape = shape,
        color = Color.White,
        border = BorderStroke(1.dp, NativeBlue.copy(alpha = .30f))
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("STUN 端口", fontSize = 9.sp, lineHeight = 11.sp, color = NativeMuted, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(
                    color = NativeInk,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                cursorBrush = SolidColor(NativeBlue)
            )
        }
    }
}

'''
    text = replace_section(
        text,
        "@Composable\nprivate fun NativeCompactPortField(",
        "private data class RouterNatResult(",
        port_field,
        "NativeCompactPortField",
    )

    text = replace_once(
        text,
        '''            val serverShape = RoundedCornerShape(14.dp)
            Box {
                OutlinedButton(
                    onClick = { serverMenu = true },
                    modifier = Modifier.fillMaxWidth().height(54.dp).nativeBlueShadow(serverShape, 5.dp),''',
        '''            val serverShape = RoundedCornerShape(22.dp)
            Box {
                OutlinedButton(
                    onClick = { serverMenu = true },
                    modifier = Modifier.fillMaxWidth().height(58.dp).nativeBlueShadow(serverShape, 5.dp),''',
        "server field shape",
    )
    text = replace_once(
        text,
        '''                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text("STUN 服务器", fontSize = 8.5.sp, lineHeight = 10.sp, color = NativeMuted, fontWeight = FontWeight.SemiBold)
                        Text(server, fontSize = 12.5.sp, lineHeight = 15.sp, color = NativeInk, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }''',
        '''                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text("STUN 服务器", fontSize = 9.sp, lineHeight = 11.sp, color = NativeMuted, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text(server, fontSize = 13.sp, lineHeight = 16.sp, color = NativeInk, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }''',
        "server field typography",
    )
    text = replace_once(
        text,
        '''                DropdownMenu(
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
                }''',
        '''                DropdownMenu(
                    expanded = serverMenu,
                    onDismissRequest = { serverMenu = false },
                    modifier = Modifier.widthIn(min = 300.dp).padding(vertical = 5.dp),
                    shape = RoundedCornerShape(24.dp),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 11.dp
                ) {
                    servers.forEach { host ->
                        DropdownMenuItem(
                            text = { Text(host, color = NativeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                            onClick = { server = host; serverMenu = false },
                            modifier = Modifier.heightIn(min = 50.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                }''',
        "server dropdown",
    )
    text = replace_once(
        text,
        '''            if (running || result.elapsedSeconds > 0L) NativeValueRow("已耗时", "${result.elapsedSeconds} 秒")
            if (result.lastRouterResponseAt > 0L) {
                val age = (System.currentTimeMillis() / 1000L - result.lastRouterResponseAt).coerceAtLeast(0L)
                NativeValueRow("路由器响应", if (age < 3L) "刚刚" else "${age} 秒前")
            }''',
        '''            if (running) {
                NativeValueRow("已耗时", "${result.elapsedSeconds} 秒")
            } else if (result.elapsedSeconds > 0L) {
                NativeValueRow("检测耗时", "${result.elapsedSeconds} 秒")
            }
            if (result.lastRouterResponseAt > 0L) {
                if (running) {
                    val age = (System.currentTimeMillis() / 1000L - result.lastRouterResponseAt).coerceAtLeast(0L)
                    NativeValueRow("路由器响应", if (age < 3L) "刚刚" else "${age} 秒前")
                } else {
                    NativeValueRow("最终响应", "已收到")
                }
            }''',
        "terminal task timing display",
    )
    return text


def patch_router_control(text: str) -> str:
    card = '''@Composable
private fun NativePortRuleCard(rule: NativePortMapRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(rule.ruleName) { mutableStateOf(false) }
    PremiumCard(RouterBlue, Modifier.clickable(onClick = onEdit)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RouterGlyphIcon(RouterGlyph.Port, RouterBlue, Modifier.size(27.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.ruleName, Modifier.weight(1f), fontSize = 12.6.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TinyBadge(rule.proto.uppercase(), RouterBlue)
                }
                Text("WAN ${rule.srcPort}  →  ${rule.destIp}:${rule.destPort}", fontSize = 10.7.sp, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, null, Modifier.size(17.dp), tint = RouterMuted) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(22.dp),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 9.dp
                ) {
                    DropdownMenuItem(text = { Text("编辑", fontSize = 11.8.sp, fontWeight = FontWeight.SemiBold) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(15.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", fontSize = 11.8.sp, fontWeight = FontWeight.SemiBold, color = RouterRed) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(15.dp), tint = RouterRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

'''
    text = replace_section(
        text,
        "@Composable\nprivate fun NativePortRuleCard(",
        "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun NativePortEditorSheet(",
        card,
        "NativePortRuleCard",
    )

    start = text.find("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun NativePortEditorSheet(")
    end = text.find("@Composable\nprivate fun UpnpPage(", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing NativePortEditorSheet")
    section = text[start:end]
    old_prefix = '''    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = Color.White,
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 4.dp).width(32.dp).height(3.dp).background(RouterBorder, RoundedCornerShape(99.dp))) }
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.86f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {'''
    new_prefix = '''    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = RouterPage) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {'''
    section = replace_once(section, old_prefix, new_prefix, "native port editor full screen")
    tail = "\n    }\n}\n\n"
    if not section.endswith(tail):
        raise RuntimeError("unexpected NativePortEditorSheet tail")
    section = section[:-len(tail)] + "\n            }\n        }\n    }\n}\n\n"
    text = text[:start] + section + text[end:]
    return text


def patch_port_mapping(text: str) -> str:
    start = text.find("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun PortMapEditorSheet(")
    end = text.find("private fun validateDraft(", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing PortMapEditorSheet")
    section = text[start:end]
    section = replace_once(
        section,
        "    LabBottomSheet(onDismiss = onDismiss, scrollable = true) {\n",
        '''    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = PortSheetBg) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
''',
        "Rust port editor full screen",
    )
    old_tail = '''        Spacer(Modifier.height(8.dp))
    }

    if (showDevicePicker) {'''
    new_tail = '''        Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDevicePicker) {'''
    section = replace_once(section, old_tail, new_tail, "Rust port editor close")
    text = text[:start] + section + text[end:]
    return text


def patch_main(text: str) -> str:
    helper = '''private fun agentUpdateUiError(raw: String?): String {
    val text = raw.orEmpty().trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> "更新检查失败，已保留上次结果"
        "502" in lower || "<!doctype" in lower || "<html" in lower -> "更新源暂不可用，Hub 已保留上次版本信息"
        "timeout" in lower || "timed out" in lower -> "更新检查超时，Hub 将继续在后台重试"
        "404" in lower -> "Hub 版本过旧，请先更新 Hub 后再检查 Agent"
        else -> "更新检查失败：${text.take(120)}"
    }
}

'''
    anchor = "@Composable\nfun HealthScoreDetailScreen("
    if helper not in text:
        if anchor not in text:
            raise RuntimeError("missing HealthScoreDetailScreen anchor")
        text = text.replace(anchor, helper + anchor, 1)

    text = replace_once(
        text,
        '''    suspend fun requestAgentUpdate(): JSONObject = withContext(Dispatchers.IO) {
        requestJson(
            "/api/agent/update",
            "POST",
            JSONObject().put("manifestUrl", UpdateRepository.AGENT_MANIFEST).put("installerUrl", UpdateRepository.AGENT_INSTALLER)
        )
    }''',
        '''    suspend fun requestAgentUpdateCheck(): JSONObject = withContext(Dispatchers.IO) {
        requestJson("/api/agent/update/check", "POST", JSONObject())
    }
    suspend fun requestAgentUpdate(): JSONObject = withContext(Dispatchers.IO) {
        requestJson(
            "/api/agent/update",
            "POST",
            JSONObject().put("manifestUrl", UpdateRepository.AGENT_MANIFEST).put("installerUrl", UpdateRepository.AGENT_INSTALLER)
        )
    }''',
        "agent update check API",
    )

    old_check = '''                        runCatching { HubApi(prefs).getAgentUpdateStatus() }
                            .onSuccess { info ->
                                val checkedMessage = if (info.updateAvailable) "发现 Rust Agent 新版本" else info.message.ifBlank { "当前已是最新版本" }
                                agentInfo = info
                                agentMessage = checkedMessage
                                prefs.agentUpdateInfoJson = info.toStoredJson()
                                prefs.agentUpdateMessage = checkedMessage
                            }
                            .onFailure {
                                val failedMessage = "检查失败：${it.message} · 已保留上次结果"
                                agentMessage = failedMessage
                                prefs.agentUpdateMessage = failedMessage
                            }'''
    new_check = '''                        runCatching {
                            val api = HubApi(prefs)
                            api.requestAgentUpdateCheck()
                            var info = api.getAgentUpdateStatus()
                            repeat(8) {
                                val settled = info.latestVersion != "未知" ||
                                    (info.message.isNotBlank() && !info.message.contains("正在后台"))
                                if (settled) return@runCatching info
                                delay(750L)
                                info = api.getAgentUpdateStatus()
                            }
                            info
                        }.onSuccess { info ->
                            val checkedMessage = when {
                                info.updateAvailable -> "发现 Rust Agent 新版本"
                                info.latestVersion == "未知" -> info.message.ifBlank { "Hub 正在后台检查更新" }
                                else -> info.message.ifBlank { "当前已是最新版本" }
                            }
                            agentInfo = info
                            agentMessage = checkedMessage
                            prefs.agentUpdateInfoJson = info.toStoredJson()
                            prefs.agentUpdateMessage = checkedMessage
                        }.onFailure {
                            val failedMessage = agentUpdateUiError(it.message)
                            agentMessage = failedMessage
                            prefs.agentUpdateMessage = failedMessage
                        }'''
    text = replace_once(text, old_check, new_check, "agent check UI")

    text = text.replace(
        '"v$NAME build$CODE · 终端卡片与 NAT 参数样式优化"',
        '"v$NAME build$CODE · NAT 诊断、Agent 更新与映射界面修复"',
        1,
    )
    notes = {
        "终端实时栏的速率与连接数改为固定间距，不再把连接数顶到最右侧": "NAT 下拉菜单改为大圆角白色浮层，参数标题与内容拉开间距",
        "路由 NAT 诊断参数框统一高度、圆角、字号与垂直居中": "NAT 任务完成后耗时和路由器响应停止累计，保留最终结果",
        "源码准备流程增加幂等保护，避免重复构建时 DDNS 补丁冲突": "Agent 更新检查改为 Hub 后台任务，502 不再显示原始 HTML",
    }
    for old, new in notes.items():
        text = text.replace(old, new, 1)
    return text


def patch_version_and_verifier() -> tuple[str, str]:
    gradle = GRADLE.read_text(encoding="utf-8")
    gradle = gradle.replace("versionCode = 164", "versionCode = 165", 1)
    gradle = gradle.replace('versionName = "0.10.22"', 'versionName = "0.10.23"', 1)

    verifier = VERIFIER.read_text(encoding="utf-8")
    verifier = verifier.replace("APP v0.10.22 build164", "APP v0.10.23 build165")
    verifier = verifier.replace("'versionCode = 164', 'versionName = \"0.10.22\"'", "'versionCode = 165', 'versionName = \"0.10.23\"'")
    verifier = verifier.replace("'终端卡片与 NAT 参数样式优化',", "'NAT 诊断、Agent 更新与映射界面修复',")
    verifier = verifier.replace(
        "print('build164 terminal card and NAT parameter styling verified')",
        "print('build165 NAT timing, Agent check and mapping editor verified')",
    )
    return gradle, verifier


def verify(main: str, native: str, router_control: str, port_mapping: str, gradle: str, verifier: str) -> None:
    required = (
        'versionCode = 165',
        'versionName = "0.10.23"',
        'NAT 诊断、Agent 更新与映射界面修复',
        'requestAgentUpdateCheck()',
        '"/api/agent/update/check"',
        'agentUpdateUiError',
        'RoundedCornerShape(24.dp)',
        'Text("STUN 服务器", fontSize = 9.sp',
        'Text("STUN 端口", fontSize = 9.sp',
        'NativeValueRow("最终响应", "已收到")',
        'Modifier.clickable(onClick = onEdit)',
        'DialogProperties(usePlatformDefaultWidth = false)',
        'fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()',
    )
    combined = "\n".join((main, native, router_control, port_mapping, gradle, verifier))
    missing = [item for item in required if item not in combined]
    if missing:
        raise RuntimeError(f"build165 verification failed: {missing}")
    forbidden = (
        '来源：全部公网地址',
        'Modifier.fillMaxWidth().fillMaxHeight(.86f)',
        '检查失败：${it.message} · 已保留上次结果',
        'NativeValueRow("路由器响应", if (age < 3L) "刚刚" else "${age} 秒前")\n            }',
    )
    found = [item for item in forbidden if item in combined]
    if found:
        raise RuntimeError(f"build165 forbidden state remains: {found}")


def apply() -> None:
    main = patch_main(MAIN.read_text(encoding="utf-8"))
    native = patch_native(NATIVE.read_text(encoding="utf-8"))
    router_control = patch_router_control(ROUTER_CONTROL.read_text(encoding="utf-8"))
    port_mapping = patch_port_mapping(PORT_MAPPING.read_text(encoding="utf-8"))
    gradle, verifier = patch_version_and_verifier()

    MAIN.write_text(main, encoding="utf-8")
    NATIVE.write_text(native, encoding="utf-8")
    ROUTER_CONTROL.write_text(router_control, encoding="utf-8")
    PORT_MAPPING.write_text(port_mapping, encoding="utf-8")
    GRADLE.write_text(gradle, encoding="utf-8")
    VERIFIER.write_text(verifier, encoding="utf-8")
    verify(main, native, router_control, port_mapping, gradle, verifier)
    print("Android v0.10.23 build165 NAT timing, Agent check and full-screen mapping editors prepared")


if __name__ == "__main__":
    apply()
