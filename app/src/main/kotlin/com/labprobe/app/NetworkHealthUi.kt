package com.labprobe.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val healthOverviewChecks = listOf(
    DiagnosisCheck.INTERNET, DiagnosisCheck.GATEWAY, DiagnosisCheck.DNS,
    DiagnosisCheck.IPV6, DiagnosisCheck.ROUTER, DiagnosisCheck.RELAY, DiagnosisCheck.WIREGUARD,
)

/**
 * Unified Network Health & Diagnosis Center:
 * Merges health score rules, live network diagnostics, router WebUI, and Agent update/cleanup.
 */
@Composable
fun NetworkHealthScreen(
    prefs: AppPrefs,
    state: AppState,
    progress: DiagnosisProgress,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) = DetailShell("网络健康", "健康得分与诊断 · 路由器运维", onBack, compactHeader = true, unifiedTypography = true) {
    val data = state.status?.optJSONObject("data") ?: state.status
    val nas = data?.optJSONObject("nas")
    val router = data?.optJSONObject("router")
    val nasV6 = safeNasIpv6ForUi(nas, router)
    val vpnOk = buildVpnRowsForHome(data, nasV6, state.events).isNotEmpty()
    val exitOk = cleanApiText(nas?.optString("exitIpv4")).isNotBlank() || cleanApiText(nas?.optString("exitIpv6")).isNotBlank()
    val hubOk = prefs.hub.isNotBlank() && state.hubConnected
    val onlineCount = state.onlineDevices.size
    val badCount = state.events.take(8).count { it.type.contains("ddns", true) || it.type.contains("offline", true) }.coerceAtMost(4)
    val score = networkScore(hubOk, exitOk, vpnOk, onlineCount, state.events)
    val scoreColor = if (score >= 85) LabV2.Green else if (score >= 70) LabV2.Amber else LabV2.Red

    LaunchedEffect(prefs.hub, prefs.token) {
        AgentUpdateCoordinator.bind(prefs)
    }
    val agentUpdateUi by AgentUpdateCoordinator.state.collectAsState()
    val agentInfo = agentUpdateUi.info
    val agentMessage = agentUpdateUi.message
    var cleanupMessage by remember { mutableStateOf("可清理所有 Agent 备份和非必要临时日志") }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var cleanupBusy by remember { mutableStateOf(false) }
    val agentBusy = agentUpdateUi.busy || cleanupBusy
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var showRouterUrlEditor by remember { mutableStateOf(false) }
    var routerLanUrl by remember { mutableStateOf(prefs.routerLanUrl) }
    var routerWanUrl by remember { mutableStateOf(prefs.routerWanUrl) }
    fun normalizedRouterUrl(raw: String): String {
        val value = raw.trim()
        return if (value.isBlank() || value.contains("://")) value else "https://$value"
    }
    fun openRouterUrl() {
        val lan = normalizedRouterUrl(routerLanUrl)
        val wan = normalizedRouterUrl(routerWanUrl)
        val target = if (prefs.favoriteNetworkMode == "wan") wan.ifBlank { lan } else lan.ifBlank { wan }
        if (target.isBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedCheck by remember { mutableStateOf<DiagnosisCheck?>(null) }
    val result = progress.result
    val visibleItems = if (progress.running || progress.cancelled) progress.items else result?.items ?: progress.items
    val status = if (progress.running || progress.cancelled) HealthStatus.UNKNOWN else result?.status ?: HealthStatus.UNKNOWN
    val attentionItems = visibleItems.filter { it.status == HealthStatus.WARNING || it.status == HealthStatus.ERROR }

    if (showRouterUrlEditor) {
        RouterUrlDialog(
            lanUrl = routerLanUrl,
            wanUrl = routerWanUrl,
            onLanChange = { routerLanUrl = it },
            onWanChange = { routerWanUrl = it },
            onDismiss = { showRouterUrlEditor = false },
            onSave = {
                val lan = normalizedRouterUrl(routerLanUrl)
                val wan = normalizedRouterUrl(routerWanUrl)
                routerLanUrl = lan
                routerWanUrl = wan
                prefs.routerLanUrl = lan
                prefs.routerWanUrl = wan
                showRouterUrlEditor = false
            }
        )
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { if (!agentBusy) showCleanupConfirm = false },
            title = { Text("清理 Agent 文件", fontWeight = FontWeight.SemiBold, fontSize = LabTypography.CardTitle.fontSize, color = LabV2.Ink) },
            text = {
                Text(
                    "将删除路由器上的所有 Agent 备份、更新/安装日志和已失效的临时安装文件。不会删除 Agent 配置、运行程序或当前状态数据。",
                    style = LabTypography.Body.copy(color = LabV2.InkMuted)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupConfirm = false
                        scope.launch {
                            cleanupBusy = true
                            cleanupMessage = "正在等待路由器执行清理…"
                            runCatching {
                                val requested = HubApi(prefs).requestAgentCleanup()
                                val commandId = requested.optString("commandId")
                                if (commandId.isBlank()) error("Hub 未返回清理任务编号")
                                var finished: JSONObject? = null
                                var transientPollFailure: Throwable? = null
                                for (attempt in 0 until 45) {
                                    delay(1_000)
                                    val cleanupStatus = try {
                                        HubApi(prefs).getAgentCleanupStatus(commandId)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Throwable) {
                                        if (!isTransientAgentTransportError(failure.message)) throw failure
                                        transientPollFailure = failure
                                        continue
                                    }
                                    when (cleanupStatus.optString("state")) {
                                        "completed" -> {
                                            finished = cleanupStatus
                                            break
                                        }
                                        "failed" -> error(cleanupStatus.optString("message").ifBlank { "路由器清理失败" })
                                    }
                                }
                                finished ?: error(
                                    if (transientPollFailure != null) {
                                        "清理状态连接暂时中断，请稍后重新查看"
                                    } else {
                                        "清理任务等待超时，请稍后重新查看"
                                    }
                                )
                            }.onSuccess {
                                cleanupMessage = agentCleanupSummary(it)
                            }.onFailure {
                                cleanupMessage = "清理失败：${uiMessageZh(it.message).ifBlank { "连接异常，请稍后重试" }}"
                            }
                            cleanupBusy = false
                        }
                    },
                    enabled = !agentBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                    shape = RoundedCornerShape(15.dp)
                ) { Text("确认清理", style = LabTypography.Button) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCleanupConfirm = false }, enabled = !agentBusy, shape = RoundedCornerShape(15.dp)) {
                    Text("取消", style = LabTypography.Button)
                }
            },
            shape = RoundedCornerShape(25.dp),
            containerColor = LAB_POPUP_SURFACE,
            tonalElevation = 0.dp
        )
    }

    // Card 1: 综合健康态势卡片 (Hero Card)
    LabV2Card {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(scoreColor.copy(alpha = 0.08f))
                    .border(2.5.dp, scoreColor.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$score",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                        lineHeight = 30.sp
                    )
                    Text(
                        "健康分",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = LabV2.InkMuted
                    )
                }
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (progress.running) LabV2.Primary else healthColor(status), CircleShape)
                    )
                    Text(
                        when {
                            progress.running -> "正在全面诊断…"
                            progress.cancelled -> "诊断已停止"
                            result != null -> "网络${overallHealthLabel(status)}"
                            else -> "待诊断"
                        },
                        style = LabTypography.CardTitle,
                        color = if (progress.running) LabV2.Primary else healthColor(status),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                Text(
                    when {
                        progress.running -> "正在检查 ${progress.current?.title ?: "各项连接"}，请稍候…"
                        progress.cancelled -> "已保留已完成检查，可随时重新启动诊断。"
                        result != null -> result.conclusion
                        else -> "全面排查网关、DNS、公网出口、IPv6、中继等 9 项网络连接指标。"
                    },
                    style = LabTypography.Body,
                    color = LabV2.InkMuted,
                    lineHeight = 18.sp
                )
                if (result != null && !progress.running && !progress.cancelled) {
                    val completedText = remember(result.completedAt) {
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(result.completedAt))
                    }
                    Text("最近诊断：$completedText", style = LabTypography.Caption, color = LabV2.InkMuted.copy(alpha = 0.8f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (progress.running) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LabV2.Red),
                border = BorderStroke(1.dp, LabV2.Red.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Rounded.Stop, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("停止诊断", style = LabTypography.Button)
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary)
            ) {
                Icon(if (result != null) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (result != null || progress.cancelled) "重新全面诊断" else "开始网络诊断", style = LabTypography.Button)
            }
        }

        if (progress.running) {
            HorizontalDivider(color = LabV2.Border)
            Text("检查进度", style = LabTypography.CardTitle, color = LabV2.Ink)
            DiagnosisCheck.entries.forEach { check ->
                val completed = visibleItems.find { it.check == check }
                HealthProgressRow(check, completed, progress.current == check)
            }
        } else if (attentionItems.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = LabV2.Amber.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, LabV2.Amber.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = LabV2.Amber, modifier = Modifier.size(16.dp))
                        Text("发现 ${attentionItems.size} 项需关注", style = LabTypography.Supporting, fontWeight = FontWeight.Bold, color = LabV2.Amber)
                    }
                    attentionItems.forEach { item ->
                        Text("• ${item.check.title}：${item.explanation}", style = LabTypography.Caption, color = LabV2.Ink)
                    }
                }
            }
        }
    }

    // Card 2: 连接概览 (Connection Overview Grid)
    HealthDetailCard(
        title = "连接概览",
        subtitle = "网关、DNS、公网出口与中继健康",
        accent = LabV2.Primary,
        headerIcon = Icons.Rounded.Lan
    ) {
        val chunked = healthOverviewChecks.chunked(2)
        chunked.forEach { rowChecks ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowChecks.forEach { check ->
                    val item = visibleItems.find { it.check == check } ?: DiagnosisItem(check)
                    HealthItemCard(
                        item = item,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { selectedCheck = check }
                    )
                }
                if (rowChecks.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
        ) {
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                null,
                Modifier.size(18.dp),
                tint = LabV2.Primary
            )
            Spacer(Modifier.width(4.dp))
            Text(if (expanded) "收起详细结果" else "查看详细结果 · 全部 9 项", style = LabTypography.Button, color = LabV2.Primary)
        }
        if (expanded) {
            val remainingChecks = DiagnosisCheck.entries.filter { it !in healthOverviewChecks }
            remainingChecks.forEach { check ->
                val item = visibleItems.find { it.check == check } ?: DiagnosisItem(check)
                HealthItemCard(
                    item = item,
                    onClick = { selectedCheck = check }
                )
            }
        }
    }

    // Card 3: 评分细则构成 (Scoring Rules Breakdown)
    HealthDetailCard(
        title = "当前得分 $score 分",
        subtitle = "满分 99 分封顶 · 基础分与关键服务状态加权计算",
        accent = scoreColor,
        headerIcon = Icons.Rounded.WorkspacePremium
    ) {
        ScoreRuleRow("基础运行分", "APP 可正常展示本地缓存", 64, true)
        ScoreRuleRow("Hub 连接", if (hubOk) "已连接" else "未连接", 12, hubOk)
        ScoreRuleRow("公网出口", if (exitOk) "已取得 IPv4/IPv6" else "暂无出口地址", 10, exitOk)
        ScoreRuleRow("VPN / STUN", if (vpnOk) "已记录地址" else "暂无记录", 7, vpnOk)
        ScoreRuleRow("在线设备", if (onlineCount > 0) "$onlineCount 台在线" else "暂无在线设备", 5, onlineCount > 0)
        ScoreRuleRow(
            "近期异常扣分",
            if (badCount > 0) "最近 8 条中 $badCount 条异常" else "未发现异常",
            -(badCount * 2),
            badCount == 0,
            isLast = true
        )
    }

    // Card 4: 路由器与 Agent 运维 (Router & Agent Maintenance)
    HealthDetailCard(
        title = "路由器与 Agent 运维",
        subtitle = "Web 管理后台快捷访问 · 路由器 Agent 状态与更新",
        accent = LabV2.Cyan,
        headerIcon = Icons.Rounded.Router
    ) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            RouterHeroGlow()
            Canvas(Modifier.size(220.dp)) {
                val c = center
                drawCircle(color = Color(0x1273A7FF), radius = size.minDimension * 0.40f, center = c)
                drawCircle(color = Color(0x0D73A7FF), radius = size.minDimension * 0.29f, center = c)
                drawArc(
                    color = Color(0x1873A7FF),
                    startAngle = 18f,
                    sweepAngle = 122f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color(0x1473A7FF),
                    startAngle = 200f,
                    sweepAngle = 112f,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
                drawLine(Color(0x0F73A7FF), Offset(size.width * 0.16f, size.height * 0.34f), Offset(size.width * 0.30f, size.height * 0.34f), 1.6.dp.toPx())
                drawLine(Color(0x0F73A7FF), Offset(size.width * 0.70f, size.height * 0.66f), Offset(size.width * 0.85f, size.height * 0.66f), 1.6.dp.toPx())
            }
            Image(
                painter = painterResource(R.drawable.router_skeuomorphic_v3),
                contentDescription = "路由器",
                modifier = Modifier
                    .size(136.dp)
                    .pointerInput(routerLanUrl, routerWanUrl, prefs.favoriteNetworkMode) {
                        detectTapGestures(
                            onTap = { openRouterUrl() },
                            onDoubleTap = { showRouterUrlEditor = true }
                        )
                    },
                contentScale = ContentScale.Fit
            )
        }
        Text(
            "轻点访问路由器后台 · 双击配置内/外网地址",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 11.sp,
            color = LabV2.InkMuted
        )
        HorizontalDivider(color = LabV2.Border)
        Text("Rust Agent 状态", style = LabTypography.SectionTitle, color = LabV2.Ink)
        agentInfo?.let {
            Text(
                "当前版本：${it.currentVersion} · 最新版本：${it.latestVersion}",
                fontSize = LabTypography.Caption.fontSize,
                fontWeight = FontWeight.SemiBold,
                color = LabV2.InkMuted
            )
        }
        Text(
            agentMessage,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            fontSize = LabTypography.Supporting.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = LabV2.InkMuted,
            maxLines = 1,
            softWrap = false
        )
        agentInfo?.lastSeenAt?.takeIf { it.isNotBlank() }?.let {
            Text(
                "Agent 最后上报：$it",
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                fontSize = LabTypography.Caption.fontSize,
                color = LabV2.InkMuted,
                maxLines = 1,
                softWrap = false
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { AgentUpdateCoordinator.check(prefs) },
                enabled = !agentBusy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LabV2.Green),
                border = BorderStroke(1.dp, LabV2.Green.copy(alpha = .34f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.TravelExplore, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("检查更新", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { AgentUpdateCoordinator.update(prefs) },
                enabled = !agentBusy && agentInfo?.updateAvailable == true,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = LabV2.Green),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("立即更新", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
            }
        }
        OutlinedButton(
            onClick = { showCleanupConfirm = true },
            enabled = !agentBusy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD97706)),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = .45f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Rounded.CleaningServices, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("一键清理", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
        }
        Text(
            cleanupMessage,
            fontSize = LabTypography.Caption.fontSize,
            lineHeight = LabTypography.Caption.lineHeight,
            color = if (cleanupMessage.startsWith("清理失败")) LabV2.Red else LabV2.InkMuted,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Clip
        )
    }

    selectedCheck?.let { check ->
        val item = visibleItems.find { it.check == check } ?: DiagnosisItem(check)
        AlertDialog(
            onDismissRequest = { selectedCheck = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(check.title, style = LabTypography.PageTitle, color = LabV2.Ink)
                    HealthStateLabel(item.status)
                }
            },
            text = {
                SelectionContainer {
                    Column(
                        Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = LabV2.FieldSoft,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("关键指标", fontSize = 11.sp, color = LabV2.InkMuted)
                                Text(item.metric, style = LabTypography.CardTitle, color = LabV2.Ink)
                            }
                        }
                        Text(item.explanation, style = LabTypography.Body, color = LabV2.Ink)
                        if (item.details.isNotEmpty()) {
                            HorizontalDivider(color = LabV2.Border)
                            Text("检测明细", style = LabTypography.SectionTitle, color = LabV2.Ink)
                            item.details.forEach { detail ->
                                Text("• $detail", style = LabTypography.Caption, color = LabV2.InkMuted, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCheck = null }) { Text("关闭", style = LabTypography.Button) }
            },
            shape = RoundedCornerShape(22.dp),
            containerColor = LAB_POPUP_SURFACE,
        )
    }
}

