package com.labprobe.app

import com.labprobe.app.ui.design.LabActionIcons

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BasicTooltipBox
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.painterResource
import com.labprobe.app.feature.router.firewall.FirewallAutomationBinding
import com.labprobe.app.feature.router.firewall.FirewallAutomationPage
import com.labprobe.app.feature.router.firewall.FirewallAutomationRepository
import com.labprobe.app.feature.router.firewall.FirewallAutomationTargets
import com.labprobe.app.feature.router.firewall.firewallAutomationStatusColor
import com.labprobe.app.feature.router.firewall.firewallAutomationStatusLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val RouterBlue = Color(0xFF0284C7)
private val RouterCyan = Color(0xFF0EA5E9)
private val RouterGreen = Color(0xFF16A36A)
private val RouterAmber = Color(0xFFF59E0B)
private val RouterRed = Color(0xFFE94B55)
private val RouterInk = Color(0xFF17233A)
private val RouterMuted = Color(0xFF687890)
private val RouterField = Color(0xFFFBFDFF)
private val RouterBorder = Color(0xFFD9E8F7)
private val RouterPage = Color(0xFFF8FBFF)

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
    val raw = routerScopedPreferences(context, "router_control")
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
    routerScopedPreferences(context, "router_control")
        .edit()
        .putString(ROUTER_DIAGNOSTIC_CACHE_PREF, result.toCacheJson().toString())
        .apply()
}

private object RouterControlMemoryCache {
    var ddnsRows: List<DdnsRecord> = emptyList()
}

@Composable
fun RouterFeatureRail(
    firewallEnabled: Int,
    ddnsHealthy: Int,
    mappingCount: Int,
    upnpEnabled: Boolean,
    diagnosticErrors: Int,
    onConnection: () -> Unit,
    onMapping: () -> Unit,
    onDdns: () -> Unit,
    onFirewall: () -> Unit,
    onDiagnostic: () -> Unit
) {
    val connection = RouterConnectionStore.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("路由器功能", fontSize = LabTypography.SectionTitle.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onConnection,
                shape = RoundedCornerShape(99.dp),
                color = if (connection.connected) RouterGreen.copy(alpha = .09f) else RouterMuted.copy(alpha = .07f),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (connection.connected) RouterGreen.copy(alpha = .16f) else RouterBorder)
            ) {
                Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (connection.connected) RouterGreen else RouterMuted, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(connection.statusText, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = if (connection.connected) RouterGreen else RouterMuted)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Settings, "路由器连接", Modifier.size(14.dp), tint = RouterBlue)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            RouterFeatureCard("映射与UPnP", "$mappingCount 条 · ${if (upnpEnabled) "已开启" else "已关闭"}", RouterBlue, RouterGlyph.Mapping, onMapping)
            RouterFeatureCard("DDNS", "$ddnsHealthy 条正常", RouterCyan, RouterGlyph.Ddns, onDdns)
            RouterFeatureCard("防火墙", "$firewallEnabled 条启用", RouterGreen, RouterGlyph.Firewall, onFirewall)
            RouterFeatureCard("网络自检", if (diagnosticErrors == 0) "状态正常" else "$diagnosticErrors 项异常", if (diagnosticErrors == 0) RouterBlue else RouterAmber, RouterGlyph.Diagnostic, onDiagnostic)
        }
    }
}

enum class RouterGlyph { Mapping, Ddns, Firewall, Diagnostic, Upnp, Port, Connection, Beta }

