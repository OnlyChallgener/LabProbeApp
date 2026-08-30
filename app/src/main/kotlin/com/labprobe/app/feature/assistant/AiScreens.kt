package com.labprobe.app.feature.assistant

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
    AiProviderPreset("腾讯混元 TokenHub", "hy4-preview", "https://tokenhub.tencentmaas.com/v1"),
)

private const val AI_GREETING = "你好，我可以查询设备与网络状态，也能在确认后帮你控制端口映射、STUN 穿透或升级 Agent。"

/** Process-lifetime chat state: reopening the screen is instant, no reload flash. */
object AiChatSession {
    val messages = mutableStateListOf<AiMessage>()
    var conversationId: String? = null
    var loaded: Boolean = false
    var toolHints: List<AiToolHint> = emptyList()
    var hubIdentity: String? = null

    fun resetForHub(identity: String) {
        if (hubIdentity == identity) return
        hubIdentity = identity
        messages.clear()
        conversationId = null
        loaded = false
        toolHints = emptyList()
    }
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
            // A Dialog can offer effectively unbounded height.  A minimum
            // alone lets weighted sibling actions stretch to the whole
            // remaining screen; keep every action at its designed height.
            .height(if (compact) 40.dp else 48.dp)
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
                                    if (index > 0) Text(
                                        "↑", color = AiTone.MintDark, fontSize = 13.sp, fontWeight = FontWeight.Black,
                                        modifier = Modifier.aiTap {
                                            scope.launch { runCatching { client.moveConfig(config.id, "up") }; loadConfigs() }
                                        }.padding(horizontal = 7.dp, vertical = 6.dp),
                                    )
                                    if (index < configs.lastIndex) Text(
                                        "↓", color = AiTone.MintDark, fontSize = 13.sp, fontWeight = FontWeight.Black,
                                        modifier = Modifier.aiTap {
                                            scope.launch { runCatching { client.moveConfig(config.id, "down") }; loadConfigs() }
                                        }.padding(horizontal = 7.dp, vertical = 6.dp),
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
                                if (model != preset.model) tokenQuota = ""
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
                AiFormField("模型", model, { next ->
                    if (next != model) tokenQuota = ""
                    model = next
                }, placeholder = "deepseek-v4-flash")
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
    val client = remember(prefs.hub, prefs.token) { AiApiClient(store, prefs.hub, prefs.token) }
    val scope = rememberCoroutineScope()
    var summary by remember(client.identity) { mutableStateOf(AiUsageSummary()) }
    var message by remember(client.identity) { mutableStateOf("正在读取") }
    var latestDayExpanded by remember(client.identity) { mutableStateOf(true) }
    var expandedOlderDays by remember(client.identity) { mutableStateOf(setOf<String>()) }
    var editingUsage by remember(client.identity) { mutableStateOf<AiConfigUsage?>(null) }
    var deletingUsage by remember(client.identity) { mutableStateOf<AiConfigUsage?>(null) }
    val refreshUsage: () -> Unit = {
        scope.launch {
            runCatching { client.usage() }
                .onSuccess { summary = it; message = "已更新" }
                .onFailure { message = it.message ?: "读取失败" }
        }
    }
    LaunchedEffect(client.identity) {
        runCatching { client.usage() }
            .onSuccess { summary = it; message = "已更新" }
            .onFailure { message = it.message ?: "读取失败" }
    }
    val daily = summary.daily
    val periodPrompt = daily.sumOf { it.promptTokens }.coerceAtLeast(0)
    val cacheReported = daily.sumOf { it.cacheReportedInputTokens }.coerceIn(0, periodPrompt)
    val cacheHit = daily.sumOf { it.cacheHitTokens }.coerceIn(0, cacheReported)
    val trendSlots = aiTrendSlots(daily)
    val trendPeriodLabel = trendSlots.takeIf { it.isNotEmpty() }
        ?.let { "${it.first().date.takeLast(5)} 至 ${it.last().date.takeLast(5)}" }
        ?: "14 天"
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
                    AiStatColumn("累计 Token（校准优先）", formatInt(summary.totalTokens), Modifier.weight(1.2f))
                    AiStatColumn("累计任务", "${summary.requests} 次", Modifier.weight(1f))
                    AiStatColumn("对话存储", formatAiStorage(summary.storageBytes), Modifier.weight(1f))
                }
                Text(
                    "任务上报：输入 ${formatInt(summary.promptTokens)} · 输出 ${formatInt(summary.completionTokens)} · 消息 ${summary.storageMessages} 条",
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
                    Text(trendPeriodLabel, color = AiTone.Muted, fontSize = 10.sp)
                }
                if (daily.isEmpty()) {
                    Text("还没有用量数据。", color = AiTone.Muted, fontSize = 12.sp)
                } else {
                    val hasCacheBreakdown = cacheReported > 0
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (hasCacheBreakdown) {
                            AiUsageLegend(Color(0xFFBCEAD9), "命中缓存")
                            AiUsageLegend(Color(0xFF68C6A6), "其他")
                        } else {
                            AiUsageLegend(Color(0xFF68C6A6), "输入")
                        }
                        AiUsageLegend(AiTone.MintDark, "输出")
                        if (trendSlots.any { it.other > 0 }) AiUsageLegend(AiTone.Warning, "校准")
                    }
                    if (cacheReported > 0 && periodPrompt > 0) {
                        Text(
                            "缓存命中 ${cacheHit * 100 / cacheReported}% · ${compactTokens(cacheReported)}/${compactTokens(periodPrompt)} 输入",
                            modifier = Modifier.fillMaxWidth(),
                            color = AiTone.Muted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AiDailyUsageBars(daily)
                    Spacer(Modifier.height(2.dp))
                    val slots = trendSlots
                    Text("点击柱子查看当日明细", color = AiTone.Muted.copy(alpha = .8f), fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth()) {
                        Text(slots.firstOrNull()?.date?.takeLast(5).orEmpty(), color = AiTone.Muted, fontSize = 10.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text("峰值 ${compactTokens(slots.maxOfOrNull { it.total } ?: 0)}", color = AiTone.Muted, fontSize = 10.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text(slots.lastOrNull()?.date?.takeLast(5).orEmpty(), color = AiTone.Muted, fontSize = 10.5.sp)
                    }
                }
            }
        }
        AiPanel {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("模型分布（同上周期）", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val trendDates = trendSlots.mapTo(mutableSetOf()) { it.date }
                val modelTotals = daily.filter { it.date in trendDates }.flatMap { it.models.entries }
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
                            "上方分布按模型统计图表所示周期；下方为每个 API 配置的累计用量与设置额度的对比。",
                            color = AiTone.Muted.copy(alpha = .85f), fontSize = 10.sp, lineHeight = 14.sp,
                        )
                        summary.configUsage.forEach { usage ->
                            AiQuotaUsageRow(
                                usage,
                                onEdit = { editingUsage = usage },
                                onDelete = { deletingUsage = usage },
                            )
                        }
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${if (record.usageKnown) "${record.totalTokens} Token" else "Token 未上报"} · ${if (record.status == "completed") "已完成" else "未完成"}",
                                            color = if (record.status == "completed") AiTone.Ink else AiTone.Danger,
                                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "删除", color = AiTone.Danger, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.aiTap {
                                                scope.launch {
                                                    runCatching { client.deleteUsageRecord(record.id) }
                                                        .onSuccess {
                                                            message = "记录已删除，正在刷新统计"
                                                            refreshUsage()
                                                        }
                                                        .onFailure { message = it.message ?: "删除失败" }
                                                }
                                            }.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    Text(
                                        when {
                                            !record.usageKnown -> "${record.model} · 服务商未返回 usage，本次不估算"
                                            record.promptTokens + record.completionTokens == 0 && record.totalTokens > 0 ->
                                                "${record.model} · 服务商只返回总量，未返回输入/输出拆分"
                                            else -> "${record.model} · 输入 ${record.promptTokens} · 输出 ${record.completionTokens}"
                                        },
                                        color = AiTone.Muted, fontSize = 11.sp,
                                    )
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
    editingUsage?.let { target ->
        AiUsageEditDialog(
            usage = target,
            onDismiss = { editingUsage = null },
            onSave = { usageTarget, quota ->
                scope.launch {
                    runCatching {
                        require(usageTarget >= 0) { "请输入有效的累计用量" }
                        client.adjustUsage(
                            configId = target.configId,
                            totalTokens = usageTarget,
                            tokenQuota = quota,
                            updateQuota = quota != target.tokenQuota,
                        )
                    }
                        .onSuccess { editingUsage = null; message = "已更新"; refreshUsage() }
                        .onFailure { message = it.message ?: "更新失败" }
                }
            },
        )
    }
    deletingUsage?.let { target ->
        AiUsageDeleteDialog(
            usage = target,
            onDismiss = { deletingUsage = null },
            onDelete = {
                scope.launch {
                    runCatching { client.deleteConfigUsageRecord(target.configId) }
                        .onSuccess {
                            deletingUsage = null
                            message = "额度记录已删除；API 配置和任务记录均已保留"
                            refreshUsage()
                        }
                        .onFailure { message = it.message ?: "删除失败" }
                }
            },
        )
    }
}

@Composable
private fun AiUsageEditDialog(
    usage: AiConfigUsage,
    onDismiss: () -> Unit,
    onSave: (usageTarget: Long, quota: Long?) -> Unit,
) {
    var usageText by remember { mutableStateOf(usage.totalTokens.toString()) }
    var quotaText by remember { mutableStateOf(usage.tokenQuota?.toString().orEmpty()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AiTone.Surface,
            border = BorderStroke(1.dp, AiTone.Border),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp).widthIn(max = 330.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("校准用量 · ${usage.name.ifBlank { usage.model }}", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "把累计用量调整为平台后台的实际值（当前 ${compactTokensLong(usage.totalTokens)} Token）。额度留空表示不设置。",
                    color = AiTone.Muted, fontSize = 11.sp, lineHeight = 15.sp,
                )
                AiFormField("累计用量（Token）", usageText, { value -> usageText = value.filter(Char::isDigit) }, placeholder = "例如 586800")
                AiFormField("额度（Token，可留空）", quotaText, { value -> quotaText = value.filter(Char::isDigit) }, placeholder = "例如 1000000")
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    AiAction("取消", Modifier.weight(1f), compact = true) { onDismiss() }
                    AiAction("保存", Modifier.weight(1f), primary = true, compact = true, enabled = usageText.isNotBlank()) {
                        onSave(usageText.toLongOrNull() ?: -1L, quotaText.toLongOrNull())
                    }
                }
            }
        }
    }
}

