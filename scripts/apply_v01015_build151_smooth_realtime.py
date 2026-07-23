#!/usr/bin/env python3
"""Final build151 presentation smoothing applied after build150 architecture.

Real samples still come from the two small Hub APIs. This patch adds one
presentation loop that interpolates continuous values only; connection counts
and events remain exact. No prediction is performed after a real sample ages.
The lightweight router API is the APP-to-Hub lease owner: after consecutive
failures the APP pauses smoothing, stops device requests, and switches to a
low-frequency router recovery probe so Hub/Relay demand expires automatically.
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
            "v$NAME build$CODE · 按需采样与缓存平滑显示" to listOf(
                "APP 与 Hub 连接正常时才维持路由器本地高频采样",
                "连续失联后立即暂停平滑缓存和终端实时请求，并切换低频恢复探测",
                "网速、CPU、内存和温度只在真实样本之间做 900ms 平滑过渡",
                "终端连接数和上下线仍使用真实整数与真实事件，不预测、不随机造数"
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
    @Volatile private var liteRealtimeLeaseActive = false
    @Volatile private var liteRealtimeLastSuccessAt = 0L
    private var liteRouterFailureCount = 0
'''

LEASE_HELPERS = '''    private fun markLiteRealtimeLeaseSuccess(now: Long) {
        liteRealtimeLastSuccessAt = now
        liteRouterFailureCount = 0
        liteRealtimeLeaseActive = true
    }

    private fun pauseLiteRealtimeLease() {
        liteRealtimeLeaseActive = false
        liteRealtimeLastSuccessAt = 0L
        liteRouterFailureCount = 0
        realtimeSmoother.pause()
    }

'''

START_METHOD = '''    private fun startLiteRealtimePolling() {
        if (liteRouterJob?.isActive != true) {
            liteRouterJob = stateScope.launch {
                while (isActive) {
                    val started = SystemClock.elapsedRealtime()
                    val latest = runCatching { liteRealtimeApi.router() }.getOrNull()
                    if (latest != null) {
                        markLiteRealtimeLeaseSuccess(SystemClock.elapsedRealtime())
                        realtimeSmoother.acceptRouter(latest)
                        routerDashboardError = ""
                    } else {
                        liteRouterFailureCount += 1
                        val lastSuccessAge = if (liteRealtimeLastSuccessAt > 0L) {
                            SystemClock.elapsedRealtime() - liteRealtimeLastSuccessAt
                        } else {
                            Long.MAX_VALUE
                        }
                        if (liteRouterFailureCount >= 2 || lastSuccessAge >= 5_000L) {
                            pauseLiteRealtimeLease()
                        }
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val nextDelay = when {
                        latest != null -> (1_000L - elapsed).coerceAtLeast(100L)
                        liteRealtimeLeaseActive -> 2_000L
                        else -> 10_000L
                    }
                    delay(nextDelay)
                }
            }
        }
        if (liteDevicesJob?.isActive != true) {
            liteDevicesJob = stateScope.launch {
                while (isActive) {
                    if (!liteRealtimeLeaseActive) {
                        delay(750L)
                        continue
                    }
                    val started = SystemClock.elapsedRealtime()
                    val latest = runCatching { liteRealtimeApi.devices() }.getOrNull()
                    if (latest != null && liteRealtimeLeaseActive) {
                        realtimeSmoother.acceptDevices(latest)
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    delay(if (latest == null) 3_000L else (1_000L - elapsed).coerceAtLeast(100L))
                }
            }
        }
        if (liteRenderJob?.isActive != true) {
            liteRenderJob = stateScope.launch {
                while (isActive) {
                    if (liteRealtimeLeaseActive) {
                        realtimeSmoother.renderRouter(routerDashboard)?.let { routerDashboard = it }
                        val nextOnline = realtimeSmoother.renderDevices(onlineDevices)
                        if (nextOnline !== onlineDevices) onlineDevices = nextOnline
                        val nextDevices = realtimeSmoother.renderDevices(devices)
                        if (nextDevices !== devices) devices = nextDevices
                        delay(RealtimeDisplaySmoother.FRAME_INTERVAL_MS)
                    } else {
                        delay(750L)
                    }
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
        pauseLiteRealtimeLease()
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


def remove_function_if_present(text: str, signature: str) -> str:
    start = text.find(signature)
    if start < 0:
        return text
    opening = text.find("{", start + len(signature) - 1)
    closing = matching_brace(text, opening)
    end = closing + 1
    while end < len(text) and text[end] in " \t":
        end += 1
    if end < len(text) and text[end] == "\n":
        end += 1
    return text[:start] + text[end:]


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

    if FIELDS_151 not in text:
        if FIELDS_150 not in text:
            raise RuntimeError("missing build150 realtime fields")
        text = text.replace(FIELDS_150, FIELDS_151, 1)

    text = remove_function_if_present(text, "    private fun markLiteRealtimeLeaseSuccess(")
    text = remove_function_if_present(text, "    private fun pauseLiteRealtimeLease(")
    start_marker = "    private fun startLiteRealtimePolling() {"
    if start_marker not in text:
        raise RuntimeError("missing realtime polling function")
    text = text.replace(start_marker, LEASE_HELPERS + start_marker, 1)

    text = replace_function(text, start_marker, START_METHOD)
    text = replace_function(text, "    fun stopRealtime() {", STOP_METHOD)

    required = (
        'private val realtimeSmoother = RealtimeDisplaySmoother()',
        'liteRealtimeLeaseActive = true',
        'liteRouterFailureCount >= 2',
        'else -> 10_000L',
        'if (!liteRealtimeLeaseActive)',
        'realtimeSmoother.acceptRouter(latest)',
        'realtimeSmoother.acceptDevices(latest)',
        'realtimeSmoother.renderRouter(routerDashboard)',
        'RealtimeDisplaySmoother.FRAME_INTERVAL_MS',
        'realtimeSmoother.pause()',
        '按需采样与缓存平滑显示',
    )
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError(f"build151 lease-aware smoothing verification failed: {missing}")

    forbidden = (
        'mergeLiteRouterRealtime(routerDashboard, latest)',
        'mergeLiteDeviceRealtime(onlineDevices, latest)',
        'mergeLiteDeviceRealtime(devices, latest)',
        'delay(if (latest == null) 3_000L else (1_000L - elapsed)',
    )
    if any(value in text for value in forbidden):
        raise RuntimeError("unbounded build150 realtime loop remains")

    MAIN.write_text(text, encoding="utf-8")
    print("build151 lease-aware realtime smoothing applied")


if __name__ == "__main__":
    apply()
