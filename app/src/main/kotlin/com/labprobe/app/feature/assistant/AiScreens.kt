package com.labprobe.app.feature.assistant

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.labprobe.app.AppPrefs
import com.labprobe.app.LabTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Assistant-only palette. Do not replace these with default Material colors. */
private object AiTone {
    val Ink = Color(0xFF17342C)
    val Muted = Color(0xFF71857F)
    val Mint = Color(0xFF119A73)
    val MintDark = Color(0xFF087356)
    val MintSoft = Color(0xFFE9F7F1)
    val Field = Color(0xFFF4FAF7)
    val Surface = Color(0xFFFFFEFB)
    val Border = Color(0xFFD5E9E0)
    val Warning = Color(0xFFF2A23E)
    val WarningSoft = Color(0xFFFFF7E8)
    val Danger = Color(0xFFD85C5C)
}

private val AiPanelShape = RoundedCornerShape(24.dp)
private val AiControlShape = RoundedCornerShape(18.dp)
private val AiPillShape = RoundedCornerShape(99.dp)

private fun Modifier.aiTap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(
        enabled = enabled,
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}

@Composable
private fun AiPanel(
    modifier: Modifier = Modifier,
    accent: Color = AiTone.Border,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AiPanelShape,
        color = AiTone.Surface,
        border = BorderStroke(1.dp, accent),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
private fun AiAction(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    tone: Color = AiTone.Mint,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val fill = when {
        !enabled -> AiTone.Field
        primary -> tone
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> AiTone.Muted.copy(alpha = .55f)
        primary -> Color.White
        else -> tone
    }
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(AiControlShape)
            .aiTap(enabled, onClick),
        shape = AiControlShape,
        color = fill,
        border = BorderStroke(1.dp, if (primary) fill else tone.copy(alpha = .42f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            icon?.let {
                Icon(it, null, tint = textColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun AiFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = 4,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = AiTone.Muted, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        Surface(
            shape = AiControlShape,
            color = AiTone.Field,
            border = BorderStroke(1.dp, AiTone.Border),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = if (singleLine) 14.dp else 12.dp)) {
                if (value.isBlank() && placeholder.isNotBlank()) {
                    Text(placeholder, color = AiTone.Muted.copy(alpha = .68f), fontSize = 14.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    singleLine = singleLine,
                    maxLines = maxLines,
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                )
            }
        }
    }
}

@Composable
private fun AiToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 3.dp,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "ai-toggle",
    )
    Surface(
        modifier = Modifier
            .width(50.dp)
            .height(30.dp)
            .clip(AiPillShape)
            .aiTap { onCheckedChange(!checked) },
        shape = AiPillShape,
        color = if (checked) AiTone.Mint else AiTone.Field,
        border = BorderStroke(1.dp, if (checked) AiTone.Mint else AiTone.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.offset(x = knobOffset, y = 3.dp).size(22.dp),
                shape = CircleShape,
                color = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {}
        }
    }
}

@Composable
private fun AiHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(42.dp).clip(CircleShape).aiTap(onClick = onBack),
            shape = CircleShape,
            color = AiTone.Surface,
            border = BorderStroke(1.dp, AiTone.Border),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ArrowBack, "返回", tint = AiTone.Ink) }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = AiTone.Ink, style = LabTypography.PageTitle)
            Text(subtitle, color = AiTone.Muted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AiRobotMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val body = AiTone.Ink
        val eye = AiTone.Mint
        drawLine(eye, Offset(size.width * .5f, 1f), Offset(size.width * .5f, size.height * .19f), 2.1f)
        drawCircle(eye, 2.1f, Offset(size.width * .5f, 1f))
        drawRoundRect(
            body,
            topLeft = Offset(size.width * .06f, size.height * .19f),
            size = Size(size.width * .88f, size.height * .65f),
            cornerRadius = CornerRadius(size.width * .27f),
        )
        drawCircle(eye, 3.1f, Offset(size.width * .36f, size.height * .51f))
        drawCircle(eye, 3.1f, Offset(size.width * .64f, size.height * .51f))
        drawRoundRect(
            Color.White.copy(alpha = .28f),
            topLeft = Offset(size.width * .31f, size.height * .70f),
            size = Size(size.width * .38f, 1.6f),
            cornerRadius = CornerRadius(1f),
        )
    }
}

