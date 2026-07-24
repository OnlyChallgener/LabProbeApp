#!/usr/bin/env python3
"""Make compact WSS deltas the only automatic realtime APP data path."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"

FIELDS_152 = '''    private val liteRealtimeApi = LiteRealtimeApi(prefs)
    private val realtimeSmoother = RealtimeDisplaySmoother()
    private var liteRouterJob: Job? = null
    private var liteDevicesJob: Job? = null
    private var liteRenderJob: Job? = null
    private var liteRouterLastSuccessAt = 0L
    private var liteDevicesLastSuccessAt = 0L
    private var liteRouterFailureCount = 0
'''

FIELDS_WSS = '''    private val liteRealtimeApi = LiteRealtimeApi(prefs)
    private val realtimeSmoother = RealtimeDisplaySmoother()
    private var liteRenderJob: Job? = null
'''

CALLBACKS = '''        onRevision = { revision -> mqttRevisionSignals.trySend(revision) },
        onRouterRealtime = { raw ->
            stateScope.launch {
                if (!foregroundActive || !mqttConnected) return@launch
                runCatching { JSONObject(raw) }.getOrNull()?.let {
                    realtimeSmoother.acceptRouter(it)
                    routerDashboardError = ""
                }
            }
        },
        onDevicesRealtime = { raw ->
            stateScope.launch {
                if (!foregroundActive || !mqttConnected) return@launch
                runCatching { JSONObject(raw) }.getOrNull()?.let { realtimeSmoother.acceptDevices(it) }
            }
        },
        onRealtimeReady = { reconnect ->
            if (reconnect) stateScope.launch { calibrateRealtimeCache() }
        },
'''

METHODS = '''    private suspend fun calibrateRealtimeCache() {
        if (!foregroundActive || prefs.hub.isBlank() || prefs.token.isBlank()) return
        supervisorScope {
            val router = async { runCatching { liteRealtimeApi.router() }.getOrNull() }
            val devices = async { runCatching { liteRealtimeApi.devices() }.getOrNull() }
            val routerSample = router.await()
            val devicesSample = devices.await()
            routerSample?.let {
                realtimeSmoother.acceptRouter(it)
                routerDashboardError = ""
            }
            devicesSample?.let { realtimeSmoother.acceptDevices(it) }
            val now = SystemClock.elapsedRealtime()
            realtimeSmoother.renderRouter(routerDashboard, now)?.let { routerDashboard = it }
            val nextOnline = realtimeSmoother.renderDevices(onlineDevices, now)
            if (nextOnline !== onlineDevices) onlineDevices = nextOnline
            val nextDevices = realtimeSmoother.renderDevices(devices, now)
            if (nextDevices !== devices) devices = nextDevices
        }
    }

    private fun startRealtimeRendering() {
        if (liteRenderJob?.isActive != true) {
            liteRenderJob = stateScope.launch {
                while (isActive) {
                    if (foregroundActive && mqttConnected) {
                        val now = SystemClock.elapsedRealtime()
                        realtimeSmoother.renderRouter(routerDashboard, now)?.let { routerDashboard = it }
                        val nextOnline = realtimeSmoother.renderDevices(onlineDevices, now)
                        if (nextOnline !== onlineDevices) onlineDevices = nextOnline
                        val nextDevices = realtimeSmoother.renderDevices(devices, now)
                        if (nextDevices !== devices) devices = nextDevices
                        delay(RealtimeDisplaySmoother.FRAME_INTERVAL_MS)
                    } else {
                        realtimeSmoother.pause()
                        delay(1_000L)
                    }
                }
            }
        }
    }

    private fun pauseRealtimeRendering() {
        liteRenderJob?.cancel()
        liteRenderJob = null
        realtimeSmoother.pause()
    }'''

START_REALTIME = '''    suspend fun startRealtime() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        calibrateRealtimeCache()
        startRealtimeRendering()
        val stored = prefs.mqttConfig
        val remote = runCatching { HubApi(prefs).getMqttConfig() }.getOrElse { stored }
        val effective = remote.copy(
            publicUrl = prefs.mqttUrlOverride.ifBlank { remote.publicUrl },
            dashboardTopic = ""
        )
        prefs.mqttConfig = effective
        realtimeClient.start(effective)
    }'''

STOP_REALTIME = '''    fun stopRealtime() {
        realtimeClient.stop()
        pauseRealtimeRendering()
        mqttConnected = false
    }'''

AUTO_HTTP_FALLBACK = '''    LaunchedEffect(appForeground, state.mqttConnected) {
        while (true) {
            delay(30_000L)
            if (appForeground && !state.mqttConnected && !state.loading) state.refreshAll(silent = true)
        }
    }
'''


def matching_brace(text: str, opening: int) -> int:
    depth = 0
    quote = ""
    escaped = False
    for index in range(opening, len(text)):
        char = text[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
        elif char in ('"', "'"):
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError("unterminated Kotlin block")


def replace_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing Kotlin function: {signature.strip()}")
    opening = text.find("{", start)
    closing = matching_brace(text, opening)
    return text[:start] + replacement + text[closing + 1:]


def apply() -> None:
    text = MAIN.read_text(encoding="utf-8")
    if "private suspend fun calibrateRealtimeCache()" in text and "onRouterRealtime = { raw ->" in text:
        forbidden = (
            "private fun startLiteRealtimePolling()",
            "liteRouterSessionActive(",
            "liteDisconnectedRetryDelay(",
            "LaunchedEffect(appForeground, state.mqttConnected)",
            "HTTP 兜底",
        )
        present = [value for value in forbidden if value in text]
        if present:
            raise RuntimeError(f"automatic HTTP realtime fallback remains: {present}")
        required = (
            'dashboardTopic = ""',
            '${AppVersion.CHANGELOG.firstOrNull()?.first.orEmpty()}',
            '"v$NAME build$CODE · 实时连接租约与离线节流"',
            "foregroundActive && mqttConnected",
        )
        missing = [value for value in required if value not in text]
        if missing:
            raise RuntimeError(f"single-WSS APP verification failed: {missing}")
        print("single-WSS APP realtime path already applied")
        return

    if FIELDS_152 not in text:
        raise RuntimeError("missing build152 realtime fields")
    text = text.replace(FIELDS_152, FIELDS_WSS, 1)

    revision_callback = "        onRevision = { revision -> mqttRevisionSignals.trySend(revision) }\n"
    if revision_callback not in text:
        raise RuntimeError("missing MQTT revision callback")
    text = text.replace(revision_callback, CALLBACKS, 1)

    methods_start = text.find("    private fun liteRouterSessionActive(")
    start_realtime = text.find("    suspend fun startRealtime() {", methods_start)
    if methods_start < 0 or start_realtime < 0:
        raise RuntimeError("missing build152 HTTP realtime methods")
    text = text[:methods_start] + METHODS + "\n\n" + text[start_realtime:]
    text = replace_function(text, "    suspend fun startRealtime() {", START_REALTIME)
    text = replace_function(text, "    fun stopRealtime() {", STOP_REALTIME)

    if "import kotlinx.coroutines.supervisorScope\n" not in text:
        text = text.replace(
            "import kotlinx.coroutines.coroutineScope\n",
            "import kotlinx.coroutines.coroutineScope\nimport kotlinx.coroutines.supervisorScope\n",
            1,
        )
    text = text.replace(AUTO_HTTP_FALLBACK, "", 1)

    parser_anchor = "            dashboardTopic = dashboardTopic\n"
    parser_replacement = '''            dashboardTopic = dashboardTopic,
            routerRealtimeTopic = root.optString("routerRealtimeTopic"),
            devicesRealtimeTopic = root.optString("devicesRealtimeTopic"),
            realtimeDemandTopic = root.optString("realtimeDemandTopic"),
'''
    if "routerRealtimeTopic = root.optString" not in text:
        if parser_anchor not in text:
            raise RuntimeError("missing Hub MQTT config parser")
        text = text.replace(parser_anchor, parser_replacement, 1)

    forbidden = (
        "private fun startLiteRealtimePolling()",
        "liteRouterSessionActive(",
        "liteDisconnectedRetryDelay(",
        "LaunchedEffect(appForeground, state.mqttConnected)",
    )
    present = [value for value in forbidden if value in text]
    if present:
        raise RuntimeError(f"automatic HTTP realtime fallback remains: {present}")
    required = (
        "onRouterRealtime = { raw ->",
        "onDevicesRealtime = { raw ->",
        "onRealtimeReady = { reconnect ->",
        "private suspend fun calibrateRealtimeCache()",
        "foregroundActive && mqttConnected",
        'dashboardTopic = ""',
        '${AppVersion.CHANGELOG.firstOrNull()?.first.orEmpty()}',
        '"v$NAME build$CODE · 实时连接租约与离线节流"',
        'routerRealtimeTopic = root.optString("routerRealtimeTopic")',
    )
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError(f"single-WSS APP verification failed: {missing}")
    MAIN.write_text(text, encoding="utf-8")
    print("single-WSS APP realtime path applied")


if __name__ == "__main__":
    apply()
