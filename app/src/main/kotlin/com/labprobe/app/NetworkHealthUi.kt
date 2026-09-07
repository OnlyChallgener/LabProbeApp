package com.labprobe.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val healthOverviewChecks = listOf(
    DiagnosisCheck.INTERNET, DiagnosisCheck.GATEWAY, DiagnosisCheck.DNS,
    DiagnosisCheck.IPV6, DiagnosisCheck.ROUTER, DiagnosisCheck.RELAY, DiagnosisCheck.WIREGUARD,
)

/** Presentation only: the coordinator owns sampling, cancellation, and conclusions. */
@Composable
fun NetworkHealthScreen(
    progress: DiagnosisProgress,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    topNav: @Composable () -> Unit,
) {
    val hostColors = MaterialTheme.colorScheme
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF86CFFF),
        onPrimary = Color(0xFF00344F),
        background = Color(0xFF10171F),
        onBackground = Color(0xFFE4EBF2),
        surface = Color(0xFF1A242E),
        surfaceContainer = Color(0xFF1A242E),
        surfaceContainerHigh = Color(0xFF25323E),
        surfaceContainerHighest = Color(0xFF2B3946),
        surfaceContainerLow = Color(0xFF151E27),
        surfaceContainerLowest = Color(0xFF10171F),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF687A8B),
        outlineVariant = Color(0xFF344452),
        error = Color(0xFFFFB4AB),
        onSurface = Color(0xFFE4EBF2),
        onSurfaceVariant = Color(0xFFB7C4D1),
    ) else hostColors
    MaterialTheme(colorScheme = colors) {
        NetworkHealthContent(progress, onStart, onCancel, topNav)
    }
}

