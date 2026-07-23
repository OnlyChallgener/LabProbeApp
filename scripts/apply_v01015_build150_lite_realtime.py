#!/usr/bin/env python3
"""Final build150 realtime architecture.

This runs after every legacy generator and deliberately removes the full
Dashboard MQTT path. MQTT remains for revision/availability only. Two
independent one-second HTTP loops read the small Hub memory APIs and update only
numeric fields, so router and device runtime cannot block each other.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
ROUTER_STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"
MQTT = ROOT / "app/src/main/kotlin/com/labprobe/app/HubMqttClient.kt"

VERSION_BLOCK = '''object AppVersion {
    val NAME: String get() = BuildConfig.VERSION_NAME
    val CODE: Int get() = BuildConfig.VERSION_CODE
    const val GITHUB = "https://github.com/OnlyChallgener/LabProbeApp"
    val CHANGELOG: List<Pair<String, List<String>>>
        get() = listOf(
            "v$NAME build$CODE · 轻量实时接口与终端刷新修复" to listOf(
                "路由实时数字改读 Hub WSS 内存轻量 API，不再传输完整 Dashboard",
                "终端网速与连接数使用独立轻量 API，每秒按 MAC 覆盖三个数字",
                "MQTT 只保留 revision 与在线状态，避免大 JSON 排队和主线程重组",
                "路由与终端实时请求互不等待，任一路径慢都不会拖累另一页"
            )
        )
}'''

LITE_FIELDS = '''    private val liteRealtimeApi = LiteRealtimeApi(prefs)
    private var liteRouterJob: Job? = null
    private var liteDevicesJob: Job? = null
'''

LITE_METHOD = '''    private fun startLiteRealtimePolling() {
        if (liteRouterJob?.isActive != true) {
            liteRouterJob = stateScope.launch {
                while (isActive) {
                    val started = SystemClock.elapsedRealtime()
                    val latest = runCatching { liteRealtimeApi.router() }.getOrNull()
                    if (latest != null) {
                        routerDashboard = mergeLiteRouterRealtime(routerDashboard, latest)
                        routerDashboardError = ""
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    delay(if (latest == null) 3_000L else (1_000L - elapsed).coerceAtLeast(100L))
                }
            }
        }
        if (liteDevicesJob?.isActive != true) {
            liteDevicesJob = stateScope.launch {
                while (isActive) {
                    val started = SystemClock.elapsedRealtime()
                    val latest = runCatching { liteRealtimeApi.devices() }.getOrNull()
                    if (latest != null) {
                        onlineDevices = mergeLiteDeviceRealtime(onlineDevices, latest)
                        devices = mergeLiteDeviceRealtime(devices, latest)
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    delay(if (latest == null) 3_000L else (1_000L - elapsed).coerceAtLeast(100L))
                }
            }
        }
    }

'''

ENTRY_EFFECT = '''    LaunchedEffect(Unit) {
        state.refreshRouterDashboard(silent = true)
        launch { runCatching { state.requestRouterCredentialsRefresh() } }
    }'''

REFRESH_FUNCTION = '''    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching { state.requestRouterDashboardRefresh() }
                .onFailure { state.refreshRouterDashboard(silent = false) }
            refreshing = false
            launch { runCatching { state.requestRouterCredentialsRefresh() } }
        }
    }'''


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")

    text, count = re.subn(
        r'object AppVersion \{.*?\n\}\n\nprivate val LabTypography:',
        VERSION_BLOCK + '\n\nprivate val LabTypography:',
        text,
        count=1,
        flags=re.DOTALL,
    )
    if count != 1:
        raise RuntimeError(f"expected AppVersion block, replaced {count}")

    # Remove the old full-dashboard callback added by build149.
    text = re.sub(
        r',\s*onRouterDashboard\s*=\s*\{\s*raw\s*->.*?\n\s*\}',
        '',
        text,
        count=1,
        flags=re.DOTALL,
    )
    text = re.sub(r'\s*@Volatile private var routerDashboardMqttAt = 0L\n', '\n', text)
    text = re.sub(r'\s*private val routerDashboardApi = HubApi\(prefs, realtimeTimeouts = true\)\n', '\n', text)
    text = re.sub(
        r'\s*fun routerDashboardMqttFresh\(.*?\n\s*\}\n',
        '\n',
        text,
        count=1,
        flags=re.DOTALL,
    )

    if "private val liteRealtimeApi = LiteRealtimeApi(prefs)" not in text:
        anchor = "    @Volatile private var foregroundActive = true\n"
        if anchor not in text:
            raise RuntimeError("missing foregroundActive anchor")
        text = text.replace(anchor, anchor + LITE_FIELDS, 1)

    if "private fun startLiteRealtimePolling()" not in text:
        marker = "    suspend fun startRealtime() {\n"
        if marker not in text:
            raise RuntimeError("missing startRealtime marker")
        text = text.replace(marker, LITE_METHOD + marker, 1)

    start_pattern = re.compile(
        r'    suspend fun startRealtime\(\) \{.*?\n    \}\n\n    suspend fun refreshRouterDashboard',
        re.DOTALL,
    )
    start_body = '''    suspend fun startRealtime() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        startLiteRealtimePolling()
        val stored = prefs.mqttConfig
        val remote = runCatching { HubApi(prefs).getMqttConfig() }.getOrElse { stored }
        val effective = remote.copy(
            publicUrl = prefs.mqttUrlOverride.ifBlank { remote.publicUrl },
            dashboardTopic = ""
        )
        prefs.mqttConfig = effective
        realtimeClient.start(effective)
    }

    suspend fun refreshRouterDashboard'''
    text, count = start_pattern.subn(start_body, text, count=1)
    if count != 1:
        raise RuntimeError(f"expected startRealtime function, replaced {count}")

    stop_pattern = re.compile(
        r'    fun stopRealtime\(\) \{.*?\n    \}',
        re.DOTALL,
    )
    stop_body = '''    fun stopRealtime() {
        realtimeClient.stop()
        liteRouterJob?.cancel()
        liteRouterJob = null
        liteDevicesJob?.cancel()
        liteDevicesJob = null
        mqttConnected = false
    }'''
    text, count = stop_pattern.subn(stop_body, text, count=1)
    if count != 1:
        raise RuntimeError(f"expected stopRealtime function, replaced {count}")

    # The short-timeout full-dashboard client from build149 is no longer used.
    text = text.replace('runCatching { routerDashboardApi.getRouterDashboard() }', 'runCatching { HubApi(prefs).getRouterDashboard() }')
    text = text.replace('val latest = runCatching { routerDashboardApi.getRouterDashboard() }.getOrNull()', 'val latest = runCatching { HubApi(prefs).getRouterDashboard() }.getOrNull()')

    forbidden = (
        'onRouterDashboard = { raw ->',
        'routerDashboardMqttFresh(',
        'private val routerDashboardApi',
        'routerDashboardMqttAt',
    )
    if any(item in text for item in forbidden):
        raise RuntimeError("full dashboard MQTT path remains in MainActivity")
    required = (
        'private val liteRealtimeApi = LiteRealtimeApi(prefs)',
        'mergeLiteRouterRealtime(routerDashboard, latest)',
        'mergeLiteDeviceRealtime(onlineDevices, latest)',
        'dashboardTopic = ""',
        '轻量实时接口与终端刷新修复',
    )
    missing = [item for item in required if item not in text]
    if missing:
        raise RuntimeError(f"build150 MainActivity verification failed: {missing}")
    MAIN.write_text(text, encoding="utf-8")


def patch_router_status() -> None:
    text = ROUTER_STATUS.read_text(encoding="utf-8")
    start = text.find("    LaunchedEffect(Unit) {", text.find("fun RouterStatusScreen"))
    end = text.find("\n\n    fun normalizedRouterUrl", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing RouterStatus launch block boundaries")
    text = text[:start] + ENTRY_EFFECT + text[end:]

    refresh_start = text.find("    fun refresh() {", start)
    refresh_end = text.find("\n\n    if (showRouterEditor)", refresh_start)
    if refresh_start < 0 or refresh_end < 0:
        raise RuntimeError("missing RouterStatus refresh boundaries")
    text = text[:refresh_start] + REFRESH_FUNCTION + text[refresh_end:]

    forbidden = (
        'delay(if (state.mqttConnected) 15_000L else 20_000L)',
        'routerDashboardMqttFresh()',
        'state.refreshRouterDashboard(silent = true)\n            delay(1_000L)',
    )
    if any(item in text for item in forbidden):
        raise RuntimeError("RouterStatus still owns a periodic full-dashboard loop")
    ROUTER_STATUS.write_text(text, encoding="utf-8")


def patch_mqtt() -> None:
    text = MQTT.read_text(encoding="utf-8")
    text = text.replace(
        '/** Foreground MQTT supervisor for revision, availability and router dashboard snapshots. */',
        '/** Foreground MQTT supervisor for revision and availability only. */',
    )
    text = re.sub(
        r'\n\s*activeConfig\.dashboardTopic to 1',
        '',
        text,
        count=1,
    )
    if 'activeConfig.dashboardTopic to 1' in text:
        raise RuntimeError("HubMqttClient still subscribes to full dashboard topic")
    MQTT.write_text(text, encoding="utf-8")


def apply() -> None:
    patch_main()
    patch_router_status()
    patch_mqtt()
    print("build150 lightweight router/device realtime architecture applied")


if __name__ == "__main__":
    apply()
