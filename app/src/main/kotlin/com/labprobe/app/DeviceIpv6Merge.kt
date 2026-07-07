package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun mergeIpv6NeighborsFromStatus(status: JSONObject?, list: List<DeviceItem>): List<DeviceItem> {
    if (status == null || list.isEmpty()) return list
    val neighbors = collectIpv6Neighbors(status)
    if (neighbors.isEmpty()) return list
    val byMac = neighbors.groupBy { it.mac.lowercase(Locale.getDefault()) }
    return list.map { d ->
        val mac = d.mac.lowercase(Locale.getDefault())
        val matches = byMac[mac].orEmpty()
        if (matches.isEmpty()) d else {
            val sortedIps = matches.sortedByDescending { it.score }.map { it.ip }
            val merged = (sortedIps + d.ipv6)
                .map { it.substringBefore('/').trim() }
                .filter { it.contains(':') && !it.startsWith("fe80:", ignoreCase = true) }
                .distinct()
                .take(8)
            d.copy(ipv6 = merged)
        }
    }
}

private data class Ipv6NeighborHit(val mac: String, val ip: String, val score: Int)

private fun collectIpv6Neighbors(root: Any?): List<Ipv6NeighborHit> {
    val out = mutableListOf<Ipv6NeighborHit>()
    fun walk(value: Any?) {
        when (value) {
            is JSONObject -> {
                val maybeArray = value.optJSONArray("ipv6_neighbors")
                    ?: value.optJSONArray("ipv6Neighbors")
                    ?: value.optJSONArray("ndp")
                    ?: value.optJSONArray("neighbors")
                if (maybeArray != null) readNeighborArray(maybeArray, out)
                val keys = value.keys()
                while (keys.hasNext()) walk(value.opt(keys.next()))
            }
            is JSONArray -> {
                val allObjectsLookLikeNeighbors = (0 until value.length()).any { i ->
                    val o = value.optJSONObject(i)
                    o != null && (o.has("mac") || o.has("lladdr")) && (o.has("ip") || o.has("ipv6") || o.has("address"))
                }
                if (allObjectsLookLikeNeighbors) readNeighborArray(value, out) else {
                    for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
        }
    }
    walk(root)
    return out.distinctBy { it.mac + "|" + it.ip }
}

private fun readNeighborArray(arr: JSONArray, out: MutableList<Ipv6NeighborHit>) {
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val mac = cleanMac(o.optString("mac").ifBlank { o.optString("lladdr") })
        val ip = cleanApiText(o.optString("ip").ifBlank { o.optString("ipv6") }.ifBlank { o.optString("address") })
            .substringBefore('/')
            .trim()
        if (mac.isBlank() || ip.isBlank() || !ip.contains(':') || ip.startsWith("fe80:", ignoreCase = true)) continue
        val state = o.optString("state").lowercase(Locale.getDefault())
        val score = when {
            state.contains("reachable") -> 100
            state.contains("delay") || state.contains("probe") -> 85
            state.contains("stale") -> 70
            else -> 60
        }
        out += Ipv6NeighborHit(mac, ip, score)
    }
}

fun cleanMac(raw: String): String = raw.trim().replace('-', ':').lowercase(Locale.getDefault())
