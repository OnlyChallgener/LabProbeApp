package com.labprobe.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Locale

internal data class FavoriteIconPreset(val id: String, val label: String, val resource: Int, val offset: IntOffset, val size: IntSize)

internal val favoriteIconPresets = listOf(
    FavoriteIconPreset("router", "路由器", R.drawable.favorite_router, IntOffset(0, 0), IntSize(128, 128)),
    FavoriteIconPreset("camera", "摄像头", R.drawable.favorite_camera, IntOffset(0, 0), IntSize(128, 128)),
    FavoriteIconPreset("ssh", "SSH", R.drawable.favorite_ssh, IntOffset(0, 0), IntSize(128, 128)),
    FavoriteIconPreset("repository", "更新仓", R.drawable.favorite_repository, IntOffset(7, 15), IntSize(82, 66)),
    FavoriteIconPreset("alist", "Alist", R.drawable.favorite_alist, IntOffset(6, 14), IntSize(116, 102)),
    FavoriteIconPreset("docker", "Docker", R.drawable.favorite_docker, IntOffset(0, 0), IntSize(128, 128)),
    FavoriteIconPreset("dsm", "群晖 DSM", R.drawable.favorite_dsm, IntOffset(0, 0), IntSize(128, 128)),
    FavoriteIconPreset("lucky", "Lucky", R.drawable.favorite_lucky, IntOffset(0, 0), IntSize(1024, 1024)),
    FavoriteIconPreset("istoreos", "iStoreOS", R.drawable.favorite_istoreos, IntOffset(1, 1), IntSize(190, 190)),
    FavoriteIconPreset("netgear", "NETGEAR", R.drawable.favorite_netgear, IntOffset(63, 399), IntSize(923, 128)),
    FavoriteIconPreset("openwrt", "OpenWrt", R.drawable.favorite_openwrt, IntOffset(46, 46), IntSize(1494, 1188)),
    FavoriteIconPreset("ruijie", "锐捷", R.drawable.favorite_ruijie, IntOffset(25, 165), IntSize(432, 126)),
    FavoriteIconPreset("fnos", "飞牛 fnOS", R.drawable.favorite_fnos, IntOffset(0, 0), IntSize(300, 300)),
    FavoriteIconPreset("nodebaby", "节点小宝", R.drawable.favorite_nodebaby, IntOffset(0, 0), IntSize(180, 180)),
    FavoriteIconPreset("tplink", "TP-Link", R.drawable.favorite_tplink, IntOffset(0, 0), IntSize(2134, 2134)),
    FavoriteIconPreset("windows", "Windows / 远程桌面", R.drawable.favorite_windows, IntOffset(0, 0), IntSize(128, 128)),
    FavoriteIconPreset("homeassistant", "Home Assistant", R.drawable.favorite_homeassistant, IntOffset(0, 0), IntSize(128, 128)),
    FavoriteIconPreset("nas", "NAS", R.drawable.favorite_nas, IntOffset(0, 0), IntSize(128, 128)),
    FavoriteIconPreset("wireguard", "WireGuard", R.drawable.favorite_wireguard, IntOffset(25, 25), IntSize(450, 450)),
    FavoriteIconPreset("openvpn", "OpenVPN", R.drawable.favorite_openvpn, IntOffset(0, 0), IntSize(300, 300)),
    FavoriteIconPreset("ugreen", "绿联 UGREEN", R.drawable.favorite_ugreen, IntOffset(0, 0), IntSize(500, 500)),
)

/** Specific brand/service first; generic protocol and NAS fallback last. */
internal fun suggestedFavoriteIcon(name: String, serviceType: String): String {
    recognizeMappingApplication(name)?.let { return it.iconId }
    val text = name.lowercase(Locale.ROOT)
    val compact = text.replace(Regex("[\\s_\\-]+"), "")
    fun word(value: String) = Regex("(?<![a-z0-9])" + Regex.escape(value) + "(?![a-z0-9])").containsMatchIn(text)
    return when {
        "wireguard" in compact || word("wg") -> "wireguard"
        "openvpn" in compact -> "openvpn"
        "homeassistant" in compact || "家庭助理" in text -> "homeassistant"
        "istoreos" in compact || "istore" == compact -> "istoreos"
        "节点小宝" in text || "nodebaby" in compact -> "nodebaby"
        "openwrt" in compact -> "openwrt"
        "netgear" in compact || "网件" in text -> "netgear"
        "tplink" in compact || "普联" in text -> "tplink"
        "ruijie" in compact || "锐捷" in text -> "ruijie"
        "lucky" in compact -> "lucky"
        "alist" in compact -> "alist"
        "docker" in compact -> "docker"
        "更新仓" in text || "更新仓库" in text || word("repository") -> "repository"
        "路由器" in text || word("router") -> "router"
        else -> favoriteIconForServiceType(serviceType)
    }
}

