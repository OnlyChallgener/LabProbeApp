package com.labprobe.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val ROAMING_LIVE_WINDOW_SECONDS = 60f

@Composable
fun LabRoamCharts(samples: List<WifiSample>, running: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoamingWaveChart(
            title = "信号强度 dBm",
            values = samples.filter { it.rssi > -120 }.map { it.rssi.toDouble() },
            running = running,
            color = Color(0xFF16A34A),
            emptyText = "无可用 RSSI",
            yFormatter = { it.roundToInt().toString() },
            emptyYMin = -90.0,
            emptyYMax = -30.0,
            modifier = Modifier.fillMaxWidth().height(196.dp)
        )
        RoamingWaveChart(
            title = "延迟 ms",
            values = samples.filter { it.latency != null }.mapNotNull { it.latency?.toDouble() },
            running = running,
            color = Color(0xFF2563EB),
            emptyText = "等待延迟样本",
            yFormatter = { formatLatencyAxis(it) },
            minFloor = 0.0,
            emptyYMin = 0.0,
            emptyYMax = 100.0,
            modifier = Modifier.fillMaxWidth().height(196.dp)
        )
    }
}

@Composable
private fun RoamingWaveChart(
    title: String,
    values: List<Double>,
    running: Boolean,
    color: Color,
    emptyText: String,
    yFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    minFloor: Double? = null,
    emptyYMin: Double = 0.0,
    emptyYMax: Double = 100.0
) {
    val scheme = MaterialTheme.colorScheme
    var followLive by remember { mutableStateOf(true) }
    var centerSec by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val totalDuration = (values.size - 1).coerceAtLeast(0).toFloat()
    val baseWindow = if (running) ROAMING_LIVE_WINDOW_SECONDS else totalDuration.coerceAtLeast(ROAMING_LIVE_WINDOW_SECONDS)

    LaunchedEffect(values.size, running, followLive) {
        if (running && followLive) {
            zoom = 1f
            centerSec = totalDuration - ROAMING_LIVE_WINDOW_SECONDS / 2f
        } else if (!running && values.isNotEmpty() && followLive) {
            zoom = 1f
            centerSec = totalDuration / 2f
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 12.2.sp, fontWeight = FontWeight.Black, color = scheme.onSurface)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = .10f)),
            modifier = modifier
        ) {
            Box(Modifier.fillMaxSize()) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                        .pointerInput(values.size, running, zoom) {
                            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                if (values.isEmpty()) return@detectTransformGestures
                                followLive = false
                                val axisLeft = 32.dp.toPx()
                                val plotRightPad = 4.dp.toPx()
                                val plotWidth = (size.width - axisLeft - plotRightPad).coerceAtLeast(1f)
                                val oldWindow = (baseWindow / zoom).coerceAtLeast(4f)
                                val oldStart = (centerSec - oldWindow / 2f).coerceIn(0f, (totalDuration - oldWindow).coerceAtLeast(0f))
                                val focalRatio = ((centroid.x - axisLeft) / plotWidth).coerceIn(0f, 1f)
                                val focalSec = oldStart + oldWindow * focalRatio
                                zoom = (zoom * gestureZoom).coerceIn(1f, 12f)
                                val newWindow = (baseWindow / zoom).coerceAtLeast(4f)
                                val panSec = -pan.x / plotWidth * newWindow
                                centerSec = (focalSec - (focalRatio - .5f) * newWindow + panSec)
                                    .coerceIn(newWindow / 2f, (totalDuration - newWindow / 2f).coerceAtLeast(newWindow / 2f))
                            }
                        }
                        .pointerInput(values.size, running) {
                            detectTapGestures(onDoubleTap = {
                                zoom = 1f
                                followLive = true
                                centerSec = if (running) totalDuration - ROAMING_LIVE_WINDOW_SECONDS / 2f else totalDuration / 2f
                            })
                        }
                ) {
                    val axisLeft = 29.dp.toPx()
                    val xAxisHeight = 22.dp.toPx()
                    val topPad = 8.dp.toPx()
                    val rightPad = 5.dp.toPx()
                    val plotLeft = axisLeft
                    val plotRight = size.width - rightPad
                    val plotTop = topPad
                    val plotBottom = size.height - xAxisHeight
                    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

                    val visibleWindow = (baseWindow / zoom).coerceAtLeast(4f)
                    val visibleStart = if (running && followLive) {
                        (totalDuration - ROAMING_LIVE_WINDOW_SECONDS).coerceAtLeast(0f)
                    } else {
                        (centerSec - visibleWindow / 2f).coerceIn(0f, (totalDuration - visibleWindow).coerceAtLeast(0f))
                    }
                    val visibleEnd = (visibleStart + visibleWindow).coerceAtLeast(visibleStart + 1f)
                    val firstIndex = floor(visibleStart).toInt().coerceAtLeast(0)
                    val lastIndex = ceil(visibleEnd).toInt().coerceAtMost(values.lastIndex)
                    val visibleValues = if (values.isEmpty() || lastIndex < firstIndex) emptyList() else values.subList(firstIndex, lastIndex + 1)

                    val hasVisibleValues = visibleValues.isNotEmpty()
                    val rawMin = if (hasVisibleValues) visibleValues.minOrNull() ?: emptyYMin else emptyYMin
                    val rawMax = if (hasVisibleValues) visibleValues.maxOrNull() ?: emptyYMax else emptyYMax
                    val range = (rawMax - rawMin).coerceAtLeast(if (minFloor == null) 3.0 else 5.0)
                    var yMin = if (hasVisibleValues) rawMin - range * .25 else emptyYMin
                    var yMax = if (hasVisibleValues) rawMax + range * .25 else emptyYMax
                    minFloor?.let { yMin = max(it, yMin) }
                    if (yMax <= yMin) yMax = yMin + 1.0
                    val yTicks = sixTicks(yMin, yMax)

                    val gridColor = Color(0xFF94A3B8).copy(alpha = .20f)
                    val axisColor = scheme.onSurfaceVariant.copy(alpha = .78f)
                    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        setColor(axisColor.toArgbCompat())
                        textSize = 8.2.sp.toPx()
                        textAlign = Paint.Align.RIGHT
                        isFakeBoldText = false
                    }
                    drawRect(scheme.surface, topLeft = Offset.Zero, size = Size(axisLeft, size.height))
                    yTicks.forEach { tick ->
                        val yRatio = ((tick - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)
                        val y = plotBottom - yRatio * plotHeight
                        drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
                        val fm = labelPaint.fontMetrics
                        drawContext.canvas.nativeCanvas.drawText(yFormatter(tick), axisLeft - 4.dp.toPx(), y - (fm.ascent + fm.descent) / 2f, labelPaint)
                    }
                    val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        setColor(axisColor.toArgbCompat())
                        textSize = 8.2.sp.toPx()
                        textAlign = Paint.Align.CENTER
                    }
                    xTicks(visibleStart, visibleEnd).forEach { sec ->
                        val x = plotLeft + ((sec - visibleStart) / (visibleEnd - visibleStart)).coerceIn(0f, 1f) * plotWidth
                        drawLine(gridColor.copy(alpha = .55f), Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
                        drawContext.canvas.nativeCanvas.drawText(formatSecondsAxis(sec), x.coerceIn(plotLeft + 8.dp.toPx(), plotRight - 8.dp.toPx()), plotBottom + 14.dp.toPx(), xPaint)
                    }
                    if (visibleValues.isEmpty()) return@Canvas
                    fun xFor(index: Int) = plotLeft + ((index - visibleStart) / (visibleEnd - visibleStart)).coerceIn(0f, 1f) * plotWidth
                    fun yFor(value: Double): Float {
                        val ratio = ((value - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)
                        return plotBottom - ratio * plotHeight
                    }
                    val pts = (firstIndex..lastIndex).map { Offset(xFor(it), yFor(values[it])) }
                    if (pts.size >= 2) {
                        val wave = cubicPath(pts, plotLeft, plotRight)
                        val fill = Path().apply {
                            addPath(wave)
                            lineTo(plotRight, plotBottom)
                            lineTo(plotLeft, plotBottom)
                            close()
                        }
                        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .12f), color.copy(alpha = .02f)), plotTop, plotBottom))
                        drawPath(wave, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    } else {
                        drawCircle(color, 3.dp.toPx(), pts.first())
                    }
                }
                if (values.isEmpty()) Text(emptyText, modifier = Modifier.align(Alignment.Center), color = scheme.onSurfaceVariant.copy(alpha = .58f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (running && !followLive) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        AssistChip(onClick = { followLive = true }, label = { Text("返回实时", fontSize = 10.5.sp) }, leadingIcon = { Icon(Icons.Rounded.MyLocation, null) })
                    }
                }
            }
        }
    }
}

