#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
ROUTER_CONTROL = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
ROUTER_API = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlApi.kt"

MARKER = "v0.10.15 build145 · 路由页面稳定与诊断交互修复"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing v0.10.15 patch pattern: {label}")
    return text.replace(old, new, 1)


def patch_router_control() -> None:
    text = ROUTER_CONTROL.read_text(encoding="utf-8")
    cache_block = '''
private object RouterControlMemoryCache {
    var ddnsRows: List<DdnsRecord> = emptyList()
}
'''
    if "private object RouterControlMemoryCache" not in text:
        text = text.replace('private val RouterPage = Color(0xFFF5F8FD)\n', 'private val RouterPage = Color(0xFFF5F8FD)\n' + cache_block, 1)

    old = '''    var rows by remember { mutableStateOf<List<DdnsRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DdnsRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DdnsRecord?>(null) }
    suspend fun refresh(force:Boolean=false){ if(!force)loading=true;runCatching{api.ddns(force)}.onSuccess{rows=it;error=""}.onFailure{error=it.message.orEmpty()};loading=false }
'''
    new = '''    var rows by remember { mutableStateOf(RouterControlMemoryCache.ddnsRows) }
    var loading by remember { mutableStateOf(rows.isEmpty()) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DdnsRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DdnsRecord?>(null) }
    suspend fun refresh(force:Boolean=false){
        val hadRows = rows.isNotEmpty()
        if (!hadRows) loading = true
        runCatching { api.ddns(force) }
            .onSuccess { latest ->
                rows = latest
                RouterControlMemoryCache.ddnsRows = latest
                error = ""
            }
            .onFailure { failure ->
                if (!hadRows) error = failure.message.orEmpty()
            }
        loading = false
    }
'''
    text = replace_once(text, old, new, "DDNS cache-preserving refresh")

    text = text.replace('.onSuccess{rows=it}.onFailure{error=it.message.orEmpty()}', '.onSuccess{rows=it;RouterControlMemoryCache.ddnsRows=it}.onFailure{error=it.message.orEmpty()}')
    text = text.replace('.onSuccess{rows=it;adding=false;editing=null}', '.onSuccess{rows=it;RouterControlMemoryCache.ddnsRows=it;adding=false;editing=null}')
    text = text.replace('.onSuccess { rows=it; deleteTarget=null }', '.onSuccess { rows=it; RouterControlMemoryCache.ddnsRows=it; deleteTarget=null }')
    ROUTER_CONTROL.write_text(text, encoding="utf-8")


