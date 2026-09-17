package com.labprobe.app

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
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun ChildInternetOverviewScreen(
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    repository: ChildInternetRepository
) {
    val overview = repository.state
    LaunchedEffect(repository) { repository.refresh() }
    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LabV2.PageHorizontal, vertical = LabV2.PageTop),
        verticalArrangement = Arrangement.spacedBy(LabV2.SectionGap)
    ) {
        CompactPageHeader("儿童上网", "培养健康上网好习惯", onBack = onBack, titleStyle = LabTypography.PageTitle, subtitleStyle = LabTypography.Supporting)
        ChildInternetHero(overview.devices.size)
        MasterGuardCard(
            enabled = overview.masterEnabled,
            onEnabledChange = repository::setMasterEnabled
        )
        if (overview.error.isNotBlank()) {
            LabCoreCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Text(overview.error, style = LabTypography.Supporting.copy(color = LabV2.Red))
            }
        }
        Text("守护设备", style = LabTypography.SectionTitle, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp))
        if (overview.devices.isEmpty()) {
            LabCoreCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 28.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    LabV2ToolIcon(Icons.Rounded.ChildCare, LabV2.InkMuted, size = 48, muted = true)
                    Text("还没有守护设备", style = LabTypography.SectionTitle)
                    Text("从设备详情中添加需要管理的设备", style = LabTypography.Supporting)
                }
            }
        } else {
            overview.devices.forEach { device ->
                ProtectedDeviceCard(
                    device = device,
                    onOpen = { onOpenDevice(device.summary.deviceId) },
                    onToggleBlocked = {
                        repository.setDeviceBlocked(
                            device.summary.deviceId,
                            device.summary.status != GuardStatus.BLOCKED
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ChildInternetHero(deviceCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = LabV2.BackgroundTop,
        border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.BorderStrong)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(82.dp).clip(RoundedCornerShape(24.dp)).background(LabCoreSurface.Card),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ChildCare, null, tint = LabV2.Primary, modifier = Modifier.size(48.dp))
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).size(30.dp),
                    shape = CircleShape,
                    color = LabV2.Green.copy(alpha = .09f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Shield, null, tint = LabV2.Green, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("儿童上网", style = LabTypography.PageTitle)
                Surface(shape = RoundedCornerShape(50), color = LabV2.Cyan.copy(alpha = .10f)) {
                    Text(
                        "培养健康上网好习惯",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = LabTypography.Supporting.copy(color = LabV2.Primary, fontWeight = FontWeight.SemiBold)
                    )
                }
                Text("正在守护 $deviceCount 台设备", style = LabTypography.Body.copy(color = LabV2.InkMuted))
            }
        }
    }
}

@Composable
private fun MasterGuardCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    LabCoreCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabV2ToolIcon(Icons.Rounded.Shield, if (enabled) LabV2.Green else LabV2.InkMuted, size = 42)
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("全设备上网计划", style = LabTypography.CardTitle)
                    if (enabled) {
                        Surface(shape = RoundedCornerShape(20.dp), color = LabV2.Green.copy(alpha = .10f)) {
                            Text("管控中", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = LabTypography.Caption.copy(color = LabV2.Green, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                Text("关闭时，设备上网不受计划限制，报告仍会保留", style = LabTypography.Supporting)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(checkedTrackColor = LabV2.Cyan)
            )
        }
    }
}

@Composable
private fun ProtectedDeviceCard(
    device: ChildInternetDeviceState,
    onOpen: () -> Unit,
    onToggleBlocked: () -> Unit
) {
    val summary = device.summary
    val accent = Color(summary.accentArgb)
    var menuExpanded by remember { mutableStateOf(false) }
    LabCoreCard(
        modifier = Modifier.clickable(onClick = onOpen),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(18.dp), color = accent.copy(alpha = .08f)) {
                Box(Modifier.padding(7.dp)) {
                    LabMiniDeviceIcon(summary.iconKey, accent, sizeDp = 54)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary.name, style = LabTypography.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    summary.status.label,
                    style = LabTypography.Supporting.copy(
                        color = when (summary.status) {
                            GuardStatus.GUARDED -> LabV2.Green
                            GuardStatus.BLOCKED -> LabV2.Red
                            GuardStatus.UNRESTRICTED -> LabV2.InkMuted
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.MoreVert, "更多", tint = LabV2.InkMuted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("查看详情", style = LabTypography.Body) },
                        onClick = { menuExpanded = false; onOpen() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (summary.status == GuardStatus.BLOCKED) "恢复上网" else "一键禁网", style = LabTypography.Body) },
                        onClick = { menuExpanded = false; onToggleBlocked() }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.QueryStats, null, tint = LabV2.Primary.copy(alpha = .72f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(7.dp))
            Text("今日上网 ${formatChildDuration(summary.todayMinutes)}", style = LabTypography.Body.copy(color = LabV2.InkMuted), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onToggleBlocked, shape = RoundedCornerShape(50)) {
                Text(if (summary.status == GuardStatus.BLOCKED) "恢复上网" else "一键禁网", style = LabTypography.CompactButton, color = if (summary.status == GuardStatus.BLOCKED) LabV2.Green else LabV2.Cyan)
            }
        }
        if (summary.hasAttention) {
            Surface(shape = RoundedCornerShape(13.dp), color = LabV2.Red.copy(alpha = .075f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = LabV2.Red.copy(alpha = .14f), modifier = Modifier.size(22.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("!", style = LabTypography.Caption.copy(color = LabV2.Red, fontWeight = FontWeight.Bold)) }
                    }
                    Spacer(Modifier.size(7.dp))
                    Text("家长请注意", style = LabTypography.Supporting.copy(color = LabV2.Red, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.size(7.dp))
                    Text("今日有深夜上网记录", style = LabTypography.Supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
