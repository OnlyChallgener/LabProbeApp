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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun LabCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
        border = BorderStroke(1.dp, accent.copy(alpha = .08f)),
        shadowElevation = 1.dp,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
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
fun LabIconBox(icon: ImageVector, accent: Color, modifier: Modifier = Modifier, sizeDp: Int = 42) {
    Box(
        modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp * .38f).dp))
            .background(accent.copy(alpha = .12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size((sizeDp * .48f).dp))
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
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
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
fun LabBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}