def patch_router_native() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")
    imports = {
        'import androidx.compose.foundation.text.selection.SelectionContainer\n': 'import androidx.compose.foundation.text.BasicTextField\nimport androidx.compose.foundation.text.KeyboardOptions\nimport androidx.compose.foundation.text.selection.SelectionContainer\n',
        'import androidx.compose.ui.draw.clip\n': 'import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.shadow\n',
        'import androidx.compose.ui.graphics.Color\n': 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.SolidColor\n',
        'import androidx.compose.ui.text.font.FontFamily\n': 'import androidx.compose.ui.text.TextStyle\nimport androidx.compose.ui.text.font.FontFamily\n',
        'import androidx.compose.ui.text.style.TextOverflow\n': 'import androidx.compose.ui.text.style.TextAlign\nimport androidx.compose.ui.text.style.TextOverflow\n',
    }
    for old, new in imports.items():
        if new not in text:
            text = text.replace(old, new, 1)

    helpers = r'''
private object RouterNativeMemoryCache {
    var natResult: RouterNatResult? = null
}

private fun Modifier.nativeBlueShadow(shape: RoundedCornerShape, elevation: androidx.compose.ui.unit.Dp = 7.dp): Modifier =
    shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = NativeBlue.copy(alpha = .22f),
        spotColor = NativeBlue.copy(alpha = .28f)
    )

private fun natTypeZh(value: String): String = when (value.trim().lowercase()) {
    "open internet", "open-internet" -> "开放互联网"
    "full cone", "full-cone", "full cone nat" -> "完全锥形 NAT"
    "restricted cone", "restricted-cone", "restricted cone nat" -> "受限锥形 NAT"
    "port-restricted cone", "port restricted cone", "port-restricted cone nat" -> "端口受限锥形 NAT"
    "symmetric", "symmetric nat" -> "对称型 NAT"
    "symmetric udp firewall" -> "对称 UDP 防火墙"
    "udp blocked", "blocked" -> "UDP 被阻断"
    "unknown", "" -> "--"
    else -> value
}

private fun natStatusZh(value: String): String = when (value.trim().lowercase()) {
    "idle" -> "等待检测"
    "running", "detecting", "started" -> "检测中"
    "completed", "success" -> "检测完成"
    "failed", "error" -> "检测失败"
    else -> value.ifBlank { "等待检测" }
}

private fun natLogZh(raw: String): String {
    var text = raw
    val replacements = listOf(
        "[NAT Detection] Starting RFC 3489 classic detection" to "[NAT 检测] 开始 RFC 3489 经典检测",
        "[NAT Detection] Starting RFC 5780 detection" to "[NAT 检测] 开始 RFC 5780 检测",
        "[Configuration] STUN server:" to "[配置] STUN 服务器：",
        "[Configuration] Local address:" to "[配置] 本地地址：",
        "[Test I] Sending Binding Request to" to "[测试 I] 正在发送绑定请求到",
        "[Test I] Mapped address:" to "[测试 I] 映射地址：",
        "[Test I] Changed address:" to "[测试 I] 变更地址：",
        "[Test II] Sending ChangeIP+ChangePort request" to "[测试 II] 正在发送更改 IP 和端口请求",
        "[Test III] Sending ChangePort request" to "[测试 III] 正在发送更改端口请求",
        "[STUN Request] Response timeout" to "[STUN 请求] 响应超时",
        "[STUN Request] Retry attempt" to "[STUN 请求] 重试次数",
        "[STUN Request] Failed after" to "[STUN 请求] 多次尝试后失败：",
        "[Detection] Local IP:" to "[检测] 本地 IP：",
        "Mapped IP:" to "映射 IP：",
        "No response, NAT detected, performing further tests" to "无响应，检测到 NAT，继续执行后续测试",
        "Sending Binding Request to alternate server" to "正在向备用服务器发送绑定请求",
        "Same mapping - consistent mapping behavior" to "映射一致，映射行为稳定",
        "No response - Port Restricted Cone NAT" to "无响应：端口受限锥形 NAT",
        "Detection completed successfully" to "检测成功完成",
        "NAT Type:" to "NAT 类型：",
        "External:" to "外网地址：",
        "Result: port-restricted cone" to "结果：端口受限锥形 NAT",
        "Result: restricted cone" to "结果：受限锥形 NAT",
        "Result: full cone" to "结果：完全锥形 NAT",
        "Result: symmetric" to "结果：对称型 NAT"
    )
    replacements.forEach { (old, new) -> text = text.replace(old, new, ignoreCase = true) }
    return text
}

@Composable
private fun NativeSelector(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier) {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.fillMaxWidth().height(50.dp).nativeBlueShadow(shape, 5.dp),
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
            border = BorderStroke(1.dp, NativeBlue.copy(alpha = .32f)),
            contentPadding = PaddingValues(horizontal = 11.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(label, fontSize = 8.5.sp, color = NativeMuted, fontWeight = FontWeight.SemiBold)
                Text(value, fontSize = 11.2.sp, color = NativeInk, fontWeight = FontWeight.Black, maxLines = 1)
            }
            Icon(Icons.Rounded.ArrowDropDown, null, tint = NativeBlue)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(Color.White),
            containerColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 7.dp
        ) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title, color = NativeInk, fontWeight = FontWeight.Bold) },
                    onClick = { onSelect(key); onExpandedChange(false) },
                    modifier = Modifier.background(if (title == value) NativeBlue.copy(alpha = .08f) else Color.White)
                )
            }
        }
    }
}

@Composable
private fun NativeCompactPortField(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("STUN端口", fontSize = 9.5.sp, color = NativeMuted, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth().height(48.dp).nativeBlueShadow(shape, 4.dp),
            shape = shape,
            color = Color.White,
            border = BorderStroke(1.dp, NativeBlue.copy(alpha = .30f))
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                BasicTextField(
                    value = value,
                    onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = NativeInk,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    cursorBrush = SolidColor(NativeBlue)
                )
            }
        }
    }
}
'''
    if "private object RouterNativeMemoryCache" not in text:
        text = text.replace('private val NativeBorder = Color(0xFFE3EAF4)\n', 'private val NativeBorder = Color(0xFFE3EAF4)\n' + helpers, 1)

    text = text.replace('    var result by remember { mutableStateOf(RouterNatResult()) }\n    var running by remember { mutableStateOf(false) }\n    var loading by remember { mutableStateOf(true) }\n    var error by remember { mutableStateOf("") }\n    var serverMenu by remember { mutableStateOf(false) }\n', '    var result by remember { mutableStateOf(RouterNativeMemoryCache.natResult ?: RouterNatResult()) }\n    var running by remember { mutableStateOf(false) }\n    var loading by remember { mutableStateOf(RouterNativeMemoryCache.natResult == null) }\n    var error by remember { mutableStateOf("") }\n    var serverMenu by remember { mutableStateOf(false) }\n    var modeMenu by remember { mutableStateOf(false) }\n    var interfaceMenu by remember { mutableStateOf(false) }\n')
    text = text.replace('                result = it\n                error = ""\n', '                result = it\n                RouterNativeMemoryCache.natResult = it\n                error = ""\n', 1)

    old_controls = '''            Box {
                OutlinedButton(onClick = { serverMenu = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(server, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                    servers.forEach { host ->
                        DropdownMenuItem(text = { Text(host) }, onClick = { server = host; serverMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = { Text("STUN端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "classic", onClick = { mode = "classic" }, label = { Text("RFC 3489") })
                FilterChip(selected = mode == "5780", onClick = { mode = "5780" }, label = { Text("RFC 5780") })
                FilterChip(selected = interfaceName == "wan", onClick = { interfaceName = "wan" }, label = { Text("WAN") })
                FilterChip(selected = interfaceName == "wan1", onClick = { interfaceName = "wan1" }, label = { Text("WAN1") })
            }
'''
    new_controls = '''            val serverShape = RoundedCornerShape(14.dp)
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
                    modifier = Modifier.background(Color.White),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 7.dp
                ) {
                    servers.forEach { host ->
                        DropdownMenuItem(
                            text = { Text(host, color = NativeInk, fontWeight = FontWeight.Bold) },
                            onClick = { server = host; serverMenu = false },
                            modifier = Modifier.background(if (host == server) NativeBlue.copy(alpha = .08f) else Color.White)
                        )
                    }
                }
            }
            NativeCompactPortField(portText) { portText = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeSelector(
                    label = "检测协议",
                    value = if (mode == "5780") "RFC 5780" else "RFC 3489",
                    options = listOf("classic" to "RFC 3489", "5780" to "RFC 5780"),
                    expanded = modeMenu,
                    onExpandedChange = { modeMenu = it },
                    onSelect = { mode = it },
                    modifier = Modifier.weight(1f)
                )
                NativeSelector(
                    label = "出口接口",
                    value = interfaceName.uppercase(),
                    options = listOf("wan" to "WAN", "wan1" to "WAN1"),
                    expanded = interfaceMenu,
                    onExpandedChange = { interfaceMenu = it },
                    onSelect = { interfaceName = it },
                    modifier = Modifier.weight(1f)
                )
            }
'''
    text = replace_once(text, old_controls, new_controls, "NAT selector controls")

    text = text.replace('modifier = Modifier.fillMaxWidth().height(44.dp),\n                shape = RoundedCornerShape(14.dp)', 'modifier = Modifier.fillMaxWidth().height(44.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),\n                shape = RoundedCornerShape(14.dp)', 1)
    text = text.replace('                result = RouterNatResult(status = "running", mode = mode)\n', '                result = RouterNatResult(status = "running", mode = mode)\n                RouterNativeMemoryCache.natResult = result\n', 1)
    text = text.replace('                else -> result.status.ifBlank { "等待检测" }\n', '                else -> natStatusZh(result.status)\n', 1)
    text = text.replace('            NativeValueRow("NAT类型", result.natType.ifBlank { "--" })\n', '            NativeValueRow("NAT类型", natTypeZh(result.natType))\n', 1)
    text = text.replace('                        result.log,\n', '                        natLogZh(result.log),\n', 1)
    text = text.replace('                modifier = Modifier.fillMaxWidth().height(42.dp),\n                shape = RoundedCornerShape(14.dp),\n                colors = ButtonDefaults.buttonColors(containerColor = NativeCyan)', '                modifier = Modifier.fillMaxWidth().height(42.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),\n                shape = RoundedCornerShape(14.dp),\n                colors = ButtonDefaults.buttonColors(containerColor = NativeCyan)', 1)
    ROUTER_NATIVE.write_text(text, encoding="utf-8")