private fun formatAiUsageTime(value: String): String {
    val raw = value.trim()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    return runCatching { OffsetDateTime.parse(raw).toInstant().atZone(AI_BEIJING_ZONE).format(formatter) }
        .recoverCatching { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(AI_BEIJING_ZONE).format(formatter) }
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
    val other: Int,
    val cacheHit: Int,
    val cacheMiss: Int,
    val cacheReported: Int,
) {
    val total: Int get() = prompt + completion + other
}

@Composable
private fun AiUsageDeleteDialog(
    usage: AiConfigUsage,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AiTone.Surface,
            border = BorderStroke(1.dp, AiTone.Border),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp).widthIn(max = 330.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("删除额度记录 · ${usage.name.ifBlank { usage.model }}", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "只删除这个模型的额度与手动校准累计。腾讯 TokenHub/API 配置、API Key、启用状态和每次任务记录都不会删除；以后可直接在原配置中更换模型。",
                    color = AiTone.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    AiAction("取消", Modifier.weight(1f), compact = true) { onDismiss() }
                    AiAction("删除额度记录", Modifier.weight(1f), primary = true, tone = AiTone.Danger, compact = true) { onDelete() }
                }
            }
        }
    }
}

/** Always show the latest 14 Beijing calendar days (including zero days).
 * The old first-data anchor could generate dates after today when the user
 * had only just started using the assistant. */