@Composable
private fun RouterFeatureCard(title: String, status: String, accent: Color, glyph: RouterGlyph, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(104.dp).height(72.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = LabCoreSurface.CompactShape,
        color = LabCoreSurface.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, LabCoreSurface.Border),
        shadowElevation = 2.dp
    ) {
        Box(
            Modifier.fillMaxSize()
                .padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Box(
                    Modifier.size(38.dp).background(accent.copy(alpha = .10f), LabCoreSurface.InnerShape),
                    contentAlignment = Alignment.Center
                ) {
                    RouterGlyphIcon(glyph, accent, Modifier.size(20.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, fontSize = LabTypography.Supporting.fontSize, lineHeight = LabTypography.Supporting.lineHeight, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1)
                    Text(status, fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.align(Alignment.TopEnd).size(5.dp).background(accent.copy(alpha = .85f), CircleShape))
        }
    }
}

@Composable
internal fun RouterGlyphIcon(glyph: RouterGlyph, color: Color, modifier: Modifier = Modifier) {
    if (glyph == RouterGlyph.Mapping || glyph == RouterGlyph.Port) {
        Image(
            painter = painterResource(R.drawable.port_mapping),
            contentDescription = null,
            modifier = modifier,
            colorFilter = ColorFilter.tint(color),
        )
        return
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * .064f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            RouterGlyph.Mapping, RouterGlyph.Port -> Unit
  RouterGlyph.Ddns -> {
                // Domain resolution: a compact globe instead of a cloud/node cluster.
                val center = Offset(w * .50f, h * .50f)
                val globe = w * .32f
                drawCircle(color.copy(alpha = .10f), globe, center)
                drawCircle(color, globe, center, style = stroke)
                drawArc(color, 90f, 180f, false, Offset(w * .32f, h * .18f), Size(w * .36f, h * .64f), style = stroke)
                drawArc(color, 270f, 180f, false, Offset(w * .32f, h * .18f), Size(w * .36f, h * .64f), style = stroke)
                drawLine(color, Offset(w * .20f, h * .50f), Offset(w * .80f, h * .50f), stroke.width, StrokeCap.Round)
            }
            RouterGlyph.Firewall -> {
                val shield = Path().apply {
                    moveTo(w*.50f,h*.08f); lineTo(w*.84f,h*.21f); lineTo(w*.80f,h*.58f)
                    cubicTo(w*.76f,h*.78f,w*.61f,h*.89f,w*.50f,h*.95f)
                    cubicTo(w*.39f,h*.89f,w*.24f,h*.78f,w*.20f,h*.58f); lineTo(w*.16f,h*.21f); close()
                }
                drawPath(shield,color.copy(alpha=.11f)); drawPath(shield,color,style=stroke)
                drawLine(color,Offset(w*.34f,h*.43f),Offset(w*.66f,h*.43f),stroke.width,StrokeCap.Round)
                drawLine(color,Offset(w*.34f,h*.60f),Offset(w*.66f,h*.60f),stroke.width,StrokeCap.Round)
            }
            RouterGlyph.Diagnostic -> {
                drawCircle(color.copy(alpha=.10f),w*.42f,Offset(w*.50f,h*.50f))
                val p=Path().apply{moveTo(w*.11f,h*.54f);lineTo(w*.31f,h*.54f);lineTo(w*.40f,h*.31f);lineTo(w*.53f,h*.73f);lineTo(w*.63f,h*.45f);lineTo(w*.88f,h*.45f)}
                drawPath(p,color,style=stroke)
            }
            RouterGlyph.Upnp -> {
                drawRoundRect(color.copy(alpha=.10f),Offset(w*.18f,h*.47f),Size(w*.64f,h*.34f),CornerRadius(w*.12f,w*.12f))
                drawRoundRect(color,Offset(w*.18f,h*.47f),Size(w*.64f,h*.34f),CornerRadius(w*.12f,w*.12f),style=stroke)
                drawCircle(color,w*.035f,Offset(w*.33f,h*.64f)); drawCircle(color,w*.035f,Offset(w*.48f,h*.64f))
                drawArc(color,210f,120f,false,Offset(w*.27f,h*.05f),Size(w*.46f,h*.40f),style=stroke)
            }
            RouterGlyph.Connection -> {
      // Shared double-lightning symbol for router-control health everywhere.
      fun bolt(cx: Float): Path = Path().apply {
          moveTo(cx + w * .04f, h * .12f)
          lineTo(cx - w * .09f, h * .50f)
          lineTo(cx - w * .01f, h * .50f)
          lineTo(cx - w * .06f, h * .88f)
          lineTo(cx + w * .11f, h * .42f)
          lineTo(cx + w * .02f, h * .42f)
          close()
      }
      drawPath(bolt(w * .34f), color)
      drawPath(bolt(w * .66f), color)
  }
  RouterGlyph.Beta -> {
                // Firmware package with an upward upgrade arrow.
                drawRoundRect(color.copy(alpha = .10f), Offset(w * .20f, h * .24f), Size(w * .60f, h * .52f), CornerRadius(w * .08f, w * .08f))
                drawRoundRect(color, Offset(w * .20f, h * .24f), Size(w * .60f, h * .52f), CornerRadius(w * .08f, w * .08f), style = stroke)
                drawLine(color, Offset(w * .50f, h * .63f), Offset(w * .50f, h * .35f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .39f, h * .46f), Offset(w * .50f, h * .35f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .61f, h * .46f), Offset(w * .50f, h * .35f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .34f, h * .68f), Offset(w * .66f, h * .68f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingAndUpnpScreen(
    prefs: AppPrefs,
    onBack: () -> Unit,
    onOpenSsh: (String, Int) -> Unit = { _, _ -> },
    onOpenWireGuard: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val capabilityResource by repository.capabilities.collectAsState()
    val wanResource by repository.wans.collectAsState()
    val pages = remember(capabilityResource.value, wanResource.value) {
        mappingSuitePages(capabilityResource.value, wanResource.value)
    }
    val pager = rememberPagerState(initialPage = 0, pageCount = { pages.size })
    LaunchedEffect(repository) {
        repository.refreshCapabilities()
        repository.refreshWans()
    }
    LaunchedEffect(pages.size) {
        if (pager.currentPage >= pages.size) pager.scrollToPage(pages.lastIndex)
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Surface(color = Color.White) {
                Column {
                    CompactTopBar("映射与 UPnP", onBack)
                    RouterSuiteTabs(pages.map(::mappingSuitePageTitle), pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(padding).labV2PageBackground(),
            beyondViewportPageCount = 1,
            key = { pages[it] },
        ) { page ->
            when (pages[page]) {
                "ipv6" -> PortMappingScreen(
                    prefs = prefs,
                    onBack = onBack,
                    embedded = true,
                    onOpenSsh = onOpenSsh,
                    onOpenWireGuard = onOpenWireGuard,
                    onOpenFavorites = onOpenFavorites,
                )
                "portMapping" -> NativePortMappingPage(prefs, capabilityResource.value?.nativePortMapping ?: true)
                else -> UpnpPage(prefs, capabilityResource.value?.upnp ?: true)
            }
        }
    }
}


internal fun mappingSuitePages(capabilities: RouterCapabilities?, inventory: WanInventory?): List<String> = buildList {
    add("ipv6")
    if ((capabilities?.nativePortMapping ?: true) && (inventory?.supportsLineCapability("portMapping") ?: true)) add("portMapping")
    if ((capabilities?.upnp ?: true) && (inventory?.supportsLineCapability("upnp") ?: true)) add("upnp")
}

private fun mappingSuitePageTitle(page: String): String = when (page) {
    "portMapping" -> "端口映射"
    "upnp" -> "UPnP"
    else -> "IPv6映射"
}


@Composable
private fun RouterSuiteTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(18.dp),
        color = LabV2.FieldSoft,
        border = BorderStroke(1.dp, LabV2.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            titles.forEachIndexed { index, title ->
                val active = selected == index
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = if (active) Color(0xFFE8F1FF) else Color.Transparent,
                    border = if (active) BorderStroke(1.dp, RouterBlue.copy(alpha = .13f)) else null,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) RouterBlue else RouterMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MappingOverviewSurface(
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(LabCoreSurface.CardShape)
            .background(Brush.linearGradient(listOf(Color.White, Color(0xFFF3FAFF), Color(0xFFE7F5FF))))
            .border(1.dp, LabCoreSurface.Border, LabCoreSurface.CardShape),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
        MappingOverviewWave(Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MappingOverviewWave(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        val width = size.width
        val height = size.height
        val path = Path().apply {
            moveTo(0f, height * .58f)
            cubicTo(width * .22f, height * .12f, width * .42f, height * .95f, width * .64f, height * .48f)
            cubicTo(width * .82f, height * .12f, width * .93f, height * .72f, width, height * .34f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        drawPath(path, RouterBlue.copy(alpha = .045f))
        val second = Path().apply {
            moveTo(0f, height * .82f)
            cubicTo(width * .28f, height * .45f, width * .48f, height * .96f, width * .72f, height * .67f)
            cubicTo(width * .86f, height * .50f, width * .95f, height * .78f, width, height * .60f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        drawPath(second, RouterBlue.copy(alpha = .03f))
    }
}

@Composable
internal fun MappingStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = RouterBlue,
    numberSize: Int = 24,
    labelSize: Int = 11,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (icon != null) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(iconTint.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(14.dp), tint = iconTint)
            }
        }
        Text(value, fontSize = numberSize.sp, lineHeight = (numberSize + 2).sp, fontWeight = FontWeight.Bold, color = RouterBlue, maxLines = 1)
        Text(label, fontSize = labelSize.sp, lineHeight = (labelSize + 2).sp, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1)
    }
}

@Composable
internal fun MappingStatusPill(text: String, color: Color, modifier: Modifier = Modifier, textSize: Int = 10) {
    Surface(modifier = modifier, shape = RoundedCornerShape(99.dp), color = color.copy(alpha = .10f)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(color))
            Text(text, fontSize = textSize.sp, lineHeight = (textSize + 2).sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun NativePortMappingPage(prefs: AppPrefs, nativePortMappingSupported: Boolean) {
    val mappingCache = remember(prefs.hub, prefs.token) { PortMappingMemoryCache.forScope(prefs.hub, prefs.token) }
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val resource by repository.portMappings.collectAsState()
    val wanResource by repository.wans.collectAsState()
    val rules = resource.value.orEmpty()
    val scope = repository.commandScope
    var devices by remember(mappingCache) { mutableStateOf(mappingCache.devices) }
    var devicesLoading by remember(mappingCache) { mutableStateOf(false) }
    var actionError by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NativePortMapRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<NativePortMapRule?>(null) }
    val error = actionError.ifBlank { resource.error }
    val mappingSupported = nativePortMappingSupported &&
        (wanResource.value?.supportsLineCapability("portMapping") ?: true)
    val canCreateMapping = mappingSupported &&
        (wanResource.value?.wans.isNullOrEmpty() || wanResource.value?.selectable("portMapping")?.isNotEmpty() == true)

    suspend fun refreshDevices(force: Boolean = false): List<DeviceItem> {
        if (devices.isNotEmpty() && !force) return devices
        devicesLoading = true
        return runCatching { loadCanonicalPortMappingDevices(deviceApi, mappingCache, forceRefresh = force) }
            .getOrElse { devices }
            .also {
                if (mappingCache !== PortMappingMemoryCache.forScope(prefs.hub, prefs.token)) return emptyList()
                if (it.isNotEmpty()) devices = it
                devicesLoading = false
            }
    }

    LaunchedEffect(mappingCache) { refreshDevices() }
    LaunchedEffect(repository) { repository.refreshWans() }

    val favorites = prefs.favoriteShortcuts()
    val favoriteHints = remember(favorites) { favoriteServiceHints(favorites) }
    val listState = rememberLazyListState()
    val syncLabel = when {
        resource.value == null && resource.refreshing -> "读取中"
        resource.error.isNotBlank() || resource.stale -> "待同步"
        resource.refreshing -> "同步中"
        resource.value != null -> "已同步"
        else -> "未读取"
    }
    val syncColor = when (syncLabel) {
        "已同步" -> RouterGreen
        "待同步" -> RouterAmber
        else -> RouterBlue
    }

    Box(Modifier.fillMaxSize().labV2PageBackground()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                MappingOverviewSurface {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("路由器原生映射", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = RouterInk, maxLines = 1)
                            MappingStat(rules.size.toString(), "条规则", icon = Icons.Rounded.CompareArrows)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MappingStatusPill(syncLabel, syncColor)
                            IconButton(
                                onClick = { scope.launch { repository.refreshPortMappings(true) } },
                                enabled = !resource.refreshing,
                                modifier = Modifier.size(32.dp),
                            ) { LabRefreshIcon(resource.refreshing, Modifier.size(17.dp), RouterBlue) }
                        }
                    }
                }
            }
            if (error.isNotBlank()) item { CompactMessage(error, RouterAmber) }
            if (!mappingSupported) item { CompactMessage("当前固件或 Hub 未提供可用的原生端口映射线路", RouterAmber) }
            if (resource.mutating) item { CompactMessage("设置正在后台应用，页面可以安全退出", RouterBlue) }
            if (resource.value == null) item { CompactMessage("端口映射正在后台预加载，页面无需等待", RouterBlue) }
            if (resource.value != null && rules.isEmpty()) item { CompactEmpty("暂无端口映射", "路由器已有规则和新建规则都会显示在这里", RouterGlyph.Port) { if (canCreateMapping) adding = true } }
            items(rules, key = { "${it.ruleName}|${it.proto}|${it.srcPort}|${it.destIp}|${it.destPort}" }) { rule ->
                NativePortRuleCard(
                    rule = rule,
                    devices = devices,
                    favoriteHints = favoriteHints,
                    onEdit = { editing = rule },
                    onDelete = { deleteTarget = rule },
                )
            }
        }
        if (mappingSupported && canCreateMapping) {
            Surface(
                onClick = { adding = true },
                shape = CircleShape,
                color = RouterBlue,
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp).size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "新建端口映射", tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
        }
    }

    if (adding || editing != null) {
        val preferredWan = wanResource.value?.preferred("portMapping")
        NativePortEditorSheet(
            initial = editing ?: NativePortMapRule(src = preferredWan?.wanId ?: "wan"),
            existingNames = rules.map { it.ruleName }.toSet(),
            wanInventory = wanResource.value,
            devices = devices,
            devicesLoading = devicesLoading,
            refreshDevices = { refreshDevices(force = true) },
            onDismiss = { adding = false; editing = null },
            onSave = { saved ->
                scope.launch {
                    val result = if (editing == null) repository.addPortMapping(saved)
                    else repository.updatePortMapping(editing!!.ruleName, saved)
                    result.onSuccess { adding = false; editing = null; actionError = "" }
                        .onFailure { actionError = it.message.orEmpty().ifBlank { "端口映射设置未生效，请稍后重试" } }
                }
            }
        )
    }
    deleteTarget?.let { target ->
        ConfirmDialog("删除端口映射？", "删除“${target.ruleName}”后，外部访问会立即中断。", "删除", {
            scope.launch {
                repository.deletePortMapping(target.ruleName)
                    .onSuccess { deleteTarget = null; actionError = "" }
                    .onFailure { actionError = it.message.orEmpty() }
            }
        }) { deleteTarget = null }
    }
}

@Composable
internal fun MappingTag(text: String, accent: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (accent) Color(0xFFE0F2FE) else LabCoreSurface.Inner,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (accent) RouterBlue else RouterMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun NativePortRuleCard(
    rule: NativePortMapRule,
    devices: List<DeviceItem>,
    favoriteHints: List<FavoriteServiceHint>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember(rule.ruleName, rule.proto, rule.destPort) { mutableStateOf(false) }
    val protocol = rule.proto.uppercase(Locale.ROOT).ifBlank { "TCP" }
    val recognition = remember(rule, devices, favoriteHints) {
        recognizeMappingService(
            name = rule.ruleName,
            explicitType = "",
            protocol = protocol,
            internalPort = rule.destPort,
            clientIp = rule.destIp,
            devices = devices,
            favoriteHints = favoriteHints,
        )
    }
    val showServiceTag = recognition.serviceType.isNotBlank() &&
        !recognition.serviceType.equals(protocol, true) &&
        !recognition.serviceType.equals("Custom", true)
    val destination = buildString {
        append(rule.destIp.ifBlank { "--" })
        if (rule.destPort.isNotBlank()) append(':').append(rule.destPort)
    }
    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CardShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = .85f)),
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FavoriteIcon("builtin", recognition.iconId, 40)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        rule.ruleName.ifBlank { "端口映射" },
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = RouterInk,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                    )
                    MappingTag(protocol)
                    if (showServiceTag) MappingTag(recognition.serviceType, accent = true)
                    if (recognition.mechanism.isNotBlank()) MappingTag(recognition.mechanism)
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.MoreVert, "端口映射操作", Modifier.size(18.dp), tint = RouterMuted)
                        }
                        LabDropdownMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            shape = LabPopupShapes.Menu,
                            containerColor = Color.White,
                            shadowElevation = 8.dp,
                            actionMenu = true,
                        ) {
                            LabDropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                            LabDropdownMenuItem(text = { Text("删除", color = RouterRed) }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
                Text(
                    "${nativePortWanLabel(rule)}:${rule.srcPort.ifBlank { "--" }} → $destination",
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RouterMuted,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

internal fun nativePortMapLineOptions(wanInventory: WanInventory?): List<String> =
    (listOf("all") + wanInventory?.selectable("portMapping").orEmpty().map { it.wanId })
        .filter { it.isNotBlank() }
        .distinct()

internal fun nativePortMapShowsLineSelector(wanInventory: WanInventory?): Boolean =
    (wanInventory?.selectable("portMapping")?.size ?: 0) > 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativePortEditorSheet(
    initial: NativePortMapRule,
    existingNames: Set<String>,
    wanInventory: WanInventory?,
    devices: List<DeviceItem>,
    devicesLoading: Boolean,
    refreshDevices: suspend () -> List<DeviceItem>,
    onDismiss: () -> Unit,
    onSave: (NativePortMapRule) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var sourceMode by remember(initial) { mutableStateOf(if (initial.srcIp.isBlank()) "任意来源" else "指定IP") }
    var error by remember { mutableStateOf("") }
    var showDevicePicker by remember { mutableStateOf(false) }
    val lineOptions = remember(wanInventory) { nativePortMapLineOptions(wanInventory) }
    val showLineSelector = nativePortMapShowsLineSelector(wanInventory)
    val scope = rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LabGradientDialogSurface(modifier = Modifier.fillMaxWidth(.94f).imePadding().heightIn(max = 680.dp), color = RouterPage) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (initial.ruleName.isBlank()) "新增端口映射" else "编辑端口映射", fontSize = LabTypography.CardTitle.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
                    Text("路由器原生 IPv4 NAT", fontSize = LabTypography.Caption.fontSize, color = RouterMuted)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, null, Modifier.size(19.dp)) }
            }
            CompactField("规则名称", draft.ruleName, "例如 NAS管理") { draft = draft.copy(ruleName = it.take(24)) }
            if (showLineSelector) {
                CompactChoice("线路", draft.wanId, lineOptions, display = { option ->
                    when (option) {
                        "all" -> "全部WAN"
                        else -> option.uppercase(Locale.ROOT)
                    }
                }) { selected ->
                    draft = draft.copy(src = if (selected == "all") "" else selected)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactChoice("协议", draft.proto, listOf("tcp", "udp", "tcp+udp"), Modifier.weight(1f)) { draft = draft.copy(proto = it) }
                CompactChoice("来源", sourceMode, listOf("任意来源", "指定IP"), Modifier.weight(1f)) { selected ->
                    sourceMode = selected
                    if (selected == "任意来源") draft = draft.copy(srcIp = "")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactField("外部端口", draft.srcPort, "80 或 1000-2000", Modifier.weight(1f), KeyboardType.Ascii) { draft = draft.copy(srcPort = it.take(32)) }
                CompactField("内部端口", draft.destPort, "80", Modifier.weight(1f), KeyboardType.Ascii) { draft = draft.copy(destPort = it.take(32)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                CompactField("内部设备 / IP", draft.destIp, "192.168.1.2", Modifier.weight(1f), KeyboardType.Ascii) { draft = draft.copy(destIp = it.take(64)) }
                OutlinedButton(
                    onClick = { showDevicePicker = true },
                    modifier = Modifier.heightIn(min = 40.dp),
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 11.dp),
                    border = BorderStroke(1.dp, RouterBlue.copy(alpha = .35f)),
                ) {
                    Icon(Icons.Rounded.Devices, "选择终端", Modifier.size(17.dp), tint = RouterBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("选择", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterBlue)
                }
            }
            Text("可手动填写地址，也可从终端列表关联路由器内网设备。", fontSize = LabTypography.Caption.fontSize, color = RouterMuted, fontWeight = FontWeight.SemiBold)
            AnimatedVisibility(sourceMode == "指定IP") {
                CompactField("允许来源IP", draft.srcIp, "例如 10.0.0.8", keyboardType = KeyboardType.Ascii) { draft = draft.copy(srcIp = it.take(64)) }
            }
            if (error.isNotBlank()) Text(error, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterRed)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp)) { Text("取消", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = {
                        error = when {
                            draft.ruleName.isBlank() -> "请填写规则名称"
                            initial.ruleName != draft.ruleName && draft.ruleName in existingNames -> "规则名称必须唯一"
                            draft.srcPort.isBlank() || draft.destPort.isBlank() -> "请填写外部和内部端口"
                            draft.destIp.isBlank() -> "请填写内部IP"
                            else -> ""
                        }
                        if (error.isBlank()) onSave(draft)
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RouterBlue)
                ) { Text("保存并同步", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showDevicePicker) {
        NativePortDevicePickerDialog(
            devices = devices,
            loading = devicesLoading,
            selectedIp = draft.destIp,
            onRefresh = {
                scope.launch { refreshDevices() }
            },
            onDismiss = { showDevicePicker = false },
            onPick = { device ->
                draft = draft.copy(destIp = device.ip)
                showDevicePicker = false
            },
        )
    }
}

@Composable
private fun NativePortDevicePickerDialog(
    devices: List<DeviceItem>,
    loading: Boolean,
    selectedIp: String,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (DeviceItem) -> Unit,
) {
    val rows = remember(devices) {
        devices.filter { it.ip.isNotBlank() && isDeviceUsableForPublicEndpoint(it) }
            .sortedWith(compareByDescending<DeviceItem> { it.online }.thenBy { it.name.ifBlank { it.hostName }.lowercase(Locale.ROOT) })
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        LabGradientDialogSurface(
            modifier = Modifier.fillMaxWidth(.94f).heightIn(min = 250.dp, max = 620.dp),
            shape = LabPopupShapes.Card,
            color = Color.White,
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择目标设备", fontSize = LabTypography.PageTitle.fontSize, fontWeight = FontWeight.Bold, color = RouterInk)
                        Text("仅展示适合原生端口映射的设备", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                    }
                    TextButton(onClick = onRefresh, enabled = !loading) {
                        Text(if (loading) "读取中" else "刷新", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.Bold, color = RouterBlue)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, "关闭", Modifier.size(19.dp)) }
                }
                if (rows.isEmpty()) {
                    CompactEmpty("暂无可关联设备", "请先在设备页为终端设置名称，或直接手动填写 IP", RouterGlyph.Port) { onRefresh() }
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(rows, key = { it.mac.ifBlank { it.ip } }) { device ->
                            val title = device.remark.ifBlank { device.name }.ifBlank { device.hostName }.ifBlank { "未命名设备" }
                            val profile = inferDeviceProfile(device)
                            val isSelected = device.ip == selectedIp
                            Surface(
                                onClick = { onPick(device) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(15.dp),
                                color = if (isSelected) RouterBlue.copy(alpha = .10f) else LabCoreSurface.Inner,
                                border = BorderStroke(1.dp, if (isSelected) RouterBlue.copy(alpha = .35f) else LabCoreSurface.Border),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = 36)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(title, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.Bold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val subtitle = buildString {
                                            append(if (device.online) "在线" else "离线")
                                            append(" · ")
                                            append(device.ip)
                                            if (device.mac.isNotBlank()) {
                                                append(" · ")
                                                append(device.mac)
                                            }
                                        }
                                        Text(subtitle, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = if (device.online) RouterGreen else RouterMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (isSelected) Icon(Icons.Rounded.Check, "已选择", Modifier.size(18.dp), tint = RouterBlue)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("取消", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.Bold, color = RouterBlue) }
            }
        }
    }
}

@Composable
private fun UpnpPage(prefs: AppPrefs, upnpCapabilitySupported: Boolean) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.upnp.collectAsState()
    val wanResource by repository.wans.collectAsState()
    val state = resource.value ?: UpnpState()
    val scope = repository.commandScope
    val mappingCache = remember(prefs.hub, prefs.token) { PortMappingMemoryCache.forScope(prefs.hub, prefs.token) }
    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    var devices by remember(mappingCache) { mutableStateOf(mappingCache.devices) }
    var actionError by remember { mutableStateOf("") }
    var confirmDisable by remember { mutableStateOf(false) }
    val favorites = prefs.favoriteShortcuts()
    val favoriteHints = remember(favorites) { favoriteServiceHints(favorites) }
    val listState = rememberLazyListState()
    val error = actionError.ifBlank { resource.error }
    val mappingStatus = upnpMappingStatus(resource.value, resource.stale, resource.error)
    val mappingStatusColor = when (mappingStatus) {
        "已映射" -> RouterGreen
        "已停用" -> RouterMuted
        else -> RouterAmber
    }
    val upnpSupported = upnpCapabilitySupported &&
        (wanResource.value?.supportsLineCapability("upnp") ?: true)
    val wanOptions = remember(wanResource.value, state.wan, upnpSupported) {
        if (upnpSupported) upnpWanOptions(wanResource.value, state.wan) else emptyList()
    }

    LaunchedEffect(repository) { repository.refreshWans() }

    LaunchedEffect(mappingCache) {
        if (devices.isEmpty()) {
            runCatching { loadCanonicalPortMappingDevices(deviceApi, mappingCache) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                    devices = it
                    mappingCache.devices = it
                }
        }
    }

    Box(Modifier.fillMaxSize().labV2PageBackground()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                MappingOverviewSurface(height = 110.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(RouterCyan.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(R.drawable.upnp_router),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("UPnP 服务", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = RouterInk, maxLines = 1)
                                Text("${state.mappings.size} 条动态映射", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Switch(
                                checked = state.enabled,
                                enabled = !resource.mutating && upnpSupported,
                                onCheckedChange = { next ->
                                    if (!next) confirmDisable = true else scope.launch {
                                        repository.setUpnp(true, state.wan)
                                            .onSuccess { actionError = "" }
                                            .onFailure { actionError = it.message.orEmpty() }
                                    }
                                },
                                modifier = Modifier.scale(.88f),
                                colors = SwitchDefaults.colors(checkedTrackColor = RouterCyan),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("默认线路", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1)
                            if (upnpShowsWanSelector(wanResource.value) && wanOptions.size > 1) {
                                UpnpWanSelector(state.wan, wanOptions) { wan ->
                                    scope.launch {
                                        repository.setUpnp(state.enabled, wan)
                                            .onSuccess { actionError = "" }
                                            .onFailure { actionError = it.message.orEmpty() }
                                    }
                                }
                            }
                        }
                        MappingStatusPill(mappingStatus, mappingStatusColor)
                    }
                }
            }
            if (!upnpSupported) item { CompactMessage("当前固件或 Hub 未提供可用的 UPnP 线路", RouterAmber) }
            if (error.isNotBlank()) item { CompactMessage(error, RouterAmber) }
            if (resource.mutating) item { CompactMessage("设置正在后台应用，页面可以安全退出", RouterBlue) }
            if (resource.value == null) item { CompactMessage("UPnP 快照正在后台预加载", RouterBlue) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("动态映射", fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = RouterInk)
                    IconButton(
                        onClick = { scope.launch { repository.refreshUpnp(true) } },
                        enabled = !resource.refreshing,
                        modifier = Modifier.size(30.dp),
                    ) { LabRefreshIcon(resource.refreshing, Modifier.size(16.dp), RouterBlue) }
                }
            }
            if (resource.value != null && state.mappings.isEmpty()) item { CompactEmpty("暂无动态映射", "内网设备申请 UPnP 端口后会显示在这里", RouterGlyph.Upnp, null) }
            items(state.mappings, key = { "${it.clientIp}|${it.protocol}|${it.internalPort}|${it.externalPort}|${it.name}" }) { row ->
                UpnpRuleCard(row, devices, favoriteHints, mappingStatus, mappingStatusColor)
            }
        }
    }
    if (confirmDisable) ConfirmDialog("关闭 UPnP？", "部分游戏、下载和远程访问可能受到影响。", "关闭", {
        confirmDisable = false
        scope.launch {
            repository.setUpnp(false, state.wan)
                .onSuccess { actionError = "" }
                .onFailure { actionError = it.message.orEmpty() }
        }
    }) { confirmDisable = false }
}

@Composable
private fun UpnpWanSelector(value: String, options: List<String>, onPick: (String) -> Unit) {
    Row(
        Modifier.width((options.size * 52 + 8).dp).height(32.dp).clip(RoundedCornerShape(99.dp))
            .background(LabCoreSurface.Inner).border(1.dp, LabCoreSurface.Border, RoundedCornerShape(99.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val selected = option == value
            Surface(
                onClick = { if (!selected) onPick(option) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(99.dp),
                color = if (selected) RouterCyan.copy(alpha = .12f) else Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(option, fontSize = 11.sp, lineHeight = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (selected) RouterBlue else RouterMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun UpnpRuleCard(
    row: UpnpMapping,
    devices: List<DeviceItem>,
    favoriteHints: List<FavoriteServiceHint>,
    status: String,
    statusColor: Color,
) {
    val protocol = row.protocol.uppercase(Locale.ROOT).ifBlank { "TCP" }
    val device = devices.firstOrNull { it.ip.trim() == row.clientIp.trim() }
    val recognition = remember(row, devices, favoriteHints) {
        recognizeMappingService(
            name = row.name,
            protocol = protocol,
            internalPort = row.internalPort,
            clientIp = row.clientIp,
            devices = devices,
            favoriteHints = favoriteHints,
        )
    }
    val title = device?.remark?.ifBlank { device.name }?.ifBlank { device.hostName }
        ?.takeIf(String::isNotBlank)
        ?: row.name.ifBlank { row.clientIp.ifBlank { "动态映射" } }
    val showServiceTag = recognition.serviceType.isNotBlank() &&
        !recognition.serviceType.equals(protocol, true) &&
        !recognition.serviceType.equals("Custom", true)
    val destination = buildString {
        append(row.clientIp.ifBlank { "--" })
        if (row.internalPort.isNotBlank()) append(':').append(row.internalPort)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CardShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = .85f)),
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FavoriteIcon("builtin", recognition.iconId, 40)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = RouterInk,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                    )
                    MappingTag(protocol)
                    if (showServiceTag) MappingTag(recognition.serviceType, accent = true)
                    if (recognition.mechanism.isNotBlank()) MappingTag(recognition.mechanism)
                    MappingStatusPill(status, statusColor)
                }
                Text(
                    "WAN:${row.externalPort.ifBlank { "--" }} → $destination",
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RouterMuted,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
fun RouterFirewallScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val automationRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { FirewallAutomationRepository(prefs) }
    val resource by repository.firewall.collectAsState()
    val wanResource by repository.wans.collectAsState()
    val automationResource by automationRepository.state.collectAsState()
    val state = resource.value ?: FirewallState()
    val scope = rememberCoroutineScope()
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 3 })
    val directions = listOf("forward" to "转发", "inbound" to "入站", "outbound" to "出站")
    val currentDirection = directions[pagerState.currentPage].first
    var actionError by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<FirewallRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FirewallRule?>(null) }
    var followRule by remember { mutableStateOf<FirewallRule?>(null) }
    var followTargets by remember { mutableStateOf(FirewallAutomationTargets()) }
    var followTargetsLoading by remember { mutableStateOf(false) }
    val bindings = automationResource.bindings.associateBy { it.firewallUuid }
    val error = actionError.ifBlank { resource.error }.ifBlank { automationResource.error }

    LaunchedEffect(automationRepository, resource.updatedAt) { automationRepository.refresh() }
    LaunchedEffect(repository) { repository.refreshWans() }
    LaunchedEffect(followRule?.uuid) {
        if (followRule != null) {
            followTargetsLoading = true
            followTargets = runCatching { automationRepository.loadTargets() }.getOrDefault(FirewallAutomationTargets())
            followTargetsLoading = false
        }
    }

    followRule?.let { selected ->
        FirewallAutomationPage(
            rule = selected,
            binding = bindings[selected.uuid],
            targets = followTargets,
            targetsLoading = followTargetsLoading,
            busy = automationResource.mutating,
            externalError = automationResource.error,
            onBack = { followRule = null; actionError = "" },
            onRefreshTargets = {
                scope.launch {
                    followTargetsLoading = true
                    followTargets = runCatching { automationRepository.loadTargets() }.getOrDefault(FirewallAutomationTargets())
                    followTargetsLoading = false
                }
            },
            onSave = { binding ->
                scope.launch {
                    automationRepository.save(binding)
                        .onSuccess { actionError = ""; repository.refreshFirewall(true) }
                        .onFailure { actionError = it.message.orEmpty() }
                }
            },
            onStop = {
                scope.launch {
                    automationRepository.remove(selected.uuid)
                        .onSuccess { followRule = null; actionError = "" }
                        .onFailure { actionError = it.message.orEmpty() }
                }
            },
            onSync = {
                scope.launch {
                    automationRepository.sync(selected.uuid)
                        .onSuccess { actionError = ""; repository.refreshFirewall(true) }
                        .onFailure { actionError = it.message.orEmpty() }
                }
            },
        )
        return
    }

    if (adding || editing != null) {
        val defaultWan = wanResource.value?.configured()?.firstOrNull()
        val defaultInterface = defaultWan?.logicalInterface?.ifBlank { defaultWan.wanId }?.ifBlank { "wan" } ?: "wan"
        FirewallEditorPage(
            initial = editing ?: FirewallRule(direction = currentDirection, inIface = if (currentDirection == "outbound") "" else defaultInterface, outIface = if (currentDirection == "inbound") "" else "lan"),
            managedByMapping = editing?.let { bindings.containsKey(it.uuid) } == true,
            wanInventory = wanResource.value,
            onBack = { adding = false; editing = null },
            onSave = { rule -> scope.launch {
                val result = if (editing == null) repository.addFirewallRule(rule) else repository.updateFirewallRule(rule)
                result.onSuccess { adding = false; editing = null; actionError = "" }
                    .onFailure { actionError = it.message.orEmpty() }
            } }
        )
        return
    }

    Scaffold(containerColor = RouterPage, topBar = { CompactTopBar("防火墙", onBack, "${state.rules.count { it.enabled }} 条启用 · ${state.rules.size}/${state.maxRules}") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                directions.forEachIndexed { index, (_, label) ->
                    CompactSegment(label, pagerState.currentPage == index, Modifier.weight(1f)) {
                        scope.launch { pagerState.animateScrollToPage(index) }
                    }
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val dir = directions[page].first
                val directionRules = state.rules.filter { it.direction.equals(dir, ignoreCase = true) }
                val ruleById = directionRules.filter { it.uuid.isNotBlank() }.associateBy { it.uuid }
                val scopes = listOf("${dir}_ipv4" to "IPv4", "${dir}_ipv6" to "IPv6")
                val serverOrders = scopes.associate { (scopeName, _) -> scopeName to firewallScopeOrder(state, scopeName) }
                var localOrders by remember(dir) { mutableStateOf(serverOrders) }
                var draggingKey by remember(dir) { mutableStateOf<String?>(null) }
                var dragOffsetY by remember(dir) { mutableFloatStateOf(0f) }
                var pendingScope by remember(dir) { mutableStateOf<String?>(null) }
                val listState = rememberLazyListState()
                val density = LocalDensity.current
                val edgeThresholdPx = with(density) { 48.dp.toPx() }
                val edgeStepPx = with(density) { 14.dp.toPx() }
                val lastAutoScrollAt = remember(dir) { longArrayOf(0L) }
                val reorderBusy = resource.mutating || automationResource.mutating || pendingScope != null

                LaunchedEffect(serverOrders, draggingKey, pendingScope) {
                    if (draggingKey == null && pendingScope == null) localOrders = serverOrders
                }

                fun cancelDrag() {
                    draggingKey = null
                    dragOffsetY = 0f
                    if (pendingScope == null) localOrders = serverOrders
                }

                fun finishDrag() {
                    val active = draggingKey ?: return
                    val separator = active.indexOf('|')
                    if (separator <= 0 || separator >= active.lastIndex) {
                        cancelDrag()
                        return
                    }
                    val scopeName = active.substring(0, separator)
                    val after = localOrders[scopeName].orEmpty()
                    val before = firewallScopeOrder(state, scopeName)
                    draggingKey = null
                    dragOffsetY = 0f
                    if (after == before) return
                    pendingScope = scopeName
                    scope.launch {
                        repository.reorderFirewall(scopeName, after)
                            .onSuccess { latest ->
                                val actual = firewallScopeOrder(latest, scopeName)
                                localOrders = localOrders + (scopeName to actual)
                                pendingScope = null
                                actionError = if (actual == after) "" else "路由器返回成功，但${if (scopeName.endsWith("ipv4")) "IPv4" else "IPv6"}优先级未改变，请重试"
                            }
                            .onFailure {
                                localOrders = localOrders + (scopeName to before)
                                pendingScope = null
                                actionError = it.message.orEmpty().ifBlank { "防火墙排序未生效，请重试" }
                            }
                    }
                }

                fun dragRule(scopeName: String, uuid: String, deltaY: Float) {
                    val rowKey = "$scopeName|$uuid"
                    if (draggingKey != rowKey || reorderBusy) return
                    val order = localOrders[scopeName].orEmpty()
                    dragOffsetY += deltaY
                    val layout = listState.layoutInfo
                    val draggedInfo = layout.visibleItemsInfo.firstOrNull { it.key == rowKey } ?: return
                    val projectedCenter = draggedInfo.offset + draggedInfo.size / 2f + dragOffsetY
                    val prefix = "$scopeName|"
                    val target = layout.visibleItemsInfo.firstOrNull { info ->
                        val key = info.key as? String ?: return@firstOrNull false
                        key != rowKey && key.startsWith(prefix) && projectedCenter >= info.offset && projectedCenter <= info.offset + info.size
                    }
                    if (target != null) {
                        val targetUuid = (target.key as String).removePrefix(prefix)
                        val from = order.indexOf(uuid)
                        val to = order.indexOf(targetUuid)
                        if (from >= 0 && to >= 0 && from != to) {
                            val next = order.toMutableList()
                            next[from] = targetUuid
                            next[to] = uuid
                            localOrders = localOrders + (scopeName to next)
                            dragOffsetY += (draggedInfo.offset - target.offset).toFloat()
                        }
                    }
                    val scrollDelta = when {
                        projectedCenter < layout.viewportStartOffset + edgeThresholdPx -> -edgeStepPx
                        projectedCenter > layout.viewportEndOffset - edgeThresholdPx -> edgeStepPx
                        else -> 0f
                    }
                    if (scrollDelta != 0f) {
                        val now = System.nanoTime()
                        if (now - lastAutoScrollAt[0] >= 50_000_000L) {
                            lastAutoScrollAt[0] = now
                            scope.launch {
                                val consumed = listState.scrollBy(scrollDelta)
                                if (draggingKey == rowKey && consumed != 0f) dragOffsetY += consumed
                            }
                        }
                    }
                }

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "toolbar-$dir") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${directionRules.size} 条规则", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                            Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = { scope.launch { repository.refreshFirewall(false); automationRepository.refresh() } }, modifier = Modifier.size(34.dp)) { LabRefreshIcon(resource.refreshing, Modifier.size(18.dp), RouterBlue) }
                                Surface(onClick = { adding = true }, shape = CircleShape, color = RouterBlue, modifier = Modifier.size(35.dp), shadowElevation = 2.dp) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "新增", tint = Color.White, modifier = Modifier.size(19.dp)) } }
                            }
                        }
                    }
                    if (error.isNotBlank()) item(key = "error-$dir") { CompactMessage(error, RouterAmber) }
                    if (automationResource.mutating) item(key = "automation-$dir") { CompactMessage("自动跟随正在通过路由器 Web 防火墙安全核对；排序按钮会暂时变灰", RouterBlue) }
                    if (resource.value == null) item(key = "loading-$dir") { CompactMessage("防火墙规则正在后台预加载", RouterBlue) }
                    scopes.forEach { (scopeName, versionLabel) ->
                        val ids = localOrders[scopeName].orEmpty().filter { it in ruleById }
                        item(key = "section-$scopeName") {
                            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(13.dp), RouterField, border = BorderStroke(1.dp, RouterBorder)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TinyBadge(versionLabel, if (versionLabel == "IPv4") RouterBlue else RouterGreen)
                                    Spacer(Modifier.width(7.dp))
                                    Text("${ids.size} 条 · 双栈规则同时参与本区域优先级", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                                    Spacer(Modifier.weight(1f))
                                    if (pendingScope == scopeName) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = RouterBlue)
                                }
                            }
                        }
                        if (ids.isEmpty()) {
                            item(key = "empty-$scopeName") { CompactMessage("$versionLabel 暂无规则", RouterMuted) }
                        } else {
                            items(ids, key = { uuid -> "$scopeName|$uuid" }) { uuid ->
                                ruleById[uuid]?.let { rule ->
                                    val rowKey = "$scopeName|$uuid"
                                    val showDrag = uuid.isNotBlank() && ids.size > 1
                                    val dragEnabled = showDrag && !reorderBusy
                                    FirewallRuleCard(
                                        rule = rule,
                                        binding = bindings[rule.uuid],
                                        draggable = showDrag,
                                        dragEnabled = dragEnabled,
                                        dragging = draggingKey == rowKey,
                                        dragOffsetY = if (draggingKey == rowKey) dragOffsetY else 0f,
                                        onDragStart = { if (dragEnabled) { draggingKey = rowKey; dragOffsetY = 0f } },
                                        onDrag = { delta -> dragRule(scopeName, uuid, delta) },
                                        onDragEnd = ::finishDrag,
                                        onDragCancel = ::cancelDrag,
                                        onOpen = { editing = rule },
                                        onFollow = { followRule = rule },
                                        onToggle = { scope.launch { repository.setFirewallEnabled(rule.uuid, !rule.enabled).onSuccess { actionError = "" }.onFailure { actionError = it.message.orEmpty() } } },
                                        onDelete = { deleteTarget = rule }
                                    )
                                }
                            }
                        }
                        item(key = "space-$scopeName") { Spacer(Modifier.height(4.dp)) }
                    }
                    item(key = "bottom-$dir") { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    deleteTarget?.let { rule ->
        ConfirmDialog("删除防火墙规则？", "删除“${rule.ruleName}”可能立即影响远程访问。", "删除", {
            scope.launch {
                repository.deleteFirewallRule(rule.uuid)
                    .onSuccess {
                        if (bindings.containsKey(rule.uuid)) automationRepository.remove(rule.uuid)
                        deleteTarget = null
                        actionError = ""
                    }
                    .onFailure { actionError = it.message.orEmpty() }
            }
        }) { deleteTarget = null }
    }
}

@Composable
private fun FirewallRuleCard(
    rule: FirewallRule,
    binding: FirewallAutomationBinding?,
    draggable: Boolean,
    dragEnabled: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onOpen: () -> Unit,
    onFollow: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = if (rule.target == "ACCEPT") RouterGreen else RouterRed
    val scale by animateFloatAsState(if (dragging) 1.015f else 1f, animationSpec = spring(), label = "firewallDragScale")
    val elevation by animateDpAsState(if (dragging) 9.dp else 2.dp, animationSpec = spring(), label = "firewallDragElevation")
    val shape = LabCoreSurface.CompactShape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragOffsetY else 0f
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .clickable(enabled = !dragging, onClick = onOpen),
        shape = shape,
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, if (dragging) accent.copy(alpha = .28f) else LabCoreSurface.Border),
        shadowElevation = elevation,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (rule.enabled) accent else RouterMuted.copy(alpha=.45f), CircleShape))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.ruleName, Modifier.weight(1f), fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(6.dp))
                        TinyBadge(if (rule.target == "ACCEPT") "允许" else "丢弃", accent)
                    }
                    val port = if (rule.proto in setOf("tcp", "udp")) rule.destPort.ifBlank { "任意端口" } else "不匹配端口"
                    Text("${firewallIpVersionLabel(rule.ipVersion)} · ${firewallProtocolLabel(rule.proto)} · $port", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val targetText = rule.destIP.ifBlank { rule.ipv6SuffixDest.ifBlank { "任意目标" } }
                    Text("${firewallInterfaceLabel(rule.inIface)} → ${firewallInterfaceLabel(rule.outIface)} · $targetText", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("命中 ${rule.stats.packets} 次 · ${formatBytesCompact(rule.stats.bytes)}", fontSize = LabTypography.Caption.fontSize, color = RouterMuted)
                    val automationEligible = rule.target.equals("ACCEPT", true) && rule.direction == "forward" && rule.ipVersion in setOf("ipv4", "ipv6") && rule.inIface.equals("wan", true) && rule.outIface.equals("lan", true)
                    if (binding != null || automationEligible) {
                        val followColor = binding?.let { firewallAutomationStatusColor(it.status) } ?: RouterBlue
                        Text(
                            if (binding == null) "关联路由器映射" else "映射联动 · ${firewallAutomationStatusLabel(binding.status)} · ${binding.targetName.ifBlank { "等待映射" }}",
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onFollow).padding(vertical = 1.dp),
                            fontSize = LabTypography.Caption.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            color = followColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    modifier = Modifier.width(42.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (draggable) {
                        val dragInput = if (dragEnabled) Modifier.pointerInput(rule.uuid, dragEnabled) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                                onDrag = { change, dragAmount -> change.consume(); onDrag(dragAmount.y) },
                            )
                        } else Modifier
                        Box(
                            Modifier.size(30.dp).clip(CircleShape)
                                .background(if (dragging) accent.copy(alpha = .10f) else Color.Transparent)
                                .then(dragInput),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.DragHandle,
                                if (dragEnabled) "拖动排序" else "排序暂不可用",
                                Modifier.size(19.dp),
                                tint = when { dragging -> accent; dragEnabled -> RouterMuted.copy(alpha = .82f); else -> RouterMuted.copy(alpha = .34f) },
                            )
                        }
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.scale(.74f), colors = SwitchDefaults.colors(checkedTrackColor = accent))
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) { Icon(LabActionIcons.Delete, "删除", Modifier.size(15.dp), tint = RouterMuted) }
                }
            }
        }
    }
}

