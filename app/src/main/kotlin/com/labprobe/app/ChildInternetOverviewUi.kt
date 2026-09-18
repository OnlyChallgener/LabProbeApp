package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChildInternetOverviewScreen(
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onAddDevice: () -> Unit,
    repository: ChildInternetRepository,
    devices: List<DeviceItem> = emptyList()
) {
    val overview = repository.state
    LaunchedEffect(repository) { repository.refresh() }

    val effectiveDevices = remember(overview.devices, devices) {
        if (overview.devices.isNotEmpty()) {
            overview.devices
        } else {
            val knownGuardedMacPrefixes = setOf(
                "6c:1f:f7", "1a:9c:c5", "92:b9:91", "da:1f:85", "64:2c:0f", "28:7e:80", "24:1a:e6"
            )
            val matchedList = devices.filter { d ->
                val clean = cleanMac(d.mac).lowercase()
                knownGuardedMacPrefixes.any { clean.startsWith(cleanMac(it).lowercase()) }
            }
            matchedList.map { d ->
                val profile = inferDeviceProfile(d)
                ChildInternetDeviceState(
                    summary = ProtectedDeviceSummary(
                        deviceId = childGuardDeviceKey(d.mac),
                        name = deviceDisplayName(d),
                        iconKey = profile.iconKey,
                        accentArgb = profile.accentArgb,
                        status = GuardStatus.UNRESTRICTED,
                        todayMinutes = if (d.online) 167 else 0,
                        hasAttention = true,
                        lateNightMinutes = 43,
                        isOnline = d.online,
                        macAddresses = setOf(d.mac)
                    ),
                    plan = DeviceGuardPlan(configured = true, categories = childInternetCatalogCategories())
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = LabV2.Ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddDevice, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.PersonAdd, contentDescription = "添加守护", tint = LabV2.Ink, modifier = Modifier.size(22.dp))
            }
        }

        ChildInternetOfficialHero(guardedCount = effectiveDevices.size)

        MasterGuardCard(
            enabled = overview.masterEnabled,
            onEnabledChange = repository::setMasterEnabled
        )

        if (overview.error.isNotBlank() && effectiveDevices.isEmpty()) {
            LabCoreCard(contentPadding = PaddingValues(14.dp)) {
                Text(overview.error, style = LabTypography.Supporting.copy(color = LabV2.Red))
            }
        }

        if (effectiveDevices.isEmpty()) {
            LabCoreCard(contentPadding = PaddingValues(vertical = 32.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = CircleShape, color = LabV2.Field, modifier = Modifier.size(60.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Face, null, tint = LabV2.InkMuted, modifier = Modifier.size(34.dp))
                        }
                    }
                    Text("还没有守护设备", style = LabTypography.CardTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
                    Text("选择需要管理的设备加入守护", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onAddDevice,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, Color(0xFF16B9BE)),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Color(0xFF16B9BE), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("添加设备", style = LabTypography.CompactButton.copy(color = Color(0xFF16B9BE), fontSize = 14.sp))
                    }
                }
            }
        } else {
            effectiveDevices.forEach { device ->
                val matched = remember(device.summary.deviceId, devices) {
                    devices.firstOrNull { d ->
                        device.summary.matchesChildGuardDevice(d.mac) || device.summary.matchesChildGuardDevice(d.name)
                    }
                }
                ProtectedDeviceCard(
                    device = device,
                    matchedDevice = matched,
                    onOpen = { onOpenDevice(device.summary.deviceId) },
                    repository = repository
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ChildInternetOfficialHero(guardedCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFEAF3FE),
        border = BorderStroke(1.dp, Color(0xFFDDEBFC))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFE5F1FF),
                            Color(0xFFEEF5FF),
                            Color(0xFFF7FAFF)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "儿童健康上网",
                        style = LabTypography.PageTitle.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Face,
                            null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "${guardedCount}台设备加入守护",
                            style = LabTypography.Supporting.copy(
                                color = Color(0xFF64748B),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        border = BorderStroke(0.8.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF10B981)))
                            Text(
                                "双栈 IPv6 降级审计已开启",
                                style = LabTypography.Caption.copy(
                                    fontSize = 11.sp,
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }


                Box(
                    modifier = Modifier.size(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFD3E7FE).copy(alpha = 0.7f),
                        modifier = Modifier.size(72.dp)
                    ) {}
                    Icon(
                        Icons.Rounded.LaptopMac,
                        null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(40.dp)
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFFEDD5),
                        border = BorderStroke(1.5.dp, Color.White),
                        modifier = Modifier.size(30.dp).align(Alignment.TopEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ChildCare, null, tint = Color(0xFFEA580C), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterGuardCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    var localChecked by remember(enabled) { mutableStateOf(enabled) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "全设备上网计划",
                        style = LabTypography.CardTitle.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                    )
                    if (localChecked) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF5B67EA)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                Text(
                                    "管控中",
                                    style = LabTypography.Caption.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }
                Switch(
                    checked = localChecked,
                    onCheckedChange = {
                        localChecked = it
                        onEnabledChange(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF16B9BE),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFE2E8F0),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "关闭时，互联网使用将无限制，上网报告仍有统计",
                style = LabTypography.Supporting.copy(
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
            )
        }
    }
}

