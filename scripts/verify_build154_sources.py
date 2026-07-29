#!/usr/bin/env python3
"""Verify final generated Android sources for APP v0.10.18 build160."""
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
TASKS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterTaskRepository.kt"
PORTMAP = ROOT / "app/src/main/kotlin/com/labprobe/app/PortMapping.kt"
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
    require(GRADLE, 'versionCode = 160', 'versionName = "0.10.18"')

    require(
        MAIN,
        'RouterRepositoryRegistry.get(prefs).start()',
        'RouterRepositoryRegistry.get(prefs).onRealtimeReady(reconnect)',
        '状态闭环与后台任务',
        'realtimeClient.start(prefs.hub, prefs.token)',
        'private suspend fun calibrateRealtimeCache()',
        'onRouterRealtime = { raw ->',
        'onDevicesRealtime = { raw ->',
        'onTaskUpdate = { raw -> RouterTaskRepositoryRegistry.get(prefs).acceptRealtime(raw) }',
        'realtimeDataFresh by mutableStateOf(false)',
        'lastRouterRealtimeAt by mutableLongStateOf(0L)',
        '实时链路已连接，等待首帧数据',
        '实时链路恢复中，已保留上次数据',
        'delay(RealtimeDisplaySmoother.FRAME_INTERVAL_MS)',
        'RouterSettingsHomeCard { onNavigate("router_settings") }',
        'HomeDdnsMiniCard(',
        '.readTimeout(45, TimeUnit.SECONDS)',
    )
    forbid(
        MAIN,
        'getMqttConfig()',
        'MqttAsyncClient',
        'message = "正在重连 ${next.attempt}/${next.maxAttempts}"',
        'state.hubConnected -> "实时同步正常"',
        'HubRealtimeState.Connected -> "实时同步正常"',
    )

    require(
        WSS,
        'class HubRealtimeWebSocketClient',
        'const val REALTIME_PATH = "/api/realtime/ws"',
        'const val PING_INTERVAL_SECONDS = 10L',
        'const val SERVER_FRAME_TIMEOUT_MS = 45_000L',
        'onTaskUpdate: (String) -> Unit',
        '"task" -> if (data != null) onTaskUpdate(data.toString())',
        '"ready" -> if (!readyReceived)',
        'webSocket.cancel()',
    )
    forbid(
        WSS,
        'org.eclipse.paho',
        'SERVER_FRAME_TIMEOUT_MS = 8_000L',
        'SERVER_FRAME_TIMEOUT_MS = 20_000L',
        'onState(HubRealtimeState.Connected)\n                    onRealtimeReady(reconnect)',
    )

    require(
        TASKS,
        'class RouterTaskRepository',
        'data class RouterTaskSnapshot',
        'val nat: StateFlow<RouterTaskSnapshot>',
        'val diagnostic: StateFlow<RouterTaskSnapshot>',
        'val beta: StateFlow<RouterTaskSnapshot>',
        '/api/router/tasks/$kind',
        'fun acceptRealtime(raw: String)',
        'private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)',
        'taskMessageZh',
        'taskErrorZh',
    )

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
        'private suspend fun <T> coalesced',
        'private val mutationMutex = Mutex()',
        'if (sequence(key).get() != seq)',
        'if (_ddns.value.mutating) return',
        'if (_upnp.value.mutating) return',
        'if (_portMappings.value.mutating) return',
        'if (_firewall.value.mutating) return',
        'val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)',
        'private suspend fun <T> executeCommand',
        'withTimeout(45_000L)',
        'catch (cancelled: CancellationException)',
        'executeCommand { api.setUpnp(enabled, wan) }',
    )
    forbid(REPOSITORY, 'withTimeout(12_000L)', 'withTimeout(25_000L)')

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
        '设置正在后台应用，页面可以安全退出',
        'repository.refreshPortMappings(false)',
        'repository.refreshUpnp(false)',
        'repository.refreshFirewall(false)',
        'repository.refreshDdns(false)',
        'RouterTaskRepositoryRegistry.get(prefs)',
        '检测已由 Hub 接管，可以安全离开页面',
        'tasks.startDiagnostic()',
    )
    forbid(
        ROUTER_CONTROL,
        'Hub 已断开，正在自动重连',
        'repository.refreshPortMappings(true)',
        'repository.refreshUpnp(true)',
        'repository.refreshFirewall(true)',
        'repository.refreshDdns(true)',
        'while(running)',
        'api.startDiagnostic()',
    )

    require(
        ROUTER_API,
        'val sessionConnected: Boolean',
        'val dataAvailable: Boolean',
        'Only /status owns connection semantics',
        '路由器会话正常，控制数据正在同步',
        'internal fun parseDiagnostic',
    )

    require(
        NATIVE,
        'repository.ddns.collectAsState()',
        'private const val ROUTER_NAT_HISTORY_LIMIT = 5',
        'fontSize = 12.5.sp',
        'modifier = Modifier.fillMaxWidth().height(44.dp).nativeBlueShadow',
        'RouterTaskRepositoryRegistry.get(prefs)',
        'tasks.startNat(server, port, interfaceName, mode)',
        'result.stageText.ifBlank { "检测中" }',
        'NativeValueRow("已耗时"',
        '显示上次检查快照 · 仅手动检测',
        'tasks.startBeta()',
    )
    forbid(
        NATIVE,
        'Text("取消", fontWeight = FontWeight.Black)',
        'api.cancelNat()',
        'LaunchedEffect(Unit) { check() }',
        'Modifier.width(158.dp).height(44.dp).nativeBlueShadow',
        'while (running && isActive)',
        'api.betaInfo()',
    )

    require(
        PORTMAP,
        'private object PortMappingMemoryCache',
        'mutableStateOf(PortMappingMemoryCache.rules)',
        'PortMappingMemoryCache.agent',
        'refresh(silent = rules.isNotEmpty() || PortMappingMemoryCache.agent != null)',
    )
    forbid(PORTMAP, 'while (true) {\n            delay(10_000)')

    DIAGNOSTIC.unlink(missing_ok=True)
    print('build160 truthful realtime state, Hub-owned task lifecycle and snapshot-preserving pages verified')


if __name__ == '__main__':
    main()