private fun firewallReorderScope(rule: FirewallRule): String? {
    val direction = rule.direction.lowercase(Locale.ROOT)
    if (direction !in setOf("forward", "inbound", "outbound")) return null
    val version = when (rule.ipVersion.lowercase(Locale.ROOT)) {
        "ipv4" -> "ipv4"
        "ipv6" -> "ipv6"
        else -> return null
    }
    return "${direction}_${version}"
}

private fun firewallRuleBelongsToScope(rule: FirewallRule, scope: String): Boolean {
    val separator = scope.lastIndexOf('_')
    if (separator <= 0 || separator >= scope.lastIndex) return false
    val direction = scope.substring(0, separator)
    val version = scope.substring(separator + 1)
    if (!rule.direction.equals(direction, ignoreCase = true)) return false
    return when (rule.ipVersion.lowercase(Locale.ROOT)) {
        "dual" -> version == "ipv4" || version == "ipv6"
        else -> rule.ipVersion.equals(version, ignoreCase = true)
    }
}

private fun firewallScopeOrder(state: FirewallState, scope: String): List<String> {
    val members = state.rules.filter { it.uuid.isNotBlank() && firewallRuleBelongsToScope(it, scope) }.map { it.uuid }
    if (members.isEmpty()) return emptyList()
    val memberSet = members.toSet()
    val persisted = state.order.optJSONArray(scope)?.let { array ->
        (0 until array.length()).map { array.optString(it) }
            .filter { it.isNotBlank() && it in memberSet }
            .distinct()
    }.orEmpty()
    return persisted + members.filterNot { it in persisted }
}

