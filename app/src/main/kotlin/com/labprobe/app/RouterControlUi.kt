package com.labprobe.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

@Composable
fun RouterFeatureRail(
    firewallEnabled: Int,
    ddnsHealthy: Int,
    mappingCount: Int,
    upnpEnabled: Boolean,
    diagnosticErrors: Int,
    onMapping: () -> Unit,
    onDdns: () -> Unit,
    onFirewall: () -> Unit,
    onDiagnostic: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("路由器功能", fontSize = 15.sp, fontWeight = FontWeight.Black, color = RouterInk)
            Spacer(Modifier.weight(1f))
            Text("左右滑动", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            RouterFeatureCard("映射与UPnP", "$mappingCount 条 · ${if (upnpEnabled) "UPnP开" else "UPnP关"}", RouterBlue, RouterGlyph.Mapping, onMapping)
            RouterFeatureCard("DDNS", "$ddnsHealthy 条正常", RouterCyan, RouterGlyph.Ddns, onDdns)
            RouterFeatureCard("防火墙", "$firewallEnabled 条启用", RouterGreen, RouterGlyph.Firewall, onFirewall)
            RouterFeatureCard("网络自检", if (diagnosticErrors == 0) "状态正常" else "$diagnosticErrors 项异常", if (diagnosticErrors == 0) RouterBlue else RouterAmber, RouterGlyph.Diagnostic, onDiagnostic)
        }
    }
}

private enum class RouterGlyph { Mapping, Ddns, Firewall, Diagnostic, Upnp, Port }

@Composable
private fun RouterFeatureCard(title: String, status: String, accent: Color, glyph: RouterGlyph, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(126.dp).height(82.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .12f)),
        shadowElevation = 3.dp
    ) {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = .075f), Color.White, Color.White))).padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                RouterGlyphIcon(glyph, accent, Modifier.size(29.dp))
                Column {
                    Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1)
                    Text(status, fontSize = 9.8.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(accent.copy(alpha = .85f), CircleShape))
        }
    }
}

@Composable
private fun RouterGlyphIcon(glyph: RouterGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * .065f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            RouterGlyph.Mapping, RouterGlyph.Port -> {
                drawRoundRect(color.copy(alpha = .14f), Offset(w*.02f,h*.18f), Size(w*.34f,h*.52f), CornerRadius(w*.10f,w*.10f))
                drawRoundRect(color.copy(alpha = .14f), Offset(w*.64f,h*.30f), Size(w*.34f,h*.52f), CornerRadius(w*.10f,w*.10f))
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
                drawPath(shield,color.copy(alpha=.12f)); drawPath(shield,color,style=stroke)
                drawLine(color,Offset(w*.34f,h*.43f),Offset(w*.66f,h*.43f),stroke.width,StrokeCap.Round)
                drawLine(color,Offset(w*.34f,h*.60f),Offset(w*.66f,h*.60f),stroke.width,StrokeCap.Round)
            }
            RouterGlyph.Diagnostic -> {
                drawCircle(color.copy(alpha=.12f),w*.42f,Offset(w*.50f,h*.50f))
                val p=Path().apply{moveTo(w*.11f,h*.54f);lineTo(w*.31f,h*.54f);lineTo(w*.40f,h*.31f);lineTo(w*.53f,h*.73f);lineTo(w*.63f,h*.45f);lineTo(w*.88f,h*.45f)}
                drawPath(p,color,style=stroke)
            }
            RouterGlyph.Upnp -> {
                drawRoundRect(color.copy(alpha=.12f),Offset(w*.18f,h*.47f),Size(w*.64f,h*.34f),CornerRadius(w*.12f,w*.12f))
                drawRoundRect(color,Offset(w*.18f,h*.47f),Size(w*.64f,h*.34f),CornerRadius(w*.12f,w*.12f),style=stroke)
                drawCircle(color,w*.035f,Offset(w*.33f,h*.64f));drawCircle(color,w*.035f,Offset(w*.48f,h*.64f))
                drawArc(color,210f,120f,false,Offset(w*.27f,h*.05f),Size(w*.46f,h*.40f),style=stroke)
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
        containerColor = Color(0xFFF6F8FC),
        topBar = {
            Surface(color = Color.White, shadowElevation = 1.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.ArrowBack, null, tint = RouterInk) }
                        Text("映射与 UPnP", fontSize = 18.sp, fontWeight = FontWeight.Black, color = RouterInk)
                    }
                    RouterSuiteTabs(pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
                }
            }
        }
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding), key = { it }) { page ->
            when (page) {
                0 -> PortMappingScreen(prefs = prefs, onBack = onBack)
                1 -> NativePortMappingPage(prefs)
                else -> UpnpPage(prefs)
            }
        }
    }
}

