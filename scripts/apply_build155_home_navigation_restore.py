#!/usr/bin/env python3
"""Restore the home navigation contract from router-control build141.

This patch is intentionally independent from realtime/WSS migrations. It keeps:
- the router settings entry on the home page;
- the compact home shortcut opening SSH;
- the dedicated DDNS card opening the router DDNS page;
- router feature management out of the toolbox overview.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"


def replace_any(text: str, olds: tuple[str, ...], new: str, label: str) -> str:
    if new in text:
        return text
    for old in olds:
        if old in text:
            return text.replace(old, new, 1)
    raise RuntimeError(f"missing home navigation pattern: {label}")


def apply() -> None:
    text = MAIN.read_text(encoding="utf-8")

    old_small_events = '''                        HealthMiniCard(
                            title = "VPN / STUN",
                            value = "${vpnRows.size}",
                            unit = "条",
                            icon = Icons.Rounded.VpnKey,
                            accent = Color(0xFF7C3AED),
                            subtitle = vpnRows.firstOrNull()?.first ?: "暂无地址",
                            modifier = Modifier.weight(1f).clickable { onNavigate("events") }
                        )'''
    old_small_ddns = old_small_events.replace('onNavigate("events")', 'onNavigate("tool_router_ddns")')
    ddns_card = '''                        HomeDdnsMiniCard(
                            prefs = prefs,
                            onClick = { onNavigate("tool_router_ddns") },
                            modifier = Modifier.weight(1f)
                        )'''
    text = replace_any(text, (old_small_events, old_small_ddns), ddns_card, "home DDNS card")

    old_score_shortcut = '                HealthShortcutTile(Icons.Rounded.VpnKey, "VPN", if (vpnOk) "已记录" else "无数据", if (vpnOk) LabV2.Purple else LabV2.InkMuted, Modifier.weight(1f)) { onNavigate("events") }\n'
    ssh_shortcut = '                HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", LabV2.Purple, Modifier.weight(1f)) { onNavigate("tool_ssh") }\n'
    text = replace_any(text, (old_score_shortcut,), ssh_shortcut, "home SSH shortcut")

    old_big_events = '''                    "vpn" -> if (vpnRows.isNotEmpty()) HealthVpnCard(
                        rows = vpnRows,
                        privacyMode = privacyMode,
                        onTogglePrivacy = {
                            privacyMode = !privacyMode
                            prefs.privacyMode = privacyMode
                        },
                        onClick = { onNavigate("events") }
                    )'''
    old_big_ddns = old_big_events.replace('onNavigate("events")', 'onNavigate("tool_router_ddns")')
    router_card = '                    "vpn" -> RouterSettingsHomeCard { onNavigate("router_settings") }'
    text = replace_any(text, (old_big_events, old_big_ddns), router_card, "home router settings card")

    tools_start = text.find('@Composable\nfun ToolsHomeScreen')
    tools_end = text.find('\n@Composable\nfun ReorderableToolSection', tools_start)
    if tools_start < 0 or tools_end < 0:
        raise RuntimeError("missing ToolsHomeScreen boundaries")
    tools = text[tools_start:tools_end]

    # The build141 contract keeps router settings on Home, not as a toolbox rail.
    if "var routerFirewallEnabled" in tools:
        tools, count = re.subn(
            r'(fun ToolsHomeScreen\(prefs: AppPrefs, topNav: @Composable \(\) -> Unit, open: \(String\) -> Unit\) = ScreenShell\("工具箱", "长按功能卡可调整分组顺序", topNav = topNav\) \{\n).*?(    val ctx = LocalContext.current\n)',
            r'\1\2',
            tools,
            count=1,
            flags=re.S,
        )
        if count != 1:
            raise RuntimeError("failed to remove toolbox router preload block")
    if "RouterFeatureRail(" in tools:
        tools, count = re.subn(
            r'\n    RouterFeatureRail\(.*?\n    \)\n\n    val toolSections',
            '\n    val toolSections',
            tools,
            count=1,
            flags=re.S,
        )
        if count != 1:
            raise RuntimeError("failed to remove toolbox router feature rail")
    tools = tools.replace(
        '                ToolMosaicItem("端口映射", Icons.Rounded.SwapHoriz, LabV2.Primary, "tool_portmap")',
        '                ToolMosaicItem("WOL 唤醒", Icons.Rounded.PowerSettingsNew, LabV2.Primary, "wol")',
        1,
    )
    text = text[:tools_start] + tools + text[tools_end:]

    required = (
        'RouterSettingsHomeCard { onNavigate("router_settings") }',
        'HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", LabV2.Purple, Modifier.weight(1f)) { onNavigate("tool_ssh") }',
        'HomeDdnsMiniCard(',
        'onClick = { onNavigate("tool_router_ddns") }',
    )
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError(f"home navigation verification failed: {missing}")

    final_tools = text[tools_start:text.find('\n@Composable\nfun ReorderableToolSection', tools_start)]
    if "RouterFeatureRail(" in final_tools or "var routerFirewallEnabled" in final_tools:
        raise RuntimeError("router settings rail still remains in toolbox")

    MAIN.write_text(text, encoding="utf-8")
    print("build155 home navigation contract restored")


if __name__ == "__main__":
    apply()
