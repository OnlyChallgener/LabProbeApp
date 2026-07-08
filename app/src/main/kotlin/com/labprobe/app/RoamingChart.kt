package com.labprobe.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private data class RoamChartValue(val index: Int, val value: Double?, val isLoss: Boolean = false)

@Composable
fun LabRoamCharts(
    samples: List<WifiSample>,
    running: Boolean,
    modifier: Modifier = Modifier,
    events: List<RoamEvent> = emptyList()
) {
    val rssiValues = remember(samples) {
        samples.mapIndexed { index, sample ->
            RoamChartValue(index, sample.rssi.takeIf { it > -120 }?.toDouble(), false)
        }
    }
    val gatewayLatencyValues = remember(samples) {
        samples.mapIndexed { index, sample ->
            RoamChartValue(index, sample.gatewayLatency?.toDouble(), sample.gatewayLost)
        }
    }
    val wanLatencyValues = remember(samples) {
        samples.mapIndexed { index, sample ->
            RoamChartValue(index, sample.wanLatency?.toDouble(), sample.wanLost)
        }
    }
    val fallbackLatencyValues = remember(samples) {
        samples.mapIndexed { index, sample ->
            RoamChartValue(index, sample.latency?.toDouble(), sample.lost)
        }
    }
    val hasGateway = gatewayLatencyValues.any { it.value != null }
    val hasWan = wanLatencyValues.any { it.value != null }
    val primaryLatency = when {
        hasGateway -> gatewayLatencyValues
        hasWan -> wanLatencyValues
        else -> fallbackLatencyValues
    }
    val secondaryLatency = if (hasGateway && hasWan) wanLatencyValues else emptyList()
    val latencyLegend = when {
        hasGateway && hasWan -> "网关 / 外网"
        hasGateway -> "网关"
        hasWan -> "外网"
        else -> "轻量稳定模式"
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PingStyleRoamChart(
            title = "信号强度 dBm",
            values = rssiValues,
            events = events,
            running = running,
            accent = Color(0xFF16A34A),
            emptyText = "无可用 RSSI",
            defaultRange = -90.0 to -30.0,
            yFormatter = { it.roundToInt().toString() },
            modifier = Modifier.fillMaxWidth().height(188.dp)
        )
        PingStyleRoamChart(
            title = "漫游延迟 ms",
            subtitle = latencyLegend,
            values = primaryLatency,
            secondaryValues = secondaryLatency,
            events = events,
            running = running,
            accent = Color(0xFF2563EB),
            secondaryAccent = Color(0xFF7C3AED),
            emptyText = "等待延迟样本",
            defaultRange = 0.0 to 100.0,
            floor = 0.0,
            yFormatter = { formatRoamAxisValue(it) },
            modifier = Modifier.fillMaxWidth().height(188.dp)
        )
    }
}

