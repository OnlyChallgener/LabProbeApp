package com.labprobe.app

import java.util.Locale

fun inferDeviceProfile(d: DeviceItem): DeviceVisualProfile {
    // 只有 APP 本地手动类型才当作强制类型；Hub / 路由器返回的 devType 只做弱参考，避免把海尔热水器、美的空调误锁成“路由/AP”。
    val manualType = normalizeDeviceTypeToken(d.manualType)

    val rule = if (manualType.isNotBlank()) {
        deviceTypeById(manualType)
    } else {
        inferDeviceTypeRule(d)
    }

    val manualWol = d.wolEnabledOverride
    val recommendation = wolRecommendationForDevice(d, rule.id)
    val wol = manualWol ?: recommendation.recommended
    val confidence = when {
        d.manualType.isNotBlank() -> 98
        rule.id == "unknown" -> 52
        else -> rule.priority.coerceIn(55, 95)
    }
    val note = when {
        manualWol == true -> "已手动启用 WOL"
        manualWol == false -> "已手动关闭 WOL"
        else -> recommendation.reason
    }
    return DeviceVisualProfile(
        type = rule.id,
        label = rule.label,
        icon = deviceTypeIcon(rule.iconKey),
        accent = DEVICE_ICON_ACCENT,
        wolCandidate = wol,
        confidence = confidence,
        note = note,
        iconKey = rule.iconKey
    )
}

fun inferDeviceTypeRule(d: DeviceItem): DeviceTypeRule {
    val text = deviceFingerprintText(d)
    if (text.isBlank()) return deviceTypeById("unknown")

    // 名称/备注是用户和路由器给出的“设备身份”，优先级高于 SSID、AP 名称、MAC 厂商和 Hub 旧分类。
    // 典型修复：设备名 iPad 不能因为连接到 @Ruijie-s8067 就被误判成路由/AP。
    strongNameType(d)?.let { return it }

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

    // 品牌太泛时防误判：只有品牌命中但没有类型词时，不把“美的/海尔/小米/华为”等泛品牌硬判成具体设备。
    val hasTypeKeyword = best.keywords.any { containsToken(text, it) }
    val onlyBrand = !hasTypeKeyword && best.brands.any { containsToken(text, it) }
    if (onlyBrand && best.id in listOf(
            "router", "ont", "desktop", "laptop", "mini_pc", "aircon", "fridge", "washer", "water_heater", "tv", "tv_box", "projector"
        )
    ) {
        return deviceTypeById("unknown")
    }
    return best
}