private fun firewallDisplayRules(state: FirewallState, direction: String): List<FirewallRule> {
    val base = state.rules.filter { it.direction.equals(direction, ignoreCase = true) }.toMutableList()
    if (base.size < 2) return base
    val byId = base.associateBy { it.uuid }
    listOf("${direction}_ipv4", "${direction}_ipv6").forEach { scope ->
        val positions = base.indices.filter { firewallReorderScope(base[it]) == scope }
        if (positions.size < 2) return@forEach
        val orderedIds = firewallScopeOrder(state, scope)
            .filter { uuid -> byId[uuid]?.let(::firewallReorderScope) == scope }
        positions.zip(orderedIds).forEach { (position, uuid) ->
            byId[uuid]?.let { base[position] = it }
        }
    }
    return base
}

private fun firewallDirectionLabel(value: String): String = when (value.lowercase()) { "forward" -> "转发"; "inbound" -> "入站"; "outbound" -> "出站"; else -> value }
private fun firewallIpVersionLabel(value: String): String = when (value.lowercase()) { "ipv4" -> "IPv4"; "ipv6" -> "IPv6"; "dual" -> "双栈"; else -> value }
private fun firewallProtocolLabel(value: String): String = when (value.lowercase()) { "tcp" -> "TCP"; "udp" -> "UDP"; "icmp" -> "ICMP"; "any" -> "全部协议"; else -> value }
private fun firewallActionLabel(value: String): String = when (value.uppercase()) { "ACCEPT" -> "允许"; "DROP" -> "丢弃"; else -> value }
private fun firewallInterfaceLabel(value: String): String = when (value.lowercase()) { "wan" -> "外网（WAN）"; "wan1" -> "外网（WAN1）"; "lan" -> "内网（LAN）"; "" -> "路由器本机"; else -> if (value.startsWith("wan", true)) value.uppercase(Locale.ROOT) else value }

