#!/usr/bin/env python3
"""Final build151 presentation smoothing applied after build150 architecture.

Real samples still come from the two small Hub APIs. This patch adds one
presentation loop that interpolates continuous values only; connection counts
and events remain exact. No prediction is performed after a real sample ages.
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
            "v$NAME build$CODE · 本地实时采样与缓存平滑显示" to listOf(
                "路由器由 LabRelay 本地采集真实样本，Hub 只保存小型内存快照",
                "网速、CPU、内存和温度在相邻真实样本之间做 900ms 平滑过渡",
                "终端上下行按 MAC 平滑显示，连接数仍立即使用真实整数",
                "样本超过安全年龄后停止动画并显示过期状态，不预测、不随机造数"
            )
        )
}'''

FIELDS_150 = '''    private val liteRealtimeApi = LiteRealtimeApi(prefs)
    private var liteRouterJob: Job? = null
    private var liteDevicesJob: Job? = null
'''

FIELDS_151 = '''    private val liteRealtimeApi = LiteRealtimeApi(prefs)
    private val realtimeSmoother = RealtimeDisplaySmoother()
    private var liteRouterJob: Job? = null
    private var liteDevicesJob: Job? = null
    private var liteRenderJob: Job? = null
'''

START_METHOD = '''    private fun startLiteRealtimePolling() {
        if (liteRouterJob?.isActive != true) {
            liteRouterJob = stateScope.launch {
                while (isActive) {
                    val started = SystemClock.elapsedRealtime()
                    val latest = runCatching { liteRealtimeApi.router() }.getOrNull()
                    if (latest != null) {
                        realtimeSmoother.acceptRouter(latest)
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
                    if (latest != null) realtimeSmoother.acceptDevices(latest)
                    val elapsed = SystemClock.elapsedRealtime() - started
                    delay(if (latest == null) 3_000L else (1_000L - elapsed).coerceAtLeast(100L))
                }
            }
        }
        if (liteRenderJob?.isActive != true) {
            liteRenderJob = stateScope.launch {
                while (isActive) {
                    realtimeSmoother.renderRouter(routerDashboard)?.let { routerDashboard = it }
                    val nextOnline = realtimeSmoother.renderDevices(onlineDevices)
                    if (nextOnline !== onlineDevices) onlineDevices = nextOnline
                    val nextDevices = realtimeSmoother.renderDevices(devices)
                    if (nextDevices !== devices) devices = nextDevices
                    delay(RealtimeDisplaySmoother.FRAME_INTERVAL_MS)
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
        realtimeSmoother.reset()
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
    if "private suspend fun calibrateRealtimeCache()" in text and "onRouterRealtime = { raw ->" in text:
        print("single-WSS realtime smoothing already applied")
        return

    text, count = re.subn(
        r'object AppVersion \{.*?\n\}\n\nprivate val LabTypography:',
        VERSION_BLOCK + '\n\nprivate val LabTypography:',
        text,
        count=1,
        flags=re.DOTALL,
    )
    if count != 1:
        raise RuntimeError(f"expected AppVersion block, replaced {count}")

    if FIELDS_151 not in text:
        if FIELDS_150 not in text:
            raise RuntimeError("missing build150 realtime fields")
        text = text.replace(FIELDS_150, FIELDS_151, 1)

    text = replace_function(text, "    private fun startLiteRealtimePolling() {", START_METHOD)
    text = replace_function(text, "    fun stopRealtime() {", STOP_METHOD)

    required = (
        'private val realtimeSmoother = RealtimeDisplaySmoother()',
        'realtimeSmoother.acceptRouter(latest)',
        'realtimeSmoother.acceptDevices(latest)',
        'realtimeSmoother.renderRouter(routerDashboard)',
        'RealtimeDisplaySmoother.FRAME_INTERVAL_MS',
        'realtimeSmoother.reset()',
        '本地实时采样与缓存平滑显示',
    )
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError(f"build151 smoothing verification failed: {missing}")

    forbidden = (
        'mergeLiteRouterRealtime(routerDashboard, latest)',
        'mergeLiteDeviceRealtime(onlineDevices, latest)',
        'mergeLiteDeviceRealtime(devices, latest)',
    )
    if any(value in text for value in forbidden):
        raise RuntimeError("build150 direct-jump realtime merge remains")

    MAIN.write_text(text, encoding="utf-8")
    print("build151 truthful realtime smoothing applied")


if __name__ == "__main__":
    apply()
