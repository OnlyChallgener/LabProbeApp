package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun mergeIpv6NeighborsFromStatus(status: JSONObject?, list: List<DeviceItem>): List<DeviceItem> {
    if (status == null || list.isEmpty()) return list
    val neighbors = collectIpv6Neighbors(status)
    if (neighbors.isEmpty()) return list
    val byMac = neighbors.groupBy { cleanMac(it.mac) }
    return list.map { d ->
        val mac = cleanMac(d.mac)
        val matches = byMac[mac].orEmpty()
        if (matches.isEmpty()) d else {
            val merged = mergeIpv6Candidates(
                matches.map { it.candidate },
                d.ipv6Candidates,
                d.ipv6.map { Ipv6AddressCandidate(it) }
            ).take(24)
            d.copy(ipv6 = merged.map { it.address }, ipv6Candidates = merged)
        }
    }
}

private data class Ipv6NeighborHit(val mac: String, val candidate: Ipv6AddressCandidate)

private fun collectIpv6Neighbors(root: Any?): List<Ipv6NeighborHit> {
    val out = mutableListOf<Ipv6NeighborHit>()
    fun walk(value: Any?) {
        when (value) {
            is String -> readNeighborText(value, out)
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
    return out.distinctBy { it.mac + "|" + it.candidate.address + "|" + it.candidate.state + "|" + it.candidate.source }
}

private fun readNeighborText(text: String, out: MutableList<Ipv6NeighborHit>) {
    if (!text.contains("lladdr", ignoreCase = true) || !text.contains(":")) return
    val linePattern = Regex(
        "^\\s*([0-9a-fA-F:]+)\\s+dev\\s+\\S+.*?\\blladdr\\s+([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\\b(.*)$",
        RegexOption.IGNORE_CASE
    )
    val statePattern = Regex("\\b(REACHABLE|STALE|DELAY|PROBE|FAILED|INCOMPLETE|PERMANENT|NOARP)\\b", RegexOption.IGNORE_CASE)
    text.lineSequence().forEach { line ->
        val match = linePattern.find(line) ?: return@forEach
        val normalized = normalizeIpv6(match.groupValues[1]) ?: return@forEach
        val mac = cleanMac(match.groupValues[2])
        if (mac.isBlank()) return@forEach
        val tail = match.groupValues.getOrNull(3).orEmpty()
        val state = statePattern.findAll(tail).lastOrNull()?.value.orEmpty()
        out += Ipv6NeighborHit(mac, Ipv6AddressCandidate(normalized, state, "ndp text"))
    }
}

private fun readNeighborArray(arr: JSONArray, out: MutableList<Ipv6NeighborHit>) {
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val mac = cleanMac(o.optString("mac").ifBlank { o.optString("lladdr") })
        val ip = cleanApiText(o.optString("ip").ifBlank { o.optString("ipv6") }.ifBlank { o.optString("address") }.ifBlank { o.optString("addr") })
        val normalized = normalizeIpv6(ip) ?: continue
        if (mac.isBlank()) continue
        val state = cleanApiText(o.optString("state").ifBlank { o.optString("status") }.ifBlank { o.optString("reachability") })
        var source = cleanApiText(o.optString("source").ifBlank { o.optString("origin") }.ifBlank { o.optString("method") }.ifBlank { o.optString("addressType") }.ifBlank { o.optString("type") }).ifBlank { "ndp" }
        if (o.optBoolean("temporary", false) || o.optBoolean("privacy", false)) source += " temporary"
        val lastSeen = listOf("lastSeenAt", "updatedAt", "seenAt", "timestamp", "time")
            .asSequence()
            .mapNotNull { key -> if (o.has(key) && !o.isNull(key)) parseIpv6Timestamp(o.opt(key)) else null }
            .firstOrNull()
        out += Ipv6NeighborHit(mac, Ipv6AddressCandidate(normalized, state, source, lastSeen))
    }
}

fun cleanMac(raw: String): String = raw.trim().replace('-', ':').lowercase(Locale.getDefault())
