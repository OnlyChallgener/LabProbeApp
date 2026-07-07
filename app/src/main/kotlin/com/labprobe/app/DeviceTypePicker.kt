package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditableDeviceTypeField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "设备类型"
) {
    val options = selectableDeviceTypes()
    var expanded by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(deviceTypeDisplayName(value)) }

    Box(modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                onChange(normalizeDeviceTypeToken(input).ifBlank { input.trim() })
            },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 240.dp) // 约 5 行，可上下滑动
        ) {
            options.forEach { rule ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DeviceTypeIconPreview(rule, 30)
                            Spacer(Modifier.width(8.dp))
                            Text(rule.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = {
                        text = rule.label
                        onChange(rule.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun DeviceTypeIconPreview(rule: DeviceTypeRule, size: Int = 38) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(rule.accent.copy(alpha = .12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(deviceTypeIcon(rule.iconKey), null, tint = rule.accent, modifier = Modifier.size((size * 0.52f).dp))
    }
}

@Composable
fun DeviceTypeTextBadge(label: String, color: Color) {
    androidx.compose.material3.Surface(shape = RoundedCornerShape(99.dp), color = color.copy(alpha = .12f), border = BorderStroke(1.dp, color.copy(alpha = .10f))) {
        Text(label, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1)
    }
}
