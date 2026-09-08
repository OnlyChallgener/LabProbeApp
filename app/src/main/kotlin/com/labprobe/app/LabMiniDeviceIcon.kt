package com.labprobe.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.labprobe.app.ui.design.LabMaterialPolish

@Composable
fun LabMiniDeviceIcon(
    iconKey: String,
    accent: Color,
    modifier: Modifier = Modifier,
    sizeDp: Int = 44
) {
    val polished = LabMaterialPolish.enabled
    val cornerRadius = (sizeDp * 0.28f).coerceAtLeast(8f).dp
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(shape)
            .then(
                if (polished) {
                    Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFFF6FAFE), Color(0xFFEBF3FB))
                            )
                        )
                        .border(1.dp, Color(0xFFE0EDF8), shape)
                } else {
                    Modifier
                        .background(accent.copy(alpha = 0.08f))
                        .border(1.dp, accent.copy(alpha = 0.12f), shape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(deviceIconDrawable(iconKey)),
            contentDescription = null,
            modifier = Modifier
                .size((sizeDp * 0.78f).dp)
                .clip(shape),
            contentScale = ContentScale.Fit
        )
    }
}
