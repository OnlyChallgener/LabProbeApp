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
import androidx.compose.material3.RadioButton
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
    val groups = selectableDeviceTypeGroups()
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
            shape = RoundedCornerShape(18.dp),
            colors = labOutlinedColors(),
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
            modifier = Modifier.fillMaxWidth(.92f).heightIn(max = 420.dp)
        ) {
            groups.forEach { group ->
                Text(
                    group.name,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
                group.items.forEach { rule ->
                    val selected = normalizeDeviceTypeToken(value) == rule.id
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DeviceTypeIconPreview(rule, 34)
                                Spacer(Modifier.width(10.dp))
                                Text(rule.label, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                RadioButton(selected = selected, onClick = null)
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
}

@Composable
fun DeviceTypeIconPreview(rule: DeviceTypeRule, size: Int = 38) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(DEVICE_INFO_CARD_BACKGROUND),
        contentAlignment = Alignment.Center
    ) {
        LabMiniDeviceIcon(rule.iconKey, DEVICE_ICON_ACCENT, sizeDp = size)
    }
}

@Composable
fun DeviceTypeTextBadge(label: String, color: Color) {
    androidx.compose.material3.Surface(shape = RoundedCornerShape(99.dp), color = DEVICE_TYPE_BADGE_BACKGROUND, border = BorderStroke(1.dp, DEVICE_INFO_CARD_BORDER)) {
        Text(label, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = DEVICE_TYPE_BADGE_CONTENT, maxLines = 1)
    }
}
