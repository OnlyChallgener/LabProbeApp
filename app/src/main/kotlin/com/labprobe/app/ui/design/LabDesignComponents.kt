package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LAB_POPUP_SURFACE = Color(0xFFFAFCFF)
val LAB_POPUP_SUBTLE = Color(0xFFF3F7FC)
val LAB_POPUP_BORDER = Color(0xFFDDE7F2)
val LAB_POPUP_SCRIM = Color(0xFF0F172A)
val LAB_POPUP_HANDLE = Color(0xFF1E293B)

@Composable
fun LabCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    LabV2Card(modifier = modifier, content = content)
}

@Composable
fun LabStatusBadge(online: Boolean, modifier: Modifier = Modifier) {
    val color = if (online) Color(0xFF16A34A) else Color(0xFF64748B)
    Surface(modifier = modifier, shape = RoundedCornerShape(99.dp), color = color.copy(alpha = .12f)) {
        Text(
            if (online) "在线" else "离线",
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
    }
}

@Composable
fun LabIconBox(icon: ImageVector, accent: Color, modifier: Modifier = Modifier, sizeDp: Int = 42, iconKey: String = "") {
    if (iconKey.isNotBlank()) {
        LabMiniDeviceIcon(iconKey, accent, modifier = modifier, sizeDp = sizeDp)
    } else {
        LabV2ToolIcon(icon = icon, accent = accent, modifier = modifier, size = sizeDp)
    }
}

@Composable
fun LabSection(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
            action?.invoke(this)
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = LabV2.FieldSoft,
            border = BorderStroke(1.dp, LabV2.Border)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
        }
    }
}

@Composable
fun LabInfoRow(
    label: String,
    value: String?,
    copyable: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val ctx = LocalContext.current
    val cleaned = value?.takeIf { it.isNotBlank() } ?: "--"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .50f), maxLines = 1)
        Text(
            cleaned,
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .clickable(enabled = copyable && cleaned != "--") { copy(ctx, cleaned) },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (cleaned == "--") MaterialTheme.colorScheme.onSurface.copy(alpha = .35f) else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        if (copyable && cleaned != "--") {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(14.dp), tint = accent.copy(alpha = .55f))
        }
    }
}

@Composable
fun LabActionChip(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AssistChip(
        modifier = modifier,
        onClick = onClick,
        label = { Text(text, fontSize = 11.sp, fontWeight = FontWeight.Black) },
        border = BorderStroke(1.dp, color.copy(alpha = .18f)),
        leadingIcon = {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabBottomSheet(onDismiss: () -> Unit, scrollable: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val contentModifier = if (scrollable) {
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = !scrollable,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        containerColor = LAB_POPUP_SURFACE,
        scrimColor = LAB_POPUP_SCRIM.copy(alpha = .38f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(LAB_POPUP_HANDLE.copy(alpha = .82f))
            )
        }
    ) {
        Column(
            contentModifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}
