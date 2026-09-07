package com.labprobe.app

import android.graphics.Paint
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

@Composable
internal fun RouterTrendPanel(history: StateFlow<List<RouterTrendSample>>) {
    val samples by history.collectAsState()
    val context = LocalContext.current
    val lifecycle = (context.findActivity() as? ComponentActivity)?.lifecycle
    var started by remember(lifecycle) {
        mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true)
    }
    var minutes by rememberSaveable { mutableIntStateOf(5) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var selected by remember { mutableStateOf<RouterTrendSample?>(null) }
    var heldEnd by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
            if (!started) { selected = null; heldEnd = null }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    val end = heldEnd ?: now
    val duration = minutes * 60_000L
    val visible = remember(samples, end, duration) {
        // One preceding point permits clipping a segment at the left edge.
        val first = samples.indexOfFirst { it.elapsedMs >= end - duration }
        if (first < 0) emptyList() else samples.drop((first - 1).coerceAtLeast(0)).filter { it.elapsedMs <= end }
    }
    val latest = samples.lastOrNull()
    val paused = latest != null && now - latest.elapsedMs > RouterTrendHistory.GAP_MS
    val reading = if (heldEnd != null) selected else latest
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("网络趋势", style = LabTypography.Body, color = LabV2.Ink, modifier = Modifier.weight(1f))
            listOf(5, 15).forEach { value ->
                Surface(color = if (minutes == value) LabCoreSurface.Inner else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)) {
                    TextButton(onClick = { minutes = value; selected = null; heldEnd = null },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Text("${value}分钟", style = LabTypography.Supporting,
                            color = if (minutes == value) LabV2.Primary else LabV2.InkMuted)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("↓ 下载 ${reading?.let { trendRate(it.downloadBps) } ?: "--"}",
                Modifier.weight(1f), style = LabTypography.Value, color = LabV2.Green)
            Text("↑ 上传 ${reading?.let { trendRate(it.uploadBps) } ?: "--"}",
                Modifier.weight(1f), style = LabTypography.Value, color = LabV2.Primary)
        }
        Text(reading?.let { "会话 ${it.sessions}  ·  IPv4 ${it.ipv4} / IPv6 ${it.ipv6}" } ?: "会话 --  ·  IPv4 -- / IPv6 --",
            style = LabTypography.Supporting, color = LabV2.InkMuted)
        val status = when {
            selected != null -> "${timeFormat.format(Date(selected!!.epochMs))} · 松手返回实时"
            heldEnd != null -> "此时段无采样 · 松手返回实时"
            latest == null -> "等待实时数据，收到后开始绘制"
            paused -> "数据暂停 · 最后采样 ${timeFormat.format(Date(latest.epochMs))}"
            visible.size < 2 -> "正在积累数据 · 长按查看读数"
            else -> "实时 · 长按拖动查看读数"
        }
        TrendCanvas(visible, end, duration, selected,
            onSelect = { point -> if (heldEnd == null) heldEnd = end; selected = point },
            onRelease = { selected = null; heldEnd = null })
        Text(status, style = LabTypography.Caption, color = if (paused && selected == null) LabV2.Amber else LabV2.InkMuted)
    }
}

