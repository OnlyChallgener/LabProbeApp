#!/usr/bin/env python3
"""Final build152 APP-Hub connection gate.

A successful router realtime response is the connection lease. When that lease
expires, the APP stops device polling and 200ms smoothing/render work, retaining
only one low-frequency router probe with bounded backoff. This prevents offline
background cache churn while allowing automatic recovery.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"

VERSION_BLOCK = '''object AppVersion {
    val NAME: String get() = BuildConfig.VERSION_NAME
    val CODE: Int get() = BuildConfig.VERSION_CODE
    const val GITHUB = "https://github.com/OnlyChallgener/LabProbeApp"
    val CHANGELOG: List<Pair<String, List<String>>>
        get() = listOf(
            "v$NAME build$CODE · 实时连接租约与离线节流" to listOf(
                "轻量路由接口成功响应作为 APP 与 Hub 的实时连接租约，租约有效期 5 秒",
                "连接中断后停止终端实时请求、200ms 平滑渲染和缓存计算",
                "断线期间仅保留一个路由探测，并按 3/5/10/15 秒逐级退避",
                "连接恢复后自动恢复每秒真实样本和短时平滑显示"
            )
        )
}'''

FIELDS_151 = '''    private val liteRealtimeApi = LiteRealtimeApi(prefs)
    private val realtimeSmoother = RealtimeDisplaySmoother()
    private var liteRouterJob: Job? = null
    private var liteDevicesJob: Job? = null
    private var liteRenderJob: Job? = null
'''

FIELDS_152 = '''    private val liteRealtimeApi = LiteRealtimeApi(prefs)
    private val realtimeSmoother = RealtimeDisplaySmoother()
    private var liteRouterJob: Job? = null
    private var liteDevicesJob: Job? = null
    private var liteRenderJob: Job? = null
    private var liteRouterLastSuccessAt = 0L
    private var liteDevicesLastSuccessAt = 0L
    private var liteRouterFailureCount = 0
'''

METHODS = '''    private fun liteRouterSessionActive(now: Long = SystemClock.elapsedRealtime()): Boolean =
        foregroundActive && liteRouterLastSuccessAt > 0L && now - liteRouterLastSuccessAt <= 5_000L

    private fun liteDevicesSessionActive(now: Long = SystemClock.elapsedRealtime()): Boolean =
        liteRouterSessionActive(now) && liteDevicesLastSuccessAt > 0L && now - liteDevicesLastSuccessAt <= 5_000L

    private fun liteDisconnectedRetryDelay(failures: Int): Long = when {
        failures <= 1 -> 3_000L
        failures == 2 -> 5_000L
        failures == 3 -> 10_000L
        else -> 15_000L
    }

    private fun startLiteRealtimePolling() {
        if (liteRouterJob?.isActive != true) {
            liteRouterJob = stateScope.launch {
                while (isActive) {
                    val started = SystemClock.elapsedRealtime()
                    val latest = runCatching { liteRealtimeApi.router() }.getOrNull()
                    if (latest != null) {
                        realtimeSmoother.acceptRouter(latest)
                        liteRouterLastSuccessAt = SystemClock.elapsedRealtime()
                        liteRouterFailureCount = 0
                        routerDashboardError = ""
                    } else {
                        liteRouterFailureCount = (liteRouterFailureCount + 1).coerceAtMost(10)
                        if (!liteRouterSessionActive()) {
                            liteDevicesLastSuccessAt = 0L
                            realtimeSmoother.pause()
                        }
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val waitMs = if (latest == null) {
                        liteDisconnectedRetryDelay(liteRouterFailureCount)
                    } else {
                        (1_000L - elapsed).coerceAtLeast(100L)
                    }
                    delay(waitMs)
                }
            }
        }
        if (liteDevicesJob?.isActive != true) {
            liteDevicesJob = stateScope.launch {
                while (isActive) {
                    if (!liteRouterSessionActive()) {
                        delay(1_000L)
                        continue
                    }
                    val started = SystemClock.elapsedRealtime()
                    val latest = runCatching { liteRealtimeApi.devices() }.getOrNull()
                    if (latest != null && liteRouterSessionActive()) {
                        realtimeSmoother.acceptDevices(latest)
                        liteDevicesLastSuccessAt = SystemClock.elapsedRealtime()
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    delay(if (latest == null) 3_000L else (1_000L - elapsed).coerceAtLeast(100L))
                }
            }
        }
        if (liteRenderJob?.isActive != true) {
            liteRenderJob = stateScope.launch {
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    val routerActive = liteRouterSessionActive(now)
                    val devicesActive = liteDevicesSessionActive(now)
                    if (routerActive) {
                        realtimeSmoother.renderRouter(routerDashboard, now)?.let { routerDashboard = it }
                    }
                    if (devicesActive) {
                        val nextOnline = realtimeSmoother.renderDevices(onlineDevices, now)
                        if (nextOnline !== onlineDevices) onlineDevices = nextOnline
                        val nextDevices = realtimeSmoother.renderDevices(devices, now)
                        if (nextDevices !== devices) devices = nextDevices
                    }
                    delay(if (routerActive || devicesActive) RealtimeDisplaySmoother.FRAME_INTERVAL_MS else 1_000L)
                }
            }
        }
    }'''

STOP_METHOD = '''    fun stopRealtime() {
        realtimeClient.stop()
        liteRouterJob?.cancel()
        liteRouterJob = null
        liteDevicesJob?.cancel()
        liteDevicesJob = null
        liteRenderJob?.cancel()
        liteRenderJob = null
        liteRouterLastSuccessAt = 0L
        liteDevicesLastSuccessAt = 0L
        liteRouterFailureCount = 0
        realtimeSmoother.pause()
        mqttConnected = false
    }'''


def matching_brace(text: str, opening: int) -> int:
    if opening < 0 or text[opening] != "{":
        raise RuntimeError("invalid Kotlin brace start")
    depth = 0
    quote = ""
    escaped = False
    index = opening
    while index < len(text):
        ch = text[index]
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = ""
        else:
            if ch in ('"', "'"):
                quote = ch
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return index
        index += 1
    raise RuntimeError("unterminated Kotlin brace block")


def replace_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing Kotlin function: {signature.strip()}")
    opening = text.find("{", start + len(signature) - 1)
    closing = matching_brace(text, opening)
    return text[:start] + replacement + text[closing + 1:]


def apply() -> None:
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

    if FIELDS_152 not in text:
        if FIELDS_151 not in text:
            raise RuntimeError("missing build151 realtime fields")
        text = text.replace(FIELDS_151, FIELDS_152, 1)

    text = replace_function(text, "    private fun startLiteRealtimePolling() {", METHODS)
    text = replace_function(text, "    fun stopRealtime() {", STOP_METHOD)

    required = (
        'liteRouterLastSuccessAt = SystemClock.elapsedRealtime()',
        'now - liteRouterLastSuccessAt <= 5_000L',
        'if (!liteRouterSessionActive())',
        'liteDisconnectedRetryDelay(liteRouterFailureCount)',
        'failures == 3 -> 10_000L',
        'else -> 15_000L',
        'realtimeSmoother.pause()',
        'latest != null && liteRouterSessionActive()',
        'delay(if (routerActive || devicesActive) RealtimeDisplaySmoother.FRAME_INTERVAL_MS else 1_000L)',
        '实时连接租约与离线节流',
    )
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError(f"build152 connection gate verification failed: {missing}")

    MAIN.write_text(text, encoding="utf-8")
    print("build152 realtime connection lease applied")


if __name__ == "__main__":
    apply()
