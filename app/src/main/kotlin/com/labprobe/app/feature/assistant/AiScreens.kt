package com.labprobe.app.feature.assistant

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.labprobe.app.DetailShell
import com.labprobe.app.LabTypography
import com.labprobe.app.LabV2
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun AiPetEntry(onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            Modifier.size(54.dp).clickable { onOpen() },
            shape = CircleShape,
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border),
            shadowElevation = 4.dp,
        ) {
            Canvas(Modifier.fillMaxSize().padding(11.dp)) {
                val body = Color(0xFF17263D)
                drawLine(LabV2.Cyan, Offset(size.width / 2f, 1f), Offset(size.width / 2f, size.height * .2f), 2.2f)
                drawCircle(LabV2.Cyan, 2.2f, Offset(size.width / 2f, 1f))
                drawRoundRect(body, topLeft = Offset(1f, size.height * .2f), size = Size(size.width - 2f, size.height * .68f), cornerRadius = CornerRadius(size.width * .28f))
                drawCircle(LabV2.Cyan, 3.2f, Offset(size.width * .36f, size.height * .52f))
                drawCircle(LabV2.Cyan, 3.2f, Offset(size.width * .64f, size.height * .52f))
            }
        }
    }
}

/** Small, edge-docked assistant entry. The first tap reveals the whole pet and the
 * second opens the assistant. Vertical drag keeps its resting position user-controlled. */
@Composable
fun AiFloatingPet(onOpen: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val petSize = 54.dp
        val maxTravel = with(density) { (maxHeight - petSize - 24.dp).toPx().coerceAtLeast(0f) }
        var yPx by remember { mutableFloatStateOf(0f) }
        var initialized by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf(false) }
        val xOffset by animateDpAsState(
            targetValue = if (expanded) (-8).dp else 18.dp,
            label = "assistant-pet-dock",
        )
        LaunchedEffect(maxTravel) {
            if (!initialized) {
                yPx = maxTravel * .36f
                initialized = true
            } else {
                yPx = yPx.coerceIn(0f, maxTravel)
            }
        }
        LaunchedEffect(expanded) {
            if (expanded) {
                delay(4_000)
                expanded = false
            }
        }
        val yDp = with(density) { yPx.coerceIn(0f, maxTravel).toDp() }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(20f)
                .offset(x = xOffset, y = yDp)
                .size(petSize)
                .clickable {
                    if (expanded) onOpen() else expanded = true
                }
                .pointerInput(maxTravel) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            yPx = (yPx + dragAmount).coerceIn(0f, maxTravel)
                        },
                    )
                },
            shape = CircleShape,
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border),
            shadowElevation = 5.dp,
        ) {
            Canvas(Modifier.fillMaxSize().padding(9.dp)) {
                val body = Color(0xFF17263D)
                val cyan = LabV2.Cyan
                drawCircle(cyan.copy(alpha = .12f), size.minDimension * .48f, Offset(size.width * .5f, size.height * .55f))
                drawLine(cyan, Offset(size.width * .5f, 0f), Offset(size.width * .5f, size.height * .19f), 2.4f)
                drawCircle(cyan, 2.4f, Offset(size.width * .5f, 0f))
                drawRoundRect(body, topLeft = Offset(1f, size.height * .19f), size = Size(size.width - 2f, size.height * .68f), cornerRadius = CornerRadius(size.width * .3f))
                drawRoundRect(cyan.copy(alpha = .42f), topLeft = Offset(1f, size.height * .19f), size = Size(size.width - 2f, size.height * .68f), cornerRadius = CornerRadius(size.width * .3f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.3f))
                drawCircle(cyan, 3.3f, Offset(size.width * .36f, size.height * .52f))
                drawCircle(cyan, 3.3f, Offset(size.width * .64f, size.height * .52f))
                drawRoundRect(Color.White.copy(alpha = .16f), topLeft = Offset(size.width * .28f, size.height * .71f), size = Size(size.width * .44f, 1.6f), cornerRadius = CornerRadius(1f))
            }
        }
    }
}