@Composable
private fun FirewallEditorPage(initial: FirewallRule, managedByMapping: Boolean, wanInventory: WanInventory?, onBack: () -> Unit, onSave: (FirewallRule) -> Unit) {
    var rule by remember(initial.uuid) { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    val addressEnabled = rule.ipVersion != "dual"
    val portEnabled = rule.proto in setOf("tcp", "udp")
    val wanOptions = remember(wanInventory, rule.inIface, rule.outIface) {
        firewallWanOptions(wanInventory, rule.inIface.ifBlank { rule.outIface })
    }
    val defaultWanInterface = wanOptions.firstOrNull { it.isNotBlank() && !it.equals("lan", true) } ?: "wan"
    RouterFormPage(if (initial.uuid.isBlank()) "新增防火墙规则" else "编辑防火墙规则", "精确匹配 · 保存后同步路由器", onBack, topBarColor = RouterPage) {
        if (managedByMapping) CompactMessage("这条规则已关联路由器映射。保存人工修改后，自动联动会暂停且不会覆盖新设置。", RouterAmber)
        CompactField("规则名称", rule.ruleName, "例如 WireGuard") { rule = rule.copy(ruleName = it.take(24)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactChoice("方向", rule.direction, listOf("forward", "inbound", "outbound"), Modifier.weight(1f), ::firewallDirectionLabel) { value -> rule = rule.copy(direction = value, inIface = if (value == "outbound") "" else rule.inIface.ifBlank { defaultWanInterface }, outIface = if (value == "inbound") "" else rule.outIface.ifBlank { "lan" }) }
            CompactChoice("IP版本", rule.ipVersion, listOf("ipv4", "ipv6", "dual"), Modifier.weight(1f), ::firewallIpVersionLabel) { rule = rule.copy(ipVersion = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactChoice("协议", rule.proto, listOf("tcp", "udp", "icmp", "any"), Modifier.weight(1f), ::firewallProtocolLabel) { rule = rule.copy(proto = it, srcPort = if (it in setOf("tcp","udp")) rule.srcPort else "", destPort = if (it in setOf("tcp","udp")) rule.destPort else "") }
            CompactChoice("动作", rule.target, listOf("ACCEPT", "DROP"), Modifier.weight(1f), ::firewallActionLabel) { rule = rule.copy(target = it) }
        }
        AnimatedVisibility(addressEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactField("源IP", rule.srcIP, "留空=任意", Modifier.weight(1f), KeyboardType.Ascii) { rule = rule.copy(srcIP = it.take(80)) }
                    CompactField("目的IP", rule.destIP, "留空=任意", Modifier.weight(1f), KeyboardType.Ascii) { rule = rule.copy(destIP = it.take(80)) }
                }
                if (rule.ipVersion == "ipv6") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactField("源IPv6后缀", rule.ipv6SuffixSrc, "::abcd", Modifier.weight(1f), KeyboardType.Ascii) { rule = rule.copy(ipv6SuffixSrc = it.take(80)) }
                    CompactField("目的IPv6后缀", rule.ipv6SuffixDest, "::abcd", Modifier.weight(1f), KeyboardType.Ascii) { rule = rule.copy(ipv6SuffixDest = it.take(80)) }
                }
            }
        }
        AnimatedVisibility(portEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactField("源端口", rule.srcPort, "80,443 或 1000:2000", Modifier.weight(1f), KeyboardType.Ascii) { rule = rule.copy(srcPort = it.take(96)) }
                CompactField("目的端口", rule.destPort, "80,443", Modifier.weight(1f), KeyboardType.Ascii) { rule = rule.copy(destPort = it.take(96)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rule.direction != "outbound") CompactChoice("入接口", rule.inIface.ifBlank { defaultWanInterface }, (listOf("lan") + wanOptions).distinct(), Modifier.weight(1f), ::firewallInterfaceLabel) { rule = rule.copy(inIface = it) }
            if (rule.direction != "inbound") CompactChoice("出接口", rule.outIface.ifBlank { "lan" }, (wanOptions + listOf("lan")).distinct(), Modifier.weight(1f), ::firewallInterfaceLabel) { rule = rule.copy(outIface = it) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("保存后立即启用", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
            Spacer(Modifier.weight(1f))
            Switch(checked = rule.enabled, onCheckedChange = { rule = rule.copy(enabled = it) }, modifier = Modifier.scale(.85f), colors = SwitchDefaults.colors(checkedTrackColor = RouterBlue))
        }
        if (error.isNotBlank()) Text(error, fontSize = LabTypography.Supporting.fontSize, color = RouterRed, fontWeight = FontWeight.SemiBold)
        Button(onClick = { error = if (rule.ruleName.isBlank()) "请填写规则名称" else ""; if (error.isBlank()) onSave(rule) }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = RouterBlue)) { Text("保存并同步路由器", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun RouterDdnsScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val capabilityResource by repository.capabilities.collectAsState()
    val capabilityValue = capabilityResource.value
    // 固件原生 DDNS 是按设备探测出的能力，而非型号猜测；自建 DDNS 与证书监控始终显示。
    // 能力尚未返回时先按「有原生 DDNS」渲染：先少一个 tab 再补回来，会让正在看的
    // 证书页被顶成原生 DDNS 页。只有明确探测到不支持才收起。
    val hasNativeDdns = capabilityValue?.ddns ?: true
    val pageCount = if (hasNativeDdns) 3 else 2
    val certificatePage = if (hasNativeDdns) 2 else 1
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    // 能力收起导致页数变少时，把越界的页码退回证书页，避免空白页。
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage > pageCount - 1) pagerState.scrollToPage(pageCount - 1)
    }
    Scaffold(containerColor = RouterPage, topBar = { CompactTopBar("DDNS", onBack, if (hasNativeDdns) "LabProbe DDNS · 路由器原生 DDNS · 证书监控" else "LabProbe DDNS · 证书监控") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactSegment("LabProbe DDNS", pagerState.currentPage == 0, Modifier.weight(1f)) {
                    scope.launch { pagerState.animateScrollToPage(0) }
                }
                if (hasNativeDdns) {
                    CompactSegment("路由器原生 DDNS", pagerState.currentPage == 1, Modifier.weight(1f)) {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    }
                }
                CompactSegment("证书监控", pagerState.currentPage == certificatePage, Modifier.weight(1f)) {
                    scope.launch { pagerState.animateScrollToPage(certificatePage) }
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        page == 0 -> LabProbeDdnsSection(prefs)
                        hasNativeDdns && page == 1 -> DdnsRecordsSection(prefs, nativeDdns = hasNativeDdns)
                        page == certificatePage -> CertificateExpirySection(prefs)
                        else -> CertificateExpirySection(prefs)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}


private val labProbeProviderIds = listOf("alidns", "dnspod", "cloudflare", "dynv6", "duckdns", "desec", "dynu", "ipv64")

private fun labProbeProviderLabel(id: String): String = when (id.lowercase(Locale.ROOT)) {
    "alidns" -> "AliDNS"
    "dnspod" -> "DNSPod"
    "cloudflare" -> "Cloudflare"
    "dynv6" -> "dynv6"
    "duckdns" -> "DuckDNS"
    "desec" -> "deSEC"
    "dynu" -> "Dynu"
    "ipv64" -> "IPv64"
    else -> id
}

private fun labProbeStatusLabel(status: String): String = when (status.lowercase(Locale.ROOT)) {
    "disabled" -> "已停用"
    "waiting" -> "等待地址"
    "detected" -> "检测到新地址"
    "updating" -> "正在更新"
    "published" -> "正常"
    "error" -> "更新失败"
    else -> status.ifBlank { "等待地址" }
}

private fun labProbeStatusColor(status: String): Color = when (status.lowercase(Locale.ROOT)) {
    "published" -> RouterGreen
    "error" -> RouterRed
    "updating", "detected" -> RouterBlue
    "disabled" -> RouterMuted
    else -> RouterAmber
}

internal fun labProbeDdnsMenuLabels(enabled: Boolean): List<String> = listOf(
    "编辑",
    if (enabled) "停用" else "启用",
    "删除",
)

internal fun labProbeDdnsWanLabel(wanId: String): String = wanId.trim().uppercase(Locale.ROOT)

private fun labProbeAddressStateLabel(state: String): String = when (state.lowercase(Locale.ROOT)) {
    "public" -> "公网"
    "cgnat" -> "CGNAT"
    "ambiguous" -> "待确认"
    "unavailable" -> "不可用"
    else -> state.ifBlank { "未知" }
}

private fun labProbeSourceLabel(source: String): String {
    val value = source.trim()
    if (value.isBlank()) return "来源未知"
    return when {
        value.startsWith("egress-http:") -> "公网出口 ${value.removePrefix("egress-http:")}"
        value.startsWith("route-src:") -> "路由出口 ${value.removePrefix("route-src:")}"
        value.startsWith("default-route:") -> "默认出口 ${value.removePrefix("default-route:")}"
        value.startsWith("delegated-lan:") -> "委派前缀 ${value.removePrefix("delegated-lan:")}"
        value.startsWith("generic:") -> "通用接口 ${value.removePrefix("generic:")}"
        else -> value
    }
}

private fun labProbeTimeText(epoch: Long): String {
    if (epoch <= 0L) return "未记录"
    val seconds = (System.currentTimeMillis() / 1000L - epoch).coerceAtLeast(0L)
    return when {
        seconds < 10L -> "刚刚"
        seconds < 60L -> "${seconds}秒前"
        seconds < 3600L -> "${seconds / 60L}分钟前"
        seconds < 86_400L -> "${seconds / 3600L}小时前"
        else -> "${seconds / 86_400L}天前"
    }
}

private fun labProbeCredentialFields(provider: String): List<Pair<String, String>> = when (provider.lowercase(Locale.ROOT)) {
    "alidns" -> listOf("zone" to "域名区域（Zone / Domain）", "AccessKeyId" to "访问密钥 ID（AccessKey ID）", "AccessKeySecret" to "访问密钥（AccessKey Secret）")
    "dnspod" -> listOf("zone" to "域名区域（Domain）", "SecretId" to "密钥 ID（Secret ID）", "SecretKey" to "密钥（Secret Key）")
    "cloudflare" -> listOf("zoneId" to "区域 ID（Zone ID）", "apiToken" to "API 令牌（API Token）")
    "dynv6", "duckdns", "desec", "ipv64" -> listOf("token" to "令牌（Token）")
    "dynu" -> listOf("username" to "用户名", "password" to "密码")
    else -> listOf("token" to "令牌（Token）")
}

private fun labProbeCredentialIsSecret(key: String): Boolean = key.lowercase(Locale.ROOT) !in setOf("zone", "zoneid", "username")

private fun labProbeHasPublicAddress(value: String, state: String): Boolean =
    value.isNotBlank() && state.equals("public", ignoreCase = true)

private const val LABPROBE_DDNS_STALE_SECONDS = 180L

private fun labProbeDetectionIsFresh(record: LabProbeDdnsRecord, address: LabProbeDdnsAddress): Boolean {
    val detectedAt = record.lastDetectedAt.takeIf { it > 0L } ?: address.detectedAt
    val ageSeconds = System.currentTimeMillis() / 1000L - detectedAt
    return detectedAt > 0L && ageSeconds in -30L..LABPROBE_DDNS_STALE_SECONDS
}

private fun labProbeDirectValueIsValid(record: LabProbeDdnsRecord, recordType: String): Boolean = when (recordType) {
    "CNAME" -> labProbeCnameTargetIsValid(record.hostname, record.recordValues[recordType].orEmpty())
    "TXT" -> record.recordValues[recordType].orEmpty().isNotBlank()
    else -> false
}

private fun labProbeCanUpdate(record: LabProbeDdnsRecord, address: LabProbeDdnsAddress, busy: Boolean): Boolean {
    if (!record.enabled || busy) return false
    val ipv4 = record.detectedIpv4.ifBlank { address.detectedIpv4 }
    val ipv6 = record.detectedIpv6.ifBlank { address.detectedIpv6 }
    val ipv4State = record.ipv4State.ifBlank { address.ipv4State }
    val ipv6State = record.ipv6State.ifBlank { address.ipv6State }
    return record.recordTypes.any { type ->
        when (type) {
            "A" -> labProbeDetectionIsFresh(record, address) && labProbeHasPublicAddress(ipv4, ipv4State)
            "AAAA" -> labProbeDetectionIsFresh(record, address) && labProbeHasPublicAddress(ipv6, ipv6State)
            "CNAME", "TXT" -> labProbeDirectValueIsValid(record, type)
            else -> false
        }
    }
}

@Composable
private fun LabProbeDdnsSection(prefs: AppPrefs) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.labProbeDdns.collectAsState()
    val wanResource by repository.wans.collectAsState()
    val snapshot = resource.value
    val rows = snapshot?.records.orEmpty()
    val scope = repository.commandScope
    var actionError by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LabProbeDdnsRecord?>(null) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<LabProbeDdnsRecord?>(null) }

    LaunchedEffect(repository) {
        repository.refreshWans()
        repository.refreshLabProbeDdns(false)
    }
    CompactToolbar(
        title = "LabProbe DDNS",
        subtitle = "独立于路由器原生 DDNS",
        loading = resource.refreshing,
        onRefresh = { scope.launch { repository.refreshWans(force = true); repository.refreshLabProbeDdns(true) } },
        onAdd = { adding = true },
    )
    val error = actionError.ifBlank { resource.error }
    if (error.isNotBlank()) CompactMessage(error, RouterRed)
    if (resource.mutating) CompactMessage("DDNS 设置正在后台应用，页面可以安全退出", RouterBlue)
    if (snapshot == null && resource.refreshing) LoadingBlock()
    if (snapshot != null && rows.isEmpty()) {
        CompactEmpty("还没有 DDNS 记录", "添加后可自动跟随公网 IPv4 / IPv6 地址变化", RouterGlyph.Ddns) { adding = true }
    }
    rows.forEach { record ->
        LabProbeDdnsCard(record, onClick = { detailId = record.id }, onEdit = { editing = record }, onToggle = {
            scope.launch {
                repository.updateLabProbeDdns(record.copy(enabled = !record.enabled), emptyMap())
                    .onSuccess { actionError = "" }
                    .onFailure { actionError = it.message.orEmpty() }
            }
        }, onDelete = { deleteTarget = record })
    }

    if (adding || editing != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { adding = false; editing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LabGradientDialogSurface(Modifier.fillMaxWidth(.94f).imePadding().heightIn(max = 720.dp)) {
                LabProbeDdnsEditorPage(
                    initial = editing ?: LabProbeDdnsRecord(provider = "cloudflare"),
                    providers = snapshot?.providers.orEmpty(),
                    wanInventory = wanResource.value,
                    wanSupported = snapshot?.wanSupported == true,
                    externalError = actionError,
                    onBack = { adding = false; editing = null },
                ) { record, credentials ->
                    scope.launch {
                        val result = if (editing == null) {
                            repository.addLabProbeDdns(record, credentials)
                        } else {
                            repository.updateLabProbeDdns(record, credentials)
                        }
                        result.onSuccess {
                            adding = false
                            editing = null
                            actionError = ""
                        }.onFailure { actionError = it.message.orEmpty() }
                    }
                }
            }
        }
    }

    detailId?.let { id ->
        val record = rows.firstOrNull { it.id == id }
        if (record != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { detailId = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(Modifier.fillMaxSize(), color = RouterPage) {
                    LabProbeDdnsDetailPage(
                        record = record,
                        address = snapshot?.address ?: LabProbeDdnsAddress(),
                        busy = resource.mutating,
                        externalError = actionError,
                        onBack = { detailId = null },
                        onEdit = { editing = record; detailId = null },
                        onToggle = {
                            scope.launch {
                                repository.updateLabProbeDdns(record.copy(enabled = !record.enabled), emptyMap())
                                    .onFailure { actionError = it.message.orEmpty() }
                            }
                        },
                        onDelete = { deleteTarget = record; detailId = null },
                        onRefreshAddress = {
                            scope.launch {
                                val result = repository.refreshLabProbeDdnsAddress()
                                if (result.isSuccess) {
                                    actionError = ""
                                    repository.refreshLabProbeDdns(true)
                                } else {
                                    actionError = result.exceptionOrNull()?.message.orEmpty()
                                }
                            }
                        },
                        onUpdateNow = {
                            scope.launch {
                                repository.updateLabProbeDdnsNow(record.id)
                                    .onSuccess { actionError = "" }
                                    .onFailure { actionError = it.message.orEmpty() }
                            }
                        },
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog("删除 LabProbe DDNS", "确认删除 ${target.hostname}？该操作不会修改路由器原生 DDNS。", "删除", {
            scope.launch {
                repository.deleteLabProbeDdns(target.id)
                    .onSuccess { deleteTarget = null; actionError = "" }
                    .onFailure { actionError = it.message.orEmpty() }
            }
        }) { deleteTarget = null }
    }
}

@Composable
private fun LabProbeDdnsCard(
    record: LabProbeDdnsRecord,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember(record.id) { mutableStateOf(false) }
    val menuLabels = labProbeDdnsMenuLabels(record.enabled)
    val accent = labProbeStatusColor(record.status)
    val detectedSummary = buildList {
        if (record.recordTypes.contains("A")) add("A ${record.detectedIpv4.ifBlank { "—" }}")
        if (record.recordTypes.contains("AAAA")) add("AAAA ${record.detectedIpv6.ifBlank { "—" }}")
        if (record.recordTypes.contains("CNAME")) add("CNAME ${record.recordValues["CNAME"].orEmpty().ifBlank { "—" }}")
        if (record.recordTypes.contains("TXT")) add("TXT ${record.recordValues["TXT"].orEmpty().ifBlank { "—" }}")
    }.joinToString(" · ").ifBlank { "—" }
    val publishedSummary = buildList {
        if (record.recordTypes.contains("A")) add("A ${record.publishedIpv4.ifBlank { "—" }}")
        if (record.recordTypes.contains("AAAA")) add("AAAA ${record.publishedIpv6.ifBlank { "—" }}")
        if (record.recordTypes.contains("CNAME")) add("CNAME ${record.publishedValues["CNAME"].orEmpty().ifBlank { "—" }}")
        if (record.recordTypes.contains("TXT")) add("TXT ${record.publishedValues["TXT"].orEmpty().ifBlank { "—" }}")
    }.joinToString(" · ").ifBlank { "—" }
    PremiumCard(accent, Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouterGlyphIcon(RouterGlyph.Ddns, RouterBlue, Modifier.size(30.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(record.hostname.ifBlank { "未命名 DDNS 记录" }, style = LabTypography.ValueStrong.copy(color = RouterInk), maxLines = 2, overflow = TextOverflow.Clip)
                Text("${labProbeProviderLabel(record.provider)} · ${record.recordTypes.joinToString(" / ")}", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    TinyBadge(labProbeStatusLabel(record.status), accent)
                    if (record.wanId.isNotBlank()) TinyBadge("线路 ${labProbeDdnsWanLabel(record.wanId)}", RouterBlue)
                    Text(if (record.enabled) "已启用" else "已停用", fontSize = LabTypography.Caption.fontSize, color = if (record.enabled) RouterGreen else RouterMuted, fontWeight = FontWeight.SemiBold)
                }
            }
            Switch(checked = record.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.scale(.76f), colors = SwitchDefaults.colors(checkedTrackColor = RouterBlue))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.MoreVert, "更多操作", Modifier.size(17.dp), tint = RouterMuted) }
                LabDropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = LabPopupShapes.Menu,
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp, actionMenu = true) {
                    LabDropdownMenuItem(text = { Text(menuLabels[0], color = RouterInk) }, onClick = { menu = false; onEdit() })
                    LabDropdownMenuItem(text = { Text(menuLabels[1], color = RouterInk) }, onClick = { menu = false; onToggle() })
                    LabDropdownMenuItem(text = { Text(menuLabels[2], color = RouterRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 39.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("检测 $detectedSummary", style = LabTypography.Caption, maxLines = 2, overflow = TextOverflow.Clip)
            Text("发布 $publishedSummary", style = LabTypography.Caption, maxLines = 2, overflow = TextOverflow.Clip)
        }
        Text("最后检测 ${labProbeTimeText(record.lastDetectedAt)} · 最后更新 ${labProbeTimeText(record.lastUpdatedAt)}", Modifier.padding(start = 39.dp), fontSize = LabTypography.Caption.fontSize, color = RouterMuted)
    }
}

@Composable
private fun LabProbeDdnsDetailPage(
    record: LabProbeDdnsRecord,
    address: LabProbeDdnsAddress,
    busy: Boolean,
    externalError: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onRefreshAddress: () -> Unit,
    onUpdateNow: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val canUpdate = labProbeCanUpdate(record, address, busy)
    BackHandler(onBack = onBack)
    Scaffold(containerColor = RouterPage, topBar = {
        Surface(color = Color.White) {
            Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.ArrowBack, null, Modifier.size(20.dp), tint = RouterInk) }
                Column(Modifier.weight(1f)) {
                    Text("DDNS 详情", fontSize = LabTypography.CardTitle.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
                    Text(labProbeProviderLabel(record.provider), fontSize = LabTypography.Caption.fontSize, color = RouterMuted, fontWeight = FontWeight.SemiBold)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.MoreVert, "更多操作", Modifier.size(20.dp), tint = RouterMuted) }
                    LabDropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        shape = LabPopupShapes.Menu,
                        containerColor = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp, actionMenu = true) {
                        LabDropdownMenuItem(text = { Text("编辑", color = RouterInk) }, onClick = { menu = false; onEdit() })
                        LabDropdownMenuItem(text = { Text(if (record.enabled) "停用" else "启用", color = RouterInk) }, onClick = { menu = false; onToggle() })
                        LabDropdownMenuItem(text = { Text("删除", color = RouterRed) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PremiumCard(labProbeStatusColor(record.status)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouterGlyphIcon(RouterGlyph.Ddns, RouterBlue, Modifier.size(31.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        LabProbeCopyableValue(record.hostname, "未命名 DDNS 记录", textSize = LabTypography.Value.fontSize)
                        Text(if (record.enabled) "已启用 · ${record.recordTypes.joinToString(" / ")}" else "已停用", fontSize = LabTypography.Caption.fontSize, color = RouterMuted, fontWeight = FontWeight.SemiBold)
                        if (record.wanId.isNotBlank()) {
                            Text("线路 ${labProbeDdnsWanLabel(record.wanId)}", fontSize = LabTypography.Caption.fontSize, color = RouterBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TinyBadge(labProbeStatusLabel(record.status), labProbeStatusColor(record.status))
                }
            }
            if (externalError.isNotBlank()) CompactMessage(externalError, RouterRed)
            Text("DNS 记录", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
            if (record.recordTypes.contains("A")) LabProbeDdnsAddressCard("A / IPv4", record.publishedIpv4, record.detectedIpv4, record.ipv4State, record.ipv4Source.ifBlank { address.ipv4Source })
            if (record.recordTypes.contains("AAAA")) LabProbeDdnsAddressCard("AAAA / IPv6", record.publishedIpv6, record.detectedIpv6, record.ipv6State, record.ipv6Source.ifBlank { address.ipv6Source })
            if (record.recordTypes.contains("CNAME")) LabProbeDdnsValueCard("CNAME", "CNAME 目标", record.recordValues["CNAME"].orEmpty(), record.publishedValues["CNAME"].orEmpty())
            if (record.recordTypes.contains("TXT")) LabProbeDdnsValueCard("TXT", "TXT 内容", record.recordValues["TXT"].orEmpty(), record.publishedValues["TXT"].orEmpty())
            if (record.recordTypes.any { it == "A" || it == "AAAA" }) {
                Text("检测地址", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
                PremiumCard(RouterCyan) {
                    if (record.recordTypes.contains("A")) {
                        LabProbeDetectedRow("IPv4", record.detectedIpv4.ifBlank { address.detectedIpv4 }, record.ipv4State.ifBlank { address.ipv4State }, record.ipv4Source.ifBlank { address.ipv4Source })
                    }
                    if (record.recordTypes.contains("AAAA")) {
                        LabProbeDetectedRow("IPv6", record.detectedIpv6.ifBlank { address.detectedIpv6 }, record.ipv6State.ifBlank { address.ipv6State }, record.ipv6Source.ifBlank { address.ipv6Source })
                    }
                }
            }
            PremiumCard(RouterMuted) {
                Text("最后检测：${labProbeTimeText(record.lastDetectedAt)}", fontSize = LabTypography.Caption.fontSize, color = RouterMuted, fontWeight = FontWeight.SemiBold)
                Text("最后更新：${labProbeTimeText(record.lastUpdatedAt)}", fontSize = LabTypography.Caption.fontSize, color = RouterMuted, fontWeight = FontWeight.SemiBold)
                if (record.recordTypes.any { it == "A" || it == "AAAA" } && !labProbeDetectionIsFresh(record, address)) {
                    Text("检测结果已过期，请先刷新检测地址", fontSize = LabTypography.Caption.fontSize, color = RouterAmber, fontWeight = FontWeight.SemiBold)
                }
                if (record.lastError.isNotBlank()) Text(uiMessageZh(record.lastError), fontSize = LabTypography.Caption.fontSize, color = RouterRed, fontWeight = FontWeight.SemiBold)
            }
            // 忙时按钮保持原色 + 转圈，只是不再响应点击：变灰会让人以为点不动、
            // 或者以为出错了（用户明确要求过）。真正不能用的原因是「未启用/没有可发布的地址」，
            // 那才用灰色表达。
            if (record.recordTypes.any { it == "A" || it == "AAAA" }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshAddress, enabled = !busy, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp)) { Text(if (busy) "处理中" else "刷新检测地址", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold) }
                    UpdateNowButton(canUpdate, busy, Modifier.weight(1f).height(42.dp), onUpdateNow)
                }
            } else {
                UpdateNowButton(canUpdate, busy, Modifier.fillMaxWidth().height(42.dp), onUpdateNow)
            }
        }
    }
}

@Composable
private fun LabProbeDdnsAddressCard(title: String, published: String, detected: String, state: String, source: String) {
    PremiumCard(if (published.isNotBlank()) RouterGreen else RouterAmber) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, modifier = Modifier.weight(1f))
            TinyBadge(if (published.isNotBlank()) "已发布" else if (detected.isNotBlank()) "待发布" else "无地址", if (published.isNotBlank()) RouterGreen else RouterAmber)
        }
        LabProbeKvRow("已发布", published, "未发布", muted = published.isBlank())
        // 只在两个地址不一致时才多占一行；一致的时候再列一遍就是纯留白。
        if (published.isNotBlank() && detected.isNotBlank() && published != detected) {
            LabProbeKvRow("检测到", detected, "未检测到", accent = true)
        }
        Text("${labProbeAddressStateLabel(state)} · ${labProbeSourceLabel(source)}", fontSize = LabTypography.Caption.fontSize, color = RouterMuted, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LabProbeDdnsValueCard(type: String, label: String, value: String, published: String) {
    PremiumCard(if (published.isNotBlank()) RouterGreen else RouterAmber) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记录类型", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted, modifier = Modifier.width(66.dp))
            Text(type, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, modifier = Modifier.weight(1f))
            TinyBadge(if (published.isNotBlank()) "已发布" else "待发布", if (published.isNotBlank()) RouterGreen else RouterAmber)
        }
        LabProbeKvRow(label, value, "未填写")
        LabProbeKvRow("已发布", published, "未发布", muted = published.isBlank())
    }
}

/** 官方 App 那种一行一条：标签靠左、值靠右、复制贴在值后面，不再整片留白。 */
@Composable
private fun LabProbeKvRow(label: String, value: String, placeholder: String, muted: Boolean = false, accent: Boolean = false) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = LabTypography.Caption.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = RouterMuted,
            maxLines = 1,
            modifier = Modifier.width(66.dp),
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                value.ifBlank { placeholder },
                fontSize = LabTypography.Supporting.fontSize,
                fontWeight = if (value.isBlank() || muted) FontWeight.Medium else FontWeight.SemiBold,
                color = when {
                    value.isBlank() || muted -> RouterMuted
                    accent -> RouterBlue
                    else -> RouterInk
                },
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { copy(context, value) }, enabled = value.isNotBlank(), modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.ContentCopy, "复制", Modifier.size(15.dp), tint = if (value.isNotBlank()) RouterBlue else RouterMuted.copy(alpha = .45f))
        }
    }
}

@Composable
private fun UpdateNowButton(canUpdate: Boolean, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = { if (!busy) onClick() },
        // 忙时不算"不可用"：按钮保持原色并转圈，只是不再接受第二次点击。
        enabled = canUpdate || busy,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RouterBlue),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(7.dp))
        }
        Text(if (busy) "正在更新…" else "立即更新", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LabProbeCopyableValue(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    textSize: androidx.compose.ui.unit.TextUnit = LabTypography.Value.fontSize,
    textColor: Color = RouterInk,
    textWeight: FontWeight = FontWeight.SemiBold,
) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                value.ifBlank { placeholder },
                fontSize = textSize,
                fontWeight = textWeight,
                color = textColor,
                lineHeight = (textSize.value * 1.35f).sp,
            )
        }
        IconButton(
            onClick = { copy(context, value) },
            enabled = value.isNotBlank(),
            modifier = Modifier.size(30.dp),
        ) {
            Icon(Icons.Rounded.ContentCopy, "复制", Modifier.size(16.dp), tint = if (value.isNotBlank()) RouterBlue else RouterMuted.copy(alpha = .45f))
        }
    }
}

@Composable
private fun LabProbeDetectedRow(label: String, value: String, state: String, source: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, modifier = Modifier.width(54.dp))
        Column(Modifier.weight(1f)) {
        LabProbeCopyableValue(value, "未检测到", textSize = LabTypography.Supporting.fontSize, textWeight = FontWeight.Medium)
            Text("${labProbeAddressStateLabel(state)} · ${labProbeSourceLabel(source)}", fontSize = LabTypography.Caption.fontSize, color = RouterMuted)
        }
    }
}