@Composable
private fun RouterSuiteTabs(selected: Int, onSelect: (Int) -> Unit) {
    val titles = listOf("IPv6映射", "端口映射", "UPnP")
    Row(Modifier.fillMaxWidth().height(39.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        titles.forEachIndexed { index, title ->
            Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                Text(title, fontSize = 12.5.sp, fontWeight = if (selected == index) FontWeight.Black else FontWeight.SemiBold, color = if (selected == index) RouterBlue else RouterMuted)
                if (selected == index) Box(Modifier.align(Alignment.BottomCenter).width(34.dp).height(2.5.dp).background(RouterBlue, RoundedCornerShape(99.dp)))
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
        runCatching { api.nativePortMappings(force) }.onSuccess { rules = it; error = "" }.onFailure { error = it.message.orEmpty() }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        item { CompactToolbar("路由器原生映射", "${rules.size} 条 · IPv4 NAT", loading, { scope.launch { refresh(true) } }, { adding = true }) }
        if (error.isNotBlank()) item { CompactMessage(error, RouterRed) }
        if (loading && rules.isEmpty()) item { Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp) } }
        if (!loading && rules.isEmpty()) item { CompactEmpty("暂无端口映射", "创建路由器原生 IPv4 NAT 规则", RouterGlyph.Port) { adding = true } }
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
                        if (editing == null) api.addNativePortMapping(saved) else api.updateNativePortMapping(editing!!.ruleName, saved)
                    }.onSuccess { rules = it; adding = false; editing = null; error = "" }
                        .onFailure { error = it.message.orEmpty() }
                }
            }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除端口映射？", fontSize = 16.sp, fontWeight = FontWeight.Black) },
            text = { Text("删除“${target.ruleName}”后，外部访问会立即中断。", fontSize = 12.5.sp) },
            confirmButton = { TextButton(onClick = { scope.launch { runCatching { api.deleteNativePortMapping(target.ruleName) }.onSuccess { rules = it; deleteTarget = null }.onFailure { error = it.message.orEmpty() } } }) { Text("删除", color = RouterRed, fontWeight = FontWeight.Black) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
private fun NativePortRuleCard(rule: NativePortMapRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(rule.ruleName) { mutableStateOf(false) }
    PremiumCard(accent = RouterBlue) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RouterGlyphIcon(RouterGlyph.Port, RouterBlue, Modifier.size(31.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.ruleName, Modifier.weight(1f), fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TinyBadge(rule.proto.uppercase(), RouterBlue)
                }
                Text("WAN ${rule.srcPort}  →  ${rule.destIp}:${rule.destPort}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (rule.srcIp.isBlank()) "来源：全部公网地址" else "来源：${rule.srcIp}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted, maxLines = 1)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.MoreVert, null, Modifier.size(18.dp), tint = RouterMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(15.dp), containerColor = Color.White) {
                    DropdownMenuItem(text = { Text("编辑", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", fontSize = 12.sp, color = RouterRed) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp), tint = RouterRed) }, onClick = { menu = false; onDelete() })
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
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Color.White,
        dragHandle = { Box(Modifier.padding(top = 9.dp, bottom = 5.dp).width(34.dp).height(4.dp).background(RouterBorder, RoundedCornerShape(99.dp))) }
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (initial.ruleName.isBlank()) "新增端口映射" else "编辑端口映射", fontSize = 17.sp, fontWeight = FontWeight.Black, color = RouterInk)
                    Text("路由器原生 IPv4 NAT", fontSize = 10.5.sp, color = RouterMuted)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, null) }
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
            if (error.isNotBlank()) Text(error, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = RouterRed)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp)) { Text("取消", fontSize = 12.sp, fontWeight = FontWeight.Black) }
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
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RouterBlue)
                ) { Text("保存并同步", fontSize = 12.sp, fontWeight = FontWeight.Black) }
            }
            Spacer(Modifier.height(8.dp))
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { CompactToolbar("UPnP 服务", "${state.mappings.size} 条动态映射", loading, { scope.launch { refresh(true) } }, null) }
        if (error.isNotBlank()) item { CompactMessage(error, RouterRed) }
        item {
            PremiumCard(accent = RouterCyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouterGlyphIcon(RouterGlyph.Upnp, RouterCyan, Modifier.size(35.dp))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自动端口发现", fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = RouterInk)
                        Text("默认线路 ${state.wan} · ${state.mappings.size} 条活动映射", fontSize = 10.2.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                    }
                    if (saving) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp) else Switch(
                        checked = state.enabled,
                        onCheckedChange = { next -> if (!next) confirmDisable = true else scope.launch { saving = true; runCatching { api.setUpnp(true, state.wan) }.onSuccess { state = it }.onFailure { error = it.message.orEmpty() }; saving = false } },
                        colors = SwitchDefaults.colors(checkedTrackColor = RouterCyan)
                    )
                }
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("默认线路", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
                    Spacer(Modifier.weight(1f))
                    CompactChoice("", state.wan, listOf("AUTO", "WAN"), Modifier.width(126.dp)) { wan -> scope.launch { saving = true; runCatching { api.setUpnp(state.enabled, wan) }.onSuccess { state = it }.onFailure { error = it.message.orEmpty() }; saving = false } }
                }
            }
        }
        item { Text("动态映射", fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = RouterInk, modifier = Modifier.padding(top = 4.dp, start = 2.dp)) }
        if (!loading && state.mappings.isEmpty()) item { CompactEmpty("暂无动态映射", "内网设备申请 UPnP 端口后会显示在这里", RouterGlyph.Upnp, null) }
        items(state.mappings, key = { "${it.clientIp}-${it.protocol}-${it.externalPort}" }) { row ->
            PremiumCard(accent = if (row.protocol == "TCP") RouterBlue else RouterCyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.name, Modifier.weight(1f), fontSize = 12.8.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TinyBadge(row.protocol, if (row.protocol == "TCP") RouterBlue else RouterCyan)
                        }
                        Text(row.clientIp, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = RouterMuted)
                        Text("内部 ${row.internalPort}  →  外部 ${row.externalPort}", fontSize = 11.2.sp, fontWeight = FontWeight.Bold, color = RouterInk)
                    }
                }
            }
        }
    }
    if (confirmDisable) AlertDialog(
        onDismissRequest = { confirmDisable = false },
        title = { Text("关闭 UPnP？", fontSize = 16.sp, fontWeight = FontWeight.Black) },
        text = { Text("部分游戏、下载和远程访问可能受到影响。", fontSize = 12.5.sp) },
        confirmButton = { TextButton(onClick = { confirmDisable = false; scope.launch { saving = true; runCatching { api.setUpnp(false, state.wan) }.onSuccess { state = it }.onFailure { error = it.message.orEmpty() }; saving = false } }) { Text("关闭", color = RouterRed, fontWeight = FontWeight.Black) } },
        dismissButton = { TextButton(onClick = { confirmDisable = false }) { Text("取消") } },
        shape = RoundedCornerShape(18.dp)
    )
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
            onSave = { rule -> scope.launch { runCatching { if (editing == null) api.addFirewallRule(rule) else api.updateFirewallRule(rule) }.onSuccess { state = it; adding = false; editing = null }.onFailure { error = it.message.orEmpty() } } }
        )
        return
    }

    DetailShell("防火墙", "${state.rules.count { it.enabled }} 条启用 · ${state.rules.size}/${state.maxRules}", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("forward" to "转发", "inbound" to "入站", "outbound" to "出站").forEach { (value, label) -> CompactSegment(label, direction == value, Modifier.weight(1f)) { direction = value } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${visible.size} 条规则", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { refresh(true) } }, modifier = Modifier.size(36.dp)) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null, Modifier.size(19.dp), tint = RouterBlue) }
            Surface(onClick = { adding = true }, shape = CircleShape, color = RouterBlue, modifier = Modifier.size(37.dp), shadowElevation = 4.dp) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(20.dp)) } }
        }
        if (error.isNotBlank()) CompactMessage(error, RouterRed)
        if (!loading && visible.isEmpty()) CompactEmpty("暂无${when(direction){"inbound"->"入站";"outbound"->"出站";else->"转发"}}规则", "点右上角添加", RouterGlyph.Firewall) { adding = true }
        visible.forEach { rule ->
            FirewallRuleCard(
                rule = rule,
                onOpen = { editing = rule },
                onToggle = { scope.launch { runCatching { api.setFirewallEnabled(rule.uuid, !rule.enabled) }.onSuccess { state = it }.onFailure { error = it.message.orEmpty() } } },
                onDelete = { deleteTarget = rule }
            )
        }
    }
    deleteTarget?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除防火墙规则？", fontSize = 16.sp, fontWeight = FontWeight.Black) },
            text = { Text("删除“${rule.ruleName}”可能立即影响远程访问。", fontSize = 12.5.sp) },
            confirmButton = { TextButton(onClick = { scope.launch { runCatching { api.deleteFirewallRule(rule.uuid) }.onSuccess { state = it; deleteTarget = null }.onFailure { error = it.message.orEmpty() } } }) { Text("删除", color = RouterRed, fontWeight = FontWeight.Black) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
private fun FirewallRuleCard(rule: FirewallRule, onOpen: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val accent = if (rule.target == "ACCEPT") RouterGreen else RouterRed
    PremiumCard(accent = accent, modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (rule.enabled) accent else RouterMuted.copy(alpha=.45f), CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.ruleName, Modifier.weight(1f), fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TinyBadge(if (rule.target == "ACCEPT") "允许" else "丢弃", accent)
                }
                val port = if (rule.proto in setOf("tcp", "udp")) rule.destPort.ifBlank { "任意端口" } else "不匹配端口"
                Text("${rule.ipVersion.uppercase()} · ${rule.proto.uppercase()} · $port", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = RouterMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val targetText = rule.destIP.ifBlank { rule.ipv6SuffixDest.ifBlank { "任意目标" } }
                Text("${rule.inIface.ifBlank { "本机" }.uppercase()} → ${rule.outIface.ifBlank { "本机" }.uppercase()} · $targetText", fontSize = 10.4.sp, fontWeight = FontWeight.SemiBold, color = RouterInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("命中 ${rule.stats.packets} 次 · ${formatBytesCompact(rule.stats.bytes)}", fontSize = 9.6.sp, color = RouterMuted)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.scale(.82f), colors = SwitchDefaults.colors(checkedTrackColor = accent))
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(16.dp), tint = RouterMuted) }
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
    DetailShell(if (initial.uuid.isBlank()) "新增防火墙规则" else "编辑防火墙规则", "精确匹配 · 保存后同步路由器", onBack) {
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
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
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
            Text("保存后立即启用", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
            Spacer(Modifier.weight(1f))
            Switch(checked = rule.enabled, onCheckedChange = { rule = rule.copy(enabled = it) }, colors = SwitchDefaults.colors(checkedTrackColor = RouterBlue))
        }
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = RouterRed, fontWeight = FontWeight.SemiBold)
        Button(onClick = { error = if (rule.ruleName.isBlank()) "请填写规则名称" else ""; if (error.isBlank()) onSave(rule) }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = RouterBlue)) { Text("保存并同步路由器", fontSize = 12.sp, fontWeight = FontWeight.Black) }
    }
}

