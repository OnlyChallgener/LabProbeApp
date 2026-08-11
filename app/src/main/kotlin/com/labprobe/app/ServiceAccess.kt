package com.labprobe.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.math.max

internal data class ServiceAccessReport(
    val reachable: Boolean,
    val path: String = "",
    val dns: String = "—",
    val ipv6: String = "—",
    val tcp: String = "—",
    val udp: String = "—",
    val https: String = "—",
    val latencyMs: Long? = null,
    val reason: String = "",
)

internal data class ServiceAccessDecision(
    val endpoint: String?,
    val report: ServiceAccessReport,
)

internal data class ServiceEndpoint(
    val raw: String,
    val scheme: String,
    val host: String,
    val port: Int,
)

/** Internal probe constraint. Favorites deliberately present only 内网 / 外网. */
internal enum class ServiceAddressFamily { Any, Ipv4, Ipv6 }

private const val SERVICE_ACCESS_TIMEOUT_MS = 550L

private val serviceHttpClient = OkHttpClient.Builder()
    .connectTimeout(SERVICE_ACCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(SERVICE_ACCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .writeTimeout(SERVICE_ACCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .callTimeout(SERVICE_ACCESS_TIMEOUT_MS + 150L, TimeUnit.MILLISECONDS)
    .build()

private val ipv6OnlyDns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = InetAddress.getAllByName(hostname).filterIsInstance<Inet6Address>()
        if (addresses.isEmpty()) throw UnknownHostException("no AAAA record")
        return addresses
    }
}

private val ipv6HttpClient = serviceHttpClient.newBuilder().dns(ipv6OnlyDns).build()
private val ipv4OnlyDns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = InetAddress.getAllByName(hostname).filterIsInstance<Inet4Address>()
        if (addresses.isEmpty()) throw UnknownHostException("no A record")
        return addresses
    }
}
private val ipv4HttpClient = serviceHttpClient.newBuilder().dns(ipv4OnlyDns).build()

internal fun parseServiceEndpoint(raw: String): ServiceEndpoint? {
    val value = raw.trim()
    if (value.isBlank()) return null
    val normalized = if (value.contains("://")) value else "tcp://$value"
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: return null
    val scheme = uri.scheme?.lowercase().orEmpty()
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
    val port = if (uri.port >= 0) uri.port else defaultPort
    return ServiceEndpoint(raw = normalized, scheme = scheme, host = host, port = port).takeIf { it.port in 1..65535 }
}

private fun isWildcardEndpoint(host: String): Boolean = host == "::" || host == "0.0.0.0" || host == "0"

internal fun isWildcardServiceEndpoint(raw: String): Boolean = parseServiceEndpoint(raw)?.let { isWildcardEndpoint(it.host) } == true

private suspend fun resolveEndpoint(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
    if (isWildcardEndpoint(host)) emptyList() else runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
}

private fun isIpv6Unavailable(error: Throwable?): Boolean = generateSequence(error) { it.cause }.any { cause ->
    cause is NoRouteToHostException ||
        (cause is SocketException && (
            cause.message.orEmpty().contains("network is unreachable", ignoreCase = true) ||
                cause.message.orEmpty().contains("no route", ignoreCase = true)
            ))
}

