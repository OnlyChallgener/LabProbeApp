package com.labprobe.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class WebhookEndpointRecord(
    val name: String,
    val address: String,
    val source: String,
    val updatedAt: String = ""
)

internal data class ParsedWebhookUrl(val baseUrl: String, val token: String)

internal data class WebhookEndpointSettings(
    val customEnabled: Boolean,
    val baseUrl: String,
    val hookToken: String,
)

private val WEBHOOK_TOKEN_KEYS = setOf("token", "hook_token", "hooktoken")

private fun queryDecode(value: String): String = runCatching {
    URLDecoder.decode(value, "UTF-8")
}.getOrDefault(value)

private fun appendWebhookQuery(url: String, query: String): String =
    if (url.contains('?')) "$url&$query" else "$url?$query"

internal fun parseWebhookUrl(raw: String): ParsedWebhookUrl {
    val value = raw.trim()
    require(value.isNotBlank()) { "请输入 Webhook 地址" }
    val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Webhook 地址格式无效") }
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Webhook 地址仅支持 HTTP/HTTPS" }
    require(!uri.host.isNullOrBlank()) { "Webhook 地址缺少有效主机" }
    require(uri.rawUserInfo == null) { "Webhook 地址不能包含用户信息" }
    require(uri.rawFragment == null) { "Webhook 地址不能包含片段" }
    require(uri.port == -1 || uri.port in 1..65535) { "Webhook 端口无效" }
    val kept = mutableListOf<String>()
    var token = ""
    uri.rawQuery?.split('&')?.forEach { pair ->
        if (pair.isBlank()) return@forEach
        val rawKey = pair.substringBefore('=')
        val key = queryDecode(rawKey).trim().lowercase(Locale.ROOT)
        if (key in WEBHOOK_TOKEN_KEYS) {
            if (token.isBlank()) token = queryDecode(pair.substringAfter('=', ""))
        } else {
            kept += pair
        }
    }
    val base = buildString {
        append(uri.scheme.lowercase(Locale.ROOT)).append("://").append(uri.rawAuthority).append(uri.rawPath)
        if (kept.isNotEmpty()) append('?').append(kept.joinToString("&"))
    }
    return ParsedWebhookUrl(base, token)
}

internal fun sanitizeWebhookBaseUrl(raw: String): String =
    runCatching { parseWebhookUrl(raw).baseUrl }.getOrDefault("")

internal fun defaultWebhookEndpoint(hubUrl: String): String =
    hubUrl.trim().trimEnd('/').takeIf(String::isNotEmpty)?.let { "$it/hook/lucky" }.orEmpty()

internal fun webhookEndpointSettings(
    customEnabled: Boolean,
    customUrl: String,
    hookToken: String,
    defaultUrl: String,
): WebhookEndpointSettings {
    val parsed = if (customEnabled) parseWebhookUrl(customUrl) else parseWebhookUrl(defaultUrl)
    val token = hookToken.trim().ifBlank { parsed.token }
    require(token.isNotBlank()) { "请填写该路由的 HOOK_TOKEN" }
    return WebhookEndpointSettings(customEnabled, parsed.baseUrl, token)
}

internal fun buildWebhookUrl(baseUrl: String, token: String): String {
    if (baseUrl.isBlank()) return ""
    val parsed = parseWebhookUrl(baseUrl)
    val effectiveToken = token.trim().ifBlank { parsed.token }
    if (effectiveToken.isBlank()) return parsed.baseUrl
    val encoded = URLEncoder.encode(effectiveToken, "UTF-8")
    return appendWebhookQuery(parsed.baseUrl, "token=$encoded")
}

internal fun maskWebhookUrl(url: String): String {
    if (url.isBlank()) return url
    val parsed = runCatching { parseWebhookUrl(url) }.getOrNull() ?: return url
    if (parsed.token.isBlank()) return parsed.baseUrl
    return appendWebhookQuery(parsed.baseUrl, "token=••••••••")
}

