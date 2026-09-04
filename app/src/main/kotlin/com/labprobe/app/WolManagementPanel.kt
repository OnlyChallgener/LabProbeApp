package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun WolManagementPanel(state: AppState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<WolDeviceConfig?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val shared = remember(state.devices, state.onlineDevices) { mergeSharedDeviceState(state.devices, state.onlineDevices) }
    val runtimes = remember(state.wolDevices, shared) { buildWolRuntimes(state.wolDevices, shared) }
    val candidates = remember(state.wolDevices, shared) { wolCandidatesFromDevices(shared, state.wolDevices) }
    val enabledCount = state.wolDevices.count { it.enabled }
    val onlineCount = runtimes.count { it.online }
    val offlineCount = runtimes.size - onlineCount

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabCoreCard(compact = true) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LabV2ToolIcon(Icons.Rounded.Power, LabV2.Primary, size = 38)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("WOL 设备管理", style = LabTypography.CardTitle)
                    Text("共 ${state.wolDevices.size} 台 · 在线 $onlineCount · 离线 $offlineCount · 启用 $enabledCount", style = LabTypography.Supporting)
                }
                Surface(
                    onClick = { showAdd = true },
                    shape = RoundedCornerShape(12.dp),
                    color = LabV2.Primary,
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("添加", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (runtimes.isEmpty()) {
            LabCoreCard(compact = true) {
                Text("暂无 WOL 设备，点右上角「添加」增加局域网唤醒目标。", color = LabV2.InkMuted, fontSize = 12.sp)
            }
        } else {
            runtimes.forEach { item ->
                WolDeviceCard(
                    item = item,
                    onToggle = { state.toggleWolDevice(item.config.mac, it) },
                    onEdit = { editing = item.config },
                    onDelete = { state.deleteWolDevice(item.config.mac) },
                    onWake = {
                        scope.launch {
                            val msg = runCatching { state.wakeMac(ctx, item.config.mac) }.getOrElse { "WOL失败：${it.message}" }
                            toast(ctx, msg)
                        }
                    }
                )
            }
        }

        if (candidates.isNotEmpty()) {
            LabCoreCard(compact = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("局域网候选设备", style = LabTypography.SectionTitle)
                    Spacer(Modifier.width(6.dp))
                    Text("(${candidates.size})", style = LabTypography.Supporting)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SmallTypeIcon(c.profile, 36)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(c.config.remark, style = LabTypography.Body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.profile.label} · ${c.config.mac.uppercase()}", style = LabTypography.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { state.addOrUpdateWolDevice(c.config.copy(enabled = true, updatedAt = System.currentTimeMillis())) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, LabV2.Primary.copy(alpha = 0.4f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("加入", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = LabV2.Primary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        WolEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { cfg -> state.addOrUpdateWolDevice(cfg); showAdd = false }
        )
    }
    editing?.let { cfg ->
        WolEditDialog(
            initial = cfg,
            onDismiss = { editing = null },
            onSave = { updated -> state.addOrUpdateWolDevice(updated); editing = null }
        )
    }
}

@Composable
private fun WolDeviceCard(
    item: WolDeviceRuntime,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWake: () -> Unit
) {
    val p = item.profile
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        shape = LabCoreSurface.CardShape,
        color = LabCoreSurface.Card,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, LabCoreSurface.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Icon, Title, Badge, MAC, and Switch
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallTypeIcon(p, 44)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            item.config.remark.ifBlank { item.config.mac },
                            style = LabTypography.CardTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TypeBadge(p.label, p.accent)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { copy(ctx, item.config.mac); toast(ctx, "已复制 MAC") }
                    ) {
                        Text(
                            "MAC:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = LabV2.InkMuted
                        )
                        Text(
                            item.config.mac.uppercase(),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = LabV2.Ink
                        )
                        Icon(
                            Icons.Rounded.ContentCopy,
                            "复制 MAC",
                            tint = LabV2.InkMuted.copy(alpha = 0.6f),
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = item.config.enabled,
                    onCheckedChange = onToggle
                )
            }

            // Network & Status Container (Inner card with distinct soft background)
            Surface(
                shape = LabCoreSurface.InnerShape,
                color = LabCoreSurface.Inner,
                border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Status row + IPv4
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Online / Offline Status Badge
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = if (item.online) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, if (item.online) Color(0xFF86EFAC) else Color(0xFFCBD5E1))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OnlineDot(item.online)
                                Text(
                                    if (item.online) "在线" else if (item.lastSeen.isNotBlank()) "离线 (${item.lastSeen})" else "离线",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.online) Color(0xFF15803D) else Color(0xFF64748B)
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        if (item.ip.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.clickable { copy(ctx, item.ip); toast(ctx, "已复制 IPv4") }
                            ) {
                                Text("IPv4:", fontSize = 11.sp, color = LabV2.InkMuted)
                                Text(item.ip, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink)
                                Icon(Icons.Rounded.ContentCopy, null, tint = LabV2.InkMuted.copy(alpha = 0.5f), modifier = Modifier.size(11.dp))
                            }
                        }
                    }

                    // IPv6 row (if available, full-width, clean truncation and copy)
                    if (item.ipv6.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { copy(ctx, item.ipv6); toast(ctx, "已复制 IPv6") },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("IPv6: ", fontSize = 10.5.sp, color = LabV2.InkMuted)
                            Text(
                                item.ipv6,
                                modifier = Modifier.weight(1f),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = LabV2.InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.ContentCopy, null, tint = LabV2.InkMuted.copy(alpha = 0.5f), modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }

            // Action Buttons Row: Prominent Wake vs Online status, with compact management buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!item.online) {
                    // Offline -> Prominent Primary Wake Button
                    Button(
                        onClick = onWake,
                        enabled = item.config.enabled,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0D9488),
                            disabledContainerColor = Color(0xFFE2E8F0)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                        modifier = Modifier.weight(1.5f).height(38.dp)
                    ) {
                        Icon(Icons.Rounded.Bolt, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("唤醒设备", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // Online -> Clean running state pill + optional repeat wake
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFECFDF5),
                        border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                        modifier = Modifier.weight(1.5f).height(38.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF059669), modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("已在线", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF047857))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "· 重发WOL",
                                fontSize = 11.sp,
                                color = Color(0xFF0D9488),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onWake() }
                            )
                        }
                    }
                }

                // Edit button
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, LabCoreSurface.Border),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(Icons.Rounded.Edit, null, tint = p.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink)
                }

                // Delete button
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5).copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFEF4444))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除 WOL 设备", fontWeight = FontWeight.Bold) },
            text = { Text("确认从 WOL 列表中移除「${item.config.remark.ifBlank { item.config.mac }}」？") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("删除", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun WolEditDialog(initial: WolDeviceConfig?, onDismiss: () -> Unit, onSave: (WolDeviceConfig) -> Unit) {
    var remark by remember(initial) { mutableStateOf(initial?.remark.orEmpty()) }
    var mac by remember(initial) { mutableStateOf(initial?.mac.orEmpty()) }
    var typeId by remember(initial) { mutableStateOf(initial?.typeId ?: "nas") }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    val selectedRule = deviceTypeRuleForInput(typeId)
    val validMac = isValidMac(cleanMac(mac))

    LabBottomSheet(onDismiss = onDismiss) {
        Text(if (initial == null) "添加 WOL 设备" else "编辑 WOL 设备", fontWeight = FontWeight.Black, fontSize = 20.sp)
        Column(verticalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("备注名称", fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted)
            CompactTextField(value = remark, onValueChange = { remark = it }, placeholder = "例如：绿联 DH4300", modifier = Modifier.fillMaxWidth())
            EditableDeviceTypeField(value = typeId, onChange = { typeId = it }, modifier = Modifier.fillMaxWidth(), label = "设备类型（可输入/点箭头选择）")
            Text("MAC 地址", fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = if (mac.isNotBlank() && !validMac) MaterialTheme.colorScheme.error else LabV2.InkMuted)
            CompactTextField(
                value = mac,
                onValueChange = { mac = it.uppercase() },
                placeholder = "6C:1F:F7:76:71:04",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
            Text(if (validMac) "图标预览：${selectedRule.label}" else "请输入正确 MAC，格式 AA:BB:CC:DD:EE:FF", fontSize = 10.sp, color = if (validMac) LabV2.InkMuted else MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DeviceTypeIconPreview(selectedRule, 44)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(selectedRule.label, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    Text(if (enabled) "启用 WOL，离线时可唤醒" else "关闭 WOL，仅记录设备", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(22.dp)) { Text("取消", fontWeight = FontWeight.Black) }
            Button(
                enabled = validMac,
                onClick = {
                    val clean = cleanMac(mac)
                    onSave(
                        WolDeviceConfig(
                            id = initial?.id ?: clean,
                            remark = remark.trim().ifBlank { selectedRule.label },
                            mac = clean,
                            typeId = normalizeDeviceTypeToken(typeId).ifBlank { typeId.trim() },
                            enabled = enabled,
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DEVICE_ICON_ACCENT)
            ) { Text("保存", fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.heightIn(min = 8.dp))
    }
}

@Composable
private fun SmallTypeIcon(profile: DeviceVisualProfile, size: Int = 40) {
    LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = size)
}

@Composable
private fun TypeBadge(label: String, color: Color) {
    androidx.compose.material3.Surface(shape = RoundedCornerShape(99.dp), color = color.copy(alpha = .12f)) {
        Text(label, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1)
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) Color(0xFF22C55E) else Color(0xFF94A3B8)))
}

@Composable
private fun IconButtonLite(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    androidx.compose.material3.Surface(onClick = onClick, modifier = Modifier.size(27.dp), shape = CircleShape, color = color.copy(alpha = .10f)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(15.dp)) }
    }
}

fun mergeSharedDeviceState(watched: List<DeviceItem>, online: List<DeviceItem>): List<DeviceItem> {
    val map = linkedMapOf<String, DeviceItem>()
    watched.forEach { device ->
        if (device.mac.isBlank()) return@forEach
        val key = cleanMac(device.mac)
        val old = map[key]
        map[key] = if (old == null) device else mergePreferFreshDevice(old, device)
    }
    online.forEach { d ->
        if (d.mac.isBlank()) return@forEach
        val key = cleanMac(d.mac)
        val old = map[key]
        map[key] = if (old == null) d else mergePreferFreshDevice(old, d)
    }
    return map.values.toList()
}

private fun mergePreferFreshDevice(old: DeviceItem, fresh: DeviceItem): DeviceItem {
    val oldTodayValid = old.todayOnlineDate == LocalDate.now().toString()
    val mergedIpv6 = mergeIpv6Candidates(
        fresh.ipv6Candidates,
        fresh.ipv6.map { Ipv6AddressCandidate(it) },
        old.ipv6Candidates,
        old.ipv6.map { Ipv6AddressCandidate(it) }
    ).take(24)
    return fresh.copy(
        name = fresh.name.ifBlank { old.name },
        ip = fresh.ip.ifBlank { old.ip },
        ssid = fresh.ssid.ifBlank { old.ssid },
        band = fresh.band.ifBlank { old.band },
        rssi = fresh.rssi.ifBlank { old.rssi },
        rxrate = fresh.rxrate.ifBlank { old.rxrate },
        onlineSince = fresh.onlineSince.ifBlank { old.onlineSince },
        offlineAt = fresh.offlineAt.ifBlank { old.offlineAt },
        onlineDurationText = fresh.onlineDurationText.ifBlank { old.onlineDurationText },
        todayOnlineDurationSec = when {
            fresh.todayOnlineDate.isNotBlank() -> fresh.todayOnlineDurationSec
            oldTodayValid -> old.todayOnlineDurationSec
            else -> 0L
        },
        todayOnlineDurationText = when {
            fresh.todayOnlineDate.isNotBlank() -> fresh.todayOnlineDurationText
            oldTodayValid -> old.todayOnlineDurationText
            else -> ""
        },
        todayOnlineDate = fresh.todayOnlineDate.ifBlank { old.todayOnlineDate.takeIf { oldTodayValid }.orEmpty() },
        lastSeenAt = fresh.lastSeenAt.ifBlank { old.lastSeenAt },
        ipv6 = mergedIpv6.map { it.address },
        ipv6Candidates = mergedIpv6,
        manufacture = fresh.manufacture.ifBlank { old.manufacture },
        devType = fresh.devType.ifBlank { old.devType },
        osType = fresh.osType.ifBlank { old.osType },
        hostName = fresh.hostName.ifBlank { old.hostName },
        wolMode = fresh.wolMode.ifBlank { old.wolMode },
        connectType = fresh.connectType.ifBlank { old.connectType },
        remark = old.remark.ifBlank { fresh.remark },
        manualType = old.manualType.ifBlank { fresh.manualType },
        wolEnabledOverride = old.wolEnabledOverride ?: fresh.wolEnabledOverride,
        followedOverride = when {
            old.followedOverride == true || fresh.followedOverride == true -> true
            old.followedOverride == false || fresh.followedOverride == false -> false
            else -> null
        },
        todayUpload = fresh.todayUpload.ifBlank { old.todayUpload },
        todayDownload = fresh.todayDownload.ifBlank { old.todayDownload },
        totalUpload = fresh.totalUpload.ifBlank { old.totalUpload },
        totalDownload = fresh.totalDownload.ifBlank { old.totalDownload }
    )
}
