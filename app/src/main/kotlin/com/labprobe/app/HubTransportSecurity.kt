package com.labprobe.app

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * HTTPS is accepted everywhere. Plain HTTP is restricted to literal/private LAN
 * targets and local host names because LabProbe must still support direct Hub
 * access inside a home network.
 */
fun validateHubTransportAddress(raw: String): String {
    val normalized = normalizeHubAddressForDisplay(raw)
    if (normalized.isBlank()) return normalized
    val uri = runCatching { URI(normalized) }.getOrElse { throw IllegalArgumentException("Hub 地址格式无效") }
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.trim('[', ']')?.lowercase().orEmpty()
    if (scheme == "https") return normalized
    if (scheme != "http") throw IllegalArgumentException("Hub 仅支持 HTTPS，或局域网内的 HTTP 地址")
    if (host.isBlank()) throw IllegalArgumentException("Hub 地址缺少主机名")
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")) return normalized
    val address = runCatching { InetAddress.getByName(host) }.getOrNull()
        ?: throw IllegalArgumentException("公网 HTTP Hub 已被阻止，请改用 HTTPS")
    val privateAddress = when (address) {
        is Inet6Address -> address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            ((address.address.first().toInt() and 0xFE) == 0xFC)
        else -> address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || isPrivateIpv4(host)
    }
    if (!privateAddress) throw IllegalArgumentException("公网 HTTP Hub 已被阻止，请改用 HTTPS")
    return normalized
}
