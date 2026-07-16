package com.labprobe.app

import java.util.Locale

data class WolRecommendation(
    val recommended: Boolean,
    val confidence: String,
    val reason: String
)

private val STRONG_WOL_TYPES = setOf("nas", "desktop", "mini_pc", "laptop", "industrial", "server", "workstation", "soft_router")
private val OPTIONAL_WOL_TYPES = setOf("printer", "tv", "tv_box", "set_top_box")

fun wolRecommendationForDeviceType(type: String?): WolRecommendation {
    val raw = type.orEmpty().trim().lowercase(Locale.ROOT)
    val softRouter = listOf("soft router", "soft_router", "软路由", "openwrt", "istoreos", "pfsense", "opnsense").any(raw::contains)
    val serverLike = listOf("server", "服务器", "workstation", "工控机", "industrial").any(raw::contains)
    val computerLike = listOf("windows pc", "windows computer", "电脑", "台式", "desktop", "mini pc", "minipc", "laptop", "notebook", "笔记本").any(raw::contains) || raw == "pc"
    val normalized = when {
        softRouter -> "soft_router"
        serverLike -> "server"
        computerLike -> "desktop"
        else -> normalizeDeviceTypeToken(raw).ifBlank { raw }
    }
    return when (normalized) {
        in STRONG_WOL_TYPES -> WolRecommendation(
            recommended = true,
            confidence = "strong",
            reason = "此类设备通常支持 WOL，建议加入远程唤醒管理。"
        )
        in OPTIONAL_WOL_TYPES -> WolRecommendation(
            recommended = false,
            confidence = "optional",
            reason = "此类设备可能支持网络唤醒，请确认设备设置后再开启。"
        )
        "unknown", "" -> WolRecommendation(
            recommended = false,
            confidence = "not_recommended",
            reason = "未知设备默认不加入 WOL，可确认支持后手动开启。"
        )
        else -> WolRecommendation(
            recommended = false,
            confidence = "not_recommended",
            reason = "此类设备通常不支持传统 WOL，默认不加入管理。"
        )
    }
}

fun wolRecommendationForDevice(device: DeviceItem, type: String? = null): WolRecommendation {
    val identity = listOf(type.orEmpty(), device.manualType, device.remark, device.name, device.hostName, device.osType, device.devType)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    val special = wolRecommendationForDeviceType(identity)
    if (special.confidence == "strong") return special
    val resolvedType = type.orEmpty().ifBlank { inferDeviceTypeRule(device).id }
    return wolRecommendationForDeviceType(resolvedType)
}
