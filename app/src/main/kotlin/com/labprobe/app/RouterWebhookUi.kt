package com.labprobe.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

data class WebhookEndpointRecord(
    val name: String,
    val address: String,
    val source: String,
    val updatedAt: String = ""
)

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
    onTestPort: (host: String, port: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val webhookList = remember(status, events) { parseWebhookEndpoints(status, events) }
    val webhookUrl = remember(prefs.hub, prefs.token) {
        val base = cleanApiText(prefs.hub).trimEnd('/')
        val tokenParam = if (prefs.token.isNotBlank()) "?token=${prefs.token}" else ""
        if (base.isNotBlank()) "$base/hook/lucky$tokenParam" else "未配置 Hub 地址"
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
                                webhookUrl,
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
                            Surface(
                                shape = LabCoreSurface.InnerShape,
                                color = LabCoreSurface.Inner,
                                border = BorderStroke(1.dp, LabCoreSurface.Border.copy(alpha = 0.7f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        copy(context, item.address)
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
                                                color = Color(0xFF0284C7).copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    item.source,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF0284C7),
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            item.address,
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
                                    val endpoint = parseQuickAccessEndpoint(item.address)
                                    val testHost = endpoint?.host?.removePrefix("[")?.removeSuffix("]").orEmpty()
                                    val testPort = endpoint?.port
                                    if (testHost.isNotBlank() && testPort != null && testPort in 1..65535) {
                                        OutlinedButton(
                                            onClick = {
                                                onTestPort(testHost, testPort.toString())
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
                            if (index != webhookList.lastIndex) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
