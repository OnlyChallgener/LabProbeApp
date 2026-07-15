package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LabDeviceEditSheet(device: DeviceItem, state: AppState, onDismiss: () -> Unit) {
    val override = remember(device.mac, state.deviceOverrides) { overrideForDevice(device, state.deviceOverrides) }
    val managedWol = remember(device.mac, state.wolDevices) { state.wolDevices.firstOrNull { it.mac.equals(device.mac, ignoreCase = true) } }
    var remark by remember(override) { mutableStateOf(override.remark.ifBlank { device.remark.ifBlank { device.name } }) }
    var typeInput by remember(override) { mutableStateOf(override.typeId.ifBlank { inferDeviceProfile(device).type }) }
    var followed by remember(override) { mutableStateOf(override.followedOverride ?: device.followedOverride ?: false) }
    var wolOverride by remember(override, managedWol?.enabled) { mutableStateOf(override.wolEnabledOverride ?: managedWol?.enabled) }
    val rule = deviceTypeRuleForInput(typeInput)
    val normalizedType = normalizeDeviceTypeToken(typeInput).ifBlank { typeInput.trim() }
    val recommendation = if (normalizedType.isBlank() || normalizedType == "unknown" || normalizedType == "router") wolRecommendationForDevice(device, typeInput) else wolRecommendationForDeviceType(typeInput)
    LabBottomSheet(onDismiss = onDismiss) {
        Text("编辑设备", fontWeight = FontWeight.Black, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("备注名称") },
                placeholder = { Text("例如：海尔电热水器 / 美的空调 / 绿联 DH4300") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            EditableDeviceTypeField(
                value = typeInput,
                onChange = { typeInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = "设备类型（可输入/点箭头选择）"
            )
            OutlinedTextField(
                value = cleanMac(device.mac),
                onValueChange = {},
                label = { Text("MAC 地址") },
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = DEVICE_INFO_CARD_BACKGROUND, border = BorderStroke(1.dp, DEVICE_INFO_CARD_BORDER)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceTypeIconPreview(rule, 42)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("图标预览：${rule.label}", fontWeight = FontWeight.Black, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("按 MAC 本地保存。刷新后仍优先使用你的备注和类型。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), maxLines = 2)
                    }
                }
            }
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = DEVICE_INFO_CARD_BACKGROUND, border = BorderStroke(1.dp, DEVICE_INFO_CARD_BORDER)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("设备管理", fontWeight = FontWeight.Black, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .66f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("添加到关注", fontWeight = FontWeight.Black, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("关注后可在“关注”页快速查看", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .52f), maxLines = 2)
                        }
                        Switch(checked = followed, onCheckedChange = { followed = it })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("加入 WOL 管理", fontWeight = FontWeight.Black, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(recommendation.reason, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .52f), maxLines = 3)
                        }
                        FilterChip(selected = wolOverride == null, onClick = { wolOverride = null }, label = { Text("自动", fontSize = 11.sp) })
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = wolOverride ?: recommendation.recommended, onCheckedChange = { wolOverride = it })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消", fontWeight = FontWeight.Black) }
            Button(
                onClick = {
                    val selectedWol = wolOverride ?: recommendation.recommended
                    val clean = cleanMac(device.mac)
                    val savedType = normalizedType.ifBlank { "unknown" }
                    state.saveDeviceOverride(clean, remark, savedType, selectedWol, followed)
                    if (isValidMac(clean)) {
                        val existing = state.wolDevices.firstOrNull { it.mac.equals(clean, ignoreCase = true) }
                        if (existing != null) {
                            state.addOrUpdateWolDevice(existing.copy(remark = remark.trim(), typeId = savedType, enabled = selectedWol))
                        } else if (selectedWol) {
                            state.addOrUpdateWolDevice(
                                WolDeviceConfig(
                                    id = clean,
                                    mac = clean,
                                    remark = remark.trim().ifBlank { device.name.ifBlank { clean } },
                                    typeId = savedType,
                                    enabled = true
                                )
                            )
                        }
                    }
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DEVICE_ICON_ACCENT)
            ) { Text("保存", fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun LabDeviceDetailSheet(state: AppState, device: DeviceItem, onDismiss: () -> Unit) {
    val profile = remember(device) { inferDeviceProfile(device) }
    val wolManaged = remember(device.mac, state.wolDevices) { state.wolDevices.any { it.enabled && it.mac.equals(device.mac, ignoreCase = true) } }
    val ip4 = cleanApiText(device.ip).ifBlank { "--" }
    val v6 = remember(device.ipv6, device.ipv6Candidates) { device.pickIpv6().best.orEmpty() }
    val signal = remember(device.rssi) {
        cleanApiText(device.rssi).takeIf { it.isNotBlank() }?.let {
            if (it.endsWith("dBm", ignoreCase = true)) it else "$it dBm"
        } ?: "--"
    }
    val wifi = hasWifiInfo(device)
    var editing by remember { mutableStateOf(false) }
    LabBottomSheet(onDismiss = onDismiss, scrollable = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabIconBox(profile.icon, profile.accent, sizeDp = 48, iconKey = profile.iconKey)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(device.remark.ifBlank { device.name.ifBlank { device.mac } }, fontSize = 19.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${profile.label} · ${connectionLabel(device)}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LabStatusBadge(device.online)
        }

        LabSection("网络") {
            LabInfoRow("IPv4", ip4, accent = profile.accent)
            LabInfoRow("IPv6", v6.ifBlank { "--" }, accent = Color(0xFF06B6D4))
            LabInfoRow("MAC", device.mac, accent = profile.accent)
        }
        if (wifi) {
            LabSection("无线") {
                LabInfoRow("SSID", cleanApiText(device.ssid).ifBlank { "--" }, accent = Color(0xFF22C55E))
                LabInfoRow("频段", cleanApiText(device.band).ifBlank { "--" }, accent = Color(0xFF22C55E))
                LabInfoRow("信号", signal, accent = Color(0xFFF59E0B))
                LabInfoRow("速率", cleanApiText(device.rxrate).ifBlank { "--" }, accent = Color(0xFF22C55E))
            }
        }
        LabSection("设备") {
            LabInfoRow("备注", device.remark.ifBlank { "--" }, copyable = false, accent = profile.accent)
            LabInfoRow("类型", profile.label, copyable = false, accent = profile.accent)
            LabInfoRow("厂商", cleanApiText(device.manufacture).ifBlank { "--" }, copyable = false, accent = profile.accent)
            LabInfoRow("主机名", cleanApiText(device.hostName).ifBlank { "--" }, accent = profile.accent)
        }
        val totalUpload = cleanApiText(device.totalUpload)
        val totalDownload = cleanApiText(device.totalDownload)
        if (totalUpload.isNotBlank() || totalDownload.isNotBlank()) {
            LabSection("实时总流量") {
                Text("路由器本次开机以来累计", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DetailTrafficValue("上行", totalUpload.ifBlank { "--" }, Color(0xFFF59E0B), Modifier.weight(1f))
                    Spacer(Modifier.width(1.dp))
                    DetailTrafficValue("下行", totalDownload.ifBlank { "--" }, Color(0xFF06B6D4), Modifier.weight(1f))
                }
            }
        }
        if (profile.wolCandidate || wolManaged) {
            LabSection("能力") {
                LabInfoRow("WOL", if (wolManaged) "已加入管理" else "可加入管理", copyable = false, accent = Color(0xFF8B5CF6))
                LabInfoRow("关机", "未配置", copyable = false, accent = Color(0xFF64748B))
                LabInfoRow("重启", "未配置", copyable = false, accent = Color(0xFF64748B))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text("编辑", fontWeight = FontWeight.Black) }
            if (profile.wolCandidate && !wolManaged) {
                Button(
                    onClick = {
                        val clean = cleanMac(device.mac)
                        state.addOrUpdateWolDevice(
                            WolDeviceConfig(
                                id = clean,
                                mac = clean,
                                remark = device.remark.ifBlank { device.name.ifBlank { clean } },
                                typeId = profile.type,
                                enabled = true,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    },
                    enabled = isValidMac(device.mac),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                ) { Text("加入WOL", fontWeight = FontWeight.Black) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (editing) LabDeviceEditSheet(device = device, state = state, onDismiss = { editing = false })
}

@Composable
private fun DetailTrafficValue(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            color = if (value == "--") MaterialTheme.colorScheme.onSurface.copy(alpha = .35f) else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
