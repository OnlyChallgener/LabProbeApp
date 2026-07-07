package com.labprobe.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.TabletMac
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalLaundryService
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

data class DeviceTypeRule(
    val id: String,
    val label: String,
    val iconKey: String,
    val accent: Color,
    val wolDefault: Boolean = false,
    val priority: Int = 50,
    val keywords: List<String> = emptyList(),
    val brands: List<String> = emptyList(),
    val aliases: List<String> = emptyList()
)

fun deviceTypeIcon(iconKey: String): ImageVector = when (iconKey) {
    "router" -> Icons.Rounded.Router
    "ap" -> Icons.Rounded.Wifi
    "ont" -> Icons.Rounded.Router
    "nas" -> Icons.Rounded.Storage
    "desktop" -> Icons.Rounded.DesktopWindows
    "mini_pc" -> Icons.Rounded.Memory
    "laptop" -> Icons.Rounded.LaptopMac
    "phone" -> Icons.Rounded.PhoneAndroid
    "tablet" -> Icons.Rounded.TabletMac
    "watch" -> Icons.Rounded.Watch
    "tv" -> Icons.Rounded.Tv
    "tv_box" -> Icons.Rounded.Tv
    "projector" -> Icons.Rounded.Tv
    "speaker" -> Icons.Rounded.Speaker
    "camera" -> Icons.Rounded.Videocam
    "doorbell" -> Icons.Rounded.Videocam
    "lock" -> Icons.Rounded.Lock
    "switch" -> Icons.Rounded.Sensors
    "socket" -> Icons.Rounded.Power
    "light" -> Icons.Rounded.Lightbulb
    "curtain" -> Icons.Rounded.Sensors
    "aircon" -> Icons.Rounded.AcUnit
    "fridge" -> Icons.Rounded.Kitchen
    "washer" -> Icons.Rounded.LocalLaundryService
    "heater" -> Icons.Rounded.WaterDrop
    "hood" -> Icons.Rounded.Air
    "cooker" -> Icons.Rounded.Fastfood
    "dishwasher" -> Icons.Rounded.WaterDrop
    "purifier" -> Icons.Rounded.WaterDrop
    "rice" -> Icons.Rounded.Fastfood
    "cleaner" -> Icons.Rounded.SmartToy
    "toilet" -> Icons.Rounded.WaterDrop
    "scale" -> Icons.Rounded.Scale
    "printer" -> Icons.Rounded.Print
    "industrial" -> Icons.Rounded.Computer
    else -> Icons.Rounded.Devices
}