@Composable
fun RouterDdnsScreen(prefs: AppPrefs, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    DetailShell("DDNS 与证书", "动态域名 · 到期提醒", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactSegment("DDNS记录", tab == 0, Modifier.weight(1f)) { tab = 0 }
            CompactSegment("证书监控", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
        if (tab == 0) DdnsRecordsSection(prefs) else CertificateExpirySection(prefs)
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
    suspend fun refresh(force:Boolean=false){ if(!force)loading=true;runCatching{api.ddns(force)}.onSuccess{rows=it;error=""}.onFailure{error=it.message.orEmpty()};loading=false }
    LaunchedEffect(Unit){refresh()}
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${rows.count { it.enabled }} 条启用 · ${rows.count { it.status.contains("error",true) || it.status.contains("fail",true) }} 条异常", fontSize = 10.8.sp, fontWeight = FontWeight.Bold, color = RouterMuted)
        Spacer(Modifier.weight(1f))
        IconButton(onClick={scope.launch{refresh(true)}},modifier=Modifier.size(35.dp)){if(loading)CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp)else Icon(Icons.Rounded.Refresh,null,Modifier.size(18.dp),tint=RouterBlue)}
        Surface(onClick={adding=true},shape=CircleShape,color=RouterBlue,modifier=Modifier.size(36.dp)){Box(contentAlignment=Alignment.Center){Icon(Icons.Rounded.Add,null,tint=Color.White,modifier=Modifier.size(19.dp))}}
    }
    if(error.isNotBlank())CompactMessage(error,RouterRed)
    if(!loading&&rows.isEmpty())CompactEmpty("暂无DDNS记录","新增后由路由器原生服务更新",RouterGlyph.Ddns){adding=true}
    rows.forEach{record->DdnsCard(record,onEdit={editing=record},onToggle={scope.launch{runCatching{api.updateDdns(record.copy(enabled=!record.enabled),null)}.onSuccess{rows=it}.onFailure{error=it.message.orEmpty()}}})}
    if(adding||editing!=null)DdnsEditorPage(editing?:DdnsRecord(),onBack={adding=false;editing=null}){record,password->scope.launch{runCatching{if(editing==null)api.addDdns(record,password.orEmpty())else api.updateDdns(record,password)}.onSuccess{rows=it;adding=false;editing=null}.onFailure{error=it.message.orEmpty()}}}
}

