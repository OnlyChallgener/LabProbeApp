package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "选择要管理的设备"页面 —— 对应官方 APP 加入/移除守护设备的入口。
 * 深度接入全量设备列表 (DeviceItem) 与指纹体系，支持分类过滤 (全部、手机、电脑、平板、手表、电视、其他)
 * 及复合品牌图标/连网方式呈现。
 */
@Composable
fun ChildInternetDevicePickerScreen(
    repository: ChildInternetRepository,
    devices: List<DeviceItem> = emptyList(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val initialCandidates = remember(devices, repository.state.devices) {
        val guardedList = repository.state.devices
        val guardedMacs = guardedList.flatMap { it.summary.macAddresses }.map { cleanMac(it).lowercase() }.toSet()
        val guardedUids = guardedList.map { childGuardDeviceKey(it.summary.deviceId) }.toSet()

        val dedupedDevices = devices.filter { it.mac.isNotBlank() }.distinctBy { cleanMac(it.mac).lowercase() }
        val result = mutableListOf<ChildGuardDeviceCandidate>()
        val seenMacs = mutableSetOf<String>()

        dedupedDevices.forEach { d ->
            val clean = cleanMac(d.mac).lowercase()
            if (clean.isBlank() || seenMacs.contains(clean)) return@forEach
            seenMacs.add(clean)

            val matchedGuarded = guardedList.firstOrNull { dev ->
                dev.summary.macAddresses.any { cleanMac(it).equals(clean, ignoreCase = true) } ||
                    cleanMac(dev.summary.deviceId).equals(clean, ignoreCase = true)
            }
            val isGuarded = guardedMacs.contains(clean) || matchedGuarded != null
            val uid = matchedGuarded?.summary?.deviceId.orEmpty()
            result.add(
                ChildGuardDeviceCandidate(
                    mac = d.mac,
                    ip = d.ip,
                    hostname = d.hostName,
                    guarded = isGuarded,
                    uid = uid,
                    name = deviceDisplayName(d),
                    deviceType = d.devType,
                    manufacturer = d.manufacture,
                    online = d.online,
                    connectType = d.connectType
                )
            )
        }

        guardedList.forEach { dev ->
            val mac = dev.summary.macAddresses.firstOrNull() ?: dev.summary.deviceId
            val clean = cleanMac(mac).lowercase()
            if (clean.isNotBlank() && !seenMacs.contains(clean)) {
                seenMacs.add(clean)
                result.add(
                    ChildGuardDeviceCandidate(
                        mac = mac,
                        ip = "",
                        hostname = dev.summary.name,
                        guarded = true,
                        uid = dev.summary.deviceId,
                        name = dev.summary.name,
                        deviceType = dev.summary.iconKey,
                        manufacturer = "",
                        online = dev.summary.isOnline,
                        connectType = ""
                    )
                )
            }
        }
        result
    }
    var candidates by remember(initialCandidates) { mutableStateOf(initialCandidates) }
    var loading by remember { mutableStateOf(initialCandidates.isEmpty()) }
    var error by remember { mutableStateOf("") }
    var selectedMacs by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var selectedCategory by rememberSaveable { mutableStateOf("全部") }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        if (candidates.isEmpty()) loading = true
        error = ""
        repository.loadCandidates { result ->
            loading = false
            result.onSuccess { remoteCandidates ->
                val guardedList = repository.state.devices
                val guardedMacs = guardedList.flatMap { it.summary.macAddresses }.map { cleanMac(it).lowercase() }.toSet()
                val guardedUids = guardedList.map { childGuardDeviceKey(it.summary.deviceId) }.toSet()
                val dedupedDevices = devices.filter { it.mac.isNotBlank() }.distinctBy { cleanMac(it.mac).lowercase() }

                val seenMacs = mutableSetOf<String>()
                val merged = mutableListOf<ChildGuardDeviceCandidate>()

                remoteCandidates.forEach { rc ->
                    val clean = cleanMac(rc.mac).lowercase()
                    if (clean.isBlank() || seenMacs.contains(clean)) return@forEach
                    seenMacs.add(clean)

                    val matched = dedupedDevices.firstOrNull { cleanMac(it.mac).equals(clean, ignoreCase = true) }
                    val matchedGuarded = guardedList.firstOrNull { dev ->
                        dev.summary.macAddresses.any { cleanMac(it).equals(clean, ignoreCase = true) } ||
                            sameChildGuardDevice(dev.summary.deviceId, rc.uid)
                    }
                    val isGuarded = rc.guarded || guardedMacs.contains(clean) || (rc.uid.isNotBlank() && guardedUids.contains(childGuardDeviceKey(rc.uid))) || matchedGuarded != null
                    val resolvedUid = matchedGuarded?.summary?.deviceId?.takeIf { it.isNotBlank() } ?: rc.uid

                    if (matched != null) {
                        merged.add(
                            rc.copy(
                                guarded = isGuarded,
                                uid = resolvedUid,
                                name = deviceDisplayName(matched).ifBlank { rc.name },
                                manufacturer = matched.manufacture.ifBlank { rc.manufacturer },
                                deviceType = matched.devType.ifBlank { rc.deviceType },
                                online = matched.online
                            )
                        )
                    } else {
                        merged.add(rc.copy(guarded = isGuarded, uid = resolvedUid))
                    }
                }

                dedupedDevices.forEach { d ->
                    val clean = cleanMac(d.mac).lowercase()
                    if (clean.isBlank() || seenMacs.contains(clean)) return@forEach
                    seenMacs.add(clean)

                    val matchedGuarded = guardedList.firstOrNull { dev ->
                        dev.summary.macAddresses.any { cleanMac(it).equals(clean, ignoreCase = true) } ||
                            cleanMac(dev.summary.deviceId).equals(clean, ignoreCase = true)
                    }
                    val isGuarded = guardedMacs.contains(clean) || matchedGuarded != null
                    val uid = matchedGuarded?.summary?.deviceId.orEmpty()
                    merged.add(
                        ChildGuardDeviceCandidate(
                            mac = d.mac,
                            ip = d.ip,
                            hostname = d.hostName,
                            guarded = isGuarded,
                            uid = uid,
                            name = deviceDisplayName(d),
                            deviceType = d.devType,
                            manufacturer = d.manufacture,
                            online = d.online,
                            connectType = d.connectType
                        )
                    )
                }

                guardedList.forEach { dev ->
                    val mac = dev.summary.macAddresses.firstOrNull() ?: dev.summary.deviceId
                    val clean = cleanMac(mac).lowercase()
                    if (clean.isNotBlank() && !seenMacs.contains(clean)) {
                        seenMacs.add(clean)
                        merged.add(
                            ChildGuardDeviceCandidate(
                                mac = mac,
                                ip = "",
                                hostname = dev.summary.name,
                                guarded = true,
                                uid = dev.summary.deviceId,
                                name = dev.summary.name,
                                deviceType = dev.summary.iconKey,
                                manufacturer = "",
                                online = dev.summary.isOnline,
                                connectType = ""
                            )
                        )
                    }
                }

                candidates = merged
            }.onFailure { if (candidates.isEmpty()) error = it.message ?: "加载设备失败" }
        }
    }

    LaunchedEffect(repository) { reload() }

    val unguarded = candidates.filter { !it.guarded }
    val selectable = unguarded.filter { it.mac in selectedMacs }
    val guardedCount = candidates.count { it.guarded }

    val categories = listOf("全部", "手机", "电脑", "平板", "手表", "电视", "其他")

    val filteredCandidates = remember(candidates, selectedCategory, devices) {
        if (selectedCategory == "全部") candidates
        else candidates.filter { candidate ->
            val matched = devices.firstOrNull { cleanMac(it.mac) == cleanMac(candidate.mac) }
            resolveDeviceCategory(candidate, matched) == selectedCategory
        }
    }

    fun commitSelection() {
        if (busy || selectable.isEmpty()) return
        busy = true
        var remaining = selectable.size
        var failures = 0
        selectable.forEach { candidate ->
            val matched = devices.firstOrNull { cleanMac(it.mac) == cleanMac(candidate.mac) }
            val name = matched?.let { deviceDisplayName(it) } ?: candidate.displayName
            repository.addGuardDevice(candidate.mac, name) { result ->
                if (result.isFailure) failures++
                if (--remaining == 0) {
                    busy = false
                    selectedMacs = arrayListOf()
                    toast(context, if (failures == 0) "已加入 ${selectable.size} 台设备" else "部分设备加入失败，请重试")
                    reload()
                }
            }
        }
    }

    fun removeDevice(candidate: ChildGuardDeviceCandidate) {
        if (busy || candidate.uid.isBlank()) return
        busy = true
        repository.removeGuardDevice(candidate.uid) { result ->
            busy = false
            if (result.isSuccess) {
                val matched = devices.firstOrNull { cleanMac(it.mac) == cleanMac(candidate.mac) }
                val name = matched?.let { deviceDisplayName(it) } ?: candidate.displayName
                toast(context, "已移除 $name")
                reload()
            } else {
                toast(context, result.exceptionOrNull()?.message ?: "移除失败")
            }
        }
    }

    Column(Modifier.fillMaxSize().appBackground()) {
        Column(
            Modifier.weight(1f).padding(horizontal = LabV2.PageHorizontal, vertical = LabV2.PageTop)
        ) {
            CompactPageHeader(
                "选择要管理的设备",
                "勾选需要加入守护的设备，可随时移除",
                onBack = onBack,
                titleStyle = LabTypography.PageTitle,
                subtitleStyle = LabTypography.Supporting
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = LabV2.Cyan.copy(alpha = .08f)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Icon(Icons.Rounded.Shield, null, tint = LabV2.Primary, modifier = Modifier.size(18.dp))
                    Text(
                        "加入守护后，该设备将纳入上网计划管理",
                        style = LabTypography.Supporting.copy(color = LabV2.Primary, fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 7 分类 FilterChip 滚动栏
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat, style = LabTypography.Caption.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LabV2.Cyan.copy(alpha = .14f),
                            selectedLabelColor = LabV2.Primary
                        )
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("局域网设备", style = LabTypography.SectionTitle, modifier = Modifier.weight(1f))
                Text(
                    "已守护 $guardedCount 台 · 可选 ${unguarded.size} 台",
                    style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
                )
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = LabCoreSurface.Card,
                border = BorderStroke(1.dp, LabV2.Border)
            ) {
                when {
                    loading && candidates.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在扫描局域网设备…", style = LabTypography.Body.copy(color = LabV2.InkMuted))
                    }
                    error.isNotBlank() && candidates.isEmpty() -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabV2ToolIcon(Icons.Rounded.ChildCare, LabV2.InkMuted, size = 46, muted = true)
                            Text(error, style = LabTypography.Body.copy(color = LabV2.Red))
                            TextButton(onClick = { reload() }) { Text("重试", style = LabTypography.Body.copy(color = LabV2.Cyan, fontWeight = FontWeight.SemiBold)) }
                        }
                    }
                    filteredCandidates.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            LabV2ToolIcon(Icons.Rounded.ChildCare, LabV2.InkMuted, size = 46, muted = true)
                            Text(if (selectedCategory == "全部") "没有发现可管理的设备" else "该分类下暂无设备", style = LabTypography.SectionTitle)
                            Text("请确认设备已连接本路由器", style = LabTypography.Supporting)
                        }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                        items(filteredCandidates, key = { it.mac }) { candidate ->
                            val matched = devices.firstOrNull { cleanMac(it.mac) == cleanMac(candidate.mac) }
                            CandidateDeviceRow(
                                candidate = candidate,
                                matchedDevice = matched,
                                selected = candidate.mac in selectedMacs,
                                enabled = !busy,
                                onToggle = { checked ->
                                    selectedMacs = if (checked) ArrayList((selectedMacs + candidate.mac).distinct()) else ArrayList(selectedMacs.filterNot { it == candidate.mac })
                                },
                                onRemove = { removeDevice(candidate) }
                            )
                        }
                    }
                }
            }
        }
        Surface(color = LabCoreSurface.Card, border = BorderStroke(1.dp, LabV2.Border)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (selectable.isEmpty()) "请勾选要加入守护的设备" else "已选 ${selectable.size} 台设备",
                        style = LabTypography.Body
                    )
                    Text("加入后可单独设置上网时段与应用", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                }
                Button(
                    onClick = { commitSelection() },
                    enabled = selectable.isNotEmpty() && !busy,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan, disabledContainerColor = LabV2.Field)
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (busy) "处理中…" else "加入守护", style = LabTypography.Button)
                }
            }
        }
    }
}

