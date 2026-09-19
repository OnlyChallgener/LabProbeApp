package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    var hudState by remember { mutableStateOf<OperationHudState>(OperationHudState.Hidden) }
    LaunchedEffect(repository) { repository.refresh() }

    val effectiveDevices = remember(overview.devices) {
        val seen = mutableSetOf<String>()
        overview.devices.filter { dev ->
            val mac = dev.summary.macAddresses.firstOrNull()?.let(::cleanMac)?.lowercase()
            val key = mac?.takeIf { it.isNotBlank() } ?: dev.summary.deviceId.lowercase()
            seen.add(key)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .appBackground()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                onEnabledChange = { enabled ->
                    scope.launch {
                        hudState = OperationHudState.Loading("配置中...")
                        runCatching {
                            repository.setMasterEnabled(enabled)
                        }.onSuccess {
                            hudState = OperationHudState.Success("配置成功")
                        }.onFailure {
                            hudState = OperationHudState.Error(it.message ?: "配置失败")
                        }
                    }
                }
            )

            if (overview.error.isNotBlank() && effectiveDevices.isEmpty()) {
                LabCoreCard(contentPadding = PaddingValues(14.dp)) {
                    Text(overview.error, style = LabTypography.Supporting.copy(color = LabV2.Red))
                }
            }

            if (effectiveDevices.isEmpty() && overview.loading) {
                LabCoreCard(contentPadding = PaddingValues(vertical = 36.dp)) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = LabV2.Primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "正在同步守护设备...",
                            style = LabTypography.Supporting.copy(color = LabV2.InkMuted)
                        )
                    }
                }
            } else if (effectiveDevices.isEmpty()) {
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
                            cleanMac(d.mac).equals(cleanMac(device.summary.deviceId), ignoreCase = true) ||
                                device.summary.macAddresses.any { cleanMac(it).equals(cleanMac(d.mac), ignoreCase = true) }
                        }
                    }
                    ProtectedDeviceCard(
                        device = device,
                        matchedDevice = matched,
                        onOpen = { onOpenDevice(device.summary.deviceId) },
                        repository = repository,
                        onHudChange = { hudState = it }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OperationHudDialog(state = hudState, onDismiss = { hudState = OperationHudState.Hidden })
    }
}