@Composable
private fun DdnsCard(record:DdnsRecord,onEdit:()->Unit,onToggle:()->Unit){
    val good=record.status.isBlank()||record.status.contains("success",true)||record.status.contains("ok",true)
    val accent=if(good)RouterGreen else RouterRed
    PremiumCard(accent,Modifier.clickable(onClick=onEdit)){
        Row(verticalAlignment=Alignment.CenterVertically){
            RouterGlyphIcon(RouterGlyph.Ddns,accent,Modifier.size(31.dp));Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                Text(record.domain,fontSize=13.sp,fontWeight=FontWeight.Black,color=RouterInk,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${record.provider} · ${if(record.useIpv6)"IPv6" else "IPv4"} · ${record.interfaceName.uppercase()}",fontSize=10.2.sp,fontWeight=FontWeight.Bold,color=RouterMuted)
                Text(record.ip.ifBlank{record.status.ifBlank{"等待更新"}},fontSize=10.5.sp,fontWeight=FontWeight.SemiBold,color=if(record.ip.isBlank())RouterMuted else RouterBlue,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Switch(checked=record.enabled,onCheckedChange={onToggle()},modifier=Modifier.scale(.82f),colors=SwitchDefaults.colors(checkedTrackColor=accent))
        }
    }
}

@Composable
private fun DdnsEditorPage(initial:DdnsRecord,onBack:()->Unit,onSave:(DdnsRecord,String?)->Unit){
    var record by remember(initial.serviceId){mutableStateOf(initial)};var password by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    BackHandler(onBack=onBack)
    Surface(Modifier.fillMaxSize(),color=Color(0xFFF6F8FC)){
        Column(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack,modifier=Modifier.size(38.dp)){Icon(Icons.Rounded.ArrowBack,null)};Column{Text(if(initial.serviceId.isBlank())"新增DDNS" else "编辑DDNS",fontSize=18.sp,fontWeight=FontWeight.Black,color=RouterInk);Text("密钥仅保存在Hub，不返回APP",fontSize=10.3.sp,color=RouterMuted)}}
            CompactChoice("服务商",record.provider,listOf("aliyun.com","dnspod.cn","no-ip.com")){record=record.copy(provider=it)}
            CompactField("域名 / 记录",record.domain,"例如 rj.lab86@shinya.icu"){record=record.copy(domain=it.take(128))}
            CompactField("用户名 / AccessKey",record.username,"AccessKey ID"){record=record.copy(username=it.take(160))}
            OutlinedTextField(value=password,onValueChange={password=it.take(256)},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation(),label={Text(if(initial.passwordConfigured)"密码/Secret（留空保持原值）" else "密码/Secret",fontSize=11.sp)},shape=RoundedCornerShape(14.dp),textStyle=LocalTextStyle.current.copy(fontSize=13.sp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){CompactChoice("接口",record.interfaceName,listOf("wan","wan1"),Modifier.weight(1f)){record=record.copy(interfaceName=it)};CompactChoice("记录类型",if(record.useIpv6)"IPv6" else "IPv4",listOf("IPv6","IPv4"),Modifier.weight(1f)){record=record.copy(useIpv6=it=="IPv6")}}
            Row(verticalAlignment=Alignment.CenterVertically){Text("启用记录",fontSize=11.5.sp,fontWeight=FontWeight.Bold,color=RouterMuted);Spacer(Modifier.weight(1f));Switch(record.enabled,{record=record.copy(enabled=it)},colors=SwitchDefaults.colors(checkedTrackColor=RouterCyan))}
            if(error.isNotBlank())Text(error,fontSize=11.sp,color=RouterRed)
            Spacer(Modifier.weight(1f))
            Button(onClick={error=when{record.domain.isBlank()->"请填写域名";record.username.isBlank()->"请填写账号/AccessKey";initial.serviceId.isBlank()&&password.isBlank()->"请填写密码/Secret";else->""};if(error.isBlank())onSave(record,password.ifBlank{null})},modifier=Modifier.fillMaxWidth().height(44.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterCyan)){Text("保存并同步",fontSize=12.sp,fontWeight=FontWeight.Black)}
        }
    }
}

@Composable
fun RouterDiagnosticScreen(prefs:AppPrefs,onBack:()->Unit){
    val api=remember(prefs.hub,prefs.token,prefs.hubDns){RouterControlApi(prefs)};val scope=rememberCoroutineScope();var result by remember{mutableStateOf(RouterDiagnostic())};var running by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    suspend fun refresh(){runCatching{api.diagnostic()}.onSuccess{result=it;error=""}.onFailure{error=it.message.orEmpty()}}
    LaunchedEffect(Unit){refresh()}
    LaunchedEffect(running){while(running){refresh();if(result.progress.startsWith("100"))running=false else delay(1000)}}
    DetailShell("网络自检","物理接线 · 协商速率 · 网络状态",onBack){
        PremiumCard(if(result.errorCount==0)RouterGreen else RouterAmber){Row(verticalAlignment=Alignment.CenterVertically){RouterGlyphIcon(RouterGlyph.Diagnostic,if(result.errorCount==0)RouterGreen else RouterAmber,Modifier.size(38.dp));Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(if(running)"检测进行中" else if(result.items.isEmpty())"尚未检测" else if(result.errorCount==0)"网络状态正常" else "发现 ${result.errorCount} 项异常",fontSize=14.sp,fontWeight=FontWeight.Black,color=RouterInk);Text("进度 ${result.progress}",fontSize=10.5.sp,color=RouterMuted)};Button(onClick={scope.launch{running=true;runCatching{api.startDiagnostic()}.onFailure{error=it.message.orEmpty();running=false}}},enabled=!running,shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=0.dp),modifier=Modifier.height(38.dp)){Text(if(running)"检测中" else "重新检测",fontSize=11.sp,fontWeight=FontWeight.Black) }}}
        if(error.isNotBlank())CompactMessage(error,RouterRed)
        result.items.forEach{item->val accent=if(item.status=="success")RouterGreen else RouterAmber;PremiumCard(accent){Row(verticalAlignment=Alignment.Top){Icon(if(item.status=="success")Icons.Rounded.CheckCircle else Icons.Rounded.Warning,null,tint=accent,modifier=Modifier.size(20.dp));Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(item.title.ifBlank{item.type},fontSize=12.8.sp,fontWeight=FontWeight.Black,color=RouterInk);Text(item.result,fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=RouterInk);if(item.port.isNotBlank())Text("问题接口：${item.port}",fontSize=10.5.sp,color=RouterRed);if(item.tips.isNotBlank())Text(item.tips,fontSize=10.3.sp,color=RouterMuted);if(item.advise.isNotBlank())Text(item.advise,fontSize=10.3.sp,color=RouterMuted,lineHeight=15.sp)}}}}
    }
}

