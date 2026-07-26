#!/usr/bin/env python3
"""Build160: truthful realtime state, Hub-owned tasks and snapshot-preserving pages."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
MAIN = SRC / "MainActivity.kt"
WSS = SRC / "HubMqttClient.kt"
NATIVE = SRC / "RouterNativeToolsUi.kt"
CONTROL = SRC / "RouterControlUi.kt"
PORTMAP = SRC / "PortMapping.kt"
TASKS = SRC / "RouterTaskRepository.kt"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build160 anchor: {label}")
    return text.replace(old, new, 1)


def patch_wss() -> None:
    text = WSS.read_text(encoding="utf-8")
    text = replace_once(text,
        "    private val onDevicesRealtime: (String) -> Unit = {},\n    private val onRealtimeReady: (Boolean) -> Unit = {},",
        "    private val onDevicesRealtime: (String) -> Unit = {},\n    private val onTaskUpdate: (String) -> Unit = {},\n    private val onRealtimeReady: (Boolean) -> Unit = {},",
        "task callback")
    text = replace_once(text,
        "            var opened = false\n            val listener = object : WebSocketListener() {",
        "            var opened = false\n            var readyReceived = false\n            var reconnect = false\n            val listener = object : WebSocketListener() {",
        "ready state variables")
    text = replace_once(text,
        "                    val reconnect = hasConnectedBefore\n                    hasConnectedBefore = true\n                    onState(HubRealtimeState.Connected)\n                    onRealtimeReady(reconnect)",
        "                    reconnect = hasConnectedBefore\n                    hasConnectedBefore = true\n                    // A TCP/WebSocket open is not yet a healthy realtime session.\n                    // Wait for Hub's authenticated ready frame before declaring Connected.",
        "do not declare connected on socket open")
    text = replace_once(text,
        '''                    when (type) {
                        "router" -> if (data != null) onRouterRealtime(data.toString())
                        "devices" -> if (data != null) onDevicesRealtime(data.toString())
                        "ready", "keepalive" -> Unit
                    }''',
        '''                    when (type) {
                        "router" -> if (data != null) onRouterRealtime(data.toString())
                        "devices" -> if (data != null) onDevicesRealtime(data.toString())
                        "task" -> if (data != null) onTaskUpdate(data.toString())
                        "ready" -> if (!readyReceived) {
                            readyReceived = true
                            onState(HubRealtimeState.Connected)
                            onRealtimeReady(reconnect)
                        }
                        "keepalive" -> Unit
                    }''',
        "ready and task frame handling")
    text = text.replace("const val SERVER_FRAME_TIMEOUT_MS = 20_000L", "const val SERVER_FRAME_TIMEOUT_MS = 45_000L")
    WSS.write_text(text, encoding="utf-8")


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")
    text = replace_once(text,
        "    private var liteRenderJob: Job? = null\n    @Volatile private var foregroundActive = true",
        "    private var liteRenderJob: Job? = null\n    private var realtimeFreshnessJob: Job? = null\n    @Volatile private var foregroundActive = true",
        "freshness job")
    text = replace_once(text,
        '''                    HubRealtimeState.Connected -> {
                        mqttConnected = true
                        hubConnected = true
                        message = "实时同步正常"
                    }''',
        '''                    HubRealtimeState.Connected -> {
                        mqttConnected = true
                        hubConnected = true
                        realtimeDataFresh = false
                        message = "实时链路已连接，等待首帧数据"
                    }''',
        "truthful connected wording")
    text = text.replace(
        '''                    HubRealtimeState.Connecting -> {
                        mqttConnected = false
                        if (!hubConnected) message = "正在连接 Hub"
                    }''',
        '''                    HubRealtimeState.Connecting -> {
                        mqttConnected = false
                        realtimeDataFresh = false
                        message = if (hubConnected) "正在连接实时链路，已保留上次数据" else "正在连接 Hub"
                    }''')
    text = text.replace(
        '''                    is HubRealtimeState.Reconnecting -> {
                        mqttConnected = false
                        // Keep the last valid UI frame. A short transport retry must not
                        // replace the whole APP status with a scary reconnect banner.
                        if (!hubConnected) message = "正在连接 Hub"
                    }''',
        '''                    is HubRealtimeState.Reconnecting -> {
                        mqttConnected = false
                        realtimeDataFresh = false
                        message = if (hubConnected) "实时链路恢复中，已保留上次数据" else "正在连接 Hub"
                    }''')
    text = replace_once(text,
        '''                runCatching { JSONObject(raw) }.getOrNull()?.let {
                    realtimeSmoother.acceptRouter(it)
                    routerDashboardError = ""
                }''',
        '''                runCatching { JSONObject(raw) }.getOrNull()?.let {
                    realtimeSmoother.acceptRouter(it)
                    routerDashboardError = ""
                    mqttConnected = true
                    realtimeDataFresh = true
                    lastRouterRealtimeAt = SystemClock.elapsedRealtime()
                    message = "实时同步正常"
                    realtimeFreshnessJob?.cancel()
                    realtimeFreshnessJob = stateScope.launch {
                        val expected = lastRouterRealtimeAt
                        delay(15_000L)
                        if (lastRouterRealtimeAt == expected && mqttConnected) {
                            realtimeDataFresh = false
                            message = "实时数据暂时未更新，已保留上次结果"
                        }
                    }
                }''',
        "router frame freshness")
    text = replace_once(text,
        '''        onDevicesRealtime = { raw ->
            stateScope.launch {
                if (!foregroundActive) return@launch
                runCatching { JSONObject(raw) }.getOrNull()?.let { realtimeSmoother.acceptDevices(it) }
            }
        },
        onRealtimeReady = { reconnect ->''',
        '''        onDevicesRealtime = { raw ->
            stateScope.launch {
                if (!foregroundActive) return@launch
                runCatching { JSONObject(raw) }.getOrNull()?.let { realtimeSmoother.acceptDevices(it) }
            }
        },
        onTaskUpdate = { raw -> RouterTaskRepositoryRegistry.get(prefs).acceptRealtime(raw) },
        onRealtimeReady = { reconnect ->''',
        "task WSS delivery")
    text = replace_once(text,
        "    var mqttConnected by mutableStateOf(false)\n    var mqttState by mutableStateOf<HubRealtimeState>(HubRealtimeState.Disabled)",
        "    var mqttConnected by mutableStateOf(false)\n    var realtimeDataFresh by mutableStateOf(false)\n    var lastRouterRealtimeAt by mutableLongStateOf(0L)\n    var mqttState by mutableStateOf<HubRealtimeState>(HubRealtimeState.Disabled)",
        "freshness state")
    text = text.replace(
        '''        if (!mqttConnected && hubConnected) {
            message = "Hub 已连接，等待实时链路"
            return
        }
        message = when {
            mqttConnected -> "实时同步正常"
            hubConnected -> "Hub 已连接，等待实时链路"
            else -> "Hub 设置已保存，等待自动连接"
        }''',
        '''        message = when {
            realtimeDataFresh -> "实时同步正常"
            mqttConnected -> "实时链路已连接，等待首帧数据"
            hubConnected -> "Hub 已连接，实时链路恢复中"
            else -> "Hub 设置已保存，等待自动连接"
        }''')
    text = text.replace(
        '''    fun stopRealtime() {
        realtimeClient.stop()
        pauseRealtimeRendering()
        mqttConnected = false
    }''',
        '''    fun stopRealtime() {
        realtimeClient.stop()
        pauseRealtimeRendering()
        realtimeFreshnessJob?.cancel()
        realtimeFreshnessJob = null
        mqttConnected = false
        realtimeDataFresh = false
    }''')
    text = text.replace('state.hubConnected -> "实时同步正常"', 'state.realtimeDataFresh -> "实时同步正常"\n                                state.mqttConnected -> "实时链路已连接，等待首帧数据"\n                                state.hubConnected -> "实时链路恢复中，已保留上次数据"')
    text = text.replace('HubRealtimeState.Connected -> "WSS 实时"', 'HubRealtimeState.Connected -> if (state.realtimeDataFresh) "实时数据正常" else "等待首帧"')
    text = text.replace('HubRealtimeState.Connected -> "实时同步正常"', 'HubRealtimeState.Connected -> if (state.realtimeDataFresh) "实时同步正常" else "实时链路已连接，等待首帧数据"')
    text = text.replace('HubRealtimeState.Connecting -> if (state.hubConnected) "正在连接实时同步" else "正在连接 Hub"', 'HubRealtimeState.Connecting -> if (state.hubConnected) "实时链路恢复中，已保留上次数据" else "正在连接 Hub"')
    text = re.sub(r'is HubRealtimeState\.Reconnecting -> "正在重连实时同步 \$\{realtime\.attempt}/\$\{realtime\.maxAttempts}"', 'is HubRealtimeState.Reconnecting -> "实时链路恢复中，已保留上次数据"', text)
    text = text.replace('HubRealtimeState.Disabled -> if (state.hubConnected) "Hub 已连接，等待实时链路"', 'HubRealtimeState.Disabled -> if (state.hubConnected) "Hub 已连接，实时链路未建立"')
    text = text.replace('color = if (state.mqttConnected) LabV2.Green else if (state.hubConnected) LabV2.Amber', 'color = if (state.realtimeDataFresh) LabV2.Green else if (state.mqttConnected || state.hubConnected) LabV2.Amber')
    text = text.replace('if (state.mqttConnected) "WSS 实时" else "实时未连接"', 'if (state.realtimeDataFresh) "实时数据正常" else if (state.mqttConnected) "等待首帧" else "实时未连接"')
    text = text.replace('tint = if (state.mqttConnected) LabV2.Green else LabV2.InkMuted', 'tint = if (state.realtimeDataFresh) LabV2.Green else if (state.mqttConnected) LabV2.Amber else LabV2.InkMuted')
    text = text.replace('"v$NAME build$CODE · 路由控制队列与可靠指令"', '"v$NAME build$CODE · 状态闭环与后台任务"')
    MAIN.write_text(text, encoding="utf-8")


def patch_native() -> None:
    text = NATIVE.read_text(encoding="utf-8")
    text = text.replace('    var activeRunStartedAt by remember { mutableLongStateOf(0L) }\n', '')
    text = replace_once(text,
        '''    val stunPort: Int = 0,
    val log: String = ""
) {''',
        '''    val stunPort: Int = 0,
    val log: String = "",
    val taskState: String = "idle",
    val stageText: String = "尚未开始",
    val elapsedSeconds: Long = 0L,
    val lastRouterResponseAt: Long = 0L
) {''',
        "NAT task fields")
    text = text.replace(
        'val running: Boolean get() = status.equals("running", true) || status.equals("detecting", true) || status.equals("started", true)',
        'val running: Boolean get() = taskState in setOf("queued", "running") || status.equals("running", true) || status.equals("detecting", true) || status.equals("started", true)')
    marker = 'private data class RouterBetaInfo('
    helper = r'''private fun natResultFromTask(task: RouterTaskSnapshot): RouterNatResult {
    val data = task.result
    fun textOf(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        data.optString(key).trim().takeIf(String::isNotBlank)
    }.orEmpty()
    val mode = textOf("mode", "requested_mode").takeIf { it == "classic" || it == "5780" } ?: "classic"
    val mapping = textOf("mapping_behavior", "mappingBehavior", "mapping")
    val filtering = textOf("filtering_behavior", "filteringBehavior", "filtering")
    val status = when {
        task.succeeded -> "completed"
        task.failed -> task.state
        task.active -> "running"
        else -> textOf("status").ifBlank { "idle" }
    }
    val routerLog = textOf("log")
    val combinedLog = (task.log + routerLog.lines().filter(String::isNotBlank)).distinct().joinToString("\n")
    return RouterNatResult(
        timestamp = data.optLong("timestamp", task.updatedAt), status = status, mode = mode,
        natType = textOf("nat_type", "natType", "classic_type", "classicType"),
        mappingBehavior = mapping, filteringBehavior = filtering,
        externalIp = textOf("external_ip", "externalIp"),
        externalPort = data.optInt("external_port", data.optInt("externalPort", 0)),
        externalAddress = textOf("external_address", "externalAddress", "mapped_address", "mappedAddress"),
        otherAddress = textOf("other_address", "otherAddress"),
        stunPort = data.optInt("requested_port", data.optInt("stun_port", data.optInt("port", 0))),
        log = combinedLog, taskState = task.state, stageText = task.stageText,
        elapsedSeconds = task.elapsedSeconds, lastRouterResponseAt = task.lastRouterResponseAt
    )
}

'''
    if helper not in text:
        text = text.replace(marker, helper + marker, 1)

    text = text.replace(
        '    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterNativeApi(prefs) }\n    val context = androidx.compose.ui.platform.LocalContext.current\n    val scope = rememberCoroutineScope()',
        '    val tasks = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterTaskRepositoryRegistry.get(prefs) }\n    val task by tasks.nat.collectAsState()\n    val context = androidx.compose.ui.platform.LocalContext.current',
        1)
    start = text.index('    suspend fun refresh() {', text.index('fun RouterNatDiagnosticScreen'))
    end = text.index('    DetailShell("路由 NAT 诊断"', start)
    replacement = '''    LaunchedEffect(Unit) { tasks.ensure("nat") }
    LaunchedEffect(task.updatedAt, task.state) {
        val normalized = natResultFromTask(task).copy(
            mode = natResultFromTask(task).mode.takeIf { it == "classic" || it == "5780" } ?: mode,
            stunPort = natResultFromTask(task).stunPort.takeIf { it > 0 } ?: portText.toIntOrNull() ?: 0
        )
        publish(normalized)
        running = task.active
        loading = false
        error = if (task.failed) task.message.ifBlank { task.stageText } else ""
        if (task.succeeded && normalized.completed) history = saveNatHistory(context, normalized)
    }

'''
    text = text[:start] + replacement + text[end:]
    old_start = r'''                            scope.launch {
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
                                    .onSuccess { Unit }
                                    .onFailure { failure ->
                                        error = natErrorZh(failure.message)
                                        running = false
                                        activeRunStartedAt = 0L
                                    }
                            }'''
    new_start = r'''                            error = ""
                            running = true
                            sessionLog = listOf(
                                "Hub 正在提交 NAT 检测任务",
                                "STUN 服务器：$server:$port",
                                "WAN 接口：${interfaceName.uppercase()}",
                                "检测协议：${if (mode == "5780") "RFC 5780" else "RFC 3489"}"
                            ).joinToString("\n")
                            publish(RouterNatResult(status = "running", taskState = "queued", mode = mode, stunPort = port, log = sessionLog, stageText = "正在提交检测任务"))
                            tasks.startNat(server, port, interfaceName, mode)'''
    text = replace_once(text, old_start, new_start, "NAT repository start")
    text = text.replace('                running -> "检测中"', '                running -> result.stageText.ifBlank { "检测中" }', 1)
    anchor = '            NativeValueRow("检测模式", if (result.mode == "5780") "RFC 5780" else "RFC 3489")'
    text = text.replace(anchor, anchor + '''
            if (running || result.elapsedSeconds > 0L) NativeValueRow("已耗时", "${result.elapsedSeconds} 秒")
            if (result.lastRouterResponseAt > 0L) {
                val age = (System.currentTimeMillis() / 1000L - result.lastRouterResponseAt).coerceAtLeast(0L)
                NativeValueRow("路由器响应", if (age < 3L) "刚刚" else "${age} 秒前")
            }''', 1)

    beta_start = text.index('@Composable\nfun RouterBetaUpgradeScreen')
    beta_end = text.index('\n@Composable\nprivate fun NativeCard', beta_start)
    beta_function = r'''@Composable
fun RouterBetaUpgradeScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val tasks = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterTaskRepositoryRegistry.get(prefs) }
    val task by tasks.beta.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var info by remember { mutableStateOf(loadRouterBetaSnapshot(context)) }

    LaunchedEffect(Unit) { tasks.ensure("beta") }
    LaunchedEffect(task.updatedAt, task.state) {
        val data = task.result
        if (data.length() > 0) {
            val next = data.optJSONObject("new") ?: JSONObject()
            val firmware = next.optJSONArray("firmwareList") ?: JSONArray()
            val versions = (0 until firmware.length()).mapNotNull { index ->
                val item = firmware.opt(index)
                when (item) {
                    is JSONObject -> item.optString("version").ifBlank { item.toString() }
                    null -> null
                    else -> item.toString()
                }
            }
            val latest = RouterBetaInfo(
                current = data.optString("cur"),
                totalCount = next.optInt("totalCount", versions.size),
                message = task.message.ifBlank { next.optString("msg") }.let(::taskMessageZh),
                versions = versions.distinct(),
                checkedAt = data.optLong("checkedAt", task.updatedAt)
            )
            if (latest.hasSnapshot) {
                info = latest
                saveRouterBetaSnapshot(context, latest)
            }
        }
    }

    DetailShell("Beta 在线升级", "显示上次检查快照 · 仅手动检测", onBack, compactHeader = true) {
        NativeCard {
            NativeTitle(Icons.Rounded.SystemUpdateAlt, "固件版本", NativeCyan)
            NativeValueRow("当前版本", info.current.ifBlank { "--" })
            NativeValueRow("可用版本", if (info.hasSnapshot) "${info.totalCount} 个" else "--")
            Text(
                when {
                    task.active -> task.stageText
                    task.failed -> task.message.ifBlank { task.stageText }
                    else -> info.message.ifBlank { "尚未检测，点击下方按钮开始" }
                },
                fontSize = 10.5.sp,
                color = if (task.failed) NativeRed else NativeMuted
            )
            if (task.active) {
                NativeValueRow("已耗时", "${task.elapsedSeconds} 秒")
                if (task.lastRouterResponseAt > 0L) NativeValueRow("路由器状态", "已返回响应")
            }
            if (info.checkedAt > 0L) Text(
                "上次检测：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(info.checkedAt * 1000L))}",
                fontSize = 9.4.sp,
                color = NativeMuted
            )
            Button(
                onClick = { tasks.startBeta() },
                enabled = !task.active,
                modifier = Modifier.fillMaxWidth().height(42.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NativeCyan,
                    contentColor = Color.White,
                    disabledContainerColor = NativeCyan.copy(alpha = .72f),
                    disabledContentColor = Color.White
                )
            ) {
                if (task.active) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (task.active) "检测进行中" else "检测更新", fontWeight = FontWeight.Black)
            }
        }
        if (info.versions.isNotEmpty()) NativeCard {
            NativeTitle(Icons.Rounded.NewReleases, "可用版本", NativeAmber)
            info.versions.forEach { Text(it, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = NativeInk) }
        }
    }
}
'''
    text = text[:beta_start] + beta_function + text[beta_end:]
    NATIVE.write_text(text, encoding="utf-8")


def patch_control() -> None:
    api_path = SRC / "RouterControlApi.kt"
    api_text = api_path.read_text(encoding="utf-8").replace("private fun parseDiagnostic(data: JSONObject): RouterDiagnostic", "internal fun parseDiagnostic(data: JSONObject): RouterDiagnostic")
    api_path.write_text(api_text, encoding="utf-8")
    text = CONTROL.read_text(encoding="utf-8")
    start = text.index('@Composable\nfun RouterDiagnosticScreen')
    end = text.index('\n@Composable\nfun RouterHubStatusScreen', start)
    function = r'''@Composable
fun RouterDiagnosticScreen(prefs:AppPrefs,onBack:()->Unit){
    val tasks=remember(prefs.hub,prefs.token,prefs.hubDns){RouterTaskRepositoryRegistry.get(prefs)}
    val task by tasks.diagnostic.collectAsState()
    val result=remember(task.updatedAt,task.state){parseDiagnostic(task.result)}
    LaunchedEffect(Unit){tasks.ensure("diagnostic")}
    Scaffold(containerColor=RouterPage,topBar={CompactTopBar("网络自检",onBack,"物理接线 · 协商速率 · 网络状态")}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{PremiumCard(if(task.failed)RouterRed else if(result.errorCount==0)RouterGreen else RouterAmber){
                Row(verticalAlignment=Alignment.CenterVertically){
                    RouterGlyphIcon(RouterGlyph.Diagnostic,if(task.failed)RouterRed else if(result.errorCount==0)RouterGreen else RouterAmber,Modifier.size(31.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Text(when{task.active->task.stageText;task.failed->task.message.ifBlank{task.stageText};result.items.isEmpty()->"尚未检测";result.errorCount==0->"网络状态正常";else->"发现 ${result.errorCount} 项异常"},fontSize=12.5.sp,fontWeight=FontWeight.Black,color=RouterInk)
                        Text(if(task.active)"已耗时 ${task.elapsedSeconds} 秒 · 进度 ${result.progress}" else "进度 ${result.progress}",fontSize=9.7.sp,color=RouterMuted)
                        if(task.active&&task.lastRouterResponseAt<=0L)Text("检测已由 Hub 接管，可以安全离开页面",fontSize=9.4.sp,color=RouterMuted)
                    }
                    Button(onClick={tasks.startDiagnostic()},enabled=!task.active,shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.height(35.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterBlue,contentColor=Color.White,disabledContainerColor=RouterBlue.copy(alpha=.62f),disabledContentColor=Color.White)){Text(if(task.active)"检测中" else "开始检测",fontSize=10.3.sp,fontWeight=FontWeight.Black)}
                }
            }}
            if(task.failed)item{CompactMessage(task.message.ifBlank{task.stageText},RouterRed)}
            items(result.items){item->val accent=if(item.status=="success")RouterGreen else RouterAmber;PremiumCard(accent){Row(verticalAlignment=Alignment.Top){Icon(if(item.status=="success")Icons.Rounded.CheckCircle else Icons.Rounded.Warning,null,tint=accent,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(item.title.ifBlank{item.type},fontSize=11.7.sp,fontWeight=FontWeight.Black,color=RouterInk);Text(item.result,fontSize=10.2.sp,fontWeight=FontWeight.SemiBold,color=RouterInk);if(item.port.isNotBlank())Text("问题接口：${item.port}",fontSize=9.7.sp,color=RouterRed);if(item.tips.isNotBlank())Text(item.tips,fontSize=9.5.sp,color=RouterMuted);if(item.advise.isNotBlank())Text(item.advise,fontSize=9.5.sp,color=RouterMuted,lineHeight=13.sp)}}}}
        }
    }
}
'''
    text = text[:start] + function + text[end:]
    CONTROL.write_text(text, encoding="utf-8")


def patch_portmap() -> None:
    text = PORTMAP.read_text(encoding="utf-8")
    cache = '''private object PortMappingMemoryCache {
    var rules: List<PortMapRule> = emptyList()
    var devices: List<DeviceItem> = emptyList()
    var agent: PortMapAgentInfo? = null
}

'''
    marker = '@Composable\nfun PortMappingScreen'
    if cache not in text:
        text = text.replace(marker, cache + marker, 1)
    text = text.replace('var rules by remember { mutableStateOf<List<PortMapRule>>(emptyList()) }', 'var rules by remember { mutableStateOf(PortMappingMemoryCache.rules) }', 1)
    text = text.replace('var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }', 'var devices by remember { mutableStateOf(PortMappingMemoryCache.devices) }', 1)
    text = text.replace('var agent by remember { mutableStateOf(PortMapAgentInfo(false, "router", "", 20000, 20020)) }', 'var agent by remember { mutableStateOf(PortMappingMemoryCache.agent ?: PortMapAgentInfo(false, "router", "", 20000, 20020)) }', 1)
    text = text.replace('var loading by remember { mutableStateOf(true) }', 'var loading by remember { mutableStateOf(PortMappingMemoryCache.agent == null && PortMappingMemoryCache.rules.isEmpty()) }', 1)
    text = text.replace('            rules = newRules\n            agent = newAgent\n            if (devices.isEmpty()) devices = deviceApi.getDevices(true)', '            rules = newRules\n            agent = newAgent\n            PortMappingMemoryCache.rules = newRules\n            PortMappingMemoryCache.agent = newAgent\n            if (devices.isEmpty()) devices = deviceApi.getDevices(true)\n            PortMappingMemoryCache.devices = devices', 1)
    text = text.replace('''    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(10_000)
            refresh(true)
        }
    }''', '''    LaunchedEffect(Unit) { refresh(silent = rules.isNotEmpty() || PortMappingMemoryCache.agent != null) }''', 1)
    PORTMAP.write_text(text, encoding="utf-8")


def patch_version() -> None:
    text = GRADLE.read_text(encoding="utf-8")
    text = text.replace("versionCode = 159", "versionCode = 160")
    text = text.replace('versionName = "0.10.17"', 'versionName = "0.10.18"')
    GRADLE.write_text(text, encoding="utf-8")


def verify() -> None:
    combined = "\n".join(path.read_text(encoding="utf-8") for path in (MAIN, WSS, NATIVE, CONTROL, PORTMAP, TASKS, GRADLE))
    required = (
        'onTaskUpdate: (String) -> Unit',
        'const val SERVER_FRAME_TIMEOUT_MS = 45_000L',
        'realtimeDataFresh by mutableStateOf(false)',
        '实时链路已连接，等待首帧数据',
        '/api/router/tasks/$kind',
        'RouterTaskRepositoryRegistry.get(prefs)',
        '检测已由 Hub 接管，可以安全离开页面',
        'PortMappingMemoryCache',
        'versionCode = 160',
        'versionName = "0.10.18"',
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build160 verification failed: {missing}")
    forbidden = (
        'HubRealtimeState.Connected -> "实时同步正常"',
        'state.hubConnected -> "实时同步正常"',
        'SERVER_FRAME_TIMEOUT_MS = 20_000L',
        'while (running && isActive) {\n            delay(700L)',
        'api.betaInfo()',
    )
    remaining = [value for value in forbidden if value in combined]
    if remaining:
        raise RuntimeError(f"build160 legacy behavior remains: {remaining}")


def apply() -> None:
    patch_wss()
    patch_main()
    patch_native()
    patch_control()
    patch_portmap()
    patch_version()
    verify()
    print("build160 truthful realtime state, Hub-owned tasks and snapshot-preserving pages applied")


if __name__ == "__main__":
    apply()