@Composable
fun AiSettingsScreen(context: Context, hubUrl: String, hubToken: String, onBack: () -> Unit, onChat: () -> Unit, onUsage: () -> Unit, onWechat: () -> Unit) {
    val store = remember { AiSettingsStore(context) }
    var settings by remember { mutableStateOf(store.read()) }
    var model by remember { mutableStateOf(settings.model) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var key by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<AiConnectionState>(AiConnectionState.Idle) }
    val scope = rememberCoroutineScope()
    val client = remember(hubUrl, hubToken) { AiApiClient(store, hubUrl, hubToken) }
    DetailShell("AI 设置", "Hub AI 与模型连接", onBack) {
        Surface(shape = LabV2.CardShape, color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("启用 Hub AI", style = LabTypography.CardTitle); Text("对话历史以 Hub 为权威，APP 仅保留当前会话", style = LabTypography.Supporting) }; Switch(settings.enabled, { settings = settings.copy(enabled = it) }) }
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型") }, singleLine = true)
                OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, placeholder = { Text("https://api.example.com") }, singleLine = true)
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (settings.hasApiKey) "API Key（已保存，可替换）" else "API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("Key 只随配置请求提交一次，之后由 Hub 托管；APP 不保存原文。", style = LabTypography.Caption)
                when (val s = state) { AiConnectionState.Testing -> LinearProgressIndicator(Modifier.fillMaxWidth()); is AiConnectionState.Success -> Text(s.message, color = LabV2.Green, fontSize = 12.sp); is AiConnectionState.Failure -> Text(s.message, color = LabV2.Red, fontSize = 12.sp); else -> Unit }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        state = AiConnectionState.Testing
                        scope.launch {
                            runCatching {
                                val saved = client.saveConfig(settings.copy(model = model, baseUrl = baseUrl), key.takeIf { it.isNotBlank() })
                                store.save(saved)
                                settings = saved
                                key = ""
                                client.testConnection()
                            }.onSuccess { state = AiConnectionState.Success(it) }
                                .onFailure { state = AiConnectionState.Failure(it.message ?: "连接失败") }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("测试连接") }
                    Button(onClick = {
                        scope.launch {
                            runCatching {
                                val saved = client.saveConfig(settings.copy(model = model, baseUrl = baseUrl), key.takeIf { it.isNotBlank() })
                                store.save(saved)
                                settings = saved
                                key = ""
                                "已保存到 Hub，Key 不保存在 APP"
                            }.onSuccess { state = AiConnectionState.Success(it) }
                                .onFailure { state = AiConnectionState.Failure(it.message ?: "保存失败") }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("保存") }
                }
                TextButton(onClick = {
                    scope.launch {
                        runCatching { client.deleteConfig(); store.deleteKey(); settings = store.read(); key = "" }
                            .onSuccess { state = AiConnectionState.Success("API Key 已删除") }
                            .onFailure { state = AiConnectionState.Failure(it.message ?: "删除失败") }
                    }
                }) { Text("删除 API Key", color = LabV2.Red) }
                Button(onClick = onChat, enabled = settings.enabled, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Chat, null); Spacer(Modifier.width(6.dp)); Text("打开对话") }
                OutlinedButton(onClick = onUsage, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.DataUsage, null); Spacer(Modifier.width(6.dp)); Text("查看 Token 用量") }
                OutlinedButton(onClick = onWechat, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Forum, null); Spacer(Modifier.width(6.dp)); Text("接入微信 ClawBot") }
            }
        }
    }
}

