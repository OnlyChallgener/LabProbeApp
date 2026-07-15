package com.labprobe.app

import java.util.Locale

private val DEVICE_TRAFFIC_VALUE = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(TIB|TB|T|GIB|GB|G|MIB|MB|M|KIB|KB|K|B)?")

internal fun parseDeviceTrafficBytes(raw: String): Long {
    val text = raw.trim().replace(",", "")
    if (text.isBlank()) return 0L
    val match = DEVICE_TRAFFIC_VALUE.find(text) ?: return 0L
    val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return 0L
    val multiplier = when (match.groupValues.getOrNull(2)?.uppercase(Locale.US).orEmpty()) {
        "T", "TB", "TIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
        "G", "GB", "GIB" -> 1024.0 * 1024.0 * 1024.0
        "M", "MB", "MIB" -> 1024.0 * 1024.0
        "K", "KB", "KIB" -> 1024.0
        else -> 1.0
    }
    return (value * multiplier).coerceIn(0.0, Long.MAX_VALUE.toDouble()).toLong()
}