@Composable
private fun LabProbeDdnsEditorPage(
    initial: LabProbeDdnsRecord,
    providers: List<LabProbeDdnsProvider>,
    wanInventory: WanInventory?,
    wanSupported: Boolean,
    externalError: String,
    onBack: () -> Unit,
    onSave: (LabProbeDdnsRecord, Map<String, String>) -> Unit,
) {
    val normalized = remember(initial) { initial.copy(provider = initial.provider.ifBlank { "cloudflare" }, recordTypes = normalizeLabProbeRecordTypes(initial.recordTypes)) }
    var record by remember(normalized) { mutableStateOf(normalized) }
    val wanEditorState = labProbeDdnsWanEditorState(record, wanInventory, wanSupported)
    LaunchedEffect(wanEditorState.selectedWanId) {
        if (record.wanId != wanEditorState.selectedWanId) record = record.copy(wanId = wanEditorState.selectedWanId)
    }
    var credentialValues by remember(normalized.id, normalized.provider) { mutableStateOf(emptyMap<String, String>()) }
    var showSecret by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val providerIds = remember(providers) { providers.map { it.id }.filter { it.isNotBlank() }.ifEmpty { labProbeProviderIds } }
    val providerOptions = providerIds.map(::labProbeProviderLabel)
    val providerSpec = providers.firstOrNull { it.id.equals(record.provider, ignoreCase = true) }
    val supportedTypes = providerSpec?.recordTypes?.toSet() ?: setOf("A", "AAAA")
    val validationProvider = providerSpec ?: LabProbeDdnsProvider(id = record.provider, recordTypes = supportedTypes.toList())
    val requiredFields = labProbeCredentialFields(record.provider)
    val hasEnteredCredential = credentialValues.values.any { it.isNotBlank() }
    val existingEdit = normalized.id.isNotBlank() && normalized.credentialsConfigured
    val providerChanged = normalized.id.isNotBlank() && normalized.provider != record.provider
    BackHandler(onBack = onBack)
    RouterFormPage(if (normalized.id.isBlank()) "新增 LabProbe DDNS" else "编辑 LabProbe DDNS", "凭据不会回显；留空保持原凭据", onBack) {
        CompactWaterfallChoice("服务商", labProbeProviderLabel(record.provider), providerOptions) { selected ->
            val id = providerIds.getOrNull(providerOptions.indexOf(selected)) ?: record.provider
            val nextSpec = providers.firstOrNull { it.id.equals(id, ignoreCase = true) }
            val nextSupported = nextSpec?.recordTypes?.toSet() ?: setOf("A", "AAAA")
            val nextTypes = normalizeLabProbeRecordTypes(record.recordTypes.filter { it in nextSupported })
            record = record.copy(provider = id, recordTypes = nextTypes)
            credentialValues = emptyMap()
        }
        CompactField("域名", record.hostname, "例如 home.example.com") { record = record.copy(hostname = it.take(253)) }
        if (wanEditorState.showSelector) {
            CompactChoice("线路", wanEditorState.selectedWanId, wanEditorState.options, display = ::labProbeDdnsWanLabel) {
                record = record.copy(wanId = it)
            }
        }
        Text("记录类型", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CompactSegment("IPv4 / A", record.recordTypes.contains("A"), Modifier.weight(1f), enabled = "A" in supportedTypes) {
                val next = if (record.recordTypes.contains("A")) record.recordTypes - "A" else record.recordTypes.filterNot { it == "CNAME" } + "A"
                if (next.isNotEmpty()) record = record.copy(recordTypes = normalizeLabProbeRecordTypes(next))
            }
            CompactSegment("IPv6 / AAAA", record.recordTypes.contains("AAAA"), Modifier.weight(1f), enabled = "AAAA" in supportedTypes) {
                val next = if (record.recordTypes.contains("AAAA")) record.recordTypes - "AAAA" else record.recordTypes.filterNot { it == "CNAME" } + "AAAA"
                if (next.isNotEmpty()) record = record.copy(recordTypes = normalizeLabProbeRecordTypes(next))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CompactSegment("CNAME", record.recordTypes.contains("CNAME"), Modifier.weight(1f), enabled = "CNAME" in supportedTypes) {
                val next = if (record.recordTypes.contains("CNAME")) listOf("A") else listOf("CNAME")
                record = record.copy(recordTypes = normalizeLabProbeRecordTypes(next))
            }
            CompactSegment("TXT", record.recordTypes.contains("TXT"), Modifier.weight(1f), enabled = "TXT" in supportedTypes) {
                val next = if (record.recordTypes.contains("TXT")) record.recordTypes - "TXT" else record.recordTypes.filterNot { it == "CNAME" } + "TXT"
                if (next.isNotEmpty()) record = record.copy(recordTypes = normalizeLabProbeRecordTypes(next))
            }
        }
        if (record.recordTypes.contains("CNAME")) {
            CompactField("CNAME 目标", record.recordValues["CNAME"].orEmpty(), "例如 target.example.com") { next ->
                record = record.copy(recordValues = record.recordValues + ("CNAME" to next.trimEnd('.').take(253)))
            }
        }
        if (record.recordTypes.contains("TXT")) {
            CompactMultilineField("TXT 内容", record.recordValues["TXT"].orEmpty(), "例如 ACME 验证文本") { next ->
                record = record.copy(recordValues = record.recordValues + ("TXT" to next.take(2048)))
            }
        }
        Text("服务商凭据", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
        if (existingEdit && !hasEnteredCredential && !providerChanged) CompactMessage("已配置凭据；以下字段留空即可保持原凭据", RouterCyan)
        requiredFields.forEach { (key, label) ->
            val value = credentialValues[key].orEmpty()
            val hint = if (existingEdit && !providerChanged) "已配置，留空保持不变" else label
            if (labProbeCredentialIsSecret(key)) {
                CompactPasswordField(label, value, hint, showSecret, { showSecret = !showSecret }) { next -> credentialValues = credentialValues + (key to next.take(512)) }
            } else {
                CompactField(label, value, hint) { next -> credentialValues = credentialValues + (key to next.take(256)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
            Spacer(Modifier.weight(1f))
            Switch(checked = record.enabled, onCheckedChange = { record = record.copy(enabled = it) }, colors = SwitchDefaults.colors(checkedTrackColor = RouterBlue))
        }
        val visibleError = error.ifBlank { externalError }
        if (visibleError.isNotBlank()) CompactMessage(visibleError, RouterRed)
        Button(onClick = {
            val entered = credentialValues.filterValues { it.isNotBlank() }
            error = when {
                record.hostname.isBlank() -> "请填写域名"
                record.recordTypes.isEmpty() -> "至少选择一种记录类型"
                labProbeRecordValidationError(record, validationProvider) != null -> labProbeRecordValidationError(record, validationProvider).orEmpty()
                normalized.id.isBlank() && requiredFields.any { entered[it.first].isNullOrBlank() } -> "请完整填写服务商凭据"
                normalized.id.isNotBlank() && (providerChanged || hasEnteredCredential) && requiredFields.any { entered[it.first].isNullOrBlank() } -> "更新凭据时请完整填写全部字段；全部留空则保持原凭据"
                else -> ""
            }
            if (error.isBlank()) onSave(record, entered)
        }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = RouterBlue)) {
            Text("保存并同步", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DdnsRecordsSection(prefs: AppPrefs, nativeDdns: Boolean = false) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.ddns.collectAsState()
    val wanResource by repository.wans.collectAsState()
    val rows = resource.value.orEmpty()
    val scope = repository.commandScope
    var actionError by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DdnsRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DdnsRecord?>(null) }
    val error = actionError.ifBlank { resource.error }
    LaunchedEffect(repository, nativeDdns) {
        repository.refreshWans()
        if (nativeDdns && rows.isEmpty()) repository.refreshDdns(force = true)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${rows.count { it.enabled }} 条启用 · ${rows.count { it.status.contains("error",true) || it.status.contains("fail",true) }} 条异常", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
        Spacer(Modifier.weight(1f))
        IconButton(onClick={scope.launch{repository.refreshDdns(true)}},modifier=Modifier.size(33.dp)){LabRefreshIcon(resource.refreshing, Modifier.size(17.dp), RouterBlue)}
        Surface(onClick={adding=true},shape=CircleShape,color=RouterBlue,modifier=Modifier.size(34.dp)){Box(contentAlignment=Alignment.Center){Icon(Icons.Rounded.Add,null,tint=Color.White,modifier=Modifier.size(18.dp))}}
    }
    if(error.isNotBlank())CompactMessage(error,RouterAmber)
    if(resource.mutating)CompactMessage("设置正在后台应用，页面可以安全退出",RouterBlue)
    if(resource.value==null)CompactMessage("DDNS 快照正在后台预加载",RouterCyan)
    if(resource.value!=null&&rows.isEmpty())CompactEmpty("暂无DDNS记录","新增后由路由器原生服务更新",RouterGlyph.Ddns){adding=true}
    rows.forEach { record ->
        DdnsCard(record,nativeDdns=nativeDdns,onEdit={editing=record},onToggle={scope.launch{
            repository.updateDdns(record.copy(enabled=!record.enabled),null)
                .onSuccess{actionError=""}.onFailure{actionError=it.message.orEmpty()}
        }},onDelete={deleteTarget=record})
    }
    if (adding || editing != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { adding = false; editing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LabGradientDialogSurface(Modifier.fillMaxWidth(.94f).imePadding().heightIn(max = 720.dp)) {
                // A brand-new record must use the same firmware protocol as the
                // device, otherwise new-firmware devices would only ever get
                // the legacy A/AAAA form (even with an empty record list).
                val deviceProtocol by repository.ddnsProtocol.collectAsState()
                DdnsEditorPage(
                    initial = editing ?: DdnsRecord(nativeProtocol = deviceProtocol),
                    externalError = actionError,
                    nativeDdns = nativeDdns,
                    wanInventory = wanResource.value,
                    onBack = { adding = false; editing = null },
                ) { record, password ->
                    scope.launch {
                        val result = if (editing == null) {
                            repository.addDdns(record, password.orEmpty())
                        } else {
                            repository.updateDdns(record, password)
                        }
                        result.onSuccess {
                            adding = false
                            editing = null
                            actionError = ""
                        }.onFailure {
                            actionError = it.message.orEmpty()
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target -> ConfirmDialog("删除DDNS记录？", "确认删除 ${target.domain}？", "删除", { scope.launch {
        repository.deleteDdns(target.serviceId).onSuccess{deleteTarget=null;actionError=""}.onFailure{actionError=it.message.orEmpty()}
    } }) { deleteTarget=null } }
}

@Composable
private fun DdnsCard(record:DdnsRecord,nativeDdns:Boolean=false,onEdit:()->Unit,onToggle:()->Unit,onDelete:()->Unit){
    // whole card must never turn red
    // Editing, switching and the overflow menu are separate hit targets.  The
    // overflow icon must never bubble into the card's edit action.
    val accent=RouterCyan
    val warning=record.status.contains("error",true)||record.status.contains("fail",true)
    val domainText=record.domain.ifBlank{"未命名 DDNS 记录"}
    // 记录上的协议字段可能是空的（Hub 快照不带它），这时按设备能力判，别把
    // 阿里云显示成 "aliyun.com"。
    val modernCard=ddnsEditorUsesModernForm(record.nativeProtocol,nativeDdns)
    val providerText=if(record.provider.isBlank())"未识别服务商" else if(modernCard)nativeDdnsProviderLabel(record.provider) else record.provider
    val interfaceText=record.interfaceName.ifBlank{"wan"}.uppercase(Locale.ROOT)
    val typeText=if(modernCard)record.effectiveRecordType else if(record.useIpv6)"IPv6" else "IPv4"
    var menu by remember(record.serviceId){mutableStateOf(false)}
    PremiumCard(accent){
        Row(verticalAlignment=Alignment.CenterVertically){
            Row(
                modifier=Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick=onEdit),
                verticalAlignment=Alignment.CenterVertically,
            ){
                RouterGlyphIcon(RouterGlyph.Ddns,accent,Modifier.size(27.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(domainText,style=LabTypography.ValueStrong,maxLines=2,overflow=TextOverflow.Clip)
                    Text("$providerText · $typeText · $interfaceText",fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=RouterMuted)
                    val detail = nativeDdnsDetailText(record)
                    Text(detail,style=LabTypography.Caption.copy(color=if(warning)RouterAmber else if(record.ip.isBlank())RouterMuted else RouterBlue),maxLines=2,overflow=TextOverflow.Clip)
                    if (record.lastUpdatedAt > 0L) {
                        Text("最后更新：${labProbeTimeText(record.lastUpdatedAt)}",fontSize=LabTypography.Caption.fontSize,color=RouterMuted)
                    }
                }
            }
            Switch(checked=record.enabled,onCheckedChange={onToggle()},modifier=Modifier.scale(.76f),colors=SwitchDefaults.colors(checkedTrackColor=accent))
            Box{
                IconButton(onClick={menu=true},modifier=Modifier.size(28.dp)){
                    Icon(Icons.Rounded.MoreVert,"更多操作",Modifier.size(16.dp),tint=RouterMuted)
                }
                LabDropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = LabPopupShapes.Menu,
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp, actionMenu = true) {
                    LabDropdownMenuItem(text = { Text("编辑", color = RouterInk) }, onClick = { menu = false; onEdit() })
                    LabDropdownMenuItem(text = { Text("删除", color = RouterRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
@Composable
private fun DdnsEditorPage(initial:DdnsRecord,externalError:String="",nativeDdns:Boolean=false,wanInventory:WanInventory?=null,onBack:()->Unit,onSave:(DdnsRecord,String?)->Unit){
    val normalizedInitial=remember(initial,wanInventory,nativeDdns){
        initial.copy(
            provider=initial.provider.trim().ifBlank{"aliyun.com"},
            interfaceName=initial.interfaceName.trim().ifBlank{wanInventory?.takeIf{nativeDdns}?.preferred("nativeDdns")?.wanId ?: "wan"},
            domain=initial.domain.trim(),
            username=initial.username.trim(),
            txtFamily=initial.txtFamily.trim().ifBlank{"ipv4"},
        )
    }
    // 记录自带协议就直接用；新建记录没有协议字段，只能靠设备能力判 —— Hub 的 DDNS
    // 快照并不下发 nativeProtocol，光看记录会永远掉回三选项的老表单。
    val modern=ddnsEditorUsesModernForm(normalizedInitial.nativeProtocol,nativeDdns)
    var record by remember(normalizedInitial){mutableStateOf(normalizedInitial)}
    var password by remember(normalizedInitial.serviceId){mutableStateOf("")}
    var showPassword by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val providerOptions=remember(record.provider,modern){
        if(modern){
            val defaults=nativeDdnsProviders
            if(record.provider in defaults)defaults else listOf(record.provider)+defaults
        }else{
            val defaults=listOf("aliyun.com","dnspod.cn","no-ip.com")
            if(record.provider in defaults)defaults else listOf(record.provider)+defaults
        }
    }
    val interfaceOptions=remember(wanInventory,record.interfaceName){
        nativeDdnsWanOptions(wanInventory,record.interfaceName)
    }
    val ddnsSupported = if (nativeDdns) wanInventory?.supportsLineCapability("nativeDdns") ?: true else true
    val recordTypes=remember(record.provider){
        listOf("A","AAAA","TXT").filter{nativeDdnsSupportsRecordType(record.provider,it)}
    }
    // Keep the selected record type valid when the provider changes.
    LaunchedEffect(record.provider){
        val current=record.effectiveRecordType.uppercase(Locale.ROOT)
        if(current !in recordTypes){
            record=record.copy(recordType=recordTypes.firstOrNull()?:"A",useIpv6=false)
        }
    }
    val flags=nativeDdnsProviderFlags(record.provider)
    val showZone=flags and 0x04!=0
    val showZoneId=flags and 0x08!=0
    val showRegion=flags and 0x10!=0
    val isTxt=record.effectiveRecordType.equals("TXT",ignoreCase=true)
    BackHandler(onBack=onBack)
    RouterFormPage(if(normalizedInitial.serviceId.isBlank())"新增DDNS" else "编辑DDNS","密钥由你输入；留空保持原值",onBack){
        CompactWaterfallChoice("服务商",record.provider,providerOptions,display={nativeDdnsProviderLabel(it)}){record=record.copy(provider=it)}
        CompactField("域名 / 记录",record.domain,"例如 rj.example.com"){record=record.copy(domain=it.take(128))}
        CompactField("用户名 / AccessKey",record.username,"AccessKey ID"){record=record.copy(username=it.take(160))}
        CompactPasswordField(if(normalizedInitial.passwordConfigured)"密码 / Secret（留空保持）" else "密码 / Secret",password,"请输入密钥",showPassword,{showPassword=!showPassword}){password=it.take(256)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(interfaceOptions.size>1) CompactChoice("接口",record.interfaceName,interfaceOptions,Modifier.weight(1f)){record=record.copy(interfaceName=it)}
            if(modern){
                CompactChoice("记录类型",record.effectiveRecordType.uppercase(Locale.ROOT),recordTypes,Modifier.weight(1f)){
                    record=record.copy(recordType=it,useIpv6=it.equals("AAAA",ignoreCase=true))
                }
            }else{
                CompactChoice("记录类型",if(record.useIpv6)"IPv6" else "IPv4",listOf("IPv6","IPv4"),Modifier.weight(1f)){record=record.copy(useIpv6=it=="IPv6")}
            }
        }
        if(!ddnsSupported) Text("当前固件或 Hub 未提供可用的原生 DDNS 线路",fontSize = LabTypography.Supporting.fontSize,color=RouterAmber,fontWeight=FontWeight.SemiBold)
        if(modern){
            if(showZone)CompactField("DNS 托管域 / Zone",record.zone,"例如 example.com"){record=record.copy(zone=it.take(160))}
            if(showZoneId)CompactField("区域 ID / Zone ID",record.zoneId,"Zone ID"){record=record.copy(zoneId=it.take(160))}
            if(showRegion)CompactField("区域 / Region",record.region,"例如 cn-north-4"){record=record.copy(region=it.take(80))}
            if(isTxt){
                CompactField("TXT 模板",record.txtTemplate,"例如 {{ip}}:{{port}}"){record=record.copy(txtTemplate=it.take(256))}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    CompactChoice("地址类型",record.txtFamily,listOf("ipv4","ipv6"),Modifier.weight(1f)){record=record.copy(txtFamily=it)}
                    CompactField("外部端口",record.externalPort.toString(),"0",Modifier.weight(1f),keyboardType=KeyboardType.Number){value->record=record.copy(externalPort=value.toIntOrNull()?.coerceIn(0,65535)?:0)}
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically){Text("启用记录",fontSize = LabTypography.Supporting.fontSize,fontWeight=FontWeight.SemiBold,color=RouterMuted);Spacer(Modifier.weight(1f));Switch(record.enabled,{record=record.copy(enabled=it)},modifier=Modifier.scale(.85f),colors=SwitchDefaults.colors(checkedTrackColor=RouterCyan))}
        val visibleError=error.ifBlank{externalError}
        if(visibleError.isNotBlank())Text(visibleError,fontSize = LabTypography.Supporting.fontSize,color=RouterRed)
        Button(onClick={
            error=when{
                record.domain.isBlank()->"请填写域名"
                modern&&isTxt&&record.txtTemplate.isBlank()->"请填写 TXT 模板"
                !modern&&record.username.isBlank()->"请填写账号/AccessKey"
                normalizedInitial.serviceId.isBlank()&&password.isBlank()->"请填写密码/Secret"
                else->""
            }
            if(error.isBlank())onSave(record,password.ifBlank{null})
        },enabled=ddnsSupported,modifier=Modifier.fillMaxWidth().height(42.dp),shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterCyan)){Text("保存并同步",fontSize = LabTypography.Supporting.fontSize,fontWeight=FontWeight.SemiBold)}
    }
}
@Composable
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
                        Text(when{task.active->task.stageText;task.failed->task.message.ifBlank{task.stageText};result.items.isEmpty()->"尚未检测";result.errorCount==0->"网络状态正常";else->"发现 ${result.errorCount} 项异常"},fontSize = LabTypography.Value.fontSize,fontWeight=FontWeight.SemiBold,color=RouterInk)
                        Text(if(task.active)"已耗时 ${task.elapsedSeconds} 秒 · 进度 ${result.progress}" else "进度 ${result.progress}",fontSize = LabTypography.Caption.fontSize,color=RouterMuted)
                        if(task.active&&task.lastRouterResponseAt<=0L)Text("检测已由 Hub 接管，可以安全离开页面",fontSize = LabTypography.Caption.fontSize,color=RouterMuted)
                    }
                    Button(onClick={tasks.startDiagnostic()},enabled=!task.active,shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.height(35.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterCyan,contentColor=Color.White,disabledContainerColor=RouterCyan.copy(alpha=.62f),disabledContentColor=Color.White)){Text(if(task.active)"检测中" else "开始检测",fontSize = LabTypography.Supporting.fontSize,fontWeight=FontWeight.SemiBold)}
                }
            }}
            if(task.failed)item{CompactMessage(task.message.ifBlank{task.stageText},RouterRed)}
            items(result.items){item->val accent=if(item.status=="success")RouterGreen else RouterAmber;PremiumCard(accent){Row(verticalAlignment=Alignment.Top){Icon(if(item.status=="success")Icons.Rounded.CheckCircle else Icons.Rounded.Warning,null,tint=accent,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(item.title.ifBlank{item.type},fontSize = LabTypography.Value.fontSize,fontWeight=FontWeight.SemiBold,color=RouterInk);Text(item.result,fontSize = LabTypography.Supporting.fontSize,fontWeight=FontWeight.SemiBold,color=RouterInk);if(item.port.isNotBlank())Text("问题接口：${item.port}",fontSize = LabTypography.Caption.fontSize,color=RouterRed);if(item.tips.isNotBlank())Text(item.tips,fontSize = LabTypography.Caption.fontSize,color=RouterMuted);if(item.advise.isNotBlank())Text(item.advise,fontSize = LabTypography.Caption.fontSize,color=RouterMuted,lineHeight = LabTypography.Caption.lineHeight)}}}}
        }
    }
}

@Composable
fun RouterHubStatusScreen(prefs: AppPrefs, onBack: () -> Unit, onOpenSettings: (() -> Unit)? = null) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.status.collectAsState()
    val status = resource.value
    val scope = repository.commandScope
    val agentSample = status?.source == "agent_dashboard_push"
    val sessionConnected = status?.sessionConnected == true || status?.connected == true
    val headline = when {
        agentSample -> "BE50 状态采样正常"
        sessionConnected && status?.dataAvailable == true -> "路由控制链路正常"
        sessionConnected -> "路由器会话正常"
        status == null -> "正在准备 Hub 状态"
        status.state == "router_login_failed" -> "路由器登录失败"
        else -> "路由控制暂不可用"
    }
    val detail = when {
        agentSample && status?.sessionConnected != true -> "状态数据来自 Relay，路由器管理连接待恢复"
        status == null -> "状态已在 APP 启动后预加载，页面不会重新建立连接"
        resource.error.isNotBlank() -> "后台同步较慢，已保留上次状态"
        sessionConnected && status.dataAvailable != true -> "路由器会话正常，控制快照正在同步"
        else -> status.message.ifBlank { "路由器登录与会话由 Hub 自动维护" }
    }
    val accent = if (sessionConnected) RouterGreen else RouterAmber

    RouterFormPage("Hub 状态", "实时 WSS 与路由控制状态独立", onBack) {
        PremiumCard(accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouterGlyphIcon(RouterGlyph.Connection, accent, Modifier.size(30.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(headline, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
                    Text(detail, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if ((status?.lastSuccessAt ?: 0L) > 0L) Text(routerLastSyncText(status!!.lastSuccessAt), fontSize = LabTypography.Caption.fontSize, color = RouterMuted)
                }
                Surface(shape = RoundedCornerShape(99.dp), color = accent.copy(alpha = .09f)) {
                    Text(if (sessionConnected) "正常" else "待同步", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = accent)
                }
            }
        }
        Button(
            onClick = { scope.launch { repository.refreshStatus(true) } },
            enabled = !resource.refreshing,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RouterBlue)
        ) {
            LabRefreshIcon(resource.refreshing, Modifier.size(17.dp), Color.White)
            Spacer(Modifier.width(7.dp))
            Text(if (resource.refreshing) "正在后台刷新" else "刷新 Hub 状态", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
        }
        if (onOpenSettings != null && (!sessionConnected || status?.state == "unconfigured" || status?.state == "router_login_failed")) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, RouterBlue),
            ) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(17.dp), tint = RouterBlue)
                Spacer(Modifier.width(7.dp))
                Text("修改路由器连接配置", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterBlue)
            }
        }
    }
}
private fun routerLastSyncText(lastSuccessAt: Long): String {
    if (lastSuccessAt <= 0L) return "最近同步：等待 Hub 同步"
    val seconds = (System.currentTimeMillis() / 1000L - lastSuccessAt).coerceAtLeast(0L)
    val age = when {
        seconds < 10L -> "刚刚"
        seconds < 60L -> "${seconds}秒前"
        seconds < 3600L -> "${seconds / 60L}分钟前"
        seconds < 86_400L -> "${seconds / 3600L}小时前"
        else -> "${seconds / 86_400L}天前"
    }
    return "最近同步：$age"
}

@Composable
private fun CompactTopBar(title:String,onBack:()->Unit,subtitle:String=""){
    Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=7.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick=onBack,modifier=Modifier.size(34.dp)){Icon(Icons.Rounded.ArrowBack,null,Modifier.size(20.dp),tint=RouterInk)}
        Column(Modifier.weight(1f)){Text(title,style=LabTypography.CardTitle,maxLines=1);if(subtitle.isNotBlank())Text(subtitle,style=LabTypography.Caption,maxLines=1,overflow=TextOverflow.Ellipsis)}
    }
}

@Composable
private fun RouterFormPage(title:String,subtitle:String,onBack:()->Unit,topBarColor:Color=Color.White,content:@Composable ColumnScope.()->Unit){
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(title, Modifier.fillMaxWidth().padding(horizontal = 30.dp), style = LabTypography.DialogTitle, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)) { Icon(Icons.Rounded.Close, "关闭", Modifier.size(20.dp)) }
        }
        if (subtitle.isNotBlank()) Text(subtitle, style = LabTypography.DialogBody, color = RouterMuted)
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun CompactToolbar(
    title: String,
    subtitle: String,
    loading: Boolean,
    onRefresh: (() -> Unit)?,
    onAdd: (() -> Unit)?,
    actionTouchSize: Dp = 36.dp,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
            Text(subtitle, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRefresh != null) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !loading,
                    modifier = Modifier.size(34.dp)
                ) {
                    LabRefreshIcon(loading, Modifier.size(18.dp), RouterBlue)
                }
            }
            if (onAdd != null) {
                Surface(
                    onClick = onAdd,
                    shape = CircleShape,
                    color = RouterBlue,
                    modifier = Modifier.size(35.dp),
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, "新增", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun PremiumCard(accent:Color,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Surface(modifier.fillMaxWidth(),shape=LabCoreSurface.CompactShape,color=LabCoreSurface.Card,border=androidx.compose.foundation.BorderStroke(1.dp,LabCoreSurface.Border),shadowElevation=2.dp){
        Column(Modifier.fillMaxWidth().padding(horizontal=11.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(5.dp),content=content)
    }
}

@Composable
private fun CompactField(label:String,value:String,hint:String,modifier:Modifier=Modifier,keyboardType:KeyboardType=KeyboardType.Text,onChange:(String)->Unit){
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(label,fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=RouterMuted)
        Surface(Modifier.fillMaxWidth().height(50.dp),shape=LabCoreSurface.InnerShape,color=LabCoreSurface.Inner,border=androidx.compose.foundation.BorderStroke(1.dp,LabCoreSurface.Border)){
            BasicTextField(
                value=value,
                onValueChange=onChange,
                modifier=Modifier.fillMaxSize(),
                singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=keyboardType),
                textStyle=TextStyle(fontSize = LabTypography.Value.fontSize,lineHeight = LabTypography.Value.lineHeight,fontWeight=FontWeight.SemiBold,color=RouterInk),
                cursorBrush=SolidColor(RouterBlue),
                decorationBox={inner->Row(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart){if(value.isEmpty())Text(hint,fontSize = LabTypography.Supporting.fontSize,lineHeight = LabTypography.Supporting.lineHeight,fontWeight=FontWeight.SemiBold,color=RouterMuted.copy(alpha=.78f),maxLines=1,overflow=TextOverflow.Clip);inner()}}}
            )
        }
    }
}

@Composable
private fun CompactMultilineField(label:String,value:String,hint:String,onChange:(String)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(label,fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=RouterMuted)
        Surface(Modifier.fillMaxWidth().heightIn(min=76.dp,max=132.dp),shape=RoundedCornerShape(13.dp),color=RouterField,border=androidx.compose.foundation.BorderStroke(1.dp,RouterBorder)){
            BasicTextField(
                value=value,
                onValueChange=onChange,
                modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),
                minLines=3,
                maxLines=8,
                textStyle=TextStyle(fontSize = LabTypography.Value.fontSize,lineHeight = LabTypography.Value.lineHeight,fontWeight=FontWeight.SemiBold,color=RouterInk),
                cursorBrush=SolidColor(RouterBlue),
                decorationBox={inner->Box{if(value.isEmpty())Text(hint,fontSize = LabTypography.Supporting.fontSize,lineHeight = LabTypography.Supporting.lineHeight,fontWeight=FontWeight.SemiBold,color=RouterMuted.copy(alpha=.78f));inner()}}
            )
        }
    }
}

@Composable
private fun CompactPasswordField(label:String,value:String,hint:String,visible:Boolean,onToggle:()->Unit,onChange:(String)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(label,fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=RouterMuted)
        Surface(Modifier.fillMaxWidth().height(50.dp),shape=LabCoreSurface.InnerShape,color=LabCoreSurface.Inner,border=androidx.compose.foundation.BorderStroke(1.dp,LabCoreSurface.Border)){
            BasicTextField(
                value=value,
                onValueChange=onChange,
                modifier=Modifier.fillMaxSize(),
                singleLine=true,
                visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),
                textStyle=TextStyle(fontSize = LabTypography.Value.fontSize,lineHeight = LabTypography.Value.lineHeight,fontWeight=FontWeight.SemiBold,color=RouterInk),
                cursorBrush=SolidColor(RouterBlue),
                decorationBox={inner->Row(Modifier.fillMaxSize().padding(start=12.dp,end=5.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart){if(value.isEmpty())Text(hint,fontSize = LabTypography.Supporting.fontSize,lineHeight = LabTypography.Supporting.lineHeight,fontWeight=FontWeight.SemiBold,color=RouterMuted.copy(alpha=.78f),maxLines=1);inner()};IconButton(onClick=onToggle,modifier=Modifier.size(34.dp)){Icon(if(visible)Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,null,Modifier.size(18.dp),tint=RouterMuted)}}}
            )
        }
    }
}