internal data class MappingApplicationMatch(
    val serviceType: String,
    val iconId: String,
)

internal data class MappingServiceRecognition(
    val serviceType: String,
    val iconId: String,
    val mechanism: String = "",
)

private fun wordsIn(text: String, vararg values: String): Boolean =
    values.any { Regex("(?<![a-z0-9])" + Regex.escape(it) + "(?![a-z0-9])").containsMatchIn(text) }

/** Strong application words only. Short aliases are token-matched to avoid false positives. */
internal fun recognizeMappingApplication(raw: String): MappingApplicationMatch? {
    val text = raw.trim().lowercase(Locale.ROOT)
    if (text.isBlank()) return null
    val compact = text.replace(Regex("[\\s_\\-]+"), "")
    return when {
        "qbittorrent" in compact -> MappingApplicationMatch("qBittorrent", "download")
        "transmission" in compact -> MappingApplicationMatch("Transmission", "download")
        "bittorrent" in compact -> MappingApplicationMatch("BitTorrent", "download")
        wordsIn(text, "rdp") || "remotedesktop" in compact || "远程桌面" in text ->
            MappingApplicationMatch("RDP", "windows")
        wordsIn(text, "ssh") || "openssh" in compact -> MappingApplicationMatch("SSH", "ssh")
        wordsIn(text, "plex") -> MappingApplicationMatch("Plex", "media")
        wordsIn(text, "emby") -> MappingApplicationMatch("Emby", "media")
        wordsIn(text, "jellyfin") -> MappingApplicationMatch("Jellyfin", "media")
        "minidlna" in compact || "readymedia" in compact || wordsIn(text, "dlna") ->
            MappingApplicationMatch("DLNA", "media")
        wordsIn(text, "smb") || "samba" in compact || wordsIn(text, "cifs") ->
            MappingApplicationMatch("SMB", "nas")
        wordsIn(text, "nfs") -> MappingApplicationMatch("NFS", "nas")
        "onvif" in compact || wordsIn(text, "nvr") || wordsIn(text, "ipc") ||
            wordsIn(text, "camera") || "监控" in text || "摄像" in text -> MappingApplicationMatch("视频监控", "camera")
        "sftp" in compact -> MappingApplicationMatch("SFTP", "download")
        wordsIn(text, "ftp") -> MappingApplicationMatch("FTP", "download")
        "ugreen" in compact || "绿联" in text -> MappingApplicationMatch("绿联NAS", "ugreen")
        "fnos" in compact || "飞牛" in text -> MappingApplicationMatch("飞牛NAS", "fnos")
        "synology" in compact || "群晖" in text || wordsIn(text, "dsm") ->
            MappingApplicationMatch("群晖NAS", "dsm")
        wordsIn(text, "nas") || "存储" in text -> MappingApplicationMatch("NAS", "nas")
        "steam" in compact || "playstation" in compact || "xbox" in compact ||
            "nintendoswitch" in compact || wordsIn(text, "ps5") || "游戏" in text ->
            MappingApplicationMatch("游戏联机", "game")
        else -> null
    }
}

internal fun favoriteIconForServiceType(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
    "WIREGUARD", "WG" -> "wireguard"
    "OPENVPN" -> "openvpn"
    "RDP", "WINDOWS", "远程桌面" -> "windows"
    "SSH" -> "ssh"
    "NAS" -> "nas"
    "绿联NAS" -> "ugreen"
    "飞牛NAS" -> "fnos"
    "群晖NAS" -> "dsm"
    "CAMERA", "IPC", "摄像头", "视频监控" -> "camera"
    "ROUTER", "路由器" -> "router"
    "PLEX", "EMBY", "JELLYFIN", "DLNA", "MEDIA" -> "media"
    "SMB", "NFS", "SAMBA" -> "nas"
    "FTP", "SFTP", "QBITTORRENT", "TRANSMISSION", "BITTORRENT" -> "download"
    "游戏联机", "GAME" -> "game"
    "TCP" -> "tcp"
    "UDP" -> "udp"
    "HTTP", "HTTPS", "WEB", "DNS", "CUSTOM", "" -> "web"
    else -> "web"
}

