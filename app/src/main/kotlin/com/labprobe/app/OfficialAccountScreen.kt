package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OfficialAccountScreen(
    prefs: AppPrefs,
    workspaces: List<RouterWorkspace>,
    onBack: () -> Unit,
) {
    val repository = remember(prefs.hubRoot, prefs.token) { OfficialAccountRepository(prefs) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<OfficialAccountSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }

    suspend fun loadStatus() {
        loading = true
        error = ""
        try {
            snapshot = repository.status(verify = true)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = uiMessageZh(failure.message.orEmpty())
        } finally {
            loading = false
        }
    }

    suspend fun refreshMatches() {
        if (busy) return
        busy = true
        error = ""
        try {
            snapshot = repository.refresh()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = uiMessageZh(failure.message.orEmpty())
        } finally {
            busy = false
        }
    }

    LaunchedEffect(repository) { loadStatus() }

    DetailShell(
        title = "锐捷官方账号",
        subtitle = "",
        onBack = onBack,
        action = {
            IconButton(onClick = { if (!busy && !loading) scope.launch { loadStatus() } }, enabled = !busy && !loading) {
                Icon(Icons.Rounded.Refresh, "刷新官方账号状态", Modifier.size(19.dp), tint = LabV2.Primary)
            }
        },
    ) {
        val current = snapshot
        when {
            loading && current == null -> Text("正在读取真实授权状态…", style = LabTypography.Supporting, color = LabV2.InkMuted)
            current == null -> OfficialAccountMessage(error.ifBlank { "暂时无法读取官方账号状态" })
            !current.supported -> OfficialAccountMessage(current.error.ifBlank { "当前 Hub 暂不支持锐捷官方账号" })
            current.state == "checking" -> OfficialAccountMessage("正在确认官方账号与项目匹配状态…")
            current.authorized || current.state in setOf("partial", "conflict", "failed", "pending_unknown") ->
                OfficialAccountAuthorizedContent(
                    snapshot = current,
                    workspaces = workspaces,
                    busy = busy,
                    onRefresh = { if (!busy) scope.launch { refreshMatches() } },
                    onLogout = { confirmLogout = true },
                )
            else -> OfficialAccountLoginContent(
                account = account,
                password = password,
                busy = busy,
                error = error,
                onAccount = { account = it },
                onPassword = { password = it },
                onLogin = {
                    if (account.isBlank() || password.isBlank() || busy) return@OfficialAccountLoginContent
                    val submittedPassword = password
                    password = ""
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            snapshot = repository.login(account, submittedPassword)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = uiMessageZh(failure.message.orEmpty())
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
        if (error.isNotBlank() && snapshot?.state in setOf("partial", "conflict", "failed", "pending_unknown")) {
            OfficialAccountMessage(error)
        }
    }

    if (confirmLogout) {
        LabCompactAlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出官方账号") },
            text = { Text("将解除各 HUB 的官方账号授权并清除已保存的官方 Token，不影响本地路由器配置、儿童上网规则和历史数据。") },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            val result = repository.logout()
                            snapshot = result
                            error = result.error
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = uiMessageZh(failure.message.orEmpty())
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy) { Text("退出登录", color = LabV2.Red) }
            },
        )
    }
}

@Composable
private fun OfficialAccountMessage(message: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, LabV2.Border)) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(16.dp),
            style = LabTypography.Body,
            color = if (message.contains("失败") || message.contains("异常")) LabV2.Red else LabV2.InkMuted,
        )
    }
}

@Composable
internal fun OfficialAccountLoginContent(
    account: String,
    password: String,
    busy: Boolean,
    error: String,
    onAccount: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, LabV2.Border.copy(alpha = .78f)),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFFE8F4FC)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, null, Modifier.size(32.dp), tint = LabV2.Primary)
            }
            Text("官方账号登录", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
            Text("未授权", style = LabTypography.Supporting, color = LabV2.InkMuted)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("账号", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted), modifier = Modifier.padding(start = 2.dp))
                    CompactTextField(
                        value = account,
                        onValueChange = onAccount,
                        placeholder = "手机号 / 官方账号",
                    )
                }
                SettingsPasswordField(
                    label = "密码",
                    value = password,
                    onValueChange = onPassword,
                    placeholder = "输入账号密码",
                )
            }
            if (error.isNotBlank()) {
                Text(error, color = LabV2.Red, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Button(
                onClick = onLogin,
                enabled = !busy && account.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                shape = LabV2.ButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = LabV2.Primary, contentColor = Color.White),
            ) { Text(if (busy) "正在登录并匹配" else "登录并绑定", style = LabTypography.Button, color = Color.White) }
        }
    }
}