@Composable
fun AiPetEntry(onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.size(42.dp).clip(CircleShape).aiTap(onClick = onOpen),
        shape = CircleShape,
        color = AiTone.Surface,
        border = BorderStroke(1.dp, AiTone.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) { AiRobotMark(Modifier.fillMaxSize().padding(8.dp)) }
}

/** Edge-docked, half-hidden pet. First tap reveals it; second tap enters chat. */
@Composable
fun AiFloatingPet(onOpen: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val petSize = 42.dp
        val maxTravel = with(density) { (maxHeight - petSize - 24.dp).toPx().coerceAtLeast(0f) }
        var yPx by remember { mutableFloatStateOf(0f) }
        var initialized by remember { mutableStateOf(false) }
        var revealed by remember { mutableStateOf(false) }
        val horizontal by animateDpAsState(
            targetValue = if (revealed) (-4).dp else 22.dp,
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            label = "assistant-edge-slide",
        )
        val scale by animateFloatAsState(
            targetValue = if (revealed) 1f else .88f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "assistant-pet-scale",
        )
        LaunchedEffect(maxTravel) {
            if (!initialized) {
                yPx = maxTravel * .42f
                initialized = true
            } else yPx = yPx.coerceIn(0f, maxTravel)
        }
        LaunchedEffect(revealed) {
            if (revealed) {
                delay(3_800)
                revealed = false
            }
        }
        val y = with(density) { yPx.coerceIn(0f, maxTravel).toDp() }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(20f)
                .offset(x = horizontal, y = y)
                .size(petSize)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(CircleShape)
                .aiTap { if (revealed) onOpen() else revealed = true }
                .pointerInput(maxTravel) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        yPx = (yPx + dragAmount).coerceIn(0f, maxTravel)
                    }
                },
            shape = CircleShape,
            color = AiTone.Surface,
            border = BorderStroke(1.dp, AiTone.Mint.copy(alpha = .58f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) { AiRobotMark(Modifier.fillMaxSize().padding(8.dp)) }
    }
}

