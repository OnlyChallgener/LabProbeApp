package com.labprobe.app

import java.util.Locale

/** 合并前端写在地址尾巴上的通道段，例如 `http://nas:58453/r/be50` 的后半截。 */
private val HUB_CHANNEL_SUFFIX = Regex("/r/[A-Za-z0-9_-]{1,64}$")

/** 去掉地址尾巴上的 `/r/<id>`，得到前端根地址。根地址才是身份比较用的那一个值。 */
fun hubChannelRoot(raw: String): String =
    HUB_CHANNEL_SUFFIX.replace(normalizeHubBaseUrl(raw), "").trimEnd('/')

/**
 * 把通道段拼回根地址。`routerId` 一律转小写：网关的 routerId 校验是
 * `^[a-z][a-z0-9_-]{1,31}$`，而 nginx 的 location 区分大小写，`/r/BE50` 只会 404。
 * 标识为空或非法时原样返回根地址（让网关自己给出「说不出要哪一台」的答复）。
 */
fun withHubChannel(raw: String, routerId: String): String {
    val route = routerId.trim().lowercase(Locale.ROOT)
    if (route.isBlank() || route == "default" || !Regex("[A-Za-z0-9_-]{1,64}").matches(route))
        return hubChannelRoot(raw)
    val root = hubChannelRoot(raw)
    return if (root.isBlank()) "" else "$root/r/$route"
}

/**
 * Hub 地址在设置页中保持简洁：
 * - http://192.168.1.2:58443 -> 192.168.1.2:58443
 * - https://example.com      -> https://example.com
 *
 * 发起 HTTP 请求时，未写协议的地址按 http:// 处理。
 */
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
