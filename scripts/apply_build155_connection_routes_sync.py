#!/usr/bin/env python3
"""Final build155 APP connection, routing and terminal-state fixes.

This patch runs after the build154 realtime migration. It restores the real
router settings/navigation contract from router-control-v01011-build141 and
keeps startup/full-sync work from delaying the Hub-native WebSocket.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build155 patch pattern: {label}")
    return text.replace(old, new, 1)


def apply_routes(text: str) -> str:
    text = replace_once(
        text,
        '            route == "router_status" -> "home"\n',
        '            route == "router_status" -> "home"\n            route == "router_settings" -> "home"\n',
        "router settings normalized home route",
    )
    text = replace_once(
        text,
        '            if (target.startsWith("tool_")) toolReturnRoute = if (route in mainRoutes) route else normalized\n',
        '            if (target.startsWith("tool_")) toolReturnRoute = when {\n'
        '                route == "router_settings" -> "router_settings"\n'
        '                route in mainRoutes -> route\n'
        '                else -> normalized\n'
        '            }\n',
        "router settings tool return route",
    )
    text = replace_once(
        text,
        '        BackHandler(route.startsWith("tool_") || route == "daily" || route == "health_score" || route == "router_status" || route == "wol" || route == "device_traffic" || route == "device_detail" || route == "settings") {\n',
        '        BackHandler(route.startsWith("tool_") || route == "daily" || route == "health_score" || route == "router_status" || route == "router_settings" || route == "wol" || route == "device_traffic" || route == "device_detail" || route == "settings") {\n',
        "router settings back handler",
    )
    text = replace_once(
        text,
        '                "router_status" -> "home"\n',
        '                "router_status" -> "home"\n                "router_settings" -> "home"\n',
        "router settings back destination",
    )
    text = replace_once(
        text,
        '                        "router_status" -> RouterStatusScreen(prefs, state, onBack = { route = "home" }, onOpenDevices = { route = "devices" })\n',
        '                        "router_status" -> RouterStatusScreen(prefs, state, onBack = { route = "home" }, onOpenDevices = { route = "devices" })\n'
        '                        "router_settings" -> RouterSettingsScreen(prefs, onBack = { route = "home" }) { target -> navigate(target) }\n',
        "real router settings screen route",
    )
    text = replace_once(
        text,
        '                        "tool_router_diag" -> RouterDiagnosticScreen(prefs, backFromTool)\n',
        '                        "tool_router_diag" -> RouterDiagnosticScreen(prefs, backFromTool)\n'
        '                        "tool_router_nat" -> RouterNatDiagnosticScreen(prefs, backFromTool)\n'
        '                        "tool_router_beta" -> RouterBetaUpgradeScreen(prefs, backFromTool)\n',
        "router NAT and beta routes",
    )
    return text


def apply_connection(text: str) -> str:
    text = replace_once(
        text,
        '''                    HubRealtimeState.Connected -> {
                        mqttConnected = true
                        message = "实时同步正常"
                    }''',
        '''                    HubRealtimeState.Connected -> {
                        mqttConnected = true
                        hubConnected = true
                        message = "实时同步正常"
                    }''',
        "WSS connected marks Hub connected",
    )
    text = replace_once(
        text,
        '''                    HubRealtimeState.Connecting -> {
                        mqttConnected = false
                        message = "正在连接实时同步"
                    }''',
        '''                    HubRealtimeState.Connecting -> {
                        mqttConnected = false
                        if (!hubConnected) message = "正在连接 Hub"
                    }''',
        "non-disruptive initial WSS state",
    )
    text = replace_once(
        text,
        '''                    is HubRealtimeState.Reconnecting -> {
                        mqttConnected = false
                        message = "正在重连 ${next.attempt}/${next.maxAttempts}"
                    }''',
        '''                    is HubRealtimeState.Reconnecting -> {
                        mqttConnected = false
                        // Keep the last valid UI frame. A short transport retry must not
                        // replace the whole APP status with a scary reconnect banner.
                        if (!hubConnected) message = "正在连接 Hub"
                    }''',
        "hide transient reconnect banner",
    )
    text = text.replace(
        '                if (!foregroundActive || !mqttConnected) return@launch\n',
        '                if (!foregroundActive) return@launch\n',
    )
    if text.count('if (!foregroundActive || !mqttConnected) return@launch'):
        raise RuntimeError("build155 still drops initial WSS frames")
    text = replace_once(
        text,
        '''        onRealtimeReady = { reconnect ->
            if (reconnect) stateScope.launch { calibrateRealtimeCache() }
        }''',
        '''        onRealtimeReady = { _ ->
            // Hub sends ready and the latest memory snapshots immediately. Calibrate
            // every open in parallel so a first connection cannot miss terminal data.
            stateScope.launch { calibrateRealtimeCache() }
        }''',
        "calibrate every WSS open",
    )
    text = replace_once(
        text,
        '''        CertificateReminderCenter.notifyDue(context, prefs)
        state.refreshAll(forceFull = true)
        state.startRealtime()
        delay(1500L)''',
        '''        CertificateReminderCenter.notifyDue(context, prefs)
        // Establish Hub-native WSS first. Full HTTP sync is independent and must
        // never delay the connected state or initial realtime snapshots.
        state.startRealtime()
        launch { state.refreshAll(forceFull = true) }
        delay(1500L)''',
        "WSS-first APP startup",
    )
    text = replace_once(
        text,
        '                            subtitle = if (watchedCount > 0) "关注 $watchedCount 台" else "等待同步",\n',
        '''                            subtitle = when {
                                watchedCount > 0 -> "关注 $watchedCount 台"
                                onlineCount > 0 -> "$onlineCount 台在线"
                                state.hubConnected -> "实时同步正常"
                                else -> "等待连接"
                            },
''',
        "terminal online subtitle reflects actual state",
    )
    return text


def apply_changelog(text: str) -> str:
    old = '''            "v$NAME build$CODE · 原生 fast 秒级稳定刷新" to listOf(
                "路由实时状态直接使用路由器原生 WSS type=fast，不再等待 Agent 更新",
                "APP 与 Hub 使用长连接、协议 Ping 和帧看门狗保活，异常最多 5 秒重连",
                "所有实时数字每秒最多更新一次，不再出现一秒内连续闪动",
                "HTTP 只读取 Hub 内存做首次校准，不参与自动实时兜底"
            )'''
    new = '''            "v$NAME build$CODE · 长连接启动与路由功能恢复" to listOf(
                "APP 启动优先建立 Hub 原生 WSS，完整 HTTP 同步不再阻塞连接",
                "放宽移动网络帧看门狗并保留最后有效数据，避免健康长连接被误判断线",
                "终端在线卡片按真实在线数和 Hub 状态显示，不再把零关注误写为等待同步",
                "恢复路由设置、映射与 UPnP、防火墙、DDNS、自检、NAT 与 Beta 的真实页面入口"
            )'''
    return replace_once(text, old, new, "build155 changelog")


def apply() -> None:
    text = MAIN.read_text(encoding="utf-8")
    text = apply_routes(text)
    text = apply_connection(text)
    text = apply_changelog(text)

    required = (
        'route == "router_settings" -> "home"',
        '"router_settings" -> RouterSettingsScreen',
        '"tool_router_nat" -> RouterNatDiagnosticScreen',
        '"tool_router_beta" -> RouterBetaUpgradeScreen',
        'state.startRealtime()\n        launch { state.refreshAll(forceFull = true) }',
        'if (!hubConnected) message = "正在连接 Hub"',
        'onlineCount > 0 -> "$onlineCount 台在线"',
        '"v$NAME build$CODE · 长连接启动与路由功能恢复"',
    )
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError(f"build155 MainActivity verification failed: {missing}")
    MAIN.write_text(text, encoding="utf-8")

    status = STATUS.read_text(encoding="utf-8")
    status = status.replace("实时链路正在自动重连", "实时数据暂时未变化，保留上次结果")
    if "实时链路正在自动重连" in status:
        raise RuntimeError("old reconnect warning remains")
    STATUS.write_text(status, encoding="utf-8")
    print("build155 connection, terminal sync and real router routes applied")


if __name__ == "__main__":
    apply()
