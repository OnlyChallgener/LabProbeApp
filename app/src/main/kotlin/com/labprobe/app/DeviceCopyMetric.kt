package com.labprobe.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Exact-arity overload used by the device list metrics.
 *
 * The original fallback remains in MainActivity for source compatibility. Device cards resolve
 * to this overload, which keeps the same layout and copy action while removing the default
 * full-row grey indication. Successful copies use an in-place check/tint so dimensions do not
 * change and no surrounding card or metric styling is affected.
 */
@Composable
fun DeviceMiniMetric(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier,
    copyValue: String,
    allowScroll: Boolean,
) {
    DeviceCopyMetric(
        label = label,
        value = value,
        icon = icon,
        color = color,
        modifier = modifier,
        copyValue = copyValue,
        allowScroll = allowScroll,
        valueColor = null,
    )
}

/** Non-null valueColor overload covers the signal metric without changing its normal-state color. */
@Composable
fun DeviceMiniMetric(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier,
    copyValue: String,
    allowScroll: Boolean,
    valueColor: Color,
) {
    DeviceCopyMetric(
        label = label,
        value = value,
        icon = icon,
        color = color,
        modifier = modifier,
        copyValue = copyValue,
        allowScroll = allowScroll,
        valueColor = valueColor,
    )
}

@Composable
private fun DeviceCopyMetric(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier,
    copyValue: String,
    allowScroll: Boolean,
    valueColor: Color?,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }

    Row(
        modifier
            .clickable(
                enabled = copyValue.isNotBlank(),
                interactionSource = interactionSource,
                indication = null,
            ) {
                copy(ctx, copyValue)
                resetJob?.cancel()
                copied = true
                resetJob = scope.launch {
                    delay(650)
                    copied = false
                }
            }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LabCoreSurface.Inner),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else icon,
                contentDescription = null,
                tint = if (copied) LabV2.Primary else color,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = LabTypography.Caption.fontSize,
                lineHeight = LabTypography.Caption.lineHeight,
                fontWeight = FontWeight.Medium,
                color = LabV2.InkFaint,
                maxLines = 1,
            )
            val textModifier = if (allowScroll && value != "--") {
                Modifier.horizontalScroll(rememberScrollState())
            } else {
                Modifier
            }
            Text(
                text = value,
                modifier = textModifier,
                fontSize = LabTypography.Supporting.fontSize,
                lineHeight = LabTypography.Supporting.lineHeight,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    value == "--" -> LabV2.InkFaint
                    copied -> LabV2.Primary
                    else -> valueColor ?: LabV2.Ink
                },
                maxLines = 1,
                overflow = if (allowScroll) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
        }
    }
}