@Composable
fun RouterLoginSettingsScreen(prefs:AppPrefs,onBack:()->Unit){
    val api=remember(prefs.hub,prefs.token,prefs.hubDns){RouterControlApi(prefs)};val scope=rememberCoroutineScope();var config by remember{mutableStateOf(RouterLoginConfig())};var address by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var seconds by remember{mutableStateOf("3600")};var saving by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    LaunchedEffect(Unit){runCatching{api.routerConfig()}.onSuccess{config=it;address=it.address;seconds=it.sessionSeconds.toString()}.onFailure{message=it.message.orEmpty()}}
    DetailShell("路由器连接","Hub 模拟登录 · 凭据加密保存",onBack){
        PremiumCard(if(config.sessionActive)RouterGreen else RouterBlue){Row(verticalAlignment=Alignment.CenterVertically){RouterGlyphIcon(RouterGlyph.Firewall,if(config.sessionActive)RouterGreen else RouterBlue,Modifier.size(36.dp));Spacer(Modifier.width(10.dp));Column{Text(if(config.sessionActive)"会话已建立" else "等待连接",fontSize=13.5.sp,fontWeight=FontWeight.Black,color=RouterInk);Text(config.serialNumber.ifBlank{"保存后自动测试登录"},fontSize=10.2.sp,color=RouterMuted)}}}
        CompactField("管理地址",address,"192.168.5.1",keyboardType=KeyboardType.Uri){address=it.take(160)}
        OutlinedTextField(value=password,onValueChange={password=it.take(128)},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation(),label={Text(if(config.passwordConfigured)"管理密码（留空保持原值）" else "管理密码",fontSize=11.sp)},shape=RoundedCornerShape(14.dp),textStyle=LocalTextStyle.current.copy(fontSize=13.sp))
        CompactField("会话超时（600-7200秒）",seconds,"3600",keyboardType=KeyboardType.Number){seconds=it.filter(Char::isDigit).take(4)}
        if(message.isNotBlank())CompactMessage(message,if(config.sessionActive)RouterGreen else RouterRed)
        Button(onClick={scope.launch{saving=true;runCatching{api.saveRouterConfig(address,password.ifBlank{null},seconds.toIntOrNull()?:3600)}.onSuccess{config=it;message="连接成功 · 会话 ${it.sessionSeconds} 秒";password=""}.onFailure{message=it.message.orEmpty()};saving=false}},enabled=!saving,modifier=Modifier.fillMaxWidth().height(44.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=RouterBlue)){if(saving)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=Color.White)else Text("保存并测试连接",fontSize=12.sp,fontWeight=FontWeight.Black)}
    }
}