@Composable
fun AiWechatScreen(context: Context, onBack: () -> Unit) {
    val store = remember { AiSettingsStore(context) }
    val appPrefs = remember { com.labprobe.app.AppPrefs(context) }
    val client = remember { AiApiClient(store, appPrefs.hub, appPrefs.token) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(WeChatBridgeStatus()) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var showInstallConfirm by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<WeChatLoginSession?>(null) }
    var message by remember { mutableStateOf("正在检查 OpenClaw") }

    suspend fun refresh() {
        runCatching { client.wechatStatus() }
            .onSuccess { status = it; message = it.message }
            .onFailure { message = it.message ?: "状态读取失败" }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(login?.loginId) {
        val current = login ?: return@LaunchedEffect
        while (login?.loginId == current.loginId) {
            val result = runCatching { client.waitWechatLogin(current.loginId) }
                .onFailure { message = it.message ?: "扫码状态读取失败" }
                .getOrNull() ?: break
            message = result.message
            if (result.connected || result.alreadyConnected) {
                login = null
                refresh()
                break
            }
            delay(1_000)
        }
    }

    if (showInstallConfirm) AlertDialog(
        onDismissRequest = { if (!installing) showInstallConfirm = false },
        title = { Text("安装微信插件") },
        text = { Text("将在 OpenClaw 主机安装腾讯维护的微信插件、启用独立私聊会话并安全重启 Gateway。微信仍需你本人扫码确认。") },
        confirmButton = {
            Button(enabled = !installing, onClick = {
                installing = true
                scope.launch {
                    runCatching { client.installWechatPlugin() }
                        .onSuccess { message = it; showInstallConfirm = false; refresh() }
                        .onFailure { message = it.message ?: "安装失败" }
                    installing = false
                }
            }) { Text(if (installing) "正在安装" else "确认安装") }
        },
        dismissButton = { TextButton(enabled = !installing, onClick = { showInstallConfirm = false }) { Text("取消") } },
    )

    DetailShell("微信 ClawBot", "OpenClaw 官方微信通道", onBack) {
        Surface(shape = LabV2.CardShape, color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Forum, null, tint = if (status.connected) LabV2.Green else LabV2.Cyan)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (status.connected) "微信已连接" else "微信连接", style = LabTypography.CardTitle)
                        Text(message, style = LabTypography.Supporting)
                    }
                    if (loading || installing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                if (status.version.isNotBlank()) Text("OpenClaw ${status.version}", style = LabTypography.Caption)
                if (status.connected) Text(
                    if (status.notificationTargetConfigured) "每日记录与关注设备事件将同步推送到微信" else "微信对话已连接；如需主动推送，请在 Hub 配置 WECHAT_NOTIFY_TO",
                    style = LabTypography.Caption,
                )
                if (!status.available) {
                    Text("请先在运行 OpenClaw Gateway 的主机执行官方命令：", style = LabTypography.Body)
                    Surface(shape = RoundedCornerShape(10.dp), color = LabV2.FieldSoft) {
                        SelectionContainer { Text(status.installCommand, Modifier.padding(10.dp), style = LabTypography.Caption) }
                    }
                    Text("Hub 不会保存微信 bot token；扫码凭证只保存在 OpenClaw 状态目录。", style = LabTypography.Caption)
                } else if (!status.pluginInstalled) {
                    Button(onClick = { showInstallConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("安装腾讯微信插件") }
                } else if (login == null) {
                    Button(onClick = {
                        loading = true
                        scope.launch {
                            runCatching { client.startWechatLogin() }
                                .onSuccess { login = it; message = it.message }
                                .onFailure { message = it.message ?: "二维码生成失败" }
                            loading = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (status.connected) "重新扫码连接" else "生成微信二维码") }
                }
                OutlinedButton(onClick = { loading = true; scope.launch { refresh() } }, modifier = Modifier.fillMaxWidth()) { Text("刷新状态") }
            }
        }
        login?.let { session ->
            Surface(shape = LabV2.CardShape, color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("使用手机微信扫码", style = LabTypography.CardTitle)
                    WeChatQrCode(session.qrContent)
                    Text("二维码约 ${session.expiresInSeconds / 60} 分钟有效；扫码后请在微信中确认。", style = LabTypography.Caption)
                }
            }
        }
    }
}

@Composable
private fun WeChatQrCode(content: String) {
    val image = remember(content) {
        val size = 720
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }.asImageBitmap()
    }
    Image(image, contentDescription = "微信扫码二维码", modifier = Modifier.size(250.dp).clip(RoundedCornerShape(14.dp)))
}

