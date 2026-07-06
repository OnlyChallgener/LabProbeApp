package com.labprobe.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.ui.graphics.Color
import java.util.Locale

fun inferDeviceProfile(d: DeviceItem): DeviceVisualProfile {
    val text = listOf(d.name, d.hostName, d.devType, d.osType, d.manufacture, d.mac)
        .joinToString(" ")
        .lowercase(Locale.getDefault())

    fun has(vararg words: String): Boolean = words.any { word -> text.contains(word.lowercase(Locale.getDefault())) }

    val manual = d.wolMode.lowercase(Locale.getDefault())
    val base = when {
        has("iphone", "ipad", "ios", "apple tablet", "平板") && has("ipad", "tablet", "平板") ->
            DeviceVisualProfile("tablet", "平板", Icons.Rounded.Devices, Color(0xFF64748B), false, 92, "平板通常不支持 WOL")

        has("iphone", "huawei", "mate", "pura", "honor", "xiaomi", "redmi", "iqoo", "vivo", "oppo", "realme", "oneplus", "galaxy", "samsung", "pixel", "手机", "android") ->
            DeviceVisualProfile("phone", "手机", Icons.Rounded.PhoneAndroid, Color(0xFF22C55E), false, 90, "手机默认不显示 WOL")

        has("watch", "手表", "wear", "band") ->
            DeviceVisualProfile("watch", "手表", Icons.Rounded.Watch, Color(0xFF8B5CF6), false, 86, "穿戴设备不显示 WOL")

        has("nas", "synology", "qnap", "truenas", "unraid", "群晖", "威联通", "storage") ->
            DeviceVisualProfile("nas", "NAS", Icons.Rounded.Storage, Color(0xFF0EA5E9), true, 93, "NAS 候选设备")

        has("desktop", "laptop", "notebook", "windows", "pc", "电脑", "主机", "macbook", "imac", "intel", "realtek pcie") ->
            DeviceVisualProfile("pc", "电脑", Icons.Rounded.Computer, Color(0xFF2563EB), true, 88, "电脑候选设备")

        has("router", "openwrt", "istoreos", "ruijie", "tplink", "tp-link", "mesh", "ap", "路由") ->
            DeviceVisualProfile("router", "路由/AP", Icons.Rounded.Router, Color(0xFF06B6D4), false, 86, "网络设备一般不在终端卡 WOL")

        has("printer", "打印", "hp ", "canon", "epson", "brother", "打印机") ->
            DeviceVisualProfile("printer", "打印机", Icons.Rounded.Print, Color(0xFFF59E0B), false, 80, "打印机按需手动标记更稳")

        has("tv", "电视", "tcl", "projector", "投影", "box", "盒子") ->
            DeviceVisualProfile("tv", "影音设备", Icons.Rounded.Tv, Color(0xFF7C3AED), false, 78, "影音设备默认隐藏 WOL")

        has("camera", "cam", "摄像", "ipc") ->
            DeviceVisualProfile("camera", "摄像头", Icons.Rounded.Videocam, Color(0xFFEF4444), false, 77, "摄像头默认隐藏 WOL")

        has("speaker", "音箱", "homepod", "小爱", "alexa", "sound") ->
            DeviceVisualProfile("speaker", "音箱", Icons.Rounded.Speaker, Color(0xFF14B8A6), false, 76, "音箱默认隐藏 WOL")

        has("sensor", "plug", "socket", "switch", "bulb", "light", "灯", "插座", "门锁", "空调", "风扇", "风尊", "热水器", "thermostat", "iot", "智能") ->
            DeviceVisualProfile("iot", "智能设备", Icons.Rounded.Sensors, Color(0xFF10B981), false, 74, "IoT 默认不显示 WOL")

        else -> DeviceVisualProfile("unknown", "未知设备", Icons.Rounded.Devices, Color(0xFF64748B), false, 52, "未知设备先不显示 WOL")
    }

    return when (manual) {
        "on", "true", "yes", "支持", "enable" -> base.copy(wolCandidate = true, confidence = maxOf(base.confidence, 95), note = "已手动标记支持 WOL")
        "off", "false", "no", "不支持", "disable" -> base.copy(wolCandidate = false, confidence = maxOf(base.confidence, 95), note = "已手动标记不支持 WOL")
        else -> base
    }
}