@Composable
private fun CompactToolbar(title:String,subtitle:String,loading:Boolean,onRefresh:(()->Unit)?,onAdd:(()->Unit)?){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,fontSize=14.sp,fontWeight=FontWeight.Black,color=RouterInk);Text(subtitle,fontSize=10.sp,fontWeight=FontWeight.SemiBold,color=RouterMuted)};if(onRefresh!=null)IconButton(onClick=onRefresh,modifier=Modifier.size(35.dp)){if(loading)CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp)else Icon(Icons.Rounded.Refresh,null,Modifier.size(18.dp),tint=RouterBlue)};if(onAdd!=null)Surface(onClick=onAdd,shape=CircleShape,color=RouterBlue,modifier=Modifier.size(36.dp),shadowElevation=4.dp){Box(contentAlignment=Alignment.Center){Icon(Icons.Rounded.Add,null,tint=Color.White,modifier=Modifier.size(19.dp))}}}}

@Composable
private fun PremiumCard(accent:Color,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=Color.White,border=androidx.compose.foundation.BorderStroke(1.dp,accent.copy(alpha=.11f)),shadowElevation=2.dp){Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(accent.copy(alpha=.048f),Color.Transparent))).padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp),content=content)}}

@Composable
private fun CompactField(label:String,value:String,hint:String,modifier:Modifier=Modifier,keyboardType:KeyboardType=KeyboardType.Text,onChange:(String)->Unit){Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){Text(label,fontSize=10.2.sp,fontWeight=FontWeight.Bold,color=RouterMuted);OutlinedTextField(value=value,onValueChange=onChange,modifier=Modifier.fillMaxWidth().height(48.dp),singleLine=true,placeholder={Text(hint,fontSize=11.5.sp)},keyboardOptions=KeyboardOptions(keyboardType=keyboardType),shape=RoundedCornerShape(14.dp),textStyle=LocalTextStyle.current.copy(fontSize=12.8.sp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=RouterBorder,focusedBorderColor=RouterBlue,unfocusedContainerColor=RouterField,focusedContainerColor=Color.White))}}

