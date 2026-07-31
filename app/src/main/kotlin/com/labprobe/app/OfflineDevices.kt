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

/**
 * The router's durable archive may lag one persistence cycle behind a device
 * transition event. The newest transition therefore corrects an old card.
 */
fun reconcileOfflineDevicesWithEvents(
    archived: List<DeviceItem>,
    events: List<EventItem>,
    online: List<DeviceItem>
): List<DeviceItem> {
    val transitions = normalizeDeviceEvents(events)
        .asSequence()
        .filter { it.type == "device_online" || it.type == "device_offline" }
        .sortedByDescending(::eventTransitionMillis)
        .fold(mutableListOf<EventItem>()) { latest, event ->
            if (latest.none { sameEventDevice(it, event) }) latest += event
            latest
        }
    if (transitions.isEmpty()) return archived

    val reconciled = archived.mapNotNull { device ->
        val latest = transitions.firstOrNull { eventMatchesDevice(it, device) } ?: return@mapNotNull device
        if (eventTransitionMillis(latest) <= deviceStateMillis(device)) return@mapNotNull device
        when (latest.type) {
            "device_online" -> null
            "device_offline" -> mergeOfflineEvent(device, latest)
            else -> device
        }
    }.toMutableList()

    // Retain an event-only offline device until the Hub archive catches up.
    transitions.filter { it.type == "device_offline" }.forEach { event ->
        if (reconciled.any { eventMatchesDevice(event, it) }) return@forEach
        if (online.any { eventMatchesDevice(event, it) }) return@forEach
        reconciled += offlineDeviceFromEvent(event)
    }
    return reconciled
}

private fun eventTransitionMillis(event: EventItem): Long =
    parseEventMillis(event.offlineAt).orElse(parseEventMillis(event.time)) ?: 0L

private fun deviceStateMillis(device: DeviceItem): Long =
    parseEventMillis(device.offlineAt).orElse(parseEventMillis(device.lastSeenAt)) ?: 0L

private fun eventMatchesDevice(event: EventItem, device: DeviceItem): Boolean {
    val eventMac = cleanMac(event.mac)
    val deviceMac = cleanMac(device.mac)
    if (eventMac.isNotBlank() && deviceMac.isNotBlank()) return eventMac == deviceMac
    val eventIdentity = offlineDeviceIdentity(offlineDeviceFromEvent(event))
    val deviceIdentity = offlineDeviceIdentity(device)
    return eventIdentity.startsWith("name:") && eventIdentity == deviceIdentity
}

private fun sameEventDevice(left: EventItem, right: EventItem): Boolean =
    eventMatchesDevice(left, offlineDeviceFromEvent(right))

private fun mergeOfflineEvent(device: DeviceItem, event: EventItem): DeviceItem {
    val stamp = event.offlineAt.ifBlank { event.time }
    return device.copy(
        online = false,
        ip = event.ip.ifBlank { device.ip },
        ssid = event.ssid.ifBlank { device.ssid },
        band = event.band.ifBlank { device.band },
        rssi = event.rssi.ifBlank { device.rssi },
        rxrate = event.rxrate.ifBlank { device.rxrate },
        onlineSince = event.onlineSince.ifBlank { device.onlineSince },
        offlineAt = stamp.ifBlank { device.offlineAt },
        onlineDurationText = event.onlineDurationText.ifBlank { device.onlineDurationText },
        lastSeenAt = stamp.ifBlank { device.lastSeenAt },
        manufacture = event.manufacture.ifBlank { device.manufacture },
        devType = event.devType.ifBlank { device.devType },
        osType = event.osType.ifBlank { device.osType },
        hostName = event.hostName.ifBlank { device.hostName },
        remark = event.remark.ifBlank { device.remark },
        manualType = event.manualType.ifBlank { device.manualType }
    )
}

private fun offlineDeviceFromEvent(event: EventItem): DeviceItem = DeviceItem(
    name = event.name.ifBlank { event.mac }.ifBlank { "未知设备" },
    mac = cleanMac(event.mac),
    online = false,
    ip = event.ip,
    ssid = event.ssid,
    band = event.band,
    rssi = event.rssi,
    rxrate = event.rxrate,
    onlineSince = event.onlineSince,
    offlineAt = event.offlineAt.ifBlank { event.time },
    onlineDurationText = event.onlineDurationText,
    lastSeenAt = event.offlineAt.ifBlank { event.time },
    manufacture = event.manufacture,
    devType = event.devType,
    osType = event.osType,
    hostName = event.hostName,
    remark = event.remark,
    manualType = event.manualType
)

private fun offlineDeviceSortKey(device: DeviceItem): String = sequenceOf(
    device.offlineAt,
    device.lastSeenAt,
    device.onlineSince
).map(::cleanApiText).firstOrNull(String::isNotBlank).orEmpty()
