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
        // Android 的 ECONNABORTED：本机路由表变了（隧道起停、Wi‑Fi/蜂窝切换）时最常见，
        // 直译成英文对用户没有意义。
        "software caused connection abort" in lower || "connection aborted" in lower ->
            "连接被本机中断（网络或隧道刚切换），请重试"
        // OkHttp 的连接超时原文是 "failed to connect to … after 6000ms"，不含 timeout 字样。
        "failed to connect" in lower || "connect failure" in lower ->
            "连不上目标服务，请检查地址是否可达"
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
