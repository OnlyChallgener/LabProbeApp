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

private fun routerSettingsRawMessageZh(raw: String): String {
    val text = raw.trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> "正在检查路由器状态"
        "hub is online, but router data is unavailable" in lower -> "Hub 已连接，暂未取得路由控制数据"
        "waiting for hub status" in lower -> "正在等待 Hub 状态"
        "timeout" in lower || "timed out" in lower -> "状态请求超时，已保留上次结果"
        else -> text
    }
}

private fun routerSettingsStatusMessage(status: RouterHubStatus): String = when {
    status.connected && status.state == "ready" -> "路由控制链路正常"
    status.state == "syncing" -> "路由器会话已建立，正在等待控制数据"
    status.errorCode == "HUB_NO_ROUTER_DATA" -> "Hub 已连接，暂未取得路由控制数据"
    status.state == "unconfigured" -> "尚未配置路由器管理地址和密码"
    status.state == "router_login_failed" -> "路由器连接失败，请检查密码或网络"
    else -> routerSettingsRawMessageZh(status.message)
}

@Composable
fun RouterSettingsHomeCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE7EDF4)),
        shadowElevation = 2.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).background(SettingsBlue.copy(alpha = .11f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Router, null, Modifier.size(29.dp), tint = SettingsBlue)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("路由设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SettingsInk)
                Text(
                    "防火墙 · NAT诊断 · DDNS · Beta升级",
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
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val statusResource by repository.status.collectAsState()
    val capabilityResource by repository.capabilities.collectAsState()
    val status = statusResource.value
    val capabilities = capabilityResource.value ?: RouterCapabilities(
        configured = true,
        dashboard = true,
        devices = true,
        firewall = true,
        nativePortMapping = true,
        upnp = true,
        ddns = true,
        diagnostic = true
    )

    DetailShell(
        title = "路由设置",
        subtitle = "已预加载配置快照 · 页面打开不重复请求",
        onBack = onBack,
        compactHeader = true
    ) {
        RouterSettingsConnectionCard(statusResource) { onOpen("tool_router_login") }

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
                title = "DDNS",
                subtitle = "LabProbe DDNS · 路由器原生 DDNS · 证书监控",
                icon = Icons.Rounded.CloudSync,
                color = SettingsCyan,
                enabled = capabilities.ddns || capabilities.configured
            ) { onOpen("tool_router_ddns") }
        }

        RouterSettingsSection("诊断与升级") {
            RouterSettingsTile(
                title = "网络自检",
                subtitle = "仅在手动点击时检测物理接线与协商速率",
                icon = Icons.Rounded.MonitorHeart,
                color = SettingsAmber,
                enabled = capabilities.diagnostic
            ) { onOpen("tool_router_diag") }
            RouterSettingsTile(
                title = "路由 NAT 诊断",
                subtitle = "路由器原生 RFC3489 / RFC5780 检测",
                icon = Icons.Rounded.Radar,
                color = SettingsPurple,
                enabled = true
            ) { onOpen("tool_router_nat") }
            RouterSettingsTile(
                title = "Beta 在线升级",
                subtitle = "显示上次快照，点击后才检测",
                icon = Icons.Rounded.SystemUpdateAlt,
                color = SettingsCyan,
                enabled = true
            ) { onOpen("tool_router_beta") }
        }
    }
}

@Composable
private fun RouterSettingsConnectionCard(resource: RouterResource<RouterHubStatus>, onClick: () -> Unit) {
    val status = resource.value
    val sessionConnected = status?.sessionConnected == true || status?.connected == true
    val accent = if (sessionConnected) SettingsGreen else SettingsAmber
    val title = when {
        sessionConnected && status?.dataAvailable == true -> "路由控制链路正常"
        sessionConnected -> "路由器会话正常"
        resource.value == null -> "正在准备路由设置"
        else -> "路由控制暂不可用"
    }
    val detail = when {
        resource.value == null -> "APP 已在后台预加载，不需要进入页面后再等待"
        resource.error.isNotBlank() -> "后台同步较慢，已保留上次状态"
        sessionConnected && status?.dataAvailable != true -> "控制数据正在静默同步，实时 WSS 不受影响"
        else -> status?.message.orEmpty().ifBlank { "路由设置快照已就绪" }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        color = Color.White,
        border = BorderStroke(1.dp, accent.copy(alpha = .17f)),
        shadowElevation = 1.dp
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(accent.copy(alpha = .10f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Hub, null, Modifier.size(21.dp), tint = accent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = SettingsInk)
                Text(detail, fontSize = 9.7.sp, color = SettingsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