@Composable
internal fun OfficialAccountAuthorizedContent(
    snapshot: OfficialAccountSnapshot,
    workspaces: List<RouterWorkspace>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    val rows = snapshot.routers.map { row ->
        val workspace = workspaces.firstOrNull {
            it.serverRouterId.equals(row.routerId, true) || it.routerId.equals(row.routerId, true) ||
                (row.serialNumber.isNotBlank() && it.serialNumber.equals(row.serialNumber, true))
        }
        row.copy(
            name = row.name.ifBlank { workspace?.name.orEmpty() },
            site = row.site.ifBlank { workspace?.site.orEmpty() },
            model = row.model.ifBlank { workspace?.model.orEmpty() },
            online = row.online,
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, LabV2.Border.copy(alpha = .78f)),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFFE8F4FC)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Person, null, Modifier.size(27.dp), tint = LabV2.Primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        snapshot.account.ifBlank { "已授权账号" },
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabV2.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (snapshot.state == "authorized") LabV2.Green else LabV2.Amber))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (snapshot.state) {
                                "partial" -> "部分授权"
                                "conflict" -> "账号冲突"
                                "failed" -> "退出未完成"
                                "pending_unknown" -> "退出待确认"
                                "checking" -> "待确认"
                                else -> "已登录"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (snapshot.state == "authorized") LabV2.Green else LabV2.Amber,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = LabV2.Border)
            Spacer(Modifier.height(13.dp))
            OfficialAccountMetric("官方项目", "${snapshot.projectCount} 个")
            OfficialAccountMetric("已匹配路由器", "${snapshot.matchedRouterCount} 台")
            OfficialAccountMetric("HUB 授权", officialHubStatusZh(snapshot.hubAuthorization, snapshot.state))
            if (snapshot.expireTime > 0L) {
                val expiry = remember(snapshot.expireTime) {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(snapshot.expireTime))
                }
                OfficialAccountMetric("授权有效期", expiry)
            }
            if (snapshot.state == "partial" || snapshot.state == "conflict") {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (snapshot.state == "conflict") "不同 HUB 的官方账号不一致，请重新登录以统一授权。"
                    else "部分 HUB 离线或同步失败，请刷新后重试。",
                    style = LabTypography.Supporting,
                    color = LabV2.Amber,
                )
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, LabV2.Border.copy(alpha = .78f)),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已匹配路由器", fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = LabV2.Ink)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !busy) {
                    Icon(Icons.Rounded.Refresh, "刷新项目匹配", Modifier.size(18.dp), tint = LabV2.Primary)
                }
            }
            if (rows.isEmpty()) Text("暂无已匹配路由器", style = LabTypography.Supporting, color = LabV2.InkMuted)
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.name.ifBlank { row.routerId },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabV2.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val project = row.projectName.ifBlank { row.site }
                        if (project.isNotBlank()) {
                            Text(project, style = LabTypography.Supporting, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (row.error.isNotBlank()) {
                            Text(row.error, style = LabTypography.Caption, color = LabV2.Amber, maxLines = 2)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        officialRouterStatusZh(row),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            row.authorized && row.matched -> LabV2.Green
                            row.online -> LabV2.Amber
                            else -> LabV2.InkMuted
                        },
                    )
                }
                HorizontalDivider(color = LabV2.Border, thickness = .5.dp)
            }
        }
    }
    OutlinedButton(
        onClick = onLogout,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        shape = LabV2.ButtonShape,
        border = BorderStroke(1.dp, LabV2.Red.copy(alpha = .32f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = LabV2.Red),
    ) {
        Text("退出登录", style = LabTypography.Button, color = LabV2.Red)
    }
    }
}

@Composable
private fun OfficialAccountMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LabTypography.Body, color = LabV2.InkMuted)
        Spacer(Modifier.weight(1f))
        Text(value, style = LabTypography.ValueStrong, color = if (value == "已同步") LabV2.Green else LabV2.Ink)
    }
}

private fun officialHubStatusZh(raw: String, state: String): String = when (raw) {
    "synced" -> "已同步"
    "partial" -> "部分同步"
    "offline" -> "部分离线"
    "failed" -> "同步失败"
    "unauthorized" -> "未授权"
    else -> if (state == "authorized") "已授权" else "待检查"
}

internal fun officialRouterStatusZh(row: OfficialRouterAuthorization): String = when {
    !row.online -> "Hub 离线"
    row.syncState == "failed" -> "同步失败"
    row.matched && row.authorized -> "已匹配"
    row.authorized -> "部分授权"
    row.syncState == "checking" -> "待确认"
    row.syncState == "serial_unknown" -> "待确认 SN"
    row.syncState == "ambiguous" -> "匹配异常"
    else -> "未匹配"
}