@Composable
private fun NetworkHealthContent(
    progress: DiagnosisProgress,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    topNav: @Composable () -> Unit,
) {
    var dismissedResultAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedCheck by remember { mutableStateOf<DiagnosisCheck?>(null) }
    val result = progress.result
    val showResult = !progress.running && !progress.cancelled && result != null &&
        result.completedAt != dismissedResultAt
    val visibleItems = if (progress.running || progress.cancelled) progress.items
        else result?.items ?: progress.items
    val colors = MaterialTheme.colorScheme
    val start = {
        expanded = false
        selectedCheck = null
        onStart()
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.padding(horizontal = LabV2.PageHorizontal, vertical = 8.dp)) { topNav() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = LabV2.PageHorizontal, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    if (showResult) "诊断结果" else "网络健康",
                    style = LabTypography.AppTitle, color = colors.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item {
                HealthSurface {
                    Text("网络状态", style = LabTypography.Supporting, color = colors.onSurfaceVariant)
                    val status = if (progress.running || progress.cancelled) HealthStatus.UNKNOWN
                        else result?.status ?: HealthStatus.UNKNOWN
                    Text(
                        when {
                            progress.running -> "正在诊断网络…"
                            progress.cancelled -> "诊断已停止"
                            else -> overallHealthLabel(status)
                        },
                        style = LabTypography.CompactMetric,
                        color = if (progress.running) colors.onSurface else healthColor(status),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text(
                        when {
                            progress.running -> "正在检查 ${progress.current?.title ?: "网络连接"}，请稍候"
                            progress.cancelled -> "已保留完成的检查，可重新开始完整诊断。"
                            result != null -> result.conclusion
                            else -> "一次检查，了解连接是否正常，找到需要关注的问题。"
                        },
                        style = LabTypography.Body, color = colors.onSurfaceVariant,
                    )
                    if (result != null && !progress.running && !progress.cancelled) {
                        val completedText = remember(result.completedAt) {
                            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(result.completedAt))
                        }
                        Text("诊断于 $completedText", style = LabTypography.Caption, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    if (progress.running) {
                        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("停止诊断", style = LabTypography.Button)
                        }
                    } else {
                        Button(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = LabV2.ButtonShape) {
                            Text(if (result != null || progress.cancelled) "重新诊断" else "开始诊断", style = LabTypography.Button)
                        }
                    }
                }
            }
            when {
                progress.running -> {
                    item {
                        HealthSurface {
                            Text("检查进度", style = LabTypography.CardTitle, color = colors.onSurface)
                            DiagnosisCheck.entries.forEach { check ->
                                val completed = visibleItems.find { it.check == check }
                                HealthProgressRow(check, completed, progress.current == check)
                            }
                        }
                    }
                }
                showResult -> {
                    val attentionItems = visibleItems.filter { it.status == HealthStatus.WARNING || it.status == HealthStatus.ERROR }
                    if (attentionItems.isNotEmpty()) {
                        item {
                            Text("需要关注", style = LabTypography.CardTitle, color = colors.onBackground)
                        }
                        items(attentionItems, key = { it.check }) { item ->
                            HealthItemCard(item, onClick = { selectedCheck = item.check })
                        }
                    }
                    item {
                        TextButton(onClick = { dismissedResultAt = result?.completedAt }, modifier = Modifier.fillMaxWidth()) {
                            Text("返回健康总览", style = LabTypography.Button)
                        }
                    }
                }
                else -> {
                    item {
                        Text("连接概览", style = LabTypography.CardTitle, color = colors.onBackground)
                    }
                    items(healthOverviewChecks.chunked(2)) { checks ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            checks.forEach { check ->
                                HealthItemCard(
                                    item = visibleItems.find { it.check == check } ?: DiagnosisItem(check),
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onClick = { selectedCheck = check },
                                )
                            }
                        }
                    }
                }
            }
            if (!progress.running) {
                item {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (expanded) "收起详细结果" else "查看详细结果 · 9 项", style = LabTypography.Button)
                    }
                }
                if (expanded) {
                    items(DiagnosisCheck.entries, key = { "detail-${it.name}" }) { check ->
                        HealthItemCard(
                            item = visibleItems.find { it.check == check } ?: DiagnosisItem(check),
                            onClick = { selectedCheck = check },
                        )
                    }
                }
            }
        }
    }
    selectedCheck?.let { check ->
        val item = visibleItems.find { it.check == check } ?: DiagnosisItem(check)
        AlertDialog(
            onDismissRequest = { selectedCheck = null },
            title = { Text(check.title, style = LabTypography.PageTitle, color = colors.onSurface) },
            text = {
                SelectionContainer {
                    Column(
                        Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HealthStateLabel(item.status)
                        Text(item.metric, style = LabTypography.CardTitle, color = colors.onSurface)
                        Text(item.explanation, style = LabTypography.Body, color = colors.onSurfaceVariant)
                        if (item.details.isNotEmpty()) {
                            Text("详细结果", style = LabTypography.SectionTitle, color = colors.onSurface)
                            item.details.forEach { detail ->
                                Text(detail, style = LabTypography.Body, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedCheck = null }) { Text("关闭") } },
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun HealthSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun HealthItemCard(item: DiagnosisItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp), color = colors.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(if (item.check == DiagnosisCheck.IPV6) "IPv6" else item.check.title,
                style = LabTypography.CardTitle, color = colors.onSurface)
            HealthStateLabel(item.status)
            Text(item.metric, style = LabTypography.ValueStrong, color = colors.onSurface)
            Text(item.explanation, style = LabTypography.Supporting, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun HealthProgressRow(check: DiagnosisCheck, completed: DiagnosisItem?, current: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            val symbol = when (completed?.status) {
                HealthStatus.GOOD -> "✓"
                HealthStatus.WARNING -> "!"
                HealthStatus.ERROR -> "×"
                HealthStatus.UNKNOWN -> "—"
                null -> "○"
            }
            Text(symbol, modifier = Modifier.size(20.dp), color = healthColor(completed?.status ?: HealthStatus.UNKNOWN))
        }
        Text(check.title, modifier = Modifier.weight(1f), style = LabTypography.Body, color = MaterialTheme.colorScheme.onSurface)
        Text(
            when { current -> "检查中"; completed != null -> healthLabel(completed.status); else -> "待检查" },
            style = LabTypography.Supporting, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HealthStateLabel(status: HealthStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(healthColor(status), CircleShape))
        Text(healthLabel(status), style = LabTypography.Supporting, color = healthColor(status))
    }
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
    HealthStatus.UNKNOWN -> "尚未确认"
}

@Composable
private fun healthColor(status: HealthStatus): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (status) {
        HealthStatus.GOOD -> if (dark) Color(0xFF75D8AE) else Color(0xFF137D53)
        HealthStatus.WARNING -> if (dark) Color(0xFFF3BE72) else Color(0xFF9A5900)
        HealthStatus.ERROR -> MaterialTheme.colorScheme.error
        HealthStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