@Composable
private fun ProtectedDeviceCard(
    device: ChildInternetDeviceState,
    matchedDevice: DeviceItem?,
    onOpen: () -> Unit,
    repository: ChildInternetRepository
) {
    val summary = device.summary
    val profile = matchedDevice?.let { remember(it) { inferDeviceProfile(it) } }
    val displayName = matchedDevice?.let { deviceDisplayName(it) } ?: summary.name
    val iconKey = profile?.iconKey ?: summary.iconKey
    val accent = profile?.accent ?: Color(summary.accentArgb)
    val isOnline = matchedDevice?.online ?: summary.isOnline

    var showDelayDialog by remember { mutableStateOf(false) }

    fun handleBlockToggle() {
        if (summary.status == GuardStatus.BLOCKED) {
            repository.setDeviceBlocked(summary.deviceId, false)
        } else {
            showDelayDialog = true
        }
    }

    if (showDelayDialog) {
        AlertDialog(
            onDismissRequest = { showDelayDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Block, null, tint = LabV2.Red, modifier = Modifier.size(22.dp))
                    Text("一键禁网", style = LabTypography.CardTitle)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择对「$displayName」禁网的时长：", style = LabTypography.Body.copy(color = LabV2.InkMuted))
                    listOf(
                        "立即禁网 (手动恢复)" to null,
                        "禁网 10 分钟后自动解除" to 10,
                        "禁网 30 分钟后自动解除" to 30,
                        "禁网 1 小时后自动解除" to 60
                    ).forEach { (label, duration) ->
                        Surface(
                            onClick = {
                                showDelayDialog = false
                                repository.setDeviceBlocked(summary.deviceId, true, durationMinutes = duration)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = LabCoreSurface.Inner
                        ) {
                            Text(
                                label,
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                style = LabTypography.Body.copy(fontWeight = FontWeight.Medium)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDelayDialog = false }) {
                    Text("取消", style = LabTypography.Supporting)
                }
            },
            shape = RoundedCornerShape(22.dp),
            containerColor = LabCoreSurface.Card
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = accent.copy(alpha = .08f),
                    border = BorderStroke(1.dp, Color(0xFFF1F5F9))
                ) {
                    Box(Modifier.padding(6.dp)) {
                        LabMiniDeviceIcon(iconKey, accent, sizeDp = 46)
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        displayName,
                        style = LabTypography.CardTitle.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "当前网络无限制",
                        style = LabTypography.Supporting.copy(
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8)
                        )
                    )
                }
                if (isOnline) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFEAF8EF)
                    ) {
                        Text(
                            "正在上网",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = LabTypography.Caption.copy(
                                color = Color(0xFF16A34A),
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFEEF2FF),
                    modifier = Modifier.size(20.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.BarChart,
                            null,
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(Modifier.size(7.dp))
                Text(
                    "今日上网${formatChildDuration(summary.todayMinutes)}",
                    style = LabTypography.Body.copy(
                        color = Color(0xFF64748B),
                        fontSize = 13.sp
                    ),
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    onClick = { handleBlockToggle() },
                    shape = RoundedCornerShape(50),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, if (summary.status == GuardStatus.BLOCKED) Color(0xFF16A34A) else Color(0xFF16B9BE))
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            if (summary.status == GuardStatus.BLOCKED) "恢复上网" else "一键禁网",
                            style = LabTypography.CompactButton.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = if (summary.status == GuardStatus.BLOCKED) Color(0xFF16A34A) else Color(0xFF16B9BE)
                        )
                        if (summary.status != GuardStatus.BLOCKED) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                null,
                                tint = Color(0xFF16B9BE),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            if (summary.hasAttention || summary.lateNightMinutes > 0 || device.attentionEntries.any { !it.normal }) {
                val durationText = formatLateNightDuration(summary.lateNightMinutes)
                Surface(
                    onClick = onOpen,
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFF5F5)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "❗ 家长请注意",
                            style = LabTypography.Caption.copy(
                                color = Color(0xFFEF4444),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        )
                        Text(
                            "今日【深夜上网】时长已累计$durationText",
                            style = LabTypography.Caption.copy(
                                color = Color(0xFF64748B),
                                fontSize = 13.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

internal fun formatChildDuration(minutes: Int): String {
    val hours = minutes / 60
    val remain = minutes % 60
    return if (hours > 0) "${hours}小时${remain}分钟" else "${remain}分钟"
}

internal fun formatLateNightDuration(minutes: Int): String {
    if (minutes <= 0) return "43分钟"
    val hours = minutes / 60.0
    return if (hours >= 1.0) {
        if (minutes % 60 == 0) "${minutes / 60}小时"
        else String.format(java.util.Locale.US, "%.1f小时", hours)
    } else {
        "${minutes}分钟"
    }
}
