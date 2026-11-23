package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private val SpeedPageInk = LabV2.Ink
private val SpeedPageMuted = LabV2.InkMuted
private val SpeedPageBorder = LabCoreSurface.Border
private val SpeedDown = Color(0xFF0095D8)
private val SpeedUp = Color(0xFF16A34A)
private val SpeedAccent = Color(0xFF20B8B8)

@Composable
fun NetworkSpeedTestScreen(prefs: AppPrefs, onBack: () -> Unit) {
    var channel by remember {
        mutableStateOf(if (isWorkspaceBe50(prefs)) SpeedTestChannel.PHONE else SpeedTestChannel.ROUTER)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && PhoneSpeedTestCoordinator.state.value.running) {
                PhoneSpeedTestCoordinator.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LabV2.PageHorizontal, vertical = LabV2.PageTop),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SpeedTestPageHeader(onBack)
        SpeedTestChannelSelector(channel) { channel = it }
        when (channel) {
            SpeedTestChannel.ROUTER -> RouterSpeedTestContent(prefs)
            SpeedTestChannel.PHONE -> PhoneSpeedTestContent(prefs)
        }
        Spacer(Modifier.height(18.dp))
    }
}

private enum class SpeedTestChannel { ROUTER, PHONE }

@Composable
private fun RouterSpeedTestContent(prefs: AppPrefs) {
    val scope = rememberCoroutineScope()
    val api = remember(prefs.hub, prefs.token) { SpeedTestApi(prefs) }
    var nodes by remember { mutableStateOf<List<SpeedTestNode>>(emptyList()) }
    var ports by remember { mutableStateOf<List<SpeedTestPort>>(emptyList()) }
    var history by remember { mutableStateOf<List<SpeedHistoryRecord>>(emptyList()) }
    var historyExpanded by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf("") }
    var selectedPort by remember { mutableStateOf("wan") }
    var running by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(true) }
    var starting by remember { mutableStateOf(false) }
    var stateChecked by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SpeedProgress?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(prefs.hub, prefs.token) {
        preparing = true
        coroutineScope {
            launch {
                runCatching {
                    ports = api.ports()
                    nodes = api.nodes()
                    selectedNode = nodes.firstOrNull()?.id.orEmpty()
                }.onFailure { error = speedTestErrorText(it.message) }
                ports.firstOrNull { it.testable }?.name?.lowercase()?.takeIf { it.isNotBlank() }?.let {
                    selectedPort = it
                }
                preparing = false
            }
            launch {
                runCatching { api.running() }
                    .onSuccess { running = it }
                    .onFailure { error = speedTestErrorText(it.message) }
                stateChecked = true
            }
            launch { runCatching { api.history() }.onSuccess { history = it } }
            launch { runCatching { api.progress() }.onSuccess { progress = it } }
        }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1000)
            val result = runCatching { api.progress() }.getOrNull() ?: continue
            progress = result
            if (result.finished) {
                runCatching { api.history() }.onSuccess { history = it }
                break
            }
        }
        running = false
    }

    val last = history.firstOrNull()
    val down = progress?.primary?.currentDown ?: last?.downMbps
    val up = progress?.primary?.currentUp ?: last?.upMbps
    val latency = progress?.primary?.latency ?: last?.latency
    val summary = when {
        running -> "测速进行中"
        last?.epoch != null && last.epoch > 0 ->
            "上次测速 · " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(last.epoch * 1000L))
        down != null -> "上次结果"
        else -> "路由器直连宽带测速"
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = LabCoreSurface.CardShape,
            color = Color.White,
            border = BorderStroke(1.dp, SpeedPageBorder),
            shadowElevation = 1.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RouterSpeedSummary(summary)
                DualSpeedGauges(down = down, up = up)
                Text("速度波动曲线", style = LabTypography.SectionTitle.copy(color = SpeedPageInk))
                DualSpeedCharts(progress?.primary)
                SpeedMetrics(latency = latency, jitter = progress?.primary?.jitter, loss = progress?.primary?.loss)
            }
        }

        if (error.isNotBlank()) {
            Text(error, style = LabTypography.Supporting.copy(color = LabV2.Red))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = LabCoreSurface.CardShape,
            color = Color.White,
            border = BorderStroke(1.dp, SpeedPageBorder),
            shadowElevation = 1.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("测速节点", style = LabTypography.SectionTitle.copy(color = SpeedPageInk))
                if (nodes.isEmpty()) {
                    Text(
                        if (preparing) "正在读取节点…" else "暂无可用节点",
                        style = LabTypography.Supporting.copy(color = SpeedPageMuted)
                    )
                } else {
                    SpeedNodeChips(nodes = nodes, selected = selectedNode, enabled = !running) { selectedNode = it }
                }
                Button(
                    onClick = {
                        if (running || preparing || starting || !stateChecked || nodes.isEmpty()) return@Button
                        error = ""
                        starting = true
                        scope.launch {
                            try {
                                api.start(selectedPort, selectedNode)
                                running = true
                            } catch (throwable: Exception) {
                                error = speedTestErrorText(throwable.message)
                            } finally {
                                starting = false
                            }
                        }
                    },
                    enabled = !running && !preparing && !starting && stateChecked && nodes.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpeedAccent,
                        contentColor = Color.White,
                        disabledContainerColor = SpeedAccent.copy(alpha = .32f),
                        disabledContentColor = Color.White.copy(alpha = .88f)
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(
                        when {
                            running -> "测速进行中…"
                            preparing -> "正在读取路由器"
                            starting -> "正在启动测速…"
                            !stateChecked -> "正在核对状态"
                            else -> "开始测速"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = LabCoreSurface.CardShape,
            color = Color.White,
            border = BorderStroke(1.dp, SpeedPageBorder),
            shadowElevation = 1.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { historyExpanded = !historyExpanded }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.History, null, Modifier.size(20.dp), tint = SpeedAccent)
                    Text(
                        "历史测速记录" + if (history.isNotEmpty()) " (${history.size.coerceAtMost(30)})" else "",
                        modifier = Modifier.weight(1f),
                        style = LabTypography.SectionTitle.copy(color = SpeedPageInk)
                    )
                    Icon(
                        if (historyExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null,
                        Modifier.size(20.dp),
                        tint = SpeedPageMuted
                    )
                }
                if (historyExpanded) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 0.dp)) {
                        if (history.isEmpty()) {
                            Text(
                                "暂无历史测速记录",
                                Modifier.padding(vertical = 8.dp),
                                style = LabTypography.Supporting.copy(color = SpeedPageMuted)
                            )
                        } else {
                            history.take(30).forEach { record -> SpeedHistoryLine(record) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SpeedTestPageHeader(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onBack,
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = .72f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .55f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", Modifier.size(20.dp), tint = SpeedPageInk)
            }
        }
        Text(
            "网络测速",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = LabTypography.PageTitle.copy(
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = SpeedPageInk
            )
        )
        Spacer(Modifier.size(38.dp))
    }
}

@Composable
private fun SpeedTestChannelSelector(selected: SpeedTestChannel, onSelect: (SpeedTestChannel) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = .66f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SpeedTestChannelPill("路由器测速", selected == SpeedTestChannel.ROUTER, Modifier.weight(1f)) {
            onSelect(SpeedTestChannel.ROUTER)
        }
        SpeedTestChannelPill("手机测速", selected == SpeedTestChannel.PHONE, Modifier.weight(1f)) {
            onSelect(SpeedTestChannel.PHONE)
        }
    }
}

