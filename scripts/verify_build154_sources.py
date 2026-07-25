#!/usr/bin/env python3
"""Verify final generated Android sources for APP v0.10.16 build158."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"
WSS = ROOT / "app/src/main/kotlin/com/labprobe/app/HubMqttClient.kt"
LITE = ROOT / "app/src/main/kotlin/com/labprobe/app/LiteRealtime.kt"
SMOOTH = ROOT / "app/src/main/kotlin/com/labprobe/app/RealtimeSmoothing.kt"
NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
ROUTER_API = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlApi.kt"
ROUTER_SETTINGS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt"
ROUTER_CONTROL = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
REPOSITORY = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterRepository.kt"
GRADLE = ROOT / "app/build.gradle.kts"
DIAGNOSTIC = Path("/tmp/labprobe-ci-error.txt")


def fail(message: str) -> None:
    DIAGNOSTIC.write_text(message, encoding="utf-8")
    raise SystemExit(message)


def require(path: Path, *needles: str) -> None:
    text = path.read_text(encoding="utf-8")
    missing = [needle for needle in needles if needle not in text]
    if missing:
        fail(f"missing {path.name}: {missing}")


def forbid(path: Path, *needles: str) -> None:
    text = path.read_text(encoding="utf-8")
    found = [needle for needle in needles if needle in text]
    if found:
        fail(f"forbidden {path.name}: {found}")


def section(path: Path, start: str, end: str) -> str:
    text = path.read_text(encoding="utf-8")
    begin = text.find(start)
    finish = text.find(end, begin + len(start))
    if begin < 0 or finish < 0:
        fail(f"missing section in {path.name}: {start}")
    return text[begin:finish]


def main() -> None:
    require(GRADLE, 'versionCode = 158', 'versionName = "0.10.16"')

    require(
        MAIN,
        'RouterRepositoryRegistry.get(prefs).start()',
        'RouterRepositoryRegistry.get(prefs).onRealtimeReady(reconnect)',
        '统一路由数据源与无感预加载',
        'realtimeClient.start(prefs.hub, prefs.token)',
        'private suspend fun calibrateRealtimeCache()',
        'onRouterRealtime = { raw ->',
        'onDevicesRealtime = { raw ->',
        'delay(RealtimeDisplaySmoother.FRAME_INTERVAL_MS)',
        '实时链路正常，完整数据同步暂时失败，已保留上次数据',
        'RouterSettingsHomeCard { onNavigate("router_settings") }',
        'HomeDdnsMiniCard(',
    )
    forbid(
        MAIN,
        'getMqttConfig()',
        'MqttAsyncClient',
        'message = "正在重连 ${next.attempt}/${next.maxAttempts}"',
    )

    require(
        WSS,
        'class HubRealtimeWebSocketClient',
        'const val REALTIME_PATH = "/api/realtime/ws"',
        'const val PING_INTERVAL_SECONDS = 10L',
        'const val SERVER_FRAME_TIMEOUT_MS = 20_000L',
        'webSocket.cancel()',
    )
    forbid(WSS, 'org.eclipse.paho', 'SERVER_FRAME_TIMEOUT_MS = 8_000L')

    require(
        LITE,
        '/api/router/realtime',
        '/api/devices/realtime',
        '.callTimeout(2_500, TimeUnit.MILLISECONDS)',
        'telemetry.put("temperature2gC"',
        'telemetry.put("temperature5gC"',
        'telemetry.put("storagePercent"',
    )
    require(
        SMOOTH,
        'const val FRAME_INTERVAL_MS = 1_000L',
        'const val STALE_WARNING_AGE_MS = 10_000L',
        'ROUTER_SAMPLE_WEIGHT = 0.72',
    )
    require(STATUS, '实时数据暂时未变化，已保留上次结果')
    forbid(STATUS, '等待 Agent 更新', '实时链路正在自动重连')

    require(
        REPOSITORY,
        'class RouterRepository',
        'data class RouterResource<T>',
        'delay(3_000L)',
        'fun onRealtimeReady(reconnect: Boolean)',
        'now - previous < 15_000L',
        'refreshStatus()',
        'refreshCapabilities()',
        'refreshDdns()',
        'refreshUpnp()',
        'refreshPortMappings()',
        'refreshFirewall()',
        'private suspend fun <T> coalesced',
        'private val mutationMutex = Mutex()',
        'if (sequence(key).get() != seq)',
        'if (_ddns.value.mutating) return',
        'if (_upnp.value.mutating) return',
        'if (_portMappings.value.mutating) return',
        'if (_firewall.value.mutating) return',
        'RouterRepositoryRegistry',
    )

    require(
        ROUTER_SETTINGS,
        'repository.status.collectAsState()',
        'repository.capabilities.collectAsState()',
        '已预加载配置快照',
        'APP 已在后台预加载',
        '控制数据正在静默同步，实时 WSS 不受影响',
    )
    settings_section = section(ROUTER_SETTINGS, 'fun RouterSettingsScreen', 'private fun RouterSettingsConnectionCard')
    if 'LaunchedEffect(' in settings_section or 'RouterControlApi(' in settings_section:
        fail('RouterSettingsScreen still owns a network request')
    if 'CircularProgressIndicator' in section(ROUTER_SETTINGS, 'private fun RouterSettingsConnectionCard', 'private fun RouterSettingsSection'):
        fail('router settings connection card still exposes a reconnect spinner')

    require(
        ROUTER_CONTROL,
        'repository.portMappings.collectAsState()',
        'repository.upnp.collectAsState()',
        'repository.firewall.collectAsState()',
        'repository.ddns.collectAsState()',
        'whole card must never turn red',
        'repository.status.collectAsState()',
    )
    forbid(ROUTER_CONTROL, 'Hub 已断开，正在自动重连')

    require(
        ROUTER_API,
        'val sessionConnected: Boolean',
        'val dataAvailable: Boolean',
        'Only /status owns connection semantics',
        '路由器会话正常，控制数据正在同步',
    )
    execute = section(ROUTER_API, 'private fun execute(', 'private suspend fun get(')
    if 'RouterConnectionStore.markSuccess()' in execute or 'RouterConnectionStore.markFailure' in execute:
        fail('individual router endpoint still mutates global connection state')

    require(
        NATIVE,
        'repository.ddns.collectAsState()',
        '正在检测，快照继续显示',
        '显示上次检查快照',
        'private const val ROUTER_NAT_HISTORY_LIMIT = 5',
        'fontSize = 13.sp',
        'terminalFromPreviousRun',
    )
    forbid(
        NATIVE,
        'Text("取消", fontWeight = FontWeight.Black)',
        'api.cancelNat()',
        'LaunchedEffect(Unit) { check() }',
    )
    beta = section(NATIVE, 'fun RouterBetaUpgradeScreen', 'private fun NativeCard')
    if 'CircularProgressIndicator' in beta:
        fail('Beta button still replaces its snapshot text with a spinner')

    DIAGNOSTIC.unlink(missing_ok=True)
    print('build158 WSS-first repository, mutation priority, silent preload and UI states verified')


if __name__ == '__main__':
    main()