@Composable
fun AiSettingsScreen(
    context: Context,
    hubUrl: String,
    hubToken: String,
    onBack: () -> Unit,
    onChat: () -> Unit,
    onUsage: () -> Unit,
    onWechat: () -> Unit,
) {
    val store = remember { AiSettingsStore(context) }
    var settings by remember { mutableStateOf(store.read()) }
    var model by remember { mutableStateOf(settings.model) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var key by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<AiConnectionState>(AiConnectionState.Idle) }
    val scope = rememberCoroutineScope()
    val client = remember(hubUrl, hubToken) { AiApiClient(store, hubUrl, hubToken) }
    val canSave = key.isNotBlank() || settings.hasApiKey

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AiHeader("AI 设置", "连接 DeepSeek 与 Hub", onBack)
        AiPanel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用 AI 助手", style = LabTypography.CardTitle, color = AiTone.Ink)
                        Text("对话与 Token 用量由 Hub 统一保存", color = AiTone.Muted, fontSize = 11.5.sp)
                    }
                    AiToggle(settings.enabled) { settings = settings.copy(enabled = it) }
                }
                AiFormField("模型", model, { model = it }, placeholder = "deepseek-v4-flash")
                AiFormField("DeepSeek API 地址", baseUrl, { baseUrl = it }, placeholder = "https://api.deepseek.com")
                AiFormField(
                    if (settings.hasApiKey) "API Key（已保存，可替换）" else "DeepSeek API Key",
                    key,
                    { key = it },
                    placeholder = "sk-…",
                    password = true,
                )
                Text("密钥只在保存时发送给 Hub；APP 不保存原文。首次配置请先粘贴 Key。", color = AiTone.Muted, fontSize = 11.sp, lineHeight = 16.sp)
                when (val current = state) {
                    AiConnectionState.Testing -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = AiTone.Mint, trackColor = AiTone.MintSoft)
                    is AiConnectionState.Success -> Text(current.message, color = AiTone.MintDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    is AiConnectionState.Failure -> Text(current.message, color = AiTone.Danger, fontSize = 12.sp, lineHeight = 17.sp)
                    AiConnectionState.Idle -> Unit
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    AiAction("保存设置", Modifier.weight(1f), enabled = canSave) {
                        scope.launch {
                            runCatching {
                                val saved = client.saveConfig(settings.copy(model = model, baseUrl = baseUrl), key.takeIf { it.isNotBlank() })
                                store.save(saved)
                                settings = saved
                                key = ""
                                "已保存，API Key 仅由 Hub 加密托管"
                            }.onSuccess { state = AiConnectionState.Success(it) }
                                .onFailure { state = AiConnectionState.Failure(it.message ?: "保存失败") }
                        }
                    }
                    AiAction("保存并测试", Modifier.weight(1f), primary = true, enabled = canSave) {
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
                    }
                }
                if (settings.hasApiKey) {
                    AiAction("删除 API Key", tone = AiTone.Danger, icon = Icons.Rounded.DeleteOutline) {
                        scope.launch {
                            runCatching { client.deleteConfig(); store.deleteKey(); settings = store.read(); key = "" }
                                .onSuccess { state = AiConnectionState.Success("API Key 已删除") }
                                .onFailure { state = AiConnectionState.Failure(it.message ?: "删除失败") }
                        }
                    }
                }
            }
        }
        AiPanel(accent = AiTone.Mint.copy(alpha = .32f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("助手与记录", style = LabTypography.CardTitle, color = AiTone.Ink)
                Text("对话可以查询 Hub 数据；涉及写入的指令会先展示确认内容。", color = AiTone.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                AiAction("打开对话", primary = true, icon = Icons.Rounded.ChatBubbleOutline, enabled = settings.enabled && settings.hasApiKey, onClick = onChat)
                AiAction("查看 Token 用量", icon = Icons.Rounded.DataUsage, onClick = onUsage)
                AiAction("接入微信 ClawBot", icon = Icons.Rounded.Forum, onClick = onWechat)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun AiWechatScreen(context: Context, onBack: () -> Unit) {
    val store = remember { AiSettingsStore(context) }
    val prefs = remember { AppPrefs(context) }
    val client = remember { AiApiClient(store, prefs.hub, prefs.token) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(WeChatBridgeStatus()) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var installConfirmation by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<WeChatLoginSession?>(null) }
    var message by remember { mutableStateOf("正在检查微信连接") }

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

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AiHeader("微信 ClawBot", "官方通道与扫码绑定", onBack)
        AiPanel(accent = if (status.connected) AiTone.Mint.copy(alpha = .42f) else AiTone.Border) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = AiTone.MintSoft, modifier = Modifier.size(40.dp), tonalElevation = 0.dp, shadowElevation = 0.dp) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Forum, null, tint = AiTone.MintDark) }
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (status.connected) "微信已绑定" else "绑定微信", style = LabTypography.CardTitle, color = AiTone.Ink)
                        Text(message, color = AiTone.Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                    }
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = AiTone.Mint, strokeWidth = 2.dp)
                }
                if (status.connected) {
                    Text(
                        if (status.notificationTargetConfigured) "每日记录与关注设备事件会发送到微信。" else "微信已连接；如需主动推送，请在 Hub 配置微信私信接收方。",
                        color = AiTone.Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    if (status.version.isNotBlank()) Text("OpenClaw ${status.version}", color = AiTone.Muted, fontSize = 11.sp)
                } else {
                    when {
                        !status.available -> {
                            Text("微信 ClawBot 需要先在运行 Hub 的主机准备 OpenClaw。准备完成后可在这里生成二维码，扫码凭证仅由官方通道保存。", color = AiTone.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                            if (status.installCommand.isNotBlank()) {
                                Surface(shape = RoundedCornerShape(18.dp), color = AiTone.MintSoft, border = BorderStroke(1.dp, AiTone.Mint.copy(alpha = .35f)), tonalElevation = 0.dp, shadowElevation = 0.dp) {
                                    Text(status.installCommand, Modifier.padding(12.dp), color = AiTone.Ink, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                            }
                        }
                        !status.pluginInstalled -> {
                            Text("已检测到 OpenClaw。准备官方微信通道后即可发出二维码；此操作会在 Hub 主机启用插件并安全重启 Gateway。", color = AiTone.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                            if (installConfirmation) {
                                AiPanel(accent = AiTone.Warning.copy(alpha = .55f)) {
                                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                        Text("确认准备微信通道", style = LabTypography.CardTitle, color = AiTone.Ink)
                                        Text("仅安装腾讯维护的微信插件、启用独立私聊会话并重启 Gateway；仍需你本人微信扫码确认。", color = AiTone.Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                            AiAction("取消", Modifier.weight(1f), tone = AiTone.Danger, enabled = !installing) { installConfirmation = false }
                                            AiAction("确认准备", Modifier.weight(1f), primary = true, enabled = !installing) {
                                                installing = true
                                                scope.launch {
                                                    runCatching { client.installWechatPlugin() }
                                                        .onSuccess { installConfirmation = false; refresh() }
                                                        .onFailure { message = it.message ?: "准备微信通道失败" }
                                                    installing = false
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                AiAction("准备官方微信通道", primary = true, enabled = !installing) { installConfirmation = true }
                            }
                        }
                        login == null -> AiAction(if (status.connected) "重新扫码连接" else "生成微信二维码", primary = true, onClick = {
                            loading = true
                            scope.launch {
                                runCatching { client.startWechatLogin() }
                                    .onSuccess { login = it; message = it.message }
                                    .onFailure { message = it.message ?: "二维码生成失败" }
                                loading = false
                            }
                        })
                    }
                }
                AiAction("刷新状态", icon = Icons.Rounded.Refresh, onClick = { loading = true; scope.launch { refresh() } })
            }
        }
        login?.let { session ->
            AiPanel(accent = AiTone.Mint.copy(alpha = .35f)) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("使用手机微信扫码", style = LabTypography.CardTitle, color = AiTone.Ink)
                    WeChatQrCode(session.qrContent)
                    Text("二维码约 ${session.expiresInSeconds / 60} 分钟有效，扫码后请在微信中确认。", color = AiTone.Muted, fontSize = 11.sp)
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
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, AiTone.Border), tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Image(image, contentDescription = "微信扫码二维码", modifier = Modifier.size(238.dp).padding(10.dp))
    }
}

@Composable
fun AiUsageScreen(context: Context, onBack: () -> Unit) {
    val store = remember { AiSettingsStore(context) }
    val prefs = remember { AppPrefs(context) }
    val client = remember { AiApiClient(store, prefs.hub, prefs.token) }
    var summary by remember { mutableStateOf(AiUsageSummary()) }
    var message by remember { mutableStateOf("正在读取") }
    LaunchedEffect(Unit) {
        runCatching { client.usage() }
            .onSuccess { summary = it; message = "已更新" }
            .onFailure { message = it.message ?: "读取失败" }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AiHeader("Token 用量", "任务级统计 · $message", onBack)
        AiPanel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("今日", color = AiTone.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("${summary.todayTotalTokens} Token · ${summary.todayRequests} 次任务", color = AiTone.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                HorizontalDivider(color = AiTone.Border)
                Text("累计 ${summary.totalTokens} Token · ${summary.requests} 次任务", color = AiTone.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("输入 ${summary.promptTokens} · 输出 ${summary.completionTokens}", color = AiTone.Muted, fontSize = 11.5.sp)
            }
        }
        AiPanel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("最近单次任务", style = LabTypography.CardTitle, color = AiTone.Ink)
                if (summary.recent.isEmpty()) Text("暂时还没有已完成的 AI 任务。", color = AiTone.Muted, fontSize = 12.sp)
                summary.recent.forEachIndexed { index, record ->
                    if (index > 0) HorizontalDivider(color = AiTone.Border.copy(alpha = .72f))
                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${record.totalTokens} Token · ${if (record.status == "completed") "已完成" else "未完成"}", color = if (record.status == "completed") AiTone.Ink else AiTone.Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${record.model} · 输入 ${record.promptTokens} · 输出 ${record.completionTokens}", color = AiTone.Muted, fontSize = 11.sp)
                        Text(formatAiUsageTime(record.createdAt), color = AiTone.Muted.copy(alpha = .78f), fontSize = 10.5.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatAiUsageTime(value: String): String = value
    .replace("T", " ")
    .substringBefore("+")
    .substringBefore("Z")
    .take(19)
    .ifBlank { "时间未知" }

@Composable
private fun AiHintChip(hint: AiToolHint, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(AiPillShape).aiTap(onClick = onClick),
        shape = AiPillShape,
        color = AiTone.Surface,
        border = BorderStroke(1.dp, if (hint.risk == "write") AiTone.Warning.copy(alpha = .5f) else AiTone.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hint.risk == "write") {
                Icon(Icons.Rounded.Lock, null, tint = AiTone.Warning, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(hint.name, color = AiTone.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun AiChatBubble(message: AiMessage) {
    val user = message.role == "user"
    AiPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = if (user) AiTone.Mint.copy(alpha = .32f) else AiTone.Border,
    ) {
        Column(
            Modifier.fillMaxWidth().background(if (user) AiTone.MintSoft else AiTone.Surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            horizontalAlignment = if (message.qrContent != null) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(message.content, Modifier.fillMaxWidth(), color = AiTone.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
            message.qrContent?.let { WeChatQrCode(it) }
        }
    }
}

@Composable
fun AiChatScreen(context: Context, onBack: () -> Unit) {
    val store = remember { AiSettingsStore(context) }
    val prefs = remember { AppPrefs(context) }
    val client = remember { AiApiClient(store, prefs.hub, prefs.token, appPrefs = prefs) }
    val localTools = remember { AiLocalToolExecutor(prefs) }
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
        messages += AiMessage("assistant", session.message + "\n此连接操作不调用 AI，不消耗 Token。", qrContent = session.qrContent)
    }

    suspend fun startWechatFlow() {
        val status = client.wechatStatus()
        when {
            !status.available -> {
                val command = status.installCommand.takeIf { it.isNotBlank() } ?: "请在 Hub 主机安装 OpenClaw"
                messages += AiMessage("assistant", "需要先在运行 Hub 的主机准备 OpenClaw。\n$command\n完成后再说“接入微信”，我会继续生成二维码。")
            }
            !status.pluginInstalled -> {
                pendingWechatInstall = true
                messages += AiMessage("assistant", "微信官方插件尚未准备好。已在下方列出确认操作；确认后由 Hub 配置，随后继续扫码。")
            }
            else -> beginWechatLogin()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { client.latestConversation() }
            .onSuccess { (id, history) ->
                conversationId = id
                messages.clear()
                if (history.isEmpty()) messages += AiMessage("assistant", "你好，我可以查询 Hub 状态、设备与网络数据；涉及变更时会先请你确认。") else messages.addAll(history)
            }
            .onFailure { if (messages.isEmpty()) messages += AiMessage("assistant", "你好，我可以查询 Hub 状态、设备与网络数据；涉及变更时会先请你确认。") }
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
                    rows.forEach { messages += AiMessage("assistant", "${it.title}\n${it.content}") }
                    rows.maxOfOrNull { it.id }?.let(store::saveLastNotificationId)
                }
            delay(15_000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AiHeader("AI 对话", "常用指令随 Hub 能力更新", onBack)
        if (toolHints.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 4.dp)) {
                items(toolHints, key = { it.id }) { hint -> AiHintChip(hint) { input = hint.example } }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            if (loadingHistory) item { Text("正在恢复最近对话…", color = AiTone.Muted, fontSize = 12.sp) }
            items(messages) { message -> AiChatBubble(message) }
            if (pendingWechatInstall) {
                item {
                    AiPanel(accent = AiTone.Warning.copy(alpha = .55f)) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Security, null, tint = AiTone.Warning, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("确认准备微信通道", style = LabTypography.CardTitle, color = AiTone.Ink)
                            }
                            Text("将在 Hub 主机安装腾讯维护的微信插件、启用独立私聊会话并安全重启 Gateway；仍需你本人微信扫码确认。", color = AiTone.Ink, fontSize = 12.5.sp, lineHeight = 18.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                AiAction("取消", Modifier.weight(1f), tone = AiTone.Danger, enabled = !sending) {
                                    pendingWechatInstall = false
                                    messages += AiMessage("assistant", "已取消微信通道准备。")
                                }
                                AiAction("确认准备", Modifier.weight(1f), primary = true, enabled = !sending) {
                                    sending = true
                                    scope.launch {
                                        runCatching { client.installWechatPlugin(); beginWechatLogin() }
                                            .onSuccess { pendingWechatInstall = false }
                                            .onFailure { messages += AiMessage("assistant", "微信通道准备失败：${it.message ?: "未知错误"}") }
                                        sending = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            pendingConfirmation?.let { confirmation ->
                item {
                    AiPanel(accent = AiTone.Warning.copy(alpha = .55f)) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Security, null, tint = AiTone.Warning, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(confirmation.title, style = LabTypography.CardTitle, color = AiTone.Ink)
                            }
                            Text(confirmation.summary, color = AiTone.Ink, fontSize = 12.5.sp, lineHeight = 18.sp)
                            Text("确认后仅执行以上操作；确认有效期为 5 分钟。", color = AiTone.Muted, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                AiAction("取消", Modifier.weight(1f), tone = AiTone.Danger, enabled = !sending) {
                                    pendingConfirmation = null
                                    messages += AiMessage("assistant", "已取消操作。")
                                }
                                AiAction("确认执行", Modifier.weight(1f), primary = true, enabled = !sending) {
                                    sending = true
                                    scope.launch {
                                        runCatching {
                                            client.confirmHubTool(confirmation.confirmationId)
                                            if (confirmation.executor == "app") localTools.execute(confirmation) else "操作已完成"
                                        }.onSuccess { messages += AiMessage("assistant", it); pendingConfirmation = null }
                                            .onFailure { messages += AiMessage("assistant", "执行失败：${it.message ?: "未知错误"}") }
                                        sending = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("本次任务 Token：${usage.total}（输入 ${usage.prompt} · 输出 ${usage.completion}）", color = AiTone.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AiFormField("", input, { input = it }, Modifier.weight(1f), placeholder = "输入消息…", singleLine = false, maxLines = 4)
            Surface(
                modifier = Modifier.size(50.dp).clip(CircleShape).aiTap(
                    enabled = input.isNotBlank() && !sending && !loadingHistory && pendingConfirmation == null && !pendingWechatInstall,
                ) {
                    val text = input.trim()
                    input = ""
                    messages += AiMessage("user", text)
                    sending = true
                    scope.launch {
                        if (isWechatConnectIntent(text)) {
                            runCatching { startWechatFlow() }
                                .onFailure { messages += AiMessage("assistant", "二维码生成失败：${it.message ?: "未知错误"}") }
                        } else {
                            runCatching { client.chat(messages.toList(), conversationId) }
                                .onSuccess {
                                    conversationId = it.conversationId ?: conversationId
                                    messages += AiMessage("assistant", it.content)
                                    usage = it.usage
                                    pendingConfirmation = it.confirmation
                                }
                                .onFailure { messages += AiMessage("assistant", "请求失败：${it.message ?: "未知错误"}") }
                        }
                        sending = false
                    }
                },
                shape = CircleShape,
                color = AiTone.Mint,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Send, "发送", tint = Color.White) }
            }
        }
    }
}

private fun isWechatConnectIntent(text: String): Boolean {
    val value = text.trim().lowercase()
    val mentionsWechat = listOf("微信", "wechat", "clawbot", "openclaw").any(value::contains)
    val asksToConnect = listOf("接入", "连接", "绑定", "扫码", "二维码", "配置").any(value::contains)
    return mentionsWechat && asksToConnect
}
