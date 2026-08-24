package com.labprobe.app

import java.util.Locale

/**
 * Devices selected for a public port mapping or STUN endpoint must be a
 * reachable service host. Keep the existing device/address loading untouched;
 * this predicate only controls which rows are offered by the two pickers.
 */
private val UNSUITABLE_ENDPOINT_DEVICE_TYPES = setOf(
    // Personal/mobile clients and wearables.
    "phone", "iphone", "huawei_phone", "tablet", "watch", "child_watch",
    "smart_ring", "wireless_earbuds", "headphones",
    // Cameras, printers and router management pages remain valid targets.
    // TVs and other media clients generally do not expose a user-managed service.
    "tv", "smart_display", "tv_box", "set_top_box", "projector", "game_console",
    "speaker", "soundbar",
    // Smart-home and appliance categories.
    "lock", "sensor", "switch", "socket", "power_strip", "light", "ceiling_light",
    "living_room_light", "bedside_lamp", "desk_lamp", "floor_lamp", "light_strip",
    "curtain", "remote", "aircon_controller", "aircon_companion", "smart_panel",
    "reading_pen", "iot", "aircon", "fresh_air", "floor_aircon", "fridge", "washer",
    "water_heater", "gas_water_heater", "room_heater", "bath_heater", "heater", "hood",
    "smart_stove", "dishwasher", "air_fryer", "pressure_cooker", "blender", "humidifier",
    "dehumidifier", "air_purifier", "purifier", "rice", "cleaner", "vacuum", "toilet",
    "scale", "fan", "dryer", "water_dispenser", "microwave", "hair_dryer", "charger",
    // Peripherals without a useful inbound service surface.
    "usb_dock", "wireless_mouse", "wireless_keyboard"
)

private val UNSUITABLE_ENDPOINT_NAME_TOKENS = listOf(
    "手机", "平板", "手表", "手环", "儿童手表", "phone", "smartphone", "mobile", "android", "iphone", "ipad", "tablet",
    "电视", "电视盒", "机顶盒", "投影", "音箱", "音响", "speaker",
    "智能家居", "智能设备", "smart home", "smart device", "iot", "家居", "门锁", "门铃",
    "传感器", "插座", "开关", "灯", "窗帘", "空调", "冰箱", "洗衣机", "热水器", "扫地",
    "吸尘"
)

private val SERVICE_MANAGEMENT_DEVICE_TYPES = setOf(
    "camera", "doorbell", "printer", "router", "soft_router", "ap", "network_switch",
    "network_device", "ont"
)

private fun normalizedEndpointText(value: String): String =
    value.trim().lowercase(Locale.getDefault())

/**
 * Unknown devices without any human-readable identity are not actionable in a
 * mapping form. Named servers/NAS devices remain selectable even when their
 * vendor type is not recognised.
 */
internal fun isDeviceUsableForPublicEndpoint(device: DeviceItem): Boolean {
    val hasUsableName = listOf(device.remark, device.name, device.hostName)
        .any { it.trim().isNotBlank() }
    if (!hasUsableName) return false

    val inferredType = inferDeviceTypeRule(device).id
    if (inferredType in UNSUITABLE_ENDPOINT_DEVICE_TYPES) return false
    if (inferredType in SERVICE_MANAGEMENT_DEVICE_TYPES) return true

    // Manual classification is authoritative for filtering. Hub's devType is
    // also honoured when it explicitly names a known unsuitable category.
    val explicitTypes = listOf(device.manualType, device.devType)
        .map(::normalizeDeviceTypeToken)
        .filter { it.isNotBlank() }
    if (explicitTypes.any { it in UNSUITABLE_ENDPOINT_DEVICE_TYPES }) return false
    if (explicitTypes.any { it in SERVICE_MANAGEMENT_DEVICE_TYPES }) return true

    val identity = listOf(device.remark, device.name, device.hostName, device.osType, device.devType)
        .joinToString(" ", transform = ::normalizedEndpointText)
    return UNSUITABLE_ENDPOINT_NAME_TOKENS.none { identity.contains(it) }
}
