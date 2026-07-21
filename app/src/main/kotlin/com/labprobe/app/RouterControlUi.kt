package com.labprobe.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BasicTooltipBox
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val RouterBlue = Color(0xFF2E6BE6)
private val RouterCyan = Color(0xFF0AA6C7)
private val RouterGreen = Color(0xFF16A36A)
private val RouterAmber = Color(0xFFF59E0B)
private val RouterRed = Color(0xFFE94B55)
private val RouterInk = Color(0xFF17233A)
private val RouterMuted = Color(0xFF687890)
private val RouterField = Color(0xFFF7F9FD)
private val RouterBorder = Color(0xFFE4EAF3)
private val RouterPage = Color(0xFFF5F8FD)

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
            Text("路由器功能", fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = RouterInk)
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
                    Text(connection.statusText, fontSize = 9.3.sp, fontWeight = FontWeight.Black, color = if (connection.connected) RouterGreen else RouterMuted)
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

private enum class RouterGlyph { Mapping, Ddns, Firewall, Diagnostic, Upnp, Port, Connection }

@Composable
private fun RouterFeatureCard(title: String, status: String, accent: Color, glyph: RouterGlyph, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(104.dp).height(72.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .12f)),
        shadowElevation = 1.5.dp
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(accent.copy(alpha = .055f), Color.White, Color.White)))
                .padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                RouterGlyphIcon(glyph, accent, Modifier.size(23.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, fontSize = 11.2.sp, lineHeight = 12.5.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1)
                    Text(status, fontSize = 8.7.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.align(Alignment.TopEnd).size(5.dp).background(accent.copy(alpha = .85f), CircleShape))
        }
    }
}

@Composable
private fun RouterGlyphIcon(glyph: RouterGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * .064f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            RouterGlyph.Mapping, RouterGlyph.Port -> {
                drawRoundRect(color.copy(alpha = .12f), Offset(w*.02f,h*.18f), Size(w*.34f,h*.52f), CornerRadius(w*.10f,w*.10f))
                drawRoundRect(color.copy(alpha = .12f), Offset(w*.64f,h*.30f), Size(w*.34f,h*.52f), CornerRadius(w*.10f,w*.10f))
                drawRoundRect(color, Offset(w*.05f,h*.21f), Size(w*.28f,h*.46f), CornerRadius(w*.08f,w*.08f), style=stroke)
                drawRoundRect(color, Offset(w*.67f,h*.33f), Size(w*.28f,h*.46f), CornerRadius(w*.08f,w*.08f), style=stroke)
                drawLine(color, Offset(w*.36f,h*.48f), Offset(w*.65f,h*.48f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w*.57f,h*.39f), Offset(w*.65f,h*.48f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w*.57f,h*.57f), Offset(w*.65f,h*.48f), stroke.width, StrokeCap.Round)
            }
            RouterGlyph.Ddns -> {
                val cloud = Path().apply {
                    moveTo(w*.17f,h*.64f); cubicTo(w*.02f,h*.61f,w*.05f,h*.38f,w*.24f,h*.38f)
                    cubicTo(w*.29f,h*.15f,w*.61f,h*.15f,w*.67f,h*.38f)
                    cubicTo(w*.88f,h*.33f,w*.98f,h*.58f,w*.82f,h*.69f); lineTo(w*.24f,h*.69f)
                }
                drawPath(cloud,color,style=stroke)
                drawArc(color, -50f, 150f, false, Offset(w*.36f,h*.38f), Size(w*.36f,h*.36f), style=stroke)
                drawLine(color,Offset(w*.69f,h*.39f),Offset(w*.69f,h*.54f),stroke.width,StrokeCap.Round)
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
                drawCircle(color.copy(alpha=.10f), w*.42f, Offset(w*.5f,h*.5f))
                drawArc(color, 205f, 130f, false, Offset(w*.18f,h*.12f), Size(w*.64f,h*.58f), style=stroke)
                drawArc(color, 210f, 120f, false, Offset(w*.31f,h*.29f), Size(w*.38f,h*.34f), style=stroke)
                drawCircle(color, w*.06f, Offset(w*.5f,h*.72f))
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
            Surface(color = Color.White, shadowElevation = 1.dp) {
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
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().offset(y = (-62).dp)) {
            PortMappingScreen(prefs = prefs, onBack = onBack)
        }
    }
}

@Composable
private fun RouterSuiteTabs(selected: Int, onSelect: (Int) -> Unit) {
    val titles = listOf("IPv6映射", "端口映射", "UPnP")
    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        titles.forEachIndexed { index, title ->
            Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                Text(title, fontSize = 11.7.sp, fontWeight = if (selected == index) FontWeight.Black else FontWeight.SemiBold, color = if (selected == index) RouterBlue else RouterMuted)
                if (selected == index) Box(Modifier.align(Alignment.BottomCenter).width(30.dp).height(2.2.dp).background(RouterBlue, RoundedCornerShape(99.dp)))
            }
        }
    }
}