private fun strongNameType(d: DeviceItem): DeviceTypeRule? {
    val nameText = listOf(d.remark, d.name, d.hostName)
        .joinToString(" ")
        .lowercase(Locale.getDefault())

    if (nameText.isBlank()) return null
    return when {
        listOf("儿童手表", "电话手表", "小天才", "米兔", "kidswatch").any { nameText.contains(it) } -> deviceTypeById("child_watch")
        listOf("galaxy watch", "apple watch", "watch", "手表", "手环", "band").any { nameText.contains(it) } || Regex("""\bsm-[rl]\d+""").containsMatchIn(nameText) -> deviceTypeById("watch")
        listOf("camera", "ipc", "tp-link", "tplink", "海康", "大华", "萤石", "ezviz", "dahua", "hikvision", "监控", "摄像").any { nameText.contains(it) } -> deviceTypeById("camera")
        listOf("printer", "打印机", "打印").any { nameText.contains(it) } -> deviceTypeById("printer")
        listOf("smart ring", "galaxy ring").any { nameText.contains(it) } -> deviceTypeById("smart_ring")
        listOf("ipad", "matepad", "galaxy tab", "xiaoxin pad", "redmi pad", "mi pad", "pad", "平板").any { nameText.contains(it) } -> deviceTypeById("tablet")
        listOf("iphone", "苹果手机").any { nameText.contains(it) } -> deviceTypeById("iphone")
        listOf("华为手机", "huawei phone", "mate60", "mate 60", "mate70", "mate 70", "pura", "nova").any { nameText.contains(it) } -> deviceTypeById("huawei_phone")
        listOf("手机", "pixel", "oneplus", "oppo", "vivo", "iqoo", "realme", "meizu", "nubia").any { nameText.contains(it) } -> deviceTypeById("phone")
        listOf("macbook", "matebook", "magicbook", "redmibook", "laptop", "notebook", "笔记本").any { nameText.contains(it) } -> deviceTypeById("laptop")
        listOf("mac mini", "macmini", "mac-mini").any { nameText.contains(it) } -> deviceTypeById("mac_mini")
        listOf("mini pc", "minipc", "nuc", "迷你主机", "小主机").any { nameText.contains(it) } -> deviceTypeById("mini_pc")
        listOf("all in one", "all-in-one", "aio", "imac", "studio display", "一体机", "一体电脑").any { nameText.contains(it) } -> deviceTypeById("all_in_one")
        listOf("usb dock", "usb-c dock", "type-c dock", "docking station", "usb hub", "thunderbolt dock").any { nameText.contains(it) } -> deviceTypeById("usb_dock")
        listOf("wireless mouse", "bluetooth mouse", "magic mouse").any { nameText.contains(it) } -> deviceTypeById("wireless_mouse")
        listOf("wireless keyboard", "bluetooth keyboard", "magic keyboard").any { nameText.contains(it) } -> deviceTypeById("wireless_keyboard")
        listOf("desktop", "台式", "台式机").any { nameText.contains(it) } -> deviceTypeById("desktop")
        listOf("server", "服务器", "proxmox", "esxi").any { nameText.contains(it) } -> deviceTypeById("server")
        listOf("industrial pc", "工控机").any { nameText.contains(it) } -> deviceTypeById("industrial")
        listOf("nas", "群晖", "威联通", "极空间", "飞牛").any { nameText.contains(it) } || ugreenNasModelTokens.any { nameText.contains(it) } -> deviceTypeById("nas")
        listOf("soundbar", "sound bar", "回音壁", "条形音箱").any { nameText.contains(it) } -> deviceTypeById("soundbar")
        listOf("airpods", "earbuds", "wireless earbuds", "tws", "无线耳机", "蓝牙耳机").any { nameText.contains(it) } -> deviceTypeById("wireless_earbuds")
        listOf("headphones", "headset", "头戴耳机", "头戴式耳机", "耳麦").any { nameText.contains(it) } -> deviceTypeById("headphones")
        listOf("soundbox", "miaisoundbox", "小爱", "天猫精灵", "音箱", "音响", "speaker").any { nameText.contains(it) } -> deviceTypeById("speaker")
        listOf("客厅灯", "living room light", "长方形吸顶灯", "矩形吸顶灯", "客厅吸顶灯").any { nameText.contains(it) } -> deviceTypeById("living_room_light")
        listOf("床头灯", "小夜灯", "bedside lamp", "night lamp", "night light").any { nameText.contains(it) } -> deviceTypeById("bedside_lamp")
        listOf("灯带", "light strip", "led strip").any { nameText.contains(it) } -> deviceTypeById("light_strip")
        listOf("新风", "fresh air").any { nameText.contains(it) } -> deviceTypeById("fresh_air")
        listOf("破壁机", "blender").any { nameText.contains(it) } -> deviceTypeById("blender")
        listOf("吸尘", "vacuum").any { nameText.contains(it) } -> deviceTypeById("vacuum")
        listOf("智能马桶", "马桶", "toilet").any { nameText.contains(it) } -> deviceTypeById("toilet")
        listOf("steam oven", "combi oven", "steam grill", "蒸烤箱", "蒸箱", "烤箱").any { nameText.contains(it) } -> deviceTypeById("steam_oven")
        listOf("智能灶", "智能燃气灶", "燃气灶", "灶具", "smart stove", "stove", "cooker").any { nameText.contains(it) } -> deviceTypeById("smart_stove")
        listOf("燃气热水器", "gas water heater", "燃热", "天然气热水器").any { nameText.contains(it) } -> deviceTypeById("gas_water_heater")
        listOf("热水器", "water heater").any { nameText.contains(it) } -> deviceTypeById("water_heater")
        listOf("空调伴侣", "aircon companion", "ac companion").any { nameText.contains(it) } -> deviceTypeById("aircon_companion")
        listOf("空调", "aircon", "air conditioner").any { nameText.contains(it) } -> deviceTypeById("aircon")
        listOf("power strip", "smart power strip", "powerstrip").any { nameText.contains(it) } -> deviceTypeById("power_strip")
        listOf("desktop charger", "charging station", "gan charger", "multiport charger").any { nameText.contains(it) } -> deviceTypeById("desktop_charger")
        listOf("软路由", "openwrt", "istoreos", "pfsense", "opnsense").any { nameText.contains(it) } -> deviceTypeById("soft_router")
        listOf("路由器", "router", "be72", "rg-", "reyee", "ruijie", "unifi").any { nameText.contains(it) } -> deviceTypeById("router")
        else -> null
    }
}