private fun applicationFromPort(protocol: String, port: Int): MappingApplicationMatch? {
    val proto = protocol.trim().uppercase(Locale.ROOT)
    return when {
        port in setOf(22, 54133) && proto == "TCP" -> MappingApplicationMatch("SSH", "ssh")
        port == 3389 && proto == "TCP" -> MappingApplicationMatch("RDP", "windows")
        port == 443 && proto == "TCP" -> MappingApplicationMatch("HTTPS", "web")
        port == 80 && proto == "TCP" -> MappingApplicationMatch("HTTP", "web")
        port == 21 && proto == "TCP" -> MappingApplicationMatch("FTP", "download")
        port == 23 && proto == "TCP" -> MappingApplicationMatch("Telnet", "tcp")
        port == 53 -> MappingApplicationMatch("DNS", "web")
        port == 51820 && proto == "UDP" -> MappingApplicationMatch("WireGuard", "wireguard")
        port == 1194 -> MappingApplicationMatch("OpenVPN", "openvpn")
        else -> null
    }
}

internal data class FavoriteServiceHint(
    val host: String,
    val port: Int,
    val serviceType: String,
    val protocol: String,
    val iconId: String,
    val title: String,
    val mappingOwned: Boolean,
)

internal fun inferredFavoriteProtocol(serviceType: String): String = when (serviceType.uppercase(Locale.ROOT)) {
    "SSH", "RDP", "HTTP", "HTTPS", "FTP", "SFTP", "PLEX", "EMBY", "JELLYFIN", "DLNA", "QBITTORRENT" -> "TCP"
    "WIREGUARD", "UDP" -> "UDP"
    "TCP" -> "TCP"
    else -> ""
}

private fun favoriteHostMatches(left: String, right: String): Boolean {
    if (left.equals(right.trim(), ignoreCase = true)) return true
    val normalizedLeft = normalizeIpv6(left) ?: return false
    return normalizedLeft == normalizeIpv6(right)
}

private fun favoriteProtocolMatches(favoriteProtocol: String, mappingProtocol: String): Boolean {
    val favorite = favoriteProtocol.trim().uppercase(Locale.ROOT)
    val mapping = mappingProtocol.trim().uppercase(Locale.ROOT).ifBlank { "TCP" }
    if (favorite.isBlank() || favorite in setOf("ANY", "TCP+UDP")) return true
    if (mapping in setOf("ANY", "TCP+UDP")) return true
    return favorite == mapping
}

internal fun recognizeMappingService(
    name: String = "",
    explicitType: String = "",
    protocol: String = "",
    internalPort: String = "",
    clientIp: String = "",
    devices: List<DeviceItem> = emptyList(),
    favoriteHints: List<FavoriteServiceHint> = emptyList(),
): MappingServiceRecognition {
    val normalizedProtocol = protocol.trim().uppercase(Locale.ROOT).ifBlank { "TCP" }
    val mechanism = if (wordsIn(name.lowercase(Locale.ROOT), "nat-pmp") || "natpmp" in name.lowercase(Locale.ROOT).replace("-", ""))
        "NAT-PMP" else ""
    fun result(serviceType: String, iconId: String) = MappingServiceRecognition(serviceType, iconId, mechanism)

    val explicit = explicitType.trim()
    if (explicit.isNotBlank()) {
        val icon = if (explicit.equals("Custom", ignoreCase = true)) favoriteIconForServiceType(normalizedProtocol)
        else favoriteIconForServiceType(explicit)
        return result(explicit, icon)
    }
    recognizeMappingApplication(name)?.let { return result(it.serviceType, it.iconId) }
    val port = internalPort.trim().toIntOrNull()
    if (port != null) {
        val hint = favoriteHints.asSequence()
            .filter {
                favoriteHostMatches(it.host, clientIp) && it.port == port &&
                    favoriteProtocolMatches(it.protocol, normalizedProtocol)
            }
            .sortedWith(
                compareByDescending<FavoriteServiceHint> { it.title.equals(name.trim(), ignoreCase = true) }
                    .thenByDescending { it.mappingOwned }
                    .thenBy { it.serviceType.isBlank() }
            )
            .firstOrNull()
        if (hint != null) {
            val type = hint.serviceType.ifBlank { recognizeMappingApplication(hint.title)?.serviceType.orEmpty() }
            if (type.isNotBlank()) return result(type, hint.iconId)
        }
    }
    if (port != null) {
        applicationFromPort(normalizedProtocol, port)?.let {
            return result(it.serviceType, it.iconId)
        }
    }
    val device = devices.firstOrNull { it.ip.trim() == clientIp.trim() }
    val deviceText = device?.let {
        listOf(it.remark, it.name, it.hostName, it.manufacture, it.devType, it.manualType, it.osType)
            .joinToString(" ")
    }.orEmpty()
    val deviceMatch = recognizeMappingApplication(deviceText)
    if (deviceMatch != null) return result(deviceMatch.serviceType, deviceMatch.iconId)
    if (port != null) {
        val nasContext = deviceText.lowercase(Locale.ROOT).let {
            "fnos" in it || "飞牛" in it || "ugreen" in it || "绿联" in it
        }
        if (port in setOf(5666, 5667) && normalizedProtocol == "TCP") {
            return if ("fnos" in deviceText.lowercase(Locale.ROOT) || "飞牛" in deviceText)
                result("飞牛NAS", "fnos")
            else if (nasContext) result("NAS", "nas") else result("", "tcp")
        }
        if (port in setOf(9443, 9999) && normalizedProtocol == "TCP") {
            return if ("ugreen" in deviceText.lowercase(Locale.ROOT) || "绿联" in deviceText)
                result("绿联NAS", "ugreen")
            else if (nasContext) result("NAS", "nas") else result("", "tcp")
        }
    }
    return result("", if (normalizedProtocol == "UDP") "udp" else "tcp")
}