@Composable
private fun NativePortMappingPage(prefs: AppPrefs) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<NativePortMapRule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NativePortMapRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<NativePortMapRule?>(null) }

    suspend fun refresh(force: Boolean = false) {
        if (!force) loading = true
        runCatching { api.nativePortMappings(force) }
            .onSuccess { rules = it; error = "" }
            .onFailure { error = it.message.orEmpty() }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { CompactToolbar("路由器原生映射", "${rules.size} 条 · IPv4 NAT", loading, { scope.launch { refresh(true) } }, { adding = true }) }
        if (error.isNotBlank()) item { CompactMessage(error, RouterRed) }
        if (loading && rules.isEmpty()) item { LoadingBlock() }
        if (!loading && rules.isEmpty()) item { CompactEmpty("暂无端口映射", "路由器已有规则和新建规则都会显示在这里", RouterGlyph.Port) { adding = true } }
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
                    runCatching {
                        if (editing == null) api.addNativePortMapping(saved)
                        else api.updateNativePortMapping(editing!!.ruleName, saved)
                    }.onSuccess { rules = it; adding = false; editing = null; error = "" }
                        .onFailure { error = it.message.orEmpty() }
                }
            }
        )
    }
    deleteTarget?.let { target ->
        ConfirmDialog("删除端口映射？", "删除“${target.ruleName}”后，外部访问会立即中断。", "删除", {
            scope.launch {
                runCatching { api.deleteNativePortMapping(target.ruleName) }
                    .onSuccess { rules = it; deleteTarget = null }
                    .onFailure { error = it.message.orEmpty() }
            }
        }) { deleteTarget = null }
    }
}