internal fun isCertificateFailure(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.any { cause ->
        cause is CertificateException ||
            cause is SSLPeerUnverifiedException ||
            (cause is SSLHandshakeException && cause.message.orEmpty().containsAny(
                "certificate", "certpath", "trust anchor", "self signed", "hostname", "peer unverified"
            ))
    }

private fun String.containsAny(vararg values: String): Boolean = values.any { contains(it, ignoreCase = true) }

internal fun isTlsHandshakeFailure(error: Throwable?): Boolean =
    !isCertificateFailure(error) && generateSequence(error) { it.cause }.any { it is SSLException }

private fun failureReason(error: Throwable?, family: ServiceAddressFamily, https: Boolean): String = when {
    https && isCertificateFailure(error) -> "HTTPS 证书校验失败"
    https && isTlsHandshakeFailure(error) -> "TCP 可达 · HTTPS 握手失败"
    family == ServiceAddressFamily.Ipv6 && isIpv6Unavailable(error) -> "当前网络无法使用 IPv6 远程访问"
    https -> "HTTPS 服务不可达"
    else -> "服务不可达"
}

internal suspend fun probeServiceEndpoint(
    raw: String,
    family: ServiceAddressFamily = ServiceAddressFamily.Any,
    serviceType: String = "",
    transportProtocol: String = "TCP",
): ServiceAccessReport {
    val endpoint = parseServiceEndpoint(raw)
        ?: return ServiceAccessReport(false, dns = "失败", ipv6 = if (family == ServiceAddressFamily.Ipv6) "不可用" else "—", tcp = "不可达", reason = "地址格式无效")
    if (isWildcardEndpoint(endpoint.host)) {
        return ServiceAccessReport(false, dns = "未配置", ipv6 = if (family == ServiceAddressFamily.Ipv6) "不可用" else "—", tcp = "未测试", reason = "未配置可测试的远程入口")
    }
    val started = System.nanoTime()
    val addresses = withTimeoutOrNull(SERVICE_ACCESS_TIMEOUT_MS) { resolveEndpoint(endpoint.host) }.orEmpty()
    val candidates = when (family) {
        ServiceAddressFamily.Any -> addresses
        ServiceAddressFamily.Ipv4 -> addresses.filterIsInstance<Inet4Address>()
        ServiceAddressFamily.Ipv6 -> addresses.filterIsInstance<Inet6Address>()
    }
    val effectiveFamily = when {
        family != ServiceAddressFamily.Any -> family
        endpoint.host.contains(':') -> ServiceAddressFamily.Ipv6
        candidates.isNotEmpty() && candidates.all { it is Inet6Address } -> ServiceAddressFamily.Ipv6
        candidates.isNotEmpty() && candidates.all { it is Inet4Address } -> ServiceAddressFamily.Ipv4
        else -> ServiceAddressFamily.Any
    }
    if (candidates.isEmpty()) {
        return ServiceAccessReport(
            reachable = false,
            dns = when (effectiveFamily) {
                ServiceAddressFamily.Ipv6 -> "无 AAAA"
                ServiceAddressFamily.Ipv4 -> "无 A"
                ServiceAddressFamily.Any -> "失败"
            },
            ipv6 = if (effectiveFamily == ServiceAddressFamily.Ipv6) "不可用" else "—",
            tcp = "不可达",
            https = if (endpoint.scheme == "https") "未测试" else "—",
            reason = if (effectiveFamily == ServiceAddressFamily.Ipv6) "当前网络无法使用 IPv6 远程访问" else "服务不可达",
        )
    }

    if (transportProtocol.equals("UDP", ignoreCase = true)) {
        return probeUdpServiceEndpoint(endpoint, candidates, effectiveFamily, serviceType, started)
    }

    val result = withContext(Dispatchers.IO) {
        if (endpoint.scheme == "http" || endpoint.scheme == "https") {
            runCatching {
                val client = when (effectiveFamily) {
                    ServiceAddressFamily.Ipv4 -> ipv4HttpClient
                    ServiceAddressFamily.Ipv6 -> ipv6HttpClient
                    ServiceAddressFamily.Any -> serviceHttpClient
                }
                client.newCall(Request.Builder().url(endpoint.raw).head().build()).execute().use { Unit }
            }
        } else {
            var lastError: Throwable? = null
            val connected = candidates.any { address ->
                runCatching { Socket().use { it.connect(InetSocketAddress(address, endpoint.port), SERVICE_ACCESS_TIMEOUT_MS.toInt()) } }
                    .onFailure { lastError = it }
                    .isSuccess
            }
            if (connected) Result.success(Unit) else Result.failure<Unit>(lastError ?: SocketException("connect failed"))
        }
    }
    val elapsed = max(1L, (System.nanoTime() - started) / 1_000_000L)
    val certificateWarning = endpoint.scheme == "https" && isCertificateFailure(result.exceptionOrNull())
    val tlsHandshakeFailure = endpoint.scheme == "https" && isTlsHandshakeFailure(result.exceptionOrNull())
    val reachable = result.isSuccess || certificateWarning
    val reason = when {
        certificateWarning -> "服务可访问 · 证书校验异常"
        else -> result.exceptionOrNull()?.let { failureReason(it, effectiveFamily, endpoint.scheme == "https") }.orEmpty()
    }
    return ServiceAccessReport(
        reachable = reachable,
        dns = "正常",
        ipv6 = if (effectiveFamily == ServiceAddressFamily.Ipv6) "可用" else "—",
        tcp = if (reachable || tlsHandshakeFailure) "可达" else "不可达",
        https = when {
            endpoint.scheme != "https" -> "—"
            certificateWarning -> "证书警告"
            result.isSuccess -> "正常"
            tlsHandshakeFailure -> "握手失败"
            else -> "失败"
        },
        latencyMs = elapsed,
        reason = reason,
    )
}

private fun dnsQuery(): ByteArray = byteArrayOf(
    0x4c.toByte(), 0x50.toByte(), 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00,
    0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
    0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00,
    0x00, 0x01, 0x00, 0x01,
)

private fun validDnsResponse(bytes: ByteArray, length: Int): Boolean =
    length >= 12 && bytes[0] == 0x4c.toByte() && bytes[1] == 0x50.toByte() && (bytes[2].toInt() and 0x80) != 0

private suspend fun probeUdpServiceEndpoint(
    endpoint: ServiceEndpoint,
    candidates: List<InetAddress>,
    family: ServiceAddressFamily,
    serviceType: String,
    started: Long,
): ServiceAccessReport {
    val ipv6 = if (family == ServiceAddressFamily.Ipv6) "可用" else "—"
    val normalizedType = serviceType.trim().uppercase()
    if (normalizedType != "DNS") {
        val detail = when (normalizedType) {
            "WIREGUARD" -> "未验证"
            "OPENVPN" -> "未验证"
            else -> "未验证"
        }
        return ServiceAccessReport(
            reachable = false,
            dns = "正常",
            ipv6 = ipv6,
            udp = detail,
            reason = "UDP 服务不能通过通用探测确认，请以客户端实际连接结果为准",
        )
    }
    val response = withContext(Dispatchers.IO) {
        candidates.any { address ->
            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = SERVICE_ACCESS_TIMEOUT_MS.toInt()
                    socket.connect(InetSocketAddress(address, endpoint.port))
                    val request = dnsQuery()
                    socket.send(DatagramPacket(request, request.size))
                    val buffer = ByteArray(1500)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    check(validDnsResponse(buffer, packet.length)) { "invalid DNS response" }
                }
            }.isSuccess
        }
    }
    val elapsed = max(1L, (System.nanoTime() - started) / 1_000_000L)
    return if (response) {
        ServiceAccessReport(true, dns = "正常", ipv6 = ipv6, udp = "可用", latencyMs = elapsed, reason = "DNS 服务可用")
    } else {
        ServiceAccessReport(false, dns = "正常", ipv6 = ipv6, udp = "无响应", latencyMs = elapsed, reason = "DNS 服务无响应")
    }
}