@Composable
private fun CompactChoice(label:String,value:String,options:List<String>,modifier:Modifier=Modifier,onPick:(String)->Unit){var expanded by remember{mutableStateOf(false)};Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){if(label.isNotBlank())Text(label,fontSize=10.2.sp,fontWeight=FontWeight.Bold,color=RouterMuted);Box{Surface(Modifier.fillMaxWidth().height(44.dp).clickable{expanded=true},shape=RoundedCornerShape(14.dp),color=RouterField,border=androidx.compose.foundation.BorderStroke(1.dp,RouterBorder)){Row(Modifier.fillMaxSize().padding(horizontal=11.dp),verticalAlignment=Alignment.CenterVertically){Text(value,Modifier.weight(1f),fontSize=11.8.sp,fontWeight=FontWeight.Bold,color=RouterInk,maxLines=1,overflow=TextOverflow.Ellipsis);Icon(Icons.Rounded.KeyboardArrowDown,null,Modifier.size(17.dp),tint=RouterMuted)}};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},shape=RoundedCornerShape(14.dp),containerColor=Color.White){options.forEach{option->DropdownMenuItem(text={Text(option,fontSize=12.sp,fontWeight=if(option==value)FontWeight.Black else FontWeight.SemiBold)},leadingIcon=if(option==value)({ Icon(Icons.Rounded.Check,null,Modifier.size(16.dp),tint=RouterBlue) })else null,onClick={expanded=false;onPick(option)})}}}}}

