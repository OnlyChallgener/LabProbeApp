package com.labprobe.app

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SettingsBlue = Color(0xFF2563EB)
private val SettingsCyan = Color(0xFF0891B2)
private val SettingsGreen = Color(0xFF16A36A)
private val SettingsAmber = Color(0xFFF59E0B)
private val SettingsPurple = Color(0xFF7C3AED)
private val SettingsInk = Color(0xFF17233A)
private val SettingsMuted = Color(0xFF687890)
private val SettingsBorder = Color(0xFFE3EAF4)

@Composable
fun RouterSettingsHomeCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = Color.White,
        border = BorderStroke(1.dp, SettingsBlue.copy(alpha = .13f)),
        shadowElevation = 3.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(SettingsBlue.copy(alpha = .075f), Color.White, SettingsCyan.copy(alpha = .035f))))
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(52.dp).background(SettingsBlue.copy(alpha = .11f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Router, null, Modifier.size(29.dp), tint = SettingsBlue)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("路由设置", fontSize = 16.sp, fontWeight = FontWeight.Black, color = SettingsInk)
                Text(
                    "防火墙 · 映射与 UPnP · 远程访问 · 网络自检",
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SettingsMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(shape = CircleShape, color = SettingsBlue.copy(alpha = .09f)) {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.padding(7.dp).size(20.dp), tint = SettingsBlue)
            }
        }
    }
}

@Composable
fun RouterSettingsScreen(prefs: AppPrefs, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterControlApi(prefs) }
    var status by remember { mutableStateOf(RouterHubStatus()) }
    var capabilities by remember { mutableStateOf(RouterCapabilities()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
        loading = true
        runCatching { api.hubStatus() }.onSuccess { status = it }
        runCatching { api.capabilities() }.onSuccess { capabilities = it }
        loading = false
    }

    DetailShell(
        title = "路由设置",
        subtitle = "路由器状态保持独立 · 此处只放配置与操作",
        onBack = onBack,
        compactHeader = true
    ) {
        RouterSettingsConnectionCard(status = status, loading = loading) { onOpen("tool_router_login") }

        RouterSettingsSection("转发与安全") {
            RouterSettingsTile(
                title = "映射与 UPnP",
                subtitle = "IPv6 映射、原生端口映射与 UPnP",
                icon = Icons.Rounded.AccountTree,
                color = SettingsBlue,
                enabled = capabilities.nativePortMapping || capabilities.upnp
            ) { onOpen("tool_portmap") }
            RouterSettingsTile(
                title = "防火墙",
                subtitle = "入站、出站与转发规则",
                icon = Icons.Rounded.Security,
                color = SettingsGreen,
                enabled = capabilities.firewall
            ) { onOpen("tool_router_firewall") }
        }

        RouterSettingsSection("远程访问") {
            RouterSettingsTile(
                title = "DDNS 与证书",
                subtitle = "动态域名、远程入口与证书提醒",
                icon = Icons.Rounded.CloudSync,
                color = SettingsCyan,
                enabled = capabilities.ddns
            ) { onOpen("tool_router_ddns") }
            RouterSettingsTile(
                title = "WOL 管理",
                subtitle = "管理离线设备并发送唤醒包",
                icon = Icons.Rounded.PowerSettingsNew,
                color = SettingsPurple,
                enabled = true
            ) { onOpen("wol") }
        }

        RouterSettingsSection("维护与诊断") {
            RouterSettingsTile(
                title = "网络自检",
                subtitle = "仅在手动点击时检测物理接线与协商速率",
                icon = Icons.Rounded.MonitorHeart,
                color = SettingsAmber,
                enabled = capabilities.diagnostic
            ) { onOpen("tool_router_diag") }
        }
    }
}

@Composable
private fun RouterSettingsConnectionCard(status: RouterHubStatus, loading: Boolean, onClick: () -> Unit) {
    val connected = status.connected
    val accent = if (connected) SettingsGreen else SettingsAmber
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        color = Color.White,
        border = BorderStroke(1.dp, accent.copy(alpha = .17f)),
        shadowElevation = 1.dp
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(accent.copy(alpha = .10f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
                else Icon(Icons.Rounded.Hub, null, Modifier.size(21.dp), tint = accent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (connected) "Hub 已连接路由器" else "检查 Hub 路由连接", fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = SettingsInk)
                Text(status.message, fontSize = 9.7.sp, color = SettingsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = accent)
        }
    }
}

@Composable
private fun RouterSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = SettingsInk, modifier = Modifier.padding(start = 3.dp))
        content()
    }
}

@Composable
private fun RouterSettingsTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val actualColor = if (enabled) color else SettingsMuted
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, SettingsBorder),
        shadowElevation = 1.dp
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(39.dp).background(actualColor.copy(alpha = .10f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = actualColor)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 12.3.sp, fontWeight = FontWeight.Black, color = if (enabled) SettingsInk else SettingsMuted)
                Text(subtitle, fontSize = 9.6.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = SettingsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = actualColor.copy(alpha = if (enabled) 1f else .45f))
        }
    }
}
