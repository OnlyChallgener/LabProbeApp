#!/usr/bin/env python3
"""Apply the user's narrowly scoped v0.10.15 UI corrections.

Only these items are touched:
- move the terminal connection count away from the far-right edge;
- localize the router-settings status message;
- localize the router Beta "no new version" message.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
ROUTER_SETTINGS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt"
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"


def patch_terminal_connection_alignment() -> None:
    text = MAIN.read_text(encoding="utf-8")
    start = text.find("fun DeviceRealtimeStatusBar")
    end = text.find("\n@Composable\nfun DeviceTodayTrafficBar", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing DeviceRealtimeStatusBar boundaries")
    section = text[start:end]
    old = '''            Text("↓${formatRealtimeRate(d.realtimeDownloadBytes)}", fontSize = 9.8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("连接 ${d.connectionCount.coerceAtLeast(0)}", fontSize = 9.6.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)'''
    new = '''            Text("↓${formatRealtimeRate(d.realtimeDownloadBytes)}", fontSize = 9.8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4), maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Text("连接 ${d.connectionCount.coerceAtLeast(0)}", fontSize = 9.6.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)'''
    if new not in section:
        if old not in section:
            raise RuntimeError("missing terminal connection alignment pattern")
        section = section.replace(old, new, 1)
    MAIN.write_text(text[:start] + section + text[end:], encoding="utf-8")


def patch_router_status_message() -> None:
    text = ROUTER_SETTINGS.read_text(encoding="utf-8")
    helper = '''
private fun routerSettingsStatusMessage(status: RouterHubStatus): String = when {
    status.connected && status.state == "ready" -> "路由器已连接，实时数据正常"
    status.state == "syncing" -> "路由器已登录，正在等待实时数据"
    status.errorCode == "HUB_NO_ROUTER_DATA" -> "Hub 在线，但尚未获取到路由器数据"
    status.state == "unconfigured" -> "尚未配置路由器管理地址和密码"
    status.state == "router_login_failed" -> "路由器连接失败，请检查密码或网络"
    else -> status.message.ifBlank { "正在检查路由器状态" }
}
'''
    if "private fun routerSettingsStatusMessage" not in text:
        anchor = 'private val SettingsBorder = Color(0xFFE3EAF4)\n'
        if anchor not in text:
            raise RuntimeError("missing RouterSettings status helper anchor")
        text = text.replace(anchor, anchor + helper, 1)

    old = '                Text(status.message, fontSize = 9.7.sp, color = SettingsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)'
    new = '                Text(routerSettingsStatusMessage(status), fontSize = 9.7.sp, color = SettingsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)'
    if new not in text:
        if old not in text:
            raise RuntimeError("missing RouterSettings status message pattern")
        text = text.replace(old, new, 1)
    ROUTER_SETTINGS.write_text(text, encoding="utf-8")


def patch_beta_message() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")
    old = '            message = next.optString("msg").ifBlank { if (versions.isEmpty()) "当前没有可用Beta版本" else "发现可用Beta版本" },'
    new = '''            message = when (val rawMessage = next.optString("msg").trim()) {
                "no new version available" -> "当前没有可用 Beta 版本"
                else -> rawMessage.ifBlank { if (versions.isEmpty()) "当前没有可用 Beta 版本" else "发现可用 Beta 版本" }
            },'''
    if new not in text:
        if old not in text:
            raise RuntimeError("missing router Beta message pattern")
        text = text.replace(old, new, 1)
    ROUTER_NATIVE.write_text(text, encoding="utf-8")


def apply() -> None:
    patch_terminal_connection_alignment()
    patch_router_status_message()
    patch_beta_message()
    print("requested v0.10.15 hotfix applied")


if __name__ == "__main__":
    apply()
