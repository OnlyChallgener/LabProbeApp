package com.labprobe.app.feature.router.firewall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.labprobe.app.DeviceItem
import com.labprobe.app.FirewallRule
import com.labprobe.app.LabCoreSurface
import com.labprobe.app.LabTypography
import com.labprobe.app.PortMapRule
import com.labprobe.app.cleanMac
import com.labprobe.app.deviceDisplayName

private val FollowBlue = Color(0xFF2E6BE6)
private val FollowGreen = Color(0xFF16A36A)
private val FollowAmber = Color(0xFFF59E0B)
private val FollowRed = Color(0xFFE94B55)
private val FollowInk = Color(0xFF17233A)
private val FollowMuted = Color(0xFF687890)
private val FollowBorder = Color(0xFFD9E8F7)
private val FollowPage = Color(0xFFF2F8FF)

fun firewallAutomationStatusLabel(status: String): String = when (status.lowercase()) {
    "synced" -> "已同步"
    "pending" -> "待同步"
    "waiting_target" -> "等待地址"
    "missing_rule" -> "原规则缺失"
    "unsupported" -> "规则已改变"
    "disabled" -> "已暂停"
    else -> "自动跟随"
}

fun firewallAutomationStatusColor(status: String): Color = when (status.lowercase()) {
    "synced" -> FollowGreen
    "pending" -> FollowBlue
    "waiting_target", "unsupported" -> FollowAmber
    "missing_rule" -> FollowRed
    else -> FollowMuted
}

private fun directionLabel(direction: String): String = when (direction) {
    "inbound" -> "入站"
    "outbound" -> "出站"
    else -> "转发"
}

