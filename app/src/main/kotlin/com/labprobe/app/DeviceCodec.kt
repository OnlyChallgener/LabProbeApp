package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val DEVICE_VALUE_SPLIT = Regex("[,;，；\\s]+")

fun parseDeviceArray(json: String): List<DeviceItem> {
    if (json.isBlank()) return emptyList()
    val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
    return (0 until arr.length()).mapNotNull { index -> parseDevice(arr.optJSONObject(index)) }
}

fun parseEvents(json: String): List<EventItem> {
    if (json.isBlank()) return emptyList()
    val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
    val out = mutableListOf<EventItem>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optBoolean("deleted", false)) continue

        val type = o.optString("type")
        val newValueRaw = o.optString("newValue")
        if (type == "lucky_webhook" && (newValueRaw.contains("token", true) || newValueRaw.length < 10)) continue

        val dev = o.optJSONObject("device") ?: JSONObject()
        fun field(name: String): String = cleanApiText(o.optString(name)).ifBlank { cleanApiText(dev.optString(name)) }

        out += EventItem(
            id = o.optInt("id", 0),
            title = o.optString("title", type.ifBlank { "事件" }),
            type = type,
            name = o.optString("name").ifBlank { dev.optString("name") },
            oldValue = o.optString("oldValue", ""),
            newValue = maskSensitive(newValueRaw.ifBlank { o.optString("value", "") }),
            time = o.optString("createdAt", o.optString("time")),
            ip = field("ip").ifBlank { field("lastIp") },
            rssi = field("rssi").ifBlank { field("lastRssi") },
            band = field("band").ifBlank { field("lastBand") },
            rxrate = field("rxrate").ifBlank { field("lastRxrate") },
            ssid = field("ssid").ifBlank { field("lastSsid") },
            onlineSince = field("onlineSince"),
            offlineAt = field("offlineAt"),
            onlineDurationText = field("onlineDurationText"),
            mac = field("mac").ifBlank { field("deviceMac") }.ifBlank { field("lastMac") },
            manufacture = field("manufacture").ifBlank { field("vendor") }.ifBlank { field("oui") },
            devType = field("devType").ifBlank { field("deviceType") },
            osType = field("osType").ifBlank { field("os") },
            hostName = field("hostName").ifBlank { field("hostname") },
            remark = field("remark").ifBlank { field("note") },
            manualType = field("manualType").ifBlank { field("deviceTypeManual") }
        )
    }
    return out
}