@Composable
private fun NativePortRuleCard(rule: NativePortMapRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(rule.ruleName) { mutableStateOf(false) }
    PremiumCard(RouterBlue) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RouterGlyphIcon(RouterGlyph.Port, RouterBlue, Modifier.size(27.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.ruleName, Modifier.weight(1f), fontSize = 12.4.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TinyBadge(rule.proto.uppercase(), RouterBlue)
                }
                Text("WAN ${rule.srcPort}  →  ${rule.destIp}:${rule.destPort}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (rule.srcIp.isBlank()) "来源：全部公网地址" else "来源：${rule.srcIp}", fontSize = 9.4.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, null, Modifier.size(17.dp), tint = RouterMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(14.dp), containerColor = Color.White) {
                    DropdownMenuItem(text = { Text("编辑", fontSize = 11.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(15.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", fontSize = 11.5.sp, color = RouterRed) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(15.dp), tint = RouterRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativePortEditorSheet(initial: NativePortMapRule, existingNames: Set<String>, onDismiss: () -> Unit, onSave: (NativePortMapRule) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = Color.White,
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 4.dp).width(32.dp).height(3.dp).background(RouterBorder, RoundedCornerShape(99.dp))) }
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.86f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (initial.ruleName.isBlank()) "新增端口映射" else "编辑端口映射", fontSize = 15.5.sp, fontWeight = FontWeight.Black, color = RouterInk)
                    Text("路由器原生 IPv4 NAT", fontSize = 9.8.sp, color = RouterMuted)
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
            if (error.isNotBlank()) Text(error, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = RouterRed)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp)) { Text("取消", fontSize = 11.5.sp, fontWeight = FontWeight.Black) }
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
                ) { Text("保存并同步", fontSize = 11.5.sp, fontWeight = FontWeight.Black) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun UpnpPage(prefs: AppPrefs) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(UpnpState()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmDisable by remember { mutableStateOf(false) }

    suspend fun refresh(force: Boolean = false) {
        if (!force) loading = true
        runCatching { api.upnp(force) }.onSuccess { state = it; error = "" }.onFailure { error = it.message.orEmpty() }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { CompactToolbar("UPnP 服务", "${state.mappings.size} 条动态映射", loading, { scope.launch { refresh(true) } }, null) }
        if (error.isNotBlank()) item { CompactMessage(error, RouterRed) }
        item {
            PremiumCard(RouterCyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouterGlyphIcon(RouterGlyph.Upnp, RouterCyan, Modifier.size(30.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自动端口发现", fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = RouterInk)
                        Text("默认线路 ${state.wan} · ${state.mappings.size} 条活动映射", fontSize = 9.6.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                    }
                    if (saving) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp) else Switch(
                        checked = state.enabled,
                        onCheckedChange = { next ->
                            if (!next) confirmDisable = true else scope.launch {
                                saving = true
                                runCatching { api.setUpnp(true, state.wan) }.onSuccess { state = it }.onFailure { error = it.message.orEmpty() }
                                saving = false
                            }
                        },
                        modifier = Modifier.scale(.84f),
                        colors = SwitchDefaults.colors(checkedTrackColor = RouterCyan)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("默认线路", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
                    Spacer(Modifier.weight(1f))
                    CompactChoice("", state.wan, listOf("AUTO", "WAN"), Modifier.width(116.dp)) { wan ->
                        scope.launch {
                            saving = true
                            runCatching { api.setUpnp(state.enabled, wan) }.onSuccess { state = it }.onFailure { error = it.message.orEmpty() }
                            saving = false
                        }
                    }
                }
            }
        }
        item { Text("动态映射", fontSize = 12.3.sp, fontWeight = FontWeight.Black, color = RouterInk, modifier = Modifier.padding(top = 2.dp, start = 2.dp)) }
        if (!loading && state.mappings.isEmpty()) item { CompactEmpty("暂无动态映射", "内网设备申请 UPnP 端口后会显示在这里", RouterGlyph.Upnp, null) }
        items(state.mappings, key = { "${it.clientIp}-${it.protocol}-${it.externalPort}" }) { row ->
            PremiumCard(if (row.protocol == "TCP") RouterBlue else RouterCyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.name, Modifier.weight(1f), fontSize = 11.8.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TinyBadge(row.protocol, if (row.protocol == "TCP") RouterBlue else RouterCyan)
                        }
                        Text(row.clientIp, fontSize = 9.7.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                        Text("内部 ${row.internalPort}  →  外部 ${row.externalPort}", fontSize = 10.3.sp, fontWeight = FontWeight.Bold, color = RouterInk)
                    }
                }
            }
        }
    }
    if (confirmDisable) ConfirmDialog("关闭 UPnP？", "部分游戏、下载和远程访问可能受到影响。", "关闭", {
        confirmDisable = false
        scope.launch {
            saving = true
            runCatching { api.setUpnp(false, state.wan) }.onSuccess { state = it }.onFailure { error = it.message.orEmpty() }
            saving = false
        }
    }) { confirmDisable = false }
}

@Composable
fun RouterFirewallScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(FirewallState()) }
    var direction by remember { mutableStateOf("forward") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<FirewallRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FirewallRule?>(null) }

    suspend fun refresh(force: Boolean = false) {
        if (!force) loading = true
        runCatching { api.firewall(force) }.onSuccess { state = it; error = "" }.onFailure { error = it.message.orEmpty() }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }
    val visible = state.rules.filter { it.direction == direction }

    if (adding || editing != null) {
        FirewallEditorPage(
            initial = editing ?: FirewallRule(direction = direction, inIface = if (direction == "outbound") "" else "wan", outIface = if (direction == "inbound") "" else "lan"),
            onBack = { adding = false; editing = null },
            onSave = { rule -> scope.launch {
                runCatching { if (editing == null) api.addFirewallRule(rule) else api.updateFirewallRule(rule) }
                    .onSuccess { state = it; adding = false; editing = null }
                    .onFailure { error = it.message.orEmpty() }
            } }
        )
        return
    }

    Scaffold(containerColor = RouterPage, topBar = { CompactTopBar("防火墙", onBack, "${state.rules.count { it.enabled }} 条启用 · ${state.rules.size}/${state.maxRules}") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("forward" to "转发", "inbound" to "入站", "outbound" to "出站").forEach { (value, label) -> CompactSegment(label, direction == value, Modifier.weight(1f)) { direction = value } }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${visible.size} 条规则", fontSize = 10.3.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { refresh(true) } }, modifier = Modifier.size(34.dp)) { if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp), tint = RouterBlue) }
                    Surface(onClick = { adding = true }, shape = CircleShape, color = RouterBlue, modifier = Modifier.size(35.dp), shadowElevation = 2.dp) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(19.dp)) } }
                }
            }
            if (error.isNotBlank()) item { CompactMessage(error, RouterRed) }
            if (!loading && visible.isEmpty()) item { CompactEmpty("暂无${when(direction){"inbound"->"入站";"outbound"->"出站";else->"转发"}}规则", "点右上角添加", RouterGlyph.Firewall) { adding = true } }
            items(visible, key = { it.uuid }) { rule ->
                FirewallRuleCard(
                    rule,
                    onOpen = { editing = rule },
                    onToggle = { scope.launch { runCatching { api.setFirewallEnabled(rule.uuid, !rule.enabled) }.onSuccess { state = it }.onFailure { error = it.message.orEmpty() } } },
                    onDelete = { deleteTarget = rule }
                )
            }
        }
    }
    deleteTarget?.let { rule ->
        ConfirmDialog("删除防火墙规则？", "删除“${rule.ruleName}”可能立即影响远程访问。", "删除", {
            scope.launch { runCatching { api.deleteFirewallRule(rule.uuid) }.onSuccess { state = it; deleteTarget = null }.onFailure { error = it.message.orEmpty() } }
        }) { deleteTarget = null }
    }
}

@Composable
private fun FirewallRuleCard(rule: FirewallRule, onOpen: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val accent = if (rule.target == "ACCEPT") RouterGreen else RouterRed
    PremiumCard(accent, Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (rule.enabled) accent else RouterMuted.copy(alpha=.45f), CircleShape))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.ruleName, Modifier.weight(1f), fontSize = 12.2.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TinyBadge(if (rule.target == "ACCEPT") "允许" else "丢弃", accent)
                }
                val port = if (rule.proto in setOf("tcp", "udp")) rule.destPort.ifBlank { "任意端口" } else "不匹配端口"
                Text("${rule.ipVersion.uppercase()} · ${rule.proto.uppercase()} · $port", fontSize = 9.7.sp, fontWeight = FontWeight.Bold, color = RouterMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val targetText = rule.destIP.ifBlank { rule.ipv6SuffixDest.ifBlank { "任意目标" } }
                Text("${rule.inIface.ifBlank { "本机" }.uppercase()} → ${rule.outIface.ifBlank { "本机" }.uppercase()} · $targetText", fontSize = 9.6.sp, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("命中 ${rule.stats.packets} 次 · ${formatBytesCompact(rule.stats.bytes)}", fontSize = 8.9.sp, color = RouterMuted)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.scale(.76f), colors = SwitchDefaults.colors(checkedTrackColor = accent))
                IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(15.dp), tint = RouterMuted) }
            }
        }
    }
}

