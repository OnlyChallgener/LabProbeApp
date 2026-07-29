#!/usr/bin/env python3
"""Build163 follow-up: instant foreground recovery, resilient daily summary and compact sheets."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
MAIN = SRC / "MainActivity.kt"
PORT_MAPPING = SRC / "PortMapping.kt"
DESIGN = SRC / "ui/design/LabDesignComponents.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build163 follow-up anchor: {label}")
    return text.replace(old, new, 1)


def patch_main(text: str) -> str:
    # Resume the native realtime channel before any full/incremental HTTP calibration.
    text, count = re.subn(
        r"\s+refreshAll\(forceFull = forceFull, silent = true\)\n\s+if \(foregroundActive\) startRealtime\(\)",
        "\n                if (foregroundActive) startRealtime()\n                refreshAll(forceFull = forceFull, silent = true)",
        text,
        count=1,
    )
    if count != 1 and "if (foregroundActive) startRealtime()\n                refreshAll(forceFull = forceFull, silent = true)" not in text:
        raise RuntimeError("missing foreground recovery order anchor")

    new_start = '''    suspend fun startRealtime() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        // Connect first so Hub can replay its latest in-memory frames immediately.
        // HTTP cache calibration is a background safety net and must not block first paint.
        startRealtimeRendering()
        realtimeClient.start(prefs.hub, prefs.token)
        stateScope.launch { calibrateRealtimeCache() }
    }'''
    if new_start not in text:
        start = text.find("    suspend fun startRealtime() {")
        end = text.find("\n\n    suspend fun refreshRouterDashboard", start)
        if start < 0 or end < 0:
            raise RuntimeError("missing generated startRealtime boundaries")
        text = text[:start] + new_start + text[end:]

    # Status polling must use Hub's cached manifest. Remote refresh happens in Hub's
    # background task and must not block this page.
    text = text.replace(
        'requestJson("/api/agent/update/status?refresh=1")',
        'requestJson("/api/agent/update/status")',
        1,
    )

    daily_vars_old = '''    var noteEdit by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    fun loadDate(d: String) { scope.launch { runCatching { HubApi(prefs).getDaily(d) }.onSuccess { val v = it.optJSONObject("daily") ?: it; data = v; noteText = v.optString("note") } } }'''
    daily_vars_new = '''    var noteEdit by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var dailyLoadJob by remember { mutableStateOf<Job?>(null) }
    var dailyRequestId by remember { mutableLongStateOf(0L) }
    var dailySyncMessage by remember { mutableStateOf("") }

    fun localDailyShell(note: String = ""): JSONObject = JSONObject()
        .put("summary", JSONObject())
        .put("sections", JSONObject())
        .put("note", note)

    fun loadDate(d: String) {
        dailyLoadJob?.cancel()
        val requestId = ++dailyRequestId
        // Render cached events immediately. The network response only enriches this shell.
        noteText = ""
        data = localDailyShell()
        dailySyncMessage = "正在后台同步…"
        dailyLoadJob = scope.launch {
            val result = runCatching { HubApi(prefs).getDaily(d) }
            if (requestId != dailyRequestId) return@launch
            result.onSuccess {
                val value = it.optJSONObject("daily") ?: it
                data = value
                noteText = value.optString("note")
                dailySyncMessage = ""
            }.onFailure {
                dailySyncMessage = "同步失败，已显示本地缓存"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { dailyLoadJob?.cancel() }
    }'''
    text = replace_once(text, daily_vars_old, daily_vars_new, "daily cache-first loader")

    cert_anchor = '''    CertificateExpirySection(prefs)
    val d = data'''
    cert_new = '''    CertificateExpirySection(prefs)
    if (dailySyncMessage.isNotBlank()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .10f)),
        ) {
            Text(
                dailySyncMessage,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 10.8.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
            )
        }
    }
    val d = data'''
    text = replace_once(text, cert_anchor, cert_new, "daily sync status")

    start = text.find("@Composable\nfun DailySection(")
    end = text.find("@Composable\nfun DailyDeviceSummaryRow", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing DailySection function")
    daily_section = '''@Composable
fun DailySection(title: String, items: JSONArray, icon: ImageVector, accent: Color, kind: String) {
    if (items.length() <= 0) return
    ExpressiveCard(title, "${items.length()} 条", icon, accent) {
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (kind == "devices") 6.dp else 0.dp),
            ) {
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    when (kind) {
                        "devices" -> DailyDeviceSummaryRow(o)
                        "address" -> DailyAddressSummaryRow(o)
                        else -> DailyTextSummaryRow(o)
                    }
                    if (i < items.length() - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}


'''
    text = text[:start] + daily_section + text[end:]
    text = text.replace(
        'Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {',
        'Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {',
        1,
    )
    return text


def patch_design(text: str) -> str:
    text = text.replace('.fillMaxHeight(0.88f)', '.fillMaxHeight(0.96f)', 1)
    text = text.replace(
        '.padding(horizontal = 16.dp, vertical = 6.dp)\n    } else {',
        '.padding(horizontal = 16.dp, vertical = 6.dp)\n            .navigationBarsPadding()\n    } else {',
        1,
    )
    return text


def patch_port_mapping(text: str) -> str:
    # The whole card is already clickable; only tighten whitespace without shrinking text.
    text = text.replace(
        'contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)',
        'contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)',
        1,
    )
    text = text.replace('modifier = modifier.height(46.dp)', 'modifier = modifier.height(42.dp)', 1)
    return text


def verify(main: str, design: str, port_mapping: str) -> None:
    required = (
        'if (foregroundActive) startRealtime()\n                refreshAll(forceFull = forceFull, silent = true)',
        'stateScope.launch { calibrateRealtimeCache() }',
        'data = localDailyShell()',
        '同步失败，已显示本地缓存',
        'SelectionContainer {',
        'Arrangement.spacedBy(if (kind == "devices") 6.dp else 0.dp)',
        '.fillMaxHeight(0.96f)',
        'modifier = modifier.height(42.dp)',
    )
    combined = main + "\n" + design + "\n" + port_mapping
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build163 follow-up verification failed: {missing}")


def apply() -> None:
    main = patch_main(MAIN.read_text(encoding="utf-8"))
    design = patch_design(DESIGN.read_text(encoding="utf-8"))
    port_mapping = patch_port_mapping(PORT_MAPPING.read_text(encoding="utf-8"))
    MAIN.write_text(main, encoding="utf-8")
    DESIGN.write_text(design, encoding="utf-8")
    PORT_MAPPING.write_text(port_mapping, encoding="utf-8")
    verify(main, design, port_mapping)
    print("build163 foreground recovery, daily cache-first UI, text selection and sheet sizing prepared")


if __name__ == "__main__":
    apply()