private fun parseDevice(o: JSONObject?): DeviceItem? {
    if (o == null) return null
    fun f(k: String): String = cleanApiText(o.optString(k, ""))

    val mac = cleanMac(f("mac"))
    val name = f("name").ifBlank { f("devRecommend") }.ifBlank { f("hostName") }.ifBlank { mac }
    val ipv6Candidates = collectDeviceIpv6Candidates(o).take(24)
    val ipv6List = ipv6Candidates.map { it.address }

    return DeviceItem(
        name = name,
        mac = mac,
        online = o.optBoolean("online", true),
        ip = f("ip").ifBlank { f("userIp") }.ifBlank { f("lastIp") },
        ssid = f("ssid").ifBlank { f("lastSsid") },
        band = f("band").ifBlank { f("lastBand") },
        rssi = f("rssi").ifBlank { f("lastRssi") },
        rxrate = f("rxrate").ifBlank { f("lastRxrate") },
        onlineSince = f("onlineSince").ifBlank { f("onlinetime") },
        offlineAt = f("offlineAt"),
        onlineDurationText = f("onlineDurationText"),
        lastSeenAt = f("lastSeenAt"),
        todayOnlineDurationSec = o.optLong("todayOnlineDurationSec", 0L).coerceAtLeast(0L),
        todayOnlineDurationText = f("todayOnlineDurationText"),
        todayOnlineDate = f("todayOnlineDate"),
        ipv6 = ipv6List,
        ipv6Candidates = ipv6Candidates,
        manufacture = f("manufacture").ifBlank { f("vendor") }.ifBlank { f("oui") },
        devType = f("devType").ifBlank { f("deviceType") }.ifBlank { f("type") },
        osType = f("osType").ifBlank { f("os") },
        hostName = f("hostName").ifBlank { f("hostname") },
        wolMode = f("wolMode").ifBlank { f("wol") }.ifBlank { f("wolCapable") },
        connectType = f("connectType").ifBlank { f("connType") }.ifBlank { f("connectionType") },
        remark = f("remark").ifBlank { f("note") },
        manualType = f("manualType").ifBlank { f("deviceTypeManual") }.ifBlank { f("typeManual") },
        wolEnabledOverride = boolOrNull(o, "wolEnabled").orElse(boolOrNull(o, "manualWol")).orElse(boolOrNull(o, "wolSwitch")),
        followedOverride = boolOrNull(o, "followed"),
        todayUpload = trafficValue(
            o,
            flatKeys = listOf("todayUpload", "todayUp", "todayUploadTraffic", "todayUpTraffic", "todayTx", "todayTxBytes", "dailyUpload", "dailyUp", "dayUpload", "dayUp", "today_upload", "today_up", "today_tx"),
            groupKeys = listOf("todayTraffic", "todayFlow", "dailyTraffic", "dailyFlow", "trafficToday", "today", "今日流量"),
            directionKeys = listOf("upload", "up", "tx", "upstream", "sent", "send", "uploadBytes", "upBytes", "txBytes", "上行", "上传"),
            directionLabels = listOf("上行", "上传", "upload", "up", "tx")
        ),
        todayDownload = trafficValue(
            o,
            flatKeys = listOf("todayDownload", "todayDown", "todayDownloadTraffic", "todayDownTraffic", "todayRx", "todayRxBytes", "dailyDownload", "dailyDown", "dayDownload", "dayDown", "today_download", "today_down", "today_rx"),
            groupKeys = listOf("todayTraffic", "todayFlow", "dailyTraffic", "dailyFlow", "trafficToday", "today", "今日流量"),
            directionKeys = listOf("download", "down", "rx", "downstream", "received", "receive", "downloadBytes", "downBytes", "rxBytes", "下行", "下载"),
            directionLabels = listOf("下行", "下载", "download", "down", "rx")
        ),
        totalUpload = trafficValue(
            o,
            flatKeys = listOf("totalUpload", "totalUp", "totalUploadTraffic", "totalUpTraffic", "realtimeUpload", "realTimeUpload", "realtimeUp", "realTimeUp", "totalTx", "totalTxBytes", "bootUpload", "bootUp", "total_upload", "total_up", "realtime_upload", "realtime_up"),
            groupKeys = listOf("realtimeTraffic", "realTimeTraffic", "totalTraffic", "trafficTotal", "realtimeFlow", "realTimeFlow", "totalFlow", "bootTraffic", "currentTraffic", "实时总流量"),
            directionKeys = listOf("upload", "up", "tx", "upstream", "sent", "send", "uploadBytes", "upBytes", "txBytes", "上行", "上传"),
            directionLabels = listOf("上行", "上传", "upload", "up", "tx")
        ),
        totalDownload = trafficValue(
            o,
            flatKeys = listOf("totalDownload", "totalDown", "totalDownloadTraffic", "totalDownTraffic", "realtimeDownload", "realTimeDownload", "realtimeDown", "realTimeDown", "totalRx", "totalRxBytes", "bootDownload", "bootDown", "total_download", "total_down", "realtime_download", "realtime_down"),
            groupKeys = listOf("realtimeTraffic", "realTimeTraffic", "totalTraffic", "trafficTotal", "realtimeFlow", "realTimeFlow", "totalFlow", "bootTraffic", "currentTraffic", "实时总流量"),
            directionKeys = listOf("download", "down", "rx", "downstream", "received", "receive", "downloadBytes", "downBytes", "rxBytes", "下行", "下载"),
            directionLabels = listOf("下行", "下载", "download", "down", "rx")
        ),
        realtimeUploadBytes = o.optLong(
            "realtimeUploadBytes",
            o.optLong("realtimeUpload", o.optLong("realtimeUpBytes", o.optLong("flowUp", 0L)))
        ).coerceAtLeast(0L),
        realtimeDownloadBytes = o.optLong(
            "realtimeDownloadBytes",
            o.optLong("realtimeDownload", o.optLong("realtimeDownBytes", o.optLong("flowDown", 0L)))
        ).coerceAtLeast(0L),
        connectionCount = o.optInt(
            "connectionCount",
            o.optInt("flow_cnt", o.optInt("flowCnt", 0))
        ).coerceAtLeast(0)
    )
}

private val TRAFFIC_CONTAINER_KEYS = listOf("traffic", "flow", "trafficStats", "flowStats", "statistics", "stats", "流量")

private fun trafficValue(
    root: JSONObject,
    flatKeys: List<String>,
    groupKeys: List<String>,
    directionKeys: List<String>,
    directionLabels: List<String>
): String {
    scalarValue(root, flatKeys)?.let { return normalizeTrafficValue(it) }
    groupedTrafficValue(root, groupKeys, directionKeys, directionLabels)?.let { return it }

    TRAFFIC_CONTAINER_KEYS.forEach { containerKey ->
        val container = root.optJSONObject(containerKey) ?: return@forEach
        scalarValue(container, flatKeys)?.let { return normalizeTrafficValue(it) }
        groupedTrafficValue(container, groupKeys, directionKeys, directionLabels)?.let { return it }
    }
    return ""
}