@Composable
private fun CompactSegment(text:String,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit){Surface(onClick=onClick,modifier=modifier.height(35.dp),shape=RoundedCornerShape(12.dp),color=if(selected)RouterBlue else RouterField,border=androidx.compose.foundation.BorderStroke(1.dp,if(selected)RouterBlue else RouterBorder)){Box(contentAlignment=Alignment.Center){Text(text,fontSize=11.2.sp,fontWeight=FontWeight.Black,color=if(selected)Color.White else RouterMuted)}}}

@Composable
private fun TinyBadge(text:String,color:Color){Surface(shape=RoundedCornerShape(99.dp),color=color.copy(alpha=.10f)){Text(text,Modifier.padding(horizontal=7.dp,vertical=3.dp),fontSize=9.sp,fontWeight=FontWeight.Black,color=color,maxLines=1)}}

@Composable
private fun CompactMessage(text:String,color:Color){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=color.copy(alpha=.07f),border=androidx.compose.foundation.BorderStroke(1.dp,color.copy(alpha=.14f))){Text(text,Modifier.padding(horizontal=11.dp,vertical=8.dp),fontSize=10.8.sp,fontWeight=FontWeight.SemiBold,color=color)}}

@Composable
private fun CompactEmpty(title:String,subtitle:String,glyph:RouterGlyph,onAdd:(()->Unit)?){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=Color.White,border=androidx.compose.foundation.BorderStroke(1.dp,RouterBorder)){Column(Modifier.fillMaxWidth().padding(vertical=22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)){RouterGlyphIcon(glyph,RouterBlue,Modifier.size(42.dp));Text(title,fontSize=13.sp,fontWeight=FontWeight.Black,color=RouterInk);Text(subtitle,fontSize=10.3.sp,color=RouterMuted);if(onAdd!=null)TextButton(onClick=onAdd){Text("立即添加",fontSize=11.5.sp,fontWeight=FontWeight.Black)}}}}

private fun formatBytesCompact(bytes:Long):String=when{bytes<1024->"${bytes}B";bytes<1024*1024->String.format(Locale.US,"%.1fKB",bytes/1024.0);else->String.format(Locale.US,"%.1fMB",bytes/1024.0/1024.0)}
