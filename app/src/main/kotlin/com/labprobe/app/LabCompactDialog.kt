package com.labprobe.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Compact, content-sized center dialog. The action row stays visible for long content. */
@Composable
fun LabCompactAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") shape: Shape = LabV2.CardShape,
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Color.White,
    iconContentColor: Color = LabV2.Primary,
    titleContentColor: Color = LabV2.Ink,
    textContentColor: Color = LabV2.InkMuted,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(),
) {
    val cardShape = LabV2.CardShape
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = LabV2.Primary, secondary = LabV2.Cyan)) {
      Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .shadow(8.dp, cardShape)
                .clip(cardShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF7FAFF), Color(0xFFEEF9FB), Color.White)
                    )
                )
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            if (icon != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides iconContentColor) { icon() }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (title != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                        ProvideTextStyle(LabTypography.DialogTitle) { title() }
                    }
                }
                Spacer(Modifier.height(if (text != null) 18.dp else 14.dp))
            }
            if (text != null) {
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    CompositionLocalProvider(LocalContentColor provides textContentColor) {
                        ProvideTextStyle(LabTypography.DialogBody) { text() }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dismissButton != null) {
                    Box(
                        Modifier.weight(1f).heightIn(min = 38.dp),
                        contentAlignment = Alignment.Center,
                        propagateMinConstraints = true,
                    ) { dismissButton() }
                }
                Box(
                    Modifier.weight(1f).heightIn(min = 38.dp),
                    contentAlignment = Alignment.Center,
                    propagateMinConstraints = true,
                ) { confirmButton() }
            }
        }
      }
    }
}

/** Backing for existing custom center dialogs whose fields and actions have their own layout. */
@Composable
fun LabGradientDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = LabV2.CardShape,
    @Suppress("UNUSED_PARAMETER") color: Color = Color.White,
    contentColor: Color = LabV2.Ink,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 8.dp,
    @Suppress("UNUSED_PARAMETER") border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .shadow(shadowElevation.coerceAtLeast(8.dp), shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFF7FAFF), Color(0xFFEEF9FB), Color.White)))
    ) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = LabV2.Primary, secondary = LabV2.Cyan)) {
            CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
        }
    }
}
