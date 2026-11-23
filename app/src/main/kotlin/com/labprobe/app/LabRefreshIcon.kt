package com.labprobe.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/** A fixed icon box: rotate only the glyph, always around its own center. */
@Composable
fun LabRefreshIcon(loading: Boolean, modifier: Modifier = Modifier, tint: Color = LabV2.Primary, description: String? = "刷新") {
    val angle = if (loading) {
        val transition = rememberInfiniteTransition(label = "refresh")
        val rotation by transition.animateFloat(0f, 360f,
            infiniteRepeatable(tween(900, easing = LinearEasing)), label = "refresh-angle")
        rotation
    } else 0f
    Icon(Icons.Rounded.Refresh, description, modifier.graphicsLayer {
        rotationZ = angle
        transformOrigin = TransformOrigin.Center
    }, tint = tint)
}
