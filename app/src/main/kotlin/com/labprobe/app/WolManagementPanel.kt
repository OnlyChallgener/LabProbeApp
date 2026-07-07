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
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

    ExpressiveCard("WOL 设备", null, Icons.Rounded.Power, Color(0xFF8B5CF6)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("已添加 ${state.wolDevices.size} 台 · 启用 $enabledCount", fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .70f))
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { showAdd = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("添加", fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
        if (runtimes.isEmpty()) {
            Text("暂无 WOL 设备，点右上角添加。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f), fontSize = 11.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
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
        }

        if (candidates.isNotEmpty()) {
            Text("自动候选", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), fontSize = 11.sp, fontWeight = FontWeight.Black)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                candidates.forEach { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SmallTypeIcon(c.profile)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.config.remark, fontWeight = FontWeight.Black, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${c.profile.label} · ${c.config.mac}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .50f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(onClick = { state.addOrUpdateWolDevice(c.config.copy(enabled = true, updatedAt = System.currentTimeMillis())) }, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
                            Text("加入", fontSize = 11.sp, fontWeight = FontWeight.Black)
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
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, p.accent.copy(alpha = .10f))
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallTypeIcon(p, 44)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.config.remark.ifBlank { item.config.mac }, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TypeBadge(p.label, p.accent)
                    }
                    Text("MAC：${item.config.mac}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = item.config.enabled, onCheckedChange = onToggle)
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                OnlineDot(item.online)
                Spacer(Modifier.width(5.dp))
                Text(if (item.online) "在线" else "离线", fontSize = 11.2.sp, fontWeight = FontWeight.Black, color = if (item.online) Color(0xFF16A34A) else Color(0xFF64748B))
                if (item.ip.isNotBlank()) Text(" · IP：${item.ip}", fontSize = 11.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f))
                if (item.ipv6.isNotBlank()) Text(" · IPv6：${item.ipv6}", fontSize = 11.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f))
                if (!item.online && item.lastSeen.isNotBlank()) Text(" · 最后：${item.lastSeen}", fontSize = 11.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Edit, null, tint = p.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFFEF4444))
                }
                Button(
                    onClick = onWake,
                    enabled = item.config.enabled && !item.online,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14B8A6)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("唤醒", fontSize = 11.sp, fontWeight = FontWeight.Black) }
            }
        }
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
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("备注名称") },
                placeholder = { Text("例如：绿联 DH4300") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            EditableDeviceTypeField(value = typeId, onChange = { typeId = it }, modifier = Modifier.fillMaxWidth(), label = "设备类型（可输入/点箭头选择）")
            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it.uppercase() },
                label = { Text("MAC 地址") },
                placeholder = { Text("6C:1F:F7:76:71:04") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = KeyboardCapitalization.Characters),
                isError = mac.isNotBlank() && !validMac,
                supportingText = { Text(if (validMac) "图标预览：${selectedRule.label}" else "请输入正确 MAC，格式 AA:BB:CC:DD:EE:FF") },
                modifier = Modifier.fillMaxWidth()
            )
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
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消", fontWeight = FontWeight.Black) }
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
                colors = ButtonDefaults.buttonColors(containerColor = selectedRule.accent)
            ) { Text("保存", fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.heightIn(min = 8.dp))
    }
}

@Composable
private fun SmallTypeIcon(profile: DeviceVisualProfile, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(profile.accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        Icon(profile.icon, null, tint = profile.accent, modifier = Modifier.size((size * 0.52f).dp))
    }
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
    watched.forEach { if (it.mac.isNotBlank()) map[it.mac.lowercase()] = it }
    online.forEach { d ->
        if (d.mac.isBlank()) return@forEach
        val old = map[d.mac.lowercase()]
        map[d.mac.lowercase()] = if (old == null) d else mergePreferFreshDevice(old, d)
    }
    return map.values.toList()
}

private fun mergePreferFreshDevice(old: DeviceItem, fresh: DeviceItem): DeviceItem = fresh.copy(
    name = fresh.name.ifBlank { old.name },
    ip = fresh.ip.ifBlank { old.ip },
    ssid = fresh.ssid.ifBlank { old.ssid },
    band = fresh.band.ifBlank { old.band },
    rssi = fresh.rssi.ifBlank { old.rssi },
    rxrate = fresh.rxrate.ifBlank { old.rxrate },
    onlineSince = fresh.onlineSince.ifBlank { old.onlineSince },
    offlineAt = fresh.offlineAt.ifBlank { old.offlineAt },
    onlineDurationText = fresh.onlineDurationText.ifBlank { old.onlineDurationText },
    lastSeenAt = fresh.lastSeenAt.ifBlank { old.lastSeenAt },
    ipv6 = (fresh.ipv6 + old.ipv6).distinct(),
    manufacture = fresh.manufacture.ifBlank { old.manufacture },
    devType = fresh.devType.ifBlank { old.devType },
    osType = fresh.osType.ifBlank { old.osType },
    hostName = fresh.hostName.ifBlank { old.hostName },
    wolMode = fresh.wolMode.ifBlank { old.wolMode },
    connectType = fresh.connectType.ifBlank { old.connectType },
    remark = fresh.remark.ifBlank { old.remark },
    manualType = fresh.manualType.ifBlank { old.manualType },
    wolEnabledOverride = fresh.wolEnabledOverride ?: old.wolEnabledOverride
)