val DEVICE_TYPE_RULES: List<DeviceTypeRule> = listOf(
    DeviceTypeRule(
        id = "router", label = "路由/AP", iconKey = "router", accent = Color(0xFF06B6D4), priority = 92,
        keywords = listOf("router", "openwrt", "istoreos", "mesh", "gateway", "wireless router", "路由", "网关", "无线ap", "be72", "rg-", "reyee", "ruijie", "unifi", "ubnt", "ubiquiti", "hiwifi", "s8067"),
        brands = listOf("华为", "huawei", "中兴", "zte", "新华三", "h3c", "tp-link", "tplink", "普联", "水星", "mercury", "迅捷", "fast", "腾达", "tenda", "d-link", "dlink", "友讯", "网件", "netgear", "华硕", "asus", "小米", "xiaomi", "mi router", "红米", "redmi", "领势", "linksys", "360", "睿易", "reyee", "锐捷", "ruijie", "unifi", "ubnt", "ubiquiti", "极路由", "hiwifi"),
        aliases = listOf("路由器", "AP", "无线路由", "企业AP")
    ),
    DeviceTypeRule(
        id = "ont", label = "光猫", iconKey = "ont", accent = Color(0xFF0EA5E9), priority = 90,
        keywords = listOf("ont", "onu", "modem", "gpon", "epon", "光猫", "光纤猫"),
        brands = listOf("华为", "huawei", "中兴", "zte", "贝尔", "alcatel", "烽火", "fiberhome", "友华", "九联", "九洲", "兆能", "创维", "星网锐捷")
    ),
    DeviceTypeRule("nas", "NAS", "nas", Color(0xFF0EA5E9), wolDefault = true, priority = 96,
        keywords = listOf("nas", "storage", "truenas", "unraid", "fs6706", "私有云", "网络存储", "飞牛", "fnos", "dh2100+", "dh2600", "dh2300", "dh4300", "dh4300plus", "dx4600", "dx4600pro", "dxp2800", "dxp4800", "dxp4800plus", "dxp4800gt", "dxp480tplus", "dxp6800plus", "dxp6800pro", "dxp6800ultra", "dxp8800", "dxp8800plus", "dxp8800pro", "dxp8800ultra"),
        // 绿联是综合品牌，不能仅凭 UGREEN / 绿联判断 NAS；这里只用具体 NAS 型号判断。
        brands = listOf("群晖", "synology", "威联通", "qnap", "极空间", "zspace", "铁威马", "terramaster", "飞牛")),
    DeviceTypeRule("desktop", "台式电脑", "desktop", Color(0xFF2563EB), wolDefault = true, priority = 88,
        keywords = listOf("desktop", "pc", "workstation", "主机", "台式", "台式机", "windows", "win-", "win11", "deskt"),
        brands = listOf("华硕", "asus", "asustek", "联想", "lenovo", "戴尔", "dell", "惠普", "hp", "hewlett", "微星", "msi", "技嘉", "gigabyte", "七彩虹", "colorful", "神舟", "hasee", "机械革命", "mechrevo", "机械师", "machenike", "雷蛇", "razer")),
    DeviceTypeRule("mini_pc", "迷你主机", "mini_pc", Color(0xFF2563EB), wolDefault = true, priority = 89,
        keywords = listOf("mini pc", "minipc", "mini-pc", "nuc", "beelink", "minisforum", "mac mini", "macmini", "迷你主机", "小主机", "畅网", "倍控"),
        brands = listOf("零刻", "beelink", "铭凡", "minisforum", "英特尔", "intel", "华硕", "asus", "联想", "lenovo", "惠普", "hp", "戴尔", "dell", "华为", "huawei", "小米", "mi", "七彩虹", "colorful", "畅网", "倍控")),
    DeviceTypeRule("laptop", "笔记本电脑", "laptop", Color(0xFF3B82F6), wolDefault = false, priority = 85,
        keywords = listOf("laptop", "notebook", "macbook", "book", "笔记本", "matebook", "magicbook", "redmibook", "vaio"),
        brands = listOf("华为", "huawei", "荣耀", "honor", "小米", "mi", "xiaomi", "红米", "redmi", "苹果", "apple", "宏碁", "acer", "vaio", "三星", "samsung", "lg", "火影", "firebat")),
    DeviceTypeRule("phone", "手机", "phone", Color(0xFF22C55E), priority = 92,
        keywords = listOf("iphone", "android", "phone", "mobile", "mate", "pura", "reno", "find", "iqoo", "vivo", "oppo", "realme", "oneplus", "galaxy", "pixel", "手机", "nubia", "meizu"),
        brands = listOf("apple", "iphone", "huawei", "honor", "xiaomi", "redmi", "samsung", "oppo", "vivo", "iqoo", "realme", "oneplus", "魅族", "meizu", "努比亚", "nubia", "google pixel", "pixel")),
    DeviceTypeRule("tablet", "平板", "tablet", Color(0xFF64748B), priority = 93,
        keywords = listOf("ipad", "ipad pro", "ipad air", "ipad mini", "tablet", "pad", "matepad", "honor pad", "xiaoxin pad", "redmi pad", "mi pad", "galaxy tab", "tab ", "平板"),
        brands = listOf("apple", "苹果", "huawei", "华为", "honor", "荣耀", "xiaomi", "小米", "redmi", "红米", "samsung", "三星", "lenovo", "联想")),
    DeviceTypeRule("watch", "智能手表", "watch", Color(0xFF8B5CF6), priority = 84,
        keywords = listOf("watch", "wear", "band", "手表", "手环", "amazfit", "garmin", "suunto", "coros", "polar", "小天才", "米兔"),
        brands = listOf("amazfit", "华米", "huawei", "华为", "小米", "oppo", "vivo", "iqoo", "garmin", "佳明", "suunto", "颂拓", "coros", "高驰", "polar", "博能", "小天才", "360", "米兔")),

    DeviceTypeRule("tv", "电视/智慧屏", "tv", Color(0xFF7C3AED), priority = 78,
        keywords = listOf("tv", "television", "智慧屏", "电视", "mitv", "hisense-tv"), brands = listOf("海信", "hisense", "tcl", "创维", "skyworth", "小米", "xiaomi", "索尼", "sony", "华为", "huawei")),
    DeviceTypeRule("tv_box", "电视盒子", "tv_box", Color(0xFF7C3AED), priority = 79,
        keywords = listOf("tv box", "tvbox", "box", "电视盒子", "机顶盒", "apple tv", "roku", "fire tv", "天猫魔盒", "小米盒子", "魔百和", "天翼高清", "沃家电视"),
        brands = listOf("海美迪", "亿格瑞", "芝杜", "开博尔", "apple tv", "roku", "fire tv", "当贝盒子", "腾讯极光", "爱奇艺电视果", "天猫魔盒", "小米盒子", "魔百和", "天翼高清", "沃家电视")),
    DeviceTypeRule("projector", "投影仪", "projector", Color(0xFF8B5CF6), priority = 78,
        keywords = listOf("projector", "projection", "投影", "投影仪"), brands = listOf("极米", "xgimi", "当贝", "dangbei", "坚果", "jmgo", "爱普生", "epson", "索尼", "sony", "松下", "panasonic", "明基", "benq")),
    DeviceTypeRule("speaker", "智能音箱", "speaker", Color(0xFF14B8A6), priority = 78,
        keywords = listOf("speaker", "sound", "audio", "homepod", "小爱", "天猫精灵", "音箱", "音响", "xiaomi sound", "miaisoundbox"), brands = listOf("小爱", "天猫精灵", "华为", "索尼", "sony", "xiaomi", "mi", "bose", "jbl", "马歇尔", "marshall", "哈曼卡顿", "harman", "b&o", "bang olufsen")),
    DeviceTypeRule("camera", "摄像头", "camera", Color(0xFFEF4444), priority = 78,
        keywords = listOf("camera", "cam", "ipc", "nvr", "摄像", "摄像头", "ezviz"), brands = listOf("海康", "hikvision", "萤石", "ezviz", "小米", "360", "tp-link", "tplink", "大华", "dahua", "华为", "huawei")),
    DeviceTypeRule("doorbell", "门铃", "doorbell", Color(0xFFEF4444), priority = 75,
        keywords = listOf("doorbell", "门铃"), brands = listOf("小米", "萤石", "360")),
    DeviceTypeRule("lock", "智能门锁", "lock", Color(0xFF0F766E), priority = 75,
        keywords = listOf("lock", "door lock", "门锁"), brands = listOf("凯迪仕", "德施曼", "小米", "萤石")),
    DeviceTypeRule("sensor", "传感器", "switch", Color(0xFF10B981), priority = 66,
        keywords = listOf("sensor", "contact", "motion", "门窗传感器", "传感器"), brands = listOf("aqara", "小米", "欧瑞博", "orvibo")),
    DeviceTypeRule("switch", "智能开关", "switch", Color(0xFF10B981), priority = 66,
        keywords = listOf("switch", "开关"), brands = listOf("欧普", "小米", "aqara", "欧瑞博", "orvibo")),
    DeviceTypeRule("socket", "智能插座", "socket", Color(0xFF10B981), priority = 66,
        keywords = listOf("plug", "socket", "插座"), brands = listOf("小米", "公牛", "博联", "broadlink")),
    DeviceTypeRule("light", "智能灯", "light", Color(0xFF22C55E), priority = 72,
        keywords = listOf("light", "lamp", "bulb", "yeelight", "mijia light", "床头灯", "灯"), brands = listOf("小米", "yeelight", "aqara", "欧普", "opple")),
    DeviceTypeRule("curtain", "智能窗帘", "curtain", Color(0xFF22C55E), priority = 66,
        keywords = listOf("curtain", "窗帘"), brands = listOf("杜亚", "小米", "aqara")),

    DeviceTypeRule("aircon", "空调", "aircon", Color(0xFF06B6D4), priority = 82,
        keywords = listOf("aircon", "air conditioner", "空调", "暖通", "climate"), brands = listOf("格力", "gree", "美的", "midea", "海尔", "haier", "华凌", "tcl", "海信", "hisense", "colmo")),
    DeviceTypeRule("fridge", "冰箱", "fridge", Color(0xFF0EA5E9), priority = 80,
        keywords = listOf("fridge", "refrigerator", "冰箱"), brands = listOf("海尔", "haier", "美的", "midea", "容声", "ronshen", "卡萨帝", "casarte", "西门子", "siemens", "colmo")),
    DeviceTypeRule("fresh_air", "新风", "aircon", Color(0xFF06B6D4), priority = 76,
        keywords = listOf("fresh air", "新风"), brands = listOf("松下", "panasonic", "美的", "midea", "小米", "352", "colmo", "大金", "daikin")),
    DeviceTypeRule("washer", "洗衣/洗烘", "washer", Color(0xFF0EA5E9), priority = 78,
        keywords = listOf("washer", "washing", "dryer", "洗衣", "洗烘"), brands = listOf("海尔", "小天鹅", "美的", "西门子", "colmo")),
    DeviceTypeRule("water_heater", "热水器", "heater", Color(0xFFF97316), priority = 84,
        keywords = listOf("water heater", "heater", "热水器", "电热水器", "燃气热水器", "热水"), brands = listOf("美的", "midea", "海尔", "haier", "林内", "rinnai", "万和", "vanward")),
    DeviceTypeRule("hood", "油烟机", "hood", Color(0xFFF59E0B), priority = 68,
        keywords = listOf("hood", "油烟机", "烟机"), brands = listOf("方太", "fotile", "老板", "robam", "美的", "华帝")),
    DeviceTypeRule("cooker", "灶具", "cooker", Color(0xFFF97316), priority = 68,
        keywords = listOf("cooker", "stove", "灶具", "燃气灶"), brands = listOf("方太", "老板", "美的")),
    DeviceTypeRule("dishwasher", "洗碗机", "dishwasher", Color(0xFF0EA5E9), priority = 68,
        keywords = listOf("dishwasher", "洗碗机"), brands = listOf("美的", "海尔", "西门子", "老板")),
    DeviceTypeRule("oven", "蒸烤箱", "cooker", Color(0xFFF97316), priority = 68,
        keywords = listOf("oven", "steam", "蒸烤箱", "烤箱"), brands = listOf("美的", "方太", "老板")),
    DeviceTypeRule("water_purifier", "净水器", "purifier", Color(0xFF0EA5E9), priority = 70,
        keywords = listOf("purifier", "water purifier", "净水", "净水器"), brands = listOf("安吉尔", "沁园", "美的", "海尔")),
    DeviceTypeRule("rice_cooker", "电饭煲", "rice", Color(0xFFF59E0B), priority = 66,
        keywords = listOf("rice cooker", "电饭煲"), brands = listOf("美的", "苏泊尔", "九阳", "小米")),
    DeviceTypeRule("blender", "破壁机", "rice", Color(0xFFF59E0B), priority = 64,
        keywords = listOf("blender", "破壁机"), brands = listOf("九阳", "美的", "苏泊尔")),
    DeviceTypeRule("air_fryer", "空气炸锅", "rice", Color(0xFFF59E0B), priority = 64,
        keywords = listOf("air fryer", "空气炸锅"), brands = listOf("美的", "九阳", "小熊")),
    DeviceTypeRule("floor_cleaner", "洗地机", "cleaner", Color(0xFF10B981), priority = 68,
        keywords = listOf("floor cleaner", "洗地机"), brands = listOf("追觅", "dreame", "石头", "roborock", "科沃斯", "ecovacs", "添可", "tineco")),
    DeviceTypeRule("robot_vacuum", "扫地机器人", "cleaner", Color(0xFF10B981), priority = 74,
        keywords = listOf("robot vacuum", "vacuum robot", "扫地", "扫地机器人"), brands = listOf("科沃斯", "ecovacs", "石头", "roborock", "云鲸", "narwal", "小米")),
    DeviceTypeRule("vacuum", "吸尘器", "cleaner", Color(0xFF10B981), priority = 66,
        keywords = listOf("vacuum", "吸尘"), brands = listOf("戴森", "dyson", "追觅", "dreame", "美的")),
    DeviceTypeRule("dryer", "电动晾衣架", "curtain", Color(0xFF22C55E), priority = 64,
        keywords = listOf("晾衣架", "clothes rack"), brands = listOf("好太太", "邦先生", "小米")),
    DeviceTypeRule("bath_heater", "浴霸", "heater", Color(0xFFF97316), priority = 64,
        keywords = listOf("浴霸"), brands = listOf("奥普", "美的", "欧普")),
    DeviceTypeRule("air_purifier", "空气净化器", "aircon", Color(0xFF06B6D4), priority = 70,
        keywords = listOf("air purifier", "空气净化器", "净化器"), brands = listOf("小米", "352", "美的", "飞利浦")),
    DeviceTypeRule("humidifier", "加湿器", "water", Color(0xFF0EA5E9), priority = 64,
        keywords = listOf("humidifier", "加湿器"), brands = listOf("小熊", "美的", "小米")),
    DeviceTypeRule("dehumidifier", "除湿机", "water", Color(0xFF0EA5E9), priority = 64,
        keywords = listOf("dehumidifier", "除湿机"), brands = listOf("美的", "格力", "德业")),
    DeviceTypeRule("hair_dryer", "吹风机", "aircon", Color(0xFF06B6D4), priority = 60,
        keywords = listOf("hair dryer", "吹风机"), brands = listOf("戴森", "徕芬", "追觅")),
    DeviceTypeRule("toilet", "智能马桶", "toilet", Color(0xFF0EA5E9), priority = 66,
        keywords = listOf("toilet", "马桶"), brands = listOf("九牧", "恒洁", "toto", "箭牌")),
    DeviceTypeRule("scale", "体脂秤", "scale", Color(0xFF64748B), priority = 64,
        keywords = listOf("scale", "体脂秤", "体重秤"), brands = listOf("小米", "华为", "云麦")),
    DeviceTypeRule("printer", "打印机", "printer", Color(0xFFF59E0B), wolDefault = true, priority = 74,
        keywords = listOf("printer", "print", "laserjet", "打印", "打印机"), brands = listOf("hp", "canon", "epson", "brother", "惠普", "佳能", "爱普生", "兄弟")),
    DeviceTypeRule("iot", "智能设备", "switch", Color(0xFF10B981), priority = 40,
        keywords = listOf("iot", "smart", "mijia", "miio", "ble", "智能")),
    DeviceTypeRule("unknown", "未知设备", "unknown", Color(0xFF64748B), priority = 1)
)

