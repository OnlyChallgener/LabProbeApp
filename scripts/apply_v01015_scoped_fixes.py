#!/usr/bin/env python3
"""Apply only the scoped router UI/history fixes requested after build145.

The project intentionally generates the final router source during preBuild.
This patch therefore runs last and is idempotent so checked-in sources, CI and
release builds all receive exactly the same corrections.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
ROUTER_CONTROL = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing scoped router fix pattern: {label}")
    return text.replace(old, new, 1)


def patch_nat_history_and_controls() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '''    val externalAddress: String = "",
    val log: String = ""''',
        '''    val externalAddress: String = "",
    val stunPort: Int = 0,
    val log: String = ""''',
        "NAT result requested STUN port",
    )
    text = replace_once(
        text,
        '''        .put("externalAddress", externalAddress)
}''',
        '''        .put("externalAddress", externalAddress)
        .put("stunPort", stunPort)
}''',
        "NAT history JSON STUN port",
    )
    text = replace_once(
        text,
        '''            externalAddress = data.optString("external_address"),
            log = data.optString("log")''',
        '''            externalAddress = data.optString("external_address"),
            stunPort = data.optInt("requested_port", data.optInt("stun_port", data.optInt("port", 0))),
            log = data.optString("log")''',
        "NAT API requested port parsing",
    )

    text = replace_once(
        text,
        '''            .onSuccess {
                result = it
                RouterNativeMemoryCache.natResult = it
                error = ""
                if (it.completed) {
                    running = false
                    history = saveNatHistory(context, it)
                } else if (it.status.contains("fail", true) || it.status.contains("error", true)) {''',
        '''            .onSuccess { latest ->
                val normalized = if (latest.completed) {
                    latest.copy(
                        timestamp = latest.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis() / 1000L,
                        mode = latest.mode.takeIf { it.isNotBlank() } ?: mode,
                        stunPort = latest.stunPort.takeIf { it > 0 } ?: portText.toIntOrNull() ?: 0
                    )
                } else latest
                result = normalized
                RouterNativeMemoryCache.natResult = normalized
                error = ""
                if (normalized.completed) {
                    running = false
                    history = saveNatHistory(context, normalized)
                } else if (normalized.status.contains("fail", true) || normalized.status.contains("error", true)) {''',
        "NAT completed result normalization",
    )

    # Clip the server, RFC and WAN popup surfaces themselves. Setting only a
    # white background leaves the platform popup rectangular.
    rounded_menu = 'modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White),'
    plain_menu = 'modifier = Modifier.background(Color.White),'
    if rounded_menu not in text:
        count = text.count(plain_menu)
        if count < 2:
            raise RuntimeError("missing scoped router fix pattern: NAT rounded popup menus")
        text = text.replace(plain_menu, rounded_menu)

    text = replace_once(
        text,
        '''                modifier = Modifier.fillMaxWidth().height(44.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),
                shape = RoundedCornerShape(14.dp)
            ) {''',
        '''                modifier = Modifier.fillMaxWidth().height(44.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NativeBlue,
                    contentColor = Color.White,
                    disabledContainerColor = NativeBlue.copy(alpha = .62f),
                    disabledContentColor = Color.White
                )
            ) {''',
        "NAT button disabled colors",
    )
    text = replace_once(
        text,
        '''                colors = ButtonDefaults.buttonColors(containerColor = NativeCyan)
            ) {''',
        '''                colors = ButtonDefaults.buttonColors(
                    containerColor = NativeCyan,
                    contentColor = Color.White,
                    disabledContainerColor = NativeCyan.copy(alpha = .62f),
                    disabledContentColor = Color.White
                )
            ) {''',
        "Beta button disabled colors",
    )
    text = text.replace(
        'CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)',
        'CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White, trackColor = Color.Transparent)',
    )
    text = text.replace(
        'CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White)',
        'CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White, trackColor = Color.Transparent)',
    )

    text = text.replace('history.take(10).forEachIndexed', 'history.forEachIndexed')
    text = text.replace('history.take(10).lastIndex', 'history.lastIndex')

    old_history = '''private fun loadNatHistory(context: Context): List<RouterNatResult> {
    val raw = context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE).getString("nat_history", "[]") ?: "[]"
    val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let {
            RouterNatResult(
                timestamp = it.optLong("timestamp"),
                status = it.optString("status"),
                mode = it.optString("mode"),
                natType = it.optString("natType"),
                externalIp = it.optString("externalIp"),
                externalPort = it.optInt("externalPort"),
                externalAddress = it.optString("externalAddress")
            )
        }
    }
}

private fun saveNatHistory(context: Context, result: RouterNatResult): List<RouterNatResult> {
    if (!result.completed || result.natType.isBlank()) return loadNatHistory(context)
    val current = loadNatHistory(context)
    val duplicate = current.firstOrNull()?.let {
        it.timestamp == result.timestamp && it.natType == result.natType && it.externalAddress == result.externalAddress
    } == true
    val next = if (duplicate) current else (listOf(result) + current).take(10)
    val array = JSONArray()
    next.forEach { array.put(it.toJson()) }
    context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE).edit().putString("nat_history", array.toString()).apply()
    return next
}'''
    new_history = '''private val ROUTER_NAT_HISTORY_PROTOCOLS = setOf("3489", "5780")

private fun RouterNatResult.natHistoryProtocol(): String =
    if (mode.equals("5780", ignoreCase = true)) "5780" else "3489"

private fun normalizeNatHistory(rows: List<RouterNatResult>): List<RouterNatResult> = rows
    .filter { it.completed && it.natType.isNotBlank() }
    .sortedByDescending { it.timestamp }
    .distinctBy { it.natHistoryProtocol() }
    .take(ROUTER_NAT_HISTORY_PROTOCOLS.size)

private fun persistNatHistory(context: Context, rows: List<RouterNatResult>) {
    val array = JSONArray()
    rows.forEach { array.put(it.toJson()) }
    context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .edit()
        .putString("nat_history", array.toString())
        .apply()
}

private fun loadNatHistory(context: Context): List<RouterNatResult> {
    val raw = context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE).getString("nat_history", "[]") ?: "[]"
    val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    val normalized = normalizeNatHistory((0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let {
            RouterNatResult(
                timestamp = it.optLong("timestamp"),
                status = it.optString("status"),
                mode = it.optString("mode"),
                natType = it.optString("natType"),
                externalIp = it.optString("externalIp"),
                externalPort = it.optInt("externalPort"),
                externalAddress = it.optString("externalAddress"),
                stunPort = it.optInt("stunPort", 0)
            )
        }
    })
    // Migrate the old ten-row list, including the previous port-grouped form.
    if (normalized.size != array.length()) persistNatHistory(context, normalized)
    return normalized
}

private fun saveNatHistory(context: Context, result: RouterNatResult): List<RouterNatResult> {
    val normalizedResult = result.copy(
        timestamp = result.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis() / 1000L,
        mode = if (result.mode.equals("5780", ignoreCase = true)) "5780" else "classic"
    )
    if (!normalizedResult.completed || normalizedResult.natType.isBlank()) {
        return loadNatHistory(context)
    }
    val next = normalizeNatHistory(listOf(normalizedResult) + loadNatHistory(context))
    persistNatHistory(context, next)
    return next
}'''
    text = replace_once(text, old_history, new_history, "two-protocol NAT history storage")

    ROUTER_NATIVE.write_text(text, encoding="utf-8")


def patch_network_diagnostic_cache() -> None:
    text = ROUTER_CONTROL.read_text(encoding="utf-8")

    if 'import android.content.Context\n' not in text:
        text = text.replace('package com.labprobe.app\n\n', 'package com.labprobe.app\n\nimport android.content.Context\n', 1)
    if 'import androidx.compose.ui.platform.LocalContext\n' not in text:
        text = text.replace('import androidx.compose.ui.geometry.Size\n', 'import androidx.compose.ui.geometry.Size\nimport androidx.compose.ui.platform.LocalContext\n', 1)
    if 'import org.json.JSONArray\n' not in text:
        text = text.replace('import kotlinx.coroutines.launch\n', 'import kotlinx.coroutines.launch\nimport org.json.JSONArray\nimport org.json.JSONObject\n', 1)

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
    if 'private const val ROUTER_DIAGNOSTIC_CACHE_PREF' not in text:
        anchor = 'private val RouterPage = Color(0xFFF5F8FD)\n'
        if anchor not in text:
            raise RuntimeError("missing scoped router fix pattern: diagnostic cache anchor")
        text = text.replace(anchor, anchor + helpers, 1)

    text = replace_once(
        text,
        '''fun RouterDiagnosticScreen(prefs:AppPrefs,onBack:()->Unit){
    val api=remember(prefs.hub,prefs.token,prefs.hubDns){RouterControlApi(prefs)}
    val scope=rememberCoroutineScope()
    var result by remember{mutableStateOf(RouterDiagnostic())}
    var running by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    suspend fun refresh(){runCatching{api.diagnostic()}.onSuccess{result=it;error=""}.onFailure{error=it.message.orEmpty()}}
    LaunchedEffect(Unit){refresh()}
    LaunchedEffect(running){while(running){refresh();if(result.progress.startsWith("100"))running=false else delay(1000)}}''',
        '''fun RouterDiagnosticScreen(prefs:AppPrefs,onBack:()->Unit){
    val api=remember(prefs.hub,prefs.token,prefs.hubDns){RouterControlApi(prefs)}
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var result by remember{mutableStateOf(loadRouterDiagnosticCache(context))}
    var running by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    suspend fun refresh():RouterDiagnostic?{
        var latest:RouterDiagnostic?=null
        runCatching{api.diagnostic()}
            .onSuccess{value->
                latest=value
                if(value.items.isNotEmpty()){
                    result=value
                    saveRouterDiagnosticCache(context,value)
                }else if(running||result.items.isEmpty()){
                    result=value
                }
                error=""
            }
            .onFailure{error=it.message.orEmpty()}
        return latest
    }
    LaunchedEffect(Unit){refresh()}
    LaunchedEffect(running){while(running){val latest=refresh();if(latest?.progress?.startsWith("100")==true)running=false else delay(1000)}}''',
        "network diagnostic last-result cache",
    )
    text = replace_once(
        text,
        '''enabled=!running,shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.height(35.dp)){Text(if(running)"检测中" else "重新检测",fontSize=10.3.sp,fontWeight=FontWeight.Black)}''',
        '''enabled=!running,shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.height(35.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterBlue,contentColor=Color.White,disabledContainerColor=RouterBlue.copy(alpha=.62f),disabledContentColor=Color.White)){Text(if(running)"检测中" else "重新检测",fontSize=10.3.sp,fontWeight=FontWeight.Black)}''',
        "network diagnostic button disabled colors",
    )

    ROUTER_CONTROL.write_text(text, encoding="utf-8")


def apply() -> None:
    patch_nat_history_and_controls()
    patch_network_diagnostic_cache()
    print("scoped NAT and network diagnostic fixes applied")


if __name__ == "__main__":
    apply()
