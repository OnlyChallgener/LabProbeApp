package com.labprobe.app

/**
 * Converts common transport / HTTP / socket errors to user-facing Chinese.
 * Keep raw protocol details in logs where they are useful; UI banners and
 * status text should not leak terse English transport messages.
 */
fun uiMessageZh(raw: String?): String {
    val text = raw.orEmpty().trim()
    if (text.isBlank()) return ""
    if (text.any { it in '\u4e00'..'\u9fff' }) return text

    val lower = text.lowercase()
    val httpCode = Regex("""\b([45]\d\d)\b""").find(lower)?.groupValues?.getOrNull(1)
    return when {
        "connection closed" in lower || "socket closed" in lower || "closed channel" in lower ->
            "连接已关闭，正在尝试恢复"
        "connection reset" in lower || "reset by peer" in lower ->
            "连接已被对端重置"
        "connection refused" in lower ->
            "连接被拒绝，请检查服务是否可用"
        "broken pipe" in lower ->
            "连接已中断"
        "timeout" in lower || "timed out" in lower ->
            "请求超时，请稍后重试"
        "network is unreachable" in lower || "no route to host" in lower || "no route" in lower ->
            "当前网络无法到达目标服务"
        "host unreachable" in lower ->
            "目标主机不可达"
        "unknown host" in lower || "unable to resolve" in lower || "name or service not known" in lower ->
            "域名解析失败"
        "ssl" in lower && "handshake" in lower || "handshake" in lower ->
            "安全连接握手失败"
        "certificate" in lower || "certpath" in lower ->
            "证书校验失败"
        "unauthorized" in lower || httpCode == "401" ->
            "认证失败，请检查凭据"
        "forbidden" in lower || httpCode == "403" ->
            "没有访问权限"
        "not found" in lower || httpCode == "404" ->
            "请求的资源不存在"
        "bad gateway" in lower || httpCode == "502" ->
            "上游服务暂不可用"
        "service unavailable" in lower || httpCode == "503" ->
            "服务暂不可用，请稍后重试"
        "internal server error" in lower || httpCode == "500" ->
            "服务器内部错误"
        "cancelled" in lower || "canceled" in lower ->
            "操作已取消"
        else -> if (httpCode != null) "请求失败（HTTP $httpCode）" else "连接异常，请稍后重试"
    }
}