def patch_router_api() -> None:
    text = ROUTER_API.read_text(encoding="utf-8")
    old = '''    fun apply(status: RouterHubStatus) {
        snapshot = RouterConnectionSnapshot(
            connected = status.connected,
            statusText = status.message,
            lastSuccessAt = status.lastSuccessAt,
            lastError = if (status.connected) "" else status.message
        )
    }'''
    new = '''    fun apply(status: RouterHubStatus) {
        val localized = when {
            status.connected && status.state == "ready" -> "路由器已连接，实时数据正常"
            status.state == "syncing" -> "路由器已登录，正在同步实时数据"
            status.state == "router_login_failed" -> "路由器连接异常，请检查密码或网络"
            status.errorCode == "HUB_NO_ROUTER_DATA" -> "Hub 在线，正在等待路由器数据"
            else -> status.message.ifBlank { "等待路由器状态" }
        }
        snapshot = RouterConnectionSnapshot(
            connected = status.connected,
            statusText = localized,
            lastSuccessAt = status.lastSuccessAt,
            lastError = if (status.connected) "" else localized
        )
    }'''
    text = replace_once(text, old, new, "localized router connection state")
    ROUTER_API.write_text(text, encoding="utf-8")


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")
    old = '''        "v0.10.14 build144 · 实时刷新与页面稳定性修复" to listOf(
            "路由状态改为约1秒读取Hub快照，快数据不再被终端列表查询阻塞",
            "刷新时保留并合并上一份完整数据，避免页面内容短暂变白",
            "路由连接与数据同步状态全部改为中文，不再出现英文提示",
            "首页SSH快捷小卡改为浅灰色，手动刷新改为非阻塞"
        )'''
    new = '''        "v0.10.15 build145 · 路由页面稳定与诊断交互修复" to listOf(
            "路由器与DDNS页面刷新时保留上一份内容，不再出现空白卡片或整页白屏",
            "路由连接状态严格区分会话已登录与实时数据已到达，所有提示改为中文",
            "路由NAT诊断将协议和WAN接口分别合并为下拉框，弹层改为白色",
            "STUN端口框缩矮并居中，诊断按钮统一增加蓝色阴影，NAT结果中文化"
        )'''
    text = replace_once(text, old, new, "v0.10.15 changelog")
    MAIN.write_text(text, encoding="utf-8")


def apply() -> None:
    current = MAIN.read_text(encoding="utf-8")
    if MARKER in current:
        print("v0.10.15 router stability fixes already applied")
        return
    patch_router_control()
    patch_router_native()
    patch_router_api()
    patch_main()
    print("v0.10.15 router stability fixes applied")


if __name__ == "__main__":
    apply()
