#!/usr/bin/env python3
"""Add NAT cancellation, RFC5780 behavior results and a five-row history.

Runs after all generated router UI patches so both RFC3489 and RFC5780 share the
same cancel action and RFC5780 results are no longer discarded for lacking a
legacy nat_type field.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"


def matching_brace(text: str, opening: int) -> int:
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
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError("unterminated Kotlin block")


def replace_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing Kotlin function: {signature}")
    opening = text.find("{", start + len(signature))
    if opening < 0:
        raise RuntimeError(f"missing Kotlin function body: {signature}")
    end = matching_brace(text, opening) + 1
    return text[:start] + replacement.rstrip() + text[end:]


def patch_result_model(text: str) -> str:
    start = text.find("private data class RouterNatResult(")
    end = text.find("private data class RouterBetaInfo(", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing RouterNatResult model boundary")
    replacement = r'''private data class RouterNatResult(
    val timestamp: Long = 0L,
    val status: String = "idle",
    val mode: String = "classic",
    val natType: String = "",
    val mappingBehavior: String = "",
    val filteringBehavior: String = "",
    val externalIp: String = "",
    val externalPort: Int = 0,
    val externalAddress: String = "",
    val otherAddress: String = "",
    val stunPort: Int = 0,
    val log: String = ""
) {
    val completed: Boolean get() =
        status.equals("completed", true) || status.equals("success", true) ||
            (mode.equals("5780", true) && mappingBehavior.isNotBlank() && filteringBehavior.isNotBlank())
    val cancelled: Boolean get() = status.equals("cancelled", true) || status.equals("canceled", true)
    val running: Boolean get() = status.equals("running", true) || status.equals("detecting", true) || status.equals("started", true)

    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("status", status)
        .put("mode", mode)
        .put("natType", natType)
        .put("mappingBehavior", mappingBehavior)
        .put("filteringBehavior", filteringBehavior)
        .put("externalIp", externalIp)
        .put("externalPort", externalPort)
        .put("externalAddress", externalAddress)
        .put("otherAddress", otherAddress)
        .put("stunPort", stunPort)
}

'''
    return text[:start] + replacement + text[end:]


def patch_api(text: str) -> str:
    parser_and_status = r'''    private fun parseNatResult(data: JSONObject): RouterNatResult {
        fun textOf(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            data.optString(key).trim().takeIf(String::isNotBlank)
        }.orEmpty()

        val mode = textOf("mode", "requested_mode").takeIf { it == "classic" || it == "5780" } ?: "classic"
        val mapping = textOf("mapping_behavior", "mappingBehavior", "mapping")
        val filtering = textOf("filtering_behavior", "filteringBehavior", "filtering")
        val rawStatus = textOf("status").ifBlank { "idle" }
        val status = if (mode == "5780" && mapping.isNotBlank() && filtering.isNotBlank() &&
            rawStatus !in listOf("cancelled", "canceled", "error", "failed", "timeout")) {
            "completed"
        } else rawStatus

        return RouterNatResult(
            timestamp = data.optLong("timestamp", data.optLong("updatedAt", 0L)),
            status = status,
            mode = mode,
            natType = textOf("nat_type", "natType", "classic_type", "classicType"),
            mappingBehavior = mapping,
            filteringBehavior = filtering,
            externalIp = textOf("external_ip", "externalIp"),
            externalPort = data.optInt("external_port", data.optInt("externalPort", 0)),
            externalAddress = textOf("external_address", "externalAddress", "mapped_address", "mappedAddress"),
            otherAddress = textOf("other_address", "otherAddress"),
            stunPort = data.optInt("requested_port", data.optInt("stun_port", data.optInt("port", 0))),
            log = textOf("log")
        )
    }

    suspend fun natStatus(): RouterNatResult {
        val data = request("/api/router/nat-diagnostic").optJSONObject("data") ?: JSONObject()
        return parseNatResult(data)
    }'''
    text = replace_function(text, "    suspend fun natStatus(", parser_and_status)

    start_and_cancel = r'''    suspend fun startNat(host: String, port: Int, interfaceName: String, mode: String) {
        request(
            "/api/router/nat-diagnostic",
            "POST",
            JSONObject()
                .put("host", host)
                .put("port", port)
                .put("interface", interfaceName)
                .put("mode", mode)
        )
    }

    suspend fun cancelNat(): RouterNatResult {
        val data = request("/api/router/nat-diagnostic/cancel", "POST").optJSONObject("data") ?: JSONObject()
        return parseNatResult(data)
    }'''
    return replace_function(text, "    suspend fun startNat(", start_and_cancel)


def patch_port_weight(text: str) -> str:
    signature = "private fun NativeCompactPortField("
    start = text.find(signature)
    if start < 0:
        raise RuntimeError("missing NativeCompactPortField")
    opening = text.find("{", start)
    end = matching_brace(text, opening) + 1
    section = text[start:end]
    section = section.replace("fontSize = 16.sp,\n                        fontWeight = FontWeight.Black", "fontSize = 13.sp,\n                        fontWeight = FontWeight.Bold")
    return text[:start] + section + text[end:]


def patch_screen(text: str) -> str:
    replacement = r'''fun RouterNatDiagnosticScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterNativeApi(prefs) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = remember {
        listOf(
            "stun.voip.aebc.com",
            "stun.miwifi.com",
            "stun.hot-chilli.net",
            "stun.internetcalls.com",
            "stun.fitaauto.ru",
            "stun.voipbuster.com",
            "stun.voipstunt.com"
        )
    }
    var server by rememberSaveable { mutableStateOf(servers.first()) }
    var portText by rememberSaveable { mutableStateOf("3478") }
    var mode by rememberSaveable { mutableStateOf("classic") }
    var interfaceName by rememberSaveable { mutableStateOf("wan") }
    val cachedResult = RouterNativeMemoryCache.natResult
    var result by remember { mutableStateOf(cachedResult ?: RouterNatResult()) }
    var sessionLog by remember { mutableStateOf(cachedResult?.log.orEmpty()) }
    var running by remember { mutableStateOf(cachedResult?.running == true) }
    var activeRunStartedAt by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(cachedResult == null) }
    var error by remember { mutableStateOf("") }
    var serverMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var interfaceMenu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadNatHistory(context)) }

    fun publish(next: RouterNatResult) {
        val mergedLog = mergeNatLog(sessionLog, next.log)
        sessionLog = mergedLog
        result = next.copy(log = mergedLog)
        RouterNativeMemoryCache.natResult = result
    }

    suspend fun refresh() {
        runCatching { api.natStatus() }
            .onSuccess { latest ->
                val previousTask = running && latest.completed && activeRunStartedAt > 0L &&
                    (latest.timestamp <= 0L || latest.timestamp < activeRunStartedAt)
                if (!previousTask) {
                    val normalized = latest.copy(
                        timestamp = if (latest.completed) latest.timestamp.takeIf { it > 0L }
                            ?: System.currentTimeMillis() / 1000L else latest.timestamp,
                        mode = latest.mode.takeIf { it == "classic" || it == "5780" } ?: mode,
                        stunPort = latest.stunPort.takeIf { it > 0 } ?: portText.toIntOrNull() ?: 0
                    )
                    publish(normalized)
                    error = ""
                    when {
                        normalized.completed -> {
                            running = false
                            activeRunStartedAt = 0L
                            history = saveNatHistory(context, normalized)
                        }
                        normalized.cancelled || normalized.status.contains("fail", true) ||
                            normalized.status.contains("error", true) || normalized.status.contains("timeout", true) -> {
                            running = false
                            activeRunStartedAt = 0L
                        }
                    }
                }
            }
            .onFailure { failure -> error = natErrorZh(failure.message) }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(running) {
        while (running && isActive) {
            delay(700L)
            refresh()
        }
    }

    DetailShell("路由 NAT 诊断", "路由器原生 RFC3489 / RFC5780", onBack, compactHeader = true) {
        NativeCard {
            NativeTitle(Icons.Rounded.Radar, "检测参数", NativeBlue)
            val serverShape = RoundedCornerShape(18.dp)
            Box {
                OutlinedButton(
                    onClick = { serverMenu = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp).nativeBlueShadow(serverShape, 5.dp),
                    shape = serverShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, NativeBlue.copy(alpha = .32f))
                ) {
                    Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp), tint = NativeBlue)
                    Spacer(Modifier.width(7.dp))
                    Text(server, Modifier.weight(1f), color = NativeInk, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = NativeBlue)
                }
                DropdownMenu(
                    expanded = serverMenu,
                    onDismissRequest = { serverMenu = false },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 7.dp
                ) {
                    servers.forEach { host ->
                        DropdownMenuItem(
                            text = { Text(host, color = NativeInk, fontWeight = FontWeight.Bold) },
                            onClick = { server = host; serverMenu = false },
                            modifier = Modifier.background(Color.White)
                        )
                    }
                }
            }
            NativeCompactPortField(portText) { portText = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeSelector(
                    label = "STUN 类型",
                    value = if (mode == "5780") "RFC 5780" else "RFC 3489",
                    options = listOf("classic" to "RFC 3489", "5780" to "RFC 5780"),
                    expanded = modeMenu,
                    onExpandedChange = { modeMenu = it },
                    onSelect = { mode = it },
                    modifier = Modifier.weight(1f)
                )
                NativeSelector(
                    label = "WAN 类型",
                    value = interfaceName.uppercase(),
                    options = listOf("wan" to "WAN", "wan1" to "WAN1"),
                    expanded = interfaceMenu,
                    onExpandedChange = { interfaceMenu = it },
                    onSelect = { interfaceName = it },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val cancelled = runCatching { api.cancelNat() }.getOrElse { failure ->
                                error = natErrorZh(failure.message)
                                result.copy(status = "cancelled", log = mergeNatLog(result.log, "[NAT 检测] 已取消 APP 与 Hub 轮询"))
                            }
                            running = false
                            activeRunStartedAt = 0L
                            publish(cancelled.copy(
                                mode = cancelled.mode.takeIf { it == "classic" || it == "5780" } ?: mode,
                                stunPort = cancelled.stunPort.takeIf { it > 0 } ?: portText.toIntOrNull() ?: 0,
                                log = mergeNatLog(cancelled.log, "[NAT 检测] 已取消")
                            ))
                        }
                    },
                    enabled = running,
                    modifier = Modifier.width(92.dp).height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White,
                        contentColor = NativeMuted,
                        disabledContentColor = NativeMuted.copy(alpha = .45f)
                    ),
                    border = BorderStroke(1.dp, NativeBorder)
                ) {
                    Text("取消", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            error = "请输入正确的 STUN 端口"
                        } else {
                            scope.launch {
                                error = ""
                                running = true
                                activeRunStartedAt = System.currentTimeMillis() / 1000L
                                sessionLog = listOf(
                                    "[NAT 检测] 已发送检测任务",
                                    "[配置] STUN 服务器：$server:$port",
                                    "[配置] WAN 类型：${interfaceName.uppercase()}",
                                    "[配置] 检测协议：${if (mode == "5780") "RFC 5780" else "RFC 3489"}"
                                ).joinToString("\n")
                                publish(RouterNatResult(status = "running", mode = mode, stunPort = port, log = sessionLog))
                                runCatching { api.startNat(server, port, interfaceName, mode) }
                                    .onSuccess { refresh() }
                                    .onFailure { failure ->
                                        error = natErrorZh(failure.message)
                                        running = false
                                        activeRunStartedAt = 0L
                                    }
                            }
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.width(158.dp).height(44.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NativeBlue,
                        contentColor = Color.White,
                        disabledContainerColor = NativeBlue.copy(alpha = .62f),
                        disabledContentColor = Color.White
                    )
                ) {
                    if (running) CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White, trackColor = Color.Transparent
                    ) else Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (running) "检测中" else "开始检测", fontWeight = FontWeight.Black)
                }
            }
        }

        if (error.isNotBlank()) NativeMessage(error, NativeRed)

        NativeCard {
            NativeTitle(Icons.Rounded.Analytics, "分析结果", NativeGreen)
            NativeValueRow("检测状态", when {
                loading && result.status == "idle" -> "读取中"
                running -> "检测中"
                result.completed -> "检测完成"
                result.cancelled -> "已取消"
                else -> natStatusZh(result.status)
            })
            if (result.mode == "5780") {
                NativeValueRow("映射行为", natMappingBehaviorZh(result.mappingBehavior))
                NativeValueRow("过滤行为", natFilteringBehaviorZh(result.filteringBehavior))
                if (result.otherAddress.isNotBlank()) NativeValueRow("其他地址", result.otherAddress)
            } else {
                NativeValueRow("NAT类型", natTypeZh(result.natType))
            }
            NativeValueRow("外网地址", result.externalAddress.ifBlank {
                if (result.externalIp.isBlank()) "--" else result.externalIp +
                    if (result.externalPort > 0) ":${result.externalPort}" else ""
            })
            NativeValueRow("检测模式", if (result.mode == "5780") "RFC 5780" else "RFC 3489")
        }

        if (result.log.isNotBlank()) {
            NativeCard {
                NativeTitle(Icons.Rounded.Terminal, "检测日志", NativeCyan)
                SelectionContainer {
                    Text(
                        natLogZh(result.log),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = NativeInk
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            NativeCard {
                NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)
                history.forEachIndexed { index, item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            if (item.mode == "5780") {
                                listOf(
                                    natMappingBehaviorZh(item.mappingBehavior),
                                    natFilteringBehaviorZh(item.filteringBehavior)
                                ).filter { it != "--" }.joinToString(" · ").ifBlank { "RFC5780 行为检测" }
                            } else natTypeZh(item.natType),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            color = NativeInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOf(
                                item.externalAddress,
                                if (item.mode == "5780") "RFC5780" else "RFC3489",
                                item.stunPort.takeIf { it > 0 }?.let { "$it 端口" }.orEmpty()
                            ).filter(String::isNotBlank).joinToString(" · "),
                            fontSize = 9.8.sp,
                            color = NativeMuted
                        )
                    }
                    if (index != history.lastIndex) HorizontalDivider(color = NativeBorder)
                }
            }
        }
    }
}'''
    return replace_function(text, "fun RouterNatDiagnosticScreen(", replacement)


def patch_history(text: str) -> str:
    start = text.find("private val ROUTER_NAT_HISTORY_PROTOCOLS")
    if start < 0:
        start = text.find("private fun loadNatHistory(")
    if start < 0:
        raise RuntimeError("missing NAT history start")
    save_start = text.find("private fun saveNatHistory(", start)
    opening = text.find("{", save_start)
    end = matching_brace(text, opening) + 1
    replacement = r'''private const val ROUTER_NAT_HISTORY_LIMIT = 5

private fun natMappingBehaviorZh(raw: String): String = when (raw.trim().lowercase()) {
    "endpoint independent", "endpoint-independent", "eim" -> "端点独立映射"
    "address dependent", "address-dependent", "adm" -> "地址相关映射"
    "address and port dependent", "addr & port dependent", "address-port dependent", "apdm" -> "地址和端口相关映射"
    "" -> "--"
    else -> raw
}

private fun natFilteringBehaviorZh(raw: String): String = when (raw.trim().lowercase()) {
    "endpoint independent", "endpoint-independent", "eif" -> "端点独立过滤"
    "address dependent", "address-dependent", "adf" -> "地址相关过滤"
    "address and port dependent", "addr & port dependent", "address-port dependent", "apdf" -> "地址和端口相关过滤"
    "" -> "--"
    else -> raw
}

private fun RouterNatResult.hasHistoryResult(): Boolean = completed && when {
    mode.equals("5780", true) -> mappingBehavior.isNotBlank() || filteringBehavior.isNotBlank()
    else -> natType.isNotBlank()
}

private fun RouterNatResult.historyKey(): String = listOf(
    timestamp.toString(), mode.lowercase(), natType.lowercase(), mappingBehavior.lowercase(),
    filteringBehavior.lowercase(), externalAddress.lowercase(), stunPort.toString()
).joinToString("|")

private fun normalizeNatHistory(rows: List<RouterNatResult>): List<RouterNatResult> = rows
    .filter(RouterNatResult::hasHistoryResult)
    .sortedByDescending { it.timestamp }
    .distinctBy(RouterNatResult::historyKey)
    .take(ROUTER_NAT_HISTORY_LIMIT)

private fun persistNatHistory(context: Context, rows: List<RouterNatResult>) {
    val array = JSONArray()
    rows.take(ROUTER_NAT_HISTORY_LIMIT).forEach { array.put(it.toJson()) }
    context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .edit()
        .putString("nat_history", array.toString())
        .apply()
}

private fun loadNatHistory(context: Context): List<RouterNatResult> {
    val raw = context.getSharedPreferences("router_native_tools", Context.MODE_PRIVATE)
        .getString("nat_history", "[]") ?: "[]"
    val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    val normalized = normalizeNatHistory((0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let {
            RouterNatResult(
                timestamp = it.optLong("timestamp"),
                status = it.optString("status"),
                mode = it.optString("mode"),
                natType = it.optString("natType"),
                mappingBehavior = it.optString("mappingBehavior"),
                filteringBehavior = it.optString("filteringBehavior"),
                externalIp = it.optString("externalIp"),
                externalPort = it.optInt("externalPort"),
                externalAddress = it.optString("externalAddress"),
                otherAddress = it.optString("otherAddress"),
                stunPort = it.optInt("stunPort", 0)
            )
        }
    })
    if (normalized.size != array.length()) persistNatHistory(context, normalized)
    return normalized
}

private fun saveNatHistory(context: Context, result: RouterNatResult): List<RouterNatResult> {
    val normalizedResult = result.copy(
        timestamp = result.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis() / 1000L,
        mode = if (result.mode.equals("5780", ignoreCase = true)) "5780" else "classic",
        status = "completed"
    )
    if (!normalizedResult.hasHistoryResult()) return loadNatHistory(context)
    val next = normalizeNatHistory(listOf(normalizedResult) + loadNatHistory(context))
    persistNatHistory(context, next)
    return next
}'''
    return text[:start] + replacement + text[end:]


def apply() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")
    text = patch_port_weight(text)
    text = patch_result_model(text)
    text = patch_api(text)
    text = patch_screen(text)
    text = patch_history(text)

    required = (
        "suspend fun cancelNat()",
        '"/api/router/nat-diagnostic/cancel"',
        "val mappingBehavior: String",
        "val filteringBehavior: String",
        "private const val ROUTER_NAT_HISTORY_LIMIT = 5",
        "enabled = running",
        'Text("取消"',
        "natMappingBehaviorZh(item.mappingBehavior)",
        "fontSize = 13.sp",
    )
    missing = [needle for needle in required if needle not in text]
    if missing:
        raise RuntimeError(f"NAT cancellation/history verification failed: {missing}")
    if "ROUTER_NAT_HISTORY_PROTOCOLS" in text or ".distinctBy { it.natHistoryProtocol() }" in text:
        raise RuntimeError("old per-protocol NAT history limiter remains")

    ROUTER_NATIVE.write_text(text, encoding="utf-8")
    print("NAT cancel, RFC5780 behavior history and five-row limit applied")


if __name__ == "__main__":
    apply()
