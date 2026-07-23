#!/usr/bin/env python3
"""Final build148 fixes applied after all legacy source generators.

This script is intentionally the last source-preparation step. It fixes only:
- realtime router dashboard delivery and 1 second HTTP fallback;
- About-page release text following BuildConfig;
- RFC3489/RFC5780 one-result-per-protocol history, including blank RFC5780 nat_type;
- consistent large rounded NAT dropdown surfaces.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
ROUTER_STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"

APP_VERSION_BLOCK = '''object AppVersion {
    val NAME: String get() = BuildConfig.VERSION_NAME
    val CODE: Int get() = BuildConfig.VERSION_CODE
    const val GITHUB = "https://github.com/OnlyChallgener/LabProbeApp"
    val CHANGELOG: List<Pair<String, List<String>>>
        get() = listOf(
            "v$NAME build$CODE · 实时刷新、NAT 历史与界面修复" to listOf(
                "路由仪表盘接收 Hub MQTT 实时快照，断流时每 1 秒通过 HTTP 读取内存快照",
                "关于页版本日志直接跟随 APK 的版本号和构建号",
                "RFC3489 与 RFC5780 各保留最近一次检测，RFC5780 无 nat_type 时也会保存",
                "NAT 服务器、协议与出口接口下拉弹层统一使用大圆角"
            )
        )
}'''

MQTT_CALLBACK = '''        onRevision = { revision -> mqttRevisionSignals.trySend(revision) },
        onRouterDashboard = { raw ->
            stateScope.launch {
                val latest = runCatching {
                    val root = JSONObject(raw)
                    root.optJSONObject("dashboard")
                        ?: root.optJSONObject("data")
                        ?: root
                }.getOrNull() ?: return@launch
                routerDashboard = mergeRouterDashboardSnapshot(routerDashboard, latest)
                routerDashboardMqttAt = SystemClock.elapsedRealtime()
                routerDashboardError = ""
            }
        }'''

FRESHNESS_FUNCTION = '''    fun routerDashboardMqttFresh(maxAgeMs: Long = 1_500L): Boolean {
        val receivedAt = routerDashboardMqttAt
        return mqttConnected && receivedAt > 0L &&
            SystemClock.elapsedRealtime() - receivedAt <= maxAgeMs
    }

'''

ROUTER_LOOP = '''    LaunchedEffect(Unit) {
        while (isActive) {
            if (!state.routerDashboardMqttFresh()) {
                state.refreshRouterDashboard(silent = true)
            }
            delay(1_000L)
        }
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

NAT_HELPERS = '''
private fun deriveRouterNatType(result: RouterNatResult): String {
    val direct = result.natType.trim()
    if (direct.isNotBlank() && direct != "--") return direct

    val log = result.log.lowercase()
    val symmetricMapping =
        log.contains("mapping: address and port dependent") ||
        log.contains("address and port dependent mapping") ||
        log.contains("endpoint dependent mapping") ||
        log.contains("symmetric nat")
    if (symmetricMapping) return "symmetric"

    val addressAndPortFiltering =
        log.contains("filtering: address and port dependent") ||
        log.contains("address and port dependent filtering") ||
        log.contains("port-restricted cone")
    if (addressAndPortFiltering) return "port-restricted cone"

    val addressFiltering =
        log.contains("filtering: address dependent") ||
        log.contains("address dependent filtering") ||
        log.contains("restricted cone")
    if (addressFiltering) return "restricted cone"

    val endpointIndependentFiltering =
        log.contains("filtering: endpoint independent") ||
        log.contains("endpoint independent filtering") ||
        log.contains("full cone")
    if (endpointIndependentFiltering) return "full cone"

    return if (result.mode.equals("5780", ignoreCase = true)) "RFC5780 行为检测" else "未知 NAT"
}
'''

NORMALIZE_HISTORY = '''private fun normalizeNatHistory(rows: List<RouterNatResult>): List<RouterNatResult> = rows
    .filter { it.completed }
    .map { row ->
        row.copy(
            mode = if (row.mode.equals("5780", ignoreCase = true)) "5780" else "classic",
            natType = row.natType.ifBlank { deriveRouterNatType(row) }
        )
    }
    .sortedByDescending { it.timestamp }
    .distinctBy { it.natHistoryProtocol() }
    .take(ROUTER_NAT_HISTORY_PROTOCOLS.size)
'''

SAVE_HISTORY = '''private fun saveNatHistory(context: Context, result: RouterNatResult): List<RouterNatResult> {
    val normalizedResult = result.copy(
        timestamp = result.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis() / 1000L,
        mode = if (result.mode.equals("5780", ignoreCase = true)) "5780" else "classic",
        natType = result.natType.ifBlank { deriveRouterNatType(result) }
    )
    if (!normalizedResult.completed) return loadNatHistory(context)
    val next = normalizeNatHistory(listOf(normalizedResult) + loadNatHistory(context))
    persistNatHistory(context, next)
    return next
}'''


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")

    version_pattern = re.compile(
        r'object AppVersion \{.*?\n\}\n\nprivate val LabTypography:',
        re.DOTALL,
    )
    text, count = version_pattern.subn(APP_VERSION_BLOCK + "\n\nprivate val LabTypography:", text, count=1)
    if count != 1:
        raise RuntimeError(f"expected one AppVersion block, replaced {count}")

    if "routerDashboardMqttAt" not in text:
        anchor = "    @Volatile private var foregroundActive = true\n"
        if anchor not in text:
            raise RuntimeError("missing foregroundActive anchor")
        text = text.replace(anchor, anchor + "    @Volatile private var routerDashboardMqttAt = 0L\n", 1)

    client_start = text.find("    private val realtimeClient = HubMqttClient(")
    client_end = text.find("\n    var status by", client_start)
    if client_start < 0 or client_end < 0:
        raise RuntimeError("missing HubMqttClient constructor boundaries")
    client = text[client_start:client_end]
    if "onRouterDashboard =" not in client:
        client, replaced = re.subn(
            r'onRevision\s*=\s*\{\s*revision\s*->\s*mqttRevisionSignals\.trySend\(revision\)\s*\}',
            MQTT_CALLBACK,
            client,
            count=1,
        )
        if replaced != 1:
            raise RuntimeError("missing onRevision callback")
        text = text[:client_start] + client + text[client_end:]

    if "fun routerDashboardMqttFresh(" not in text:
        marker = "    suspend fun refreshRouterDashboard(silent: Boolean = true) {\n"
        if marker not in text:
            raise RuntimeError("missing refreshRouterDashboard marker")
        text = text.replace(marker, FRESHNESS_FUNCTION + marker, 1)
    else:
        text = re.sub(
            r'    fun routerDashboardMqttFresh\(maxAgeMs: Long = [0-9_]+L\): Boolean \{.*?\n    \}\n\n',
            FRESHNESS_FUNCTION,
            text,
            count=1,
            flags=re.DOTALL,
        )

    if "v0.10.6 build134 · 首页关注与概览显示修复" in text:
        raise RuntimeError("obsolete v0.10.6 About-page text remains")
    MAIN.write_text(text, encoding="utf-8")


def patch_router_status() -> None:
    text = ROUTER_STATUS.read_text(encoding="utf-8")

    loop_start = text.find("    LaunchedEffect(state.mqttConnected) {")
    if loop_start < 0:
        loop_start = text.find("    LaunchedEffect(Unit) {", text.find("fun RouterStatusScreen"))
    loop_end = text.find("\n\n    fun normalizedRouterUrl", loop_start)
    if loop_start < 0 or loop_end < 0:
        raise RuntimeError("missing router dashboard polling loop boundaries")
    text = text[:loop_start] + ROUTER_LOOP + text[loop_end:]

    refresh_start = text.find("    fun refresh() {", loop_start)
    refresh_end = text.find("\n\n    if (showRouterEditor)", refresh_start)
    if refresh_start < 0 or refresh_end < 0:
        raise RuntimeError("missing router manual refresh boundaries")
    text = text[:refresh_start] + REFRESH_FUNCTION + text[refresh_end:]

    forbidden = (
        "delay(if (state.mqttConnected) 15_000L else 20_000L)",
        "runCatching { state.requestRouterCredentialsRefresh() }\n            refreshing = false",
    )
    if any(item in text for item in forbidden):
        raise RuntimeError("stale router refresh code remains")
    ROUTER_STATUS.write_text(text, encoding="utf-8")


def patch_nat() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")

    if "private val NativePopupShape" not in text:
        anchor = 'private val NativeBorder = Color(0xFFE3EAF4)\n'
        if anchor not in text:
            raise RuntimeError("missing NativeBorder anchor")
        text = text.replace(anchor, anchor + 'private val NativePopupShape = RoundedCornerShape(22.dp)\n', 1)

    if "private fun deriveRouterNatType" not in text:
        marker = "\nprivate data class RouterBetaInfo("
        if marker not in text:
            raise RuntimeError("missing RouterBetaInfo marker")
        text = text.replace(marker, NAT_HELPERS + marker, 1)

    text = text.replace(
        'mode = data.optString("mode", "classic"),',
        'mode = data.optString("requested_mode").takeIf { it == "classic" || it == "5780" }\n                ?: data.optString("mode", "classic"),',
        1,
    )
    text = re.sub(
        r'mode = data\.optString\("mode"\)\.takeIf \{ it == "classic" \|\| it == "5780" \}\s*\?: data\.optString\("requested_mode", "classic"\),',
        'mode = data.optString("requested_mode").takeIf { it == "classic" || it == "5780" }\n                ?: data.optString("mode", "classic"),',
        text,
        count=1,
    )

    text = text.replace(
        'NativeValueRow("NAT类型", natTypeZh(result.natType))',
        'NativeValueRow("NAT类型", natTypeZh(result.natType.ifBlank { deriveRouterNatType(result) }))',
    )
    text = text.replace(
        'NativeValueRow("NAT类型", result.natType.ifBlank { "--" })',
        'NativeValueRow("NAT类型", result.natType.ifBlank { deriveRouterNatType(result) })',
    )
    text = text.replace(
        'natTypeZh(item.natType)',
        'natTypeZh(item.natType.ifBlank { deriveRouterNatType(item) })',
    )
    text = text.replace(
        'Text(item.natType.ifBlank { "未知NAT" },',
        'Text(item.natType.ifBlank { deriveRouterNatType(item) },',
    )

    normalize_pattern = re.compile(
        r'private fun normalizeNatHistory\(rows: List<RouterNatResult>\): List<RouterNatResult> = rows.*?\n(?=private fun persistNatHistory)',
        re.DOTALL,
    )
    text, count = normalize_pattern.subn(NORMALIZE_HISTORY + "\n", text, count=1)
    if count != 1:
        raise RuntimeError("missing normalizeNatHistory function")

    save_pattern = re.compile(
        r'private fun saveNatHistory\(context: Context, result: RouterNatResult\): List<RouterNatResult> \{.*?\n\}',
        re.DOTALL,
    )
    text, count = save_pattern.subn(SAVE_HISTORY, text, count=1)
    if count != 1:
        raise RuntimeError("missing saveNatHistory function")

    text = text.replace(
        "modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White),",
        "modifier = Modifier.clip(NativePopupShape).background(Color.White),",
    )
    text = text.replace(
        "modifier = Modifier.background(Color.White),",
        "modifier = Modifier.clip(NativePopupShape).background(Color.White),",
    )
    popup_pattern = re.compile(
        r'(modifier = Modifier\.clip\(NativePopupShape\)\.background\(Color\.White\),\n)(\s*)(containerColor = Color\.White,)',
    )
    text = popup_pattern.sub(r'\1\2shape = NativePopupShape,\n\2\3', text)

    required = (
        "private val NativePopupShape = RoundedCornerShape(22.dp)",
        "natType = result.natType.ifBlank { deriveRouterNatType(result) }",
        ".distinctBy { it.natHistoryProtocol() }",
        "shape = NativePopupShape",
    )
    missing = [item for item in required if item not in text]
    if missing:
        raise RuntimeError(f"build148 NAT verification failed: {missing}")
    ROUTER_NATIVE.write_text(text, encoding="utf-8")


def apply() -> None:
    patch_main()
    patch_router_status()
    patch_nat()
    print("build148 realtime, version, NAT history and popup fixes applied")


if __name__ == "__main__":
    apply()