@Composable
private fun FirewallEditorPage(initial: FirewallRule, onBack: () -> Unit, onSave: (FirewallRule) -> Unit) {
    var rule by remember(initial.uuid) { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    val addressEnabled = rule.ipVersion != "dual"
    val portEnabled = rule.proto in setOf("tcp", "udp")
    RouterFormPage(if (initial.uuid.isBlank()) "新增防火墙规则" else "编辑防火墙规则", "精确匹配 · 保存后同步路由器", onBack) {
        CompactField("规则名称", rule.ruleName, "例如 WireGuard") { rule = rule.copy(ruleName = it.take(24)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactChoice("方向", rule.direction, listOf("forward", "inbound", "outbound"), Modifier.weight(1f)) { value -> rule = rule.copy(direction = value, inIface = if (value == "outbound") "" else rule.inIface.ifBlank { "wan" }, outIface = if (value == "inbound") "" else rule.outIface.ifBlank { "lan" }) }
            CompactChoice("IP版本", rule.ipVersion, listOf("ipv4", "ipv6", "dual"), Modifier.weight(1f)) { rule = rule.copy(ipVersion = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactChoice("协议", rule.proto, listOf("tcp", "udp", "icmp", "any"), Modifier.weight(1f)) { rule = rule.copy(proto = it, srcPort = if (it in setOf("tcp","udp")) rule.srcPort else "", destPort = if (it in setOf("tcp","udp")) rule.destPort else "") }
            CompactChoice("动作", rule.target, listOf("ACCEPT", "DROP"), Modifier.weight(1f)) { rule = rule.copy(target = it) }
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
            if (rule.direction != "outbound") CompactChoice("入接口", rule.inIface.ifBlank { "wan" }, listOf("wan", "lan"), Modifier.weight(1f)) { rule = rule.copy(inIface = it) }
            if (rule.direction != "inbound") CompactChoice("出接口", rule.outIface.ifBlank { "lan" }, listOf("lan", "wan"), Modifier.weight(1f)) { rule = rule.copy(outIface = it) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("保存后立即启用", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
            Spacer(Modifier.weight(1f))
            Switch(checked = rule.enabled, onCheckedChange = { rule = rule.copy(enabled = it) }, modifier = Modifier.scale(.85f), colors = SwitchDefaults.colors(checkedTrackColor = RouterBlue))
        }
        if (error.isNotBlank()) Text(error, fontSize = 10.5.sp, color = RouterRed, fontWeight = FontWeight.SemiBold)
        Button(onClick = { error = if (rule.ruleName.isBlank()) "请填写规则名称" else ""; if (error.isBlank()) onSave(rule) }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = RouterBlue)) { Text("保存并同步路由器", fontSize = 11.5.sp, fontWeight = FontWeight.Black) }
    }
}

@Composable
fun RouterDdnsScreen(prefs: AppPrefs, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(containerColor = RouterPage, topBar = { CompactTopBar("DDNS 与证书", onBack, "动态域名 · 到期提醒") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactSegment("DDNS记录", tab == 0, Modifier.weight(1f)) { tab = 0 }
                CompactSegment("证书监控", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == 0) DdnsRecordsSection(prefs) else CertificateExpirySection(prefs)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DdnsRecordsSection(prefs: AppPrefs) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DdnsRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DdnsRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DdnsRecord?>(null) }
    suspend fun refresh(force:Boolean=false){ if(!force)loading=true;runCatching{api.ddns(force)}.onSuccess{rows=it;error=""}.onFailure{error=it.message.orEmpty()};loading=false }
    LaunchedEffect(Unit){refresh()}
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${rows.count { it.enabled }} 条启用 · ${rows.count { it.status.contains("error",true) || it.status.contains("fail",true) }} 条异常", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
        Spacer(Modifier.weight(1f))
        IconButton(onClick={scope.launch{refresh(true)}},modifier=Modifier.size(33.dp)){if(loading)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)else Icon(Icons.Rounded.Refresh,null,Modifier.size(17.dp),tint=RouterBlue)}
        Surface(onClick={adding=true},shape=CircleShape,color=RouterBlue,modifier=Modifier.size(34.dp)){Box(contentAlignment=Alignment.Center){Icon(Icons.Rounded.Add,null,tint=Color.White,modifier=Modifier.size(18.dp))}}
    }
    if(error.isNotBlank())CompactMessage(error,RouterRed)
    if(!loading&&rows.isEmpty())CompactEmpty("暂无DDNS记录","新增后由路由器原生服务更新",RouterGlyph.Ddns){adding=true}
    rows.forEach { record ->
        DdnsCard(record,onEdit={editing=record},onToggle={scope.launch{runCatching{api.updateDdns(record.copy(enabled=!record.enabled),null)}.onSuccess{rows=it}.onFailure{error=it.message.orEmpty()}}},onDelete={deleteTarget=record})
    }
    if(adding||editing!=null)DdnsEditorPage(editing?:DdnsRecord(),onBack={adding=false;editing=null}){record,password->scope.launch{runCatching{if(editing==null)api.addDdns(record,password.orEmpty())else api.updateDdns(record,password)}.onSuccess{rows=it;adding=false;editing=null}.onFailure{error=it.message.orEmpty()}}}
    deleteTarget?.let { target -> ConfirmDialog("删除DDNS记录？", "确认删除 ${target.domain}？", "删除", { scope.launch { runCatching { api.deleteDdns(target.serviceId) }.onSuccess { rows=it; deleteTarget=null }.onFailure { error=it.message.orEmpty() } } }) { deleteTarget=null } }
}

@Composable
private fun DdnsCard(record:DdnsRecord,onEdit:()->Unit,onToggle:()->Unit,onDelete:()->Unit){
    val good=record.status.isBlank()||record.status.contains("success",true)||record.status.contains("ok",true)
    val accent=if(good)RouterGreen else RouterRed
    var menu by remember(record.serviceId){mutableStateOf(false)}
    PremiumCard(accent,Modifier.clickable(onClick=onEdit)){
        Row(verticalAlignment=Alignment.CenterVertically){
            RouterGlyphIcon(RouterGlyph.Ddns,accent,Modifier.size(27.dp));Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                Text(record.domain,fontSize=11.9.sp,fontWeight=FontWeight.Black,color=RouterInk,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${record.provider} · ${if(record.useIpv6)"IPv6" else "IPv4"} · ${record.interfaceName.uppercase()}",fontSize=9.5.sp,fontWeight=FontWeight.Bold,color=RouterMuted)
                Text(record.ip.ifBlank{record.status.ifBlank{"等待更新"}},fontSize=9.8.sp,fontWeight=FontWeight.SemiBold,color=if(record.ip.isBlank())RouterMuted else RouterBlue,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Switch(checked=record.enabled,onCheckedChange={onToggle()},modifier=Modifier.scale(.76f),colors=SwitchDefaults.colors(checkedTrackColor=accent))
            Box{IconButton(onClick={menu=true},modifier=Modifier.size(28.dp)){Icon(Icons.Rounded.MoreVert,null,Modifier.size(16.dp),tint=RouterMuted)};DropdownMenu(expanded=menu,onDismissRequest={menu=false}){DropdownMenuItem(text={Text("编辑",fontSize=11.5.sp)},onClick={menu=false;onEdit()});DropdownMenuItem(text={Text("删除",fontSize=11.5.sp,color=RouterRed)},onClick={menu=false;onDelete()})}}
        }
    }
}

@Composable
private fun DdnsEditorPage(initial:DdnsRecord,onBack:()->Unit,onSave:(DdnsRecord,String?)->Unit){
    var record by remember(initial.serviceId){mutableStateOf(initial)}
    var password by remember{mutableStateOf("")}
    var showPassword by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    BackHandler(onBack=onBack)
    RouterFormPage(if(initial.serviceId.isBlank())"新增DDNS" else "编辑DDNS","密钥由你输入；留空保持原值",onBack){
        CompactChoice("服务商",record.provider,listOf("aliyun.com","dnspod.cn","no-ip.com")){record=record.copy(provider=it)}
        CompactField("域名 / 记录",record.domain,"例如 rj.lab86@shinya.icu"){record=record.copy(domain=it.take(128))}
        CompactField("用户名 / AccessKey",record.username,"AccessKey ID"){record=record.copy(username=it.take(160))}
        CompactPasswordField(if(initial.passwordConfigured)"密码 / Secret（留空保持）" else "密码 / Secret",password,"请输入密钥",showPassword,{showPassword=!showPassword}){password=it.take(256)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){CompactChoice("接口",record.interfaceName,listOf("wan","wan1"),Modifier.weight(1f)){record=record.copy(interfaceName=it)};CompactChoice("记录类型",if(record.useIpv6)"IPv6" else "IPv4",listOf("IPv6","IPv4"),Modifier.weight(1f)){record=record.copy(useIpv6=it=="IPv6")}}
        Row(verticalAlignment=Alignment.CenterVertically){Text("启用记录",fontSize=10.5.sp,fontWeight=FontWeight.Bold,color=RouterMuted);Spacer(Modifier.weight(1f));Switch(record.enabled,{record=record.copy(enabled=it)},modifier=Modifier.scale(.85f),colors=SwitchDefaults.colors(checkedTrackColor=RouterCyan))}
        if(error.isNotBlank())Text(error,fontSize=10.5.sp,color=RouterRed)
        Button(onClick={error=when{record.domain.isBlank()->"请填写域名";record.username.isBlank()->"请填写账号/AccessKey";initial.serviceId.isBlank()&&password.isBlank()->"请填写密码/Secret";else->""};if(error.isBlank())onSave(record,password.ifBlank{null})},modifier=Modifier.fillMaxWidth().height(42.dp),shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterCyan)){Text("保存并同步",fontSize=11.5.sp,fontWeight=FontWeight.Black)}
    }
}

@Composable
fun RouterDiagnosticScreen(prefs:AppPrefs,onBack:()->Unit){
    val api=remember(prefs.hub,prefs.token,prefs.hubDns){RouterControlApi(prefs)}
    val scope=rememberCoroutineScope()
    var result by remember{mutableStateOf(RouterDiagnostic())}
    var running by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    suspend fun refresh(){runCatching{api.diagnostic()}.onSuccess{result=it;error=""}.onFailure{error=it.message.orEmpty()}}
    LaunchedEffect(Unit){refresh()}
    LaunchedEffect(running){while(running){refresh();if(result.progress.startsWith("100"))running=false else delay(1000)}}
    Scaffold(containerColor=RouterPage,topBar={CompactTopBar("网络自检",onBack,"物理接线 · 协商速率 · 网络状态")}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{PremiumCard(if(result.errorCount==0)RouterGreen else RouterAmber){Row(verticalAlignment=Alignment.CenterVertically){RouterGlyphIcon(RouterGlyph.Diagnostic,if(result.errorCount==0)RouterGreen else RouterAmber,Modifier.size(31.dp));Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text(if(running)"检测进行中" else if(result.items.isEmpty())"尚未检测" else if(result.errorCount==0)"网络状态正常" else "发现 ${result.errorCount} 项异常",fontSize=12.5.sp,fontWeight=FontWeight.Black,color=RouterInk);Text("进度 ${result.progress}",fontSize=9.7.sp,color=RouterMuted)};Button(onClick={scope.launch{running=true;runCatching{api.startDiagnostic()}.onFailure{error=it.message.orEmpty();running=false}}},enabled=!running,shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.height(35.dp)){Text(if(running)"检测中" else "重新检测",fontSize=10.3.sp,fontWeight=FontWeight.Black)}}}}
            if(error.isNotBlank())item{CompactMessage(error,RouterRed)}
            items(result.items){item->val accent=if(item.status=="success")RouterGreen else RouterAmber;PremiumCard(accent){Row(verticalAlignment=Alignment.Top){Icon(if(item.status=="success")Icons.Rounded.CheckCircle else Icons.Rounded.Warning,null,tint=accent,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(item.title.ifBlank{item.type},fontSize=11.7.sp,fontWeight=FontWeight.Black,color=RouterInk);Text(item.result,fontSize=10.2.sp,fontWeight=FontWeight.SemiBold,color=RouterInk);if(item.port.isNotBlank())Text("问题接口：${item.port}",fontSize=9.7.sp,color=RouterRed);if(item.tips.isNotBlank())Text(item.tips,fontSize=9.5.sp,color=RouterMuted);if(item.advise.isNotBlank())Text(item.advise,fontSize=9.5.sp,color=RouterMuted,lineHeight=13.sp)}}}}
        }
    }
}

@Composable
fun RouterLoginSettingsScreen(prefs:AppPrefs,onBack:()->Unit){
    val api=remember(prefs.hub,prefs.token,prefs.hubDns){RouterControlApi(prefs)}
    val scope=rememberCoroutineScope()
    var config by remember{mutableStateOf(RouterLoginConfig())}
    var address by remember{mutableStateOf("")}
    var password by remember{mutableStateOf("")}
    var showPassword by remember{mutableStateOf(false)}
    var seconds by remember{mutableStateOf("3600")}
    var saving by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}

    LaunchedEffect(Unit){
        runCatching{api.routerConfig(includeSecret=true,probe=true)}.onSuccess{config=it;address=it.address;password=it.password;seconds=it.sessionSeconds.toString();message=""}.onFailure{message=it.message.orEmpty()}
        while(true){
            delay(15_000)
            runCatching{api.routerConfig(includeSecret=false,probe=true)}.onSuccess{latest->config=latest.copy(password=password)}.onFailure{message=it.message.orEmpty()}
        }
    }

    RouterFormPage("路由器连接","Hub 持续保活 · 管理密码由你输入",onBack){
        val connected=config.connected||config.sessionActive
        PremiumCard(if(connected)RouterGreen else if(config.passwordConfigured)RouterAmber else RouterBlue){
            Row(verticalAlignment=Alignment.CenterVertically){
                RouterGlyphIcon(RouterGlyph.Connection,if(connected)RouterGreen else RouterBlue,Modifier.size(30.dp));Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)){
                    Text(if(connected)"路由器已连接" else if(config.passwordConfigured)"路由器连接异常" else "等待配置",fontSize=12.5.sp,fontWeight=FontWeight.Black,color=RouterInk)
                    Text(buildString{append(config.serialNumber.ifBlank{config.address.ifBlank{"输入管理地址和密码"}});if(config.sessionRemainingSeconds>0)append(" · 剩余 ${config.sessionRemainingSeconds}s")},fontSize=9.6.sp,color=RouterMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Surface(shape=RoundedCornerShape(99.dp),color=(if(connected)RouterGreen else RouterMuted).copy(alpha=.09f)){Row(Modifier.padding(horizontal=8.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(6.dp).background(if(connected)RouterGreen else RouterMuted,CircleShape));Spacer(Modifier.width(5.dp));Text(if(connected)"在线" else "离线",fontSize=9.2.sp,fontWeight=FontWeight.Black,color=if(connected)RouterGreen else RouterMuted)}}
            }
            if(config.lastError.isNotBlank())Text(config.lastError,fontSize=9.5.sp,color=RouterRed,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        CompactField("管理地址",address,"192.168.5.1",keyboardType=KeyboardType.Uri){address=it.take(160)}
        CompactPasswordField("管理密码",password,"请输入路由器管理密码",showPassword,{showPassword=!showPassword}){password=it.take(128)}
        CompactField("会话超时（600-7200秒）",seconds,"3600",keyboardType=KeyboardType.Number){seconds=it.filter(Char::isDigit).take(4)}
        if(message.isNotBlank())CompactMessage(message,if(config.connected)RouterGreen else RouterRed)
        Button(onClick={scope.launch{saving=true;runCatching{api.saveRouterConfig(address,password,seconds.toIntOrNull()?:3600)}.onSuccess{config=it.copy(password=password);message="连接成功 · Hub 已开始保活"}.onFailure{message=it.message.orEmpty()};saving=false}},enabled=!saving,modifier=Modifier.fillMaxWidth().height(42.dp),shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterBlue)){if(saving)CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp,color=Color.White)else Text("保存并测试连接",fontSize=11.5.sp,fontWeight=FontWeight.Black)}
    }
}

@Composable
private fun CompactTopBar(title:String,onBack:()->Unit,subtitle:String=""){
    Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=7.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick=onBack,modifier=Modifier.size(34.dp)){Icon(Icons.Rounded.ArrowBack,null,Modifier.size(20.dp),tint=RouterInk)}
        Column(Modifier.weight(1f)){Text(title,fontSize=15.5.sp,lineHeight=17.sp,fontWeight=FontWeight.Black,color=RouterInk,maxLines=1);if(subtitle.isNotBlank())Text(subtitle,fontSize=9.2.sp,lineHeight=10.5.sp,fontWeight=FontWeight.SemiBold,color=RouterMuted,maxLines=1,overflow=TextOverflow.Ellipsis)}
    }
}

