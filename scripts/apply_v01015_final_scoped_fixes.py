#!/usr/bin/env python3
"""Final scoped fixes against the generated router UI source."""
from pathlib import Path

from apply_v01015_scoped_fixes import patch_nat_history_and_controls

ROOT = Path(__file__).resolve().parents[1]
ROUTER_CONTROL = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing final scoped router fix pattern: {label}")
    return text.replace(old, new, 1)


def patch_generated_network_diagnostic_cache() -> None:
    text = ROUTER_CONTROL.read_text(encoding="utf-8")

    if "import android.content.Context\n" not in text:
        text = text.replace("package com.labprobe.app\n\n", "package com.labprobe.app\n\nimport android.content.Context\n", 1)
    if "import androidx.compose.ui.platform.LocalContext\n" not in text:
        text = text.replace("import androidx.compose.ui.geometry.Size\n", "import androidx.compose.ui.geometry.Size\nimport androidx.compose.ui.platform.LocalContext\n", 1)
    if "import org.json.JSONArray\n" not in text:
        text = text.replace("import kotlinx.coroutines.launch\n", "import kotlinx.coroutines.launch\nimport org.json.JSONArray\nimport org.json.JSONObject\n", 1)

    helpers = '''
private const val ROUTER_DIAGNOSTIC_CACHE_PREF = "router_diagnostic_cache_v1"

private fun RouterDiagnostic.toCacheJson(): JSONObject = JSONObject()
    .put("progress", progress)
    .put("errorCount", errorCount)
    .put("items", JSONArray().apply {
        items.forEach { item ->
            put(JSONObject()
                .put("type", item.type)
                .put("title", item.title)
                .put("status", item.status)
                .put("result", item.result)
                .put("tips", item.tips)
                .put("advise", item.advise)
                .put("port", item.port))
        }
    })

private fun loadRouterDiagnosticCache(context: Context): RouterDiagnostic {
    val raw = context.getSharedPreferences("router_control", Context.MODE_PRIVATE)
        .getString(ROUTER_DIAGNOSTIC_CACHE_PREF, "")
        .orEmpty()
    if (raw.isBlank()) return RouterDiagnostic()
    return runCatching {
        val root = JSONObject(raw)
        val array = root.optJSONArray("items") ?: JSONArray()
        RouterDiagnostic(
            progress = root.optString("progress", "100%"),
            errorCount = root.optInt("errorCount", 0),
            items = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    RouterDiagnosticItem(
                        type = item.optString("type"),
                        title = item.optString("title"),
                        status = item.optString("status"),
                        result = item.optString("result"),
                        tips = item.optString("tips"),
                        advise = item.optString("advise"),
                        port = item.optString("port")
                    )
                }
            }
        )
    }.getOrDefault(RouterDiagnostic())
}

private fun saveRouterDiagnosticCache(context: Context, result: RouterDiagnostic) {
    if (result.items.isEmpty()) return
    context.getSharedPreferences("router_control", Context.MODE_PRIVATE)
        .edit()
        .putString(ROUTER_DIAGNOSTIC_CACHE_PREF, result.toCacheJson().toString())
        .apply()
}
'''
    if "private const val ROUTER_DIAGNOSTIC_CACHE_PREF" not in text:
        anchor = "private val RouterPage = Color(0xFFF5F8FD)\n"
        if anchor not in text:
            raise RuntimeError("missing final scoped router fix pattern: diagnostic cache anchor")
        text = text.replace(anchor, anchor + helpers, 1)

    old_state = '''fun RouterDiagnosticScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf(RouterDiagnostic()) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    suspend fun refresh() { runCatching { api.diagnostic() }.onSuccess { result = it; error = "" }.onFailure { error = it.message.orEmpty() } }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(running) { while (running) { refresh(); if (result.progress.startsWith("100")) running = false else delay(1000) } }
    val visibleItems = remember(result.items) { collapseDiagnosticItems(result.items) }'''
    new_state = '''fun RouterDiagnosticScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var result by remember { mutableStateOf(loadRouterDiagnosticCache(context)) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    suspend fun refresh(): RouterDiagnostic? {
        var latest: RouterDiagnostic? = null
        runCatching { api.diagnostic() }
            .onSuccess { value ->
                latest = value
                if (value.items.isNotEmpty()) {
                    result = value
                    saveRouterDiagnosticCache(context, value)
                } else if (running || result.items.isEmpty()) {
                    result = value
                }
                error = ""
            }
            .onFailure { error = it.message.orEmpty() }
        return latest
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(running) {
        while (running) {
            val latest = refresh()
            if (latest?.progress?.startsWith("100") == true) running = false else delay(1000)
        }
    }
    val visibleItems = remember(result.items) { collapseDiagnosticItems(result.items) }'''
    text = replace_once(text, old_state, new_state, "generated network diagnostic cache")

    old_button = '''enabled = !running, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(35.dp)) { Text(if (running) "检测中" else if (visibleItems.isEmpty()) "开始检测" else "重新检测", fontSize = 10.3.sp, fontWeight = FontWeight.Black) }'''
    new_button = '''enabled = !running, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(35.dp), colors = ButtonDefaults.buttonColors(containerColor = RouterBlue, contentColor = Color.White, disabledContainerColor = RouterBlue.copy(alpha = .62f), disabledContentColor = Color.White)) { Text(if (running) "检测中" else if (visibleItems.isEmpty()) "开始检测" else "重新检测", fontSize = 10.3.sp, fontWeight = FontWeight.Black) }'''
    text = replace_once(text, old_button, new_button, "generated diagnostic button colors")

    ROUTER_CONTROL.write_text(text, encoding="utf-8")


def apply() -> None:
    patch_nat_history_and_controls()
    patch_generated_network_diagnostic_cache()
    print("final scoped NAT and diagnostic fixes applied")


if __name__ == "__main__":
    apply()