private fun groupedTrafficValue(
    root: JSONObject,
    groupKeys: List<String>,
    directionKeys: List<String>,
    directionLabels: List<String>
): String? {
    groupKeys.forEach { groupKey ->
        val raw = root.opt(groupKey) ?: return@forEach
        if (raw == JSONObject.NULL) return@forEach
        if (raw is JSONObject) {
            scalarValue(raw, directionKeys)?.let { return normalizeTrafficValue(it) }
        } else if (raw is String) {
            labeledTrafficValue(raw, directionLabels)?.let { return it }
        }
    }
    return null
}

private fun scalarValue(root: JSONObject, keys: List<String>): Any? {
    keys.forEach { key ->
        if (!root.has(key) || root.isNull(key)) return@forEach
        val raw = root.opt(key)
        if (raw is String || raw is Number) return raw
    }
    return null
}

private fun labeledTrafficValue(raw: String, labels: List<String>): String? {
    val labelPattern = labels.joinToString("|") { Regex.escape(it) }
    val match = Regex("(?i)(?:$labelPattern)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?\\s*(?:[KMGT]i?B?|B)?)").find(raw) ?: return null
    return normalizeTrafficValue(match.groupValues[1])
}

private fun normalizeTrafficValue(raw: Any): String {
    val text = cleanApiText(raw.toString()).replace(" ", "")
    if (text.isBlank()) return ""
    val numeric = when (raw) {
        is Number -> raw.toDouble()
        is String -> text.toDoubleOrNull()?.takeIf { it >= 1024.0 }
        else -> null
    }
    return numeric?.takeIf { it.isFinite() && it >= 0.0 }?.let(::formatTrafficBytesCompact) ?: text
}

private fun formatTrafficBytesCompact(bytes: Double): String {
    if (bytes < 1024.0) return "${bytes.toLong()}B"
    val units = arrayOf("K", "M", "G", "T")
    var value = bytes
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.2f%s", value, units[unit.coerceAtLeast(0)])
}

private val DEVICE_IPV6_KEYS = listOf(
    "ipv6Records", "ipv6", "ipv6Address", "globalIpv6", "globalIPv6", "ipv6List", "lastIpv6",
    "ndpIpv6", "ndpIPv6", "ipv6Addrs", "ipv6Addresses", "addresses", "ipv6Candidates"
)

private fun collectDeviceIpv6Candidates(o: JSONObject): List<Ipv6AddressCandidate> {
    val out = mutableListOf<Ipv6AddressCandidate>()
    DEVICE_IPV6_KEYS.forEach { key -> readDeviceIpv6Value(o.opt(key), key, out) }
    return mergeIpv6Candidates(out)
}

private fun readDeviceIpv6Value(value: Any?, sourceKey: String, out: MutableList<Ipv6AddressCandidate>) {
    when (value) {
        null, JSONObject.NULL -> Unit
        is String -> value.split(DEVICE_VALUE_SPLIT).forEach { raw ->
            cleanApiText(raw).takeIf { it.contains(':') }?.let {
                val primary = sourceKey in setOf("ipv6", "ipv6Address", "globalIpv6", "globalIPv6")
                out += Ipv6AddressCandidate(it, source = if (primary) "hub_primary" else sourceKey, primary = primary)
            }
        }
        is JSONArray -> for (index in 0 until value.length()) readDeviceIpv6Value(value.opt(index), sourceKey, out)
        is JSONObject -> {
            val address = listOf("ip", "ipv6", "address", "value", "addr")
                .asSequence()
                .map { cleanApiText(value.optString(it)) }
                .firstOrNull { it.contains(':') }
            if (address != null) {
                val state = cleanApiText(value.optString("state").ifBlank { value.optString("status") }.ifBlank { value.optString("reachability") })
                var source = cleanApiText(value.optString("source").ifBlank { value.optString("origin") }.ifBlank { value.optString("method") }.ifBlank { value.optString("addressType") }.ifBlank { value.optString("type") }).ifBlank { sourceKey }
                if (value.optBoolean("temporary", false) || value.optBoolean("privacy", false)) source += " temporary"
                out += Ipv6AddressCandidate(
                    address = address,
                    state = state,
                    source = source,
                    lastSeenAt = firstIpv6Timestamp(value),
                    primary = value.optBoolean("primary", false),
                    currentPrefix = value.optBoolean("currentPrefix", false),
                    historical = value.optBoolean("historical", false)
                )
            } else {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key.contains(':')) {
                        val meta = child as? JSONObject
                        val state = if (meta == null) "" else meta.optString("state").ifBlank { meta.optString("status") }
                        val source = if (meta == null) sourceKey else meta.optString("source").ifBlank { meta.optString("origin") }.ifBlank { sourceKey }
                        out += Ipv6AddressCandidate(
                            address = key,
                            state = state,
                            source = source,
                            lastSeenAt = meta?.let(::firstIpv6Timestamp),
                            primary = meta?.optBoolean("primary", false) ?: false,
                            currentPrefix = meta?.optBoolean("currentPrefix", false) ?: false,
                            historical = meta?.optBoolean("historical", false) ?: false
                        )
                    } else {
                        readDeviceIpv6Value(child, key, out)
                    }
                }
            }
        }
    }
}