@Composable
private fun RouterFormPage(title:String,subtitle:String,onBack:()->Unit,content:@Composable ColumnScope.()->Unit){
    BackHandler(onBack=onBack)
    Scaffold(containerColor=RouterPage,topBar={Surface(color=Color.White,shadowElevation=1.dp){CompactTopBar(title,onBack,subtitle)}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=13.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}

@Composable
private fun CompactToolbar(title:String,subtitle:String,loading:Boolean,onRefresh:(()->Unit)?,onAdd:(()->Unit)?){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text(title,fontSize=12.7.sp,fontWeight=FontWeight.Black,color=RouterInk);Text(subtitle,fontSize=9.3.sp,fontWeight=FontWeight.SemiBold,color=RouterMuted)}
        if(onRefresh!=null)IconButton(onClick=onRefresh,modifier=Modifier.size(33.dp)){if(loading)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)else Icon(Icons.Rounded.Refresh,null,Modifier.size(17.dp),tint=RouterBlue)}
        if(onAdd!=null)Surface(onClick=onAdd,shape=CircleShape,color=RouterBlue,modifier=Modifier.size(34.dp),shadowElevation=2.dp){Box(contentAlignment=Alignment.Center){Icon(Icons.Rounded.Add,null,tint=Color.White,modifier=Modifier.size(18.dp))}}
    }
}

