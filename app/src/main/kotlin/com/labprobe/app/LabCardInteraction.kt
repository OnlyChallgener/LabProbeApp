package com.labprobe.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Indication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Rounded, faint blue perimeter feedback. No elevation, square plate or expanding ripple. */
fun Modifier.labCardCombinedClickable(
    enabled: Boolean = true,
    halo: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    @Suppress("UNUSED_PARAMETER") indication: Indication? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val strength by animateFloatAsState(if (pressed && enabled && halo) 1f else 0f, label = "card-perimeter-halo")
    this.drawWithContent {
        drawContent()
        drawCardHalo(strength)
    }.combinedClickable(
        interactionSource = source, indication = null, enabled = enabled,
        onClickLabel = onClickLabel, role = role, onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick, onDoubleClick = onDoubleClick, onClick = onClick,
    )
}

/** The existing followed-device drag gesture keeps its behavior and gets the same feedback. */
fun Modifier.labCardHeldHalo(active: Boolean): Modifier = composed {
    val strength by animateFloatAsState(if (active) 1f else 0f, label = "held-card-halo")
    drawWithContent { drawContent(); drawCardHalo(strength) }
}

private fun ContentDrawScope.drawCardHalo(strength: Float) {
        if (strength > .001f) {
            // Only the card perimeter is drawn; no rings over text or artwork.
            val inset = .6.dp.toPx()
            drawRoundRect(
                color = LabV2.Primary.copy(alpha = strength * .14f),
                topLeft = Offset(inset, inset),
                size = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(1.2.dp.toPx()),
            )
        }
}
