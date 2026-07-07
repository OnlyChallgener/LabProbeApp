package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class WolDeviceConfig(
    val id: String,
    val remark: String,
    val mac: String,
    val typeId: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class WolDeviceRuntime(
    val config: WolDeviceConfig,
    val device: DeviceItem?,
    val online: Boolean,
    val ip: String,
    val ipv6: String,
    val lastSeen: String,
    val profile: DeviceVisualProfile
)

fun parseWolDevices(json: String): List<WolDeviceConfig> {
    if (json.isBlank()) return emptyList()
    val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val mac = cleanMac(o.optString("mac"))
        if (!isValidMac(mac)) return@mapNotNull null
        val typeId = normalizeDeviceTypeToken(o.optString("typeId").ifBlank { o.optString("type") }).ifBlank { "desktop" }
        WolDeviceConfig(
            id = o.optString("id").ifBlank { mac },
            remark = cleanApiText(o.optString("remark").ifBlank { o.optString("name") }),
            mac = mac,
            typeId = typeId,
            enabled = o.optBoolean("enabled", true),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }.distinctBy { it.mac.lowercase(Locale.getDefault()) }
}

fun wolDevicesToJson(list: List<WolDeviceConfig>): String {
    val arr = JSONArray()
    list.distinctBy { it.mac.lowercase(Locale.getDefault()) }.forEach { d ->
        arr.put(JSONObject()
            .put("id", d.id.ifBlank { d.mac })
            .put("remark", d.remark)
            .put("mac", d.mac)
            .put("typeId", d.typeId)
            .put("enabled", d.enabled)
            .put("createdAt", d.createdAt)
            .put("updatedAt", d.updatedAt)
        )
    }
    return arr.toString()
}

fun buildWolRuntimes(configs: List<WolDeviceConfig>, sharedDevices: List<DeviceItem>): List<WolDeviceRuntime> {
    val byMac = sharedDevices.associateBy { it.mac.lowercase(Locale.getDefault()) }
    return configs.map { cfg ->
        val d = byMac[cfg.mac.lowercase(Locale.getDefault())]
        val type = deviceTypeById(cfg.typeId)
        val virtual = DeviceItem(
            name = cfg.remark.ifBlank { d?.name ?: cfg.mac },
            mac = cfg.mac,
            online = d?.online ?: false,
            ip = d?.ip ?: "",
            ssid = d?.ssid ?: "",
            band = d?.band ?: "",
            rssi = d?.rssi ?: "",
            rxrate = d?.rxrate ?: "",
            onlineSince = d?.onlineSince ?: "",
            offlineAt = d?.offlineAt ?: "",
            onlineDurationText = d?.onlineDurationText ?: "",
            lastSeenAt = d?.lastSeenAt ?: "",
            ipv6 = d?.ipv6 ?: emptyList(),
            manufacture = d?.manufacture ?: "",
            devType = d?.devType ?: "",
            osType = d?.osType ?: "",
            hostName = d?.hostName ?: "",
            wolMode = "on",
            connectType = d?.connectType ?: "",
            remark = cfg.remark,
            manualType = cfg.typeId,
            wolEnabledOverride = cfg.enabled
        )
        val profile = DeviceVisualProfile(
            type = type.id,
            label = type.label,
            icon = deviceTypeIcon(type.iconKey),
            accent = type.accent,
            wolCandidate = cfg.enabled,
            confidence = 99,
            note = if (cfg.enabled) "WOL 手动设备" else "WOL 已关闭"
        )
        WolDeviceRuntime(
            config = cfg,
            device = d,
            online = d?.online ?: false,
            ip = d?.ip.orEmpty(),
            ipv6 = bestIpv6ForDisplay(d?.ipv6.orEmpty()),
            lastSeen = d?.lastSeenAt?.takeIf { it.isNotBlank() } ?: d?.offlineAt.orEmpty(),
            profile = profile
        )
    }
}

fun wolCandidatesFromDevices(devices: List<DeviceItem>, existing: List<WolDeviceConfig>): List<WolDeviceRuntime> {
    val exists = existing.map { it.mac.lowercase(Locale.getDefault()) }.toSet()
    val candidates = devices
        .filter { it.mac.isNotBlank() && it.mac.lowercase(Locale.getDefault()) !in exists }
        .mapNotNull { d ->
            val p = inferDeviceProfile(d)
            if (!p.wolCandidate) return@mapNotNull null
            val cfg = WolDeviceConfig(
                id = d.mac,
                remark = d.name.ifBlank { d.mac },
                mac = d.mac,
                typeId = p.type,
                enabled = false
            )
            WolDeviceRuntime(cfg, d, d.online, d.ip, bestIpv6ForDisplay(d.ipv6), d.lastSeenAt.ifBlank { d.offlineAt }, p.copy(wolCandidate = false))
        }
    return candidates.take(8)
}