@Composable
private fun PingStyleRoamChart(
    title: String,
    values: List<RoamChartValue>,
    events: List<RoamEvent>,
    running: Boolean,
    accent: Color,
    emptyText: String,
    defaultRange: Pair<Double, Double>,
    modifier: Modifier = Modifier,
    floor: Double? = null,
    yFormatter: (Double) -> String,
    subtitle: String = "",
    secondaryValues: List<RoamChartValue> = emptyList(),
    secondaryAccent: Color = Color(0xFF7C3AED)
) {
    val scrollState = rememberScrollState()
    val scheme = MaterialTheme.colorScheme
    val combinedValues = remember(values, secondaryValues) { values + secondaryValues }
    val validValues = remember(combinedValues) { combinedValues.mapNotNull { it.value } }
    val axisValues = remember(combinedValues) { combinedValues.takeLast(360).mapNotNull { it.value }.ifEmpty { validValues } }
    val range = remember(axisValues, defaultRange, floor) { roamNiceRange(axisValues, defaultRange, floor) }
    val yMin = range.first
    val yMax = range.second.coerceAtLeast(yMin + 1.0)
    val totalSamples = maxOf(values.size, secondaryValues.size, 1)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
    ) {
        val axisWidth = 31.dp
        val baseWidth = maxWidth
        val plotViewportWidth = (baseWidth - axisWidth).coerceAtLeast(120.dp)
        val extraWidth = when {
            totalSamples <= 80 -> 0.dp
            totalSamples <= 300 -> ((totalSamples - 80) * 1.0f).dp
            totalSamples <= 1000 -> (220 + (totalSamples - 300) * .42f).dp
            else -> (520 + (totalSamples - 1000) * .20f).dp
        }
        val chartWidth = (plotViewportWidth + extraWidth).coerceAtLeast(plotViewportWidth)
        LaunchedEffect(totalSamples, running) {
            if (running && totalSamples > 60) scrollState.scrollTo(scrollState.maxValue)
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = .10f)),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    if (subtitle.isBlank()) title else "$title  ·  $subtitle",
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 6.dp).zIndex(5f),
                    fontSize = 10.2.sp,
                    fontWeight = FontWeight.Black,
                    color = scheme.onSurface.copy(alpha = .58f),
                    maxLines = 1
                )
                if (validValues.isEmpty()) {
                    Text(
                        emptyText,
                        modifier = Modifier.align(Alignment.Center),
                        color = scheme.onSurface.copy(alpha=.40f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.0.sp
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = axisWidth, end = 2.dp, top = 18.dp, bottom = 0.dp)
                        .horizontalScroll(scrollState)
                        .zIndex(1f)
                ) {
                    Canvas(Modifier.width(chartWidth).fillMaxHeight()) {
                        val fullW = size.width
                        val fullH = size.height
                        val bottomH = 16.dp.toPx()
                        val topH = 4.dp.toPx()
                        val rightPad = 2.dp.toPx()
                        val plotLeft = 0f
                        val plotTop = topH
                        val plotRight = fullW - rightPad
                        val plotBottom = fullH - bottomH
                        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
                        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
                        val faintGrid = Color(0xFF64748B).copy(alpha = 0.030f)
                        for (idx in 0 until 5) {
                            val ratio = idx / 4f
                            val x = plotLeft + ratio * plotW
                            drawLine(faintGrid, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
                        }
                        fun yFor(v: Double): Float {
                            val ratio = ((v - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)
                            return plotBottom - ratio * plotH
                        }
                        fun xFor(index: Int): Float {
                            val ratio = if (totalSamples <= 1) .5f else (index.toFloat() / (totalSamples - 1).toFloat()).coerceIn(0f, 1f)
                            return plotLeft + ratio * plotW
                        }
                        fun pointForEvent(index: Int): Offset? {
                            val primary = values.getOrNull(index)?.value
                            val secondary = secondaryValues.getOrNull(index)?.value
                            val value = primary ?: secondary
                            return value?.let { Offset(xFor(index), yFor(it)) }
                        }
                        fun drawWave(items: List<RoamChartValue>, color: Color, isRssi: Boolean, widthPx: Float) {
                            val linePoints = items.mapNotNull { item -> item.value?.let { Offset(xFor(item.index), yFor(it)) } }
                            if (linePoints.size >= 2) {
                                val path = Path().apply {
                                    moveTo(plotLeft, linePoints.first().y)
                                    lineTo(linePoints.first().x, linePoints.first().y)
                                    for (i in 1 until linePoints.size) {
                                        val p0 = linePoints[i - 1]
                                        val p1 = linePoints[i]
                                        if (isRssi) {
                                            lineTo(p1.x, p0.y)
                                            lineTo(p1.x, p1.y)
                                        } else {
                                            lineTo(p1.x, p1.y)
                                        }
                                    }
                                    lineTo(plotRight, linePoints.last().y)
                                }
                                drawPath(path, color, style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            } else if (linePoints.size == 1) {
                                drawCircle(color, radius = 1.6.dp.toPx(), center = linePoints.first())
                            }
                        }

                        drawWave(values, accent, title.contains("信号"), 1.25f)
                        if (secondaryValues.any { it.value != null }) {
                            drawWave(secondaryValues, secondaryAccent.copy(alpha = .82f), false, 1.05f)
                        }

                        events.forEach { event ->
                            if (event.title.contains("丢包")) return@forEach
                            val point = pointForEvent(event.index.coerceIn(0, totalSamples - 1)) ?: return@forEach
                            val color = when (event.level) {
                                "bad" -> Color(0xFFEF4444)
                                "warn" -> Color(0xFFF59E0B)
                                "good" -> Color(0xFF16A34A)
                                else -> Color(0xFF7C3AED)
                            }
                            val bottom = plotBottom - 2.dp.toPx()
                            val top = point.y.coerceIn(plotTop + 2.dp.toPx(), plotBottom - 3.dp.toPx())
                            drawLine(
                                color.copy(alpha = .24f),
                                Offset(point.x, bottom),
                                Offset(point.x, top),
                                strokeWidth = 0.65.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }

                        combinedValues.forEach { item ->
                            if (item.isLoss) {
                                val x = xFor(item.index)
                                // 丢包使用底部超短超细红线，只做断点提示，不遮挡波形。
                                val lossBottom = plotBottom - 2.dp.toPx()
                                val lossTop = lossBottom - 3.2.dp.toPx()
                                drawLine(
                                    Color(0xFFEF4444).copy(alpha = .72f),
                                    Offset(x, lossTop),
                                    Offset(x, lossBottom),
                                    strokeWidth = 0.72.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 0.dp, end = 2.dp, top = 18.dp, bottom = 0.dp)
                        .zIndex(2f)
                ) {
                    val fullW = size.width
                    val fullH = size.height
                    val labelW = axisWidth.toPx()
                    val bottomH = 16.dp.toPx()
                    val topH = 4.dp.toPx()
                    val rightPad = 2.dp.toPx()
                    val plotLeft = labelW
                    val plotTop = topH
                    val plotRight = fullW - rightPad
                    val plotBottom = fullH - bottomH
                    val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
                    drawRect(scheme.surface.copy(alpha = 0.96f), topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(labelW, fullH))
                    val grid = Color(0xFF64748B).copy(alpha = 0.068f)
                    val labelColor = android.graphics.Color.argb(210, 71, 85, 105)
                    val yPaint = Paint().apply {
                        color = labelColor
                        textSize = 7.0.sp.toPx()
                        isFakeBoldText = true
                        isAntiAlias = true
                        textAlign = Paint.Align.LEFT
                    }
                    val ticks = roamTicks(yMin, yMax)
                    ticks.forEach { tick ->
                        val yRatio = ((tick - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)
                        val y = plotBottom - yRatio * plotH
                        drawLine(grid, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
                        drawContext.canvas.nativeCanvas.drawText(yFormatter(tick), 4.dp.toPx(), y + 2.5f, yPaint)
                    }
                    val xPaint = Paint().apply {
                        color = labelColor
                        textSize = 7.0.sp.toPx()
                        isFakeBoldText = true
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    }
                    val visiblePlotW = (plotRight - plotLeft).coerceAtLeast(1f)
                    val contentPlotW = (chartWidth.toPx() - 2.dp.toPx()).coerceAtLeast(visiblePlotW)
                    val maxScrollPx = (contentPlotW - visiblePlotW).coerceAtLeast(0f)
                    val scrollPx = scrollState.value.toFloat().coerceIn(0f, maxScrollPx)
                    val startRatio = (scrollPx / contentPlotW).coerceIn(0f, 1f)
                    val endRatio = ((scrollPx + visiblePlotW) / contentPlotW).coerceIn(0f, 1f)
                    val totalSec = (totalSamples - 1).coerceAtLeast(1).toFloat()
                    val startSec = totalSec * startRatio
                    val visibleSec = (totalSec * endRatio - startSec).coerceAtLeast(1f)
                    val xGrid = Color(0xFF64748B).copy(alpha = 0.030f)
                    for (idx in 0 until 5) {
                        val ratio = idx / 4f
                        val x = plotLeft + ratio * visiblePlotW
                        drawLine(xGrid, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
                        val label = formatRoamSeconds(startSec + visibleSec * ratio)
                        val labelX = when (idx) {
                            0 -> (x + 6.dp.toPx()).coerceAtMost(plotRight)
                            4 -> (x - 7.dp.toPx()).coerceAtLeast(plotLeft)
                            else -> x
                        }
                        drawContext.canvas.nativeCanvas.drawText(label, labelX, plotBottom + 12.dp.toPx(), xPaint)
                    }
                }
            }
        }
    }
}

private fun roamNiceRange(values: List<Double>, defaultRange: Pair<Double, Double>, floor: Double? = null): Pair<Double, Double> {
    if (values.isEmpty()) return defaultRange
    val min = values.minOrNull() ?: defaultRange.first
    val max = values.maxOrNull() ?: defaultRange.second
    val span = (max - min).coerceAtLeast(4.0)
    var low = min - span * .25
    var high = max + span * .25
    floor?.let { low = kotlin.math.max(it, low) }
    if (high <= low) high = low + 1.0
    return low to high
}

private fun roamTicks(min: Double, max: Double): List<Double> {
    val step = (max - min) / 5.0
    return (0..5).map { min + step * it }
}

private fun formatRoamAxisValue(value: Double): String {
    return if (value >= 1000.0) String.format(java.util.Locale.US, "%.1fs", value / 1000.0) else value.roundToInt().toString()
}

private fun formatRoamSeconds(sec: Float): String {
    return if (sec < 10f && sec % 1f != 0f) String.format(java.util.Locale.US, "%.1fs", sec) else "${sec.roundToInt()}s"
}