@Composable
fun AiUsageScreen(context: Context, onBack: () -> Unit) {
    val store = remember { AiSettingsStore(context) }
    val appPrefs = remember { com.labprobe.app.AppPrefs(context) }
    val client = remember { AiApiClient(store, appPrefs.hub, appPrefs.token) }
    var summary by remember { mutableStateOf(AiUsageSummary()) }
    var message by remember { mutableStateOf("正在读取") }
    LaunchedEffect(Unit) {
        runCatching { client.usage() }
            .onSuccess { summary = it; message = "已更新" }
            .onFailure { message = it.message ?: "读取失败" }
    }
    DetailShell("AI 用量", "Token 统计与每日记录 · $message", onBack) {
        Surface(shape = LabV2.CardShape, color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("今日用量", style = LabTypography.CardTitle)
                Text("${summary.todayTotalTokens} Token · ${summary.todayRequests} 次请求", style = LabTypography.AppTitle)
                Divider()
                Text("累计用量", style = LabTypography.CardTitle)
                Text("${summary.totalTokens} Token · ${summary.requests} 次请求", style = LabTypography.Body)
                Text("输入 ${summary.promptTokens} · 输出 ${summary.completionTokens}", style = LabTypography.Supporting)
            }
        }
        Surface(shape = LabV2.CardShape, color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("最近单次任务", style = LabTypography.CardTitle)
                if (summary.recent.isEmpty()) {
                    Text("暂无任务记录", style = LabTypography.Supporting)
                } else {
                    summary.recent.forEachIndexed { index, record ->
                        if (index > 0) Divider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(record.model.ifBlank { record.provider.ifBlank { "AI 请求" } }, style = LabTypography.Body)
                                Text(formatAiUsageTime(record.createdAt), style = LabTypography.Caption)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (record.usageKnown) "${record.totalTokens} Token" else "用量未知", style = LabTypography.Body)
                                Text("输入 ${record.promptTokens} · 输出 ${record.completionTokens}", style = LabTypography.Caption)
                                if (record.status != "completed") Text("失败", color = LabV2.Red, style = LabTypography.Caption)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatAiUsageTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value)
        .atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
}.getOrElse { value.replace('T', ' ').removeSuffix("Z").take(19) }

@Composable
fun AiChatScreen(context: Context, onBack: () -> Unit) {
    val store = remember { AiSettingsStore(context) }
    val appPrefs = remember { com.labprobe.app.AppPrefs(context) }
    val client = remember { AiApiClient(store, appPrefs.hub, appPrefs.token, appPrefs = appPrefs) }
    val localTools = remember { AiLocalToolExecutor(appPrefs) }
    val messages = remember { mutableStateListOf<AiMessage>() }
    var toolHints by remember { mutableStateOf<List<AiToolHint>>(emptyList()) }
    var pendingConfirmation by remember { mutableStateOf<AiToolConfirmation?>(null) }
    var pendingWechatInstall by remember { mutableStateOf(false) }
    var wechatLoginId by remember { mutableStateOf<String?>(null) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    var loadingHistory by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(AiTokenSummary()) }
    val scope = rememberCoroutineScope()
    suspend fun beginWechatLogin() {
        val session = client.startWechatLogin()
        wechatLoginId = session.loginId
        messages += AiMessage(
            role = "assistant",
            content = session.message + "\n此连接操作未调用 AI，不消耗 Token。",
            qrContent = session.qrContent,
        )
    }
    LaunchedEffect(Unit) {
        runCatching { client.latestConversation() }
            .onSuccess { (id, history) ->
                conversationId = id
                messages.clear()
                if (history.isEmpty()) messages += AiMessage("assistant", "你好，我可以帮你查看 Hub 状态、解释网络数据。") else messages.addAll(history)
            }
            .onFailure { if (messages.isEmpty()) messages += AiMessage("assistant", "你好，我可以帮你查看 Hub 状态、解释网络数据。") }
        loadingHistory = false
        runCatching { client.catalog() }.onSuccess { toolHints = it }
    }
    LaunchedEffect(wechatLoginId) {
        val loginId = wechatLoginId ?: return@LaunchedEffect
        while (wechatLoginId == loginId) {
            val result = runCatching { client.waitWechatLogin(loginId) }
                .onFailure { messages += AiMessage("assistant", "微信扫码状态读取失败：${it.message ?: "未知错误"}") }
                .getOrNull() ?: break
            if (result.connected || result.alreadyConnected) {
                messages += AiMessage("assistant", result.message)
                wechatLoginId = null
                break
            }
            delay(1_000)
        }
    }
    LaunchedEffect(Unit) {
        while (loadingHistory) delay(100)
        while (true) {
            runCatching { client.notifications(store.lastNotificationId()) }
                .onSuccess { rows ->
                    rows.forEach { notification ->
                        messages += AiMessage("assistant", "${notification.title}\n${notification.content}")
                    }
                    rows.maxOfOrNull { it.id }?.let(store::saveLastNotificationId)
                }
            delay(15_000)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回", tint = LabV2.Ink) }
            Column(Modifier.weight(1f)) {
                Text("AI 对话", style = LabTypography.PageTitle)
                Text("常用指令会跟随 Hub 能力自动更新", style = LabTypography.Supporting)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            item {
                if (loadingHistory) Text("正在恢复最近对话…", style = LabTypography.Supporting)
                else if (toolHints.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(toolHints, key = { it.id }) { hint ->
                        SuggestionChip(
                            onClick = { input = hint.example },
                            label = { Text(hint.name, maxLines = 1) },
                            icon = if (hint.risk == "write") {
                                { Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp)) }
                            } else null,
                        )
                    }
                }
            }
            items(messages) { message ->
                Surface(shape = RoundedCornerShape(14.dp), color = if (message.role == "user") LabV2.Primary.copy(alpha = .1f) else Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp), horizontalAlignment = if (message.qrContent != null) Alignment.CenterHorizontally else Alignment.Start) {
                        Text(message.content, Modifier.fillMaxWidth(), style = LabTypography.Body)
                        message.qrContent?.let { WeChatQrCode(it) }
                    }
                }
            }
        }
        if (pendingWechatInstall) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFF8E8), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3C969))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确认安装腾讯微信插件", style = LabTypography.CardTitle)
                    Text("将在 OpenClaw 主机安装官方插件并安全重启 Gateway；随后生成二维码，仍需你本人微信扫码确认。", style = LabTypography.Body)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { pendingWechatInstall = false }, enabled = !sending, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(onClick = {
                            sending = true
                            scope.launch {
                                runCatching { client.installWechatPlugin(); beginWechatLogin() }
                                    .onSuccess { pendingWechatInstall = false }
                                    .onFailure { messages += AiMessage("assistant", "微信插件安装失败：${it.message ?: "未知错误"}") }
                                sending = false
                            }
                        }, enabled = !sending, modifier = Modifier.weight(1f)) { Text("确认安装") }
                    }
                }
            }
        }
        pendingConfirmation?.let { confirmation ->
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFF8E8), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3C969))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, null, tint = Color(0xFFB56A00), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(confirmation.title, style = LabTypography.CardTitle)
                    }
                    Text(confirmation.summary, style = LabTypography.Body)
                    Text("确认后仅执行以上操作；确认 5 分钟内有效。", style = LabTypography.Caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = {
                            pendingConfirmation = null
                            messages += AiMessage("assistant", "已取消操作。")
                        }, enabled = !sending, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(onClick = {
                            sending = true
                            scope.launch {
                                runCatching {
                                    client.confirmHubTool(confirmation.confirmationId)
                                    if (confirmation.executor == "app") localTools.execute(confirmation) else "操作已完成"
                                }.onSuccess { messages += AiMessage("assistant", it); pendingConfirmation = null }
                                    .onFailure { messages += AiMessage("assistant", "执行失败：${it.message ?: "未知错误"}") }
                                sending = false
                            }
                        }, enabled = !sending, modifier = Modifier.weight(1f)) { Text("确认执行") }
                    }
                }
            }
        }
        Text("本次任务 Token：${usage.total}（输入 ${usage.prompt} · 输出 ${usage.completion}）", style = LabTypography.Caption)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("输入消息…") }, maxLines = 4)
            IconButton(enabled = input.isNotBlank() && !sending && !loadingHistory && pendingConfirmation == null && !pendingWechatInstall, onClick = {
                val text = input.trim()
                input = ""
                messages += AiMessage("user", text)
                sending = true
                scope.launch {
                    if (isWechatConnectIntent(text)) {
                        runCatching { client.wechatStatus() }
                            .onSuccess { wechat ->
                                when {
                                    !wechat.available -> messages += AiMessage("assistant", "OpenClaw 尚未安装。请在 Gateway 主机执行：\n${wechat.installCommand}")
                                    !wechat.pluginInstalled -> pendingWechatInstall = true
                                    else -> runCatching { beginWechatLogin() }
                                        .onFailure { messages += AiMessage("assistant", "二维码生成失败：${it.message ?: "未知错误"}") }
                                }
                            }
                            .onFailure { messages += AiMessage("assistant", "微信连接状态读取失败：${it.message ?: "未知错误"}") }
                    } else {
                        runCatching { client.chat(messages.toList(), conversationId) }
                            .onSuccess { conversationId = it.conversationId ?: conversationId; messages += AiMessage("assistant", it.content); usage = it.usage; pendingConfirmation = it.confirmation }
                            .onFailure { messages += AiMessage("assistant", "请求失败：${it.message ?: "未知错误"}") }
                    }
                    sending = false
                }
            }) { Icon(Icons.Rounded.Send, "发送", tint = LabV2.Primary) }
        }
    }
}

private fun isWechatConnectIntent(text: String): Boolean {
    val value = text.trim().lowercase()
    val mentionsWechat = listOf("微信", "wechat", "clawbot", "openclaw").any(value::contains)
    val asksToConnect = listOf("接入", "连接", "绑定", "扫码", "二维码", "配置").any(value::contains)
    return mentionsWechat && asksToConnect
}