@Composable
private fun HealthDetailCard(
    title: String,
    subtitle: String,
    accent: Color,
    headerIcon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    LabV2Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = .18f), accent.copy(alpha = .07f))))
                    .border(1.dp, accent.copy(alpha = .18f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = .86f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(headerIcon, null, Modifier.size(16.dp), tint = accent)
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = LabTypography.SectionTitle, maxLines = 2, overflow = TextOverflow.Clip)
                Text(
                    subtitle,
                    fontSize = LabTypography.Caption.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = LabV2.InkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    lineHeight = LabTypography.Caption.lineHeight
                )
            }
        }
        content()
    }
}

@Composable
private fun ScoreRuleRow(title: String, detail: String, points: Int, achieved: Boolean, isLast: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 43.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(27.dp).height(43.dp), contentAlignment = Alignment.Center) {
            if (!isLast) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(1.dp)
                        .height(22.dp)
                        .background((if (achieved) LabV2.Green else LabV2.InkMuted).copy(alpha = .18f))
                )
            }
            Surface(
                modifier = Modifier.size(24.dp),
                shape = RoundedCornerShape(8.dp),
                color = (if (achieved) LabV2.Green else LabV2.InkMuted).copy(alpha = .09f),
                border = BorderStroke(1.dp, (if (achieved) LabV2.Green else LabV2.InkMuted).copy(alpha = .18f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (achieved) Icons.Rounded.DoneAll else Icons.Rounded.HorizontalRule,
                        null,
                        tint = if (achieved) LabV2.Green else LabV2.InkMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.Ink)
            Text(detail, fontSize = LabTypography.Caption.fontSize, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (points > 0) "+$points" else "$points",
            fontSize = LabTypography.Supporting.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = if (points < 0) LabV2.Red else if (achieved) LabV2.Green else LabV2.InkMuted
        )
    }
}

