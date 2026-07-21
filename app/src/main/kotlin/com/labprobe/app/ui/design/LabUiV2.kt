package com.labprobe.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * LabProbe UI V2 design tokens.
 *
 * This file intentionally contains only presentation primitives. Network tests,
 * device identification, Hub APIs, charts and router logic must not depend on it.
 */
object LabV2 {
    val Primary = Color(0xFF1769E0)
    val PrimaryStrong = Color(0xFF0E5BD8)
    val Cyan = Color(0xFF10A9C8)
    val Green = Color(0xFF16A36A)
    val Purple = Color(0xFF7456D8)
    val Amber = Color(0xFFF09A3E)
    val Red = Color(0xFFE74B55)

    val Ink = Color(0xFF142033)
    val InkMuted = Color(0xFF6B778A)
    val InkFaint = Color(0xFF95A1B3)

    val BackgroundTop = Color(0xFFEAF3FF)
    val BackgroundMid = Color(0xFFF2F7FD)
    val BackgroundBottom = Color(0xFFFBFDFF)

    val CardTop = Color(0xFFFCFEFF)
    val CardBottom = Color(0xFFF3F7FC)
    val Field = Color(0xFFFFFFFF)
    val FieldSoft = Color(0xFFF7FAFE)
    val Border = Color(0xFFDDE7F2)
    val BorderStrong = Color(0xFFCAD8E8)

    val PageHorizontal = 14.dp
    val PageTop = 8.dp
    val SectionGap = 10.dp
    val CardGap = 8.dp
    val ListGap = 7.dp
    val CardHorizontal = 12.dp
    val CardVertical = 9.dp
    val RowGap = 6.dp
    val FieldHeight = 48.dp

    val CardShape = RoundedCornerShape(22.dp)
    val CompactCardShape = RoundedCornerShape(18.dp)
    val FieldShape = RoundedCornerShape(14.dp)
    val MetricShape = RoundedCornerShape(14.dp)
    val ButtonShape = RoundedCornerShape(16.dp)
}

fun Modifier.labV2PageBackground(): Modifier = background(
    Brush.verticalGradient(
        0f to LabV2.BackgroundTop,
        .36f to LabV2.BackgroundMid,
        1f to LabV2.BackgroundBottom
    )
)

@Composable
fun LabV2Card(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = LabV2.CardHorizontal, vertical = LabV2.CardVertical),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = if (compact) LabV2.CompactCardShape else LabV2.CardShape
    Box(
        modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, clip = false, ambientColor = Color(0x1A4B6C91), spotColor = Color(0x164B6C91))
            .clip(shape)
            .background(Brush.linearGradient(listOf(LabV2.CardTop, LabV2.CardBottom)))
            .border(1.dp, Color.White.copy(alpha = .92f), shape)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(LabV2.RowGap),
            content = content
        )
    }
}

@Composable
fun CompactPageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compactTitle: Boolean = false
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = LabV2.Field,
                border = BorderStroke(1.dp, LabV2.Border)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowBack, null, Modifier.size(19.dp), tint = LabV2.Ink)
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = if (compactTitle) 19.sp else 21.sp, lineHeight = if (compactTitle) 22.sp else 24.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        action?.let {
            Spacer(Modifier.width(8.dp))
            it.invoke(this)
        }
    }
}

@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.height(LabV2.FieldHeight),
        shape = LabV2.FieldShape,
        color = if (enabled) LabV2.Field else LabV2.FieldSoft,
        border = BorderStroke(1.dp, LabV2.BorderStrong.copy(alpha = .8f)),
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            leadingIcon?.invoke()
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotBlank()) {
                            Text(placeholder, fontSize = 12.sp, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                }
            )
            trailingIcon?.invoke()
        }
    }
}

@Composable
fun CompactDropdown(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(LabV2.FieldHeight),
            shape = LabV2.FieldShape,
            color = LabV2.Field,
            border = BorderStroke(1.dp, LabV2.BorderStrong.copy(alpha = .8f))
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                leadingIcon?.invoke()
                Text(value, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp), tint = LabV2.InkMuted)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = LabV2.Field,
            shadowElevation = 10.dp
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 12.5.sp, fontWeight = if (option == value) FontWeight.Black else FontWeight.SemiBold) },
                    onClick = { expanded = false; onSelect(option) }
                )
            }
        }
    }
}

@Composable
fun CompactSegmentedControl(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) = LabV2SegmentedControl(options, selected, onSelect, modifier)

@Composable
fun CompactListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LabV2Card(modifier = modifier, compact = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp), content = content)
}

@Composable
fun CompactChartCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LabV2Card(modifier = modifier, compact = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), content = content)
}

@Composable
fun CompactPopup(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(22.dp),
            color = LabV2.CardTop,
            border = BorderStroke(1.dp, LabV2.Border),
            shadowElevation = 14.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun CompactBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    LabBottomSheet(onDismiss = onDismiss, scrollable = scrollable) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, null, Modifier.size(19.dp), tint = LabV2.InkMuted)
            }
        }
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun LabV2SectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = LabV2.Primary,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = .10f))
                    .border(1.dp, accent.copy(alpha = .08f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Black,
                color = LabV2.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 10.7.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LabV2.InkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }
        action?.let {
            Spacer(Modifier.width(8.dp))
            it.invoke(this)
        }
    }
}

@Composable
fun LabV2ToolIcon(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Int = 46
) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * .32f).dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = .16f),
                        accent.copy(alpha = .07f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = .88f), RoundedCornerShape((size * .32f).dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size((size * .48f).dp))
    }
}

@Composable
fun LabV2BottomNav(
    titles: List<String>,
    icons: List<ImageVector>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        color = Color(0xFFFBFDFF).copy(alpha = .98f),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, LabV2.Border.copy(alpha = .78f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            titles.forEachIndexed { index, title ->
                val active = index == selected
                val tint by animateColorAsState(
                    if (active) LabV2.Primary else LabV2.InkMuted,
                    animationSpec = tween(180),
                    label = "bottom-nav-tint"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) LabV2.Primary.copy(alpha = .10f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icons[index], title, tint = tint, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        title,
                        fontSize = 9.8.sp,
                        fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold,
                        color = tint,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun LabV2SegmentedControl(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = LabV2.Primary
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = LabV2.FieldSoft,
        border = BorderStroke(1.dp, LabV2.Border),
        tonalElevation = 0.dp
    ) {
        Row(Modifier.fillMaxWidth().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(option) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (active) accent else Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = if (active) 1.dp else 0.dp
                ) {
                    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            option,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            color = if (active) Color.White else LabV2.InkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LabV2Metric(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = LabV2.MetricShape,
        color = accent.copy(alpha = .065f),
        border = BorderStroke(1.dp, accent.copy(alpha = .10f)),
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(label, fontSize = 9.5.sp, color = LabV2.InkMuted, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            val valueSize = when {
                value.length >= 18 -> 10.5.sp
                value.length >= 13 -> 11.5.sp
                value.length >= 9 -> 12.5.sp
                else -> 14.5.sp
            }
            Text(
                value,
                fontSize = valueSize,
                color = accent,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}