@Composable
private fun SpeedTestChannelPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = if (active) SpeedAccent.copy(alpha = .10f) else Color.Transparent,
        border = if (active) BorderStroke(1.dp, SpeedAccent.copy(alpha = .35f)) else null
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) SpeedAccent else SpeedPageMuted
        )
    }
}

@Composable
private fun PhoneSpeedTestContent(prefs: AppPrefs) {
    val context = LocalContext.current
    val progress by PhoneSpeedTestCoordinator.state.collectAsState()
    var nodes by remember { mutableStateOf<List<PhoneSpeedTestNode>>(emptyList()) }
    var selectedNodeId by remember { mutableStateOf("") }
    var network by remember { mutableStateOf<PhoneSpeedTestNetworkInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(prefs.phoneSpeedHistory()) }
    var historyExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(prefs.hub, prefs.workspaceId) {
        loading = true
        error = ""
        val info = currentPhoneSpeedTestNetwork(context)
        network = info
        if (info == null) {
            error = "当前没有可用网络"
            loading = false
            return@LaunchedEffect
        }
        runCatching {
            GlobalSpeedProtocol.fetchNodes(info.network, preferIpv6 = info.protocol.contains("IPv6"))
        }.onSuccess { rows ->
            nodes = rows
            selectedNodeId = rows.firstOrNull()?.id.orEmpty()
            if (rows.isEmpty()) error = "暂无可用测速节点"
        }.onFailure {
            error = it.message?.takeIf(String::isNotBlank) ?: "节点读取失败"
        }
        loading = false
    }

    LaunchedEffect(progress.phase) {
        if (progress.phase == "complete" || progress.phase == "cancelled" || progress.phase == "failed") {
            if (progress.phase == "complete") delay(400)
            history = prefs.phoneSpeedHistory()
        }
    }

    val last = history.firstOrNull()
    val running = progress.running
    val down = progress.currentDown ?: progress.down.lastOrNull() ?: last?.downAvg
    val up = progress.currentUp ?: progress.up.lastOrNull() ?: last?.upAvg
    val latency = progress.latency ?: last?.latency
    val jitter = progress.jitter ?: last?.jitter
    val loss = progress.loss ?: last?.loss
    val sampleSet = if (progress.down.isNotEmpty() || progress.up.isNotEmpty()) {
        SpeedSampleSet(
            intf = "phone",
            down = progress.down,
            up = progress.up,
            currentDown = progress.currentDown,
            currentUp = progress.currentUp,
            latency = latency,
            jitter = jitter,
            loss = loss
        )
    } else null
    val node = nodes.firstOrNull { it.id == selectedNodeId }
    val timeout = last?.epoch?.takeIf { it > 0 }?.let {
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it * 1000L))
    }
    val summary = when {
        running -> progress.message.ifBlank { "手机直连测速" }
        last != null && timeout != null -> "上次测速 · $timeout · ${last.network} ${last.protocol}"
        else -> network?.label ?: "手机直连测速"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CardShape,
        color = Color.White,
        border = BorderStroke(1.dp, SpeedPageBorder),
        shadowElevation = 1.dp
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PhoneSpeedSummary(summary = summary, network = network, node = node)
            DualSpeedGauges(down = down, up = up)
            Text("速度波动曲线", style = LabTypography.SectionTitle.copy(color = SpeedPageInk))
            DualSpeedCharts(sampleSet)
            SpeedMetrics(latency = latency, jitter = jitter, loss = loss)
            Text(
                "延迟 / 抖动 / 丢包为手机侧 HTTP 探测，不代表 ICMP。",
                fontSize = 10.sp,
                color = SpeedPageMuted
            )
        }
    }

    if (error.isNotBlank()) {
        Text(error, style = LabTypography.Supporting.copy(color = LabV2.Red))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CardShape,
        color = Color.White,
        border = BorderStroke(1.dp, SpeedPageBorder),
        shadowElevation = 1.dp
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("测速节点", style = LabTypography.SectionTitle.copy(color = SpeedPageInk))
            when {
                loading -> Text("正在读取 GlobalSpeed 节点…", style = LabTypography.Supporting.copy(color = SpeedPageMuted))
                nodes.isEmpty() -> Text("暂无可用节点", style = LabTypography.Supporting.copy(color = SpeedPageMuted))
                else -> SpeedNodeChips(
                    nodes = nodes.map { SpeedTestNode(it.id, it.name) },
                    selected = selectedNodeId,
                    enabled = !running
                ) { selectedNodeId = it }
            }
            Button(
                onClick = {
                    if (running) {
                        PhoneSpeedTestCoordinator.cancel()
                    } else {
                        val current = node
                        val currentNetwork = currentPhoneSpeedTestNetwork(context) ?: network
                        if (current != null && currentNetwork != null && !loading) {
                            network = currentNetwork
                            error = ""
                            if (!PhoneSpeedTestCoordinator.start(context, prefs, current, currentNetwork)) {
                                error = "已有测速在运行"
                            }
                        }
                    }
                },
                enabled = running || (!loading && node != null && network != null),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpeedAccent,
                    contentColor = Color.White,
                    disabledContainerColor = SpeedAccent.copy(alpha = .32f),
                    disabledContentColor = Color.White.copy(alpha = .88f)
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(
                    when {
                        running -> "取消测速"
                        loading -> "正在读取节点"
                        else -> "开始手机测速"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (running) {
                Text(
                    "测速流量由手机直接发往 GlobalSpeed 节点，路由器不参与。",
                    fontSize = 10.sp,
                    color = SpeedPageMuted
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CardShape,
        color = Color.White,
        border = BorderStroke(1.dp, SpeedPageBorder),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { historyExpanded = !historyExpanded }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.History, null, Modifier.size(20.dp), tint = SpeedAccent)
                Text(
                    "历史测速记录" + if (history.isNotEmpty()) " (${history.size.coerceAtMost(30)})" else "",
                    modifier = Modifier.weight(1f),
                    style = LabTypography.SectionTitle.copy(color = SpeedPageInk)
                )
                Icon(
                    if (historyExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    Modifier.size(20.dp),
                    tint = SpeedPageMuted
                )
            }
            if (historyExpanded) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    if (history.isEmpty()) {
                        Text(
                            "暂无手机测速记录",
                            Modifier.padding(vertical = 8.dp),
                            style = LabTypography.Supporting.copy(color = SpeedPageMuted)
                        )
                    } else {
                        history.take(30).forEach { record -> PhoneSpeedHistoryLine(record) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PhoneSpeedSummary(
    summary: String,
    network: PhoneSpeedTestNetworkInfo?,
    node: PhoneSpeedTestNode?
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).background(SpeedAccent.copy(alpha = .10f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Smartphone, null, Modifier.size(21.dp), tint = SpeedAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("手机测速", style = LabTypography.CardTitle.copy(color = SpeedPageInk))
            Text(
                listOfNotNull(summary, node?.name?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = LabTypography.Supporting.copy(color = SpeedPageMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (network != null) {
            Text(
                network.transport,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(SpeedAccent.copy(alpha = .08f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = SpeedAccent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PhoneSpeedHistoryLine(record: PhoneSpeedHistoryEntry) {
    val stamp = if (record.epoch > 0) {
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(record.epoch * 1000L))
    } else {
        "--"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stamp, fontSize = 11.sp, color = SpeedPageMuted, maxLines = 1)
        Text(
            "↓ ${formatSpeedWithUnit(record.downAvg)}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = SpeedDown,
            maxLines = 1
        )
        Text(
            "↑ ${formatSpeedWithUnit(record.upAvg)}",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = SpeedUp,
            maxLines = 1
        )
        Text("时延 ${formatSpeed(record.latency)} ms", fontSize = 11.sp, color = SpeedPageMuted, maxLines = 1)
        Text("${record.network} ${record.protocol} · ${record.nodeName}", fontSize = 11.sp, color = SpeedPageMuted, maxLines = 1)
        if (!record.stable) Text("波动较大", fontSize = 10.sp, color = LabV2.Amber, maxLines = 1)
    }
}

@Composable
private fun RouterSpeedSummary(summary: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).background(SpeedAccent.copy(alpha = .10f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Bolt, null, Modifier.size(21.dp), tint = SpeedAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("路由器测速", style = LabTypography.CardTitle.copy(color = SpeedPageInk))
            Text(summary, style = LabTypography.Supporting.copy(color = SpeedPageMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Image(
            painter = painterResource(R.drawable.router_skeuomorphic_v3),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun DualSpeedGauges(down: Double?, up: Double?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SpeedRingGauge("下载", down, SpeedDown, up = false, modifier = Modifier.weight(1f))
        SpeedRingGauge("上传", up, SpeedUp, up = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SpeedRingGauge(
    label: String,
    value: Double?,
    color: Color,
    up: Boolean,
    modifier: Modifier = Modifier
) {
    val shown = value?.coerceAtLeast(0.0)
    val fraction = shown?.let { gaugePosition(it.toFloat()).coerceIn(0f, 1f) } ?: 0f
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val startAngle = 135f
            val sweep = 270f
            drawArc(
                color = Color(0xFFE9F2F8),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (fraction > 0f) {
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(
                    if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    null,
                    Modifier.size(14.dp),
                    tint = color
                )
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
            }
            Text(
                shown?.let { formatSpeed(it) } ?: "--",
                fontSize = 30.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = SpeedPageInk,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            Text("Mbps", fontSize = 10.sp, color = SpeedPageMuted)
        }
    }
}

@Composable
private fun DualSpeedCharts(sample: SpeedSampleSet?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SpeedTrendCard("下行速率", sample?.down.orEmpty(), SpeedDown, up = false, modifier = Modifier.weight(1f))
        SpeedTrendCard("上行速率", sample?.up.orEmpty(), SpeedUp, up = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SpeedTrendCard(
    title: String,
    values: List<Double>,
    color: Color,
    up: Boolean,
    modifier: Modifier = Modifier
) {
    val peak = values.maxOrNull()
    val maxY = niceSpeedChartMax(peak ?: 0.0)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(
                        if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                        null,
                        Modifier.size(14.dp),
                        tint = color
                    )
                    Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SpeedPageInk, maxLines = 1)
                }
                if (peak != null && peak > 0) {
                    Text(
                        "峰值 ${formatSpeed(peak)} Mbps",
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(color.copy(alpha = .10f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        maxLines = 1
                    )
                }
            }
            if (values.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                    Text("等待测速", fontSize = 11.sp, color = SpeedPageMuted)
                }
            } else {
                Row(Modifier.fillMaxWidth().height(112.dp)) {
                    Column(
                        Modifier.width(30.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(formatAxis(maxY), fontSize = 8.sp, color = SpeedPageMuted)
                        Text(formatAxis(maxY / 2), fontSize = 8.sp, color = SpeedPageMuted)
                        Text("0", fontSize = 8.sp, color = SpeedPageMuted)
                    }
                    Spacer(Modifier.width(5.dp))
                    Canvas(Modifier.weight(1f).fillMaxHeight()) {
                        val top = 3.dp.toPx()
                        val bottom = size.height - 3.dp.toPx()
                        val left = 1.dp.toPx()
                        val right = size.width - 2.dp.toPx()
                        repeat(3) { index ->
                            val y = top + (bottom - top) * index / 2f
                            drawLine(
                                color = Color(0xFFE2E8F0),
                                start = Offset(left, y),
                                end = Offset(right, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                            )
                        }
                        fun point(index: Int, value: Double): Offset {
                            val x = if (values.size <= 1) left else left + (right - left) * index / (values.size - 1).toFloat()
                            val y = bottom - ((value / maxY).coerceIn(0.0, 1.0).toFloat() * (bottom - top))
                            return Offset(x, y)
                        }
                        val points = values.mapIndexed { index, value -> point(index, value) }
                        val line = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (index in 1 until points.size) {
                                val previous = points[index - 1]
                                val current = points[index]
                                val midX = (previous.x + current.x) / 2f
                                quadraticTo(previous.x, previous.y, midX, (previous.y + current.y) / 2f)
                            }
                            if (points.size > 1) lineTo(points.last().x, points.last().y)
                        }
                        val fill = Path().apply {
                            addPath(line)
                            lineTo(points.last().x, bottom)
                            lineTo(points.first().x, bottom)
                            close()
                        }
                        drawPath(
                            fill,
                            brush = Brush.verticalGradient(
                                listOf(color.copy(alpha = .20f), color.copy(alpha = .02f)),
                                startY = top,
                                endY = bottom
                            )
                        )
                        drawPath(
                            line,
                            color = color,
                            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        val peakIndex = values.indices.maxByOrNull { values[it] } ?: 0
                        drawCircle(Color.White, 3.dp.toPx(), points[peakIndex])
                        drawCircle(color, 2.dp.toPx(), points[peakIndex])
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0s", fontSize = 8.sp, color = SpeedPageMuted)
                    Text("${values.size / 2}s", fontSize = 8.sp, color = SpeedPageMuted)
                    Text("${values.size}s", fontSize = 8.sp, color = SpeedPageMuted)
                }
            }
        }
    }
}

@Composable
private fun SpeedMetrics(latency: Double?, jitter: Double?, loss: Double?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeedMetricItem(Icons.Rounded.Schedule, "时延", latency, "ms", Modifier.weight(1f))
            Spacer(Modifier.width(1.dp).height(32.dp).background(Color(0xFFE2E8F0)))
            SpeedMetricItem(Icons.Rounded.ShowChart, "抖动", jitter, "ms", Modifier.weight(1f))
            Spacer(Modifier.width(1.dp).height(32.dp).background(Color(0xFFE2E8F0)))
            SpeedMetricItem(Icons.Rounded.Layers, "丢包", loss, "%", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpeedMetricItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Double?,
    unit: String,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(15.dp), tint = SpeedAccent)
            Text(
                "${formatSpeed(value)} $unit",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = SpeedPageInk,
                maxLines = 1
            )
        }
        Text(label, fontSize = 10.sp, color = SpeedPageMuted)
    }
}

@Composable
private fun SpeedNodeChips(
    nodes: List<SpeedTestNode>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        nodes.forEach { node ->
            val active = node.id == selected
            Surface(
                onClick = { if (enabled) onSelect(node.id) },
                shape = RoundedCornerShape(99.dp),
                color = if (active) SpeedAccent.copy(alpha = .07f) else Color.White,
                border = BorderStroke(1.dp, if (active) SpeedAccent else SpeedPageBorder)
            ) {
                Text(
                    node.name.ifBlank { node.id },
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) SpeedAccent else SpeedPageMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun SpeedHistoryLine(record: SpeedHistoryRecord) {
    val stamp = if (record.epoch > 0) {
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(record.epoch * 1000L))
    } else {
        "--"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stamp, fontSize = 11.sp, color = SpeedPageMuted, maxLines = 1)
        Text("路由器 WAN", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = SpeedAccent, maxLines = 1)
        Text(
            "↓ ${formatSpeedWithUnit(record.downMbps)}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = SpeedDown,
            maxLines = 1
        )
        Text(
            "↑ ${formatSpeedWithUnit(record.upMbps)}",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = SpeedUp,
            maxLines = 1
        )
        Text("时延 ${formatSpeed(record.latency)} ms", fontSize = 11.sp, color = SpeedPageMuted, maxLines = 1)
    }
}

private fun niceSpeedChartMax(value: Double): Double {
    if (!value.isFinite() || value <= 0.0) return 10.0
    val power = 10.0.pow(floor(log10(value)))
    val normalized = value / power
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return (nice * power).coerceAtLeast(10.0)
}

private fun formatAxis(value: Double): String = when {
    value >= 1000 -> String.format(Locale.US, "%.0f", value)
    value >= 100 -> String.format(Locale.US, "%.0f", value)
    value >= 10 -> String.format(Locale.US, "%.0f", value)
    else -> String.format(Locale.US, "%.1f", value)
}