private fun aiTrendSlots(daily: List<AiUsageDay>, days: Int = 14): List<AiUsageSlot> {
    val byDate = daily.associateBy { it.date }
    return runCatching {
        val start = LocalDate.now(AI_BEIJING_ZONE).minusDays((days - 1).toLong())
        (0 until days).map { offset ->
            val day = start.plusDays(offset.toLong()).toString()
            val source = byDate[day]
            val total = (source?.totalTokens ?: 0).coerceAtLeast(0)
            val prompt = (source?.promptTokens ?: 0).coerceAtLeast(0).coerceAtMost(total)
            val completion = (source?.completionTokens ?: 0).coerceAtLeast(0).coerceAtMost(total - prompt)
            val other = (total - prompt - completion).coerceAtLeast(0)
            val reported = (source?.cacheReportedInputTokens ?: 0).coerceIn(0, prompt)
            AiUsageSlot(
                day, prompt, completion, other,
                (source?.cacheHitTokens ?: 0).coerceIn(0, reported),
                (source?.cacheMissTokens ?: 0).coerceIn(0, reported),
                reported,
            )
        }
    }.getOrElse {
        daily.takeLast(days).map { source ->
            val total = source.totalTokens.coerceAtLeast(0)
            val prompt = source.promptTokens.coerceAtLeast(0).coerceAtMost(total)
            val completion = source.completionTokens.coerceAtLeast(0).coerceAtMost(total - prompt)
            val reported = source.cacheReportedInputTokens.coerceIn(0, prompt)
            AiUsageSlot(
                source.date, prompt, completion, (total - prompt - completion).coerceAtLeast(0),
                source.cacheHitTokens.coerceIn(0, reported),
                source.cacheMissTokens.coerceIn(0, reported),
                reported,
            )
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
    // 悬浮明细 5 秒自动收起；点空白格或再点当前柱立即收起，避免一直挂着
    LaunchedEffect(selected) {
        if (selected != null) {
            delay(5000)
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
                val hasCache = slot.cacheReported > 0
                val cacheHit = if (hasCache) slot.cacheHit.coerceIn(0, slot.prompt) else 0
                // 其余输入包含已上报的未命中部分和服务商未拆分的输入，统一以普通输入显示。
                val otherInput = (slot.prompt - cacheHit).coerceAtLeast(0)
                listOf(
                    cacheHit to Color(0xFFBCEAD9),
                    otherInput to Color(0xFF68C6A6),
                    slot.completion to AiTone.MintDark,
                    slot.other to AiTone.Warning,
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
            val tooltipWidth = minOf(280.dp, maxWidth - 20.dp)
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
                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(slot.date, color = AiTone.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (slot.total > 0) Text("${formatInt(slot.total)} Token", color = AiTone.Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    if (slot.total == 0) {
                        Text("当日无用量", color = AiTone.Muted, fontSize = 10.5.sp)
                    } else {
                        HorizontalDivider(color = AiTone.Border.copy(alpha = .72f))
                        Text("用量构成", color = AiTone.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        AiTooltipRow(Color(0xFF68C6A6), "输入", slot.prompt)
                        AiTooltipRow(AiTone.MintDark, "输出", slot.completion)
                        if (slot.other > 0) AiTooltipRow(AiTone.Warning, "其他/校准", slot.other)
                        val hasCache = slot.cacheReported > 0
                        if (hasCache && (slot.cacheHit > 0 || slot.cacheMiss > 0)) {
                            HorizontalDivider(color = AiTone.Border.copy(alpha = .72f))
                            Text("缓存明细（已上报）", color = AiTone.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            if (slot.cacheHit > 0) AiTooltipRow(Color(0xFFBCEAD9), "命中缓存", slot.cacheHit)
                            if (slot.cacheMiss > 0) AiTooltipRow(Color(0xFF68C6A6), "未命中缓存", slot.cacheMiss)
                        }
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
private fun AiQuotaUsageRow(
    usage: AiConfigUsage,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
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
            if (onEdit != null) {
                Text(
                    "校准", color = AiTone.MintDark, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.aiTap { onEdit() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (onDelete != null) {
                Text(
                    "删除", color = AiTone.Danger, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.aiTap { onDelete() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
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
private fun AiChatBubble(
    message: AiMessage,
    multiSelectMode: Boolean,
    selected: Boolean,
    interactionEnabled: Boolean,
    onToggleSelection: () -> Unit,
    onOpenActions: () -> Unit,
) {
    val user = message.role == "user"
    // Normal bubbles never host SelectionContainer. A long press opens the
    // in-page single-message actions; text selection lives in its own dialog,
    // so the Android selection toolbar and app actions cannot overlap.
    val bubbleInteractionSource = remember { MutableInteractionSource() }
    fun bubbleModifier(maxWidth: Dp): Modifier = Modifier
        .widthIn(max = maxWidth)
        .combinedClickable(
            interactionSource = bubbleInteractionSource,
            indication = null,
            onClick = { if (multiSelectMode && interactionEnabled) onToggleSelection() },
            onLongClick = {
                if (interactionEnabled) {
                    if (multiSelectMode) onToggleSelection() else onOpenActions()
                }
            },
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
                    color = if (selected) AiTone.Mint.copy(alpha = .22f) else AiTone.MintSoft,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) AiTone.MintDark else AiTone.Mint.copy(alpha = .32f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    bubbleContent()
                }
                if (selected) AiSelectedBadge(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
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
                    color = if (selected) AiTone.Mint.copy(alpha = .14f) else AiTone.Surface,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) AiTone.MintDark else AiTone.Border),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    bubbleContent()
                }
                if (selected) AiSelectedBadge(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
            }
        }
    }
}

@Composable
private fun AiSelectedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(20.dp),
        shape = CircleShape,
        color = AiTone.MintDark,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun aiMessageSelectionKey(message: AiMessage): String =
    if (message.serverId > 0) "server-${message.serverId}"
    else "local-${System.identityHashCode(message)}"

private fun aiSelectedMessagesText(messages: List<AiMessage>): String = messages.joinToString("\n\n") { message ->
    val speaker = if (message.role == "user") "我" else "助手"
    "$speaker：${message.content}"
}

@Composable
private fun AiPartialCopyDialog(message: AiMessage, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            shape = RoundedCornerShape(18.dp),
            color = AiTone.Surface,
            border = BorderStroke(1.dp, AiTone.Border),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择并复制文字", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("关闭", color = AiTone.MintDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.aiTap { onDismiss() }.padding(6.dp))
                }
                Text("长按下方文字后拖动选区，再点系统的“复制”。", color = AiTone.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = AiTone.Field,
                    border = BorderStroke(1.dp, AiTone.Border.copy(alpha = .75f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    SelectionContainer {
                        Text(
                            message.content,
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(13.dp),
                            color = AiTone.Ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiSwitchModelCard(retryPreview: String, onSwitch: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = AiTone.Surface,
        border = BorderStroke(1.dp, AiTone.Mint.copy(alpha = .45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前模型不可用，要换一个再试吗？", color = AiTone.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Text(
                "刚才的消息：「${retryPreview.take(24)}${if (retryPreview.length > 24) "…" else ""}」",
                color = AiTone.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                AiAction("切换模型", Modifier.weight(1f), primary = true, compact = true, onClick = onSwitch)
                AiAction("忽略", Modifier.weight(1f), compact = true, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun AiModelPickerDialog(
    configs: List<AiProviderConfig>,
    onDismiss: () -> Unit,
    onPick: (AiProviderConfig) -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AiTone.Surface,
            border = BorderStroke(1.dp, AiTone.Border),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp).widthIn(max = 330.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("切换对话模型", color = AiTone.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("选中后该配置会被置顶，并自动重试刚才的消息", color = AiTone.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                if (configs.isEmpty()) {
                    Text("没有已启用的配置，请先到 AI 设置中启用。", color = AiTone.Muted, fontSize = 12.sp)
                } else {
                    configs.forEachIndexed { index, config ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).aiTap { onPick(config) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (index == 0) AiTone.MintSoft else AiTone.Field,
                            border = BorderStroke(1.dp, AiTone.Border.copy(alpha = .8f)),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(config.name.ifBlank { config.model }, color = AiTone.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(config.model, color = AiTone.Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (index == 0) Text("当前", color = AiTone.MintDark, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Text("取消", color = AiTone.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End).aiTap { onDismiss() }.padding(6.dp))
            }
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
    val client = remember(prefs.hub, prefs.token) { AiApiClient(store, prefs.hub, prefs.token, appPrefs = prefs) }
    val localTools = remember { AiLocalToolExecutor(prefs) }
    val messages = AiChatSession.messages
    var toolHints by remember(client.identity) { mutableStateOf(AiChatSession.toolHints) }
    var pendingConfirmation by remember(client.identity) { mutableStateOf<AiToolConfirmation?>(null) }
    var retryText by remember(client.identity) { mutableStateOf<String?>(null) }
    var modelPickerOpen by remember(client.identity) { mutableStateOf(false) }
    var pickerConfigs by remember(client.identity) { mutableStateOf<List<AiProviderConfig>>(emptyList()) }
    var conversationId by remember(client.identity) { mutableStateOf(AiChatSession.conversationId) }
    var loadingHistory by remember(client.identity) { mutableStateOf(AiChatSession.hubIdentity != client.identity || !AiChatSession.loaded) }
    var historyError by remember(client.identity) { mutableStateOf<String?>(null) }
    var restoreNonce by remember(client.identity) { mutableStateOf(0) }
    var input by remember(client.identity) { mutableStateOf("") }
    var sending by remember(client.identity) { mutableStateOf(false) }
    var usage by remember(client.identity) { mutableStateOf(AiTokenSummary()) }
    var usageKnown by remember(client.identity) { mutableStateOf(true) }
    var showHistory by remember(client.identity) { mutableStateOf(false) }
    var multiSelectHistory by remember(client.identity) { mutableStateOf(false) }
    var selectedHistoryIds by remember(client.identity) { mutableStateOf<Set<String>>(emptySet()) }
    var historyBusy by remember(client.identity) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var conversations by remember(client.identity) { mutableStateOf<List<AiConversation>>(emptyList()) }
    var loadingConversations by remember(client.identity) { mutableStateOf(false) }
    var expandedHistoryDays by remember(client.identity) { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var editingConversationId by remember(client.identity) { mutableStateOf<String?>(null) }
    var editingConversationTitle by remember(client.identity) { mutableStateOf("") }
    var pendingDeleteConversation by remember(client.identity) { mutableStateOf<AiConversation?>(null) }
    var singleActionMessageKey by remember(client.identity) { mutableStateOf<String?>(null) }
    var partialCopyMessage by remember(client.identity) { mutableStateOf<AiMessage?>(null) }
    var multiSelectMessages by remember(client.identity) { mutableStateOf(false) }
    var selectedMessageKeys by remember(client.identity) { mutableStateOf<Set<String>>(emptySet()) }
    var messageSelectionBusy by remember(client.identity) { mutableStateOf(false) }
    var messageSelectionError by remember(client.identity) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    fun clearMessageActions() {
        singleActionMessageKey = null
        multiSelectMessages = false
        selectedMessageKeys = emptySet()
        messageSelectionError = null
    }
    fun deleteChatMessages(targets: List<AiMessage>) {
        if (messageSelectionBusy || targets.isEmpty()) return
        val targetConversationId = conversationId
        scope.launch {
            messageSelectionBusy = true
            messageSelectionError = null
            val removedKeys = mutableSetOf<String>()
            val failed = mutableListOf<String>()
            try {
                for (target in targets) {
                    val key = aiMessageSelectionKey(target)
                    if (target.serverId <= 0) {
                        removedKeys += key
                        continue
                    }
                    if (targetConversationId == null) {
                        failed += "消息 ${target.serverId}：未找到所属对话"
                        continue
                    }
                    try {
                        client.deleteConversationMessage(targetConversationId, target.serverId)
                        removedKeys += key
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        failed += "消息 ${target.serverId}：${error.message ?: "未知错误"}"
                    }
                }
                if (conversationId == targetConversationId) {
                    messages.removeAll { aiMessageSelectionKey(it) in removedKeys }
                    selectedMessageKeys = selectedMessageKeys - removedKeys
                    singleActionMessageKey?.let { key -> if (key in removedKeys) singleActionMessageKey = null }
                    if (multiSelectMessages && failed.isEmpty()) {
                        multiSelectMessages = false
                        selectedMessageKeys = emptySet()
                    }
                }
                messageSelectionError = if (failed.isEmpty()) null
                else "${failed.size} 条消息删除失败；已保留未删除项。"
            } finally {
                messageSelectionBusy = false
            }
        }
    }
    val messageActionActive = singleActionMessageKey != null || multiSelectMessages
    BackHandler(enabled = messageActionActive && !messageSelectionBusy) { clearMessageActions() }
    LaunchedEffect(messages.size, sending, pendingConfirmation) {
        val target = messages.size - 1 + if (pendingConfirmation != null || sending) 1 else 0
        if (target >= 0) runCatching { listState.animateScrollToItem(target) }
    }

    LaunchedEffect(client.identity, restoreNonce) {
        val hubChanged = AiChatSession.hubIdentity != client.identity
        AiChatSession.resetForHub(client.identity)
        if (hubChanged) {
            conversationId = null
            pendingConfirmation = null
            historyError = null
            clearMessageActions()
            partialCopyMessage = null
            loadingHistory = true
        }
        if (!AiChatSession.loaded) {
            clearMessageActions()
            partialCopyMessage = null
            // 先渲染欢迎语，恢复期间不再整屏空白
            if (messages.isEmpty()) messages += AiMessage("assistant", AI_GREETING)
            val hints = launch { runCatching { client.catalog() }.onSuccess { toolHints = it; AiChatSession.toolHints = it } }
            runCatching { client.latestConversation() }
                .onSuccess { (id, history) ->
                    AiChatSession.conversationId = id
                    messages.clear()
                    if (history.isEmpty()) messages += AiMessage("assistant", AI_GREETING) else messages.addAll(history)
                }
                .onFailure { error ->
                    historyError = "恢复最近对话失败：${error.message ?: "未知错误"}"
                    if (messages.isEmpty()) messages += AiMessage("assistant", AI_GREETING)
                }
            AiChatSession.loaded = historyError == null
            conversationId = AiChatSession.conversationId
            if (conversationId != null && historyError == null) {
                runCatching { client.pendingConfirmation(conversationId!!) }
                    .onSuccess { pendingConfirmation = it }
                    .onFailure { historyError = "读取待确认操作失败：${it.message ?: "未知错误"}" }
            }
            loadingHistory = false
            hints.join()
        } else {
            loadingHistory = false
            runCatching { client.catalog() }.onSuccess { toolHints = it; AiChatSession.toolHints = it }
            AiChatSession.conversationId?.let { id ->
                runCatching { client.pendingConfirmation(id) }
                    .onSuccess { pendingConfirmation = it }
                    .onFailure { historyError = "读取待确认操作失败：${it.message ?: "未知错误"}" }
            }
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AiHeader("AI 对话", "常用指令随 Hub 能力更新", onBack = {
            if (messageActionActive && !messageSelectionBusy) clearMessageActions() else onBack()
        }, trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp).clip(CircleShape).aiTap(enabled = !sending, onClick = {
                        clearMessageActions()
                        showHistory = !showHistory
                        if (showHistory && conversations.isEmpty() && !loadingConversations) {
                            loadingConversations = true
                            scope.launch {
                                 runCatching { client.listConversations() }.onSuccess {
                                     conversations = it
                                     expandedHistoryDays = aiHistoryDays(it).firstOrNull()?.let { day -> setOf(day.date) }.orEmpty()
                                }.onFailure { historyError = "读取历史失败：${it.message ?: "未知错误"}" }
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
                    modifier = Modifier.size(34.dp).clip(CircleShape).aiTap(enabled = !sending, onClick = {
                        AiChatSession.messages.clear()
                        AiChatSession.conversationId = null
                        messages.clear()
                        conversationId = null
                        pendingConfirmation = null
                        clearMessageActions()
                        partialCopyMessage = null
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
                    modifier = Modifier.size(34.dp).aiTap(enabled = !sending, onClick = onOpenSettings),
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
                        if (selectedHistoryIds.isNotEmpty()) {
                            Text("已选 ${selectedHistoryIds.size} 项", color = AiTone.MintDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        Text(
                            if (multiSelectHistory) "取消多选" else "多选",
                            color = if (multiSelectHistory) AiTone.Danger else AiTone.MintDark,
                            fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.aiTap {
                                multiSelectHistory = !multiSelectHistory
                                selectedHistoryIds = emptySet()
                            }.padding(horizontal = 8.dp),
                        )
                        Text("收起", color = AiTone.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.aiTap {
                            showHistory = false
                            multiSelectHistory = false
                            selectedHistoryIds = emptySet()
                        })
                    }
                    when {
                        loadingConversations -> Text("读取中…", color = AiTone.Muted, fontSize = 12.sp)
                        historyError != null -> Text(
                            "$historyError · 重试恢复",
                            color = AiTone.Danger,
                            fontSize = 12.sp,
                            modifier = Modifier.aiTap(enabled = !sending) {
                                AiChatSession.loaded = false
                                loadingHistory = true
                                historyError = null
                                restoreNonce++
                            },
                        )
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
                                            val historyInteractionSource = remember(convo.id) { MutableInteractionSource() }
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 6.dp)
                                                    .clip(AiControlShape)
                                                    .background(if (current) AiTone.MintSoft else AiTone.Field),
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().combinedClickable(
                                                        interactionSource = historyInteractionSource,
                                                        indication = null,
                                                        enabled = !sending && !historyBusy,
                                                        onLongClick = {
                                                            // 长按历史条目直接进入多选并选中当前项；
                                                            // 再点其它条目即可连续选择，支持批量复制/删除。
                                                            multiSelectHistory = true
                                                            selectedHistoryIds = selectedHistoryIds + convo.id
                                                        },
                                                        onClick = {
                                                        if (multiSelectHistory) {
                                                            selectedHistoryIds = if (convo.id in selectedHistoryIds) {
                                                                selectedHistoryIds - convo.id
                                                            } else {
                                                                selectedHistoryIds + convo.id
                                                            }
                                                        } else {
                                                            if (!current) {
                                                                scope.launch {
                                                                    loadingConversations = true
                                                                     runCatching { client.conversationMessages(convo.id) }
                                                                         .onSuccess { loaded ->
                                                                            pendingConfirmation = null
                                                                            AiChatSession.conversationId = convo.id
                                                                            conversationId = convo.id
                                                                            messages.clear()
                                                                            clearMessageActions()
                                                                            partialCopyMessage = null
                                                                            if (loaded.isEmpty()) messages += AiMessage("assistant", AI_GREETING) else messages.addAll(loaded)
                                                                            expandedHistoryDays += aiHistoryDate(convo.updatedAt)
                                                                            runCatching { client.pendingConfirmation(convo.id) }
                                                                                .onSuccess { pendingConfirmation = it }
                                                                                .onFailure { messages += AiMessage("assistant", "读取待确认操作失败：${it.message ?: "未知错误"}") }
                                                                         }
                                                                        .onFailure { messages += AiMessage("assistant", "读取对话失败：${it.message ?: "未知错误"}") }
                                                                    loadingConversations = false
                                                                }
                                                            }
                                                            showHistory = false
                                                        }
                                                    },
                                                    ).padding(horizontal = 10.dp, vertical = 8.dp),
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
                                                    if (multiSelectHistory) {
                                                        Box(
                                                            Modifier
                                                                .size(18.dp)
                                                                .clip(CircleShape)
                                                                .background(if (convo.id in selectedHistoryIds) AiTone.Mint else AiTone.Surface)
                                                                .aiTap {
                                                                    selectedHistoryIds = if (convo.id in selectedHistoryIds) {
                                                                        selectedHistoryIds - convo.id
                                                                    } else {
                                                                        selectedHistoryIds + convo.id
                                                                    }
                                                                },
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            if (convo.id in selectedHistoryIds) {
                                                                Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                                            } else {
                                                                Box(Modifier.size(14.dp).clip(CircleShape).border(BorderStroke(1.dp, AiTone.Border), CircleShape))
                                                            }
                                                        }
                                                    } else {
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
                                                    }
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
                                                                                    clearMessageActions()
                                                                                    partialCopyMessage = null
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
                    if (multiSelectHistory) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = AiTone.Field,
                            border = BorderStroke(1.dp, AiTone.Border),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "全选",
                                    color = AiTone.MintDark, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.aiTap {
                                        selectedHistoryIds = if (selectedHistoryIds.size == conversations.size) {
                                            emptySet()
                                        } else {
                                            conversations.map { it.id }.toSet()
                                        }
                                    }.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                                Spacer(Modifier.weight(1f))
                                AiAction(
                                    "复制选定对话",
                                    modifier = Modifier.weight(1.2f),
                                    compact = true,
                                    enabled = selectedHistoryIds.isNotEmpty() && !historyBusy,
                                ) {
                                    scope.launch {
                                        historyBusy = true
                                        val parts = mutableListOf<String>()
                                        conversations.filter { it.id in selectedHistoryIds }.forEach { convo ->
                                            runCatching { client.conversationMessages(convo.id) }.onSuccess { rows ->
                                                val title = convo.title.ifBlank { "对话 ${convo.updatedAt.take(16)}" }
                                                val body = rows.joinToString("\n") { row ->
                                                    (if (row.role == "user") "我" else "助手") + "：" + row.content
                                                }
                                                parts += "【$title】\n$body"
                                            }
                                        }
                                        historyBusy = false
                                        if (parts.isEmpty()) {
                                            messages += AiMessage("assistant", "没有可复制的内容。")
                                        } else {
                                            clipboard.setText(AnnotatedString(parts.joinToString("\n\n")))
                                            messages += AiMessage("assistant", "已复制 ${parts.size} 个对话的内容。")
                                        }
                                        multiSelectHistory = false
                                        selectedHistoryIds = emptySet()
                                        showHistory = false
                                    }
                                }
                                AiAction(
                                    "删除(${selectedHistoryIds.size})",
                                    modifier = Modifier.weight(1.2f),
                                    tone = AiTone.Danger,
                                    compact = true,
                                    enabled = selectedHistoryIds.isNotEmpty() && !historyBusy,
                                ) {
                                    scope.launch {
                                        historyBusy = true
                                        var removedCurrent = false
                                        val removedIds = buildSet {
                                            selectedHistoryIds.forEach { id ->
                                                runCatching { client.deleteConversation(id) }
                                                    .onSuccess {
                                                        add(id)
                                                        if (AiChatSession.conversationId == id) removedCurrent = true
                                                    }
                                                    .onFailure { messages += AiMessage("assistant", "删除对话失败：${it.message ?: "未知错误"}") }
                                            }
                                        }
                                        conversations = conversations.filterNot { it.id in removedIds }
                                        if (removedCurrent) {
                                            AiChatSession.conversationId = null
                                            AiChatSession.messages.clear()
                                            messages.clear()
                                            conversationId = null
                                            clearMessageActions()
                                            partialCopyMessage = null
                                            messages += AiMessage("assistant", AI_GREETING)
                                        }
                                        historyBusy = false
                                        multiSelectHistory = false
                                        selectedHistoryIds = emptySet()
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
            items(
                items = messages,
                key = { message -> aiMessageSelectionKey(message) },
            ) { message ->
                val selectionKey = aiMessageSelectionKey(message)
                AiChatBubble(
                    message = message,
                    multiSelectMode = multiSelectMessages,
                    selected = selectionKey in selectedMessageKeys,
                    interactionEnabled = !sending && !messageSelectionBusy,
                    onToggleSelection = {
                        messageSelectionError = null
                        selectedMessageKeys = if (selectionKey in selectedMessageKeys) {
                            selectedMessageKeys - selectionKey
                        } else {
                            selectedMessageKeys + selectionKey
                        }
                    },
                    onOpenActions = {
                        singleActionMessageKey = selectionKey
                        messageSelectionError = null
                    },
                )
            }
            retryText?.let { retry ->
                item(key = "switch-model-card") {
                    AiSwitchModelCard(
                        retryPreview = retry,
                        onSwitch = {
                            scope.launch {
                                runCatching { client.configs() }.onSuccess { bundle ->
                                    pickerConfigs = bundle.configs.filter { it.enabled }
                                    modelPickerOpen = true
                                }.onFailure { messages += AiMessage("assistant", "读取配置失败：${it.message ?: "未知错误"}") }
                            }
                        },
                        onDismiss = { retryText = null },
                    )
                }
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
                                    sending = true
                                    val confirmationConversationId = conversationId
                                    scope.launch {
                                        try {
                                            val message = client.cancelHubTool(confirmation.confirmationId)
                                            if (conversationId == confirmationConversationId && pendingConfirmation?.confirmationId == confirmation.confirmationId) {
                                                pendingConfirmation = null
                                                messages += AiMessage("assistant", message)
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (error: Throwable) {
                                            messages += AiMessage("assistant", "取消失败：${error.message ?: "未知错误"}")
                                        } finally {
                                            sending = false
                                        }
                                    }
                                }
                                AiAction("确认执行", Modifier.weight(1f), primary = true, enabled = !sending) {
                                    sending = true
                                    val confirmationConversationId = conversationId
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
                                            if (conversationId == confirmationConversationId && pendingConfirmation?.confirmationId == confirmation.confirmationId) {
                                                messages += AiMessage("assistant", message)
                                                pendingConfirmation = null
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (error: Throwable) {
                                            val status = runCatching { client.toolConfirmationStatus(confirmation.confirmationId) }.getOrNull()
                                            val detail = when (status) {
                                                "completed" -> {
                                                    pendingConfirmation = null
                                                    "Hub 已确认操作执行完成；仅执行结果响应在传输中丢失，不会重复执行。"
                                                }
                                                "failed" -> {
                                                    pendingConfirmation = null
                                                    "Hub 已确认操作执行失败；不会自动重试。"
                                                }
                                                "cancelled", "expired" -> {
                                                    pendingConfirmation = null
                                                    if (status == "cancelled") "操作已取消，未执行。" else "确认已过期，操作未执行。"
                                                }
                                                "pending", "executing" -> "执行响应丢失，Hub 当前状态：$status；请勿重复确认。"
                                                null, "" -> "执行响应丢失，Hub 状态未确认：${error.message ?: "未知错误"}"
                                                else -> "执行响应丢失，Hub 当前状态：$status"
                                            }
                                            messages += AiMessage("assistant", detail)
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
        singleActionMessageKey?.let { key -> messages.firstOrNull { aiMessageSelectionKey(it) == key } }?.let { targetMessage ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = AiTone.Field,
                border = BorderStroke(1.dp, AiTone.Mint.copy(alpha = .45f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("当前消息", color = AiTone.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "关闭",
                            color = AiTone.Muted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.aiTap(enabled = !messageSelectionBusy) {
                                singleActionMessageKey = null
                                messageSelectionError = null
                            }.padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiAction("复制全文", Modifier.weight(1f), compact = true, enabled = !messageSelectionBusy) {
                            clipboard.setText(AnnotatedString(targetMessage.content))
                            singleActionMessageKey = null
                            messageSelectionError = null
                        }
                        AiAction("局部复制", Modifier.weight(1f), compact = true, enabled = !messageSelectionBusy) {
                            partialCopyMessage = targetMessage
                            singleActionMessageKey = null
                            messageSelectionError = null
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiAction(
                            "删除消息",
                            Modifier.weight(1f),
                            tone = AiTone.Danger,
                            compact = true,
                            enabled = !messageSelectionBusy && !sending,
                        ) { deleteChatMessages(listOf(targetMessage)) }
                        AiAction("多选", Modifier.weight(1f), compact = true, enabled = !messageSelectionBusy && !sending) {
                            selectedMessageKeys = setOf(aiMessageSelectionKey(targetMessage))
                            multiSelectMessages = true
                            singleActionMessageKey = null
                            messageSelectionError = null
                        }
                    }
                    messageSelectionError?.let { error ->
                        Text(error, color = AiTone.Danger, fontSize = 10.5.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
        if (multiSelectMessages) {
            val selectedMessages = messages.filter { aiMessageSelectionKey(it) in selectedMessageKeys }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = AiTone.Field,
                border = BorderStroke(1.dp, AiTone.Mint.copy(alpha = .45f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selectedMessages.size} 条消息", color = AiTone.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "取消",
                            color = AiTone.Muted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.aiTap(enabled = !messageSelectionBusy) {
                                clearMessageActions()
                            }.padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiAction(
                            "复制选定对话",
                            modifier = Modifier.weight(1f),
                            compact = true,
                            enabled = selectedMessages.isNotEmpty() && !messageSelectionBusy,
                        ) {
                            clipboard.setText(AnnotatedString(aiSelectedMessagesText(selectedMessages)))
                            clearMessageActions()
                        }
                        AiAction(
                            "删除(${selectedMessages.size})",
                            modifier = Modifier.weight(1f),
                            tone = AiTone.Danger,
                            compact = true,
                            enabled = selectedMessages.isNotEmpty() && !messageSelectionBusy && !sending,
                        ) {
                            deleteChatMessages(selectedMessages)
                        }
                    }
                    messageSelectionError?.let { error ->
                        Text(error, color = AiTone.Danger, fontSize = 10.5.sp, lineHeight = 14.sp)
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
        val canSend = input.isNotBlank() && !sending && !loadingHistory && pendingConfirmation == null && !messageActionActive && partialCopyMessage == null
        val sendNow = {
            if (input.isNotBlank() && !sending && !loadingHistory && pendingConfirmation == null && !messageActionActive && partialCopyMessage == null) {
                val text = input.trim()
                val requestConversationId = conversationId
                retryText = null
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
                            text, requestConversationId,
                            onDelta = delta@{ piece ->
                                if (conversationId != requestConversationId) return@delta
                                streamed = true
                                val index = liveBubble()
                                messages[index] = AiMessage("assistant", messages[index].content + piece)
                            },
                            onReset = reset@{
                                if (conversationId != requestConversationId) return@reset
                                liveIndex?.let { index ->
                                    if (messages.getOrNull(index) != null) messages[index] = AiMessage("assistant", "")
                                }
                            },
                        )
                        if (conversationId != requestConversationId) {
                            sending = false
                            return@launch
                        }
                        conversationId = reply.conversationId ?: requestConversationId
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
                        if (conversationId != requestConversationId) {
                            sending = false
                            return@launch
                        }
                        conversationId = error.conversationId ?: requestConversationId
                        AiChatSession.conversationId = conversationId
                        if (error.userMessageId > 0) {
                            val userIndex = messages.indexOfFirst { it === userMessage }
                            if (userIndex >= 0) {
                                messages[userIndex] = userMessage.copy(serverId = error.userMessageId)
                            }
                        }
                        val existing = liveIndex?.let { messages.getOrNull(it) }
                        val failure = AiMessage("assistant", "请求失败：${error.message ?: "未知错误"}", serverId = error.messageId)
                        if (error.messageId > 0 && liveIndex != null && messages.getOrNull(liveIndex!!) != null) {
                            messages[liveIndex!!] = failure
                        } else {
                            if (existing != null && existing.content.isBlank()) messages.removeAt(liveIndex!!)
                            if (error.messageId > 0 || existing == null || existing.content.isNotBlank()) messages += failure
                        }
                        if ((error.message ?: "").contains("自动切换已停用")) retryText = text
                    } catch (error: Throwable) {
                        val existing = liveIndex?.let { messages.getOrNull(it) }
                        if (existing != null && existing.content.isBlank()) messages.removeAt(liveIndex!!)
                        if (existing == null || existing.content.isNotBlank()) {
                            messages += AiMessage("assistant", "请求失败：${error.message ?: "未知错误"}")
                        }
                        if ((error.message ?: "").contains("自动切换已停用")) retryText = text
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
                enabled = !sending && !loadingHistory && pendingConfirmation == null && !messageActionActive && partialCopyMessage == null,
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
        if (modelPickerOpen) {
            AiModelPickerDialog(
                configs = pickerConfigs,
                onDismiss = { modelPickerOpen = false },
                onPick = { config ->
                    modelPickerOpen = false
                    val retry = retryText
                    scope.launch {
                        runCatching { client.promoteConfig(config.id) }
                            .onSuccess { saved ->
                                messages += AiMessage("assistant", "已切换到 ${saved.name.ifBlank { saved.model }}（${saved.model}），正在重试…")
                                retryText = null
                                retry?.let { input = it; sendNow() }
                            }
                            .onFailure { messages += AiMessage("assistant", "切换失败：${it.message ?: "未知错误"}") }
                    }
                },
            )
        }
        partialCopyMessage?.let { message ->
            AiPartialCopyDialog(message = message, onDismiss = { partialCopyMessage = null })
        }
    }
}
