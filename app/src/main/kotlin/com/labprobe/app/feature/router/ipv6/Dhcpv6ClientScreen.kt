package com.labprobe.app.feature.router.ipv6

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labprobe.app.DetailShell
import com.labprobe.app.LabActionChip
import com.labprobe.app.LabCoreSurface
import com.labprobe.app.LabInfoRow
import com.labprobe.app.LabTypography
import com.labprobe.app.LabV2

@Composable
fun Dhcpv6ClientScreen(viewModel: Ipv6ViewModel, onBack: () -> Unit) {
    val state by viewModel.clients.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.loadClients()
    }

    DetailShell(
        title = "DHCPv6 客户端",
        subtitle = when {
            state.loading && !state.loaded -> "正在通过 Hub 读取客户端"
            state.loaded -> "共 ${state.clients.size} 个租约"
            else -> "查看地址、租期与 DUID"
        },
        onBack = onBack,
        compactHeader = true,
        unifiedTypography = true,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LabActionChip(
                text = if (state.loading) "刷新中" else "刷新",
                color = LabV2.Cyan,
                onClick = { viewModel.loadClients(force = true) },
            )
        }

        if (state.loading && !state.loaded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = LabCoreSurface.CompactShape,
                color = Color.White,
                border = BorderStroke(1.dp, LabCoreSurface.Border),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = LabV2.Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在读取 DHCPv6 租约", style = LabTypography.SectionTitle.copy(color = LabV2.Ink))
                }
            }
        }

        if (state.error.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = LabCoreSurface.InnerShape,
                color = LabV2.Red.copy(alpha = .07f),
                border = BorderStroke(1.dp, LabV2.Red.copy(alpha = .15f)),
            ) {
                Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp), tint = LabV2.Red)
                    Spacer(Modifier.width(8.dp))
                    Text(state.error, style = LabTypography.Supporting.copy(color = LabV2.Red, fontWeight = FontWeight.SemiBold))
                }
            }
        }

        if (state.loaded && state.clients.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = LabCoreSurface.CardShape,
                color = Color.White,
                border = BorderStroke(1.dp, LabCoreSurface.Border),
                shadowElevation = 1.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(48.dp).background(LabV2.Cyan.copy(alpha = .10f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Devices, null, Modifier.size(24.dp), tint = LabV2.Cyan)
                    }
                    Text("暂无 DHCPv6 客户端", style = LabTypography.CardTitle.copy(color = LabV2.Ink))
                    Text("客户端获取 IPv6 租约后会显示在这里", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                }
            }
        }

        state.clients.forEachIndexed { index, client ->
            Dhcpv6ClientCard(index = index, client = client)
        }
    }
}

@Composable
private fun Dhcpv6ClientCard(index: Int, client: Dhcpv6Client) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CardShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabCoreSurface.Border),
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(LabV2.Cyan.copy(alpha = .10f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Router, null, Modifier.size(20.dp), tint = LabV2.Cyan)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        client.hostname.ifBlank { "未命名客户端" },
                        style = LabTypography.CardTitle.copy(color = LabV2.Ink),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("客户端 ${index + 1}", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                }
                Surface(shape = RoundedCornerShape(99.dp), color = LabV2.Green.copy(alpha = .10f)) {
                    Text(
                        leaseText(client.leaseMinutes),
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = LabTypography.Caption.copy(color = LabV2.Green, fontWeight = FontWeight.Bold),
                    )
                }
            }
            Surface(
                shape = LabCoreSurface.InnerShape,
                color = LabCoreSurface.Inner,
                border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = .72f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    LabInfoRow("IPv6 地址", client.ipv6, accent = LabV2.Cyan)
                    LabInfoRow("剩余租期", leaseText(client.leaseMinutes), copyable = false, accent = LabV2.Cyan)
                    LabInfoRow("DUID", client.duid, accent = LabV2.Cyan)
                }
            }
        }
    }
}

private fun leaseText(minutes: Int): String = when {
    minutes <= 0 -> "即将到期"
    minutes < 60 -> "剩余 $minutes 分钟"
    minutes % 60 == 0 -> "剩余 ${minutes / 60} 小时"
    else -> "剩余 ${minutes / 60} 小时 ${minutes % 60} 分"
}