@Composable
private fun PremiumCard(accent:Color,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color.White,border=androidx.compose.foundation.BorderStroke(1.dp,accent.copy(alpha=.10f)),shadowElevation=1.5.dp){
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(accent.copy(alpha=.038f),Color.Transparent))).padding(horizontal=11.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(5.dp),content=content)
    }
}

@Composable
private fun CompactField(label:String,value:String,hint:String,modifier:Modifier=Modifier,keyboardType:KeyboardType=KeyboardType.Text,onChange:(String)->Unit){
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(label,fontSize=9.7.sp,fontWeight=FontWeight.Bold,color=RouterMuted)
        Surface(Modifier.fillMaxWidth().height(44.dp),shape=RoundedCornerShape(13.dp),color=RouterField,border=androidx.compose.foundation.BorderStroke(1.dp,RouterBorder)){
            BasicTextField(
                value=value,
                onValueChange=onChange,
                modifier=Modifier.fillMaxSize(),
                singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=keyboardType),
                textStyle=TextStyle(fontSize=12.2.sp,lineHeight=14.sp,fontWeight=FontWeight.SemiBold,color=RouterInk),
                cursorBrush=SolidColor(RouterBlue),
                decorationBox={inner->Row(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart){if(value.isEmpty())Text(hint,fontSize=11.2.sp,lineHeight=13.sp,fontWeight=FontWeight.SemiBold,color=RouterMuted.copy(alpha=.78f),maxLines=1,overflow=TextOverflow.Clip);inner()}}}
            )
        }
    }
}

