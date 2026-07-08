package com.labprobe.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * LabProbe Mini Device Icon Style
 *
 * 轻拟物设备缩略图：不画品牌，不用 Material 线稿，不做复杂 3D。
 * 每个图标都是轻量 Canvas 绘制，适合 LazyColumn 列表中大量复用。
 */
@Composable
fun LabMiniDeviceIcon(
    iconKey: String,
    accent: Color,
    modifier: Modifier = Modifier,
    sizeDp: Int = 44
) {
    Box(
        modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp * 0.34f).dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color.White, accent.copy(alpha = 0.10f))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.72f), RoundedCornerShape((sizeDp * 0.34f).dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size((sizeDp * 0.72f).dp)) {
            val w = size.width
            val h = size.height
            val key = iconKey.lowercase()
            val body = Color(0xFFF8FAFC)
            val edge = Color(0xFFCBD5E1)
            val ink = Color(0xFF334155)
            val soft = accent.copy(alpha = 0.22f)
            val stroke = Stroke(width = w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun rr(x: Float, y: Float, ww: Float, hh: Float, r: Float, color: Color = body, style: androidx.compose.ui.graphics.drawscope.DrawStyle = androidx.compose.ui.graphics.drawscope.Fill) {
                drawRoundRect(color, topLeft = Offset(x, y), size = Size(ww, hh), cornerRadius = CornerRadius(r, r), style = style)
            }
            fun line(a: Offset, b: Offset, color: Color = ink, sw: Float = w * 0.055f) {
                drawLine(color, a, b, strokeWidth = sw, cap = StrokeCap.Round)
            }
            fun port(x: Float, y: Float, ww: Float = w * .105f, hh: Float = h * .07f) {
                rr(x, y, ww, hh, w * .018f, accent.copy(alpha = .55f))
            }

            when (key) {
                "router" -> {
                    line(Offset(w*.22f,h*.24f), Offset(w*.18f,h*.02f), accent.copy(alpha=.65f), w*.045f)
                    line(Offset(w*.78f,h*.24f), Offset(w*.82f,h*.02f), accent.copy(alpha=.65f), w*.045f)
                    rr(w*.14f,h*.30f,w*.72f,h*.42f,w*.12f)
                    rr(w*.14f,h*.30f,w*.72f,h*.42f,w*.12f, edge, Stroke(w*.035f))
                    drawCircle(accent, radius = w*.045f, center = Offset(w*.36f,h*.51f))
                    drawCircle(accent.copy(alpha=.75f), radius = w*.035f, center = Offset(w*.51f,h*.51f))
                    port(w*.63f,h*.48f); port(w*.75f,h*.48f)
                    line(Offset(w*.34f,h*.82f), Offset(w*.66f,h*.82f), accent.copy(alpha=.25f), w*.08f)
                }
                "ap" -> {
                    drawCircle(body, radius = w*.36f, center = Offset(w*.50f,h*.50f))
                    drawCircle(edge, radius = w*.36f, center = Offset(w*.50f,h*.50f), style = Stroke(w*.035f))
                    drawCircle(accent.copy(alpha=.18f), radius = w*.24f, center = Offset(w*.50f,h*.50f))
                    drawCircle(accent, radius = w*.055f, center = Offset(w*.50f,h*.50f))
                }
                "ont" -> {
                    rr(w*.24f,h*.10f,w*.52f,h*.74f,w*.11f)
                    rr(w*.24f,h*.10f,w*.52f,h*.74f,w*.11f, edge, Stroke(w*.035f))
                    for (i in 0..2) drawCircle(accent.copy(alpha=.75f), radius = w*.032f, center = Offset(w*.39f,h*(.28f+i*.16f)))
                    port(w*.54f,h*.28f); port(w*.54f,h*.44f); port(w*.54f,h*.60f)
                }
                "nas" -> {
                    rr(w*.18f,h*.10f,w*.64f,h*.78f,w*.10f)
                    rr(w*.18f,h*.10f,w*.64f,h*.78f,w*.10f, edge, Stroke(w*.035f))
                    val bayW = w*.18f
                    for (i in 0..2) {
                        val x = w*(.27f+i*.16f)
                        rr(x,h*.22f,bayW,h*.44f,w*.035f, Color(0xFFE2E8F0))
                        rr(x,h*.22f,bayW,h*.44f,w*.035f, Color(0xFF94A3B8), Stroke(w*.018f))
                        drawCircle(accent, radius = w*.025f, center = Offset(x+bayW*.5f,h*.72f))
                    }
                    line(Offset(w*.30f,h*.81f), Offset(w*.70f,h*.81f), accent.copy(alpha=.30f), w*.055f)
                }
                "desktop" -> {
                    rr(w*.12f,h*.16f,w*.58f,h*.44f,w*.045f)
                    rr(w*.12f,h*.16f,w*.58f,h*.44f,w*.045f, edge, Stroke(w*.035f))
                    line(Offset(w*.41f,h*.61f), Offset(w*.41f,h*.72f), edge, w*.045f)
                    line(Offset(w*.27f,h*.76f), Offset(w*.55f,h*.76f), edge, w*.055f)
                    rr(w*.74f,h*.24f,w*.15f,h*.47f,w*.04f, Color(0xFFE2E8F0))
                    drawCircle(accent, radius = w*.022f, center = Offset(w*.815f,h*.62f))
                }
                "laptop" -> {
                    rr(w*.17f,h*.18f,w*.66f,h*.40f,w*.045f)
                    rr(w*.17f,h*.18f,w*.66f,h*.40f,w*.045f, edge, Stroke(w*.035f))
                    val p = Path().apply {
                        moveTo(w*.08f,h*.68f); lineTo(w*.92f,h*.68f); lineTo(w*.82f,h*.80f); lineTo(w*.18f,h*.80f); close()
                    }
                    drawPath(p, Color(0xFFE2E8F0))
                    line(Offset(w*.42f,h*.73f), Offset(w*.58f,h*.73f), accent.copy(alpha=.35f), w*.035f)
                }
                "mini_pc" -> {
                    rr(w*.22f,h*.22f,w*.56f,h*.50f,w*.10f)
                    rr(w*.22f,h*.22f,w*.56f,h*.50f,w*.10f, edge, Stroke(w*.035f))
                    for (i in 0..2) port(w*(.34f+i*.12f),h*.36f,w*.07f,h*.055f)
                    line(Offset(w*.34f,h*.56f), Offset(w*.66f,h*.56f), accent.copy(alpha=.45f), w*.045f)
                }
                "phone" -> {
                    rr(w*.32f,h*.08f,w*.36f,h*.78f,w*.08f)
                    rr(w*.32f,h*.08f,w*.36f,h*.78f,w*.08f, edge, Stroke(w*.035f))
                    drawCircle(accent.copy(alpha=.70f), radius = w*.025f, center = Offset(w*.50f,h*.77f))
                    line(Offset(w*.44f,h*.15f), Offset(w*.56f,h*.15f), edge, w*.018f)
                }
                "tablet" -> {
                    rr(w*.12f,h*.24f,w*.76f,h*.48f,w*.08f)
                    rr(w*.12f,h*.24f,w*.76f,h*.48f,w*.08f, edge, Stroke(w*.035f))
                    drawCircle(accent.copy(alpha=.65f), radius = w*.021f, center = Offset(w*.82f,h*.48f))
                }
                "watch" -> {
                    rr(w*.36f,h*.20f,w*.28f,h*.50f,w*.10f)
                    rr(w*.36f,h*.20f,w*.28f,h*.50f,w*.10f, edge, Stroke(w*.03f))
                    line(Offset(w*.50f,h*.08f), Offset(w*.50f,h*.20f), edge, w*.09f)
                    line(Offset(w*.50f,h*.70f), Offset(w*.50f,h*.84f), edge, w*.09f)
                    drawCircle(accent.copy(alpha=.55f), radius = w*.065f, center = Offset(w*.50f,h*.45f))
                }
                "switch" -> {
                    rr(w*.10f,h*.30f,w*.80f,h*.34f,w*.055f)
                    rr(w*.10f,h*.30f,w*.80f,h*.34f,w*.055f, edge, Stroke(w*.03f))
                    for (row in 0..1) for (i in 0..5) port(w*(.20f+i*.10f), h*(.40f+row*.11f), w*.055f, h*.045f)
                }
                "printer" -> {
                    rr(w*.22f,h*.15f,w*.56f,h*.22f,w*.04f, Color(0xFFF8FAFC))
                    rr(w*.14f,h*.36f,w*.72f,h*.34f,w*.08f)
                    rr(w*.14f,h*.36f,w*.72f,h*.34f,w*.08f, edge, Stroke(w*.035f))
                    rr(w*.27f,h*.58f,w*.46f,h*.22f,w*.035f, Color(0xFFE2E8F0))
                    drawCircle(accent, radius = w*.028f, center = Offset(w*.72f,h*.47f))
                }
                "tv", "tv_box", "projector" -> {
                    if (key == "projector") {
                        rr(w*.18f,h*.34f,w*.64f,h*.28f,w*.08f)
                        rr(w*.18f,h*.34f,w*.64f,h*.28f,w*.08f, edge, Stroke(w*.035f))
                        drawCircle(accent.copy(alpha=.55f), radius = w*.07f, center = Offset(w*.34f,h*.48f))
                    } else if (key == "tv_box") {
                        rr(w*.22f,h*.30f,w*.56f,h*.34f,w*.08f)
                        rr(w*.22f,h*.30f,w*.56f,h*.34f,w*.08f, edge, Stroke(w*.035f))
                        line(Offset(w*.38f,h*.48f), Offset(w*.62f,h*.48f), accent.copy(alpha=.45f), w*.05f)
                    } else {
                        rr(w*.14f,h*.18f,w*.72f,h*.48f,w*.045f)
                        rr(w*.14f,h*.18f,w*.72f,h*.48f,w*.045f, edge, Stroke(w*.035f))
                        line(Offset(w*.50f,h*.67f), Offset(w*.50f,h*.76f), edge, w*.04f)
                        line(Offset(w*.35f,h*.79f), Offset(w*.65f,h*.79f), edge, w*.05f)
                    }
                }
                "camera", "doorbell" -> {
                    drawCircle(body, radius = w*.32f, center = Offset(w*.50f,h*.50f))
                    drawCircle(edge, radius = w*.32f, center = Offset(w*.50f,h*.50f), style = Stroke(w*.035f))
                    drawCircle(ink.copy(alpha=.18f), radius = w*.20f, center = Offset(w*.50f,h*.50f))
                    drawCircle(accent.copy(alpha=.75f), radius = w*.10f, center = Offset(w*.50f,h*.50f))
                    drawCircle(Color.White.copy(alpha=.8f), radius = w*.025f, center = Offset(w*.46f,h*.45f))
                }
                "speaker" -> {
                    rr(w*.28f,h*.12f,w*.44f,h*.74f,w*.10f)
                    rr(w*.28f,h*.12f,w*.44f,h*.74f,w*.10f, edge, Stroke(w*.035f))
                    drawCircle(accent.copy(alpha=.24f), radius = w*.16f, center = Offset(w*.50f,h*.42f))
                    drawCircle(accent.copy(alpha=.55f), radius = w*.075f, center = Offset(w*.50f,h*.42f))
                    line(Offset(w*.40f,h*.68f), Offset(w*.60f,h*.68f), accent.copy(alpha=.35f), w*.035f)
                }
                "aircon" -> {
                    rr(w*.13f,h*.22f,w*.74f,h*.28f,w*.08f)
                    rr(w*.13f,h*.22f,w*.74f,h*.28f,w*.08f, edge, Stroke(w*.03f))
                    line(Offset(w*.24f,h*.57f), Offset(w*.76f,h*.57f), accent.copy(alpha=.35f), w*.035f)
                    line(Offset(w*.32f,h*.68f), Offset(w*.28f,h*.78f), accent.copy(alpha=.35f), w*.03f)
                    line(Offset(w*.50f,h*.68f), Offset(w*.50f,h*.80f), accent.copy(alpha=.35f), w*.03f)
                    line(Offset(w*.68f,h*.68f), Offset(w*.72f,h*.78f), accent.copy(alpha=.35f), w*.03f)
                }
                "heater" -> {
                    rr(w*.20f,h*.18f,w*.60f,h*.56f,w*.12f)
                    rr(w*.20f,h*.18f,w*.60f,h*.56f,w*.12f, edge, Stroke(w*.035f))
                    line(Offset(w*.34f,h*.36f), Offset(w*.66f,h*.36f), accent.copy(alpha=.40f), w*.04f)
                    line(Offset(w*.40f,h*.51f), Offset(w*.60f,h*.51f), accent.copy(alpha=.26f), w*.035f)
                    drawCircle(accent, radius = w*.03f, center = Offset(w*.50f,h*.66f))
                }
                "fridge" -> {
                    rr(w*.28f,h*.10f,w*.44f,h*.76f,w*.08f)
                    rr(w*.28f,h*.10f,w*.44f,h*.76f,w*.08f, edge, Stroke(w*.035f))
                    line(Offset(w*.28f,h*.46f), Offset(w*.72f,h*.46f), edge, w*.025f)
                    line(Offset(w*.62f,h*.25f), Offset(w*.62f,h*.38f), accent.copy(alpha=.45f), w*.028f)
                    line(Offset(w*.62f,h*.56f), Offset(w*.62f,h*.70f), accent.copy(alpha=.45f), w*.028f)
                }
                "washer" -> {
                    rr(w*.24f,h*.10f,w*.52f,h*.76f,w*.08f)
                    rr(w*.24f,h*.10f,w*.52f,h*.76f,w*.08f, edge, Stroke(w*.035f))
                    drawCircle(edge.copy(alpha=.45f), radius = w*.19f, center = Offset(w*.50f,h*.53f))
                    drawCircle(accent.copy(alpha=.25f), radius = w*.13f, center = Offset(w*.50f,h*.53f))
                    drawCircle(accent, radius = w*.022f, center = Offset(w*.62f,h*.22f))
                }
                "light" -> {
                    drawCircle(accent.copy(alpha=.20f), radius = w*.20f, center = Offset(w*.50f,h*.36f))
                    line(Offset(w*.39f,h*.57f), Offset(w*.61f,h*.57f), edge, w*.06f)
                    line(Offset(w*.43f,h*.68f), Offset(w*.57f,h*.68f), edge, w*.05f)
                }
                "lock" -> {
                    rr(w*.30f,h*.40f,w*.40f,h*.34f,w*.06f)
                    rr(w*.30f,h*.40f,w*.40f,h*.34f,w*.06f, edge, Stroke(w*.035f))
                    drawArc(edge, 205f, 130f, false, topLeft = Offset(w*.34f,h*.17f), size = Size(w*.32f,h*.35f), style = Stroke(w*.055f, cap = StrokeCap.Round))
                    drawCircle(accent, radius = w*.035f, center = Offset(w*.50f,h*.55f))
                }
                "socket" -> {
                    rr(w*.22f,h*.18f,w*.56f,h*.56f,w*.12f)
                    rr(w*.22f,h*.18f,w*.56f,h*.56f,w*.12f, edge, Stroke(w*.035f))
                    drawCircle(ink.copy(alpha=.40f), radius = w*.025f, center = Offset(w*.42f,h*.46f))
                    drawCircle(ink.copy(alpha=.40f), radius = w*.025f, center = Offset(w*.58f,h*.46f))
                    line(Offset(w*.50f,h*.54f), Offset(w*.50f,h*.64f), accent.copy(alpha=.45f), w*.025f)
                }
                else -> {
                    rr(w*.18f,h*.18f,w*.64f,h*.64f,w*.16f)
                    rr(w*.18f,h*.18f,w*.64f,h*.64f,w*.16f, edge, Stroke(w*.035f))
                    drawCircle(accent.copy(alpha=.65f), radius = w*.09f, center = Offset(w*.50f,h*.50f))
                    line(Offset(w*.35f,h*.35f), Offset(w*.65f,h*.65f), accent.copy(alpha=.30f), w*.04f)
                    line(Offset(w*.65f,h*.35f), Offset(w*.35f,h*.65f), accent.copy(alpha=.30f), w*.04f)
                }
            }
        }
    }
}