private fun scoreDeviceRule(text: String, rule: DeviceTypeRule): Int {
    var score = 0
    for (kw in rule.keywords) {
        if (containsToken(text, kw)) score += when {
            kw.length >= 4 || kw.any { it.code > 127 } -> 22
            else -> 14
        }
    }
    for (brand in rule.brands) {
        if (containsToken(text, brand)) score += 7
    }
    for (alias in rule.aliases) {
        if (containsToken(text, alias)) score += 14
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
    d.remark,
    d.name,
    d.hostName,
    d.manualType,
    d.osType,
    d.manufacture,
    // SSID / 频段 / AP 名称 / connectType 属于“连接信息”，不是设备身份。
    // 不能参与类型识别，否则 iPad 连接 @Ruijie-s8067 会被误识别为路由/AP。
    // devType 也经常来自 Hub 旧规则或路由器弱分类，仅作为展示回填，不参与自动识别。
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

fun bestIpv6ForDisplay(v6: List<String>): String = pickBestIpv6(v6).best.orEmpty()

fun formatManufacturer(raw: String): String {
    val trimmed = cleanApiText(raw).trim()
    if (trimmed.isBlank() || trimmed == "--") return ""
    val lower = trimmed.lowercase(Locale.ROOT)
    return when (lower) {
        "huawei", "华为" -> "Huawei"
        "xiaomi", "小米" -> "Xiaomi"
        "apple", "苹果" -> "Apple"
        "samsung", "三星" -> "Samsung"
        "haier", "海尔" -> "Haier"
        "midea", "美的" -> "Midea"
        "honor", "荣耀" -> "Honor"
        "oppo" -> "OPPO"
        "vivo" -> "vivo"
        "oneplus", "一加" -> "OnePlus"
        "meizu", "魅族" -> "Meizu"
        "tp-link", "tplink", "tp link" -> "TP-Link"
        "ruijie", "锐捷" -> "Ruijie"
        "h3c", "华三" -> "H3C"
        "zte", "中兴" -> "ZTE"
        "sony", "索尼" -> "Sony"
        "lenovo", "联想" -> "Lenovo"
        "dell", "戴尔" -> "Dell"
        "hp", "惠普" -> "HP"
        "asus", "华硕" -> "ASUS"
        "intel", "英特尔" -> "Intel"
        "realtek", "瑞昱" -> "Realtek"
        "espressif", "乐鑫" -> "Espressif"
        "google", "谷歌" -> "Google"
        "amazon", "亚马逊" -> "Amazon"
        "microsoft", "微软" -> "Microsoft"
        "nintendo", "任天堂" -> "Nintendo"
        "hisense", "海信" -> "Hisense"
        "tcl" -> "TCL"
        "dji", "大疆" -> "DJI"
        "synology", "群晖" -> "Synology"
        "qnap", "威联通" -> "QNAP"
        "ugreen", "绿联" -> "UGREEN"
        "fnos", "飞牛" -> "FnOS"
        "360" -> "360"
        "xtc", "小天才" -> "小天才"
        else -> {
            trimmed.split(" ").joinToString(" ") { word ->
                if (word.contains("-")) {
                    word.split("-").joinToString("-") { sub ->
                        sub.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    }
                } else {
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
            }
        }
    }
}

fun resolveDeviceManufacturer(d: DeviceItem): String {
    val formatted = formatManufacturer(d.manufacture)
    if (formatted.isNotBlank()) return formatted

    val nameText = listOf(d.remark, d.name, d.hostName)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if (nameText.isBlank()) return ""

    return when {
        Regex("""\bsm-[a-z0-9]{3,6}\b""").containsMatchIn(nameText) ||
            listOf("samsung", "galaxy", "三星").any { nameText.contains(it) } -> "Samsung"
        listOf("iphone", "ipad", "macbook", "imac", "mac mini", "apple watch", "airpods", "homepod", "apple").any { nameText.contains(it) } -> "Apple"
        listOf("huawei", "mate60", "mate70", "mate50", "mate40", "mate30", "mate20", "matepad", "matebook", "pura", "nova", "华为").any { nameText.contains(it) } ||
            Regex("""\bhuawei[-_]""").containsMatchIn(nameText) -> "Huawei"
        listOf("redmi", "红米").any { nameText.contains(it) } -> "Redmi"
        listOf("xiaomi", "小米", "mi pad", "poco", "miaisoundbox").any { nameText.contains(it) } -> "Xiaomi"
        listOf("honor", "magicbook", "magicpad", "荣耀").any { nameText.contains(it) } -> "Honor"
        listOf("360-kidswatch", "360-", "360儿童卫士").any { nameText.contains(it) } -> "360"
        listOf("小天才", "xtc").any { nameText.contains(it) } -> "小天才"
        listOf("pixel", "google").any { nameText.contains(it) } -> "Google"
        listOf("playstation", "ps5", "ps4", "sony", "索尼").any { nameText.contains(it) } -> "Sony"
        listOf("nintendo", "switch", "任天堂").any { nameText.contains(it) } -> "Nintendo"
        listOf("haier", "海尔").any { nameText.contains(it) } -> "Haier"
        listOf("midea", "美的").any { nameText.contains(it) } -> "Midea"
        listOf("hisense", "海信").any { nameText.contains(it) } -> "Hisense"
        listOf("tcl").any { nameText.contains(it) } -> "TCL"
        listOf("oppo").any { nameText.contains(it) } -> "OPPO"
        listOf("vivo", "iqoo").any { nameText.contains(it) } -> "vivo"
        listOf("oneplus", "一加").any { nameText.contains(it) } -> "OnePlus"
        listOf("meizu", "魅族").any { nameText.contains(it) } -> "Meizu"
        listOf("dell", "戴尔").any { nameText.contains(it) } -> "Dell"
        listOf("lenovo", "thinkpad", "xiaoxin", "联想").any { nameText.contains(it) } -> "Lenovo"
        listOf("asus", "rog", "华硕").any { nameText.contains(it) } -> "ASUS"
        listOf("synology", "群晖").any { nameText.contains(it) } -> "Synology"
        listOf("qnap", "威联通").any { nameText.contains(it) } -> "QNAP"
        listOf("ugreen", "绿联").any { nameText.contains(it) } -> "UGREEN"
        listOf("tp-link", "tplink").any { nameText.contains(it) } -> "TP-Link"
        listOf("ruijie", "reyee", "锐捷").any { nameText.contains(it) } -> "Ruijie"
        else -> ""
    }
}