@Composable
private fun ChildInternetOfficialHero(guardedCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
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
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "儿童上网守护",
                        style = LabTypography.PageTitle.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Face,
                            null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "已守护 ${guardedCount} 台设备 · 保护健康上网",
                            style = LabTypography.Supporting.copy(
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal
                            )
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        border = BorderStroke(0.6.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(Color(0xFF10B981)))
                            Text(
                                "双栈 IPv6 降级审计已生效",
                                style = LabTypography.Caption.copy(
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.size(54.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFD3E7FE).copy(alpha = 0.7f),
                        modifier = Modifier.size(50.dp)
                    ) {}
                    Icon(
                        Icons.Rounded.LaptopMac,
                        null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(28.dp)
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFFEDD5),
                        border = BorderStroke(1.5.dp, Color.White),
                        modifier = Modifier.size(22.dp).align(Alignment.TopEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ChildCare, null, tint = Color(0xFFEA580C), modifier = Modifier.size(13.dp))
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
    repository: ChildInternetRepository,
    onHudChange: (OperationHudState) -> Unit
) {
    val scope = rememberCoroutineScope()
    val summary = device.summary
    val profile = matchedDevice?.let { remember(it) { inferDeviceProfile(it) } }
    val displayName = matchedDevice?.let { deviceDisplayName(it) } ?: summary.name
    val iconKey = profile?.iconKey ?: summary.iconKey
    val accent = profile?.accent ?: Color(summary.accentArgb)
    val isOnline = matchedDevice?.online ?: summary.isOnline

    val isBlocked = summary.status == GuardStatus.BLOCKED
    var delayMenuExpanded by remember { mutableStateOf(false) }

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
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accent.copy(alpha = .08f),
                    border = BorderStroke(1.dp, Color(0xFFF1F5F9))
                ) {
                    Box(Modifier.padding(5.dp)) {
                        LabMiniDeviceIcon(iconKey, accent, sizeDp = 40)
                    }
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        displayName,
                        style = LabTypography.CardTitle.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val nowEpoch = System.currentTimeMillis() / 1000L
                    val isBlocked = summary.status == GuardStatus.BLOCKED
                    val isTemporaryBlock = isBlocked && summary.blockedUntilEpoch > nowEpoch
                    val remainingMins = if (isTemporaryBlock) {
                        ((summary.blockedUntilEpoch - nowEpoch + 59) / 60).toInt().coerceAtLeast(1)
                    } else 0

                    val statusText = when {
                        isTemporaryBlock -> "已禁网 · 剩余 $remainingMins 分钟"
                        isBlocked -> "已一键禁网 · 手动恢复后可用"
                        summary.status == GuardStatus.GUARDED -> "上网计划守护中"
                        else -> "当前网络无限制"
                    }
                    val statusColor = when {
                        isBlocked -> LabV2.Red
                        summary.status == GuardStatus.GUARDED -> LabV2.Primary
                        else -> Color(0xFF94A3B8)
                    }
                    Text(
                        statusText,
                        style = LabTypography.Supporting.copy(
                            fontSize = 12.sp,
                            color = statusColor,
                            fontWeight = if (isBlocked) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
                if (isBlocked) {
                    val isTemporaryBlock = summary.blockedUntilEpoch > System.currentTimeMillis() / 1000L
                    val remainingMins = if (isTemporaryBlock) {
                        ((summary.blockedUntilEpoch - System.currentTimeMillis() / 1000L + 59) / 60).toInt().coerceAtLeast(1)
                    } else 0
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFFEE2E2)
                    ) {
                        Text(
                            if (isTemporaryBlock) "已禁网 ($remainingMins 分钟)" else "已禁网",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                            style = LabTypography.Caption.copy(
                                color = Color(0xFFDC2626),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            )
                        )
                    }
                } else if (isOnline) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFEAF8EF)
                    ) {
                        Text(
                            "正在上网",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                            style = LabTypography.Caption.copy(
                                color = Color(0xFF16A34A),
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.5.sp
                            )
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFF1F5F9)
                    ) {
                        Text(
                            "离线",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                            style = LabTypography.Caption.copy(
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.5.sp
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
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.BarChart,
                            null,
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "今日上网${formatChildDuration(summary.todayMinutes)}",
                    style = LabTypography.Body.copy(
                        color = Color(0xFF64748B),
                        fontSize = 12.5.sp
                    ),
                    modifier = Modifier.weight(1f)
                )

                val btnBorderColor = if (isBlocked) Color(0xFF16A34A) else Color(0xFF16B9BE)
                val btnTextColor = if (isBlocked) Color(0xFF16A34A) else Color(0xFF16B9BE)
                val btnBgColor = if (isBlocked) Color(0xFFEAF8EF) else Color.Transparent

                Box {
                    Surface(
                        onClick = {
                            if (isBlocked) {
                                scope.launch {
                                    onHudChange(OperationHudState.Loading("配置中..."))
                                    runCatching {
                                        repository.setDeviceBlocked(summary.deviceId, false)
                                    }.onSuccess {
                                        onHudChange(OperationHudState.Success("配置成功"))
                                    }.onFailure {
                                        onHudChange(OperationHudState.Error(it.message ?: "配置失败"))
                                    }
                                }
                            } else {
                                delayMenuExpanded = true
                            }
                        },
                        shape = RoundedCornerShape(50),
                        color = btnBgColor,
                        border = BorderStroke(1.dp, btnBorderColor)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 4.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (isBlocked) {
                                Icon(
                                    Icons.Rounded.CheckCircleOutline,
                                    null,
                                    tint = btnTextColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                            }
                            Text(
                                if (isBlocked) "恢复上网" else "一键禁网",
                                style = LabTypography.CompactButton.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = btnTextColor
                            )
                            if (!isBlocked) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    null,
                                    tint = btnTextColor,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    MaterialTheme(
                        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(14.dp))
                    ) {
                        DropdownMenu(
                            expanded = delayMenuExpanded,
                            onDismissRequest = { delayMenuExpanded = false },
                            modifier = Modifier
                                .width(160.dp)
                                .background(Color.White, RoundedCornerShape(14.dp)),
                            offset = DpOffset(x = (-30).dp, y = 4.dp)
                        ) {
                            val options = listOf(
                                "立即禁网" to null,
                                "10分钟后禁网" to 10,
                                "30分钟后禁网" to 30,
                                "1小时后禁网" to 60
                            )
                            options.forEachIndexed { index, (label, duration) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            style = TextStyle(
                                                fontSize = 14.5.sp,
                                                color = Color(0xFF1E293B),
                                                fontWeight = FontWeight.Normal
                                            )
                                        )
                                    },
                                    onClick = {
                                        delayMenuExpanded = false
                                        scope.launch {
                                            onHudChange(OperationHudState.Loading("配置中..."))
                                            runCatching {
                                                repository.setDeviceBlocked(summary.deviceId, true, durationMinutes = duration)
                                            }.onSuccess {
                                                onHudChange(OperationHudState.Success("配置成功"))
                                            }.onFailure {
                                                onHudChange(OperationHudState.Error(it.message ?: "配置失败"))
                                            }
                                        }
                                    },
                                    modifier = Modifier.height(42.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                )
                                if (index < options.lastIndex) {
                                    HorizontalDivider(
                                        color = Color(0xFFF1F5F9),
                                        thickness = 0.5.dp,
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val showAttention = summary.lateNightMinutes > 0
            if (showAttention) {
                val durationText = formatLateNightDuration(summary.lateNightMinutes)
                val attentionMessage = "今日【深夜上网】时长已累计$durationText"
                Surface(
                    onClick = onOpen,
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFFF5F5)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            "❗ 家长请注意",
                            style = LabTypography.Caption.copy(
                                color = Color(0xFFEF4444),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                        Text(
                            attentionMessage,
                            style = LabTypography.Caption.copy(
                                color = Color(0xFF64748B),
                                fontSize = 12.sp
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
    if (minutes <= 0) return "0分钟"
    val hours = minutes / 60.0
    return if (hours >= 1.0) {
        if (minutes % 60 == 0) "${minutes / 60}小时"
        else String.format(java.util.Locale.US, "%.1f小时", hours)
    } else {
        "${minutes}分钟"
    }
}
