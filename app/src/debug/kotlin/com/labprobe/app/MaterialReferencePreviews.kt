package com.labprobe.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import org.json.JSONObject

/** Debug-only fixtures: no Hub connection, network test, or production state mutation. */
@Composable
fun MaterialReferencePreviewScreen(screen: String) {
    val context = LocalContext.current
    val prefs = remember {
        AppPrefs(context).apply {
            hub = ""
            pingHost = "example.com"
            pingProtocol = "ICMP"
            pingPort = "443"
            pingCount = "200"
            pingInterval = "1000"
            pingTimeout = "自动"
            homeOrder = "score,mini,router,exit,vpn,devices,today"
        }
    }
    val first = remember { materialReferenceDevice("书房笔记本", "02:00:00:00:00:01", "192.168.1.24", "pc") }
    val second = remember { materialReferenceDevice("客厅手机", "02:00:00:00:00:02", "192.168.1.32", "phone") }
    val state = remember {
        AppState(prefs, context).apply {
            devices = listOf(first, second)
            onlineDevices = devices
            hubConnected = true
            mqttConnected = true
            realtimeDataFresh = true
            message = "实时同步正常"
            status = JSONObject("""{"data":{"nas":{"exitIpv4":"203.0.113.8","exitIpv6":"2001:db8::8"},"router":{"exitIpv4":"203.0.113.8"}}}""")
        }
    }
    DisposableEffect(state) { onDispose { state.close() } }
    val nav: @Composable () -> Unit = {
        OneUiTopNav(
            listOf("首页", "设备", "工具", "记录", "收藏"),
            listOf(Icons.Rounded.Dashboard, Icons.Rounded.Router, Icons.Rounded.Build, Icons.Rounded.History, Icons.Rounded.Star),
            if (screen == "devices") 1 else 0,
            onSelect = {},
        )
    }
    val route = when (screen) {
        "home", "sheet" -> "home"
        "devices" -> "devices"
        "detail" -> "device_detail"
        else -> "tool_ping"
    }
    LabMaterialReferenceTheme(route) {
        when (screen) {
            "home" -> HomeScreen(prefs, state, "实时", {}, {}, {}, nav)
            "devices" -> DevicesScreen(state, nav, {}, {})
            "detail" -> DeviceDetailScreen(state, first.mac, {}, {}, {})
            "ping" -> PingScreen(prefs, {})
            "result" -> DetailShell("延迟测试", "ICMP · IPv6 · 真实时间轴", {}) {
                PingLatencyCard(materialReferencePingPoints(), LabV2.Primary, {})
                ExpressiveCard("响应日志") { ResultText("IPv6 · example.com\n2001:db8::8\nseq=1  time=18ms\nseq=2  time=21ms") }
            }
            "sheet" -> Box(Modifier.fillMaxSize()) {
                DevicesScreen(state, nav, {}, {})
                LabDeviceEditSheet(first, state, {})
            }
            "dialog" -> Box(Modifier.fillMaxSize()) {
                PingScreen(prefs, {})
                PingHistoryDialog(listOf(materialReferencePingHistory()), 512, {}, {})
            }
        }
    }
}

private fun materialReferenceDevice(name: String, mac: String, ip: String, type: String) = DeviceItem(
    name = name,
    mac = mac,
    online = true,
    ip = ip,
    ssid = "LabProbe Home",
    band = "5 GHz",
    rssi = "-48 dBm",
    rxrate = "866 Mbps",
    onlineSince = "",
    offlineAt = "",
    onlineDurationText = "2小时18分",
    lastSeenAt = "",
    ipv6 = listOf("2001:db8:1234:5678:abcd:ef01:2345:6789"),
    manualType = type,
    remark = name,
    followedOverride = true,
    todayUpload = "256 MB",
    todayDownload = "1.8 GB",
    connectType = "wifi",
)

private fun materialReferencePingPoints() = listOf(18, 21, 19, 22, 20, 24, 18, 19, 23, 20).mapIndexed { index, ms ->
    PingPoint(index + 1, ms, "seq=${index + 1} time=${ms}ms", (index + 1) * 1000L)
}

private fun materialReferencePingHistory() = PingHistoryEntry(
    1, "2026-09-08 14:30", "example.com", "ICMP", "IPv6优先", "自动DNS",
    "2001:db8::8", 200, 200, 200, 0, 20, 24, 18, 200000, 1.0,
)

@Preview(name = "Material · Home", widthDp = 393, heightDp = 852)
@Composable fun MaterialHomePreview() = MaterialReferencePreviewScreen("home")

@Preview(name = "Material · Devices", widthDp = 393, heightDp = 852)
@Composable fun MaterialDevicesPreview() = MaterialReferencePreviewScreen("devices")

@Preview(name = "Material · Ping", widthDp = 393, heightDp = 852)
@Composable fun MaterialPingPreview() = MaterialReferencePreviewScreen("ping")

@Preview(name = "Material · Device detail", widthDp = 393, heightDp = 852)
@Composable fun MaterialDetailPreview() = MaterialReferencePreviewScreen("detail")