private fun cubicPath(points: List<Offset>, plotLeft: Float, plotRight: Float): Path = Path().apply {
    moveTo(plotLeft, points.first().y)
    lineTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val p0 = points[i - 1]
        val p1 = points[i]
        val dx = (p1.x - p0.x) / 2f
        cubicTo(p0.x + dx, p0.y, p1.x - dx, p1.y, p1.x, p1.y)
    }
    lineTo(plotRight, points.last().y)
}

private fun sixTicks(min: Double, max: Double): List<Double> {
    val step = (max - min) / 5.0
    return (0..5).map { min + step * it }
}

private fun xTicks(start: Float, end: Float): List<Float> {
    val span = (end - start).coerceAtLeast(1f)
    val rawStep = span / 4f
    val niceStep = when {
        rawStep <= 1f -> 1f
        rawStep <= 2f -> 2f
        rawStep <= 5f -> 5f
        rawStep <= 10f -> 10f
        rawStep <= 15f -> 15f
        rawStep <= 30f -> 30f
        rawStep <= 60f -> 60f
        else -> ceil(rawStep / 60f) * 60f
    }
    val first = ceil(start / niceStep) * niceStep
    return generateSequence(first) { it + niceStep }.takeWhile { it <= end + .001f }.take(5).toList().ifEmpty { listOf(start, end) }
}

private fun formatLatencyAxis(value: Double): String {
    return if (value >= 1000.0) String.format(java.util.Locale.US, "%.1fs", value / 1000.0) else value.roundToInt().toString()
}

private fun formatSecondsAxis(sec: Float): String {
    return if (sec < 10f && sec % 1f != 0f) String.format(java.util.Locale.US, "%.1fs", sec) else "${sec.roundToInt()}s"
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb((alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