private fun firstIpv6Timestamp(o: JSONObject): Long? {
    listOf("lastSeen", "lastSeenAt", "lastReachable", "updatedAt", "seenAt", "timestamp", "time", "createdAt").forEach { key ->
        if (o.has(key) && !o.isNull(key)) parseIpv6Timestamp(o.opt(key))?.let { return it }
    }
    return null
}

fun DeviceItem.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("mac", mac)
    .put("online", online)
    .put("ip", ip)
    .put("ssid", ssid)
    .put("band", band)
    .put("rssi", rssi)
    .put("rxrate", rxrate)
    .put("onlineSince", onlineSince)
    .put("offlineAt", offlineAt)
    .put("onlineDurationText", onlineDurationText)
    .put("todayOnlineDurationSec", todayOnlineDurationSec)
    .put("todayOnlineDurationText", todayOnlineDurationText)
    .put("todayOnlineDate", todayOnlineDate)
    .put("lastSeenAt", lastSeenAt)
    .put("ipv6", pickIpv6().best ?: "")
    .put("ipv6List", JSONArray(ipv6))
    .put("ipv6Candidates", JSONArray(ipv6Candidates.map { candidate ->
        JSONObject()
            .put("address", candidate.address)
            .put("state", candidate.state)
            .put("source", candidate.source)
            .put("lastSeenAt", candidate.lastSeenAt ?: JSONObject.NULL)
            .put("primary", candidate.primary)
            .put("currentPrefix", candidate.currentPrefix)
            .put("historical", candidate.historical)
    }))
    .put("manufacture", manufacture)
    .put("devType", devType)
    .put("osType", osType)
    .put("hostName", hostName)
    .put("wolMode", wolMode)
    .put("connectType", connectType)
    .put("remark", remark)
    .put("manualType", manualType)
    .put("wolEnabled", wolEnabledOverride ?: JSONObject.NULL)
    .put("followed", followedOverride ?: JSONObject.NULL)
    .put("todayUpload", todayUpload)
    .put("todayDownload", todayDownload)
    .put("totalUpload", totalUpload)
    .put("totalDownload", totalDownload)
    .put("realtimeUploadBytes", realtimeUploadBytes)
    .put("realtimeDownloadBytes", realtimeDownloadBytes)
    .put("connectionCount", connectionCount)
    .put("realtimeUploadBytes", realtimeUploadBytes)
    .put("realtimeDownloadBytes", realtimeDownloadBytes)
    .put("connectionCount", connectionCount)
    .put("realtimeUploadBytes", realtimeUploadBytes)
    .put("realtimeDownloadBytes", realtimeDownloadBytes)
    .put("connectionCount", connectionCount)

fun EventItem.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("type", type)
    .put("name", name)
    .put("oldValue", oldValue)
    .put("newValue", newValue)
    .put("createdAt", time)
    .put("ip", ip)
    .put("rssi", rssi)
    .put("band", band)
    .put("rxrate", rxrate)
    .put("ssid", ssid)
    .put("onlineSince", onlineSince)
    .put("offlineAt", offlineAt)
    .put("onlineDurationText", onlineDurationText)
    .put("mac", mac)
    .put("manufacture", manufacture)
    .put("devType", devType)
    .put("osType", osType)
    .put("hostName", hostName)
    .put("remark", remark)
    .put("manualType", manualType)


private fun boolOrNull(o: JSONObject, key: String): Boolean? {
    if (!o.has(key) || o.isNull(key)) return null
    val raw = o.opt(key) ?: return null
    return when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> when (raw.trim().lowercase()) {
            "1", "true", "yes", "on", "enable", "enabled", "支持", "开启" -> true
            "0", "false", "no", "off", "disable", "disabled", "不支持", "关闭" -> false
            else -> null
        }
        else -> null
    }
}

private fun Boolean?.orElse(other: Boolean?): Boolean? = this ?: other
