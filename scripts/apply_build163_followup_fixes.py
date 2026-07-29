#!/usr/bin/env python3
"""Build164 follow-up: responsive realtime UI, unified NAT fields and resilient source prep."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
MAIN = SRC / "MainActivity.kt"
PORT_MAPPING = SRC / "PortMapping.kt"
ROUTER_NATIVE = SRC / "RouterNativeToolsUi.kt"
DESIGN = SRC / "ui/design/LabDesignComponents.kt"
GRADLE = ROOT / "app/build.gradle.kts"
VERIFIER = ROOT / "scripts/verify_build154_sources.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build164 follow-up anchor: {label}")
    return text.replace(old, new, 1)


def patch_main(text: str) -> str:
    text, count = re.subn(
        r"\s+refreshAll\(forceFull = forceFull, silent = true\)\n\s+if \(foregroundActive\) startRealtime\(\)",
        "\n                if (foregroundActive) startRealtime()\n                refreshAll(forceFull = forceFull, silent = true)",
        text,
        count=1,
    )
    if count != 1 and "if (foregroundActive) startRealtime()\n                refreshAll(forceFull = forceFull, silent = true)" not in text:
        raise RuntimeError("missing foreground recovery order anchor")

    new_start = '''    suspend fun startRealtime() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        startRealtimeRendering()
        realtimeClient.start(prefs.hub, prefs.token)
        stateScope.launch { calibrateRealtimeCache() }
    }'''
    if new_start not in text:
        start = text.find("    suspend fun startRealtime() {")
        end = text.find("\n\n    suspend fun refreshRouterDashboard", start)
        if start < 0 or end < 0:
            raise RuntimeError("missing generated startRealtime boundaries")
        text = text[:start] + new_start + text[end:]

    text = text.replace(
        'requestJson("/api/agent/update/status?refresh=1")',
        'requestJson("/api/agent/update/status")',
        1,
    )

    daily_vars_old = '''    var noteEdit by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    fun loadDate(d: String) { scope.launch { runCatching { HubApi(prefs).getDaily(d) }.onSuccess { val v = it.optJSONObject("daily") ?: it; data = v; noteText = v.optString("note") } } }'''
    daily_vars_new = '''    var noteEdit by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var dailyLoadJob by remember { mutableStateOf<Job?>(null) }
    var dailyRequestId by remember { mutableLongStateOf(0L) }
    var dailySyncMessage by remember { mutableStateOf("") }

    fun localDailyShell(note: String = ""): JSONObject = JSONObject()
        .put("summary", JSONObject())
        .put("sections", JSONObject())
        .put("note", note)

    fun loadDate(d: String) {
        dailyLoadJob?.cancel()
        val requestId = ++dailyRequestId
        noteText = ""
        data = localDailyShell()
        dailySyncMessage = "正在后台同步…"
        dailyLoadJob = scope.launch {
            val result = runCatching { HubApi(prefs).getDaily(d) }
            if (requestId != dailyRequestId) return@launch
            result.onSuccess {
                val value = it.optJSONObject("daily") ?: it
                data = value
                noteText = value.optString("note")
                dailySyncMessage = ""
            }.onFailure {
                dailySyncMessage = "同步失败，已显示本地缓存"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { dailyLoadJob?.cancel() }
    }'''
    text = replace_once(text, daily_vars_old, daily_vars_new, "daily cache-first loader")

    cert_anchor = '''    CertificateExpirySection(prefs)
    val d = data'''
    cert_new = '''    CertificateExpirySection(prefs)
    if (dailySyncMessage.isNotBlank()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .10f)),
        ) {
            Text(
                dailySyncMessage,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 10.8.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
            )
        }
    }
    val d = data'''
    text = replace_once(text, cert_anchor, cert_new, "daily sync status")

    start = text.find("@Composable\nfun DailySection(")
    end = text.find("@Composable\nfun DailyDeviceSummaryRow", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing DailySection function")
    daily_section = '''@Composable
fun DailySection(title: String, items: JSONArray, icon: ImageVector, accent: Color, kind: String) {
    if (items.length() <= 0) return
    ExpressiveCard(title, "${items.length()} 条", icon, accent) {
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (kind == "devices") 6.dp else 0.dp),
            ) {
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    when (kind) {
                        "devices" -> DailyDeviceSummaryRow(o)
                        "address" -> DailyAddressSummaryRow(o)
                        else -> DailyTextSummaryRow(o)
                    }
                    if (i < items.length() - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}


'''
    text = text[:start] + daily_section + text[end:]
    text = text.replace(
        'Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {',
        'Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {',
        1,
    )

    realtime_old = '''            Text("↓${formatRealtimeRate(d.realtimeDownloadBytes)}", fontSize = 9.8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("连接 ${d.connectionCount.coerceAtLeast(0)}", fontSize = 9.6.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)'''
    realtime_new = '''            Text("↓${formatRealtimeRate(d.realtimeDownloadBytes)}", fontSize = 9.8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4), maxLines = 1)
            Spacer(Modifier.width(16.dp))
            Text("连接 ${d.connectionCount.coerceAtLeast(0)}", fontSize = 9.6.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)'''
    text = replace_once(text, realtime_old, realtime_new, "device realtime connection spacing")

    text = text.replace(
        '"v$NAME build$CODE · 终端列表五秒实时同步"',
        '"v$NAME build$CODE · 终端卡片与 NAT 参数样式优化"',
        1,
    )
    release_notes = {
        "APP 使用现有 APP Token 直接连接 Hub 原生 WSS，不再需要 MQTT 地址或账号密码": "终端实时栏的速率与连接数改为固定间距，不再把连接数顶到最右侧",
        "路由 fast 与终端增量仅经 Hub 内存快照推送，HTTP 只用于首次与重连校准": "路由 NAT 诊断参数框统一高度、圆角、字号与垂直居中",
        "APP 退到后台或 WSS 断开时暂停平滑渲染和终端高频采样需求": "源码准备流程增加幂等保护，避免重复构建时 DDNS 补丁冲突",
    }
    for old, new in release_notes.items():
        text = text.replace(old, new, 1)
    return text


def patch_router_native(text: str) -> str:
    text = text.replace('val serverShape = RoundedCornerShape(18.dp)', 'val serverShape = RoundedCornerShape(14.dp)', 1)
    text = text.replace(
        'modifier = Modifier.fillMaxWidth().height(50.dp).nativeBlueShadow(serverShape, 5.dp),',
        'modifier = Modifier.fillMaxWidth().height(54.dp).nativeBlueShadow(serverShape, 5.dp),',
        1,
    )
    server_old = '''                    Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp), tint = NativeBlue)
                    Spacer(Modifier.width(7.dp))
                    Text(server, Modifier.weight(1f), color = NativeInk, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = NativeBlue)'''
    server_new = '''                    Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp), tint = NativeBlue)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text("STUN 服务器", fontSize = 8.5.sp, lineHeight = 10.sp, color = NativeMuted, fontWeight = FontWeight.SemiBold)
                        Text(server, fontSize = 12.5.sp, lineHeight = 15.sp, color = NativeInk, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = NativeBlue)'''
    text = replace_once(text, server_old, server_new, "NAT server field typography")

    selector_old = '''            modifier = Modifier.fillMaxWidth().height(50.dp).nativeBlueShadow(shape, 5.dp),
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
            border = BorderStroke(1.dp, NativeBlue.copy(alpha = .32f)),
            contentPadding = PaddingValues(horizontal = 11.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(label, fontSize = 8.5.sp, color = NativeMuted, fontWeight = FontWeight.SemiBold)
                Text(value, fontSize = 11.2.sp, color = NativeInk, fontWeight = FontWeight.Black, maxLines = 1)
            }'''
    selector_new = '''            modifier = Modifier.fillMaxWidth().height(54.dp).nativeBlueShadow(shape, 5.dp),
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
            border = BorderStroke(1.dp, NativeBlue.copy(alpha = .32f)),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(label, fontSize = 8.5.sp, lineHeight = 10.sp, color = NativeMuted, fontWeight = FontWeight.SemiBold)
                Text(value, fontSize = 12.5.sp, lineHeight = 15.sp, color = NativeInk, fontWeight = FontWeight.Black, maxLines = 1)
            }'''
    text = replace_once(text, selector_old, selector_new, "NAT selector field typography")

    port_start = text.find('@Composable\nprivate fun NativeCompactPortField(')
    port_end = text.find('\n\nprivate data class RouterNatResult', port_start + 12)
    if port_start < 0 or port_end < 0:
        raise RuntimeError("missing NativeCompactPortField boundaries")
    port_field = '''@Composable
private fun NativeCompactPortField(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().height(54.dp).nativeBlueShadow(shape, 4.dp),
        shape = shape,
        color = Color.White,
        border = BorderStroke(1.dp, NativeBlue.copy(alpha = .30f))
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("STUN 端口", fontSize = 8.5.sp, lineHeight = 10.sp, color = NativeMuted, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(
                    color = NativeInk,
                    fontSize = 12.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                cursorBrush = SolidColor(NativeBlue)
            )
        }
    }
}'''
    text = text[:port_start] + port_field + text[port_end:]
    return text


def patch_design(text: str) -> str:
    text = text.replace('.fillMaxHeight(0.88f)', '.fillMaxHeight(0.96f)', 1)
    text = text.replace(
        '.padding(horizontal = 16.dp, vertical = 6.dp)\n    } else {',
        '.padding(horizontal = 16.dp, vertical = 6.dp)\n            .navigationBarsPadding()\n    } else {',
        1,
    )
    return text


def patch_port_mapping(text: str) -> str:
    text = text.replace(
        'contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)',
        'contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)',
        1,
    )
    text = text.replace('modifier = modifier.height(46.dp)', 'modifier = modifier.height(42.dp)', 1)
    return text


def patch_version_and_verifier() -> tuple[str, str]:
    gradle = GRADLE.read_text(encoding="utf-8")
    gradle = gradle.replace("versionCode = 163", "versionCode = 164", 1)
    gradle = gradle.replace('versionName = "0.10.21"', 'versionName = "0.10.22"', 1)

    verifier = VERIFIER.read_text(encoding="utf-8")
    verifier = verifier.replace("APP v0.10.21 build163", "APP v0.10.22 build164")
    verifier = verifier.replace("'versionCode = 163', 'versionName = \"0.10.21\"'", "'versionCode = 164', 'versionName = \"0.10.22\"'")
    verifier = verifier.replace("'终端列表五秒实时同步',", "'终端卡片与 NAT 参数样式优化',")
    verifier = verifier.replace(
        "print('build163 five-second full terminal snapshot merge verified')",
        "print('build164 terminal card and NAT parameter styling verified')",
    )
    return gradle, verifier


def verify(main: str, design: str, port_mapping: str, router_native: str, gradle: str, verifier: str) -> None:
    required = (
        'if (foregroundActive) startRealtime()\n                refreshAll(forceFull = forceFull, silent = true)',
        'stateScope.launch { calibrateRealtimeCache() }',
        'data = localDailyShell()',
        '同步失败，已显示本地缓存',
        'SelectionContainer {',
        'Arrangement.spacedBy(if (kind == "devices") 6.dp else 0.dp)',
        'Spacer(Modifier.width(16.dp))',
        '终端卡片与 NAT 参数样式优化',
        '.fillMaxHeight(0.96f)',
        'modifier = modifier.height(42.dp)',
        'Text("STUN 服务器", fontSize = 8.5.sp',
        'Text("STUN 端口", fontSize = 8.5.sp',
        'fontSize = 12.5.sp',
        'height(54.dp).nativeBlueShadow',
        'versionCode = 164',
        'versionName = "0.10.22"',
    )
    combined = main + "\n" + design + "\n" + port_mapping + "\n" + router_native + "\n" + gradle + "\n" + verifier
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build164 follow-up verification failed: {missing}")
    if 'Spacer(Modifier.weight(1f))\n            Text("连接 ${d.connectionCount.coerceAtLeast(0)}"' in main:
        raise RuntimeError("device connection count is still pinned to the far-right edge")


def apply() -> None:
    main = patch_main(MAIN.read_text(encoding="utf-8"))
    design = patch_design(DESIGN.read_text(encoding="utf-8"))
    port_mapping = patch_port_mapping(PORT_MAPPING.read_text(encoding="utf-8"))
    router_native = patch_router_native(ROUTER_NATIVE.read_text(encoding="utf-8"))
    gradle, verifier = patch_version_and_verifier()
    MAIN.write_text(main, encoding="utf-8")
    DESIGN.write_text(design, encoding="utf-8")
    PORT_MAPPING.write_text(port_mapping, encoding="utf-8")
    ROUTER_NATIVE.write_text(router_native, encoding="utf-8")
    GRADLE.write_text(gradle, encoding="utf-8")
    VERIFIER.write_text(verifier, encoding="utf-8")
    verify(main, design, port_mapping, router_native, gradle, verifier)
    print("build164 device realtime spacing, unified NAT fields and resilient UI prepared")


if __name__ == "__main__":
    apply()
