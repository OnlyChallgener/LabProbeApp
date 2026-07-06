package com.labprobe.app

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

fun isValidMac(mac: String): Boolean = Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$").matches(mac.trim())

fun magicPacket(mac: String): ByteArray {
    val bytes = mac.trim().split(':').map { it.toInt(16).toByte() }.toByteArray()
    val packet = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
    var pos = 6
    repeat(16) {
        System.arraycopy(bytes, 0, packet, pos, 6)
        pos += 6
    }
    return packet
}

suspend fun sendWakeOnLanLocal(ctx: Context, mac: String): Int = withContext(Dispatchers.IO) {
    val packet = magicPacket(mac)
    val targets = linkedSetOf("255.255.255.255")

    runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val dhcp = wm?.dhcpInfo
        if (dhcp != null) {
            val ip = dhcp.ipAddress
            val mask = dhcp.netmask
            if (ip != 0 && mask != 0) {
                val broadcast = ip or mask.inv()
                val addr = listOf(
                    broadcast and 0xff,
                    broadcast shr 8 and 0xff,
                    broadcast shr 16 and 0xff,
                    broadcast shr 24 and 0xff
                ).joinToString(".")
                targets += addr
            }
        }
    }

    var sent = 0
    DatagramSocket().use { socket ->
        socket.broadcast = true
        targets.forEach { host ->
            repeat(3) {
                runCatching {
                    socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), 9))
                    sent++
                }
                runCatching {
                    socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), 7))
                    sent++
                }
            }
        }
    }
    sent
}
