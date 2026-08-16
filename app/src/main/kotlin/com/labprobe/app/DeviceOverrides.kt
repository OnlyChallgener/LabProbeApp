package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class DeviceOverrideConfig(
    val mac: String,
    val remark: String = "",
    val typeId: String = "",
    val followedOverride: Boolean? = null,
    val wolEnabledOverride: Boolean? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

fun parseDeviceOverrides(json: String): List<DeviceOverrideConfig> {
    if (json.isBlank()) return emptyList()
    val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val mac = cleanMac(o.optString("mac"))
        if (!isValidMac(mac)) return@mapNotNull null
        DeviceOverrideConfig(
            mac = mac,
            remark = cleanApiText(o.optString("remark").ifBlank { o.optString("name") }),
            typeId = normalizeDeviceTypeToken(o.optString("typeId").ifBlank { o.optString("type") }).ifBlank { o.optString("typeId").ifBlank { o.optString("type") }.trim() },
            followedOverride = jsonBoolOrNull(o, "followed"),
            wolEnabledOverride = jsonBoolOrNull(o, "wolEnabled"),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }.distinctBy { it.mac.lowercase(Locale.getDefault()) }
}

fun deviceOverridesToJson(list: List<DeviceOverrideConfig>): String {
    val arr = JSONArray()
    list.distinctBy { it.mac.lowercase(Locale.getDefault()) }.forEach { item ->
        arr.put(JSONObject()
            .put("mac", item.mac)
            .put("remark", item.remark)
            .put("typeId", item.typeId)
            .put("followed", item.followedOverride ?: JSONObject.NULL)
            .put("wolEnabled", item.wolEnabledOverride ?: JSONObject.NULL)
            .put("updatedAt", item.updatedAt)
        )
    }
    return arr.toString()
}

fun applyDeviceOverrides(devices: List<DeviceItem>, overrides: List<DeviceOverrideConfig>): List<DeviceItem> {
    if (devices.isEmpty() || overrides.isEmpty()) return devices
    val byMac = overrides.associateBy { it.mac.lowercase(Locale.getDefault()) }
    return devices.map { d ->
        val ov = byMac[d.mac.lowercase(Locale.getDefault())] ?: return@map d
        d.copy(
            remark = ov.remark.ifBlank { d.remark },
            manualType = ov.typeId.ifBlank { d.manualType },
            followedOverride = ov.followedOverride ?: d.followedOverride,
            wolEnabledOverride = ov.wolEnabledOverride ?: d.wolEnabledOverride
        )
    }
}

fun applyEventDeviceNames(
    events: List<EventItem>,
    devices: List<DeviceItem>,
    overrides: List<DeviceOverrideConfig>
): List<EventItem> {
    if (events.isEmpty()) return events
    val devicesByMac = devices.filter { isValidMac(cleanMac(it.mac)) }
        .associateBy { cleanMac(it.mac).lowercase(Locale.getDefault()) }
    val overridesByMac = overrides.associateBy { cleanMac(it.mac).lowercase(Locale.getDefault()) }
    val enriched = events.map { event ->
        if (event.type != "device_online" && event.type != "device_offline") return@map event
        val key = cleanMac(event.mac).lowercase(Locale.getDefault())
        val device = devicesByMac[key]
        val override = overridesByMac[key]
        val localRemark = override?.remark.orEmpty().ifBlank { device?.remark.orEmpty() }
        event.copy(
            name = localRemark.ifBlank { event.remark }.ifBlank { device?.name.orEmpty() }.ifBlank { event.name },
            remark = localRemark.ifBlank { event.remark },
            manualType = override?.typeId.orEmpty().ifBlank { device?.manualType.orEmpty() }.ifBlank { event.manualType },
            devType = device?.devType.orEmpty().ifBlank { event.devType },
            manufacture = device?.manufacture.orEmpty().ifBlank { event.manufacture },
            osType = device?.osType.orEmpty().ifBlank { event.osType },
            hostName = device?.hostName.orEmpty().ifBlank { event.hostName }
        )
    }
    val keys = enriched.asSequence()
        .filter { it.type == "device_online" || it.type == "device_offline" }
        .filterNot(::hasUsableEventDeviceName)
        .sortedBy { parseEventMillis(it.time) ?: Long.MAX_VALUE }
        .map(::unknownEventDeviceKey).distinct().toList()
    val numbers = keys.withIndex().associate { (index, key) -> key to index + 1 }
    return enriched.map { event ->
        if ((event.type == "device_online" || event.type == "device_offline") && !hasUsableEventDeviceName(event)) {
            event.copy(name = "未知设备${numbers[unknownEventDeviceKey(event)] ?: 1}")
        } else event
    }
}

private fun hasUsableEventDeviceName(event: EventItem): Boolean {
    val name = event.name.trim()
    if (name.isBlank()) return false
    val normalized = name.lowercase(Locale.getDefault())
    if (normalized in setOf("事件", "设备", "终端", "未知", "unknown", "device") || name.startsWith("未知设备")) return false
    if (isValidMac(cleanMac(name)) || name == event.ip.trim()) return false
    return true
}

private fun unknownEventDeviceKey(event: EventItem): String {
    val mac = cleanMac(event.mac)
    return when {
        isValidMac(mac) -> "mac:${mac.lowercase(Locale.getDefault())}"
        event.ip.isNotBlank() -> "ip:${event.ip.trim()}"
        else -> "event:${event.id}"
    }
}

fun overrideForDevice(device: DeviceItem, overrides: List<DeviceOverrideConfig>): DeviceOverrideConfig {
    val mac = cleanMac(device.mac)
    val old = overrides.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
    return old ?: DeviceOverrideConfig(
        mac = mac,
        remark = device.remark.ifBlank { device.name },
        typeId = device.manualType.ifBlank { inferDeviceProfile(device).type },
        followedOverride = device.followedOverride,
        wolEnabledOverride = device.wolEnabledOverride
    )
}

fun followedDeviceList(devices: List<DeviceItem>): List<DeviceItem> = devices
    .filter { it.followedOverride == true }
    .distinctBy { cleanMac(it.mac) }
    .sortedWith(
        compareByDescending<DeviceItem> { it.online }
            .thenByDescending { deviceLastOnlineMillis(it) }
            .thenBy { it.remark.ifBlank { it.name }.lowercase(Locale.getDefault()) }
    )

private fun deviceLastOnlineMillis(device: DeviceItem): Long = listOf(
    device.lastSeenAt,
    device.offlineAt,
    device.onlineSince
).mapNotNull(::parseEventMillis).maxOrNull() ?: 0L

/**
 * Hub 的关注列表与 APP 本地“添加到关注”不是同一概念。
 * 这里仅在本地缓存中补回用户明确关注过、但本次 Hub 在线/设备列表未返回的设备快照。
 */
fun preserveFollowedDeviceSnapshots(
    base: List<DeviceItem>,
    previous: List<DeviceItem>,
    online: List<DeviceItem>,
    overrides: List<DeviceOverrideConfig>
): List<DeviceItem> {
    val followedMacs = overrides.filter { it.followedOverride == true }.map { cleanMac(it.mac) }.toSet()
    if (followedMacs.isEmpty()) return applyDeviceOverrides(base, overrides)
    val baseByMac = base.associateBy { cleanMac(it.mac) }.toMutableMap()
    val previousByMac = previous.associateBy { cleanMac(it.mac) }
    val onlineByMac = online.associateBy { cleanMac(it.mac) }
    followedMacs.forEach { mac ->
        val snapshot = onlineByMac[mac]
            ?: baseByMac[mac]
            ?: previousByMac[mac]?.copy(online = false)
        if (snapshot != null) baseByMac[mac] = snapshot
    }
    return applyDeviceOverrides(baseByMac.values.toList(), overrides)
}

private fun jsonBoolOrNull(o: JSONObject, key: String): Boolean? {
    if (!o.has(key) || o.isNull(key)) return null
    return when (val v = o.opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase(Locale.getDefault())) {
            "1", "true", "yes", "on", "enable", "enabled", "开启", "支持" -> true
            "0", "false", "no", "off", "disable", "disabled", "关闭", "不支持" -> false
            else -> null
        }
        else -> null
    }
}