@Composable
private fun CompactChoice(label:String,value:String,options:List<String>,modifier:Modifier=Modifier,display:(String)->String={it},onPick:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
        if(label.isNotBlank())Text(label,fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=RouterMuted)
        Box{
            Surface(Modifier.fillMaxWidth().height(50.dp).clip(LabCoreSurface.InnerShape).clickable{expanded=true},shape=LabCoreSurface.InnerShape,color=LabCoreSurface.Inner,border=androidx.compose.foundation.BorderStroke(1.dp,LabCoreSurface.Border)){
                Row(Modifier.fillMaxSize().padding(horizontal=11.dp),verticalAlignment=Alignment.CenterVertically){Text(display(value),Modifier.weight(1f),fontSize = LabTypography.Supporting.fontSize,lineHeight = LabTypography.Supporting.lineHeight,fontWeight=FontWeight.SemiBold,color=RouterInk,maxLines=1,overflow=TextOverflow.Clip);Icon(Icons.Rounded.KeyboardArrowDown,null,Modifier.size(17.dp),tint=RouterMuted)}
            }
            LabDropdownMenu(expanded=expanded,onDismissRequest={expanded=false},shape = LabPopupShapes.Menu,containerColor=Color.White){options.forEach{option->LabDropdownMenuItem(text={Text(display(option),fontSize = LabTypography.Supporting.fontSize,fontWeight=if(option==value)FontWeight.SemiBold else FontWeight.SemiBold)},leadingIcon=if(option==value)({Icon(Icons.Rounded.Check,null,Modifier.size(15.dp),tint=RouterBlue)})else null,onClick={expanded=false;onPick(option)})}}
        }
    }
}

