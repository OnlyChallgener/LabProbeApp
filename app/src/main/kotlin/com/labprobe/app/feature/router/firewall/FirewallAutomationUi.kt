package com.labprobe.app.feature.router.firewall

import androidx.compose.ui.draw.clip

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
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
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
import com.labprobe.app.FirewallRule
import com.labprobe.app.LabCoreSurface
import com.labprobe.app.LabTypography

private val FollowBlue = Color(0xFF0284C7)
private val FollowGreen = Color(0xFF16A36A)
private val FollowAmber = Color(0xFFF59E0B)
private val FollowRed = Color(0xFFE94B55)
private val FollowInk = Color(0xFF17233A)
private val FollowMuted = Color(0xFF687890)
private val FollowBorder = Color(0xFFD9E8F7)
private val FollowPage = Color(0xFFF8FBFF)

fun firewallAutomationStatusLabel(status: String): String = when (status.lowercase()) {
    "synced" -> "已同步"
    "pending" -> "待同步"
    "waiting_target" -> "等待地址"
    "missing_rule" -> "原规则缺失"
    "unsupported" -> "规则已改变"
    "manual_override" -> "人工修改优先"
    "out_of_scope" -> "已停止接管"
    "disabled" -> "已暂停"
    else -> "自动跟随"
}

fun firewallAutomationStatusColor(status: String): Color = when (status.lowercase()) {
    "synced" -> FollowGreen
    "pending" -> FollowBlue
    "waiting_target", "unsupported", "manual_override", "out_of_scope" -> FollowAmber
    "missing_rule" -> FollowRed
    else -> FollowMuted
}

private fun directionLabel(direction: String): String = when (direction) {
    "inbound" -> "入站"
    "outbound" -> "出站"
    else -> "转发"
}

private fun portContains(spec: String, target: String): Boolean {
    val port = target.trim().toIntOrNull() ?: return false
    return spec.replace('-', ':').split(',').any { item ->
        val part = item.trim()
        if (part.toIntOrNull() == port) return@any true
        val range = part.split(':', limit = 2).mapNotNull(String::toIntOrNull)
        range.size == 2 && port in range[0]..range[1]
    }
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
    var mappingKind by remember(rule.uuid, binding?.mappingKind) { mutableStateOf(binding?.mappingKind ?: "relay") }
    var mappingId by remember(rule.uuid, binding?.mappingId) { mutableStateOf(binding?.mappingId.orEmpty()) }
    var picker by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }
    val field = binding?.matchField?.ifBlank { inferredField } ?: inferredField
    val family = rule.ipVersion.lowercase()
    val existingAddress = if (field == "ipv6SuffixDest") rule.ipv6SuffixDest else rule.destIP
    val compatibleMappings = targets.mappings.filter { mapping ->
        mapping.addressFamily == family &&
            mapping.protocol.lowercase().replace("/", "+").split('+').contains(rule.proto.lowercase()) &&
            portContains(rule.destPort, mapping.targetPort)
    }
    val selectedMapping = compatibleMappings.firstOrNull { it.kind == mappingKind && it.id == mappingId }
    val modeValid = family in setOf("ipv4", "ipv6") &&
        existingAddress.isNotBlank() &&
        rule.target.equals("ACCEPT", true) &&
        rule.direction == "forward" &&
        rule.inIface.equals("wan", true) &&
        rule.outIface.equals("lan", true)
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
                Text("关联的路由器映射", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.Bold, color = FollowInk, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            }
            item {
                TargetPickerCard(
                    title = selectedMapping?.name ?: "选择映射规则",
                    subtitle = selectedMapping?.let { "${it.modeText} · ${it.protocol} · 外部 ${it.externalPort} → 目标 ${it.targetPort}" }
                        ?: "仅显示与当前 IP 版本、协议和目的端口精确匹配的映射",
                    icon = Icons.Rounded.Link,
                    enabled = !busy,
                ) { picker = true }
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
                            when {
                                family == "dual" -> "双栈规则没有单一地址字段，请分别建立 IPv4 和 IPv6 规则。"
                                !rule.target.equals("ACCEPT", true) -> "丢弃规则不参与映射自动化。"
                                rule.direction != "forward" || !rule.inIface.equals("wan", true) || !rule.outIface.equals("lan", true) -> "映射只能关联 WAN 到 LAN 的转发规则。"
                                else -> "请先在原规则中填写要跟随的目的地址或目的 IPv6 后缀。"
                            },
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
                            mappingId.isBlank() -> "请选择映射规则"
                            else -> ""
                        }
                        if (localError.isBlank()) {
                            onSave(
                                FirewallAutomationBinding(
                                    firewallUuid = rule.uuid,
                                    enabled = enabled,
                                    targetType = "mapping",
                                    mappingKind = mappingKind,
                                    mappingId = mappingId,
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
                    else Text(
                        when {
                            binding == null -> "建立映射联动并安全核对"
                            binding.status == "manual_override" -> "确认当前规则并恢复联动"
                            else -> "保存映射联动设置"
                        },
                        fontSize = LabTypography.Supporting.fontSize,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (binding != null) item {
                OutlinedButton(onClick = onStop, enabled = !busy, modifier = Modifier.fillMaxWidth().height(41.dp), shape = RoundedCornerShape(13.dp)) {
                    Text("停止跟随（保留原规则）", fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = FollowRed)
                }
            }
        }
    }

    if (picker) {
        TargetDialog(
            title = "选择映射规则",
            emptyText = if (targetsLoading) "映射列表正在刷新" else "没有与 ${family.uppercase()} 匹配的映射",
            onDismiss = { picker = false },
            rows = compatibleMappings,
            key = { "${it.kind}:${it.id}" },
            headline = { it.name },
            supporting = { "${it.modeText} · ${it.protocol} · 外部 ${it.externalPort} → 目标 ${it.targetPort}" },
        ) { mapping -> mappingKind = mapping.kind; mappingId = mapping.id; picker = false; localError = "" }
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
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(value) },
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
        Modifier.fillMaxWidth().clip(LabCoreSurface.CompactShape).clickable(enabled = enabled, onClick = onClick),
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
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).clickable { onPick(row) },
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
