package com.labprobe.app.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Shared fine-line trash can: lid, handle and two vertical ribs, without a backplate. */
object LabActionIcons {
    val Delete: ImageVector by lazy {
        ImageVector.Builder("LabDelete", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.35f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.5f, 6f); lineTo(20.5f, 6f)
                moveTo(9f, 6f); lineTo(9.5f, 3.5f); lineTo(14.5f, 3.5f); lineTo(15f, 6f)
                moveTo(5.5f, 8f); lineTo(6.2f, 20.5f); lineTo(17.8f, 20.5f); lineTo(18.5f, 8f)
                moveTo(10f, 9f); lineTo(10f, 17.5f)
                moveTo(14f, 9f); lineTo(14f, 17.5f)
            }
        }.build()
    }
}