@Composable
private fun CompactPasswordField(label:String,value:String,hint:String,visible:Boolean,onToggle:()->Unit,onChange:(String)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(label,fontSize=9.7.sp,fontWeight=FontWeight.Bold,color=RouterMuted)
        Surface(Modifier.fillMaxWidth().height(44.dp),shape=RoundedCornerShape(13.dp),color=RouterField,border=androidx.compose.foundation.BorderStroke(1.dp,RouterBorder)){
            BasicTextField(
                value=value,
                onValueChange=onChange,
                modifier=Modifier.fillMaxSize(),
                singleLine=true,
                visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),
                textStyle=TextStyle(fontSize=12.2.sp,lineHeight=14.sp,fontWeight=FontWeight.SemiBold,color=RouterInk),
                cursorBrush=SolidColor(RouterBlue),
                decorationBox={inner->Row(Modifier.fillMaxSize().padding(start=12.dp,end=5.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart){if(value.isEmpty())Text(hint,fontSize=11.2.sp,lineHeight=13.sp,fontWeight=FontWeight.SemiBold,color=RouterMuted.copy(alpha=.78f),maxLines=1);inner()};IconButton(onClick=onToggle,modifier=Modifier.size(34.dp)){Icon(if(visible)Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,null,Modifier.size(18.dp),tint=RouterMuted)}}}
            )
        }
    }
}

