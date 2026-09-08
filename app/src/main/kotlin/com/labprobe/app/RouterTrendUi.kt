package com.labprobe.app

import android.graphics.Paint
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
internal fun RouterTrendPanel(history: StateFlow<List<RouterTrendSample>>) {
    val allSamples by history.collectAsState()
    val context = LocalContext.current
    val lifecycle = (context.findActivity() as? ComponentActivity)?.lifecycle
    var started by remember(lifecycle) {
        mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true)
    }
    var selected by remember { mutableStateOf<RouterTrendSample?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
            if (!started) selected = null
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // Retain the most recent 300 samples (~5 minutes at 1s rate), flexibly mapped across the full canvas
    val visible = remember(allSamples) { allSamples.takeLast(300) }
    val latest = visible.lastOrNull()
    val reading = selected ?: latest
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("网络趋势", style = LabTypography.Body, color = LabV2.Ink, modifier = Modifier.weight(1f))
            Surface(
                color = LabV2.Green.copy(alpha = .1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.size(6.dp).background(LabV2.Green, CircleShape))
                    Text("实时动态走势", style = LabTypography.Caption, color = LabV2.Green)
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
            visible.isEmpty() -> "等待实时数据，收到后开始绘制"
            visible.size < 5 -> "正在积累实时采样 · 已收集 ${visible.size} 秒"
            else -> "实时平滑走势 · 长按拖动查看各时刻读数"
        }

        TrendCanvas(
            samples = visible,
            selected = selected,
            onSelect = { point -> selected = point },
            onRelease = { selected = null }
        )
        Text(status, style = LabTypography.Caption, color = LabV2.InkMuted)
    }
}

