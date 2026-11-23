package com.labprobe.app

import java.util.Locale

private val MAC_REGEX = Regex("""^([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$""")

private fun isMacLikeText(value: String): Boolean {
    val clean = value.trim()
    if (MAC_REGEX.matches(clean)) return true
    val hexOnly = clean.replace(":", "").replace("-", "")
    return hexOnly.length == 12 && hexOnly.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

private val SUITABLE_ENDPOINT_DEVICE_TYPES = setOf(
    // Computers and Workstations
    "desktop", "laptop", "mac_mini", "mini_pc", "all_in_one", "server", "industrial", "linux_pc", "mac_pc",
    // NAS and Storage
    "nas",
    // Management, Gateway and Network Appliances
    "camera", "doorbell", "nvr", "ipc", "printer", "router", "soft_router", "ap",
    "network_switch", "netswitch", "network_device", "ont", "gateway", "pdu", "ip_kvm",
    "tv_box", "tvbox", "set_top_box"
)

private val MAPPING_MEDIA_DEVICE_TYPES = setOf("tv_box", "tvbox", "set_top_box")
private val MAPPING_ONLY_DEVICE_TYPES = MAPPING_MEDIA_DEVICE_TYPES + "switch"
private val MAPPING_MEDIA_NAME_TOKENS = listOf("电视盒", "机顶盒", "tv box", "tv_box", "tvbox", "set-top box", "set top box")

private val SUITABLE_NAME_TOKENS = listOf(
    // NAS
    "nas", "群晖", "synology", "qnap", "威联通", "飞牛", "fnos", "truenas", "unraid", "omv", "极空间", "绿联", "ugreen", "dh4300",
    // PC / Servers
    "pc", "电脑", "台式", "主机", "笔记本", "desktop", "laptop", "macbook", "imac", "nuc", "技嘉", "华硕", "asus", "gigabyte", "联想", "thinkpad", "dell", "hp", "server", "服务器", "pve", "proxmox", "esxi", "docker", "ubuntu", "debian", "centos", "linux", "workstation",
    // Routers / Network
    "router", "路由", "openwrt", "istoreos", "软路由", "旁路由", "switch", "交换机", "gateway", "网关", "ap",
    // Cameras / Printers
    "camera", "摄像", "监控", "ipc", "nvr", "tp-link", "tplink", "海康", "大华", "萤石", "ezviz", "dahua", "hikvision", "printer", "打印"
)

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
    "手机", "平板", "手表", "手环", "儿童手表", "phone", "smartphone", "mobile", "android", "iphone", "ipad", "tablet", "matepad", "galaxy tab",
    "sm-r", "sm-l", "galaxy watch", "apple watch", "kidswatch",
    "电视", "电视盒", "机顶盒", "投影", "音箱", "音响", "speaker", "soundbox", "soundbar", "homepod", "小爱", "小度", "天猫精灵",
    "智能家居", "智能设备", "smart home", "smart device", "iot", "家居", "门锁",
    "传感器", "插座", "开关", "灯", "窗帘", "空调", "冰箱", "洗衣机", "热水器", "扫地",
    "吸尘", "马桶", "净水", "风扇", "秤", "体脂", "耳机", "earbuds", "airpods"
)

private fun normalizedEndpointText(value: String): String =
    value.trim().lowercase(Locale.getDefault())

/**
 * Devices selected for a public port mapping or STUN endpoint must be a
 * reachable service host.
 */
internal fun isDeviceUsableForPublicEndpoint(
    device: DeviceItem,
    allowOfflineWithoutIpv4: Boolean = false,
    allowMediaTargets: Boolean = false,
): Boolean {
    val rawNames = listOf(device.remark, device.name, device.hostName).filter { it.trim().isNotBlank() }
    val meaningfulNames = rawNames.filterNot { isMacLikeText(it) }
    val hasMeaningfulName = meaningfulNames.isNotEmpty()

    // Port mapping may target a sleeping device by its stable IPv6/MAC even
    // when its IPv4 lease is absent from this snapshot.
    if (!device.online) {
        if (!hasMeaningfulName && !allowOfflineWithoutIpv4) return false
        if (device.ip.isBlank() && !allowOfflineWithoutIpv4) return false
    }

    val inferredType = inferDeviceTypeRule(device).id
    val identity = listOf(device.remark, device.name, device.hostName, device.osType, device.devType)
        .joinToString(" ", transform = ::normalizedEndpointText)
    val isMappingMediaTarget = allowMediaTargets && (
        inferredType in MAPPING_MEDIA_DEVICE_TYPES || MAPPING_MEDIA_NAME_TOKENS.any(identity::contains)
    )
    val isMappingOnlyTarget = allowMediaTargets && inferredType in MAPPING_ONLY_DEVICE_TYPES
    if (inferredType in UNSUITABLE_ENDPOINT_DEVICE_TYPES && !isMappingMediaTarget && !isMappingOnlyTarget) return false

    // Manual classification is authoritative for filtering. Hub's devType is
    // also honoured when it explicitly names a known unsuitable category.
    val explicitTypes = listOf(device.manualType, device.devType)
        .map(::normalizeDeviceTypeToken)
        .filter { it.isNotBlank() }
    if (explicitTypes.any { it in UNSUITABLE_ENDPOINT_DEVICE_TYPES && !(allowMediaTargets && it in MAPPING_ONLY_DEVICE_TYPES) }) return false

    if (UNSUITABLE_ENDPOINT_NAME_TOKENS.any { identity.contains(it) } && !isMappingMediaTarget) return false

    if (inferredType in SUITABLE_ENDPOINT_DEVICE_TYPES) return true
    if (explicitTypes.any { it in SUITABLE_ENDPOINT_DEVICE_TYPES }) return true
    if (isMappingMediaTarget || isMappingOnlyTarget) return true
    if (SUITABLE_NAME_TOKENS.any { identity.contains(it) }) return true

    // For online devices with a custom remark or recognized name, allow
    if (device.remark.isNotBlank()) return true
    if (device.online && hasMeaningfulName) return true

    return false
}

internal fun isDeviceUsableForPortMappingTarget(device: DeviceItem): Boolean =
    isDeviceUsableForPublicEndpoint(device, allowOfflineWithoutIpv4 = true, allowMediaTargets = true)
