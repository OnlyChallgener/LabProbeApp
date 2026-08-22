package com.labprobe.app.feature.router.ipv6

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labprobe.app.AppPrefs
import com.labprobe.app.CompactSegmentedControl
import com.labprobe.app.CompactTextField
import com.labprobe.app.DetailShell
import com.labprobe.app.LabActionChip
import com.labprobe.app.LabCoreSurface
import com.labprobe.app.LabInfoRow
import com.labprobe.app.LabTypography
import com.labprobe.app.LabV2

@Composable
fun Ipv6Screen(prefs: AppPrefs, onBack: () -> Unit) {
    val repository = remember(prefs.hub, prefs.token, prefs.hubDns) { Ipv6Repository(prefs) }
    val viewModel = remember(repository) { Ipv6ViewModel(repository) }
    var showingClients by remember { mutableStateOf(false) }

    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }
    LaunchedEffect(viewModel) {
        viewModel.load()
    }
    BackHandler(enabled = showingClients) {
        showingClients = false
    }

    if (showingClients) {
        Dhcpv6ClientScreen(viewModel = viewModel, onBack = { showingClients = false })
        return
    }

    val state by viewModel.state.collectAsState()
    DetailShell(
        title = "IPv6 设置",
        subtitle = "配置将通过 LabProbe Hub 安全同步",
        onBack = onBack,
        compactHeader = true,
        unifiedTypography = true,
    ) {
        Ipv6StatusCard(state = state, onRefresh = { viewModel.load(force = true) })

        if (state.config == null && state.loading) {
            Ipv6LoadingCard()
        } else if (state.config != null) {
            WanIpv6Card(form = state.form, onUpdate = viewModel::updateForm)
            LanIpv6Card(form = state.form, onUpdate = viewModel::updateForm)
            Dhcpv6ClientsEntry { showingClients = true }
            Ipv6MessageCard(error = state.error, notice = state.notice)
            Ipv6SaveArea(state = state, onSave = viewModel::save, onReset = viewModel::resetForm)
        } else {
            Ipv6MessageCard(error = state.error.ifBlank { "暂时无法读取 IPv6 配置" }, notice = "")
            Button(
                onClick = { viewModel.load(force = true) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = LabV2.ButtonShape,
            ) {
                Text("重新读取", style = LabTypography.Button)
            }
        }
    }
}

@Composable
private fun Ipv6StatusCard(state: Ipv6UiState, onRefresh: () -> Unit) {
    val status = state.status
    val connected = status?.connected == true
    val accent = if (connected) LabV2.Green else LabV2.Amber
    Ipv6SectionCard(
        title = "IPv6 连接状态",
        subtitle = when {
            connected -> "已连接 · ${ipv6ModeTitle(status?.proto.orEmpty())}"
            state.loading -> "正在通过 Hub 获取路由器状态"
            else -> "当前未取得可用的 IPv6 地址"
        },
        icon = if (connected) Icons.Rounded.CloudDone else Icons.Rounded.Public,
        accent = accent,
        action = {
            LabActionChip(
                text = if (state.refreshing) "刷新中" else "刷新",
                color = accent,
                onClick = onRefresh,
            )
        },
    ) {
        Surface(
            shape = LabCoreSurface.InnerShape,
            color = LabCoreSurface.Inner,
            border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = .72f)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabInfoRow("IPv6 地址", status?.address, accent = LabV2.Cyan)
                HorizontalDivider(color = LabCoreSurface.Border.copy(alpha = .65f))
                LabInfoRow("委派前缀", status?.prefix, accent = LabV2.Cyan)
                HorizontalDivider(color = LabCoreSurface.Border.copy(alpha = .65f))
                LabInfoRow("网关", status?.gateway, accent = LabV2.Cyan)
                HorizontalDivider(color = LabCoreSurface.Border.copy(alpha = .65f))
                LabInfoRow("DNS", status?.dns?.joinToString(", "), accent = LabV2.Cyan)
            }
        }
    }
}

