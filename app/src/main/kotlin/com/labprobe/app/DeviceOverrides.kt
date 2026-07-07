package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class DeviceOverrideConfig(
    val mac: String,
    val remark: String = "",
    val typeId: String = "",
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
            wolEnabledOverride = ov.wolEnabledOverride ?: d.wolEnabledOverride
        )
    }
}

fun overrideForDevice(device: DeviceItem, overrides: List<DeviceOverrideConfig>): DeviceOverrideConfig {
    val mac = cleanMac(device.mac)
    val old = overrides.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
    return old ?: DeviceOverrideConfig(
        mac = mac,
        remark = device.remark.ifBlank { device.name },
        typeId = device.manualType.ifBlank { inferDeviceProfile(device).type },
        wolEnabledOverride = device.wolEnabledOverride
    )
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