fun deviceTypeById(id: String?): DeviceTypeRule {
    val raw = id?.trim().orEmpty()
    if (raw.isBlank()) return DEVICE_TYPE_RULES.first { it.id == "unknown" }
    return DEVICE_TYPE_RULES.firstOrNull { it.id.equals(raw, ignoreCase = true) }
        ?: DEVICE_TYPE_RULES.firstOrNull { it.label.equals(raw, ignoreCase = true) }
        ?: DEVICE_TYPE_RULES.firstOrNull { it.aliases.any { alias -> alias.equals(raw, ignoreCase = true) } }
        ?: DEVICE_TYPE_RULES.first { it.id == "unknown" }
}

fun selectableDeviceTypes(): List<DeviceTypeRule> = DEVICE_TYPE_RULES
    .filter { it.id != "unknown" && it.id != "iot" }
    .distinctBy { it.id }

val ugreenNasModelTokens = listOf(
    "dh2100+", "dh2600", "dh2300", "dh4300", "dh4300plus",
    "dx4600", "dx4600pro", "dxp2800", "dxp4800", "dxp4800plus",
    "dxp4800gt", "dxp480tplus", "dxp6800plus", "dxp6800pro",
    "dxp6800ultra", "dxp8800", "dxp8800plus", "dxp8800pro", "dxp8800ultra"
)