@Composable
private fun WanIpv6Card(form: Ipv6FormState, onUpdate: ((Ipv6FormState) -> Ipv6FormState) -> Unit) {
    Ipv6SectionCard(
        title = "WAN IPv6",
        subtitle = "上网连接与 DNS 服务",
        icon = Icons.Rounded.Public,
        accent = LabV2.Cyan,
    ) {
        Ipv6FieldLabel("连接模式")
        CompactSegmentedControl(
            options = WanIpv6Mode.entries.map { it.title },
            selected = form.wanMode.title,
            onSelect = { selected ->
                onUpdate { it.copy(wanMode = WanIpv6Mode.entries.first { mode -> mode.title == selected }) }
            },
        )
        Text(
            if (form.wanMode == WanIpv6Mode.RELAY) "中继模式会将上游 IPv6 配置转发到局域网。" else "DHCPv6 会自动向上游获取 IPv6 地址和前缀。",
            style = LabTypography.Supporting.copy(color = LabV2.InkMuted),
        )

        Spacer(Modifier.height(2.dp))
        Ipv6FieldLabel("DNS 模式")
        CompactSegmentedControl(
            options = Ipv6DnsMode.entries.map { it.title },
            selected = form.dnsMode.title,
            onSelect = { selected ->
                onUpdate { it.copy(dnsMode = Ipv6DnsMode.entries.first { mode -> mode.title == selected }) }
            },
        )
        if (form.dnsMode == Ipv6DnsMode.MANUAL) {
            CompactTextField(
                value = form.manualDns,
                onValueChange = { value -> onUpdate { it.copy(manualDns = value) } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "例如 2400:3200::1，可用逗号分隔",
                leadingIcon = { Icon(Icons.Rounded.Dns, null, Modifier.size(18.dp), tint = LabV2.Cyan) },
            )
        } else {
            Text("DNS 将由上游 DHCPv6 自动分配。", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
        }
    }
}

@Composable
private fun LanIpv6Card(form: Ipv6FormState, onUpdate: ((Ipv6FormState) -> Ipv6FormState) -> Unit) {
    Ipv6SectionCard(
        title = "LAN IPv6",
        subtitle = "为局域网设备分配 IPv6 地址",
        icon = Icons.Rounded.Lan,
        accent = LabV2.Primary,
    ) {
        Ipv6SwitchRow(
            title = "DHCPv6 Server",
            subtitle = "向设备分配地址与租期",
            checked = form.dhcpv6Server,
        ) { checked -> onUpdate { it.copy(dhcpv6Server = checked) } }
        HorizontalDivider(color = LabCoreSurface.Border.copy(alpha = .65f))
        Ipv6SwitchRow(
            title = "SLAAC",
            subtitle = "允许设备自动生成 IPv6 地址",
            checked = form.slaac,
        ) { checked -> onUpdate { it.copy(slaac = checked) } }
        HorizontalDivider(color = LabCoreSurface.Border.copy(alpha = .65f))
        Ipv6SwitchRow(
            title = "RA 路由通告",
            subtitle = "向设备广播可用前缀和网关",
            checked = form.ra,
        ) { checked -> onUpdate { it.copy(ra = checked) } }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Ipv6FieldLabel("Prefix 长度")
                CompactTextField(
                    value = form.prefixLength,
                    onValueChange = { value -> onUpdate { it.copy(prefixLength = value.filter(Char::isDigit).take(3)) } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "64",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Ipv6FieldLabel("Lease 时间（分钟）")
                CompactTextField(
                    value = form.leaseMinutes,
                    onValueChange = { value -> onUpdate { it.copy(leaseMinutes = value.filter(Char::isDigit).take(5)) } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "120",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        if (!form.dhcpv6Server && !form.slaac) {
            Text(
                "DHCPv6 Server 与 SLAAC 均关闭后，局域网设备可能无法自动获取 IPv6 地址。",
                style = LabTypography.Supporting.copy(color = LabV2.Amber),
            )
        } else if (!form.ra) {
            Text("关闭 RA 可能导致设备无法发现 IPv6 前缀。", style = LabTypography.Supporting.copy(color = LabV2.Amber))
        }
    }
}

@Composable
private fun Dhcpv6ClientsEntry(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(LabCoreSurface.CompactShape).clickable(onClick = onClick),
        shape = LabCoreSurface.CompactShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabCoreSurface.Border),
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).background(LabV2.Cyan.copy(alpha = .10f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Router, null, Modifier.size(20.dp), tint = LabV2.Cyan)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("DHCPv6 客户端", style = LabTypography.CardTitle.copy(color = LabV2.Ink))
                Text("查看主机名、地址、剩余租期与 DUID", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
            }
            Surface(shape = CircleShape, color = LabV2.Cyan.copy(alpha = .09f)) {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.padding(7.dp).size(19.dp), tint = LabV2.Cyan)
            }
        }
    }
}

@Composable
private fun Ipv6SaveArea(state: Ipv6UiState, onSave: () -> Unit, onReset: () -> Unit) {
    state.form.validationError?.let { validation ->
        Text(validation, style = LabTypography.Supporting.copy(color = LabV2.Red), modifier = Modifier.padding(horizontal = 3.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.dirty) {
            TextButton(onClick = onReset, enabled = !state.saving) {
                Text("撤销修改", style = LabTypography.CompactButton.copy(color = LabV2.InkMuted))
            }
        }
        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = LabV2.ButtonShape,
            colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary),
        ) {
            if (state.saving) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.saving) "正在保存并校验" else "保存 IPv6 设置", style = LabTypography.Button)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Info, null, Modifier.padding(top = 1.dp).size(14.dp), tint = LabV2.Cyan)
        Spacer(Modifier.width(6.dp))
        Text(
            "保存后 IPv6 连接可能短暂中断；Hub 会先保留完整配置，再提交并回读校验。",
            style = LabTypography.Supporting.copy(color = LabV2.InkMuted),
        )
    }
}