fun parseWebhookEndpoints(status: JSONObject?, events: List<EventItem>): List<WebhookEndpointRecord> {
    val results = mutableListOf<WebhookEndpointRecord>()
    val data = status?.optJSONObject("data") ?: status
    val nasObj = data?.optJSONObject("nas")
    val routerObj = data?.optJSONObject("router")
    val nasIpv6 = cleanApiText(nasObj?.optString("exitIpv6"))
        .ifBlank { cleanApiText(nasObj?.optString("ipv6")) }
        .ifBlank { cleanApiText(routerObj?.optString("wan6")) }
    val seenNames = mutableSetOf<String>()

    fun addRecord(nameRaw: String, addressRaw: String, sourceRaw: String, timeRaw: String = "") {
        var address = cleanApiText(addressRaw)
        if (address.isBlank()) return
        val lower = address.lowercase(Locale.getDefault())
        if ((lower.startsWith("ipv6:") || lower.startsWith("[ipv6]:")) && nasIpv6.isNotBlank()) {
            val port = address.substringAfterLast(":")
            address = if (nasIpv6.contains(":")) "[$nasIpv6]:$port" else "$nasIpv6:$port"
        }
        val name = cleanApiText(nameRaw).ifBlank { "Webhook 服务" }
        val key = name.lowercase(Locale.getDefault())
        if (seenNames.contains(key)) return
        seenNames.add(key)
        results.add(
            WebhookEndpointRecord(
                name = name,
                address = address,
                source = cleanApiText(sourceRaw).ifBlank { "Webhook" },
                updatedAt = cleanApiText(timeRaw)
            )
        )
    }

    // 1. 从 vpnStunAddresses 或 vpnAddresses 提取非纯 STUN 来源的 Webhook 记录
    val list = data?.optJSONArray("vpnStunAddresses") ?: data?.optJSONArray("vpnAddresses")
    if (list != null) {
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val src = o.optString("source")
            val name = o.optString("name", o.optString("service"))
            val addr = o.optString("address", o.optString("stun"))
            val time = o.optString("updatedAt")
            addRecord(name, addr, if (src.isNotBlank()) src else "Webhook", time)
        }
    }

    // 2. 从 luckyStun / vpn 字典提取
    val luckyObj = data?.optJSONObject("luckyStun")
    if (luckyObj != null) {
        addRecord(
            luckyObj.optString("name", "Lucky"),
            luckyObj.optString("address", luckyObj.optString("stun")),
            luckyObj.optString("source", "Lucky Webhook"),
            luckyObj.optString("updatedAt")
        )
    }

    val vpnObj = data?.optJSONObject("vpn")
    if (vpnObj != null) {
        val keys = vpnObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val obj = vpnObj.optJSONObject(k) ?: continue
            addRecord(
                obj.optString("name", k),
                obj.optString("address", obj.optString("stun")),
                obj.optString("source", "Webhook"),
                obj.optString("updatedAt")
            )
        }
    }

    // 3. 从最近事件流提取 webhook 相关动态推送
    events.forEach { e ->
        val typ = e.type.lowercase(Locale.getDefault())
        if (typ.contains("webhook") || typ.contains("lucky") || typ.contains("stun_changed")) {
            val n = cleanApiText(e.name).ifBlank {
                cleanApiText(e.title).replace("STUN 地址变化", "").replace("地址变化", "").trim()
            }.ifBlank { "Lucky" }
            val addr = cleanApiText(e.newValue).ifBlank { cleanApiText(e.ip) }
            addRecord(n, addr, if (typ.contains("lucky")) "Lucky Webhook" else "事件推送", e.time)
        }
    }

    return results
}