@Composable
private fun TrendCanvas(
    samples: List<RouterTrendSample>,
    selected: RouterTrendSample?,
    onSelect: (RouterTrendSample?) -> Unit,
    onRelease: () -> Unit,
) {
    val density = LocalDensity.current
    val rightGutter = with(density) { 44.dp.toPx() }
    val labelSize = with(density) { LabTypography.Caption.fontSize.toPx() }
    val paint = remember(labelSize) { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = labelSize; color = LabV2.InkMuted.toArgb() } }
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val rawSpeedMax = samples.maxOfOrNull { maxOf(it.uploadBps, it.downloadBps).toDouble() } ?: 0.0
    val rawSessionMax = samples.maxOfOrNull { maxOf(it.ipv4, it.ipv6).toDouble() } ?: 0.0
    val speedMax = rememberTrendScale(rawSpeedMax, 1_000.0)
    val sessionMax = rememberTrendScale(rawSessionMax, 10.0)

    val latestSamples by rememberUpdatedState(samples)
    val selectCallback by rememberUpdatedState(onSelect)
    val releaseCallback by rememberUpdatedState(onRelease)

    Canvas(
        Modifier.fillMaxWidth().height(222.dp)
            .semantics { contentDescription = "路由器实时网络趋势图。绿色下载，蓝色上传，深蓝 IPv4 会话，天蓝 IPv6 会话。长按拖动查看采样值。" }
            .pointerInput(rightGutter) {
                fun select(x: Float) {
                    val count = latestSamples.size
                    if (count == 0) return
                    val drawWidth = (size.width - rightGutter).coerceAtLeast(1f)
                    val fraction = (x / drawWidth).coerceIn(0f, 1f)
                    val index = (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
                    selectCallback(latestSamples[index])
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { select(it.x) },
                    onDragEnd = { releaseCallback() },
                    onDragCancel = { releaseCallback() },
                    onDrag = { change, _ ->
                        change.consume()
                        select(change.position.x)
                    }
                )
            }
    ) {
        val width = (size.width - rightGutter).coerceAtLeast(1f)
        val speedTop = 21.dp.toPx()
        val speedBottom = 112.dp.toPx()
        val sessionsTop = 143.dp.toPx()
        val sessionsBottom = 198.dp.toPx()
        val unit = when { speedMax >= 1e9 -> 1e9 to "Gbps"; speedMax >= 1e6 -> 1e6 to "Mbps"; else -> 1e3 to "Kbps" }

        fun text(value: String, x: Float, y: Float) { drawContext.canvas.nativeCanvas.drawText(value, x, y, paint) }
        text("网速 · ${unit.second}", 0f, 12.dp.toPx())
        text("会话 · IPv4 (蓝) / IPv6 (青)", 0f, 134.dp.toPx())

        fun grid(top: Float, bottom: Float, max: Double, divisor: Double) {
            for (i in 0..2) {
                val y = bottom - (bottom - top) * i / 2
                drawLine(
                    LabV2.Border, Offset(0f, y), Offset(width, y), 0.7.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                )
                text(trendNumber(max * i / 2 / divisor), width + 5.dp.toPx(), y + labelSize / 3)
            }
        }
        grid(speedTop, speedBottom, speedMax, unit.first)
        grid(sessionsTop, sessionsBottom, sessionMax, 1.0)

        if (samples.isNotEmpty()) {
            val count = samples.size
            fun x(index: Int): Float = if (count <= 1) width / 2f else (index.toFloat() / (count - 1)) * width

            fun series(top: Float, bottom: Float, maximum: Double, color: Color, value: (RouterTrendSample) -> Long) {
                fun y(point: RouterTrendSample) = bottom - ((value(point) / maximum).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()

                val path = Path()
                val area = Path()

                path.moveTo(x(0), y(samples.first()))
                area.moveTo(x(0), bottom)
                area.lineTo(x(0), y(samples.first()))

                if (count == 1) {
                    path.lineTo(width, y(samples.first()))
                    area.lineTo(width, y(samples.first()))
                    area.lineTo(width, bottom)
                    area.close()
                } else {
                    for (i in 1 until count) {
                        val prevX = x(i - 1)
                        val prevY = y(samples[i - 1])
                        val curX = x(i)
                        val curY = y(samples[i])
                        val midX = (prevX + curX) / 2f
                        path.cubicTo(midX, prevY, midX, curY, curX, curY)
                        area.cubicTo(midX, prevY, midX, curY, curX, curY)
                    }
                    area.lineTo(x(count - 1), bottom)
                    area.close()
                }

                clipRect(0f, top - 3.dp.toPx(), width, bottom + 1.dp.toPx()) {
                    drawPath(
                        area,
                        Brush.verticalGradient(
                            listOf(color.copy(alpha = .18f), color.copy(alpha = .02f)),
                            top, bottom
                        )
                    )
                    drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

                    if (count == 1) {
                        drawCircle(color, 2.5.dp.toPx(), Offset(x(0), y(samples.first())))
                    }
                    selected?.let { sel ->
                        val selIndex = samples.indexOf(sel).takeIf { it >= 0 } ?: (count - 1)
                        drawCircle(Color.White, 4.dp.toPx(), Offset(x(selIndex), y(sel)))
                        drawCircle(color, 2.5.dp.toPx(), Offset(x(selIndex), y(sel)))
                    }
                }
            }

            series(speedTop, speedBottom, speedMax, LabV2.Green) { it.downloadBps }
            series(speedTop, speedBottom, speedMax, LabV2.Primary) { it.uploadBps }
            series(sessionsTop, sessionsBottom, sessionMax, LabV2.Primary) { it.ipv4 }
            series(sessionsTop, sessionsBottom, sessionMax, Color(0xFF0EA5E9)) { it.ipv6 }

            selected?.let { sel ->
                val selIndex = samples.indexOf(sel).takeIf { it >= 0 } ?: (count - 1)
                val cursor = x(selIndex).coerceIn(0f, width)
                drawLine(LabV2.InkFaint, Offset(cursor, speedTop), Offset(cursor, sessionsBottom), 1.dp.toPx())
            }

            // Bottom dynamic time labels
            val first = samples.first()
            val last = samples.last()
            val startLabel = dateFormat.format(Date(first.epochMs))
            val endLabel = dateFormat.format(Date(last.epochMs))
            val midLabel = dateFormat.format(Date((first.epochMs + last.epochMs) / 2))

            val startWidth = paint.measureText(startLabel)
            val midWidth = paint.measureText(midLabel)
            val endWidth = paint.measureText(endLabel)

            text(startLabel, 0f, 217.dp.toPx())
            if (count > 2 && startLabel != endLabel) {
                val midX = (width / 2f - midWidth / 2f).coerceIn(startWidth + 6.dp.toPx(), (width - endWidth - midWidth - 6.dp.toPx()).coerceAtLeast(startWidth + 6.dp.toPx()))
                text(midLabel, midX, 217.dp.toPx())
            }
            text(endLabel, (width - endWidth).coerceAtLeast(0f), 217.dp.toPx())
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
