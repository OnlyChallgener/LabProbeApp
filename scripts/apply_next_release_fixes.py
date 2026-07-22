#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
ROUTER_STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"
WOL_PANEL = ROOT / "app/src/main/kotlin/com/labprobe/app/WolManagementPanel.kt"
NATIVE_UI = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing v0.10.13 patch pattern: {label}")
    return text.replace(old, new, 1)


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '                        "tool_router_diag" -> RouterDiagnosticScreen(prefs, backFromTool)\n',
        '                        "tool_router_diag" -> RouterDiagnosticScreen(prefs, backFromTool)\n'
        '                        "tool_router_nat" -> RouterNatDiagnosticScreen(prefs, backFromTool)\n'
        '                        "tool_router_beta" -> RouterBetaUpgradeScreen(prefs, backFromTool)\n',
        "router native tool routes",
    )

    old_ddns = '''                        HealthMiniCard(
                            title = "VPN / STUN",
                            value = "${vpnRows.size}",
                            unit = "条",
                            icon = Icons.Rounded.VpnKey,
                            accent = Color(0xFF7C3AED),
                            subtitle = vpnRows.firstOrNull()?.first ?: "暂无地址",
                            modifier = Modifier.weight(1f).clickable { onNavigate("tool_router_ddns") }
                        )'''
    new_ddns = '''                        HomeDdnsMiniCard(
                            prefs = prefs,
                            onClick = { onNavigate("tool_router_ddns") },
                            modifier = Modifier.weight(1f)
                        )'''
    text = replace_once(text, old_ddns, new_ddns, "home DDNS card")

    text = replace_once(
        text,
        '                HealthShortcutTile(Icons.Rounded.VpnKey, "VPN", if (vpnOk) "已记录" else "无数据", if (vpnOk) LabV2.Purple else LabV2.InkMuted, Modifier.weight(1f)) { onNavigate("events") }\n',
        '                HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", LabV2.Purple, Modifier.weight(1f)) { onNavigate("tool_ssh") }\n',
        "home SSH shortcut",
    )

    text = replace_once(
        text,
        'fun DailyScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("每日总结", "实时聚合，最近 7 天", onBack) {\n',
        'fun DailyScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("每日总结", "实时聚合，最近 7 天", onBack, compactHeader = true) {\n',
        "compact daily title",
    )

    text = replace_once(
        text,
        '''fun DailySection(title: String, items: JSONArray, icon: ImageVector, accent: Color, kind: String) {
    if (items.length() <= 0) return
    ExpressiveCard(title, "${items.length()} 条", icon, accent) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {''',
        '''fun DailySection(title: String, items: JSONArray, icon: ImageVector, accent: Color, kind: String) {
    if (items.length() <= 0) return
    ExpressiveCard(title, "${items.length()} 条", icon, accent) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {''',
        "daily terminal spacing",
    )
    text = text.replace(
        '                if (i < items.length() - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))',
        '                if (i < items.length() - 1) HorizontalDivider(Modifier.padding(vertical = 1.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))',
        1,
    )
    text = text.replace(
        '    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {',
        '    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {',
        1,
    )

    text = text.replace(
        '"v0.10.12 build142 · 路由器原生状态与设置重构" to listOf(',
        '"v0.10.13 build143 · 路由诊断与首页联动" to listOf(',
        1,
    )
    text = text.replace(
        '            "路由器状态接入 eWeb WebSocket，实时显示 CPU、内存、温度、运行时间、流量和连接数",\n'
        '            "大 VPN 卡改为路由设置，路由器配置功能集中到独立二级页面",\n'
        '            "修复 DDNS 卡片与更多菜单闪退，异常状态不再整卡标红",\n'
        '            "网络自检改为手动触发说明，合并重复网线结果并补充中文显示"',
        '            "路由器状态页改为约2秒读取Hub内存中的WSS快照，实时速率和连接数更快更新",\n'
        '            "首页快捷卡调整为SSH与DDNS，路由设置总入口继续保留",\n'
        '            "新增路由器原生RFC3489/RFC5780 NAT诊断与最近10次历史",\n'
        '            "新增ReyeeOS Beta在线检查，暂不猜测安装参数"',
        1,
    )

    MAIN.write_text(text, encoding="utf-8")


def patch_router_status() -> None:
    text = ROUTER_STATUS.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '            delay(if (state.mqttConnected) 15_000L else 20_000L)\n',
        '            delay(2_000L)\n',
        "router status 2 second refresh",
    )
    ROUTER_STATUS.write_text(text, encoding="utf-8")


def patch_wol_title() -> None:
    text = WOL_PANEL.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '                    Text("WOL 设备", fontSize = 16.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)\n',
        '                    Text("WOL 设备", fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)\n',
        "smaller WOL title",
    )
    WOL_PANEL.write_text(text, encoding="utf-8")


def patch_native_ui() -> None:
    text = NATIVE_UI.read_text(encoding="utf-8")
    text = replace_once(
        text,
        'import androidx.compose.runtime.*\n',
        'import androidx.compose.runtime.*\nimport androidx.compose.runtime.saveable.rememberSaveable\n',
        "rememberSaveable import",
    )
    NATIVE_UI.write_text(text, encoding="utf-8")


def apply() -> None:
    patch_main()
    patch_router_status()
    patch_wol_title()
    patch_native_ui()
    print("v0.10.13 fixes applied")


if __name__ == "__main__":
    apply()