@Composable
fun FirewallAutomationPage(
    rule: FirewallRule,
    binding: FirewallAutomationBinding?,
    targets: FirewallAutomationTargets,
    targetsLoading: Boolean,
    busy: Boolean,
    externalError: String,
    onBack: () -> Unit,
    onRefreshTargets: () -> Unit,
    onSave: (FirewallAutomationBinding) -> Unit,
    onStop: () -> Unit,
    onSync: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val inferredField = if (rule.ipv6SuffixDest.isNotBlank()) "ipv6SuffixDest" else "destIP"
    var enabled by remember(rule.uuid, binding?.enabled) { mutableStateOf(binding?.enabled ?: true) }
    var targetType by remember(rule.uuid, binding?.targetType) {
        mutableStateOf(binding?.targetType ?: if (rule.direction == "inbound") "router" else "device")
    }
    var targetMac by remember(rule.uuid, binding?.targetMac) { mutableStateOf(binding?.targetMac.orEmpty()) }
    var mappingId by remember(rule.uuid, binding?.mappingId) { mutableStateOf(binding?.mappingId.orEmpty()) }
    var picker by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf("") }
    val field = binding?.matchField?.ifBlank { inferredField } ?: inferredField
    val family = rule.ipVersion.lowercase()
    val existingAddress = if (field == "ipv6SuffixDest") rule.ipv6SuffixDest else rule.destIP
    val compatibleMappings = targets.mappings.filter { mapping ->
        if (family == "ipv4") mapping.mode == "6to4" else mapping.mode != "6to4"
    }
    val selectedDevice = targets.devices.firstOrNull { cleanMac(it.mac) == cleanMac(targetMac) }
    val selectedMapping = compatibleMappings.firstOrNull { it.id == mappingId }
    val modeValid = family in setOf("ipv4", "ipv6") && existingAddress.isNotBlank()
    val error = localError.ifBlank { externalError }

    Scaffold(
        containerColor = FollowPage,
        topBar = {
            Surface(color = Color.White) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.ArrowBack, null, Modifier.size(20.dp), tint = FollowInk)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("目的地址自动跟随", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.Bold, color = FollowInk)
                        Text("认领现有规则 · 原位安全更新", fontSize = LabTypography.Caption.fontSize, color = FollowMuted)
                    }
                    IconButton(onClick = onRefreshTargets, enabled = !targetsLoading && !busy, modifier = Modifier.size(36.dp)) {
                        if (targetsLoading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, null, Modifier.size(19.dp), tint = FollowBlue)
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                FollowCard(FollowBlue) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).background(FollowBlue.copy(alpha = .1f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Sync, null, Modifier.size(19.dp), tint = FollowBlue)
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(rule.ruleName, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.Bold, color = FollowInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${directionLabel(rule.direction)} · ${rule.ipVersion.uppercase()} · ${rule.proto.uppercase()}", fontSize = LabTypography.Caption.fontSize, color = FollowMuted)
                            Text("端口 ${rule.destPort.ifBlank { "任意" }} · $existingAddress", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = FollowInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            binding?.let { current ->
                item {
                    val statusColor = firewallAutomationStatusColor(current.status)
                    FollowCard(statusColor) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(firewallAutomationStatusLabel(current.status), fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.Bold, color = statusColor)
                                Text(current.statusMessage.ifBlank { "自动跟随状态已更新" }, fontSize = LabTypography.Caption.fontSize, color = FollowMuted)
                            }
                            if (!busy) OutlinedButton(onClick = onSync, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("立即核对", fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        AddressCompare("规则当前值", current.currentAddress)
                        AddressCompare("目标最新值", current.desiredAddress.ifBlank { "尚未确认，保持原值" })
                    }
                }
            }

            item {
                Text("跟随目标", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.Bold, color = FollowInk, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FollowTargetChoice("终端设备", "device", Icons.Rounded.Devices, targetType, Modifier.weight(1f)) { targetType = it; localError = "" }
                    FollowTargetChoice("路由器", "router", Icons.Rounded.Router, targetType, Modifier.weight(1f)) { targetType = it; localError = "" }
                    FollowTargetChoice("映射规则", "mapping", Icons.Rounded.Link, targetType, Modifier.weight(1f)) { targetType = it; localError = "" }
                }
            }

            if (targetType == "device") item {
                TargetPickerCard(
                    title = selectedDevice?.let(::deviceDisplayName) ?: "选择终端设备",
                    subtitle = selectedDevice?.let { "${if (it.online) "在线" else "离线"} · ${cleanMac(it.mac)}" } ?: "以 MAC 作为稳定身份，地址由 HUB 实时确认",
                    icon = Icons.Rounded.Devices,
                    enabled = !busy,
                ) { picker = "device" }
            }
            if (targetType == "mapping") item {
                TargetPickerCard(
                    title = selectedMapping?.name ?: "选择映射规则",
                    subtitle = selectedMapping?.let { "${it.modeText} · ${it.transportProtocol} · ${it.listenPort}" } ?: "使用现有映射运行时已确认的目标地址",
                    icon = Icons.Rounded.Link,
                    enabled = !busy,
                ) { picker = "mapping" }
            }
            if (targetType == "router") item {
                FollowCard(FollowBlue) {
                    Text("跟随路由器本身", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.Bold, color = FollowInk)
                    Text(
                        if (family == "ipv6") "使用路由器当前 WAN IPv6；适合入站规则。" else "使用路由器当前 WAN IPv4；适合入站规则。",
                        fontSize = LabTypography.Caption.fontSize,
                        color = FollowMuted,
                    )
                }
            }

            item {
                FollowCard(if (modeValid) FollowGreen else FollowAmber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("只更新${if (field == "ipv6SuffixDest") "目的 IPv6 后缀" else "目的 IP"}", fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.Bold, color = FollowInk)
                            Text("端口（含区间和多端口）、方向、协议、接口、动作、开关、顺序及未知字段全部原样保留。", fontSize = LabTypography.Caption.fontSize, color = FollowMuted)
                        }
                        Switch(
                            checked = enabled,
                            enabled = !busy,
                            onCheckedChange = { enabled = it },
                            modifier = Modifier.scale(.82f),
                            colors = SwitchDefaults.colors(checkedTrackColor = FollowGreen),
                        )
                    }
                    if (!modeValid) {
                        Text(
                            if (family == "dual") "双栈规则没有单一地址字段，请分别建立 IPv4 和 IPv6 规则。" else "请先在原规则中填写要跟随的目的地址或目的 IPv6 后缀。",
                            fontSize = LabTypography.Supporting.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            color = FollowAmber,
                        )
                    }
                }
            }

            if (error.isNotBlank()) item {
                Surface(shape = RoundedCornerShape(12.dp), color = FollowRed.copy(alpha = .08f), border = BorderStroke(1.dp, FollowRed.copy(alpha = .22f))) {
                    Text(error, Modifier.fillMaxWidth().padding(10.dp), fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = FollowRed)
                }
            }

            item {
                Button(
                    onClick = {
                        localError = when {
                            !modeValid -> "原规则的地址匹配方式暂不支持自动跟随"
                            targetType == "device" && targetMac.isBlank() -> "请选择终端设备"
                            targetType == "mapping" && mappingId.isBlank() -> "请选择映射规则"
                            else -> ""
                        }
                        if (localError.isBlank()) {
                            onSave(
                                FirewallAutomationBinding(
                                    firewallUuid = rule.uuid,
                                    enabled = enabled,
                                    targetType = targetType,
                                    targetMac = if (targetType == "device") cleanMac(targetMac) else "",
                                    mappingId = if (targetType == "mapping") mappingId else "",
                                    addressFamily = family,
                                    matchField = field,
                                ),
                            )
                        }
                    },
                    enabled = !busy && modeValid,
                    modifier = Modifier.fillMaxWidth().height(43.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FollowBlue),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(if (binding == null) "开启并立即安全核对" else "保存自动跟随设置", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.Bold)
                }
            }
            if (binding != null) item {
                OutlinedButton(onClick = onStop, enabled = !busy, modifier = Modifier.fillMaxWidth().height(41.dp), shape = RoundedCornerShape(13.dp)) {
                    Text("停止跟随（保留原规则）", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = FollowRed)
                }
            }
        }
    }

    if (picker == "device") {
        TargetDialog(
            title = "选择终端设备",
            emptyText = if (targetsLoading) "设备列表正在刷新" else "暂无可选设备",
            onDismiss = { picker = "" },
            rows = targets.devices,
            key = { cleanMac(it.mac) },
            headline = { deviceDisplayName(it) },
            supporting = { "${if (it.online) "在线" else "离线"} · ${it.ip.ifBlank { "无 IPv4" }} · ${cleanMac(it.mac)}" },
        ) { device -> targetMac = cleanMac(device.mac); picker = ""; localError = "" }
    }
    if (picker == "mapping") {
        TargetDialog(
            title = "选择映射规则",
            emptyText = if (targetsLoading) "映射列表正在刷新" else "没有与 ${family.uppercase()} 匹配的映射",
            onDismiss = { picker = "" },
            rows = compatibleMappings,
            key = { it.id },
            headline = { it.name },
            supporting = { "${it.modeText} · ${it.transportProtocol} · 外部端口 ${it.listenPort}" },
        ) { mapping -> mappingId = mapping.id; picker = ""; localError = "" }
    }
}

