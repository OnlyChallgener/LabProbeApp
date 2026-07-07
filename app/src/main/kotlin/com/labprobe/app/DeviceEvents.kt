package com.labprobe.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val DEVICE_EVENT_OFFLINE_COOLDOWN_MS = 5 * 60 * 1000L

fun mergeDeviceCache(old: List<DeviceItem>, fresh: List<DeviceItem>): List<DeviceItem> {
    val oldByMac = old.associateBy { it.mac.lowercase(Locale.getDefault()) }
    val freshKeys = fresh.map { it.mac.lowercase(Locale.getDefault()) }.toSet()
    val merged = fresh.map { n ->
        val o = oldByMac[n.mac.lowercase(Locale.getDefault())]
        if (!n.online && o != null) {
            n.copy(
                ip = n.ip.ifBlank { o.ip },
                ssid = n.ssid.ifBlank { o.ssid },
                band = n.band.ifBlank { o.band },
                rssi = n.rssi.ifBlank { o.rssi },
                rxrate = n.rxrate.ifBlank { o.rxrate },
                onlineSince = n.onlineSince.ifBlank { o.onlineSince },
                offlineAt = n.offlineAt.ifBlank { o.offlineAt },
                onlineDurationText = n.onlineDurationText.ifBlank { o.onlineDurationText },
                lastSeenAt = n.lastSeenAt.ifBlank { o.lastSeenAt },
                ipv6 = if (n.ipv6.isNotEmpty()) n.ipv6 else o.ipv6,
                manufacture = n.manufacture.ifBlank { o.manufacture },
                devType = n.devType.ifBlank { o.devType },
                osType = n.osType.ifBlank { o.osType },
                hostName = n.hostName.ifBlank { o.hostName },
                wolMode = n.wolMode.ifBlank { o.wolMode },
                connectType = n.connectType.ifBlank { o.connectType },
                remark = n.remark.ifBlank { o.remark },
                manualType = n.manualType.ifBlank { o.manualType },
                wolEnabledOverride = n.wolEnabledOverride ?: o.wolEnabledOverride
            )
        } else n
    }.toMutableList()
    old.filter { !it.online && it.mac.lowercase(Locale.getDefault()) !in freshKeys }.forEach { merged += it }
    return merged
}

fun normalizeDeviceEvents(raw: List<EventItem>): List<EventItem> {
    if (raw.isEmpty()) return raw
    val chronological = raw.sortedWith(compareBy<EventItem> { parseEventMillis(it.time) ?: Long.MAX_VALUE }.thenBy { it.id })
    val stateByKey = mutableMapOf<String, String>()
    val onlineAtByKey = mutableMapOf<String, Long>()
    val lastOfflineAtByKey = mutableMapOf<String, Long>()
    val kept = mutableListOf<EventItem>()

    chronological.forEach { event ->
        val key = eventDeviceKey(event)
        if (key.isBlank()) {
            kept += event
            return@forEach
        }
        val type = event.type
        if (type != "device_online" && type != "device_offline") {
            kept += event
            return@forEach
        }

        val at = parseEventMillis(event.time)
        val previousState = stateByKey[key]
        if (type == "device_online") {
            if (previousState == "online") return@forEach
            stateByKey[key] = "online"
            at?.let { onlineAtByKey[key] = it }
            kept += event
            return@forEach
        }

        val fixedDuration = bestOfflineDurationText(event, onlineAtByKey[key], at)
        val durationSec = parseDurationSeconds(fixedDuration.ifBlank { event.onlineDurationText })
        if (durationSec != null && durationSec <= 0L) return@forEach

        val lastOffline = lastOfflineAtByKey[key]
        val isDuplicateState = previousState == "offline"
        val isCooldownDuplicate = at != null && lastOffline != null && at - lastOffline in 0..DEVICE_EVENT_OFFLINE_COOLDOWN_MS
        if (isDuplicateState || isCooldownDuplicate) return@forEach

        kept += event.copy(onlineDurationText = fixedDuration.ifBlank { event.onlineDurationText })
        stateByKey[key] = "offline"
        if (at != null) lastOfflineAtByKey[key] = at
        onlineAtByKey.remove(key)
    }
    return kept.sortedWith(compareByDescending<EventItem> { parseEventMillis(it.time) ?: 0L }.thenByDescending { it.id })
}

fun eventDeviceKey(e: EventItem): String {
    val mac = e.mac.trim().lowercase(Locale.getDefault())
    if (mac.isNotBlank() && mac != "null" && mac != "-") return "mac:$mac"
    val name = e.name.ifBlank { e.title.removeSuffix(" 上线").removeSuffix(" 离线") }.trim().lowercase(Locale.getDefault())
    val ip = e.ip.trim().lowercase(Locale.getDefault())
    return when {
        name.isNotBlank() -> "name:$name"
        ip.isNotBlank() -> "ip:$ip"
        else -> ""
    }
}

fun bestOfflineDurationText(e: EventItem, trackedOnlineAt: Long?, offlineAt: Long?): String {
    val end = parseEventMillis(e.offlineAt).orElse(offlineAt)
    val start = parseEventMillis(e.onlineSince).orElse(trackedOnlineAt)
    if (start != null && end != null && end >= start) return formatDurationMs(end - start)
    return formatDurationText(e.onlineDurationText)
}

fun Long?.orElse(other: Long?): Long? = this ?: other

fun parseDurationSeconds(raw: String): Long? {
    val s = raw.trim()
    if (s.isBlank()) return null
    var total = 0L
    Regex("(\\d+)天").find(s)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it * 86400L }
    Regex("(\\d+)小时").find(s)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it * 3600L }
    Regex("(\\d+)分").find(s)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it * 60L }
    Regex("(\\d+)秒").find(s)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it }
    return if (total > 0L || s.contains("0秒")) total else null
}

fun parseEventMillis(raw: String): Long? {
    val s = raw.trim()
    if (s.isBlank() || s == "-") return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "MM-dd HH:mm:ss",
        "HH:mm:ss"
    )
    for (pattern in patterns) {
        val parsed = runCatching { SimpleDateFormat(pattern, Locale.CHINA).parse(s) }.getOrNull() ?: continue
        val cal = Calendar.getInstance(Locale.CHINA)
        cal.time = parsed
        if (pattern == "MM-dd HH:mm:ss") {
            cal.set(Calendar.YEAR, Calendar.getInstance(Locale.CHINA).get(Calendar.YEAR))
        } else if (pattern == "HH:mm:ss") {
            val now = Calendar.getInstance(Locale.CHINA)
            cal.set(Calendar.YEAR, now.get(Calendar.YEAR))
            cal.set(Calendar.MONTH, now.get(Calendar.MONTH))
            cal.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
        }
        return cal.timeInMillis
    }
    return null
}

fun formatDurationMs(msRaw: Long): String {
    val totalSec = (msRaw / 1000L).coerceAtLeast(0L)
    val days = totalSec / 86400L
    val hours = (totalSec % 86400L) / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return buildString {
        if (days > 0) append(days).append("天")
        if (hours > 0) append(hours).append("小时")
        if (minutes > 0 || days > 0 || hours > 0) append(minutes).append("分")
        append(seconds).append("秒")
    }
}
