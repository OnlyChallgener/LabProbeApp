#!/usr/bin/env python3
"""Apply the router-control integration to the large legacy Compose source.

The project currently keeps most screens in MainActivity.kt. This script makes
small, idempotent edits so CI and local builds use the same integration logic.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
UI = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
DEVICE_CODEC = ROOT / "app/src/main/kotlin/com/labprobe/app/DeviceCodec.kt"


def replace_once(text: str, old: str, new: str) -> str:
    return text.replace(old, new, 1) if old in text else text


def patch_main_activity() -> None:
    text = MAIN.read_text(encoding="utf-8")

    old_route = '                        "tool_portmap" -> PortMappingScreen(prefs, backFromTool)'
    new_routes = '''                        "tool_portmap" -> MappingAndUpnpScreen(prefs, backFromTool)
                        "tool_router_ddns" -> RouterDdnsScreen(prefs, backFromTool)
                        "tool_router_firewall" -> RouterFirewallScreen(prefs, backFromTool)
                        "tool_router_diag" -> RouterDiagnosticScreen(prefs, backFromTool)
                        "tool_router_login" -> RouterLoginSettingsScreen(prefs, backFromTool)'''
    text = replace_once(text, old_route, new_routes)

    function_token = "fun ToolsHomeScreen("
    function_at = text.find(function_token)
    if function_at < 0:
        raise RuntimeError("ToolsHomeScreen not found")
    body_at = text.find("{", function_at)
    if body_at < 0:
        raise RuntimeError("ToolsHomeScreen body not found")

    state_marker = "    var routerFirewallEnabled by remember { mutableIntStateOf(0) }"
    if state_marker not in text[body_at:]:
        state_block = '''
    var routerFirewallEnabled by remember { mutableIntStateOf(0) }
    var routerDdnsHealthy by remember { mutableIntStateOf(0) }
    var routerNativeMappingCount by remember { mutableIntStateOf(0) }
    var routerUpnpMappingCount by remember { mutableIntStateOf(0) }
    var routerUpnpEnabled by remember { mutableStateOf(false) }
    var routerDiagnosticErrors by remember { mutableIntStateOf(0) }

    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return@LaunchedEffect
        val api = RouterControlApi(prefs)
        runCatching { api.firewall() }.onSuccess { state ->
            routerFirewallEnabled = state.rules.count { it.enabled }
        }
        runCatching { api.ddns() }.onSuccess { records ->
            routerDdnsHealthy = records.count {
                it.enabled && !it.status.contains("error", true) && !it.status.contains("fail", true)
            }
        }
        runCatching { api.nativePortMappings() }.onSuccess {
            routerNativeMappingCount = it.size
        }
        runCatching { api.upnp() }.onSuccess {
            routerUpnpEnabled = it.enabled
            routerUpnpMappingCount = it.mappings.size
        }
        runCatching { api.diagnostic() }.onSuccess {
            routerDiagnosticErrors = it.errorCount
        }
    }
'''
        text = text[: body_at + 1] + state_block + text[body_at + 1 :]

    tool_sections_at = text.find("    val toolSections = remember {", body_at)
    if tool_sections_at < 0:
        raise RuntimeError("toolSections marker not found inside ToolsHomeScreen")
    rail_marker = 'onDdns = { open("tool_router_ddns") }'
    if rail_marker not in text[body_at : tool_sections_at + 5000]:
        rail_block = '''    RouterFeatureRail(
        firewallEnabled = routerFirewallEnabled,
        ddnsHealthy = routerDdnsHealthy,
        mappingCount = routerNativeMappingCount + routerUpnpMappingCount,
        upnpEnabled = routerUpnpEnabled,
        diagnosticErrors = routerDiagnosticErrors,
        onConnection = { open("tool_router_login") },
        onMapping = { open("tool_portmap") },
        onDdns = { open("tool_router_ddns") },
        onFirewall = { open("tool_router_firewall") },
        onDiagnostic = { open("tool_router_diag") }
    )

'''
        text = text[:tool_sections_at] + rail_block + text[tool_sections_at:]

    # Add the compact realtime speed/connection line to both wireless and wired cards.
    if "fun DeviceRealtimeStatusBar(" not in text:
        text = replace_once(
            text,
            "            DeviceTodayTrafficBar(d)\n            DeviceFooterLine(d = d, showTime = true)",
            "            DeviceRealtimeStatusBar(d)\n            DeviceTodayTrafficBar(d)\n            DeviceFooterLine(d = d, showTime = true)",
        )
        text = replace_once(
            text,
            "        DeviceTodayTrafficBar(d)\n        DeviceFooterLine(d = d, showTime = false)",
            "        DeviceRealtimeStatusBar(d)\n        DeviceTodayTrafficBar(d)\n        DeviceFooterLine(d = d, showTime = false)",
        )
        helper_marker = "@Composable\nfun DeviceTodayTrafficBar(d: DeviceItem) {"
        helper = '''private fun formatRealtimeRate(bytesPerSecond: Long): String {
    val safe = bytesPerSecond.coerceAtLeast(0L)
    val bitsPerSecond = safe * 8.0
    return if (bitsPerSecond < 1_000_000.0) {
        String.format(Locale.US, "%.0f Kbps", bitsPerSecond / 1_000.0)
    } else {
        String.format(Locale.US, "%.2f Mbps", bitsPerSecond / 1_000_000.0)
    }
}

@Composable
fun DeviceRealtimeStatusBar(d: DeviceItem) {
    if (!d.online) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2563EB).copy(alpha = .045f),
        border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .09f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Speed, null, Modifier.size(14.dp), tint = Color(0xFF2563EB))
            Spacer(Modifier.width(5.dp))
            Text("实时", fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted)
            Spacer(Modifier.width(7.dp))
            Text("↑${formatRealtimeRate(d.realtimeUploadBytes)}", fontSize = 9.8.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B), maxLines = 1)
            Spacer(Modifier.width(7.dp))
            Text("↓${formatRealtimeRate(d.realtimeDownloadBytes)}", fontSize = 9.8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("连接 ${d.connectionCount.coerceAtLeast(0)}", fontSize = 9.6.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)
        }
    }
}

'''
        if helper_marker not in text:
            raise RuntimeError("DeviceTodayTrafficBar marker not found")
        text = text.replace(helper_marker, helper + helper_marker, 1)

    MAIN.write_text(text, encoding="utf-8")


def patch_router_ui() -> None:
    text = UI.read_text(encoding="utf-8")

    normalized = []
    seen_corner = False
    for line in text.splitlines():
        if line == "import androidx.compose.ui.geometry.CornerRadius":
            if seen_corner:
                continue
            seen_corner = True
        normalized.append(line)
    text = "\n".join(normalized) + "\n"

    text = replace_once(
        text,
        '''    diagnosticErrors: Int,
    onMapping: () -> Unit,''',
        '''    diagnosticErrors: Int,
    onConnection: () -> Unit,
    onMapping: () -> Unit,''',
    )
    text = replace_once(
        text,
        '''            Spacer(Modifier.weight(1f))
            Text("左右滑动", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted)''',
        '''            Spacer(Modifier.weight(1f))
            Text("左右滑动", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted)
            Spacer(Modifier.width(5.dp))
            Surface(
                onClick = onConnection,
                shape = CircleShape,
                color = RouterBlue.copy(alpha = .08f),
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Settings, "路由器连接", Modifier.size(16.dp), tint = RouterBlue)
                }
            }''',
    )

    replacements = {
        'DetailShell("路由器连接","Hub 模拟登录 · 凭据加密保存",onBack)':
            'DetailShell("路由器连接","Hub 模拟登录 · 管理密码由你输入",onBack)',
        'Text(config.serialNumber.ifBlank{"保存后自动测试登录"},fontSize=10.2.sp,color=RouterMuted)':
            'Text(config.serialNumber.ifBlank{"输入管理密码后保存并测试"},fontSize=10.2.sp,color=RouterMuted)',
        'Text("密钥仅保存在Hub，不返回APP",fontSize=10.3.sp,color=RouterMuted)':
            'Text("密钥由你输入；保存后不回显",fontSize=10.3.sp,color=RouterMuted)',
    }
    for old, new in replacements.items():
        text = text.replace(old, new)

    UI.write_text(text, encoding="utf-8")


def patch_device_codec() -> None:
    text = DEVICE_CODEC.read_text(encoding="utf-8")

    if "realtimeUploadBytes =" not in text:
        anchor = "        totalDownload = trafficValue("
        start = text.find(anchor)
        if start < 0:
            raise RuntimeError("totalDownload parser anchor not found")
        close_pattern = "        )\n    )"
        close_at = text.find(close_pattern, start)
        if close_at < 0:
            raise RuntimeError("DeviceItem parser close marker not found")
        replacement = '''        ),
        realtimeUploadBytes = o.optLong(
            "realtimeUploadBytes",
            o.optLong("realtimeUpload", o.optLong("realtimeUpBytes", o.optLong("flowUp", 0L)))
        ).coerceAtLeast(0L),
        realtimeDownloadBytes = o.optLong(
            "realtimeDownloadBytes",
            o.optLong("realtimeDownload", o.optLong("realtimeDownBytes", o.optLong("flowDown", 0L)))
        ).coerceAtLeast(0L),
        connectionCount = o.optInt(
            "connectionCount",
            o.optInt("flow_cnt", o.optInt("flowCnt", 0))
        ).coerceAtLeast(0)
    )'''
        text = text[:close_at] + replacement + text[close_at + len(close_pattern):]

    text = replace_once(
        text,
        '    .put("totalDownload", totalDownload)',
        '''    .put("totalDownload", totalDownload)
    .put("realtimeUploadBytes", realtimeUploadBytes)
    .put("realtimeDownloadBytes", realtimeDownloadBytes)
    .put("connectionCount", connectionCount)''',
    )

    DEVICE_CODEC.write_text(text, encoding="utf-8")


def main() -> None:
    patch_main_activity()
    patch_router_ui()
    patch_device_codec()
    print("Router-control integration applied.")


if __name__ == "__main__":
    main()