internal suspend fun chooseServiceAccess(
    localEndpoint: String,
    remoteEndpoint: String,
    mode: String = "auto",
    serviceType: String = "",
    transportProtocol: String = "TCP",
): ServiceAccessDecision {
    if (mode == "lan") {
        val local = localEndpoint.trim().takeIf { it.isNotBlank() }?.let { probeServiceEndpoint(it, serviceType = serviceType, transportProtocol = transportProtocol) }
        return if (local?.reachable == true) {
            ServiceAccessDecision(localEndpoint.trim(), local.copy(path = "内网直连"))
        } else {
            ServiceAccessDecision(null, (local ?: ServiceAccessReport(false, reason = "未配置内网地址")).copy(reason = local?.reason.orEmpty().ifBlank { "服务不可达" }))
        }
    }
    if (mode == "wan") {
        val remote = remoteEndpoint.trim().takeIf { it.isNotBlank() }?.let { probeServiceEndpoint(it, ServiceAddressFamily.Any, serviceType, transportProtocol) }
        return if (remote?.reachable == true) {
            ServiceAccessDecision(remoteEndpoint.trim(), remote.copy(path = "外网访问"))
        } else {
            ServiceAccessDecision(null, (remote ?: ServiceAccessReport(false, reason = "未配置外网地址")).copy(reason = remote?.reason.orEmpty().ifBlank { "服务不可达" }))
        }
    }

    val local = localEndpoint.trim().takeIf { it.isNotBlank() }?.let { probeServiceEndpoint(it, serviceType = serviceType, transportProtocol = transportProtocol) }
    if (local?.reachable == true) return ServiceAccessDecision(localEndpoint.trim(), local.copy(path = "内网直连"))
    val remote = remoteEndpoint.trim().takeIf { it.isNotBlank() }?.let { probeServiceEndpoint(it, serviceType = serviceType, transportProtocol = transportProtocol) }
    if (remote?.reachable == true) return ServiceAccessDecision(remoteEndpoint.trim(), remote.copy(path = "外网访问"))
    val failure = remote ?: local ?: ServiceAccessReport(false, reason = "当前不可达")
    return ServiceAccessDecision(null, failure.copy(reason = failure.reason.ifBlank { "服务不可达" }))
}

internal suspend fun testServiceRemoteEndpoint(
    remoteEndpoint: String,
    family: ServiceAddressFamily = ServiceAddressFamily.Any,
    serviceType: String = "",
    transportProtocol: String = "TCP",
): ServiceAccessReport {
    val value = remoteEndpoint.trim()
    if (value.isBlank()) return ServiceAccessReport(false, dns = "未配置", ipv6 = "不可用", tcp = "未测试", https = "未测试", reason = "未配置可测试的远程入口")
    return probeServiceEndpoint(value, family, serviceType, transportProtocol)
}

internal fun serviceAccessStatus(report: ServiceAccessReport): String = when {
    report.reachable -> report.path
    report.udp == "未验证" -> "服务未验证"
    report.reason == "当前网络无法使用 IPv6 远程访问" -> "当前不可达 · 当前网络无 IPv6"
    report.reason == "未配置可测试的远程入口" -> "当前不可达 · 未配置远程入口"
    else -> "服务不可达 · 目标端口无响应"
}