@Composable
private fun TrendCanvas(
    samples: List<RouterTrendSample>, end: Long, duration: Long, selected: RouterTrendSample?,
    onSelect: (RouterTrendSample?) -> Unit, onRelease: () -> Unit,
) {
    val density = LocalDensity.current
    val rightGutter = with(density) { 44.dp.toPx() }
    val labelSize = with(density) { LabTypography.Caption.fontSize.toPx() }
    val paint = remember(labelSize) { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = labelSize; color = LabV2.InkMuted.toArgb() } }
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val origin = remember { end }
    val movingEnd by animateFloatAsState((end - origin).toFloat(), tween(300), label = "trendTime")
    val displayedEnd = origin + movingEnd.toLong()
    val rawSpeedMax = samples.maxOfOrNull { maxOf(it.uploadBps, it.downloadBps).toDouble() } ?: 0.0
    val rawSessionMax = samples.maxOfOrNull { it.sessions.toDouble() } ?: 0.0
    val speedMax = rememberTrendScale(rawSpeedMax, 1_000.0)
    val sessionMax = rememberTrendScale(rawSessionMax, 10.0)
    val latestSamples by rememberUpdatedState(samples)
    val latestEnd by rememberUpdatedState(displayedEnd)
    val selectCallback by rememberUpdatedState(onSelect)
    val releaseCallback by rememberUpdatedState(onRelease)
    Canvas(Modifier.fillMaxWidth().height(222.dp)
        .semantics { contentDescription = "最近${duration / 60_000}分钟网速与会话趋势。绿色下载，蓝色上传，青色会话。长按拖动查看采样值。" }
        .pointerInput(duration, rightGutter) {
            fun select(x: Float) {
                val time = latestEnd - duration + (x / (size.width - rightGutter).coerceAtLeast(1f)).coerceIn(0f, 1f).toDouble() * duration
                val point = latestSamples.minByOrNull { abs(it.elapsedMs - time.toLong()) }
                // A missing interval must not display a distant measurement as current.
                selectCallback(point?.takeIf { abs(it.elapsedMs - time.toLong()) <= RouterTrendHistory.GAP_MS })
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { select(it.x) }, onDragEnd = { releaseCallback() },
                onDragCancel = { releaseCallback() },
                onDrag = { change, _ -> change.consume(); select(change.position.x) })
        }) {
        val width = (size.width - rightGutter).coerceAtLeast(1f)
        val speedTop = 21.dp.toPx()
        val speedBottom = 112.dp.toPx()
        val sessionsTop = 143.dp.toPx()
        val sessionsBottom = 198.dp.toPx()
        val unit = when { speedMax >= 1e9 -> 1e9 to "Gbps"; speedMax >= 1e6 -> 1e6 to "Mbps"; else -> 1e3 to "Kbps" }
        fun text(value: String, x: Float, y: Float) { drawContext.canvas.nativeCanvas.drawText(value, x, y, paint) }
        text("网速 · ${unit.second}", 0f, 12.dp.toPx())
        text("会话 · 个", 0f, 134.dp.toPx())
        fun grid(top: Float, bottom: Float, max: Double, divisor: Double) {
            for (i in 0..2) {
                val y = bottom - (bottom - top) * i / 2
                drawLine(LabV2.Border, Offset(0f, y), Offset(width, y), 0.7.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
                text(trendNumber(max * i / 2 / divisor), width + 5.dp.toPx(), y + labelSize / 3)
            }
        }
        grid(speedTop, speedBottom, speedMax, unit.first)
        grid(sessionsTop, sessionsBottom, sessionMax, 1.0)
        fun x(point: RouterTrendSample) = ((point.elapsedMs - (displayedEnd - duration)).toDouble() / duration * width).toFloat()
        fun series(top: Float, bottom: Float, maximum: Double, color: Color, value: (RouterTrendSample) -> Long) {
            fun y(point: RouterTrendSample) = bottom - ((value(point) / maximum).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()
            val segments = mutableListOf<MutableList<RouterTrendSample>>()
            samples.forEach { point ->
                val last = segments.lastOrNull()?.lastOrNull()
                if (last == null || point.elapsedMs - last.elapsedMs > RouterTrendHistory.GAP_MS) segments.add(mutableListOf())
                segments.last().add(point)
            }
            clipRect(0f, top - 3.dp.toPx(), width, bottom + 1.dp.toPx()) {
                segments.forEach { points ->
                    val path = Path().apply {
                        moveTo(x(points.first()), y(points.first()))
                        points.drop(1).forEach { lineTo(x(it), y(it)) }
                    }
                    val area = Path().apply {
                        addPath(path); lineTo(x(points.last()), bottom); lineTo(x(points.first()), bottom); close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .17f), color.copy(alpha = .025f)), top, bottom))
                    drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (points.size == 1) drawCircle(color, 2.dp.toPx(), Offset(x(points.first()), y(points.first())))
                }
                selected?.let { drawCircle(color, 3.dp.toPx(), Offset(x(it), y(it))) }
            }
        }
        series(speedTop, speedBottom, speedMax, LabV2.Green) { it.downloadBps }
        series(speedTop, speedBottom, speedMax, LabV2.Primary) { it.uploadBps }
        series(sessionsTop, sessionsBottom, sessionMax, Color(0xFF19B5AA)) { it.sessions }
        selected?.let {
            val cursor = x(it).coerceIn(0f, width)
            drawLine(LabV2.InkFaint, Offset(cursor, speedTop), Offset(cursor, sessionsBottom), 1.dp.toPx())
        }
        val last = samples.lastOrNull()
        val endEpoch = last?.let { it.epochMs + displayedEnd - it.elapsedMs } ?: System.currentTimeMillis()
        for (i in 0..2) {
            val label = dateFormat.format(Date(endEpoch - duration + duration * i / 2))
            val labelWidth = paint.measureText(label)
            val left = (width * i / 2 - labelWidth / 2).coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
            text(label, left, 217.dp.toPx())
        }
    }
}

/** Expand immediately; only shrink after the lower range has stayed stable. */
@Composable
private fun rememberTrendScale(raw: Double, minimum: Double): Double {
    val desired = niceTrendMaximum(raw, minimum)
    var ceiling by remember { mutableDoubleStateOf(desired) }
    LaunchedEffect(desired) {
        if (desired >= ceiling) ceiling = desired
        else if (desired < ceiling) { delay(4_000); ceiling = desired }
    }
    val animated by animateFloatAsState(ceiling.toFloat(), tween(600), label = "trendScale")
    return maxOf(raw, animated.toDouble(), minimum)
}

internal fun niceTrendMaximum(value: Double, minimum: Double): Double {
    val target = maxOf(value * 1.15, minimum)
    val step = 10.0.pow(floor(log10(target))) / 2
    return ceil(target / step) * step
}

private fun trendNumber(value: Double): String = when {
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000)
    value >= 10_000 -> String.format(Locale.getDefault(), "%.0fk", value / 1_000)
    value >= 100 || value % 1.0 == 0.0 -> String.format(Locale.getDefault(), "%.0f", value)
    else -> String.format(Locale.getDefault(), "%.1f", value)
}

private fun trendRate(value: Long): String = when {
    value >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.2f Gbps", value / 1e9)
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.2f Mbps", value / 1e6)
    else -> String.format(Locale.getDefault(), "%.1f Kbps", value / 1e3)
}
