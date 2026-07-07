package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject

private val DEVICE_VALUE_SPLIT = Regex("[,\\s]+")

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
            mac = field("mac").ifBlank { field("deviceMac") }.ifBlank { field("lastMac") }
        )
    }
    return out
}

private fun parseDevice(o: JSONObject?): DeviceItem? {
    if (o == null) return null
    fun f(k: String): String = cleanApiText(o.optString(k, ""))

    val mac = f("mac")
    val name = f("name").ifBlank { f("devRecommend") }.ifBlank { f("hostName") }.ifBlank { mac }
    val ipv6List = buildList {
        addAll(jsonStringList(o, "ipv6List"))
        addAll(jsonStringList(o, "ipv6"))
        addAll(f("ipv6").split(DEVICE_VALUE_SPLIT))
        addAll(f("ipv6Address").split(DEVICE_VALUE_SPLIT))
        addAll(f("lastIpv6").split(DEVICE_VALUE_SPLIT))
        addAll(f("globalIpv6").split(DEVICE_VALUE_SPLIT))
        addAll(f("globalIPv6").split(DEVICE_VALUE_SPLIT))
        addAll(f("ndpIpv6").split(DEVICE_VALUE_SPLIT))
        addAll(f("ndpIPv6").split(DEVICE_VALUE_SPLIT))
        addAll(jsonStringList(o, "ipv6Addrs"))
        addAll(jsonStringList(o, "ipv6Addresses"))
        addAll(jsonStringList(o, "addresses"))
    }
        .map { it.substringBefore('/').trim() }
        .map(::cleanApiText)
        .filter { it.contains(':') && !it.startsWith("fe80:", ignoreCase = true) }
        .distinct()
        .take(6)

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
        ipv6 = ipv6List,
        manufacture = f("manufacture").ifBlank { f("vendor") }.ifBlank { f("oui") },
        devType = f("devType").ifBlank { f("deviceType") }.ifBlank { f("type") },
        osType = f("osType").ifBlank { f("os") },
        hostName = f("hostName").ifBlank { f("hostname") },
        wolMode = f("wolMode").ifBlank { f("wol") }.ifBlank { f("wolCapable") },
        connectType = f("connectType").ifBlank { f("connType") }.ifBlank { f("connectionType") },
        remark = f("remark").ifBlank { f("note") },
        manualType = f("manualType").ifBlank { f("deviceTypeManual") }.ifBlank { f("typeManual") },
        wolEnabledOverride = boolOrNull(o, "wolEnabled").orElse(boolOrNull(o, "manualWol")).orElse(boolOrNull(o, "wolSwitch"))
    )
}

private fun jsonStringList(o: JSONObject, key: String): List<String> {
    val v = o.opt(key) ?: return emptyList()
    return when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { index -> cleanApiText(v.optString(index)).takeIf { it.isNotBlank() } }
        is String -> v.split(DEVICE_VALUE_SPLIT).map(::cleanApiText).filter { it.isNotBlank() }
        else -> emptyList()
    }
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
    .put("lastSeenAt", lastSeenAt)
    .put("ipv6List", JSONArray(ipv6))
    .put("manufacture", manufacture)
    .put("devType", devType)
    .put("osType", osType)
    .put("hostName", hostName)
    .put("wolMode", wolMode)
    .put("connectType", connectType)
    .put("remark", remark)
    .put("manualType", manualType)
    .put("wolEnabled", wolEnabledOverride ?: JSONObject.NULL)

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
