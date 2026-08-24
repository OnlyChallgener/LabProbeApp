package com.labprobe.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BasicTooltipBox
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val RouterBlue = Color(0xFF2E6BE6)
private val RouterCyan = Color(0xFF0AA6C7)
private val RouterGreen = Color(0xFF16A36A)
private val RouterAmber = Color(0xFFF59E0B)
private val RouterRed = Color(0xFFE94B55)
private val RouterInk = Color(0xFF17233A)
private val RouterMuted = Color(0xFF687890)
private val RouterField = Color(0xFFFBFDFF)
private val RouterBorder = Color(0xFFD9E8F7)
private val RouterPage = Color(0xFFF2F8FF)

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
        modifier = Modifier.width(104.dp).height(72.dp).clickable(onClick = onClick),
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
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * .064f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            RouterGlyph.Mapping, RouterGlyph.Port -> {
      // One shared bidirectional mapping symbol across settings, IPv6 mapping and native port mapping.
      val left = w * .18f
      val right = w * .82f
      val upper = h * .36f
      val lower = h * .64f
      drawLine(color, Offset(left, upper), Offset(right, upper), stroke.width, StrokeCap.Round)
      drawLine(color, Offset(right - w * .13f, upper - h * .10f), Offset(right, upper), stroke.width, StrokeCap.Round)
      drawLine(color, Offset(right - w * .13f, upper + h * .10f), Offset(right, upper), stroke.width, StrokeCap.Round)
      drawLine(color, Offset(right, lower), Offset(left, lower), stroke.width, StrokeCap.Round)
      drawLine(color, Offset(left + w * .13f, lower - h * .10f), Offset(left, lower), stroke.width, StrokeCap.Round)
      drawLine(color, Offset(left + w * .13f, lower + h * .10f), Offset(left, lower), stroke.width, StrokeCap.Round)
  }
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
fun MappingAndUpnpScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = 0, pageCount = { 3 })
    Scaffold(
        containerColor = RouterPage,
        topBar = {
            Surface(color = Color.White) {
                Column {
                    CompactTopBar("映射与 UPnP", onBack)
                    RouterSuiteTabs(pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
                }
            }
        }
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding), key = { it }) { page ->
            when (page) {
                0 -> LegacyIpv6MappingPage(prefs, onBack)
                1 -> NativePortMappingPage(prefs)
                else -> UpnpPage(prefs)
            }
        }
    }
}

@Composable
private fun LegacyIpv6MappingPage(prefs: AppPrefs, onBack: () -> Unit) {
    PortMappingScreen(prefs = prefs, onBack = onBack, embedded = true)
}

