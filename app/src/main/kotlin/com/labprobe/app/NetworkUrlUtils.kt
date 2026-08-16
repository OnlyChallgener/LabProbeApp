package com.labprobe.app

/**
 * Hub 地址在设置页中保持简洁：
 * - http://192.168.1.2:58443 -> 192.168.1.2:58443
 * - https://example.com      -> https://example.com
 *
 * 发起 HTTP 请求时，未写协议的地址按 http:// 处理。
 */
fun normalizeHubAddressForDisplay(raw: String): String {
    val value = raw.trim().trimEnd('/')
    return if (value.startsWith("http://", ignoreCase = true)) {
        value.substring(7).trimEnd('/')
    } else {
        value
    }
}

fun normalizeHubBaseUrl(raw: String): String {
    val value = raw.trim().trimEnd('/')
    if (value.isBlank()) return ""
    return when {
        value.startsWith("http://", ignoreCase = true) -> value
        value.startsWith("https://", ignoreCase = true) -> value
        else -> "http://$value"
    }
}

fun joinUrl(base: String, path: String): String {
    val cleanPath = path.trim()
    if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) return cleanPath

    val cleanBase = normalizeHubBaseUrl(base)
    if (cleanBase.isBlank()) return cleanPath
    if (cleanPath.isBlank()) return cleanBase

    return if (cleanPath.startsWith("/")) cleanBase + cleanPath else "$cleanBase/$cleanPath"
}