@Composable
private fun Ipv6MessageCard(error: String, notice: String) {
    val text = error.ifBlank { notice }
    if (text.isBlank()) return
    val errorState = error.isNotBlank()
    val accent = if (errorState) LabV2.Red else LabV2.Green
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.InnerShape,
        color = accent.copy(alpha = .07f),
        border = BorderStroke(1.dp, accent.copy(alpha = .15f)),
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (errorState) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudDone, null, Modifier.size(18.dp), tint = accent)
            Spacer(Modifier.width(8.dp))
            Text(text, style = LabTypography.Supporting.copy(color = accent, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun Ipv6LoadingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CompactShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabCoreSurface.Border),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = LabV2.Cyan, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("正在读取 IPv6 配置", style = LabTypography.SectionTitle.copy(color = LabV2.Ink))
                Text("Hub 正在与路由器安全通信", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
            }
        }
    }
}

@Composable
private fun Ipv6SectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
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
                    Modifier.size(40.dp).background(accent.copy(alpha = .10f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = accent)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = LabTypography.CardTitle.copy(color = LabV2.Ink), maxLines = 1)
                    Text(
                        subtitle,
                        style = LabTypography.Supporting.copy(color = if (accent == LabV2.Green) accent else LabV2.InkMuted),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                action?.let {
                    Spacer(Modifier.width(8.dp))
                    it()
                }
            }
            content()
        }
    }
}

@Composable
private fun Ipv6SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = LabTypography.SectionTitle.copy(color = LabV2.Ink))
            Text(subtitle, style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Ipv6FieldLabel(text: String) {
    Text(text, style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted), modifier = Modifier.padding(start = 2.dp))
}

private fun ipv6ModeTitle(proto: String): String = when (proto.lowercase()) {
    "dhcpv6" -> "DHCPv6"
    "relay" -> "IPv6 中继"
    else -> proto.ifBlank { "IPv6" }
}