@Composable
private fun CandidateDeviceRow(
    candidate: ChildGuardDeviceCandidate,
    matchedDevice: DeviceItem?,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val profile = remember(candidate, matchedDevice) {
        if (matchedDevice != null) {
            inferDeviceProfile(matchedDevice)
        } else {
            val synthetic = DeviceItem(
                name = candidate.name.ifBlank { candidate.hostname },
                mac = candidate.mac,
                online = candidate.online,
                ip = candidate.ip,
                ssid = "", band = "", rssi = "", rxrate = "",
                onlineSince = "", offlineAt = "", onlineDurationText = "", lastSeenAt = "",
                manufacture = candidate.manufacturer, devType = candidate.deviceType,
                hostName = candidate.hostname, connectType = candidate.connectType
            )
            inferDeviceProfile(synthetic)
        }
    }
    val displayName = matchedDevice?.let { deviceDisplayName(it) } ?: candidate.displayName
    val isOnline = matchedDevice?.online ?: candidate.online
    val isWifi = matchedDevice?.let { hasWifiInfo(it) } ?: (candidate.connectType.contains("wifi", ignoreCase = true) || candidate.connectType.contains("wireless", ignoreCase = true))
    val connectionLabel = if (isWifi) {
        val band = matchedDevice?.band.orEmpty()
        if (band.isNotBlank()) "$band Wi-Fi" else "无线 Wi-Fi"
    } else {
        "有线直连"
    }

    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && !candidate.guarded) { onToggle(!selected) }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = profile.accent.copy(alpha = 0.08f)) {
            Box(Modifier.padding(6.dp)) {
                LabMiniDeviceIcon(profile.iconKey, profile.accent, sizeDp = 42)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(displayName, style = LabTypography.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (candidate.mac.length >= 5) {
                        Text("(${candidate.mac.takeLast(5).uppercase()})", style = LabTypography.Caption.copy(color = LabV2.InkMuted, fontSize = 11.sp))
                    }
                }
                if (candidate.guarded) {
                    Surface(shape = RoundedCornerShape(20.dp), color = LabV2.Green.copy(alpha = .10f)) {
                        Row(
                            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Rounded.Check, null, tint = LabV2.Green, modifier = Modifier.size(12.dp))
                            Text("已守护", style = LabTypography.Caption.copy(color = LabV2.Green, fontWeight = FontWeight.Bold))
                        }
                    }
                } else {
                    Surface(shape = RoundedCornerShape(20.dp), color = if (isOnline) LabV2.Green.copy(alpha = .10f) else LabV2.InkMuted.copy(alpha = .10f)) {
                        Text(
                            if (isOnline) "在线" else "离线",
                            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = LabTypography.Caption.copy(color = if (isOnline) LabV2.Green else LabV2.InkMuted, fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
            Text(
                listOfNotNull(
                    candidate.ip.takeIf { it.isNotBlank() },
                    candidate.mac.takeIf { it.isNotBlank() },
                    profile.label.takeIf { it.isNotBlank() },
                    connectionLabel
                ).joinToString(" · "),
                style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (candidate.guarded) {
            TextButton(onClick = onRemove, enabled = enabled) {
                Text("移除", style = LabTypography.Supporting.copy(color = LabV2.Red, fontWeight = FontWeight.SemiBold))
            }
        } else {
            Checkbox(
                checked = selected,
                onCheckedChange = onToggle,
                enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = LabV2.Cyan, uncheckedColor = LabV2.BorderStrong)
            )
        }
    }
}

/** 将候选设备推断映射到顶部 7 个分类之一 */
internal fun resolveDeviceCategory(candidate: ChildGuardDeviceCandidate, matchedDevice: DeviceItem?): String {
    val type = if (matchedDevice != null) {
        inferDeviceProfile(matchedDevice).type
    } else {
        val synthetic = DeviceItem(
            name = candidate.name.ifBlank { candidate.hostname },
            mac = candidate.mac,
            online = candidate.online,
            ip = candidate.ip,
            ssid = "", band = "", rssi = "", rxrate = "",
            onlineSince = "", offlineAt = "", onlineDurationText = "", lastSeenAt = "",
            manufacture = candidate.manufacturer, devType = candidate.deviceType,
            hostName = candidate.hostname, connectType = candidate.connectType
        )
        inferDeviceProfile(synthetic).type
    }
    return when (type) {
        "phone", "iphone", "huawei_phone" -> "手机"
        "desktop", "laptop", "mini_pc", "mac_mini", "all_in_one", "server", "industrial" -> "电脑"
        "tablet" -> "平板"
        "watch", "child_watch", "smart_ring" -> "手表"
        "tv", "tv_box", "set_top_box", "projector", "smart_display" -> "电视"
        else -> "其他"
    }
}