@Composable
private fun CompactChoice(label:String,value:String,options:List<String>,modifier:Modifier=Modifier,onPick:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
        if(label.isNotBlank())Text(label,fontSize=9.7.sp,fontWeight=FontWeight.Bold,color=RouterMuted)
        Box{
            Surface(Modifier.fillMaxWidth().height(44.dp).clickable{expanded=true},shape=RoundedCornerShape(13.dp),color=RouterField,border=androidx.compose.foundation.BorderStroke(1.dp,RouterBorder)){
                Row(Modifier.fillMaxSize().padding(horizontal=11.dp),verticalAlignment=Alignment.CenterVertically){Text(value,Modifier.weight(1f),fontSize=11.5.sp,lineHeight=13.sp,fontWeight=FontWeight.Bold,color=RouterInk,maxLines=1,overflow=TextOverflow.Clip);Icon(Icons.Rounded.KeyboardArrowDown,null,Modifier.size(17.dp),tint=RouterMuted)}
            }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},shape=RoundedCornerShape(13.dp),containerColor=Color.White){options.forEach{option->DropdownMenuItem(text={Text(option,fontSize=11.5.sp,fontWeight=if(option==value)FontWeight.Black else FontWeight.SemiBold)},leadingIcon=if(option==value)({Icon(Icons.Rounded.Check,null,Modifier.size(15.dp),tint=RouterBlue)})else null,onClick={expanded=false;onPick(option)})}}
        }
    }
}

@Composable
private fun CompactSegment(text:String,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit){
    Surface(onClick=onClick,modifier=modifier.height(33.dp),shape=RoundedCornerShape(11.dp),color=if(selected)RouterBlue else RouterField,border=androidx.compose.foundation.BorderStroke(1.dp,if(selected)RouterBlue else RouterBorder)){Box(contentAlignment=Alignment.Center){Text(text,fontSize=10.5.sp,fontWeight=FontWeight.Black,color=if(selected)Color.White else RouterMuted)}}
}

@Composable
private fun TinyBadge(text:String,color:Color){Surface(shape=RoundedCornerShape(99.dp),color=color.copy(alpha=.09f)){Text(text,Modifier.padding(horizontal=6.dp,vertical=2.dp),fontSize=8.5.sp,fontWeight=FontWeight.Black,color=color,maxLines=1)}}

@Composable
private fun CompactMessage(text:String,color:Color){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp),color=color.copy(alpha=.065f),border=androidx.compose.foundation.BorderStroke(1.dp,color.copy(alpha=.13f))){Text(text,Modifier.padding(horizontal=10.dp,vertical=7.dp),fontSize=10.sp,fontWeight=FontWeight.SemiBold,color=color)}}

@Composable
private fun CompactEmpty(title:String,subtitle:String,glyph:RouterGlyph,onAdd:(()->Unit)?){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color.White,border=androidx.compose.foundation.BorderStroke(1.dp,RouterBorder)){Column(Modifier.fillMaxWidth().padding(vertical=18.dp,horizontal=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){RouterGlyphIcon(glyph,RouterBlue,Modifier.size(34.dp));Text(title,fontSize=11.8.sp,fontWeight=FontWeight.Black,color=RouterInk);Text(subtitle,fontSize=9.5.sp,color=RouterMuted);if(onAdd!=null)TextButton(onClick=onAdd,contentPadding=PaddingValues(horizontal=10.dp,vertical=2.dp)){Text("立即添加",fontSize=10.5.sp,fontWeight=FontWeight.Black)}}}}

@Composable
private fun LoadingBlock(){Box(Modifier.fillMaxWidth().height(130.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.4.dp)}}

@Composable
private fun ConfirmDialog(title:String,text:String,confirmText:String,onConfirm:()->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text(title,fontSize=15.sp,fontWeight=FontWeight.Black)},text={Text(text,fontSize=11.8.sp)},confirmButton={TextButton(onClick=onConfirm){Text(confirmText,color=RouterRed,fontWeight=FontWeight.Black)}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}},shape=RoundedCornerShape(17.dp))}

private fun formatBytesCompact(bytes:Long):String=when{bytes<1024->"${bytes}B";bytes<1024*1024->String.format(Locale.US,"%.1fKB",bytes/1024.0);else->String.format(Locale.US,"%.1fMB",bytes/1024.0/1024.0)}