@Composable
private fun HealthItemCard(item: DiagnosisItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = LabV2.FieldSoft,
        border = BorderStroke(1.dp, LabV2.Border),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.check == DiagnosisCheck.IPV6) "IPv6" else item.check.title,
                    style = LabTypography.CardTitle,
                    color = LabV2.Ink
                )
                HealthStateLabel(item.status)
            }
            Text(item.metric, style = LabTypography.ValueStrong, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.explanation, style = LabTypography.Supporting, color = LabV2.InkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HealthProgressRow(check: DiagnosisCheck, completed: DiagnosisItem?, current: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LabV2.Primary)
        } else {
            val symbol = when (completed?.status) {
                HealthStatus.GOOD -> "✓"
                HealthStatus.WARNING -> "!"
                HealthStatus.ERROR -> "×"
                HealthStatus.UNKNOWN -> "—"
                null -> "○"
            }
            Text(symbol, modifier = Modifier.size(20.dp), fontWeight = FontWeight.Bold, color = healthColor(completed?.status ?: HealthStatus.UNKNOWN))
        }
        Text(check.title, modifier = Modifier.weight(1f), style = LabTypography.Body, color = LabV2.Ink)
        Text(
            when { current -> "检查中"; completed != null -> healthLabel(completed.status); else -> "待检查" },
            style = LabTypography.Supporting, color = LabV2.InkMuted,
        )
    }
}

