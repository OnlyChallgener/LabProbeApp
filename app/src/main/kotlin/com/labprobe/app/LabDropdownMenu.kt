package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

private val LocalActionMenu = staticCompositionLocalOf { false }

/** Presentation-only dropdown contract. Material still positions/flips/scrolls the popup. */
@Composable
fun LabDropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier, offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = LabPopupShapes.Menu,
    minWidth: Dp = 64.dp,
    containerColor: Color = Color.White, tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 4.dp, border: BorderStroke? = null,
    actionMenu: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded, onDismissRequest,
        modifier.widthIn(min = if (actionMenu) 88.dp else minWidth).heightIn(max = 360.dp), offset,
        scrollState, properties, if (actionMenu) LabPopupShapes.Card else shape,
        if (actionMenu) Color.White else containerColor, tonalElevation,
        if (actionMenu) 6.dp else shadowElevation, if (actionMenu) null else border) {
        CompositionLocalProvider(LocalActionMenu provides actionMenu) {
            if (actionMenu) Column(Modifier.padding(horizontal = 8.dp), content = content)
            else content()
        }
    }
}

/** Action rows follow HUB's 34dp sizing; compact selectors retain 24dp rows. */
@Composable
fun LabDropdownMenuItem(
    text: @Composable () -> Unit, onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") leadingIcon: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") colors: MenuItemColors = MenuDefaults.itemColors(),
    @Suppress("UNUSED_PARAMETER") contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    interactionSource: MutableInteractionSource? = null,
) {
    val actionMenu = LocalActionMenu.current
    Row(modifier.fillMaxWidth().heightIn(min = if (actionMenu) 34.dp else 24.dp).clip(RoundedCornerShape(8.dp))
        .clickable(interactionSource = interactionSource, indication = ripple(), enabled = enabled,
            role = Role.Button, onClick = onClick)
        .padding(horizontal = if (actionMenu) 10.dp else 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ProvideTextStyle(LabTypography.Body.copy(fontSize = if (actionMenu) 14.sp else 13.sp,
                lineHeight = if (actionMenu) 20.sp else 18.sp,
                textAlign = TextAlign.Center, color = if (enabled) LabV2.Ink else LabV2.InkMuted)) { text() }
        }
    }
}