@Composable
private fun FollowCard(accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = LabCoreSurface.CompactShape,
        color = LabCoreSurface.Card,
        border = BorderStroke(1.dp, LabCoreSurface.Border),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = .18f), CircleShape))
            content()
        }
    }
}

@Composable
private fun AddressCompare(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(78.dp), fontSize = LabTypography.Caption.fontSize, color = FollowMuted)
        Text(value.ifBlank { "--" }, Modifier.weight(1f), fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = FollowInk)
    }
}

@Composable
private fun FollowTargetChoice(label: String, value: String, icon: ImageVector, selected: String, modifier: Modifier, onPick: (String) -> Unit) {
    val active = value == selected
    Surface(
        modifier = modifier.clickable { onPick(value) },
        shape = RoundedCornerShape(12.dp),
        color = if (active) FollowBlue.copy(alpha = .1f) else Color.White,
        border = BorderStroke(1.dp, if (active) FollowBlue.copy(alpha = .3f) else FollowBorder),
    ) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, null, Modifier.size(17.dp), tint = if (active) FollowBlue else FollowMuted)
            Text(label, fontSize = LabTypography.Caption.fontSize, fontWeight = FontWeight.SemiBold, color = if (active) FollowBlue else FollowMuted, maxLines = 1)
        }
    }
}

@Composable
private fun TargetPickerCard(title: String, subtitle: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = LabCoreSurface.CompactShape,
        color = Color.White,
        border = BorderStroke(1.dp, FollowBorder),
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(35.dp).background(FollowBlue.copy(alpha = .09f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(19.dp), tint = FollowBlue)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.Bold, color = FollowInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = LabTypography.Caption.fontSize, color = FollowMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = FollowMuted)
        }
    }
}

@Composable
private fun <T> TargetDialog(
    title: String,
    emptyText: String,
    onDismiss: () -> Unit,
    rows: List<T>,
    key: (T) -> String,
    headline: (T) -> String,
    supporting: (T) -> String,
    onPick: (T) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = LabTypography.SectionTitle.fontSize, fontWeight = FontWeight.Bold, color = FollowInk)
                if (rows.isEmpty()) {
                    Text(emptyText, Modifier.fillMaxWidth().padding(vertical = 28.dp), fontSize = LabTypography.Supporting.fontSize, color = FollowMuted)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rows, key = key) { row ->
                            Surface(
                                Modifier.fillMaxWidth().clickable { onPick(row) },
                                shape = RoundedCornerShape(13.dp),
                                color = FollowPage,
                                border = BorderStroke(1.dp, FollowBorder),
                            ) {
                                Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(headline(row), fontSize = LabTypography.Value.fontSize, fontWeight = FontWeight.SemiBold, color = FollowInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(supporting(row), fontSize = LabTypography.Caption.fontSize, color = FollowMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("取消", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
