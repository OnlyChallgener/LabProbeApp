package com.labprobe.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun DeviceDetailScreen(
    state: AppState,
    deviceMac: String?,
    onBack: () -> Unit,
    onOpenPortMap: () -> Unit,
    onOpenSsh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember(state.devices, state.onlineDevices) { mergeSharedDeviceState(state.devices, state.onlineDevices) }
    val device = remember(deviceMac, devices) { deviceMac?.let { mac -> devices.firstOrNull { it.mac.equals(mac, true) } } }
    if (device == null) {
        DetailShell("设备详情", "设备数据已刷新", onBack) {
            CompactListCard {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabV2ToolIcon(Icons.Rounded.DeviceUnknown, LabV2.InkMuted, size = 50)
                    Text("找不到该设备", fontWeight = FontWeight.Black, color = LabV2.Ink)
                    TextButton(onClick = onBack) { Text("返回设备列表") }
                }
            }
        }
        return
    }

    val profile = remember(device) { inferDeviceProfile(device) }
    val ipv6 = remember(device.ipv6, device.ipv6Candidates) { device.pickIpv6().best.orEmpty() }
    val wifi = hasWifiInfo(device)
    val signal = cleanApiText(device.rssi).takeIf { it.isNotBlank() }?.let { if (it.endsWith("dBm", true)) it else "$it dBm" } ?: "--"
    val rate = cleanApiText(device.rxrate).ifBlank { "--" }
    val band = if (wifi) cleanApiText(device.band).ifBlank { "Wi-Fi" } else "有线"
    val wifiName = if (wifi) cleanApiText(device.ssid) else ""
    val onlineTime = cleanApiText(device.onlineDurationText).takeIf { it.isNotBlank() }?.let(::formatDurationText).orEmpty().ifBlank { "--" }
    var editing by remember { mutableStateOf(false) }
    var waking by remember { mutableStateOf(false) }

    DetailShell("设备详情", "信息紧凑视图", onBack) {
        CompactListCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(112.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(profile.accent.copy(alpha = .065f))
                        .clickable { editing = true },
                    contentAlignment = Alignment.Center
                ) {
                    LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = 100)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(device.remark.ifBlank { device.name.ifBlank { device.mac } }, fontSize = 18.sp, lineHeight = 21.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LabStatusBadge(device.online)
                }
                Text(
                    listOf(cleanApiText(device.manufacture), profile.label, cleanApiText(device.hostName)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { profile.label },
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LabV2.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(cleanApiText(device.ip).ifBlank { ipv6.ifBlank { device.mac } }, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = LabV2.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        CompactListCard {
            Text("连接概览", fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DeviceDetailMetric("频段", band, LabV2.Primary, Modifier.weight(1f))
                DeviceDetailMetric("信号", signal, LabV2.Amber, Modifier.weight(1f))
                DeviceDetailMetric("速率", rate, LabV2.Green, Modifier.weight(1f))
            }
            if (wifiName.isNotBlank()) {
                Surface(shape = RoundedCornerShape(14.dp), color = LabV2.Primary.copy(alpha = .055f), border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Primary.copy(alpha = .09f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi, null, Modifier.size(15.dp), tint = LabV2.Primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Wi-Fi", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted)
                        Spacer(Modifier.width(8.dp))
                        Text(wifiName, Modifier.weight(1f), fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = LabV2.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DeviceDetailMetric("在线", onlineTime, LabV2.Green, Modifier.weight(1f))
                DeviceDetailMetric("今日上传", cleanApiText(device.todayUpload).ifBlank { "--" }, LabV2.Primary, Modifier.weight(1f))
                DeviceDetailMetric("今日下载", cleanApiText(device.todayDownload).ifBlank { "--" }, LabV2.Cyan, Modifier.weight(1f))
            }
        }

        CompactListCard {
            Text("地址信息", modifier = Modifier.fillMaxWidth(), fontSize = 13.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
            DeviceDetailAddress("IPv4", cleanApiText(device.ip).ifBlank { "--" }, LabV2.Primary)
            DeviceDetailAddress("IPv6", ipv6.ifBlank { "--" }, LabV2.Cyan, allowTwoLines = true)
            DeviceDetailAddress("MAC", cleanMac(device.mac).ifBlank { "--" }, profile.accent)
        }

        CompactListCard {
            Text("设备信息", modifier = Modifier.fillMaxWidth(), fontSize = 13.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DeviceDetailPair("类型", profile.label, Modifier.weight(1f))
                DeviceDetailPair("厂商", cleanApiText(device.manufacture).ifBlank { "--" }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DeviceDetailPair("主机名", cleanApiText(device.hostName).ifBlank { "--" }, Modifier.weight(1f))
                DeviceDetailPair("备注", cleanApiText(device.remark).ifBlank { "--" }, Modifier.weight(1f))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DeviceActionButton(Icons.Rounded.Power, if (waking) "发送中" else "WOL", Color(0xFF2563EB), Modifier.weight(1f), enabled = !waking) {
                if (device.online) {
                    toast(context, "设备当前在线")
                } else {
                    waking = true
                    scope.launch {
                        val result = runCatching { state.wakeDevice(context, device) }.getOrElse { "WOL失败：${it.message}" }
                        toast(context, result)
                        waking = false
                    }
                }
            }
            DeviceActionButton(Icons.Rounded.SwapHoriz, "端口映射", Color(0xFF0EA5E9), Modifier.weight(1f), onClick = onOpenPortMap)
            DeviceActionButton(Icons.Rounded.Terminal, "SSH", Color(0xFF64748B), Modifier.weight(1f), onClick = onOpenSsh)
            DeviceActionButton(Icons.Rounded.MoreHoriz, "更多", Color(0xFF7C3AED), Modifier.weight(1f)) { editing = true }
        }
    }

    if (editing) LabDeviceEditSheet(device = device, state = state, onDismiss = { editing = false })
}

@Composable
private fun DeviceDetailMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = LabV2.MetricShape, color = color.copy(alpha = .075f), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .10f))) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 9.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted, maxLines = 1)
            Text(value, modifier = Modifier.fillMaxWidth(), fontSize = if (value.length > 12) 10.5.sp else 12.sp, lineHeight = 13.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DeviceDetailAddress(label: String, value: String, color: Color, allowTwoLines: Boolean = false) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(enabled = value != "--") { copy(context, value) }, verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(54.dp).padding(top = 1.dp), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted)
        Text(value, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = if (value == "--") LabV2.InkFaint else color, maxLines = if (allowTwoLines) 2 else 1, overflow = TextOverflow.Clip)
        if (value != "--") Icon(Icons.Rounded.ContentCopy, null, Modifier.size(14.dp), tint = color.copy(alpha = .55f))
    }
}

@Composable
private fun DeviceDetailPair(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(44.dp), fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = LabV2.InkMuted)
        Text(value, Modifier.weight(1f), fontSize = 11.8.sp, lineHeight = 14.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeviceActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(16.dp), color = color.copy(alpha = .09f), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .12f))) {
        Column(Modifier.padding(horizontal = 3.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, Modifier.size(19.dp), tint = color)
            Text(text, fontSize = 10.2.sp, lineHeight = 11.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
