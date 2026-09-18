package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * "选择要管理的设备"页面 —— 对应官方 APP 加入/移除守护设备的入口。
 * 数据来自 `GET /devices/candidates`（DHCP 租约表），勾选后逐台加入守护，
 * 已守护设备展示"已守护"徽章并可一键移除。
 */
@Composable
fun ChildInternetDevicePickerScreen(
    repository: ChildInternetRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var candidates by remember { mutableStateOf<List<ChildGuardDeviceCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var selectedMacs by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        error = ""
        repository.loadCandidates { result ->
            loading = false
            result.onSuccess { candidates = it }.onFailure { error = it.message ?: "加载设备失败" }
        }
    }

    LaunchedEffect(repository) { reload() }

    val unguarded = candidates.filter { !it.guarded }
    val selectable = unguarded.filter { it.mac in selectedMacs }
    val guardedCount = candidates.count { it.guarded }

    fun commitSelection() {
        if (busy || selectable.isEmpty()) return
        busy = true
        var remaining = selectable.size
        var failures = 0
        selectable.forEach { candidate ->
            repository.addGuardDevice(candidate.mac, candidate.displayName) { result ->
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
                toast(context, "已移除 ${candidate.displayName}")
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
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = LabV2.Cyan.copy(alpha = .08f)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
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
            Spacer(Modifier.height(12.dp))
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
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在扫描局域网设备…", style = LabTypography.Body.copy(color = LabV2.InkMuted))
                    }
                    error.isNotBlank() -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabV2ToolIcon(Icons.Rounded.ChildCare, LabV2.InkMuted, size = 46, muted = true)
                            Text(error, style = LabTypography.Body.copy(color = LabV2.Red))
                            TextButton(onClick = { reload() }) { Text("重试", style = LabTypography.Body.copy(color = LabV2.Cyan, fontWeight = FontWeight.SemiBold)) }
                        }
                    }
                    candidates.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            LabV2ToolIcon(Icons.Rounded.ChildCare, LabV2.InkMuted, size = 46, muted = true)
                            Text("没有发现可管理的设备", style = LabTypography.SectionTitle)
                            Text("请确认设备已连接本路由器", style = LabTypography.Supporting)
                        }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                        items(candidates, key = { it.mac }) { candidate ->
                            CandidateDeviceRow(
                                candidate = candidate,
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
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val iconKey = remember(candidate) { childGuardCandidateIconKey(candidate.hostname) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && !candidate.guarded) { onToggle(!selected) }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = LabV2.Field) {
            Box(Modifier.padding(6.dp)) {
                LabMiniDeviceIcon(iconKey, DEVICE_ICON_ACCENT, sizeDp = 42)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(candidate.displayName, style = LabTypography.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                }
            }
            Text(
                if (candidate.ip.isNotBlank()) "${candidate.ip} · ${candidate.mac}" else candidate.mac,
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

/** 从 DHCP 主机名推断设备类型图标，无法识别时回落 unknown。 */
private fun childGuardCandidateIconKey(hostname: String): String {
    val text = hostname.lowercase()
    return when {
        listOf("ipad", "matepad", "galaxy tab", "xiaoxin pad", "redmi pad", "mi pad", "pad", "平板", "tablet").any { text.contains(it) } -> "tablet"
        listOf("iphone", "手机", "phone", "pixel", "oneplus", "oppo", "vivo", "iqoo", "realme", "meizu", "nubia").any { text.contains(it) } -> "phone"
        listOf("华为", "huawei", "mate60", "mate70", "pura", "nova").any { text.contains(it) } -> "huawei_phone"
        listOf("macbook", "matebook", "magicbook", "redmibook", "laptop", "notebook", "笔记本").any { text.contains(it) } -> "laptop"
        listOf("mac mini", "macmini", "mac-mini").any { text.contains(it) } -> "mac_mini"
        listOf("mini pc", "minipc", "nuc", "迷你主机", "小主机").any { text.contains(it) } -> "mini_pc"
        listOf("all in one", "all-in-one", "aio", "imac", "一体机").any { text.contains(it) } -> "all_in_one"
        listOf("desktop", "台式", "电脑", "computer", "pc").any { text.contains(it) } -> "desktop"
        listOf("nas", "群晖", "威联通", "极空间", "飞牛").any { text.contains(it) } -> "nas"
        listOf("tv", "电视", "television", "客厅").any { text.contains(it) } -> "tv"
        listOf("watch", "手表", "手环", "band", "小天才", "米兔").any { text.contains(it) } -> "watch"
        listOf("camera", "监控", "摄像", "ipc", "萤石", "海康", "大华").any { text.contains(it) } -> "camera"
        listOf("音箱", "音响", "speaker", "小爱", "天猫精灵").any { text.contains(it) } -> "speaker"
        listOf("路由器", "router", "be72", "rg-", "reyee", "ruijie", "unifi", "openwrt", "软路由").any { text.contains(it) } -> "router"
        else -> "unknown"
    }
}