@Composable
private fun HealthStateLabel(status: HealthStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(healthColor(status), CircleShape))
        Text(healthLabel(status), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = healthColor(status))
    }
}

@Composable
private fun RouterUrlDialog(
    lanUrl: String,
    wanUrl: String,
    onLanChange: (String) -> Unit,
    onWanChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("路由器地址", fontWeight = FontWeight.SemiBold, fontSize = LabTypography.PageTitle.fontSize, color = LabV2.Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("内网", Modifier.width(42.dp), fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    CompactTextField(
                        value = lanUrl,
                        onValueChange = onLanChange,
                        placeholder = "192.168.5.1",
                        leadingIcon = { Icon(Icons.Rounded.Router, null, Modifier.size(16.dp), tint = LabV2.Primary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                        textStyle = LabTypography.FieldValue,
                        placeholderStyle = LabTypography.Placeholder
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("外网", Modifier.width(42.dp), fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted)
                    CompactTextField(
                        value = wanUrl,
                        onValueChange = onWanChange,
                        placeholder = "example.com",
                        leadingIcon = { Icon(Icons.Rounded.Public, null, Modifier.size(16.dp), tint = LabV2.Cyan) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                        textStyle = LabTypography.FieldValue,
                        placeholderStyle = LabTypography.Placeholder
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onSave, shape = RoundedCornerShape(16.dp)) { Text("保存", style = LabTypography.Button) } },
        dismissButton = { OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(16.dp)) { Text("取消", style = LabTypography.Button) } },
        shape = RoundedCornerShape(28.dp),
        containerColor = LAB_POPUP_SURFACE,
        tonalElevation = 0.dp
    )
}

private fun healthLabel(status: HealthStatus) = when (status) {
    HealthStatus.GOOD -> "正常"
    HealthStatus.WARNING -> "需关注"
    HealthStatus.ERROR -> "异常"
    HealthStatus.UNKNOWN -> "未确认"
}

private fun overallHealthLabel(status: HealthStatus) = when (status) {
    HealthStatus.GOOD -> "良好"
    HealthStatus.WARNING -> "存在异常"
    HealthStatus.ERROR -> "严重异常"
    HealthStatus.UNKNOWN -> "待诊断"
}

private fun healthColor(status: HealthStatus): Color = when (status) {
    HealthStatus.GOOD -> LabV2.Green
    HealthStatus.WARNING -> LabV2.Amber
    HealthStatus.ERROR -> LabV2.Red
    HealthStatus.UNKNOWN -> LabV2.InkMuted
}
