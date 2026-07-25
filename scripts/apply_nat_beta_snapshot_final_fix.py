#!/usr/bin/env python3
"""Final NAT/Beta behavior requested after the five-row history patch.

- Remove the NAT cancel action and button.
- Reject terminal results belonging to the previous NAT run, including timeout.
- Keep RFC3489/RFC5780 history behavior and five-row limit intact.
- Do not auto-check Beta on page entry. Render the last local snapshot first and
  preserve it while a manual check runs.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"


def matching(text: str, opening: int, left: str, right: str) -> int:
    depth = 0
    quote = ""
    escaped = False
    for index in range(opening, len(text)):
        ch = text[index]
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = ""
            continue
        if ch in ('"', "'"):
            quote = ch
        elif ch == left:
            depth += 1
        elif ch == right:
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError(f"unterminated {left}{right} block")


def function_bounds(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing Kotlin function: {signature}")
    opening = text.find("{", start + len(signature))
    if opening < 0:
        raise RuntimeError(f"missing Kotlin body: {signature}")
    return start, matching(text, opening, "{", "}") + 1


def replace_function(text: str, signature: str, replacement: str) -> str:
    start, end = function_bounds(text, signature)
    return text[:start] + replacement.rstrip() + text[end:]


def remove_function(text: str, signature: str) -> str:
    start = text.find(signature)
    if start < 0:
        return text
    line_start = text.rfind("\n", 0, start) + 1
    _, end = function_bounds(text, signature)
    while end < len(text) and text[end] in " \t\r\n":
        end += 1
    return text[:line_start] + text[end:]


def remove_cancel_button(section: str) -> str:
    marker = 'Text("取消", fontWeight = FontWeight.Black)'
    marker_at = section.find(marker)
    if marker_at < 0:
        return section
    start = section.rfind("                OutlinedButton(", 0, marker_at)
    if start < 0:
        raise RuntimeError("missing NAT cancel button start")
    paren_open = section.find("(", start)
    paren_close = matching(section, paren_open, "(", ")")
    lambda_open = section.find("{", paren_close)
    if lambda_open < 0 or lambda_open > paren_close + 8:
        raise RuntimeError("missing NAT cancel button lambda")
    lambda_close = matching(section, lambda_open, "{", "}")
    end = lambda_close + 1
    while end < len(section) and section[end] in " \t\r\n":
        end += 1
    spacer = "                Spacer(Modifier.width(8.dp))\n"
    if section.startswith(spacer, end):
        end += len(spacer)
    return section[:start] + section[end:]


def patch_beta_model(text: str) -> str:
    start = text.find("private data class RouterBetaInfo(")
    end = text.find("private class RouterNativeApi", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing RouterBetaInfo boundary")
    replacement = r'''private data class RouterBetaInfo(
    val current: String = "",
    val totalCount: Int = 0,
    val message: String = "",
    val versions: List<String> = emptyList(),
    val checkedAt: Long = 0L
) {
    val hasSnapshot: Boolean get() = checkedAt > 0L || current.isNotBlank() || message.isNotBlank() || versions.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("current", current)
        .put("totalCount", totalCount)
        .put("message", message)
        .put("versions", JSONArray().apply { versions.forEach(::put) })
        .put("checkedAt", checkedAt)
}

'''
    return text[:start] + replacement + text[end:]


def patch_native_api(text: str) -> str:
    text = remove_function(text, "    suspend fun cancelNat(")

    replacement = r'''    suspend fun betaInfo(): RouterBetaInfo {
        val data = request("/api/router/beta-upgrade?force=1").optJSONObject("data") ?: JSONObject()
        val next = data.optJSONObject("new") ?: JSONObject()
        val versions = mutableListOf<String>()
        when (val firmware = next.opt("firmwareList")) {
            is JSONArray -> for (i in 0 until firmware.length()) {
                val item = firmware.opt(i)
                when (item) {
                    is JSONObject -> versions += item.optString("version").ifBlank { item.toString() }
                    null -> Unit
                    else -> versions += item.toString()
                }
            }
            is JSONObject -> {
                val keys = firmware.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = firmware.opt(key)
                    versions += if (item is JSONObject) item.optString("version").ifBlank { key } else key
                }
            }
        }
        return RouterBetaInfo(
            current = data.optString("cur").trim(),
            totalCount = next.optInt("totalCount", versions.size),
            message = next.optString("msg").ifBlank {
                if (versions.isEmpty()) "当前没有可用 Beta 版本" else "发现可用 Beta 版本"
            },
            versions = versions.distinct(),
            checkedAt = data.optLong("checkedAt", data.optLong("updatedAt", System.currentTimeMillis() / 1000L))
        )
    }'''
    return replace_function(text, "    suspend fun betaInfo(", replacement)


def patch_nat_screen(text: str) -> str:
    start, end = function_bounds(text, "fun RouterNatDiagnosticScreen(")
    section = text[start:end]
    section = remove_cancel_button(section)
    section = section.replace(
        '''                val previousTask = running && latest.completed && activeRunStartedAt > 0L &&
                    (latest.timestamp <= 0L || latest.timestamp < activeRunStartedAt)''',
        '''                val terminalFromPreviousRun = latest.completed || latest.cancelled ||
                    latest.status.contains("timeout", true) || latest.status.contains("fail", true) ||
                    latest.status.contains("error", true)
                val previousTask = running && terminalFromPreviousRun && activeRunStartedAt > 0L &&
                    (latest.timestamp <= 0L || latest.timestamp < activeRunStartedAt)''',
    )
    section = section.replace(
        '''                        normalized.cancelled || normalized.status.contains("fail", true) ||
                            normalized.status.contains("error", true) || normalized.status.contains("timeout", true) -> {''',
        '''                        normalized.status.contains("fail", true) || normalized.status.contains("error", true) ||
                            normalized.status.contains("timeout", true) -> {''',
    )
    section = section.replace('                result.cancelled -> "已取消"\n', "")
    section = section.replace('.onSuccess { refresh() }', '.onSuccess { Unit }')
    if 'Text("取消"' in section or "api.cancelNat()" in section:
        raise RuntimeError("NAT cancel UI still exists")
    return text[:start] + section + text[end:]


BETA_HELPERS = r'''
private const val ROUTER_BETA_SNAPSHOT_PREF = "router_beta_snapshot_v1"

private fun loadRouterBetaSnapshot(context: Context): RouterBetaInfo {
    val raw = context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .getString(ROUTER_BETA_SNAPSHOT_PREF, "")
        .orEmpty()
    if (raw.isBlank()) return RouterBetaInfo()
    return runCatching {
        val root = JSONObject(raw)
        val versions = root.optJSONArray("versions") ?: JSONArray()
        RouterBetaInfo(
            current = root.optString("current"),
            totalCount = root.optInt("totalCount", versions.length()),
            message = root.optString("message"),
            versions = (0 until versions.length()).mapNotNull { index ->
                versions.optString(index).trim().takeIf(String::isNotBlank)
            },
            checkedAt = root.optLong("checkedAt", 0L)
        )
    }.getOrDefault(RouterBetaInfo())
}

private fun saveRouterBetaSnapshot(context: Context, info: RouterBetaInfo) {
    if (!info.hasSnapshot) return
    context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .edit()
        .putString(ROUTER_BETA_SNAPSHOT_PREF, info.toJson().toString())
        .apply()
}

'''


def patch_beta_screen(text: str) -> str:
    if "private fun loadRouterBetaSnapshot" not in text:
        anchor = "@Composable\nfun RouterBetaUpgradeScreen"
        index = text.find(anchor)
        if index < 0:
            raise RuntimeError("missing Beta screen anchor")
        text = text[:index] + BETA_HELPERS + text[index:]

    replacement = r'''fun RouterBetaUpgradeScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterNativeApi(prefs) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(loadRouterBetaSnapshot(context)) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    suspend fun check() {
        if (checking) return
        checking = true
        runCatching { api.betaInfo() }
            .onSuccess { latest ->
                val snapshot = latest.copy(
                    checkedAt = latest.checkedAt.takeIf { it > 0L } ?: System.currentTimeMillis() / 1000L
                )
                info = snapshot
                saveRouterBetaSnapshot(context, snapshot)
                error = ""
            }
            .onFailure { failure ->
                error = natErrorZh(failure.message)
            }
        checking = false
    }

    DetailShell("Beta 在线升级", "进入页面仅显示快照 · 点击后才检测", onBack, compactHeader = true) {
        NativeCard {
            NativeTitle(Icons.Rounded.SystemUpdateAlt, "固件版本", NativeCyan)
            NativeValueRow("当前版本", info.current.ifBlank { "--" })
            NativeValueRow("可用版本", if (info.hasSnapshot) "${info.totalCount} 个" else "--")
            Text(
                when {
                    checking && info.hasSnapshot -> "正在后台检测，当前显示上次快照"
                    checking -> "正在检测更新"
                    info.message.isNotBlank() -> info.message
                    else -> "尚未检测，点击下方按钮获取最新版本信息"
                },
                fontSize = 10.5.sp,
                color = NativeMuted
            )
            Button(
                onClick = { scope.launch { check() } },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NativeCyan,
                    contentColor = Color.White,
                    disabledContainerColor = NativeCyan.copy(alpha = .72f),
                    disabledContentColor = Color.White
                )
            ) {
                if (checking) CircularProgressIndicator(
                    Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White, trackColor = Color.Transparent
                ) else Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (checking) "检测中" else "检测更新", fontWeight = FontWeight.Black)
            }
        }

        if (error.isNotBlank()) NativeMessage(
            if (info.hasSnapshot) "$error，已保留上次快照" else error,
            NativeRed
        )

        if (info.versions.isNotEmpty()) {
            NativeCard {
                NativeTitle(Icons.Rounded.NewReleases, "可用 Beta 版本", NativeGreen)
                info.versions.forEach { version ->
                    Text(version, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NativeInk)
                }
            }
        }

        NativeCard {
            NativeTitle(Icons.Rounded.WarningAmber, "升级说明", NativeAmber)
            Text(
                "Beta 版本可能存在不稳定因素。升级期间不要断电，升级完成后路由器会重启。",
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = NativeMuted
            )
            Text(
                "当前只开放安全的版本检查；尚未抓到实际安装请求前，不会猜测参数或执行升级。",
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                color = NativeAmber
            )
        }
    }
}'''
    return replace_function(text, "fun RouterBetaUpgradeScreen(", replacement)


def apply() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")
    text = patch_beta_model(text)
    text = patch_native_api(text)
    text = patch_nat_screen(text)
    text = patch_beta_screen(text)

    required = (
        "private const val ROUTER_NAT_HISTORY_LIMIT = 5",
        "terminalFromPreviousRun",
        "private const val ROUTER_BETA_SNAPSHOT_PREF",
        "loadRouterBetaSnapshot(context)",
        "正在后台检测，当前显示上次快照",
        "进入页面仅显示快照 · 点击后才检测",
        'Text(if (checking) "检测中" else "检测更新"',
    )
    missing = [needle for needle in required if needle not in text]
    if missing:
        raise RuntimeError(f"NAT/Beta final verification failed: {missing}")
    for forbidden in (
        'Text("取消"',
        "suspend fun cancelNat()",
        'LaunchedEffect(Unit) { check() }',
    ):
        if forbidden in text:
            raise RuntimeError(f"removed NAT/Beta behavior remains: {forbidden}")

    ROUTER_NATIVE.write_text(text, encoding="utf-8")
    print("NAT cancel removed, stale timeout guarded and Beta snapshot UI applied")


if __name__ == "__main__":
    apply()
