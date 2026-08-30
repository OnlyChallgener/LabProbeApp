package com.labprobe.app.feature.assistant

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.labprobe.app.AppPrefs
import com.labprobe.app.LabTypography
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

private val AI_BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

private data class AiProviderPreset(val label: String, val model: String, val baseUrl: String)

/** One-tap fills for common OpenAI-compatible providers; fields stay editable. */
private val AI_PROVIDER_PRESETS = listOf(
    AiProviderPreset("DeepSeek", "deepseek-v4-flash", "https://api.deepseek.com"),
    AiProviderPreset("智谱 GLM", "glm-4.7-flash", "https://open.bigmodel.cn/api/paas/v4"),
    AiProviderPreset("阿里千问", "qwen3.6-flash", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    AiProviderPreset("Gemini", "gemini-3.6-flash", "https://generativelanguage.googleapis.com/v1beta/openai"),
    AiProviderPreset("小米 MiMo", "mimo-v2.5", "https://api.xiaomimimo.com/v1"),
    AiProviderPreset("腾讯混元", "hunyuan-turbos", "https://tokenhub.tencentmaas.com/v1"),
)

private const val AI_GREETING = "你好，我可以查询设备与网络状态，也能在确认后帮你控制端口映射、STUN 穿透或升级 Agent。"

/** Process-lifetime chat state: reopening the screen is instant, no reload flash. */
object AiChatSession {
    val messages = mutableStateListOf<AiMessage>()
    var conversationId: String? = null
    var loaded: Boolean = false
    var toolHints: List<AiToolHint> = emptyList()
}

private val AiPanelShape = RoundedCornerShape(24.dp)
private val AiControlShape = RoundedCornerShape(18.dp)
private val AiPillShape = RoundedCornerShape(99.dp)

private data class AiHistoryDay(
    val date: LocalDate,
    val label: String,
    val conversations: List<AiConversation>,
)

/** The Hub writes ISO UTC timestamps; all AI history is grouped by Beijing day. */
private fun aiHistoryDate(value: String): LocalDate {
    val raw = value.trim()
    return runCatching { OffsetDateTime.parse(raw).toInstant().atZone(AI_BEIJING_ZONE).toLocalDate() }
        .recoverCatching { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toLocalDate() }
        .recoverCatching { LocalDate.parse(raw.take(10)) }
        .getOrDefault(LocalDate.MIN)
}

private fun aiHistoryDays(conversations: List<AiConversation>): List<AiHistoryDay> {
    val today = LocalDate.now(AI_BEIJING_ZONE)
    return conversations.groupBy { aiHistoryDate(it.updatedAt) }
        .toList()
        .sortedByDescending { it.first }
        .map { (date, rows) ->
            val label = when (date) {
                LocalDate.MIN -> "更早"
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> date.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
            }
            AiHistoryDay(date, label, rows)
        }
}

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
    compact: Boolean = false,
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
            .heightIn(min = if (compact) 40.dp else 48.dp)
            .clip(AiControlShape)
            .aiTap(enabled, onClick),
        shape = AiControlShape,
        color = fill,
        border = BorderStroke(1.dp, if (primary) fill else tone.copy(alpha = .42f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxSize().padding(
                horizontal = if (compact) 12.dp else 14.dp,
                vertical = if (compact) 7.dp else 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            icon?.let {
                Icon(it, null, tint = textColor, modifier = Modifier.size(if (compact) 15.dp else 18.dp))
                Spacer(Modifier.width(if (compact) 5.dp else 7.dp))
            }
            Text(
                label,
                color = textColor,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
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
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) {
            Text(label, color = AiTone.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
        Surface(
            shape = AiControlShape,
            color = if (focused) Color.White else AiTone.Field,
            border = BorderStroke(1.dp, if (focused) AiTone.Mint.copy(alpha = .6f) else AiTone.Border),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (singleLine) 10.dp else 9.dp)) {
                if (value.isBlank() && placeholder.isNotBlank()) {
                    Text(placeholder, color = AiTone.Muted.copy(alpha = .68f), fontSize = 13.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    textStyle = TextStyle(
                        color = AiTone.Ink, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, lineHeight = 18.sp,
                    ),
                    singleLine = singleLine,
                    maxLines = maxLines,
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    cursorBrush = SolidColor(AiTone.Mint),
                )
            }
        }
    }
}

/** Chat input with mint focus ring, send-key support and no label gutter. */
@Composable
private fun AiChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (focused) Color.White else AiTone.Field,
        border = BorderStroke(1.dp, if (focused) AiTone.Mint.copy(alpha = .6f) else AiTone.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (value.isBlank()) {
                Text("输入消息…", color = AiTone.Muted.copy(alpha = .68f), fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                enabled = enabled,
                textStyle = TextStyle(color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
                cursorBrush = SolidColor(AiTone.Mint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun AiToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, compact: Boolean = false) {
    val trackWidth = if (compact) 40.dp else 50.dp
    val knobSize = if (compact) 18.dp else 22.dp
    val knobTravel = if (compact) 19.dp else 23.dp
    val knobOffset by animateDpAsState(
        targetValue = if (checked) knobTravel else 3.dp,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "ai-toggle",
    )
    Surface(
        modifier = Modifier
            .width(trackWidth)
            .height(if (compact) 24.dp else 30.dp)
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
                modifier = Modifier
                    .offset(x = knobOffset, y = if (compact) 2.dp else 3.dp)
                    .size(knobSize),
                shape = CircleShape,
                color = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {}
        }
    }
}

@Composable
private fun AiHeader(title: String, subtitle: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
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
        trailing?.let {
            Spacer(Modifier.weight(1f))
            it()
        }
    }
}

@Composable
private fun AiRobotMark(modifier: Modifier = Modifier, thinking: Boolean = false) {
    val eyeAlpha: Float
    if (thinking) {
        val blink = rememberInfiniteTransition(label = "ai-robot-think")
        eyeAlpha = blink.animateFloat(
            initialValue = .3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(560, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "ai-robot-blink",
        ).value
    } else {
        eyeAlpha = 1f
    }
    Canvas(modifier) {
        val body = AiTone.Ink
        val eye = AiTone.Mint.copy(alpha = eyeAlpha)
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
fun AiFloatingPet(visible: Boolean = true, onOpen: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val petSize = 42.dp
        val maxTravelX = with(density) { (maxWidth - petSize).toPx().coerceAtLeast(0f) }
        val maxTravelY = with(density) { (maxHeight - petSize - 24.dp).toPx().coerceAtLeast(0f) }
        val revealedInsetPx = with(density) { 4.dp.toPx() }
        val hiddenInsetPx = with(density) { 22.dp.toPx() }
        var xPx by remember { mutableFloatStateOf(0f) }
        var yPx by remember { mutableFloatStateOf(0f) }
        var initialized by remember { mutableStateOf(false) }
        var revealed by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }
        var dockedRight by remember { mutableStateOf(true) }
        val dockedXPx = when {
            dockedRight && revealed -> maxTravelX - revealedInsetPx
            dockedRight -> maxTravelX + hiddenInsetPx
            revealed -> revealedInsetPx
            else -> -hiddenInsetPx
        }
        val horizontalPx by animateFloatAsState(
            targetValue = if (dragging) xPx else dockedXPx,
            animationSpec = if (dragging) snap() else tween(240, easing = FastOutSlowInEasing),
            label = "assistant-edge-slide",
        )
        val scale by animateFloatAsState(
            targetValue = if (revealed) 1f else .88f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "assistant-pet-scale",
        )
        LaunchedEffect(maxTravelX, maxTravelY) {
            if (!initialized) {
                xPx = maxTravelX
                yPx = maxTravelY * .42f
                initialized = true
            } else {
                xPx = xPx.coerceIn(0f, maxTravelX)
                yPx = yPx.coerceIn(0f, maxTravelY)
            }
        }
        LaunchedEffect(revealed) {
            if (revealed) {
                delay(3_800)
                revealed = false
            }
        }
        val x = with(density) { horizontalPx.toDp() }
        val y = with(density) { yPx.coerceIn(0f, maxTravelY).toDp() }
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(20f)
                .offset(x = x, y = y)
                .size(petSize)
                .graphicsLayer(scaleX = scale, scaleY = scale, alpha = if (visible) 1f else 0f)
                .clip(CircleShape)
                .aiTap(enabled = visible) { if (revealed) onOpen() else revealed = true }
                .pointerInput(visible, maxTravelX, maxTravelY) {
                    if (visible) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                revealed = true
                                xPx = if (dockedRight) maxTravelX else 0f
                            },
                            onDragEnd = {
                                dockedRight = xPx >= maxTravelX / 2f
                                dragging = false
                                revealed = true
                            },
                            onDragCancel = {
                                dockedRight = xPx >= maxTravelX / 2f
                                dragging = false
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            xPx = (xPx + dragAmount.x).coerceIn(0f, maxTravelX)
                            yPx = (yPx + dragAmount.y).coerceIn(0f, maxTravelY)
                        }
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
) {
    val store = remember { AiSettingsStore(context) }
    var settings by remember { mutableStateOf(store.read()) }
    var configs by remember { mutableStateOf<List<AiProviderConfig>>(emptyList()) }
    var loadingConfigs by remember { mutableStateOf(true) }
    var configsError by remember { mutableStateOf<String?>(null) }
    var configLoadGeneration by remember { mutableStateOf(0L) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var configName by remember { mutableStateOf("") }
    var configProvider by remember { mutableStateOf("openai_compatible") }
    var model by remember { mutableStateOf(settings.model) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var tokenQuota by remember { mutableStateOf("") }
    var configEnabled by remember { mutableStateOf(true) }
    var configHasKey by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<AiConnectionState>(AiConnectionState.Idle) }
    val scope = rememberCoroutineScope()
    val client = remember(hubUrl, hubToken) { AiApiClient(store, hubUrl, hubToken) }
    val editing = editingId != null
    val canSave = editing && model.isNotBlank() && baseUrl.isNotBlank() && (key.isNotBlank() || configHasKey)
    val beginEdit: (AiProviderConfig) -> Unit = { config ->
        editingId = config.id
        configName = config.name
        configProvider = config.provider
        model = config.model
        baseUrl = config.baseUrl
        tokenQuota = config.tokenQuota?.toString().orEmpty()
        configEnabled = config.enabled
        configHasKey = config.hasApiKey
        key = ""
        state = AiConnectionState.Idle
    }
    val syncLocalSettings: (List<AiProviderConfig>) -> Unit = { rows ->
        val active = rows.firstOrNull { it.enabled && it.hasApiKey } ?: rows.firstOrNull()
        val local = AiSettings(
            enabled = rows.any { it.enabled && it.hasApiKey },
            model = active?.model ?: settings.model,
            baseUrl = active?.baseUrl ?: settings.baseUrl,
            hasApiKey = rows.any { it.hasApiKey },
        )
        store.save(local)
        settings = local
    }

    suspend fun loadConfigs(clearExisting: Boolean = false) {
        configLoadGeneration += 1
        val generation = configLoadGeneration
        loadingConfigs = true
        configsError = null
        if (clearExisting) configs = emptyList()
        try {
            val bundle = client.configs()
            if (generation != configLoadGeneration) return
            configs = bundle.configs
            syncLocalSettings(bundle.configs)
            state = AiConnectionState.Idle
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation != configLoadGeneration) return
            // 读取失败表示“无法确认”，不能据此断言 Hub 中没有配置或配置仍然存在。
            configsError = error.message ?: "读取配置失败"
            state = AiConnectionState.Failure(error.message ?: "读取配置失败")
        } finally {
            if (generation == configLoadGeneration) loadingConfigs = false
        }
    }
    LaunchedEffect(hubUrl, hubToken) { loadConfigs(clearExisting = true) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AiHeader("AI 设置", "连接大模型与 Hub", onBack)
        AiPanel {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("API 配置", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("使用第一个启用的配置；不可用时对话中会提醒你切换", color = AiTone.Muted, fontSize = 10.5.sp)
                    }
                    AiAction("添加", modifier = Modifier.width(64.dp), primary = true, compact = true) {
                        beginEdit(AiProviderConfig(name = "新配置", hasApiKey = false))
                    }
                }
                when {
                    loadingConfigs -> LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(4.dp).clip(AiPillShape),
                        color = AiTone.Mint, trackColor = AiTone.MintSoft,
                    )
                    configs.isNotEmpty() && configsError == null -> configs.forEachIndexed { index, config ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (config.enabled) AiTone.Field else AiTone.Surface,
                            border = BorderStroke(1.dp, if (config.enabled) AiTone.Mint.copy(alpha = .35f) else AiTone.Border),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(22.dp).clip(CircleShape).background(AiTone.MintSoft),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("${index + 1}", color = AiTone.MintDark, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            config.name.ifBlank { config.model },
                                            color = AiTone.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            listOf(config.model, aiUrlHost(config.baseUrl)).filter { it.isNotBlank() }.joinToString(" · "),
                                            color = AiTone.Muted, fontSize = 10.5.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    AiToggle(
                                        checked = config.enabled,
                                        compact = true,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                runCatching { client.saveProviderConfig(config.copy(enabled = enabled)) }
                                                    .onSuccess { saved ->
                                                        configs = configs.map { if (it.id == config.id) saved else it }
                                                        syncLocalSettings(configs)
                                                    }
                                                    .onFailure { state = AiConnectionState.Failure(it.message ?: "更新失败") }
                                            }
                                        },
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        config.tokenQuota?.let { "额度 ${compactTokensLong(it)} Token" } ?: "额度未设置",
                                        color = AiTone.Muted,
                                        fontSize = 10.5.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("编辑", color = AiTone.MintDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.aiTap { beginEdit(config) }.padding(6.dp))
                                    Text("删除", color = AiTone.Danger, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.aiTap {
                                        scope.launch {
                                            runCatching {
                                                if (config.id == "legacy") client.deleteConfig() else client.deleteProviderConfig(config.id)
                                            }.onSuccess {
                                                configs = configs.filterNot { it.id == config.id }
                                                syncLocalSettings(configs)
                                                if (editingId == config.id) editingId = null
                                                state = AiConnectionState.Success("配置已删除")
                                            }.onFailure { state = AiConnectionState.Failure(it.message ?: "删除失败") }
                                        }
                                    }.padding(6.dp))
                                }
                            }
                        }
                    }
                    configsError != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            "配置读取失败：$configsError\n（未能确认当前配置；本次读取没有修改 Hub 数据）",
                            color = AiTone.Danger, fontSize = 11.sp, lineHeight = 16.sp,
                        )
                        AiAction("重新读取", modifier = Modifier.fillMaxWidth(), primary = true, compact = true) {
                            scope.launch { loadConfigs() }
                        }
                    }
                    else -> Text("还没有 API 配置，点右上角“添加”。", color = AiTone.Muted, fontSize = 11.sp)
                }
            }
        }
        if (editing) AiPanel(accent = AiTone.Mint.copy(alpha = .32f)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (editingId.isNullOrBlank()) "添加 API" else "编辑 API",
                        color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text("取消", color = AiTone.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.aiTap { editingId = null }.padding(6.dp))
                }
                Text("常用服务商（点选自动填充，可再手动修改）", color = AiTone.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(AI_PROVIDER_PRESETS) { preset ->
                        val active = baseUrl.equals(preset.baseUrl, ignoreCase = true)
                        Surface(
                            modifier = Modifier.clip(AiPillShape).aiTap {
                                configName = preset.label
                                model = preset.model
                                baseUrl = preset.baseUrl
                            },
                            shape = AiPillShape,
                            color = if (active) AiTone.MintSoft else AiTone.Surface,
                            border = BorderStroke(1.dp, if (active) AiTone.Mint.copy(alpha = .7f) else AiTone.Border),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            Text(preset.label, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), color = AiTone.Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                AiFormField("配置名称", configName, { configName = it }, placeholder = "例如：主力 API")
                AiFormField("模型", model, { model = it }, placeholder = "deepseek-v4-flash")
                AiFormField("API 地址（OpenAI 兼容）", baseUrl, { baseUrl = it }, placeholder = "https://api.deepseek.com")
                AiFormField("模型额度 Token（可不填）", tokenQuota, { value -> tokenQuota = value.filter(Char::isDigit) }, placeholder = "例如 1000000")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用此配置", color = AiTone.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("关闭后 Hub 不会选择这一项", color = AiTone.Muted, fontSize = 10.sp)
                    }
                    AiToggle(checked = configEnabled, compact = true, onCheckedChange = { configEnabled = it })
                }
                AiFormField(
                    if (configHasKey) "API Key（已保存，可替换）" else "API Key",
                    key,
                    { key = it },
                    placeholder = "sk-…",
                    password = true,
                )
                Text("密钥只在保存时发送给 Hub；APP 不保存原文。首次配置请先粘贴 Key。", color = AiTone.Muted, fontSize = 10.5.sp, lineHeight = 15.sp)
                when (val current = state) {
                    AiConnectionState.Testing -> LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(4.dp).clip(AiPillShape),
                        color = AiTone.Mint, trackColor = AiTone.MintSoft,
                    )
                    is AiConnectionState.Success -> Surface(
                        shape = AiControlShape,
                        color = AiTone.MintSoft,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(current.message, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), color = AiTone.MintDark, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                    is AiConnectionState.Failure -> Surface(
                        shape = AiControlShape,
                        color = AiTone.Danger.copy(alpha = .08f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(current.message, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), color = AiTone.Danger, fontSize = 11.5.sp, lineHeight = 16.sp)
                    }
                    AiConnectionState.Idle -> Unit
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiAction("保存", Modifier.weight(1f), enabled = canSave, compact = true) {
                        scope.launch {
                            runCatching {
                                val draft = AiProviderConfig(
                                    id = editingId.orEmpty(), name = configName.trim().ifBlank { model.trim() },
                                    provider = configProvider,
                                    enabled = configEnabled, model = model.trim(), baseUrl = baseUrl.trim(),
                                    hasApiKey = configHasKey, tokenQuota = tokenQuota.toLongOrNull()?.takeIf { it > 0 },
                                )
                                val saved = client.saveProviderConfig(draft, key.takeIf { it.isNotBlank() })
                                configs = if (draft.id.isBlank()) configs + saved else configs.map { if (it.id == draft.id) saved else it }
                                syncLocalSettings(configs)
                                editingId = null
                                key = ""
                                "已保存，API Key 仅由 Hub 加密托管"
                            }.onSuccess { state = AiConnectionState.Success(it) }
                                .onFailure { state = AiConnectionState.Failure(it.message ?: "保存失败") }
                        }
                    }
                    AiAction("保存并测试", Modifier.weight(1f), primary = true, enabled = canSave, compact = true) {
                        state = AiConnectionState.Testing
                        scope.launch {
                            runCatching {
                                val draft = AiProviderConfig(
                                    id = editingId.orEmpty(), name = configName.trim().ifBlank { model.trim() },
                                    provider = configProvider,
                                    enabled = configEnabled, model = model.trim(), baseUrl = baseUrl.trim(),
                                    hasApiKey = configHasKey, tokenQuota = tokenQuota.toLongOrNull()?.takeIf { it > 0 },
                                )
                                val saved = client.saveProviderConfig(draft, key.takeIf { it.isNotBlank() })
                                configs = if (draft.id.isBlank()) configs + saved else configs.map { if (it.id == draft.id) saved else it }
                                syncLocalSettings(configs)
                                key = ""
                                editingId = saved.id
                                client.testConnection(saved.id.takeUnless { it == "legacy" })
                            }.onSuccess { state = AiConnectionState.Success(it) }
                                .onFailure { state = AiConnectionState.Failure(it.message ?: "连接失败") }
                        }
                    }
                }
            }
        }
        AiPanel(accent = AiTone.Mint.copy(alpha = .32f)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AiRobotMark(Modifier.size(20.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("助手与记录", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Text("对话可查询 Hub 数据；写入指令需先在页面内确认。", color = AiTone.Muted, fontSize = 10.5.sp, lineHeight = 15.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiAction("打开对话", Modifier.weight(1f), primary = true, icon = Icons.Rounded.ChatBubbleOutline, compact = true, enabled = settings.enabled && settings.hasApiKey, onClick = onChat)
                    AiAction("Token 用量", Modifier.weight(1f), icon = Icons.Rounded.DataUsage, compact = true, onClick = onUsage)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}


@Composable
fun AiUsageScreen(context: Context, onBack: () -> Unit) {
    val store = remember { AiSettingsStore(context) }
    val prefs = remember { AppPrefs(context) }
    val client = remember { AiApiClient(store, prefs.hub, prefs.token) }
    var summary by remember { mutableStateOf(AiUsageSummary()) }
    var message by remember { mutableStateOf("正在读取") }
    var latestDayExpanded by remember { mutableStateOf(true) }
    var expandedOlderDays by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        runCatching { client.usage() }
            .onSuccess { summary = it; message = "已更新" }
            .onFailure { message = it.message ?: "读取失败" }
    }
    val daily = summary.daily
    val cacheHit = daily.sumOf { it.cacheHitTokens }
    val cacheMiss = daily.sumOf { it.cacheMissTokens }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AiHeader("Token 用量", "按北京时间自然日 · $message", onBack)
        AiPanel {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(AiTone.Mint))
                    Spacer(Modifier.width(5.dp))
                    Text("今日", color = AiTone.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${todayBeijingLabel()} · ${summary.todayRequests} 次任务",
                        color = AiTone.Muted, fontSize = 10.5.sp,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatInt(summary.todayTotalTokens), color = AiTone.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(4.dp))
                    Text("Token", color = AiTone.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 1.dp))
                }
                if (summary.todayTotalTokens == 0 && daily.isNotEmpty()) {
                    val yesterdaysDate = runCatching { java.time.LocalDate.now(AI_BEIJING_ZONE).minusDays(1).toString() }.getOrDefault("")
                    val yesterday = daily.firstOrNull { it.date == yesterdaysDate && it.totalTokens > 0 }
                    if (yesterday != null) {
                        Text("昨日 ${compactTokens(yesterday.totalTokens)} Token · ${yesterday.requests} 次任务", color = AiTone.Muted, fontSize = 10.5.sp)
                    }
                }
                HorizontalDivider(color = AiTone.Border)
                Row(Modifier.fillMaxWidth()) {
                    AiStatColumn("累计 Token", formatInt(summary.totalTokens), Modifier.weight(1.2f))
                    AiStatColumn("累计任务", "${summary.requests} 次", Modifier.weight(1f))
                    AiStatColumn("对话存储", formatAiStorage(summary.storageBytes), Modifier.weight(1f))
                }
                Text(
                    "输入 ${formatInt(summary.promptTokens)} · 输出 ${formatInt(summary.completionTokens)} · 消息 ${summary.storageMessages} 条",
                    color = AiTone.Muted, fontSize = 10.sp,
                )
                if (summary.storageBytes > 8L * 1024 * 1024) {
                    Text("存储已超 8MB，建议在 Hub 清理历史对话", color = AiTone.Warning, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        AiPanel {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Token 用量", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("自昨日起 14 天", color = AiTone.Muted, fontSize = 10.sp)
                }
                if (daily.isEmpty()) {
                    Text("还没有用量数据。", color = AiTone.Muted, fontSize = 12.sp)
                } else {
                    val hasCacheBreakdown = daily.any { it.cacheHitTokens + it.cacheMissTokens > 0 }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (hasCacheBreakdown) {
                            AiUsageLegend(Color(0xFFBCEAD9), "输入（缓存）")
                            AiUsageLegend(Color(0xFF68C6A6), "输入")
                        } else {
                            AiUsageLegend(Color(0xFF68C6A6), "输入")
                        }
                        AiUsageLegend(AiTone.MintDark, "输出")
                        Spacer(Modifier.weight(1f))
                        if (cacheHit + cacheMiss > 0) {
                            Text("缓存命中 ${cacheHit * 100 / (cacheHit + cacheMiss)}%", color = AiTone.MintDark, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    AiDailyUsageBars(daily)
                    Spacer(Modifier.height(2.dp))
                    val slots = aiTrendSlots(daily)
                    Text("点击柱子查看当日明细", color = AiTone.Muted.copy(alpha = .8f), fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth()) {
                        Text(slots.firstOrNull()?.date?.takeLast(5).orEmpty(), color = AiTone.Muted, fontSize = 10.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text("峰值 ${compactTokens(daily.maxOfOrNull { it.totalTokens } ?: 0)}", color = AiTone.Muted, fontSize = 10.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text(slots.lastOrNull()?.date?.takeLast(5).orEmpty(), color = AiTone.Muted, fontSize = 10.5.sp)
                    }
                }
            }
        }
        AiPanel {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("模型分布（近 14 天）", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val modelTotals = daily.flatMap { it.models.entries }
                    .groupingBy { it.key }
                    .fold(0L) { acc, entry -> acc + entry.value }
                    .filterValues { it > 0 }
                if (modelTotals.isEmpty() && summary.configUsage.isEmpty()) {
                    Text("暂无模型维度数据。", color = AiTone.Muted, fontSize = 12.sp)
                } else {
                    if (modelTotals.isNotEmpty()) AiModelDonut(modelTotals)
                    if (summary.configUsage.isNotEmpty()) {
                        HorizontalDivider(color = AiTone.Border.copy(alpha = .72f))
                        Text("API 配置额度（累计）", color = AiTone.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "上方分布按模型统计近 14 天；下方为每个 API 配置的累计用量与设置额度的对比。",
                            color = AiTone.Muted.copy(alpha = .85f), fontSize = 10.sp, lineHeight = 14.sp,
                        )
                        summary.configUsage.forEach { usage -> AiQuotaUsageRow(usage) }
                    }
                }
            }
        }
        AiPanel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("任务记录（近 7 天，按天折叠）", style = LabTypography.CardTitle, color = AiTone.Ink)
                if (summary.recent.isEmpty()) Text("暂时还没有已完成的 AI 任务。", color = AiTone.Muted, fontSize = 12.sp)
                val dayGroups = remember(summary.recent) { aiGroupTasksByDay(summary.recent) }
                dayGroups.take(7).forEachIndexed { groupIndex, group ->
                    val expanded = if (groupIndex == 0) latestDayExpanded else expandedOlderDays.contains(group.day)
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clip(AiControlShape).background(AiTone.Field).aiTap {
                                if (groupIndex == 0) latestDayExpanded = !latestDayExpanded
                                else expandedOlderDays = if (expandedOlderDays.contains(group.day)) expandedOlderDays - group.day else expandedOlderDays + group.day
                            }.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group.dayLabel, color = AiTone.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${group.tasks.size} 次 · ${compactTokens(group.totalTokens.toInt())}", color = AiTone.Muted, fontSize = 11.sp)
                        }
                        if (expanded) {
                            Spacer(Modifier.height(6.dp))
                            group.tasks.forEachIndexed { index, record ->
                                if (index > 0) HorizontalDivider(color = AiTone.Border.copy(alpha = .72f))
                                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("${record.totalTokens} Token · ${if (record.status == "completed") "已完成" else "未完成"}", color = if (record.status == "completed") AiTone.Ink else AiTone.Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("${record.model} · 输入 ${record.promptTokens} · 输出 ${record.completionTokens}", color = AiTone.Muted, fontSize = 11.sp)
                                    Text(formatAiUsageTime(record.createdAt), color = AiTone.Muted.copy(alpha = .78f), fontSize = 10.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatAiUsageTime(value: String): String {
    val raw = value.trim()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    return runCatching { OffsetDateTime.parse(raw).toInstant().atZone(AI_BEIJING_ZONE).format(formatter) }
        .recoverCatching { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).format(formatter) }
        .getOrElse {
            raw.replace("T", " ").substringBefore("+").substringBefore("Z").take(19).ifBlank { "时间未知" }
        }
}

private fun compactTokens(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun formatInt(value: Long): String = String.format(Locale.ROOT, "%,d", value)

private fun formatInt(value: Int): String = String.format(Locale.ROOT, "%,d", value)

private fun aiUrlHost(url: String): String = url
    .trim()
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')
    .substringBefore(':')

private fun todayBeijingLabel(): String = runCatching {
    java.time.LocalDate.now(AI_BEIJING_ZONE).format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
}.getOrDefault("")

@Composable
private fun AiStatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = AiTone.Muted, fontSize = 10.sp)
        Text(value, color = AiTone.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun compactTokensLong(value: Long): String = when {
    value >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun formatAiStorage(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes > 0 -> "${(bytes + 1023) / 1024} KB"
    else -> "0 KB"
}

private class AiTaskDayAcc(val label: String) {
    val tasks = mutableListOf<AiUsageRecord>()
    var tokens = 0L
}

private data class AiTaskDayGroup(val day: String, val dayLabel: String, val totalTokens: Long, val tasks: List<AiUsageRecord>)

private fun aiGroupTasksByDay(recent: List<AiUsageRecord>): List<AiTaskDayGroup> {
    val today = LocalDate.now(AI_BEIJING_ZONE)
    val order = LinkedHashMap<String, AiTaskDayAcc>()
    for (record in recent) {
        val day = aiHistoryDate(record.createdAt)
        val label = when (day) {
            LocalDate.MIN -> "更早"
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> day.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
        }
        val parsed = day.toString() to label
        val acc = order.getOrPut(parsed.first) { AiTaskDayAcc(parsed.second) }
        acc.tasks += record
        acc.tokens += record.totalTokens
    }
    return order.entries.take(7).map { (key, acc) -> AiTaskDayGroup(key, acc.label, acc.tokens, acc.tasks) }
}

private data class AiUsageSlot(
    val date: String,
    val prompt: Int,
    val completion: Int,
    val cacheHit: Int,
    val cacheMiss: Int,
) {
    val total: Int get() = prompt + completion
}

/** 14 slots starting yesterday and growing forward; today is the second day. */
private fun aiTrendSlots(daily: List<AiUsageDay>, days: Int = 14): List<AiUsageSlot> {
    val byDate = daily.associateBy { it.date }
    return runCatching {
        val yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1)
        (0 until days).map { offset ->
            val day = yesterday.plusDays(offset.toLong()).toString()
            val source = byDate[day]
            val knownInput = (source?.promptTokens ?: 0).coerceAtLeast(0)
            val knownOutput = (source?.completionTokens ?: 0).coerceAtLeast(0)
            val total = (source?.totalTokens ?: 0).coerceAtLeast(0)
            val cacheInput = ((source?.cacheHitTokens ?: 0) + (source?.cacheMissTokens ?: 0)).coerceAtLeast(0)
            val prompt = when {
                knownInput > 0 || knownOutput > 0 -> knownInput
                cacheInput > 0 -> cacheInput.coerceAtMost(total)
                else -> (total - knownOutput).coerceAtLeast(0)
            }
            val completion = if (knownInput > 0 || knownOutput > 0) knownOutput else (total - prompt).coerceAtLeast(0)
            AiUsageSlot(day, prompt, completion, source?.cacheHitTokens ?: 0, source?.cacheMissTokens ?: 0)
        }
    }.getOrElse {
        daily.takeLast(days).map { source ->
            AiUsageSlot(source.date, source.promptTokens, source.completionTokens, source.cacheHitTokens, source.cacheMissTokens)
        }
    }
}

@Composable
private fun AiUsageLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, color = AiTone.Muted, fontSize = 10.5.sp)
    }
}

@Composable
private fun AiDailyUsageBars(daily: List<AiUsageDay>) {
    val slots = aiTrendSlots(daily)
    if (slots.isEmpty()) return
    val maxTokens = (slots.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)
    var selected by remember { mutableStateOf<Int?>(null) }
    // 悬浮明细 3 秒自动收起；点空白格或再点当前柱立即收起，避免一直挂着
    LaunchedEffect(selected) {
        if (selected != null) {
            delay(3000)
            selected = null
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slotWidth = maxWidth / slots.size
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .pointerInput(slots) {
                    detectTapGestures { offset ->
                        val index = (offset.x / (size.width / slots.size)).toInt().coerceIn(0, slots.lastIndex)
                        selected = when {
                            index == selected -> null
                            slots[index].total <= 0 -> null
                            else -> index
                        }
                    }
                },
        ) {
            val topPad = 8.dp.toPx()
            val bottomPad = 3.dp.toPx()
            val usable = size.height - topPad - bottomPad
            val slotWidthPx = size.width / slots.size
            val barWidth = (slotWidthPx * .58f).coerceAtMost(16.dp.toPx()).coerceAtLeast(4.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
            drawLine(AiTone.Border, Offset(0f, size.height - bottomPad), Offset(size.width, size.height - bottomPad), strokeWidth = 1.dp.toPx())
            drawLine(AiTone.Border.copy(alpha = .65f), Offset(0f, topPad + usable / 2), Offset(size.width, topPad + usable / 2), strokeWidth = 1.dp.toPx())
            selected?.let { index ->
                val center = index * slotWidthPx + slotWidthPx / 2
                drawLine(
                    AiTone.Mint.copy(alpha = .55f),
                    Offset(center, 2f),
                    Offset(center, size.height - bottomPad),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }
            slots.forEachIndexed { index, slot ->
                val left = index * slotWidthPx + (slotWidthPx - barWidth) / 2
                var bottom = size.height - bottomPad
                val hasCache = slot.cacheHit + slot.cacheMiss > 0
                val cacheHit = if (hasCache) slot.cacheHit.coerceIn(0, slot.prompt) else 0
                val cacheMiss = if (hasCache) (slot.prompt - cacheHit).coerceAtLeast(0) else slot.prompt
                listOf(
                    cacheHit to Color(0xFFBCEAD9),
                    cacheMiss to Color(0xFF68C6A6),
                    slot.completion to AiTone.MintDark,
                ).forEach { (tokens, color) ->
                    if (tokens > 0) {
                        val height = usable * tokens.toFloat() / maxTokens
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(left, bottom - height),
                            size = Size(barWidth, height),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                        )
                        bottom -= height
                    }
                }
            }
        }
        selected?.let { index ->
            val slot = slots[index]
            val tooltipWidth = 214.dp
            val center = slotWidth * (index + 0.5f)
            val x = (center - tooltipWidth / 2).coerceIn(0.dp, maxWidth - tooltipWidth)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = x, y = 4.dp)
                    .width(tooltipWidth),
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, AiTone.Border),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(slot.date, color = AiTone.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (slot.total > 0) Text(formatInt(slot.total), color = AiTone.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    if (slot.total == 0) {
                        Text("当日无用量", color = AiTone.Muted, fontSize = 10.5.sp)
                    } else {
                        val hasCache = slot.cacheHit + slot.cacheMiss > 0
                        if (hasCache) AiTooltipRow(Color(0xFFBCEAD9), "输入（命中缓存）", slot.cacheHit)
                        AiTooltipRow(Color(0xFF68C6A6), if (hasCache) "输入（未命中缓存）" else "输入", if (hasCache) slot.cacheMiss else slot.prompt)
                        AiTooltipRow(AiTone.MintDark, "输出", slot.completion)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiTooltipRow(color: Color, label: String, tokens: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = AiTone.Muted, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
        Text(formatInt(tokens), color = AiTone.Ink, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AiQuotaUsageRow(usage: AiConfigUsage) {
    val quota = usage.tokenQuota?.takeIf { it > 0 }
    val used = usage.totalTokens.coerceAtLeast(0)
    val usedPercent = quota?.let { ((used.coerceAtMost(it) * 100.0) / it).toInt() }
    val remainingPercent = usedPercent?.let { (100 - it).coerceAtLeast(0) }
    Column(
        Modifier.fillMaxWidth().clip(AiControlShape).background(AiTone.Field).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(usage.name.ifBlank { usage.model.ifBlank { "未命名配置" } }, color = AiTone.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (usage.name.isNotBlank() && usage.model.isNotBlank() && usage.name != usage.model) {
                    Text(usage.model, color = AiTone.Muted, fontSize = 10.5.sp)
                }
            }
            Text("已用 ${compactTokensLong(used)}", color = AiTone.Muted, fontSize = 10.5.sp)
        }
        if (quota == null) {
            Text("模型额度未设置，暂不计算使用/剩余百分比", color = AiTone.Muted, fontSize = 10.5.sp)
        } else {
            LinearProgressIndicator(
                progress = { (used.toFloat() / quota).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(AiPillShape),
                color = if (used > quota) AiTone.Warning else AiTone.Mint,
                trackColor = AiTone.MintSoft,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("额度 ${compactTokensLong(quota)} · 已用 $usedPercent%", color = AiTone.Muted, fontSize = 10.5.sp)
                Spacer(Modifier.weight(1f))
                Text("剩余 $remainingPercent%", color = if (remainingPercent == 0) AiTone.Warning else AiTone.MintDark, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AiModelDonut(models: Map<String, Long>) {
    val entries = models.entries.sortedByDescending { it.value }
    val periodTotal = entries.sumOf { it.value }.coerceAtLeast(1L)
    val palette = listOf(AiTone.Mint, Color(0xFF3E8E7E), Color(0xFF7FBFA9), Color(0xFFB7D9CC), AiTone.Warning, AiTone.Muted)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 15.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                var start = -90f
                entries.forEachIndexed { index, (_, tokens) ->
                    val sweep = (360f * (tokens.toFloat() / periodTotal)).coerceAtLeast(1.5f)
                    drawArc(
                        color = palette[index % palette.size],
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = arcSize,
                        style = Stroke(width = stroke),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(compactTokens(periodTotal.toInt()), color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text("近 14 天", color = AiTone.Muted, fontSize = 9.5.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            entries.forEachIndexed { index, (model, tokens) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(palette[index % palette.size]))
                    Spacer(Modifier.width(6.dp))
                    Text(model, color = AiTone.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${compactTokens(tokens.toInt())} · ${tokens * 100 / periodTotal}%", color = AiTone.Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

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
            } else {
                Box(Modifier.size(6.dp).clip(CircleShape).background(AiTone.Mint.copy(alpha = .55f)))
                Spacer(Modifier.width(5.dp))
            }
            Text(hint.name, color = AiTone.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Minimal markdown: headings, bullets, ordered lists, **bold**, `code`. */
private sealed interface AiMdBlock {
    data class Paragraph(val text: String) : AiMdBlock
    data class Heading(val text: String, val level: Int) : AiMdBlock
    data class Bullet(val text: String, val marker: String) : AiMdBlock
}

private fun aiMarkdownBlocks(content: String): List<AiMdBlock> {
    val blocks = mutableListOf<AiMdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += AiMdBlock.Paragraph(paragraph.toString().trim())
        paragraph.setLength(0)
    }
    val ordered = Regex("^\\d+[.、)]\\s*")
    for (raw in content.replace("\r\n", "\n").split("\n")) {
        val trimmed = raw.trim()
        when {
            trimmed.isBlank() -> flush()
            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                blocks += AiMdBlock.Heading(trimmed.dropWhile { it == '#' }.trim(), level)
            }
            trimmed.startsWith("- ") || trimmed.startsWith("• ") || trimmed.startsWith("* ") -> {
                flush()
                blocks += AiMdBlock.Bullet(trimmed.substring(2).trim(), "•")
            }
            ordered.containsMatchIn(trimmed) -> {
                flush()
                val marker = ordered.find(trimmed)!!.value.trimEnd('.', '、', ')')
                blocks += AiMdBlock.Bullet(trimmed.substring(ordered.find(trimmed)!!.value.length).trim(), "$marker.")
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
    }
    flush()
    return blocks
}

private fun aiInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`\\n]+)`")
    var last = 0
    for (match in pattern.findAll(text)) {
        append(text.substring(last, match.range.first))
        val bold = match.groupValues[1]
        val code = match.groupValues[2]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = AiTone.MintDark)) {
                append(code)
            }
        }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

@Composable
private fun AiMarkdownText(content: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (block in aiMarkdownBlocks(content)) {
            when (block) {
                is AiMdBlock.Heading -> Text(
                    aiInlineMarkdown(block.text),
                    color = AiTone.Ink,
                    fontSize = if (block.level >= 3) 13.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                is AiMdBlock.Bullet -> Row {
                    Text(block.marker, color = AiTone.Mint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        aiInlineMarkdown(block.text),
                        Modifier.weight(1f),
                        color = AiTone.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp,
                    )
                }
                is AiMdBlock.Paragraph -> Text(
                    aiInlineMarkdown(block.text),
                    color = AiTone.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiChatBubble(message: AiMessage, onDelete: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    val user = message.role == "user"
    // 长按气泡边缘弹出菜单；直接长按文字则走系统文本选择（可局部复制）。
    val bubbleInteractionSource = remember { MutableInteractionSource() }
    fun bubbleModifier(maxWidth: Dp): Modifier = Modifier
        .widthIn(max = maxWidth)
        .combinedClickable(
            interactionSource = bubbleInteractionSource,
            indication = null,
            onClick = {},
            onLongClick = { menuOpen = true },
        )
    val bubbleContent: @Composable () -> Unit = {
        if (user) {
            Text(
                message.content,
                Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                color = AiTone.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp,
            )
        } else {
            AiMarkdownText(message.content, Modifier.padding(horizontal = 13.dp, vertical = 11.dp))
        }
    }
    if (user) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box {
                Surface(
                    modifier = bubbleModifier(264.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = AiTone.MintSoft,
                    border = BorderStroke(1.dp, AiTone.Mint.copy(alpha = .32f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    SelectionContainer { bubbleContent() }
                }
                AiBubbleMenu(menuOpen, { menuOpen = false }, message.content, clipboard, onDelete)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Surface(
                modifier = Modifier.size(26.dp).clip(CircleShape),
                shape = CircleShape,
                color = AiTone.Field,
                border = BorderStroke(1.dp, AiTone.Border),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) { AiRobotMark(Modifier.fillMaxSize().padding(5.dp)) }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Surface(
                    modifier = bubbleModifier(280.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = AiTone.Surface,
                    border = BorderStroke(1.dp, AiTone.Border),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    SelectionContainer { bubbleContent() }
                }
                AiBubbleMenu(menuOpen, { menuOpen = false }, message.content, clipboard, onDelete)
            }
        }
    }
}

@Composable
private fun AiBubbleMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: String,
    clipboard: ClipboardManager,
    onDelete: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("复制全文", fontSize = 13.sp) },
            onClick = {
                clipboard.setText(AnnotatedString(content))
                onDismiss()
            },
        )
        if (onDelete != null) {
            DropdownMenuItem(
                text = { Text("删除消息", fontSize = 13.sp, color = AiTone.Danger) },
                onClick = {
                    onDismiss()
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun AiTypingBubble() {
    val dots = rememberInfiniteTransition(label = "ai-typing")
    val phase by dots.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "ai-typing-phase",
    )
    AiPanel {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiRobotMark(Modifier.size(24.dp), thinking = true)
            Spacer(Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { index ->
                    val active = phase.toInt() % 3 == index
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(AiTone.Mint.copy(alpha = if (active) .95f else .32f)),
                    )
                }
            }
        }
    }
}

@Composable
fun AiChatScreen(context: Context, onBack: () -> Unit, onNavigate: (String) -> Unit = {}, onRefreshData: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val store = remember { AiSettingsStore(context) }
    val prefs = remember { AppPrefs(context) }
    val client = remember { AiApiClient(store, prefs.hub, prefs.token, appPrefs = prefs) }
    val localTools = remember { AiLocalToolExecutor(prefs) }
    val messages = AiChatSession.messages
    var toolHints by remember { mutableStateOf(AiChatSession.toolHints) }
    var pendingConfirmation by remember { mutableStateOf<AiToolConfirmation?>(null) }
    var conversationId by remember { mutableStateOf(AiChatSession.conversationId) }
    var loadingHistory by remember { mutableStateOf(!AiChatSession.loaded) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(AiTokenSummary()) }
    var usageKnown by remember { mutableStateOf(true) }
    var showHistory by remember { mutableStateOf(false) }
    var conversations by remember { mutableStateOf<List<AiConversation>>(emptyList()) }
    var loadingConversations by remember { mutableStateOf(false) }
    var expandedHistoryDays by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var editingConversationId by remember { mutableStateOf<String?>(null) }
    var editingConversationTitle by remember { mutableStateOf("") }
    var pendingDeleteConversation by remember { mutableStateOf<AiConversation?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, sending, pendingConfirmation) {
        val target = messages.size - 1 + if (pendingConfirmation != null || sending) 1 else 0
        if (target >= 0) runCatching { listState.animateScrollToItem(target) }
    }

    LaunchedEffect(Unit) {
        if (!AiChatSession.loaded) {
            // 先渲染欢迎语，恢复期间不再整屏空白
            if (messages.isEmpty()) messages += AiMessage("assistant", AI_GREETING)
            val hints = launch { runCatching { client.catalog() }.onSuccess { toolHints = it; AiChatSession.toolHints = it } }
            runCatching { client.latestConversation() }
                .onSuccess { (id, history) ->
                    AiChatSession.conversationId = id
                    messages.clear()
                    if (history.isEmpty()) messages += AiMessage("assistant", AI_GREETING) else messages.addAll(history)
                }
                .onFailure { if (messages.isEmpty()) messages += AiMessage("assistant", AI_GREETING) }
            AiChatSession.loaded = true
            conversationId = AiChatSession.conversationId
            loadingHistory = false
            hints.join()
        } else {
            loadingHistory = false
            runCatching { client.catalog() }.onSuccess { toolHints = it; AiChatSession.toolHints = it }
        }
    }
    LaunchedEffect(Unit) {
        while (loadingHistory) delay(100)
        fun deliver(row: AiNotification) {
            messages += AiMessage("assistant", "${row.title}\n${row.content}")
            AiNotifier.notifyAssistantMessage(context, row.title, row.content)
            store.saveLastNotificationId(row.id)
        }
        while (isActive) {
            try {
                client.notifications(store.lastNotificationId()).forEach { deliver(it) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // 短轮询失败留到下一轮重试，不长期占用代理或 Hub 连接。
            }
            delay(15_000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AiHeader("AI 对话", "常用指令随 Hub 能力更新", onBack, trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp).clip(CircleShape).aiTap(onClick = {
                        showHistory = !showHistory
                        if (showHistory && conversations.isEmpty() && !loadingConversations) {
                            loadingConversations = true
                            scope.launch {
                                runCatching { client.listConversations() }.onSuccess {
                                    conversations = it
                                    expandedHistoryDays = aiHistoryDays(it).firstOrNull()?.let { day -> setOf(day.date) }.orEmpty()
                                }
                                loadingConversations = false
                            }
                        }
                    }),
                    shape = CircleShape,
                    color = AiTone.Surface,
                    border = BorderStroke(1.dp, AiTone.Border),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.History, "历史对话", tint = AiTone.Ink, modifier = Modifier.size(17.dp)) }
                }
                Surface(
                    modifier = Modifier.size(34.dp).clip(CircleShape).aiTap(onClick = {
                        AiChatSession.messages.clear()
                        AiChatSession.conversationId = null
                        messages.clear()
                        conversationId = null
                        pendingConfirmation = null
                        messages += AiMessage("assistant", AI_GREETING)
                        showHistory = false
                    }),
                    shape = CircleShape,
                    color = AiTone.Surface,
                    border = BorderStroke(1.dp, AiTone.Border),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AddComment, "新对话", tint = AiTone.Ink, modifier = Modifier.size(17.dp)) }
                }
                Surface(
                    modifier = Modifier.size(34.dp).aiTap(onClick = onOpenSettings),
                    shape = CircleShape,
                    color = AiTone.MintSoft,
                    border = BorderStroke(1.dp, AiTone.Mint.copy(alpha = .35f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) { AiRobotMark(Modifier.fillMaxSize().padding(6.dp)) }
                }
            }
        })
        if (showHistory) {
            AiPanel {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("历史对话", style = LabTypography.CardTitle, color = AiTone.Ink, modifier = Modifier.weight(1f))
                        Text("收起", color = AiTone.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.aiTap { showHistory = false })
                    }
                    when {
                        loadingConversations -> Text("读取中…", color = AiTone.Muted, fontSize = 12.sp)
                        conversations.isEmpty() -> Text("还没有历史对话。", color = AiTone.Muted, fontSize = 12.sp)
                        else -> {
                            val days = aiHistoryDays(conversations)
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                days.forEach { day ->
                                    val expanded = day.date in expandedHistoryDays
                                    item(key = "history-day-${day.date}") {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth().clip(AiControlShape).aiTap {
                                                expandedHistoryDays = if (expanded) {
                                                    expandedHistoryDays - day.date
                                                } else {
                                                    expandedHistoryDays + day.date
                                                }
                                            },
                                            shape = AiControlShape,
                                            color = AiTone.Field,
                                            border = BorderStroke(1.dp, AiTone.Border.copy(alpha = .9f)),
                                            tonalElevation = 0.dp,
                                            shadowElevation = 0.dp,
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(day.label, color = AiTone.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.width(7.dp))
                                                Text("${day.conversations.size} 条", color = AiTone.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                                                Spacer(Modifier.weight(1f))
                                                Icon(
                                                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                    if (expanded) "收起 ${day.label} 对话" else "展开 ${day.label} 对话",
                                                    tint = AiTone.Muted,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                    if (expanded) {
                                        items(day.conversations, key = { "history-${it.id}" }) { convo ->
                                            val current = convo.id == conversationId
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 6.dp)
                                                    .clip(AiControlShape)
                                                    .background(if (current) AiTone.MintSoft else AiTone.Field),
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().aiTap {
                                                        if (!current) {
                                                            scope.launch {
                                                                loadingConversations = true
                                                                runCatching { client.conversationMessages(convo.id) }
                                                                    .onSuccess { loaded ->
                                                                        AiChatSession.conversationId = convo.id
                                                                        conversationId = convo.id
                                                                        messages.clear()
                                                                        if (loaded.isEmpty()) messages += AiMessage("assistant", AI_GREETING) else messages.addAll(loaded)
                                                                        expandedHistoryDays += aiHistoryDate(convo.updatedAt)
                                                                    }
                                                                loadingConversations = false
                                                            }
                                                        }
                                                        showHistory = false
                                                    }.padding(horizontal = 10.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        convo.title.ifBlank { "对话 ${convo.updatedAt.take(16)}" },
                                                        color = AiTone.Ink,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    Text(
                                                        "编辑",
                                                        color = AiTone.MintDark,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.aiTap {
                                                            editingConversationId = convo.id
                                                            editingConversationTitle = convo.title
                                                            pendingDeleteConversation = null
                                                        }.padding(horizontal = 7.dp, vertical = 4.dp),
                                                    )
                                                    Text(
                                                        "删除",
                                                        color = AiTone.Danger,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.aiTap {
                                                            editingConversationId = null
                                                            pendingDeleteConversation = if (pendingDeleteConversation?.id == convo.id) null else convo
                                                        }.padding(horizontal = 7.dp, vertical = 4.dp),
                                                    )
                                                    if (current && pendingDeleteConversation?.id != convo.id) {
                                                        Text("当前", color = AiTone.MintDark, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                                if (editingConversationId == convo.id) {
                                                    Row(
                                                        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        BasicTextField(
                                                            value = editingConversationTitle,
                                                            onValueChange = { editingConversationTitle = it.take(40) },
                                                            singleLine = true,
                                                            textStyle = TextStyle(color = AiTone.Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                                            cursorBrush = SolidColor(AiTone.Mint),
                                                            modifier = Modifier.weight(1f).clip(AiPillShape).background(AiTone.Surface).padding(horizontal = 10.dp, vertical = 8.dp),
                                                        )
                                                        Text("取消", color = AiTone.Muted, fontSize = 10.5.sp, modifier = Modifier.aiTap { editingConversationId = null }.padding(7.dp))
                                                        Text("保存", color = AiTone.MintDark, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.aiTap(enabled = editingConversationTitle.isNotBlank()) {
                                                            scope.launch {
                                                                runCatching { client.renameConversation(convo.id, editingConversationTitle) }
                                                                    .onSuccess { renamed ->
                                                                        conversations = conversations.map { row ->
                                                                            if (row.id == convo.id) row.copy(title = renamed.title) else row
                                                                        }
                                                                        editingConversationId = null
                                                                    }
                                                                    .onFailure { messages += AiMessage("assistant", "重命名失败：${it.message ?: "未知错误"}") }
                                                            }
                                                        }.padding(7.dp))
                                                    }
                                                }
                                                if (pendingDeleteConversation?.id == convo.id) {
                                                    Surface(
                                                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = AiTone.WarningSoft,
                                                        tonalElevation = 0.dp,
                                                        shadowElevation = 0.dp,
                                                    ) {
                                                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                                            Text(
                                                                "删除对话「${convo.title.ifBlank { convo.updatedAt.take(16) } }」？消息记录将一并删除且不可恢复。",
                                                                color = AiTone.Ink, fontSize = 11.sp, lineHeight = 15.sp,
                                                            )
                                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                                AiAction("取消", Modifier.weight(1f), compact = true) {
                                                                    pendingDeleteConversation = null
                                                                }
                                                                AiAction("确认删除", Modifier.weight(1f), primary = true, tone = AiTone.Danger, compact = true) {
                                                                    scope.launch {
                                                                        runCatching { client.deleteConversation(convo.id) }
                                                                            .onSuccess {
                                                                                conversations = conversations.filterNot { it.id == convo.id }
                                                                                if (AiChatSession.conversationId == convo.id) {
                                                                                    AiChatSession.conversationId = null
                                                                                    AiChatSession.messages.clear()
                                                                                    messages.clear()
                                                                                    conversationId = null
                                                                                    messages += AiMessage("assistant", AI_GREETING)
                                                                                }
                                                                                pendingDeleteConversation = null
                                                                            }
                                                                            .onFailure { messages += AiMessage("assistant", "删除失败：${it.message ?: "未知错误"}") }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (toolHints.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 4.dp)) {
                items(toolHints, key = { it.id }) { hint -> AiHintChip(hint) { input = hint.example } }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            if (loadingHistory) item { Text("正在恢复最近对话…", color = AiTone.Muted, fontSize = 12.sp) }
            items(messages) { message ->
                val deleteMessage: (() -> Unit)? = if (sending) {
                    null
                } else {
                    {
                        val convoId = conversationId
                        if (message.serverId > 0 && convoId != null) {
                            scope.launch {
                                try {
                                    client.deleteConversationMessage(convoId, message.serverId)
                                    messages.remove(message)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Throwable) {
                                    messages += AiMessage("assistant", "删除失败：${error.message ?: "未知错误"}")
                                }
                            }
                        } else {
                            messages.remove(message)
                        }
                    }
                }
                AiChatBubble(message, onDelete = deleteMessage)
            }
            if (sending) item { AiTypingBubble() }
            pendingConfirmation?.let { confirmation ->
                item {
                    AiPanel(accent = AiTone.Warning.copy(alpha = .55f)) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(34.dp),
                                    shape = CircleShape,
                                    color = AiTone.WarningSoft,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Security, null, tint = AiTone.Warning, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(confirmation.title, style = LabTypography.CardTitle, color = AiTone.Ink)
                                    Text("需要你确认后才会执行", color = AiTone.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = AiTone.Field,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                            ) {
                                Text(confirmation.summary, Modifier.fillMaxWidth().padding(12.dp), color = AiTone.Ink, fontSize = 12.5.sp, lineHeight = 18.sp)
                            }
                            Text("确认后仅执行以上操作；确认有效期为 5 分钟。", color = AiTone.Muted, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                AiAction("取消", Modifier.weight(1f), tone = AiTone.Danger, enabled = !sending) {
                                    pendingConfirmation = null
                                    messages += AiMessage("assistant", "已取消操作。")
                                }
                                AiAction("确认执行", Modifier.weight(1f), primary = true, enabled = !sending) {
                                    sending = true
                                    scope.launch {
                                        try {
                                            val hubMessage = client.confirmHubTool(confirmation.confirmationId)
                                            val message = if (confirmation.executor == "app") {
                                                try {
                                                    localTools.execute(confirmation).also { result ->
                                                        client.completeClientTool(confirmation.confirmationId, true, result)
                                                    }
                                                } catch (cancel: CancellationException) {
                                                    throw cancel
                                                } catch (error: Throwable) {
                                                    val failureMessage = error.message ?: "本机操作失败"
                                                    try {
                                                        client.completeClientTool(confirmation.confirmationId, false, failureMessage)
                                                    } catch (cancel: CancellationException) {
                                                        throw cancel
                                                    } catch (_: Throwable) {
                                                        // Preserve the original local-tool failure below.
                                                    }
                                                    throw error
                                                }
                                            } else hubMessage
                                            messages += AiMessage("assistant", message)
                                            pendingConfirmation = null
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (error: Throwable) {
                                            messages += AiMessage("assistant", "执行失败：${error.message ?: "未知错误"}")
                                        }
                                        sending = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(
            shape = AiPillShape,
            color = AiTone.Field,
            border = BorderStroke(1.dp, AiTone.Border),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Text(
                if (!usageKnown && usage.total == 0) "本次任务 Token：模型未上报"
                else "本次任务 Token：${usage.total}（输入 ${usage.prompt} · 输出 ${usage.completion}）",
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                color = AiTone.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            )
        }
        val canSend = input.isNotBlank() && !sending && !loadingHistory && pendingConfirmation == null
        val sendNow = {
            if (canSend) {
                val text = input.trim()
                input = ""
                val userMessage = AiMessage("user", text)
                messages += userMessage
                sending = true
                scope.launch {
                    var streamed = false
                    var liveIndex: Int? = null
                    fun liveBubble(): Int {
                        val index = liveIndex
                        if (index != null && messages.getOrNull(index) != null) return index
                        messages += AiMessage("assistant", "")
                        val created = messages.lastIndex
                        liveIndex = created
                        return created
                    }
                    try {
                        val reply = client.chatStream(
                            text, conversationId,
                            onDelta = { piece ->
                                streamed = true
                                val index = liveBubble()
                                messages[index] = AiMessage("assistant", messages[index].content + piece)
                            },
                            onReset = {
                                liveIndex?.let { index ->
                                    if (messages.getOrNull(index) != null) messages[index] = AiMessage("assistant", "")
                                }
                            },
                        )
                        conversationId = reply.conversationId ?: conversationId
                        AiChatSession.conversationId = conversationId
                        if (reply.userMessageId > 0) {
                            val userIndex = messages.indexOfFirst { it === userMessage }
                            if (userIndex >= 0) {
                                messages[userIndex] = userMessage.copy(serverId = reply.userMessageId)
                            }
                        }
                        if (streamed) {
                            val index = liveIndex
                            val existing = index?.let { messages.getOrNull(it) }
                            when {
                                existing != null && index != null ->
                                    messages[index] = AiMessage("assistant", reply.content.ifBlank { existing.content }, serverId = reply.messageId)
                                reply.content.isNotBlank() -> messages += AiMessage("assistant", reply.content, serverId = reply.messageId)
                            }
                        }
                        while (messages.size >= 120) messages.removeAt(0)
                        usage = reply.usage
                        usageKnown = reply.usageKnown || reply.usage.total > 0
                        pendingConfirmation = reply.confirmation
                        if (!streamed) {
                            messages += AiMessage("assistant", "", serverId = reply.messageId)
                            val replyIndex = messages.lastIndex
                            // 旧 Hub 返回完整 JSON 时使用打字机揭示；SSE 路径由真实增量驱动。
                            var shown = 0
                            while (shown < reply.content.length && messages.getOrNull(replyIndex) != null) {
                                shown = (shown + maxOf(1, reply.content.length / 60)).coerceAtMost(reply.content.length)
                                messages[replyIndex] = AiMessage("assistant", reply.content.substring(0, shown), serverId = reply.messageId)
                                delay(16)
                            }
                            if (messages.getOrNull(replyIndex) != null && shown < reply.content.length) {
                                messages[replyIndex] = AiMessage("assistant", reply.content, serverId = reply.messageId)
                            }
                        }
                        // Navigate only after the bubble is final: leaving the
                        // composition cancels this scope mid-navigation.
                        reply.clientActions.forEach { action ->
                            when (action.type) {
                                "navigate" -> if (action.route.isNotBlank()) onNavigate(action.route)
                                "refresh" -> onRefreshData()
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: AiStreamProtocolException) {
                        conversationId = error.conversationId ?: conversationId
                        AiChatSession.conversationId = conversationId
                        if (error.userMessageId > 0) {
                            val userIndex = messages.indexOfFirst { it === userMessage }
                            if (userIndex >= 0) {
                                messages[userIndex] = userMessage.copy(serverId = error.userMessageId)
                            }
                        }
                        messages += AiMessage("assistant", "请求失败：${error.message ?: "未知错误"}")
                    } catch (error: Throwable) {
                        messages += AiMessage("assistant", "请求失败：${error.message ?: "未知错误"}")
                    }
                    sending = false
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AiChatInput(
                value = input,
                onValueChange = { input = it },
                onSend = sendNow,
                enabled = !sending && !loadingHistory && pendingConfirmation == null,
                modifier = Modifier.weight(1f),
            )
            Surface(
                modifier = Modifier.size(50.dp).clip(CircleShape).aiTap(enabled = canSend, onClick = sendNow),
                shape = CircleShape,
                color = if (canSend) AiTone.Mint else AiTone.Mint.copy(alpha = .35f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Send, "发送", tint = Color.White) }
            }
        }
    }
}
