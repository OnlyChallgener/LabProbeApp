package com.labprobe.app

import java.util.Locale

fun inferDeviceProfile(d: DeviceItem): DeviceVisualProfile {
    val manualType = normalizeDeviceTypeToken(d.manualType)
        .ifBlank { normalizeDeviceTypeToken(d.devType) }
        .ifBlank { normalizeDeviceTypeToken(d.wolMode) }

    val rule = if (manualType.isNotBlank()) {
        deviceTypeById(manualType)
    } else {
        inferDeviceTypeRule(d)
    }

    val manualWol = d.wolEnabledOverride
    val wol = manualWol ?: rule.wolDefault
    val confidence = when {
        d.manualType.isNotBlank() -> 98
        manualType.isNotBlank() -> 94
        rule.id == "unknown" -> 52
        else -> rule.priority.coerceIn(55, 95)
    }
    val note = when {
        manualWol == true -> "已手动启用 WOL"
        manualWol == false -> "已手动关闭 WOL"
        rule.wolDefault -> "${rule.label} 默认作为 WOL 候选"
        else -> "${rule.label} 默认不显示 WOL"
    }
    return DeviceVisualProfile(
        type = rule.id,
        label = rule.label,
        icon = deviceTypeIcon(rule.iconKey),
        accent = rule.accent,
        wolCandidate = wol,
        confidence = confidence,
        note = note
    )
}

fun inferDeviceTypeRule(d: DeviceItem): DeviceTypeRule {
    val text = deviceFingerprintText(d)
    if (text.isBlank()) return deviceTypeById("unknown")

    var best = deviceTypeById("unknown")
    var bestScore = 0
    for (rule in DEVICE_TYPE_RULES) {
        if (rule.id == "unknown") continue
        val score = scoreDeviceRule(text, rule)
        if (score > bestScore || (score == bestScore && rule.priority > best.priority)) {
            best = rule
            bestScore = score
        }
    }
    if (bestScore <= 0) return deviceTypeById("unknown")

    // 品牌太泛时防误判：只有品牌命中但没有类型词时，电脑/家电类降级为未知。
    val hasTypeKeyword = best.keywords.any { containsToken(text, it) }
    val onlyBrand = !hasTypeKeyword && best.brands.any { containsToken(text, it) }
    if (onlyBrand && best.id in listOf("router", "desktop", "laptop", "aircon", "fridge", "washer", "water_heater", "tv")) {
        return deviceTypeById("unknown")
    }
    return best
}

private fun scoreDeviceRule(text: String, rule: DeviceTypeRule): Int {
    var score = 0
    for (kw in rule.keywords) {
        if (containsToken(text, kw)) score += 18
    }
    for (brand in rule.brands) {
        if (containsToken(text, brand)) score += 8
    }
    for (alias in rule.aliases) {
        if (containsToken(text, alias)) score += 12
    }
    if (score > 0) score += rule.priority / 10
    return score
}

private fun containsToken(text: String, token: String): Boolean {
    val t = token.trim().lowercase(Locale.getDefault())
    if (t.isBlank()) return false
    return text.contains(t)
}

private fun deviceFingerprintText(d: DeviceItem): String = listOf(
    d.name,
    d.remark,
    d.manualType,
    d.mac,
    d.ip,
    d.ssid,
    d.band,
    d.connectType,
    d.hostName,
    d.devType,
    d.osType,
    d.manufacture,
    d.wolMode
).joinToString(" ") { it.lowercase(Locale.getDefault()) }

fun hasWifiInfo(d: DeviceItem): Boolean {
    val ssid = cleanApiText(d.ssid)
    val rssi = cleanApiText(d.rssi)
    val band = cleanApiText(d.band)
    val rate = cleanApiText(d.rxrate)
    val conn = cleanApiText(d.connectType).lowercase(Locale.getDefault())
    if (conn.contains("wired") || conn.contains("ethernet") || conn.contains("有线") || conn == "lan") return false
    return ssid.isNotBlank() || rssi.isNotBlank() || band.isNotBlank() || rate.isNotBlank() || conn.contains("wifi") || conn.contains("wireless") || conn.contains("无线")
}

fun connectionLabel(d: DeviceItem): String = if (hasWifiInfo(d)) "无线连接" else "有线设备"

fun bestIpv6ForDisplay(v6: List<String>): String = v6
    .map { it.substringBefore('/').trim() }
    .filter { it.contains(':') && !it.startsWith("fe80:", ignoreCase = true) }
    .distinct()
    .firstOrNull()
    .orEmpty()