/**
 * 瀑布式下拉：点开后面板就地在原位向下展开，选项一条条淌下来。
 *
 * 和弹层菜单的区别在于它不抢层级 —— 选项长（服务商有十来个）、又在弹窗里的时候，
 * 弹层会被弹窗裁掉或者盖住表单，展开式列表只是把下面的内容推下去。
 */
@Composable
private fun CompactWaterfallChoice(
    label: String,
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    display: (String) -> String = { it },
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = LabCoreSurface.InnerShape
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) Text(label, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(LabCoreSurface.Inner)
                .border(androidx.compose.foundation.BorderStroke(1.dp, LabCoreSurface.Border), shape)
        ) {
            Row(
                Modifier.fillMaxWidth().height(50.dp).clickable { expanded = !expanded }.padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(display(value), Modifier.weight(1f), fontSize = LabTypography.Supporting.fontSize, lineHeight = LabTypography.Supporting.lineHeight, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Clip)
                Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, Modifier.size(17.dp), tint = RouterMuted)
            }
            if (expanded) {
                options.forEachIndexed { index, option ->
                    val selected = option == value
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(LabCoreSurface.Border))
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) Color(0xFFE9F7F8) else Color.Transparent)
                            .clickable { expanded = false; onPick(option) }
                            .padding(horizontal = 11.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            display(option),
                            fontSize = LabTypography.Supporting.fontSize,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) RouterCyan else RouterInk,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = RouterCyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSegment(text:String,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    CompactSegment(text, selected, modifier, true, onClick)
}

@Composable
private fun CompactSegment(text:String,selected:Boolean,modifier:Modifier,enabled:Boolean,onClick:()->Unit){
    Surface(onClick=onClick, enabled=enabled, modifier=modifier.height(33.dp), shape=RoundedCornerShape(11.dp), color=if(selected)RouterBlue else RouterField, border=androidx.compose.foundation.BorderStroke(1.dp,if(selected)RouterBlue else RouterBorder)){Box(contentAlignment=Alignment.Center){Text(text,fontSize = LabTypography.Supporting.fontSize,fontWeight=FontWeight.SemiBold,color=if(selected)Color.White else RouterMuted)}}
}

@Composable
private fun TinyBadge(text:String,color:Color){Surface(shape=RoundedCornerShape(99.dp),color=color.copy(alpha=.09f)){Text(text,Modifier.padding(horizontal=6.dp,vertical=2.dp),fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=color,maxLines=1)}}

@Composable
private fun CompactMessage(text:String,color:Color){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp),color=color.copy(alpha=.065f),border=androidx.compose.foundation.BorderStroke(1.dp,color.copy(alpha=.13f))){Text(uiMessageZh(text),Modifier.padding(horizontal=10.dp,vertical=7.dp),fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=color)}}

@Composable
private fun CompactEmpty(title:String,subtitle:String,glyph:RouterGlyph,onAdd:(()->Unit)?){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color.White,border=androidx.compose.foundation.BorderStroke(1.dp,RouterBorder)){Column(Modifier.fillMaxWidth().padding(vertical=18.dp,horizontal=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){RouterGlyphIcon(glyph,RouterBlue,Modifier.size(34.dp));Text(title,fontSize = LabTypography.Value.fontSize,fontWeight=FontWeight.SemiBold,color=RouterInk);Text(subtitle,fontSize = LabTypography.Caption.fontSize,color=RouterMuted);if(onAdd!=null)TextButton(onClick=onAdd,contentPadding=PaddingValues(horizontal=10.dp,vertical=2.dp)){Text("立即添加",fontSize = LabTypography.Supporting.fontSize,fontWeight=FontWeight.SemiBold)}}}}

@Composable
private fun LoadingBlock(){Box(Modifier.fillMaxWidth().height(130.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.4.dp)}}

@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    LabCompactAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = LabTypography.DialogTitle.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk) },
        text = { Text(text, fontSize = LabTypography.Value.fontSize, color = RouterMuted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, color = RouterRed, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = RouterMuted) } },
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        tonalElevation = 0.dp
    )
}

private fun formatBytesCompact(bytes:Long):String=when{bytes<1024->"${bytes}B";bytes<1024*1024->String.format(Locale.US,"%.1fKB",bytes/1024.0);else->String.format(Locale.US,"%.1fMB",bytes/1024.0/1024.0)}
