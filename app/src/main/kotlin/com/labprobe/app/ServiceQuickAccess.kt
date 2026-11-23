package com.labprobe.app

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import com.labprobe.app.labCardCombinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.URI
import java.util.Locale

internal data class ServiceQuickAccessEndpoint(val host: String, val port: Int? = null)

internal sealed interface ServiceQuickAccessTarget {
    data class Browser(val url: String) : ServiceQuickAccessTarget
    data class Rdp(val uri: String, val address: String = uri.removePrefix("rdp://")) : ServiceQuickAccessTarget
    data class Ssh(val host: String, val port: Int) : ServiceQuickAccessTarget
    data class ClientApp(val serviceType: String, val packages: List<String>) : ServiceQuickAccessTarget
    data class Copy(val address: String) : ServiceQuickAccessTarget
}

private fun normalizedServiceType(value: String): String = value.trim().uppercase(Locale.ROOT)

internal fun serviceSupportsQuickAccess(serviceType: String): Boolean =
    normalizedServiceType(serviceType) in setOf("HTTP", "HTTPS", "RDP", "SSH", "WIREGUARD", "OPENVPN", "TELNET", "DNS", "TCP", "UDP", "CUSTOM")

private fun serviceDefaultPort(serviceType: String): Int? = when (normalizedServiceType(serviceType)) {
    "HTTP" -> 80
    "HTTPS" -> 443
    "SSH" -> 22
    "RDP" -> 3389
    "WIREGUARD" -> 51820
    "OPENVPN" -> 1194
    "TELNET" -> 23
    "DNS" -> 53
    else -> null
}

private fun parseQuickAccessAuthority(raw: String): ServiceQuickAccessEndpoint? {
    val authority = raw.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').trim()
    if (authority.isBlank()) return null
    if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        if (end <= 1) return null
        val host = authority.substring(1, end)
        val port = authority.substring(end + 1).removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 }
        return ServiceQuickAccessEndpoint(host, port)
    }
    val colonCount = authority.count { it == ':' }
    if (colonCount == 0) return ServiceQuickAccessEndpoint(authority)
    if (colonCount == 1) {
        val host = authority.substringBefore(':').trim()
        val port = authority.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 }
        return host.takeIf { it.isNotBlank() }?.let { ServiceQuickAccessEndpoint(it, port) }
    }
    normalizeIpv6(authority)?.let { return ServiceQuickAccessEndpoint(it) }
    val lastColon = authority.lastIndexOf(':')
    val hostCandidate = authority.substring(0, lastColon)
    val port = authority.substring(lastColon + 1).toIntOrNull()?.takeIf { it in 1..65535 }
    val host = port?.let { normalizeIpv6(hostCandidate) } ?: return null
    return ServiceQuickAccessEndpoint(host, port)
}

internal fun parseQuickAccessEndpoint(rawAddress: String): ServiceQuickAccessEndpoint? {
    val raw = rawAddress.trim()
    if (raw.isBlank()) return null
    val uri = runCatching { URI(if (raw.contains("://")) raw else "tcp://$raw") }.getOrNull()
    val uriHost = uri?.host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
    if (uriHost.isNotBlank()) {
        return ServiceQuickAccessEndpoint(uriHost, uri?.port?.takeIf { it in 1..65535 })
    }
    return parseQuickAccessAuthority(raw.substringAfter("://", raw))
}

internal fun formatServiceHostPort(host: String, port: Int? = null): String {
    val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
    if (cleanHost.isBlank()) return ""
    val renderedHost = if (cleanHost.contains(':')) "[$cleanHost]" else cleanHost
    return if (port != null && port in 1..65535) "$renderedHost:$port" else renderedHost
}

private fun webUrl(rawAddress: String, scheme: String): String {
    val raw = rawAddress.trim()
    if (raw.isBlank()) return ""
    val source = runCatching { URI(if (raw.contains("://")) raw else "$scheme://$raw") }.getOrNull()
    val endpoint = parseQuickAccessEndpoint(raw) ?: return ""
    val suffix = buildString {
        source?.rawPath?.takeIf { it.isNotBlank() }?.let(::append)
        source?.rawQuery?.takeIf { it.isNotBlank() }?.let { append('?').append(it) }
        source?.rawFragment?.takeIf { it.isNotBlank() }?.let { append('#').append(it) }
    }
    return "$scheme://${formatServiceHostPort(endpoint.host, endpoint.port)}$suffix"
}

internal fun serviceAddressForCopy(serviceType: String, rawAddress: String): String {
    val type = normalizedServiceType(serviceType)
    if (rawAddress.isBlank()) return ""
    if (type in setOf("HTTP", "HTTPS", "SSH", "RDP", "WIREGUARD", "TCP", "UDP", "OPENVPN", "TELNET", "DNS", "CUSTOM")) {
        val endpoint = parseQuickAccessEndpoint(rawAddress) ?: return rawAddress.trim()
        return formatServiceHostPort(endpoint.host, endpoint.port ?: serviceDefaultPort(type))
    }
    return rawAddress.trim()
}

