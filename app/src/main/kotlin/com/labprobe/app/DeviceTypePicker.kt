package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun EditableDeviceTypeField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "设备类型",
    showLeadingIcon: Boolean = false
) {
    val groups = selectableDeviceTypeGroups()
    var expanded by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(deviceTypeDisplayName(value)) }

    Column(modifier) {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
        CompactTextField(
            value = text,
            onValueChange = { input ->
                text = input
                onChange(normalizeDeviceTypeToken(input).ifBlank { input.trim() })
            },
            leadingIcon = if (showLeadingIcon) ({
                Icon(Icons.Rounded.Category, null, Modifier.size(16.dp), tint = DEVICE_ICON_ACCENT)
            }) else null,
            trailingIcon = {
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = LAB_POPUP_SURFACE,
                tonalElevation = 0.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(1.dp, LAB_POPUP_BORDER),
                modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.78f)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("选择设备类型", fontSize = 18.sp, fontWeight = FontWeight.Black)
                        }
                        IconButton(onClick = { expanded = false }) { Icon(Icons.Rounded.Close, "关闭") }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(94.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)
                    ) {
                        groups.forEach { group ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(group.name, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp), color = DEVICE_ICON_ACCENT, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            }
                            items(group.items, key = { it.id }) { rule ->
                                val selected = normalizeDeviceTypeToken(value) == rule.id
                                Surface(
                                    shape = RoundedCornerShape(17.dp),
                                    color = if (selected) Color(0xFFEAF7FA) else LAB_POPUP_SUBTLE,
                                    border = BorderStroke(1.dp, if (selected) DEVICE_ICON_ACCENT.copy(alpha = .32f) else LAB_POPUP_BORDER),
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).clickable {
                                        text = rule.label
                                        onChange(rule.id)
                                        expanded = false
                                    }
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 7.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        DeviceTypeIconPreview(rule, 30)
                                        Spacer(Modifier.width(2.dp))
                                        Text(rule.label, fontSize = 10.2.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
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