@Composable
fun RouterWebhookScreen(
    prefs: AppPrefs,
    status: JSONObject?,
    events: List<EventItem>,
    onBack: () -> Unit,
    onTestPort: (host: String, port: String) -> Unit = { _, _ -> },
    onTestUdp: (host: String, port: String, template: String) -> Unit = { _, _, _ -> }
) {
    BackHandler(enabled = true) { onBack() }
    val context = LocalContext.current
    var portOverridesRevision by remember { mutableIntStateOf(0) }
    var remarkOverridesRevision by remember { mutableIntStateOf(0) }
    var endpointRevision by remember { mutableIntStateOf(0) }

    var showEndpointDialog by remember { mutableStateOf(false) }
    var endpointCustomMode by remember { mutableStateOf(false) }
    var endpointInput by remember { mutableStateOf("") }
    var hookTokenInput by remember { mutableStateOf("") }
    var hookTokenVisible by remember { mutableStateOf(false) }
    var endpointError by remember { mutableStateOf("") }

    var showPortDialog by remember { mutableStateOf(false) }
    var targetPortItem by remember { mutableStateOf<WebhookEndpointRecord?>(null) }
    var portInputValue by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf("") }

    var showRemarkDialog by remember { mutableStateOf(false) }
    var targetRemarkItem by remember { mutableStateOf<WebhookEndpointRecord?>(null) }
    var remarkInputValue by remember { mutableStateOf("") }

    val webhookList = remember(status, events) { parseWebhookEndpoints(status, events) }
    val webhookUrl = remember(prefs.hub, prefs.hookToken, prefs.webhookCustomEnabled, prefs.webhookCustomUrl, endpointRevision) {
        val base = if (prefs.webhookCustomEnabled && prefs.webhookCustomUrl.isNotBlank()) {
            prefs.webhookCustomUrl
        } else {
            defaultWebhookEndpoint(prefs.hub)
        }
        buildWebhookUrl(base, prefs.hookToken)
    }
    val displayedWebhookUrl = maskWebhookUrl(webhookUrl).ifBlank { "未配置 Webhook 地址" }

    fun openEndpointEditor() {
        endpointCustomMode = prefs.webhookCustomEnabled
        endpointInput = if (prefs.webhookCustomEnabled) prefs.webhookCustomUrl else defaultWebhookEndpoint(prefs.hub)
        hookTokenInput = prefs.hookToken
        hookTokenVisible = false
        endpointError = ""
        showEndpointDialog = true
    }

    if (showEndpointDialog) {
        LabCompactAlertDialog(
            onDismissRequest = { showEndpointDialog = false },
            title = { Text("编辑 Webhook 地址", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = { endpointCustomMode = false; endpointError = "" },
                            shape = RoundedCornerShape(9.dp),
                            color = if (!endpointCustomMode) Color(0xFF0284C7).copy(alpha = 0.12f) else LabCoreSurface.Inner
                        ) {
                            Text(
                                "自动生成",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (!endpointCustomMode) Color(0xFF0284C7) else LabV2.InkMuted
                            )
                        }
                        Surface(
                            onClick = { endpointCustomMode = true; endpointError = "" },
                            shape = RoundedCornerShape(9.dp),
                            color = if (endpointCustomMode) Color(0xFF0284C7).copy(alpha = 0.12f) else LabCoreSurface.Inner
                        ) {
                            Text(
                                "自定义地址",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (endpointCustomMode) Color(0xFF0284C7) else LabV2.InkMuted
                            )
                        }
                    }
                    OutlinedTextField(
                        value = endpointInput,
                        onValueChange = { input ->
                            val parsed = runCatching { parseWebhookUrl(input) }.getOrNull()
                            if (parsed != null) {
                                endpointInput = parsed.baseUrl
                                if (parsed.token.isNotBlank()) hookTokenInput = parsed.token
                            } else {
                                endpointInput = input
                            }
                            endpointError = ""
                        },
                        enabled = endpointCustomMode,
                        label = { Text(if (endpointCustomMode) "Webhook 地址" else "自动地址") },
                        singleLine = false,
                        maxLines = 3,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = hookTokenInput,
                        onValueChange = { hookTokenInput = it; endpointError = "" },
                        label = { Text("HOOK_TOKEN") },
                        singleLine = true,
                        visualTransformation = if (hookTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { hookTokenVisible = !hookTokenVisible }) {
                                Icon(
                                    if (hookTokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    if (hookTokenVisible) "隐藏 HOOK_TOKEN" else "显示 HOOK_TOKEN"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (endpointError.isNotBlank()) {
                        Text(endpointError, color = Color(0xFFEF4444), fontSize = 11.5.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val settings = runCatching {
                            webhookEndpointSettings(
                                endpointCustomMode,
                                endpointInput,
                                hookTokenInput,
                                defaultWebhookEndpoint(prefs.hub),
                            )
                        }.getOrElse {
                            endpointError = it.message ?: "Webhook 地址格式无效"
                            return@Button
                        }
                        runCatching {
                            prefs.hookToken = settings.hookToken
                            prefs.webhookCustomEnabled = settings.customEnabled
                            prefs.webhookCustomUrl = settings.baseUrl
                            endpointRevision++
                            showEndpointDialog = false
                            toast(context, "Webhook 地址已保存")
                        }.onFailure {
                            endpointError = it.message ?: "安全存储失败，请重试"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) { Text("保存", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val settings = runCatching {
                            webhookEndpointSettings(false, "", hookTokenInput, defaultWebhookEndpoint(prefs.hub))
                        }.getOrElse {
                            endpointError = it.message ?: "Webhook 地址格式无效"
                            return@TextButton
                        }
                        runCatching {
                            prefs.hookToken = settings.hookToken
                            prefs.webhookCustomEnabled = false
                            prefs.webhookCustomUrl = ""
                            endpointRevision++
                            showEndpointDialog = false
                            toast(context, "已恢复自动地址")
                        }.onFailure {
                            endpointError = it.message ?: "安全存储失败，请重试"
                        }
                    }) { Text("恢复默认", color = LabV2.InkMuted) }
                    TextButton(onClick = { showEndpointDialog = false }) { Text("取消", color = LabV2.InkMuted) }
                }
            }
        )
    }

    if (showPortDialog && targetPortItem != null) {
        LabCompactAlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text("编辑端口", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "为 ${targetPortItem?.name ?: "服务"} 自由配置公网端口（1-65535）：",
                        fontSize = 12.5.sp,
                        color = LabV2.InkMuted
                    )
                    OutlinedTextField(
                        value = portInputValue,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(5)
                            portInputValue = filtered
                            portError = if (filtered.isNotBlank()) {
                                val p = filtered.toIntOrNull()
                                if (p == null || p !in 1..65535) "端口范围须为 1 ~ 65535" else ""
                            } else ""
                        },
                        isError = portError.isNotBlank(),
                        supportingText = if (portError.isNotBlank()) { { Text(portError, color = Color(0xFFEF4444), fontSize = 11.sp) } } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text("例如 20009", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = portInputValue.toIntOrNull()
                        if (p == null || p !in 1..65535) {
                            portError = "请输入有效的端口号 (1-65535)"
                            return@Button
                        }
                        targetPortItem?.let { itm ->
                            prefs.setWebhookPortOverride(itm.name, p.toString())
                            prefs.setWebhookPortOverride("__first__", p.toString())
                        }
                        portOverridesRevision++
                        showPortDialog = false
                        toast(context, "端口已修改为 $p")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("保存", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val hasOverride = prefs.getWebhookPortOverride(targetPortItem?.name.orEmpty()).isNotBlank() ||
                            prefs.getWebhookPortOverride("__first__").isNotBlank()
                    if (hasOverride) {
                        TextButton(
                            onClick = {
                                targetPortItem?.let { itm ->
                                    prefs.setWebhookPortOverride(itm.name, "")
                                }
                                prefs.setWebhookPortOverride("__first__", "")
                                portOverridesRevision++
                                showPortDialog = false
                                toast(context, "已恢复默认端口")
                            }
                        ) {
                            Text("恢复默认", color = LabV2.InkMuted)
                        }
                    }
                    TextButton(onClick = { showPortDialog = false }) {
                        Text("取消", color = LabV2.InkMuted)
                    }
                }
            }
        )
    }

    if (showRemarkDialog && targetRemarkItem != null) {
        LabCompactAlertDialog(
            onDismissRequest = { showRemarkDialog = false },
            title = { Text("修改备注", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "为 ${targetRemarkItem?.name ?: "服务"} 自定义备注或服务标识：",
                        fontSize = 12.5.sp,
                        color = LabV2.InkMuted
                    )
                    OutlinedTextField(
                        value = remarkInputValue,
                        onValueChange = { remarkInputValue = it.take(24) },
                        singleLine = true,
                        placeholder = { Text("例如 Lucky Webhook / 自建节点", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = remarkInputValue.trim()
                        targetRemarkItem?.let { itm ->
                            prefs.setWebhookRemarkOverride(itm.name, clean)
                        }
                        remarkOverridesRevision++
                        showRemarkDialog = false
                        toast(context, if (clean.isNotBlank()) "备注已更新为: $clean" else "备注已恢复默认")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("保存", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val hasOverride = prefs.getWebhookRemarkOverride(targetRemarkItem?.name.orEmpty()).isNotBlank()
                    if (hasOverride) {
                        TextButton(
                            onClick = {
                                targetRemarkItem?.let { itm ->
                                    prefs.setWebhookRemarkOverride(itm.name, "")
                                }
                                remarkOverridesRevision++
                                showRemarkDialog = false
                                toast(context, "已恢复默认备注")
                            }
                        ) {
                            Text("恢复默认", color = LabV2.InkMuted)
                        }
                    }
                    TextButton(onClick = { showRemarkDialog = false }) {
                        Text("取消", color = LabV2.InkMuted)
                    }
                }
            }
        )
    }

    DetailShell(
        title = "Webhook 设置",
        subtitle = "Lucky / 动态服务推送与公网地址归集",
        onBack = onBack,
        compactHeader = true,
        unifiedTypography = true
    ) {
            // Card 1: Webhook 接收端点配置
            Surface(
                shape = LabCoreSurface.CardShape,
                color = Color.White,
                border = BorderStroke(1.dp, LabCoreSurface.Border),
                shadowElevation = 1.5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0284C7).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.CloudUpload, null, tint = Color(0xFF0284C7), modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Webhook 接收端点", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
                            Text("用于在 Lucky 或脚本中上报公网 IP 与端口", fontSize = 11.5.sp, color = LabV2.InkMuted)
                        }
                        IconButton(onClick = { openEndpointEditor() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Edit, "编辑 Webhook 地址", tint = Color(0xFF0284C7), modifier = Modifier.size(18.dp))
                        }
                    }

                    Surface(
                        shape = LabCoreSurface.InnerShape,
                        color = LabCoreSurface.Inner,
                        border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                displayedWebhookUrl,
                                modifier = Modifier.weight(1f),
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0F172A),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                onClick = {
                                    copy(context, webhookUrl)
                                    toast(context, "已复制 Webhook URL")
                                },
                                enabled = webhookUrl.isNotBlank(),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF0284C7),
                            ) {
                                Text(
                                    "复制链接",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Card 2: Lucky / 钉钉格式配置示例
            Surface(
                shape = LabCoreSurface.CardShape,
                color = Color.White,
                border = BorderStroke(1.dp, LabCoreSurface.Border),
                shadowElevation = 1.5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Code, null, tint = Color(0xFF64748B), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lucky 推送格式说明", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
                    }
                    Text(
                        "在 Lucky 的 Webhook 推送内容中，推荐使用以下 JSON 格式。冒号前填写服务名，冒号后填写变量 #{ipAddr}，Hub 将自动提取归集：",
                        fontSize = 11.5.sp,
                        color = LabV2.InkMuted,
                        lineHeight = 16.sp
                    )
                    Surface(
                        shape = LabCoreSurface.InnerShape,
                        color = Color(0xFF0F172A),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "{\n  \"msgtype\": \"text\",\n  \"text\": {\n    \"content\": \"服务名: #{ipAddr}\"\n  }\n}",
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE2E8F0),
                                lineHeight = 16.sp
                            )
                            Surface(
                                onClick = {
                                    copy(context, "{\n  \"msgtype\": \"text\",\n  \"text\": {\n    \"content\": \"服务名: #{ipAddr}\"\n  }\n}")
                                    toast(context, "已复制请求体模板")
                                },
                                shape = RoundedCornerShape(7.dp),
                                color = Color.White.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    "复制模板",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Card 3: 已接收的 Webhook 服务地址列表
            Surface(
                shape = LabCoreSurface.CardShape,
                color = Color.White,
                border = BorderStroke(1.dp, LabCoreSurface.Border),
                shadowElevation = 1.5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已接收地址 (${webhookList.size})",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabV2.Ink
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "点击地址即刻复制",
                            fontSize = 11.sp,
                            color = LabV2.InkMuted
                        )
                    }

                    if (webhookList.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.Inbox, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(36.dp))
                                Text("暂未接收到 Webhook 服务地址", fontSize = 12.5.sp, color = LabV2.InkMuted)
                                Text("请在 Lucky 中配置上方 Webhook 接收端点", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    } else {
                        webhookList.forEachIndexed { index, item ->
                            val isFirstItem = (index == 0)
                            val overridePort = remember(item.name, index, portOverridesRevision) {
                                prefs.getWebhookPortOverride(item.name).ifBlank {
                                    if (isFirstItem) prefs.getWebhookPortOverride("__first__") else ""
                                }
                            }
                            val displayAddress = remember(item.address, overridePort) {
                                if (overridePort.isNotBlank()) {
                                    val endpoint = parseQuickAccessEndpoint(item.address)
                                    if (endpoint != null) {
                                        formatServiceHostPort(endpoint.host, overridePort.toIntOrNull())
                                    } else {
                                        val clean = overridePort.trim()
                                        if (item.address.startsWith("[") && item.address.contains("]:")) {
                                            item.address.substringBeforeLast("]:") + "]:$clean"
                                        } else if (item.address.contains(":")) {
                                            item.address.substringBeforeLast(":") + ":$clean"
                                        } else {
                                            "${item.address}:$clean"
                                        }
                                    }
                                } else item.address
                            }
                            val displayRemark = remember(item.name, item.source, remarkOverridesRevision) {
                                prefs.getWebhookRemarkOverride(item.name).ifBlank { item.source }
                            }
                            Surface(
                                shape = LabCoreSurface.InnerShape,
                                color = LabCoreSurface.Inner,
                                border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = 0.7f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        copy(context, displayAddress)
                                        toast(context, "已复制 ${item.name} 地址")
                                    }
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(item.name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFF0284C7).copy(alpha = 0.12f),
                                                modifier = Modifier.clickable {
                                                    targetRemarkItem = item
                                                    remarkInputValue = displayRemark
                                                    showRemarkDialog = true
                                                }
                                            ) {
                                                Text(
                                                    displayRemark,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF0284C7),
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            displayAddress,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF0F172A)
                                        )
                                        if (item.updatedAt.isNotBlank()) {
                                            Text("更新时间：${item.updatedAt}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (isFirstItem) {
                                        OutlinedButton(
                                            onClick = {
                                                targetPortItem = item
                                                val endpoint = parseQuickAccessEndpoint(displayAddress)
                                                portInputValue = endpoint?.port?.toString()
                                                    ?: displayAddress.substringAfterLast(":", "").filter { it.isDigit() }
                                                portError = ""
                                                showPortDialog = true
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                                            border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.4f)),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("编辑", fontSize = 10.5.sp, color = Color(0xFF0284C7), fontWeight = FontWeight.SemiBold)
                                        }
                                    } else {
                                        val endpoint = parseQuickAccessEndpoint(displayAddress)
                                        val testHost = endpoint?.host?.removePrefix("[")?.removeSuffix("]").orEmpty()
                                        val testPort = endpoint?.port
                                        if (testHost.isNotBlank() && testPort != null && testPort in 1..65535) {
                                            val lower = (item.name + " " + item.source).lowercase(Locale.getDefault())
                                            val isUdpVpn = lower.contains("wg") || lower.contains("wireguard") || lower.contains("openvpn")
                                            OutlinedButton(
                                                onClick = {
                                                    if (isUdpVpn) {
                                                        val tpl = if (lower.contains("openvpn")) "UDP 空包" else "WireGuard 握手"
                                                        onTestUdp(testHost, testPort.toString(), tpl)
                                                    } else {
                                                        onTestPort(testHost, testPort.toString())
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                                                border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.4f)),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("测端口", fontSize = 10.5.sp, color = Color(0xFF0284C7), fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }
                            if (index != webhookList.lastIndex) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
