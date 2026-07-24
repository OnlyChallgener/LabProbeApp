#!/usr/bin/env python3
"""Verify final generated Android sources used by APP build156."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"
WSS = ROOT / "app/src/main/kotlin/com/labprobe/app/HubMqttClient.kt"
LITE = ROOT / "app/src/main/kotlin/com/labprobe/app/LiteRealtime.kt"
SMOOTH = ROOT / "app/src/main/kotlin/com/labprobe/app/RealtimeSmoothing.kt"
NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
GRADLE = ROOT / "app/build.gradle.kts"
DIAGNOSTIC = Path("/tmp/labprobe-ci-error.txt")


def fail(message: str) -> None:
    DIAGNOSTIC.write_text(message, encoding="utf-8")
    raise SystemExit(message)


def require(path: Path, needle: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        fail(f"missing {path.name}: {needle}")


def forbid(path: Path, needle: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle in text:
        fail(f"forbidden {path.name}: {needle}")


def section(path: Path, start: str, end: str) -> str:
    text = path.read_text(encoding="utf-8")
    begin = text.find(start)
    finish = text.find(end, begin + len(start))
    if begin < 0 or finish < 0:
        fail(f"missing section in {path.name}: {start}")
    return text[begin:finish]


def main() -> None:
    for needle in ('versionCode = 156', 'versionName = "0.10.15"'):
        require(GRADLE, needle)

    for needle in (
        '版本 ${AppVersion.NAME} build ${AppVersion.CODE}',
        '"v$NAME build$CODE · 路由字段与长连接完整修复"',
        'realtimeClient.start(prefs.hub, prefs.token)',
        'stateScope.launch { calibrateRealtimeCache() }',
        'onRouterRealtime = { raw ->',
        'onDevicesRealtime = { raw ->',
        'onRealtimeReady = { _ ->',
        'private suspend fun calibrateRealtimeCache()',
        'delay(RealtimeDisplaySmoother.FRAME_INTERVAL_MS)',
        'state.startRealtime()\n        launch { state.refreshAll(forceFull = true) }',
        'hubConnected = true',
        'onlineCount > 0 -> "$onlineCount 台在线"',
        'state.hubConnected -> "实时同步正常"',
    ):
        require(MAIN, needle)
    for needle in (
        'getMqttConfig()',
        'LaunchedEffect(appForeground, state.mqttConnected)',
        'if (!foregroundActive || !mqttConnected) return@launch',
        'message = "正在重连 ${next.attempt}/${next.maxAttempts}"',
        'subtitle = if (watchedCount > 0) "关注 $watchedCount 台" else "等待同步"',
    ):
        forbid(MAIN, needle)

    for needle in (
        'class HubRealtimeWebSocketClient',
        'const val REALTIME_PATH = "/api/realtime/ws"',
        'const val PING_INTERVAL_SECONDS = 10L',
        'const val WATCHDOG_INTERVAL_MS = 1_000L',
        'const val SERVER_FRAME_TIMEOUT_MS = 20_000L',
        'const val MAX_RETRY_ATTEMPT = 3',
        'webSocket.cancel()',
        'else -> 3_000L',
    ):
        require(WSS, needle)
    for needle in ('MqttAsyncClient', 'org.eclipse.paho', 'SERVER_FRAME_TIMEOUT_MS = 8_000L'):
        forbid(WSS, needle)

    for needle in (
        '/api/router/realtime',
        '/api/devices/realtime',
        '.callTimeout(2_500, TimeUnit.MILLISECONDS)',
        'if (!stale) root.put("online", true)',
        'sample.has("temperature2gC")',
        'telemetry.put("temperature2gC"',
        'sample.has("temperature5gC")',
        'telemetry.put("temperature5gC"',
        'sample.has("storagePercent")',
        'telemetry.put("storagePercent"',
    ):
        require(LITE, needle)

    for needle in (
        'const val FRAME_INTERVAL_MS = 1_000L',
        'const val STALE_WARNING_AGE_MS = 10_000L',
        'ROUTER_SAMPLE_WEIGHT = 0.72',
        'Each real sample can change the visible number',
    ):
        require(SMOOTH, needle)
    for needle in ('FRAME_INTERVAL_MS = 200L', 'TRANSITION_MS = 900L', 'kotlin.random'):
        forbid(SMOOTH, needle)

    forbid(STATUS, '等待 Agent 更新')
    forbid(STATUS, '实时链路正在自动重连')
    require(STATUS, '实时数据暂时未变化，保留上次结果')
    for needle in (
        'temperature2g = jsonNumber(telemetry, "temperature2gC")',
        'temperature5g = jsonNumber(telemetry, "temperature5gC")',
        'telemetry.has("storagePercent")',
    ):
        require(STATUS, needle)

    # Exact home navigation and real router settings contract restored from build141.
    for needle in (
        'RouterSettingsHomeCard { onNavigate("router_settings") }',
        'HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", LabV2.Purple, Modifier.weight(1f)) { onNavigate("tool_ssh") }',
        'HomeDdnsMiniCard(',
        'onClick = { onNavigate("tool_router_ddns") }',
        'route == "router_settings" -> "home"',
        '"router_settings" -> RouterSettingsScreen',
        '"tool_portmap" -> MappingAndUpnpScreen',
        '"tool_router_firewall" -> RouterFirewallScreen',
        '"tool_router_ddns" -> RouterDdnsScreen',
        '"tool_router_diag" -> RouterDiagnosticScreen',
        '"tool_router_nat" -> RouterNatDiagnosticScreen',
        '"tool_router_beta" -> RouterBetaUpgradeScreen',
        '"tool_router_login" -> RouterHubStatusScreen',
    ):
        require(MAIN, needle)
    tools = section(MAIN, 'fun ToolsHomeScreen', 'fun ReorderableToolSection')
    for obsolete in ('RouterFeatureRail(', 'var routerFirewallEnabled'):
        if obsolete in tools:
            fail(f"toolbox still contains router settings UI: {obsolete}")

    # Validate actual NAT behavior, not a removed private set constant.
    for needle in (
        '路由器原生 RFC3489 / RFC5780',
        'FilterChip(selected = mode == "classic"',
        'FilterChip(selected = mode == "5780"',
        'history.take(10)',
        'saveNatHistory(context, it)',
    ):
        require(NATIVE, needle)

    DIAGNOSTIC.unlink(missing_ok=True)
    print("build156 startup, WSS keepalive, terminal state, router fields and real routes verified")


if __name__ == "__main__":
    main()