internal fun serviceQuickAccessTarget(serviceType: String, rawAddress: String): ServiceQuickAccessTarget? {
    val type = normalizedServiceType(serviceType)
    if (rawAddress.isBlank()) return when (type) {
        "WIREGUARD" -> ServiceQuickAccessTarget.ClientApp("WIREGUARD", listOf("com.wireguard.android"))
        "OPENVPN" -> ServiceQuickAccessTarget.ClientApp("OPENVPN", listOf("net.openvpn.openvpn", "de.blinkt.openvpn"))
        else -> null
    }
    return when (type) {
        "HTTPS" -> webUrl(rawAddress, "https").takeIf { it.isNotBlank() }?.let { ServiceQuickAccessTarget.Browser(it) }
        "HTTP" -> webUrl(rawAddress, "http").takeIf { it.isNotBlank() }?.let { ServiceQuickAccessTarget.Browser(it) }
        "RDP" -> parseQuickAccessEndpoint(rawAddress)?.let { endpoint ->
            val address = formatServiceHostPort(endpoint.host, endpoint.port ?: 3389)
            ServiceQuickAccessTarget.Rdp("rdp://full%20address=s:$address", address)
        }
        "SSH" -> parseQuickAccessEndpoint(rawAddress)?.let { ServiceQuickAccessTarget.Ssh(it.host, it.port ?: 22) }
        "WIREGUARD" -> ServiceQuickAccessTarget.ClientApp("WIREGUARD", listOf("com.wireguard.android"))
        "OPENVPN" -> ServiceQuickAccessTarget.ClientApp("OPENVPN", listOf("net.openvpn.openvpn", "de.blinkt.openvpn"))
        else -> ServiceQuickAccessTarget.Copy(serviceAddressForCopy(serviceType, rawAddress))
    }
}

internal fun launchServiceQuickAccess(
    context: Context,
    target: ServiceQuickAccessTarget,
    onOpenSsh: (String, Int) -> Unit,
    onOpenWireGuard: () -> Unit,
) {
    when (target) {
        is ServiceQuickAccessTarget.Ssh -> {
            copyServiceAddress(context, formatServiceHostPort(target.host, target.port))
            onOpenSsh(target.host, target.port)
        }
        is ServiceQuickAccessTarget.ClientApp -> {
            val intents = target.packages.mapNotNull { context.packageManager.getLaunchIntentForPackage(it) }
            if (intents.size > 1) {
                val chooser = Intent.createChooser(intents.first(), "选择 ${target.serviceType} 客户端")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
                runCatching { context.startActivity(chooser) }
                    .onFailure { toast(context, "无法打开 ${target.serviceType} 客户端") }
            } else if (intents.isNotEmpty()) {
                runCatching { context.startActivity(intents.first().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { toast(context, "无法打开 ${target.serviceType} 客户端") }
            } else if (target.serviceType == "WIREGUARD") {
                onOpenWireGuard()
            } else {
                toast(context, "未安装 OpenVPN 客户端")
            }
        }
        is ServiceQuickAccessTarget.Copy -> {
            copyServiceAddress(context, target.address)
            toast(context, "已复制 ${target.address}")
        }
        is ServiceQuickAccessTarget.Browser -> runCatching {
            val scheme = target.url.substringBefore("://").uppercase(Locale.ROOT)
            copyServiceAddress(context, serviceAddressForCopy(scheme, target.url))
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            val scheme = target.url.substringBefore("://").uppercase(Locale.ROOT)
            val address = serviceAddressForCopy(scheme, target.url)
            copyServiceAddress(context, address)
            toast(context, "已复制 $address")
        }
        is ServiceQuickAccessTarget.Rdp -> runCatching {
            copyServiceAddress(context, target.address)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            copyServiceAddress(context, target.address)
            toast(context, "已复制 ${target.address}")
        }
    }
}

private fun copyServiceAddress(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("WAN 地址", value))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServiceQuickAccessIconButton(
    serviceType: String,
    endpoint: String,
    tint: Color = LabV2.Primary,
    onOpenSsh: (String, Int) -> Unit,
    onOpenWireGuard: () -> Unit,
) {
    if (!serviceSupportsQuickAccess(serviceType)) return
    val context = LocalContext.current
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .labCardCombinedClickable(
                halo = false,
                onClick = {
                    val target = serviceQuickAccessTarget(serviceType, endpoint)
                    if (target == null) toast(context, "暂未取得可用的快捷访问地址")
                    else launchServiceQuickAccess(context, target, onOpenSsh, onOpenWireGuard)
                },
                onLongClick = { toast(context, "快捷访问") },
            ),
        contentAlignment = Alignment.Center,
    ) {
        LabNavigationIndicator(external = true, description = "快捷访问")
    }
}