@Composable
private fun RouterSuiteTabs(selected: Int, onSelect: (Int) -> Unit) {
    val titles = listOf("IPv6映射", "端口映射", "UPnP")
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        titles.forEachIndexed { index, title ->
            val active = selected == index
            Surface(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(14.dp),
                color = if (active) Color(0xFFE8F1FF) else Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = if (active) BorderStroke(1.dp, RouterBlue.copy(alpha = .12f)) else null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        title,
                        style = LabTypography.Value.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) RouterBlue else RouterMuted
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun NativePortMappingPage(prefs: AppPrefs) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.portMappings.collectAsState()
    val rules = resource.value.orEmpty()
    val scope = repository.commandScope
    var actionError by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NativePortMapRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<NativePortMapRule?>(null) }
    val error = actionError.ifBlank { resource.error }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CompactToolbar(
                title = "路由器原生映射",
                subtitle = "${rules.size} 条 · IPv4 NAT",
                loading = resource.refreshing,
                onRefresh = { scope.launch { repository.refreshPortMappings(false) } },
                onAdd = { adding = true },
                actionTouchSize = 44.dp,
            )
        }
        if (error.isNotBlank()) item { CompactMessage(error, RouterAmber) }
        if (resource.mutating) item { CompactMessage("设置正在后台应用，页面可以安全退出", RouterBlue) }
        if (resource.value == null) item { CompactMessage("端口映射正在后台预加载，页面无需等待", RouterBlue) }
        if (resource.value != null && rules.isEmpty()) item { CompactEmpty("暂无端口映射", "路由器已有规则和新建规则都会显示在这里", RouterGlyph.Port) { adding = true } }
        items(rules, key = { it.ruleName }) { rule ->
            NativePortRuleCard(rule, onEdit = { editing = rule }, onDelete = { deleteTarget = rule })
        }
    }

    if (adding || editing != null) {
        NativePortEditorSheet(
            initial = editing ?: NativePortMapRule(),
            existingNames = rules.map { it.ruleName }.toSet(),
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
private fun NativePortRuleCard(rule: NativePortMapRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(rule.ruleName) { mutableStateOf(false) }
    val shape = LabCoreSurface.CompactShape
    val protocol = rule.proto.uppercase(Locale.ROOT)
    val protocolColor = if (protocol == "UDP") RouterCyan else RouterBlue
    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = LabCoreSurface.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, LabCoreSurface.Border),
        shadowElevation = 2.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).background(RouterBlue.copy(alpha = .10f), LabCoreSurface.InnerShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CompareArrows, "端口映射", Modifier.size(20.dp), tint = RouterBlue)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.ruleName, Modifier.weight(1f), fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TinyBadge(protocol, protocolColor)
                }
                NativePortRouteSummary(rule)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.MoreVert, "更多操作", Modifier.size(17.dp), tint = RouterMuted) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(22.dp),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 9.dp
                ) {
                    DropdownMenuItem(text = { Text("编辑", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(15.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterRed) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(15.dp), tint = RouterRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun NativePortRouteSummary(rule: NativePortMapRule) {
    val sourcePort = rule.srcPort.ifBlank { "--" }
    val destinationIp = rule.destIp.ifBlank { "--" }
    val destinationPort = rule.destPort.takeIf { it.isNotBlank() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("外网", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1)
        Spacer(Modifier.width(3.dp))
        Text("WAN:$sourcePort", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        Icon(Icons.Rounded.ArrowForward, null, Modifier.size(14.dp), tint = RouterMuted)
        Spacer(Modifier.width(5.dp))
        Text("内网", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1)
        Spacer(Modifier.width(3.dp))
        Text(destinationIp, Modifier.weight(1f), fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        destinationPort?.let { Text(":$it", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativePortEditorSheet(initial: NativePortMapRule, existingNames: Set<String>, onDismiss: () -> Unit, onSave: (NativePortMapRule) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = RouterPage) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 10.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactChoice("协议", draft.proto, listOf("tcp", "udp", "tcp+udp"), Modifier.weight(1f)) { draft = draft.copy(proto = it) }
                CompactChoice("来源", if (draft.srcIp.isBlank()) "全部WAN" else "指定IP", listOf("全部WAN", "指定IP"), Modifier.weight(1f)) {
                    draft = if (it == "全部WAN") draft.copy(src = "wan", srcIp = "") else draft.copy(src = "", srcIp = draft.srcIp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactField("外部端口", draft.srcPort, "80 或 1000-2000", Modifier.weight(1f), KeyboardType.Ascii) { draft = draft.copy(srcPort = it.take(32)) }
                CompactField("内部端口", draft.destPort, "80", Modifier.weight(1f), KeyboardType.Ascii) { draft = draft.copy(destPort = it.take(32)) }
            }
            CompactField("内部设备 / IP", draft.destIp, "192.168.5.46", keyboardType = KeyboardType.Ascii) { draft = draft.copy(destIp = it.take(64)) }
            AnimatedVisibility(draft.srcIp.isNotBlank() || draft.src.isBlank()) {
                CompactField("允许来源IP", draft.srcIp, "例如 10.0.0.8", keyboardType = KeyboardType.Ascii) { draft = draft.copy(src = "", srcIp = it.take(64)) }
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
}

@Composable
private fun UpnpPage(prefs: AppPrefs) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.upnp.collectAsState()
    val state = resource.value ?: UpnpState()
    val scope = repository.commandScope
    var actionError by remember { mutableStateOf("") }
    var confirmDisable by remember { mutableStateOf(false) }
    val error = actionError.ifBlank { resource.error }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { CompactToolbar("UPnP 服务", "${state.mappings.size} 条动态映射", false, { scope.launch { repository.refreshUpnp(false) } }, null) }
        if (error.isNotBlank()) item { CompactMessage(error, RouterAmber) }
        if (resource.mutating) item { CompactMessage("设置正在后台应用，页面可以安全退出", RouterBlue) }
        if (resource.value == null) item { CompactMessage("UPnP 快照正在后台预加载", RouterBlue) }
        item {
            PremiumCard(RouterCyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouterGlyphIcon(RouterGlyph.Upnp, RouterCyan, Modifier.size(30.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自动端口发现", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
                        Text("默认线路 ${state.wan} · ${state.mappings.size} 条活动映射", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                    }
                    Switch(
                        checked = state.enabled,
                        enabled = !resource.mutating,
                        onCheckedChange = { next ->
                            if (!next) confirmDisable = true else scope.launch {
                                repository.setUpnp(true, state.wan)
                                    .onSuccess { actionError = "" }
                                    .onFailure { actionError = it.message.orEmpty() }
                            }
                        },
                        modifier = Modifier.scale(.84f),
                        colors = SwitchDefaults.colors(checkedTrackColor = RouterCyan)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("默认线路", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                    Spacer(Modifier.weight(1f))
                    CompactChoice("", state.wan, listOf("AUTO", "WAN"), Modifier.width(116.dp)) { wan ->
                        scope.launch {
                            repository.setUpnp(state.enabled, wan)
                                .onSuccess { actionError = "" }
                                .onFailure { actionError = it.message.orEmpty() }
                        }
                    }
                }
            }
        }
        item { Text("动态映射", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, modifier = Modifier.padding(top = 2.dp, start = 2.dp)) }
        if (resource.value != null && state.mappings.isEmpty()) item { CompactEmpty("暂无动态映射", "内网设备申请 UPnP 端口后会显示在这里", RouterGlyph.Upnp, null) }
        items(state.mappings, key = { "${it.clientIp}-${it.protocol}-${it.externalPort}" }) { row ->
            PremiumCard(if (row.protocol == "TCP") RouterBlue else RouterCyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.name, Modifier.weight(1f), fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TinyBadge(row.protocol, if (row.protocol == "TCP") RouterBlue else RouterCyan)
                        }
                        Text(row.clientIp, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                        Text("内部 ${row.internalPort}  →  外部 ${row.externalPort}", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk)
                    }
                }
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
fun RouterFirewallScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val automationRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { FirewallAutomationRepository(prefs) }
    val resource by repository.firewall.collectAsState()
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
        FirewallEditorPage(
            initial = editing ?: FirewallRule(direction = currentDirection, inIface = if (currentDirection == "outbound") "" else "wan", outIface = if (currentDirection == "inbound") "" else "lan"),
            managedByMapping = editing?.let { bindings.containsKey(it.uuid) } == true,
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
                val visible = state.rules.filter { it.direction == dir }
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${visible.size} 条规则", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                            Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = { scope.launch { repository.refreshFirewall(false); automationRepository.refresh() } }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Rounded.Refresh, "刷新", Modifier.size(18.dp), tint = RouterBlue)
                                }
                                Surface(onClick = { adding = true }, shape = CircleShape, color = RouterBlue, modifier = Modifier.size(35.dp), shadowElevation = 2.dp) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Add, "新增", tint = Color.White, modifier = Modifier.size(19.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (error.isNotBlank()) item { CompactMessage(error, RouterAmber) }
                    if (automationResource.mutating) item { CompactMessage("自动跟随正在通过路由器 Web 防火墙安全核对", RouterBlue) }
                    if (resource.value == null) item { CompactMessage("防火墙规则正在后台预加载", RouterBlue) }
                    if (resource.value != null && visible.isEmpty()) item { CompactEmpty("暂无${directions[page].second}规则", "点右上角添加", RouterGlyph.Firewall) { adding = true } }
                    items(visible, key = { it.uuid }) { rule ->
                        FirewallRuleCard(
                            rule,
                            binding = bindings[rule.uuid],
                            onOpen = { editing = rule },
                            onFollow = { followRule = rule },
                            onToggle = { scope.launch {
                                repository.setFirewallEnabled(rule.uuid, !rule.enabled)
                                    .onSuccess { actionError = "" }
                                    .onFailure { actionError = it.message.orEmpty() }
                            } },
                            onDelete = { deleteTarget = rule }
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
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
private fun FirewallRuleCard(rule: FirewallRule, binding: FirewallAutomationBinding?, onOpen: () -> Unit, onFollow: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val accent = if (rule.target == "ACCEPT") RouterGreen else RouterRed
    PremiumCard(accent, Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (rule.enabled) accent else RouterMuted.copy(alpha=.45f), CircleShape))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rule.ruleName, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        modifier = Modifier.clickable(onClick = onFollow).padding(vertical = 1.dp),
                        fontSize = LabTypography.Caption.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = followColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                modifier = Modifier.width(54.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TinyBadge(if (rule.target == "ACCEPT") "允许" else "丢弃", accent)
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.scale(.76f), colors = SwitchDefaults.colors(checkedTrackColor = accent))
                IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(15.dp), tint = RouterMuted) }
            }
        }
    }
}

private fun firewallDirectionLabel(value: String): String = when (value.lowercase()) { "forward" -> "转发"; "inbound" -> "入站"; "outbound" -> "出站"; else -> value }
private fun firewallIpVersionLabel(value: String): String = when (value.lowercase()) { "ipv4" -> "IPv4"; "ipv6" -> "IPv6"; "dual" -> "双栈"; else -> value }
private fun firewallProtocolLabel(value: String): String = when (value.lowercase()) { "tcp" -> "TCP"; "udp" -> "UDP"; "icmp" -> "ICMP"; "any" -> "全部协议"; else -> value }
private fun firewallActionLabel(value: String): String = when (value.uppercase()) { "ACCEPT" -> "允许"; "DROP" -> "丢弃"; else -> value }
private fun firewallInterfaceLabel(value: String): String = when (value.lowercase()) { "wan" -> "外网（WAN）"; "lan" -> "内网（LAN）"; "" -> "路由器本机"; else -> value }

@Composable
private fun FirewallEditorPage(initial: FirewallRule, managedByMapping: Boolean, onBack: () -> Unit, onSave: (FirewallRule) -> Unit) {
    var rule by remember(initial.uuid) { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    val addressEnabled = rule.ipVersion != "dual"
    val portEnabled = rule.proto in setOf("tcp", "udp")
    RouterFormPage(if (initial.uuid.isBlank()) "新增防火墙规则" else "编辑防火墙规则", "精确匹配 · 保存后同步路由器", onBack, topBarColor = RouterPage) {
        if (managedByMapping) CompactMessage("这条规则已关联路由器映射。保存人工修改后，自动联动会暂停且不会覆盖新设置。", RouterAmber)
        CompactField("规则名称", rule.ruleName, "例如 WireGuard") { rule = rule.copy(ruleName = it.take(24)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactChoice("方向", rule.direction, listOf("forward", "inbound", "outbound"), Modifier.weight(1f), ::firewallDirectionLabel) { value -> rule = rule.copy(direction = value, inIface = if (value == "outbound") "" else rule.inIface.ifBlank { "wan" }, outIface = if (value == "inbound") "" else rule.outIface.ifBlank { "lan" }) }
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
            if (rule.direction != "outbound") CompactChoice("入接口", rule.inIface.ifBlank { "wan" }, listOf("wan", "lan"), Modifier.weight(1f), ::firewallInterfaceLabel) { rule = rule.copy(inIface = it) }
            if (rule.direction != "inbound") CompactChoice("出接口", rule.outIface.ifBlank { "lan" }, listOf("lan", "wan"), Modifier.weight(1f), ::firewallInterfaceLabel) { rule = rule.copy(outIface = it) }
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
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = RouterPage, topBar = { CompactTopBar("DDNS", onBack, "LabProbe DDNS · 路由器原生 DDNS · 证书监控") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactSegment("LabProbe DDNS", pagerState.currentPage == 0, Modifier.weight(1f)) {
                    scope.launch { pagerState.animateScrollToPage(0) }
                }
                CompactSegment("路由器原生 DDNS", pagerState.currentPage == 1, Modifier.weight(1f)) {
                    scope.launch { pagerState.animateScrollToPage(1) }
                }
                CompactSegment("证书监控", pagerState.currentPage == 2, Modifier.weight(1f)) {
                    scope.launch { pagerState.animateScrollToPage(2) }
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (page) {
                        0 -> LabProbeDdnsSection(prefs)
                        1 -> DdnsRecordsSection(prefs)
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
    val snapshot = resource.value
    val rows = snapshot?.records.orEmpty()
    val scope = repository.commandScope
    var actionError by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LabProbeDdnsRecord?>(null) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<LabProbeDdnsRecord?>(null) }

    LaunchedEffect(repository) { repository.refreshLabProbeDdns(false) }
    CompactToolbar(
        title = "LabProbe DDNS",
        subtitle = "独立于路由器原生 DDNS",
        loading = resource.refreshing,
        onRefresh = { scope.launch { repository.refreshLabProbeDdns(true) } },
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
            Surface(Modifier.fillMaxSize(), color = RouterPage) {
                LabProbeDdnsEditorPage(
                    initial = editing ?: LabProbeDdnsRecord(provider = "cloudflare"),
                    providers = snapshot?.providers.orEmpty(),
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
    PremiumCard(accent, Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouterGlyphIcon(RouterGlyph.Ddns, RouterBlue, Modifier.size(30.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(record.hostname.ifBlank { "未命名 DDNS 记录" }, style = LabTypography.ValueStrong.copy(color = RouterInk), maxLines = 2, overflow = TextOverflow.Clip)
                Text("${labProbeProviderLabel(record.provider)} · ${record.recordTypes.joinToString(" / ")}", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    TinyBadge(labProbeStatusLabel(record.status), accent)
                    Text(if (record.enabled) "已启用" else "已停用", fontSize = LabTypography.Caption.fontSize, color = if (record.enabled) RouterGreen else RouterMuted, fontWeight = FontWeight.SemiBold)
                }
            }
            Switch(checked = record.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.scale(.76f), colors = SwitchDefaults.colors(checkedTrackColor = RouterBlue))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.MoreVert, "更多操作", Modifier.size(17.dp), tint = RouterMuted) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                ) {
                    DropdownMenuItem(text = { Text(menuLabels[0], fontSize = LabTypography.Supporting.fontSize, color = RouterInk) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(menuLabels[1], fontSize = LabTypography.Supporting.fontSize, color = RouterInk) }, onClick = { menu = false; onToggle() })
                    DropdownMenuItem(text = { Text(menuLabels[2], fontSize = LabTypography.Supporting.fontSize, color = RouterRed) }, onClick = { menu = false; onDelete() })
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
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp,
                    ) {
                        DropdownMenuItem(text = { Text("编辑", fontSize = LabTypography.Supporting.fontSize, color = RouterInk) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(if (record.enabled) "停用" else "启用", fontSize = LabTypography.Supporting.fontSize, color = RouterInk) }, onClick = { menu = false; onToggle() })
                        DropdownMenuItem(text = { Text("删除", fontSize = LabTypography.Supporting.fontSize, color = RouterRed) }, onClick = { menu = false; onDelete() })
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
            if (record.recordTypes.any { it == "A" || it == "AAAA" }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshAddress, enabled = !busy, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp)) { Text(if (busy) "处理中" else "刷新检测地址", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold) }
                    Button(onClick = onUpdateNow, enabled = canUpdate, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = RouterBlue)) { Text(if (busy) "正在更新…" else "立即更新", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold) }
                }
            } else {
                Button(
                    onClick = onUpdateNow,
                    enabled = canUpdate,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RouterBlue),
                ) {
                    Text(
                        if (busy) "正在更新…" else "立即更新",
                        fontSize = LabTypography.Supporting.fontSize,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
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
        LabProbeCopyableValue(published.ifBlank { detected }, "未检测到地址", textSize = LabTypography.Value.fontSize)
        if (published.isNotBlank() && detected.isNotBlank() && published != detected) {
            Text("检测到新地址", fontSize = LabTypography.Caption.fontSize, color = RouterBlue, fontWeight = FontWeight.SemiBold)
        LabProbeCopyableValue(detected, "未检测到地址", textSize = LabTypography.Value.fontSize, textColor = RouterBlue)
        }
        Text("${labProbeAddressStateLabel(state)} · ${labProbeSourceLabel(source)}", fontSize = LabTypography.Caption.fontSize, color = RouterMuted, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LabProbeDdnsValueCard(type: String, label: String, value: String, published: String) {
    PremiumCard(if (published.isNotBlank()) RouterGreen else RouterAmber) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记录类型：$type", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = RouterInk, modifier = Modifier.weight(1f))
            TinyBadge(if (published.isNotBlank()) "已发布" else "待发布", if (published.isNotBlank()) RouterGreen else RouterAmber)
        }
        Text(label, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
        LabProbeCopyableValue(value, "未填写", textSize = LabTypography.Value.fontSize)
        Text("已发布", fontSize = LabTypography.Caption.fontSize, color = RouterMuted, fontWeight = FontWeight.SemiBold)
        LabProbeCopyableValue(published, "未发布", textSize = LabTypography.Supporting.fontSize, textColor = RouterMuted, textWeight = FontWeight.Medium)
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
    externalError: String,
    onBack: () -> Unit,
    onSave: (LabProbeDdnsRecord, Map<String, String>) -> Unit,
) {
    val normalized = remember(initial) { initial.copy(provider = initial.provider.ifBlank { "cloudflare" }, recordTypes = normalizeLabProbeRecordTypes(initial.recordTypes)) }
    var record by remember(normalized) { mutableStateOf(normalized) }
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
        CompactChoice("服务商", labProbeProviderLabel(record.provider), providerOptions) { selected ->
            val id = providerIds.getOrNull(providerOptions.indexOf(selected)) ?: record.provider
            val nextSpec = providers.firstOrNull { it.id.equals(id, ignoreCase = true) }
            val nextSupported = nextSpec?.recordTypes?.toSet() ?: setOf("A", "AAAA")
            val nextTypes = normalizeLabProbeRecordTypes(record.recordTypes.filter { it in nextSupported })
            record = record.copy(provider = id, recordTypes = nextTypes)
            credentialValues = emptyMap()
        }
        CompactField("域名", record.hostname, "例如 home.example.com") { record = record.copy(hostname = it.take(253)) }
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
private fun DdnsRecordsSection(prefs: AppPrefs) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.ddns.collectAsState()
    val rows = resource.value.orEmpty()
    val scope = repository.commandScope
    var actionError by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DdnsRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DdnsRecord?>(null) }
    val error = actionError.ifBlank { resource.error }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${rows.count { it.enabled }} 条启用 · ${rows.count { it.status.contains("error",true) || it.status.contains("fail",true) }} 条异常", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = RouterMuted)
        Spacer(Modifier.weight(1f))
        IconButton(onClick={scope.launch{repository.refreshDdns(false)}},modifier=Modifier.size(33.dp)){Icon(Icons.Rounded.Refresh,null,Modifier.size(17.dp),tint=RouterBlue)}
        Surface(onClick={adding=true},shape=CircleShape,color=RouterBlue,modifier=Modifier.size(34.dp)){Box(contentAlignment=Alignment.Center){Icon(Icons.Rounded.Add,null,tint=Color.White,modifier=Modifier.size(18.dp))}}
    }
    if(error.isNotBlank())CompactMessage(error,RouterAmber)
    if(resource.mutating)CompactMessage("设置正在后台应用，页面可以安全退出",RouterBlue)
    if(resource.value==null)CompactMessage("DDNS 快照正在后台预加载",RouterCyan)
    if(resource.value!=null&&rows.isEmpty())CompactEmpty("暂无DDNS记录","新增后由路由器原生服务更新",RouterGlyph.Ddns){adding=true}
    rows.forEach { record ->
        DdnsCard(record,onEdit={editing=record},onToggle={scope.launch{
            repository.updateDdns(record.copy(enabled=!record.enabled),null)
                .onSuccess{actionError=""}.onFailure{actionError=it.message.orEmpty()}
        }},onDelete={deleteTarget=record})
    }
    if (adding || editing != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { adding = false; editing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = RouterPage) {
                DdnsEditorPage(
                    initial = editing ?: DdnsRecord(),
                    externalError = actionError,
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
private fun DdnsCard(record:DdnsRecord,onEdit:()->Unit,onToggle:()->Unit,onDelete:()->Unit){
    // whole card must never turn red
    // Editing, switching and the overflow menu are separate hit targets.  The
    // overflow icon must never bubble into the card's edit action.
    val accent=RouterCyan
    val warning=record.status.contains("error",true)||record.status.contains("fail",true)
    val domainText=record.domain.ifBlank{"未命名 DDNS 记录"}
    val providerText=record.provider.ifBlank{"未识别服务商"}
    val interfaceText=record.interfaceName.ifBlank{"wan"}.uppercase(Locale.ROOT)
    var menu by remember(record.serviceId){mutableStateOf(false)}
    PremiumCard(accent){
        Row(verticalAlignment=Alignment.CenterVertically){
            Row(
                modifier=Modifier.weight(1f).clickable(onClick=onEdit),
                verticalAlignment=Alignment.CenterVertically,
            ){
                RouterGlyphIcon(RouterGlyph.Ddns,accent,Modifier.size(27.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(domainText,style=LabTypography.ValueStrong,maxLines=2,overflow=TextOverflow.Clip)
                    Text("$providerText · ${if(record.useIpv6)"IPv6" else "IPv4"} · $interfaceText",fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=RouterMuted)
                    Text(record.ip.ifBlank{record.status.ifBlank{"等待更新"}},style=LabTypography.Caption.copy(color=if(warning)RouterAmber else if(record.ip.isBlank())RouterMuted else RouterBlue),maxLines=2,overflow=TextOverflow.Clip)
                }
            }
            Switch(checked=record.enabled,onCheckedChange={onToggle()},modifier=Modifier.scale(.76f),colors=SwitchDefaults.colors(checkedTrackColor=accent))
            Box{
                IconButton(onClick={menu=true},modifier=Modifier.size(28.dp)){
                    Icon(Icons.Rounded.MoreVert,"更多操作",Modifier.size(16.dp),tint=RouterMuted)
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                ) {
                    DropdownMenuItem(text = { Text("编辑", fontSize = LabTypography.Supporting.fontSize, color = RouterInk) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", fontSize = LabTypography.Supporting.fontSize, color = RouterRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
@Composable
private fun DdnsEditorPage(initial:DdnsRecord,externalError:String="",onBack:()->Unit,onSave:(DdnsRecord,String?)->Unit){
    val normalizedInitial=remember(initial){
        initial.copy(
            provider=initial.provider.trim().ifBlank{"aliyun.com"},
            interfaceName=initial.interfaceName.trim().ifBlank{"wan"},
            domain=initial.domain.trim(),
            username=initial.username.trim(),
        )
    }
    var record by remember(normalizedInitial){mutableStateOf(normalizedInitial)}
    var password by remember(normalizedInitial.serviceId){mutableStateOf("")}
    var showPassword by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val providerOptions=remember(record.provider){
        val defaults=listOf("aliyun.com","dnspod.cn","no-ip.com")
        if(record.provider in defaults)defaults else listOf(record.provider)+defaults
    }
    val interfaceOptions=remember(record.interfaceName){
        val defaults=listOf("wan","wan1")
        if(record.interfaceName in defaults)defaults else listOf(record.interfaceName)+defaults
    }
    BackHandler(onBack=onBack)
    RouterFormPage(if(normalizedInitial.serviceId.isBlank())"新增DDNS" else "编辑DDNS","密钥由你输入；留空保持原值",onBack){
        CompactChoice("服务商",record.provider,providerOptions){record=record.copy(provider=it)}
        CompactField("域名 / 记录",record.domain,"例如 rj.lab86@shinya.icu"){record=record.copy(domain=it.take(128))}
        CompactField("用户名 / AccessKey",record.username,"AccessKey ID"){record=record.copy(username=it.take(160))}
        CompactPasswordField(if(normalizedInitial.passwordConfigured)"密码 / Secret（留空保持）" else "密码 / Secret",password,"请输入密钥",showPassword,{showPassword=!showPassword}){password=it.take(256)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){CompactChoice("接口",record.interfaceName,interfaceOptions,Modifier.weight(1f)){record=record.copy(interfaceName=it)};CompactChoice("记录类型",if(record.useIpv6)"IPv6" else "IPv4",listOf("IPv6","IPv4"),Modifier.weight(1f)){record=record.copy(useIpv6=it=="IPv6")}}
        Row(verticalAlignment=Alignment.CenterVertically){Text("启用记录",fontSize = LabTypography.Supporting.fontSize,fontWeight=FontWeight.SemiBold,color=RouterMuted);Spacer(Modifier.weight(1f));Switch(record.enabled,{record=record.copy(enabled=it)},modifier=Modifier.scale(.85f),colors=SwitchDefaults.colors(checkedTrackColor=RouterCyan))}
        val visibleError=error.ifBlank{externalError}
        if(visibleError.isNotBlank())Text(visibleError,fontSize = LabTypography.Supporting.fontSize,color=RouterRed)
        Button(onClick={error=when{record.domain.isBlank()->"请填写域名";record.username.isBlank()->"请填写账号/AccessKey";normalizedInitial.serviceId.isBlank()&&password.isBlank()->"请填写密码/Secret";else->""};if(error.isBlank())onSave(record,password.ifBlank{null})},modifier=Modifier.fillMaxWidth().height(42.dp),shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterCyan)){Text("保存并同步",fontSize = LabTypography.Supporting.fontSize,fontWeight=FontWeight.SemiBold)}
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
fun RouterHubStatusScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val resource by repository.status.collectAsState()
    val status = resource.value
    val scope = repository.commandScope
    val sessionConnected = status?.sessionConnected == true || status?.connected == true
    val headline = when {
        sessionConnected && status?.dataAvailable == true -> "路由控制链路正常"
        sessionConnected -> "路由器会话正常"
        status == null -> "正在准备 Hub 状态"
        status.state == "router_login_failed" -> "路由器登录失败"
        else -> "路由控制暂不可用"
    }
    val detail = when {
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
            Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (resource.refreshing) "正在后台刷新" else "刷新 Hub 状态", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
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
    Scaffold(containerColor=RouterPage,topBar={Surface(color=topBarColor){CompactTopBar(title,onBack,subtitle)}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=13.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
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
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = RouterBlue, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, "刷新", Modifier.size(18.dp), tint = RouterBlue)
                    }
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
            Surface(Modifier.fillMaxWidth().height(50.dp).clickable{expanded=true},shape=LabCoreSurface.InnerShape,color=LabCoreSurface.Inner,border=androidx.compose.foundation.BorderStroke(1.dp,LabCoreSurface.Border)){
                Row(Modifier.fillMaxSize().padding(horizontal=11.dp),verticalAlignment=Alignment.CenterVertically){Text(display(value),Modifier.weight(1f),fontSize = LabTypography.Supporting.fontSize,lineHeight = LabTypography.Supporting.lineHeight,fontWeight=FontWeight.SemiBold,color=RouterInk,maxLines=1,overflow=TextOverflow.Clip);Icon(Icons.Rounded.KeyboardArrowDown,null,Modifier.size(17.dp),tint=RouterMuted)}
            }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},shape=RoundedCornerShape(13.dp),containerColor=Color.White){options.forEach{option->DropdownMenuItem(text={Text(display(option),fontSize = LabTypography.Supporting.fontSize,fontWeight=if(option==value)FontWeight.SemiBold else FontWeight.SemiBold)},leadingIcon=if(option==value)({Icon(Icons.Rounded.Check,null,Modifier.size(15.dp),tint=RouterBlue)})else null,onClick={expanded=false;onPick(option)})}}
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
private fun ConfirmDialog(title:String,text:String,confirmText:String,onConfirm:()->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text(title,fontSize = LabTypography.CardTitle.fontSize,fontWeight=FontWeight.SemiBold)},text={Text(text,fontSize = LabTypography.Value.fontSize)},confirmButton={TextButton(onClick=onConfirm){Text(confirmText,color=RouterRed,fontWeight=FontWeight.SemiBold)}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}},shape=RoundedCornerShape(17.dp))}

private fun formatBytesCompact(bytes:Long):String=when{bytes<1024->"${bytes}B";bytes<1024*1024->String.format(Locale.US,"%.1fKB",bytes/1024.0);else->String.format(Locale.US,"%.1fMB",bytes/1024.0/1024.0)}