internal fun favoriteServiceHints(favorites: List<FavoriteShortcut>): List<FavoriteServiceHint> = buildList {
    favorites.forEach { favorite ->
        val type = favorite.serviceType.trim().ifBlank {
            recognizeMappingApplication(favorite.title)?.serviceType.orEmpty()
        }
        if (type.isBlank()) return@forEach
        val endpoint = favorite.localEndpoint.ifBlank { favorite.lanUrl }
        val parsed = parseQuickAccessEndpoint(endpoint) ?: return@forEach
        val port = parsed.port ?: when (type.uppercase(Locale.ROOT)) {
            "HTTPS" -> 443
            "HTTP" -> 80
            "SSH" -> 22
            "RDP" -> 3389
            else -> return@forEach
        }
        val protocol = favorite.protocol.trim().ifBlank { inferredFavoriteProtocol(type) }
        val icon = if (favorite.iconType == "builtin") favorite.iconValue.ifBlank { favoriteIconForServiceType(type) }
        else favoriteIconForServiceType(type)
        add(
            FavoriteServiceHint(
                host = parsed.host.trim(),
                port = port,
                serviceType = type,
                protocol = protocol,
                iconId = icon,
                title = favorite.title,
                mappingOwned = favorite.type == "mapping",
            )
        )
    }
}

@Composable
internal fun FavoritePresetIcon(id: String, size: Int): Boolean {
    val preset = favoriteIconPresets.firstOrNull { it.id == id } ?: return false
    val resources = LocalContext.current.resources
    val painter = remember(resources, preset) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(resources, preset.resource, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false }
        val bitmap = BitmapFactory.decodeResource(resources, preset.resource, options).asImageBitmap()
        val scaleX = bitmap.width.toFloat() / bounds.outWidth
        val scaleY = bitmap.height.toFloat() / bounds.outHeight
        val offset = IntOffset((preset.offset.x * scaleX).toInt(), (preset.offset.y * scaleY).toInt())
        val crop = IntSize((preset.size.width * scaleX).toInt().coerceIn(1, bitmap.width - offset.x),
            (preset.size.height * scaleY).toInt().coerceIn(1, bitmap.height - offset.y))
        BitmapPainter(bitmap, offset, crop)
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * .22f).dp)), contentAlignment = Alignment.Center) {
        // Crop only the source's empty margin at render time. Never stretch a logo
        // or crop its artwork to a square; wide wordmarks keep their full width.
        Image(painter, preset.label, Modifier.fillMaxSize().padding(if (id in setOf("netgear", "ruijie", "openwrt", "alist", "wireguard", "openvpn", "repository")) 3.dp else 0.dp), contentScale = ContentScale.Fit)
    }
    return true
}
