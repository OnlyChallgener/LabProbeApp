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
    val text = name.lowercase(Locale.ROOT)
    val compact = text.replace(Regex("[\\s_\\-]+"), "")
    fun word(value: String) = Regex("(?<![a-z0-9])" + Regex.escape(value) + "(?![a-z0-9])").containsMatchIn(text)
    return when {
        "ugreen" in compact || "绿联" in text -> "ugreen"
        "synology" in compact || "群晖" in text || word("dsm") -> "dsm"
        "fnos" in compact || "飞牛" in text -> "fnos"
        "homeassistant" in compact || "家庭助理" in text -> "homeassistant"
        "wireguard" in compact || word("wg") -> "wireguard"
        "openvpn" in compact -> "openvpn"
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
        "windows" in compact || "远程桌面" in text || word("rdp") -> "windows"
        word("ssh") -> "ssh"
        "摄像头" in text || "摄像机" in text || "监控" in text || word("camera") || word("ipc") -> "camera"
        "路由器" in text || word("router") -> "router"
        word("nas") || "存储" in text -> "nas"
        else -> when (serviceType.trim().uppercase(Locale.ROOT)) {
            "WIREGUARD", "WG" -> "wireguard"
            "OPENVPN" -> "openvpn"
            "RDP", "WINDOWS", "远程桌面" -> "windows"
            "SSH" -> "ssh"
            "NAS" -> "nas"
            "CAMERA", "IPC", "摄像头" -> "camera"
            "ROUTER", "路由器" -> "router"
            else -> "web"
        }
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
