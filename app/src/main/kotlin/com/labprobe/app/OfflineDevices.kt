package com.labprobe.app

import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OFFLINE_GENERIC_NAMES = setOf(
    "unknown", "unknowndevice", "device", "android", "iphone", "ipad",
    "phone", "mobile", "pc", "computer", "tablet", "tv", "未知设备"
)

fun offlineDeviceIdentity(device: DeviceItem): String {
    val candidate = sequenceOf(device.hostName, device.name, device.remark)
        .map(::cleanApiText)
        .firstOrNull { value ->
            value.isNotBlank() &&
                !value.equals(device.mac, ignoreCase = true) &&
                !value.matches(Regex("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}")) &&
                !value.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        }
        .orEmpty()
    val normalized = candidate.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
    return if (normalized.length >= 4 && normalized !in OFFLINE_GENERIC_NAMES) {
        "name:$normalized"
    } else {
        "mac:${cleanMac(device.mac)}"
    }
}

fun parseOfflineHiddenKeys(raw: String): Set<String> {
    val array = runCatching { JSONArray(raw) }.getOrElse { return emptySet() }
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
}

fun offlineHiddenKeysJson(keys: Set<String>): String = JSONArray(keys.sorted()).toString()

fun aggregateOfflineDevices(
    archived: List<DeviceItem>,
    online: List<DeviceItem>,
    hiddenKeys: Set<String>
): List<DeviceItem> {
    val onlineMacs = online.map { cleanMac(it.mac) }.filter(String::isNotBlank).toSet()
    val onlineIdentities = online.map(::offlineDeviceIdentity).filterNot { it == "mac:" }.toSet()
    return archived.asSequence()
        .filterNot { cleanMac(it.mac) in onlineMacs }
        .filterNot { offlineDeviceIdentity(it) in onlineIdentities }
        .filterNot { offlineDeviceIdentity(it) in hiddenKeys }
        .map { it.copy(online = false) }
        .groupBy(::offlineDeviceIdentity)
        .mapNotNull { (_, devices) -> devices.maxByOrNull { offlineDeviceSortKey(it) } }
        .sortedByDescending { offlineDeviceSortKey(it) }
}

fun offlineNow(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

private fun offlineDeviceSortKey(device: DeviceItem): String = sequenceOf(
    device.offlineAt,
    device.lastSeenAt,
    device.onlineSince
).map(::cleanApiText).firstOrNull(String::isNotBlank).orEmpty()