fun normalizeDeviceTypeToken(raw: String): String {
    val s = raw.trim().lowercase(Locale.getDefault())
    if (s.isBlank()) return ""
    DEVICE_TYPE_RULES.forEach { rule ->
        if (rule.id.lowercase(Locale.getDefault()) == s) return rule.id
        if (rule.label.lowercase(Locale.getDefault()) == s) return rule.id
        if (rule.aliases.any { it.lowercase(Locale.getDefault()) == s }) return rule.id
    }
    return when {
        s.contains("nas") || s.contains("群晖") || s.contains("威联通") || s.contains("极空间") || s.contains("飞牛") || ugreenNasModelTokens.any { s.contains(it) } -> "nas"
        s.contains("迷你") || s.contains("mini") || s.contains("零刻") || s.contains("铭凡") || s.contains("畅网") || s.contains("倍控") -> "mini_pc"
        s.contains("台式") || s == "pc" || s.contains("主机") -> "desktop"
        s.contains("笔记") || s.contains("laptop") || s.contains("macbook") -> "laptop"
        s.contains("光猫") || s.contains("ont") || s.contains("onu") || s.contains("gpon") -> "ont"
        s.contains("路由") || s.contains("网关") || s == "ap" || s.contains("无线ap") -> "router"
        s.contains("手机") || s.contains("iphone") -> "phone"
        s.contains("平板") || s.contains("ipad") || s.contains("matepad") || s.contains("galaxy tab") || s.contains("pad") -> "tablet"
        s.contains("手表") || s.contains("手环") || s.contains("watch") -> "watch"
        s.contains("电视盒") || s.contains("机顶盒") || s.contains("tv box") -> "tv_box"
        s.contains("音箱") || s.contains("音响") || s.contains("speaker") -> "speaker"
        s.contains("热水") -> "water_heater"
        s.contains("空调") -> "aircon"
        s.contains("冰箱") -> "fridge"
        s.contains("洗衣") || s.contains("洗烘") -> "washer"
        s.contains("电视") || s.contains("智慧屏") -> "tv"
        s.contains("投影") -> "projector"
        s.contains("灯") -> "light"
        s.contains("摄像") || s.contains("camera") -> "camera"
        s.contains("打印") || s.contains("printer") -> "printer"
        s.contains("扫地") -> "robot_vacuum"
        s.contains("插座") -> "socket"
        s.contains("开关") -> "switch"
        else -> ""
    }
}

fun deviceTypeDisplayName(raw: String): String {
    val normalized = normalizeDeviceTypeToken(raw).ifBlank { raw.trim() }
    val rule = deviceTypeById(normalized)
    return if (rule.id != "unknown") rule.label else raw.trim().ifBlank { "未知设备" }
}

fun deviceTypeRuleForInput(raw: String): DeviceTypeRule {
    val normalized = normalizeDeviceTypeToken(raw).ifBlank { raw.trim() }
    val rule = deviceTypeById(normalized)
    return if (rule.id != "unknown") rule else DeviceTypeRule(
        id = normalized.ifBlank { "unknown" },
        label = normalized.ifBlank { "未知设备" },
        iconKey = "unknown",
        accent = Color(0xFF64748B),
        priority = 30
    )
}
