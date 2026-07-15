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

/** 设备缩略图与终端页共用的克制青蓝色，避免按类型随机换色。 */
val DEVICE_ICON_ACCENT = Color(0xFF20A7B5)
val DEVICE_INFO_CARD_BACKGROUND = Color(0xFFF3F7FB)
val DEVICE_INFO_CARD_BORDER = Color(0xFFE3EBF3)
val DEVICE_TYPE_BADGE_BACKGROUND = Color(0xFFEAF3F5)
val DEVICE_TYPE_BADGE_CONTENT = Color(0xFF475569)

data class DeviceTypeGroup(val name: String, val items: List<DeviceTypeRule>)

private val DEVICE_TYPE_GROUP_ORDER = listOf(
    "常用设备", "计算机与存储", "网络设备", "影音设备", "智能家居", "生活家电", "可穿戴", "其他"
)

private fun deviceTypeCategory(id: String): String = when (id) {
    "phone", "iphone", "huawei_phone", "tablet", "laptop", "desktop", "tv", "smart_display", "speaker", "camera", "printer" -> "常用设备"
    "nas", "mini_pc", "server", "industrial" -> "计算机与存储"
    "router", "soft_router", "ap", "network_switch", "network_device", "ont" -> "网络设备"
    "projector", "tv_box", "set_top_box", "game_console" -> "影音设备"
    "lock", "sensor", "switch", "socket", "light", "ceiling_light", "living_room_light", "bedside_lamp", "desk_lamp", "floor_lamp", "light_strip", "curtain", "doorbell", "remote", "aircon_controller", "smart_panel", "reading_pen", "iot" -> "智能家居"
    "watch", "child_watch" -> "可穿戴"
    "unknown" -> "其他"
    else -> "生活家电"
}

fun selectableDeviceTypeGroups(): List<DeviceTypeGroup> {
    val byGroup = DEVICE_TYPE_RULES
        .distinctBy { it.id }
        .groupBy { deviceTypeCategory(it.id) }
    return DEVICE_TYPE_GROUP_ORDER.mapNotNull { name ->
        byGroup[name]?.takeIf { it.isNotEmpty() }?.let { DeviceTypeGroup(name, it) }
    }
}

fun deviceTypeIcon(iconKey: String): ImageVector = when (iconKey) {
    "router" -> Icons.Rounded.Router
    "ap" -> Icons.Rounded.Wifi
    "ont" -> Icons.Rounded.Router
    "network_device" -> Icons.Rounded.Router
    "nas" -> Icons.Rounded.Storage
    "desktop" -> Icons.Rounded.DesktopWindows
    "mini_pc" -> Icons.Rounded.Memory
    "laptop" -> Icons.Rounded.LaptopMac
    "phone", "iphone", "huawei_phone" -> Icons.Rounded.PhoneAndroid
    "tablet" -> Icons.Rounded.TabletMac
    "watch" -> Icons.Rounded.Watch
    "child_watch" -> Icons.Rounded.Watch
    "tv" -> Icons.Rounded.Tv
    "smart_display" -> Icons.Rounded.Tv
    "tv_box" -> Icons.Rounded.Tv
    "set_top_box" -> Icons.Rounded.Tv
    "game_console" -> Icons.Rounded.Devices
    "projector" -> Icons.Rounded.Tv
    "speaker" -> Icons.Rounded.Speaker
    "smart_panel" -> Icons.Rounded.Devices
    "camera" -> Icons.Rounded.Videocam
    "doorbell" -> Icons.Rounded.Videocam
    "lock" -> Icons.Rounded.Lock
    "switch", "sensor" -> Icons.Rounded.Sensors
    "network_switch" -> Icons.Rounded.Router
    "socket" -> Icons.Rounded.Power
    "light", "ceiling_light", "living_room_light", "bedside_lamp", "desk_lamp", "floor_lamp", "light_strip" -> Icons.Rounded.Lightbulb
    "curtain" -> Icons.Rounded.Sensors
    "aircon" -> Icons.Rounded.AcUnit
    "fresh_air" -> Icons.Rounded.Air
    "floor_aircon", "aircon_controller" -> Icons.Rounded.AcUnit
    "fridge" -> Icons.Rounded.Kitchen
    "washer" -> Icons.Rounded.LocalLaundryService
    "heater", "water_heater", "gas_water_heater", "room_heater", "bath_heater" -> Icons.Rounded.WaterDrop
    "hood" -> Icons.Rounded.Air
    "smart_stove" -> Icons.Rounded.Fastfood
    "dishwasher" -> Icons.Rounded.WaterDrop
    "air_fryer" -> Icons.Rounded.Fastfood
    "pressure_cooker", "blender" -> Icons.Rounded.Fastfood
    "humidifier" -> Icons.Rounded.WaterDrop
    "air_purifier" -> Icons.Rounded.Air
    "purifier" -> Icons.Rounded.WaterDrop
    "rice" -> Icons.Rounded.Fastfood
    "cleaner", "vacuum" -> Icons.Rounded.SmartToy
    "reading_pen" -> Icons.Rounded.SmartToy
    "toilet" -> Icons.Rounded.WaterDrop
    "scale" -> Icons.Rounded.Scale
    "printer" -> Icons.Rounded.Print
    "industrial" -> Icons.Rounded.Computer
    "server" -> Icons.Rounded.Storage
    "soft_router" -> Icons.Rounded.Router
    "remote" -> Icons.Rounded.Sensors
    "fan" -> Icons.Rounded.Air
    "charger" -> Icons.Rounded.Power
    else -> Icons.Rounded.Devices
}

val DEVICE_TYPE_RULES: List<DeviceTypeRule> = listOf(
    DeviceTypeRule(
        id = "router", label = "路由器", iconKey = "router", accent = Color(0xFF06B6D4), priority = 92,
        keywords = listOf("router", "openwrt", "istoreos", "mesh", "gateway", "wireless router", "路由", "网关", "无线ap", "be72", "rg-", "reyee", "ruijie", "unifi", "ubnt", "ubiquiti", "hiwifi", "s8067"),
        brands = listOf("华为", "huawei", "中兴", "zte", "新华三", "h3c", "tp-link", "tplink", "普联", "水星", "mercury", "迅捷", "fast", "腾达", "tenda", "d-link", "dlink", "友讯", "网件", "netgear", "华硕", "asus", "小米", "xiaomi", "mi router", "红米", "redmi", "领势", "linksys", "360", "睿易", "reyee", "锐捷", "ruijie", "unifi", "ubnt", "ubiquiti", "极路由", "hiwifi"),
        aliases = listOf("路由/AP", "路由器", "AP", "无线路由", "企业AP")
    ),
    DeviceTypeRule(
        id = "ont", label = "光猫", iconKey = "ont", accent = Color(0xFF0EA5E9), priority = 90,
        keywords = listOf("ont", "onu", "modem", "gpon", "epon", "光猫", "光纤猫"),
        brands = listOf("华为", "huawei", "中兴", "zte", "贝尔", "alcatel", "烽火", "fiberhome", "友华", "九联", "九洲", "兆能", "创维", "星网锐捷")
    ),
    DeviceTypeRule(
        id = "network_device", label = "网络设备", iconKey = "network_device", accent = Color(0xFF06B6D4), priority = 72,
        keywords = listOf("network device", "network appliance", "网络设备", "网关设备", "边缘网关")
    ),
    DeviceTypeRule("nas", "NAS", "nas", Color(0xFF0EA5E9), wolDefault = true, priority = 96,
        keywords = listOf("nas", "storage", "truenas", "unraid", "fs6706", "私有云", "网络存储", "飞牛", "fnos", "dh2100+", "dh2600", "dh2300", "dh4300", "dh4300plus", "dx4600", "dx4600pro", "dxp2800", "dxp4800", "dxp4800plus", "dxp4800gt", "dxp480tplus", "dxp6800plus", "dxp6800pro", "dxp6800ultra", "dxp8800", "dxp8800plus", "dxp8800pro", "dxp8800ultra"),
        // 绿联是综合品牌，不能仅凭 UGREEN / 绿联判断 NAS；这里只用具体 NAS 型号判断。
        brands = listOf("群晖", "synology", "威联通", "qnap", "极空间", "zspace", "铁威马", "terramaster", "飞牛")),
    DeviceTypeRule("desktop", "台式电脑", "desktop", Color(0xFF2563EB), wolDefault = true, priority = 88,
        keywords = listOf("desktop", "pc", "workstation", "主机", "台式", "台式机", "windows", "win-", "win11", "deskt"),
        brands = listOf("华硕", "asus", "asustek", "联想", "lenovo", "戴尔", "dell", "惠普", "hp", "hewlett", "微星", "msi", "技嘉", "gigabyte", "七彩虹", "colorful", "神舟", "hasee", "机械革命", "mechrevo", "机械师", "machenike", "雷蛇", "razer"),
        aliases = listOf("电脑", "Windows PC", "PC")),
    DeviceTypeRule("mini_pc", "迷你主机", "mini_pc", Color(0xFF2563EB), wolDefault = true, priority = 89,
        keywords = listOf("mini pc", "minipc", "mini-pc", "nuc", "beelink", "minisforum", "mac mini", "macmini", "迷你主机", "小主机", "畅网", "倍控"),
        brands = listOf("零刻", "beelink", "铭凡", "minisforum", "英特尔", "intel", "华硕", "asus", "联想", "lenovo", "惠普", "hp", "戴尔", "dell", "华为", "huawei", "小米", "mi", "七彩虹", "colorful", "畅网", "倍控")),
    DeviceTypeRule("laptop", "笔记本电脑", "laptop", Color(0xFF3B82F6), wolDefault = true, priority = 85,
        keywords = listOf("laptop", "notebook", "macbook", "book", "笔记本", "matebook", "magicbook", "redmibook", "vaio"),
        brands = listOf("华为", "huawei", "荣耀", "honor", "小米", "mi", "xiaomi", "红米", "redmi", "苹果", "apple", "宏碁", "acer", "vaio", "三星", "samsung", "lg", "火影", "firebat")),
    DeviceTypeRule("phone", "手机", "phone", Color(0xFF22C55E), priority = 92,
        keywords = listOf("iphone", "android", "phone", "mobile", "mate", "pura", "reno", "find", "iqoo", "vivo", "oppo", "realme", "oneplus", "galaxy", "pixel", "手机", "nubia", "meizu"),
        brands = listOf("apple", "iphone", "huawei", "honor", "xiaomi", "redmi", "samsung", "oppo", "vivo", "iqoo", "realme", "oneplus", "魅族", "meizu", "努比亚", "nubia", "google pixel", "pixel")),
    DeviceTypeRule("iphone", "iPhone", "iphone", Color(0xFF22C55E), priority = 95,
        keywords = listOf("iphone", "ios", "苹果手机", "apple phone"), brands = listOf("apple", "苹果")),
    DeviceTypeRule("huawei_phone", "华为手机", "huawei_phone", Color(0xFF22C55E), priority = 94,
        keywords = listOf("华为手机", "huawei phone", "mate60", "mate 60", "mate70", "mate 70", "pura", "nova"),
        brands = listOf("华为", "huawei")),
    DeviceTypeRule("tablet", "平板", "tablet", Color(0xFF64748B), priority = 93,
        keywords = listOf("ipad", "ipad pro", "ipad air", "ipad mini", "tablet", "pad", "matepad", "honor pad", "xiaoxin pad", "redmi pad", "mi pad", "galaxy tab", "tab ", "平板"),
        brands = listOf("apple", "苹果", "huawei", "华为", "honor", "荣耀", "xiaomi", "小米", "redmi", "红米", "samsung", "三星", "lenovo", "联想")),
    DeviceTypeRule("watch", "智能手表", "watch", Color(0xFF8B5CF6), priority = 84,
        keywords = listOf("watch", "wear", "band", "手表", "手环", "amazfit", "garmin", "suunto", "coros", "polar", "小天才", "米兔"),
        brands = listOf("amazfit", "华米", "huawei", "华为", "小米", "oppo", "vivo", "iqoo", "garmin", "佳明", "suunto", "颂拓", "coros", "高驰", "polar", "博能", "小天才", "360", "米兔")),
    DeviceTypeRule("child_watch", "儿童手表", "child_watch", Color(0xFF8B5CF6), priority = 88,
        keywords = listOf("儿童手表", "电话手表", "小天才", "米兔", "kid watch", "kids watch")),
    DeviceTypeRule("server", "服务器", "server", Color(0xFF2563EB), wolDefault = true, priority = 90,
        keywords = listOf("server", "服务器", "rack server", "proxmox", "esxi")),
    DeviceTypeRule("industrial", "工控机", "industrial", Color(0xFF2563EB), wolDefault = true, priority = 88,
        keywords = listOf("industrial pc", "industrial", "工控机", "工控电脑")),
    DeviceTypeRule("soft_router", "软路由", "soft_router", Color(0xFF06B6D4), wolDefault = true, priority = 91,
        keywords = listOf("soft router", "软路由", "openwrt", "istoreos", "pfsense", "opnsense")),
    DeviceTypeRule("ap", "AP", "ap", Color(0xFF06B6D4), priority = 86,
        keywords = listOf("wireless ap", "access point", "无线 ap", "吸顶 ap")),
    DeviceTypeRule("network_switch", "交换机", "network_switch", Color(0xFF06B6D4), priority = 84,
        keywords = listOf("network switch", "ethernet switch", "交换机", "poe switch")),

    DeviceTypeRule("tv", "电视", "tv", Color(0xFF7C3AED), priority = 78,
        keywords = listOf("tv", "television", "电视", "mitv", "hisense-tv"), brands = listOf("海信", "hisense", "tcl", "创维", "skyworth", "小米", "xiaomi", "索尼", "sony", "华为", "huawei"),
        aliases = listOf("电视/智慧屏")),
    DeviceTypeRule("smart_display", "智慧屏", "smart_display", Color(0xFF7C3AED), priority = 79,
        keywords = listOf("smart display", "智慧屏", "智能屏", "带屏音箱", "屏幕音箱")),
    DeviceTypeRule("tv_box", "电视盒子", "tv_box", Color(0xFF7C3AED), priority = 79,
        keywords = listOf("tv box", "tvbox", "box", "电视盒子", "机顶盒", "apple tv", "roku", "fire tv", "天猫魔盒", "小米盒子", "魔百和", "天翼高清", "沃家电视"),
        brands = listOf("海美迪", "亿格瑞", "芝杜", "开博尔", "apple tv", "roku", "fire tv", "当贝盒子", "腾讯极光", "爱奇艺电视果", "天猫魔盒", "小米盒子", "魔百和", "天翼高清", "沃家电视")),
    DeviceTypeRule("set_top_box", "机顶盒", "set_top_box", Color(0xFF7C3AED), priority = 80,
        keywords = listOf("set top box", "set-top box", "机顶盒", "iptv", "魔百和", "天翼高清", "沃家电视")),
    DeviceTypeRule("game_console", "游戏机", "game_console", Color(0xFF7C3AED), priority = 78,
        keywords = listOf("game console", "游戏机", "playstation", "xbox", "nintendo switch", "ps5", "ps4")),
    DeviceTypeRule("projector", "投影仪", "projector", Color(0xFF8B5CF6), priority = 78,
        keywords = listOf("projector", "projection", "投影", "投影仪"), brands = listOf("极米", "xgimi", "当贝", "dangbei", "坚果", "jmgo", "爱普生", "epson", "索尼", "sony", "松下", "panasonic", "明基", "benq")),
    DeviceTypeRule("speaker", "智能音箱", "speaker", Color(0xFF14B8A6), priority = 78,
        keywords = listOf("speaker", "sound", "audio", "homepod", "小爱", "天猫精灵", "音箱", "音响", "xiaomi sound", "miaisoundbox"), brands = listOf("小爱", "天猫精灵", "华为", "索尼", "sony", "xiaomi", "mi", "bose", "jbl", "马歇尔", "marshall", "哈曼卡顿", "harman", "b&o", "bang olufsen")),
    DeviceTypeRule("smart_panel", "智能面板", "smart_panel", Color(0xFF14B8A6), priority = 70,
        keywords = listOf("smart panel", "control panel", "智能面板", "中控屏", "场景面板")),
    DeviceTypeRule("camera", "摄像头", "camera", Color(0xFFEF4444), priority = 78,
        keywords = listOf("camera", "cam", "ipc", "nvr", "摄像", "摄像头", "ezviz"), brands = listOf("海康", "hikvision", "萤石", "ezviz", "小米", "360", "tp-link", "tplink", "大华", "dahua", "华为", "huawei")),
    DeviceTypeRule("doorbell", "门铃", "doorbell", Color(0xFFEF4444), priority = 75,
        keywords = listOf("doorbell", "门铃"), brands = listOf("小米", "萤石", "360")),
    DeviceTypeRule("lock", "智能门锁", "lock", Color(0xFF0F766E), priority = 75,
        keywords = listOf("lock", "door lock", "门锁"), brands = listOf("凯迪仕", "德施曼", "小米", "萤石")),
    DeviceTypeRule("sensor", "传感器", "sensor", Color(0xFF10B981), priority = 66,
        keywords = listOf("sensor", "contact", "motion", "门窗传感器", "传感器"), brands = listOf("aqara", "小米", "欧瑞博", "orvibo")),
    DeviceTypeRule("switch", "智能开关", "switch", Color(0xFF10B981), priority = 66,
        keywords = listOf("switch", "开关"), brands = listOf("欧普", "小米", "aqara", "欧瑞博", "orvibo")),
    DeviceTypeRule("socket", "智能插座", "socket", Color(0xFF10B981), priority = 66,
        keywords = listOf("plug", "socket", "插座"), brands = listOf("小米", "公牛", "博联", "broadlink")),
    DeviceTypeRule("light", "智能灯", "light", Color(0xFF22C55E), priority = 72,
        keywords = listOf("light", "lamp", "bulb", "yeelight", "mijia light", "灯"), brands = listOf("小米", "yeelight", "aqara", "欧普", "opple")),
    DeviceTypeRule("ceiling_light", "吸顶灯", "ceiling_light", Color(0xFF22C55E), priority = 73,
        keywords = listOf("ceiling light", "吸顶灯", "顶灯"), brands = listOf("小米", "yeelight", "aqara", "欧普", "opple")),
    DeviceTypeRule("living_room_light", "客厅灯", "living_room_light", Color(0xFF22C55E), priority = 74,
        keywords = listOf("living room light", "客厅灯", "长方形吸顶灯", "矩形吸顶灯", "客厅吸顶灯"), brands = listOf("小米", "yeelight", "aqara", "欧普", "opple")),
    DeviceTypeRule("bedside_lamp", "床头灯", "bedside_lamp", Color(0xFF22C55E), priority = 75,
        keywords = listOf("bedside lamp", "night lamp", "night light", "床头灯", "小夜灯"), brands = listOf("小米", "yeelight", "aqara", "欧普", "opple")),
    DeviceTypeRule("desk_lamp", "台灯", "desk_lamp", Color(0xFF22C55E), priority = 74,
        keywords = listOf("desk lamp", "table lamp", "台灯", "阅读灯"), brands = listOf("小米", "yeelight", "欧普", "明基")),
    DeviceTypeRule("floor_lamp", "落地灯", "floor_lamp", Color(0xFF22C55E), priority = 74,
        keywords = listOf("floor lamp", "落地灯", "立灯"), brands = listOf("小米", "yeelight", "欧普")),
    DeviceTypeRule("light_strip", "智能灯带", "light_strip", Color(0xFF22C55E), priority = 75,
        keywords = listOf("light strip", "led strip", "灯带", "智能灯带"), brands = listOf("小米", "yeelight", "aqara", "欧普", "opple")),
    DeviceTypeRule("curtain", "智能窗帘", "curtain", Color(0xFF22C55E), priority = 66,
        keywords = listOf("curtain", "窗帘"), brands = listOf("杜亚", "小米", "aqara")),
    DeviceTypeRule("remote", "遥控器", "remote", Color(0xFF10B981), priority = 62,
        keywords = listOf("remote", "遥控器", "万能遥控")),
    DeviceTypeRule("aircon_controller", "空调控制器", "aircon_controller", Color(0xFF06B6D4), priority = 70,
        keywords = listOf("air conditioner controller", "aircon controller", "空调控制器", "空调伴侣")),

    DeviceTypeRule("aircon", "空调", "aircon", Color(0xFF06B6D4), priority = 82,
        keywords = listOf("aircon", "air conditioner", "空调", "暖通", "climate"), brands = listOf("格力", "gree", "美的", "midea", "海尔", "haier", "华凌", "tcl", "海信", "hisense", "colmo")),
    DeviceTypeRule("floor_aircon", "立式空调", "floor_aircon", Color(0xFF06B6D4), priority = 83,
        keywords = listOf("floor air conditioner", "立式空调", "柜机空调", "空调柜机")),
    DeviceTypeRule("fridge", "冰箱", "fridge", Color(0xFF0EA5E9), priority = 80,
        keywords = listOf("fridge", "refrigerator", "冰箱"), brands = listOf("海尔", "haier", "美的", "midea", "容声", "ronshen", "卡萨帝", "casarte", "西门子", "siemens", "colmo")),
    DeviceTypeRule("fresh_air", "新风", "fresh_air", Color(0xFF06B6D4), priority = 76,
        keywords = listOf("fresh air", "新风"), brands = listOf("松下", "panasonic", "美的", "midea", "小米", "352", "colmo", "大金", "daikin")),
    DeviceTypeRule("washer", "洗衣机", "washer", Color(0xFF0EA5E9), priority = 78,
        keywords = listOf("washer", "washing", "dryer", "洗衣", "洗烘"), brands = listOf("海尔", "小天鹅", "美的", "西门子", "colmo"), aliases = listOf("洗衣/洗烘")),
    DeviceTypeRule("water_heater", "热水器", "water_heater", Color(0xFFF97316), priority = 84,
        keywords = listOf("water heater", "heater", "热水器", "电热水器", "燃气热水器", "热水"), brands = listOf("美的", "midea", "海尔", "haier", "林内", "rinnai", "万和", "vanward")),
    DeviceTypeRule("gas_water_heater", "燃气热水器", "gas_water_heater", Color(0xFFF97316), priority = 85,
        keywords = listOf("gas water heater", "燃气热水器", "燃热", "天然气热水器"),
        brands = listOf("林内", "rinnai", "能率", "noritz", "万和", "vanward", "美的", "海尔")),
    DeviceTypeRule("water_dispenser", "饮水机", "water_dispenser", Color(0xFF0EA5E9), priority = 70,
        keywords = listOf("water dispenser", "饮水机", "饮水器", "即热饮水")),
    DeviceTypeRule("microwave", "微波炉", "microwave", Color(0xFFF97316), priority = 68,
        keywords = listOf("microwave", "微波炉")),
    DeviceTypeRule("room_heater", "取暖器", "room_heater", Color(0xFFF97316), priority = 66,
        keywords = listOf("room heater", "space heater", "取暖器", "电暖器", "暖风机")),
    DeviceTypeRule("fan", "风扇", "fan", Color(0xFF06B6D4), priority = 64,
        keywords = listOf("fan", "风扇", "电风扇", "循环扇")),
    DeviceTypeRule("hood", "抽油烟机", "hood", Color(0xFFF59E0B), priority = 68,
        keywords = listOf("hood", "油烟机", "烟机"), brands = listOf("方太", "fotile", "老板", "robam", "美的", "华帝"), aliases = listOf("油烟机")),
    DeviceTypeRule("smart_stove", "智能灶", "smart_stove", Color(0xFFF97316), priority = 70,
        keywords = listOf("smart stove", "智能灶", "智能燃气灶", "cooker", "stove", "灶具", "燃气灶")),
    DeviceTypeRule("pressure_cooker", "电压力锅", "pressure_cooker", Color(0xFFF59E0B), priority = 67,
        keywords = listOf("pressure cooker", "电压力锅", "压力锅")),
    DeviceTypeRule("dishwasher", "洗碗机", "dishwasher", Color(0xFF0EA5E9), priority = 68,
        keywords = listOf("dishwasher", "洗碗机"), brands = listOf("美的", "海尔", "西门子", "老板")),
    DeviceTypeRule("oven", "蒸烤箱", "smart_stove", Color(0xFFF97316), priority = 68,
        keywords = listOf("oven", "steam", "蒸烤箱", "烤箱"), brands = listOf("美的", "方太", "老板")),
    DeviceTypeRule("water_purifier", "净水器", "purifier", Color(0xFF0EA5E9), priority = 70,
        keywords = listOf("purifier", "water purifier", "净水", "净水器"), brands = listOf("安吉尔", "沁园", "美的", "海尔")),
    DeviceTypeRule("rice_cooker", "电饭煲", "rice", Color(0xFFF59E0B), priority = 66,
        keywords = listOf("rice cooker", "电饭煲"), brands = listOf("美的", "苏泊尔", "九阳", "小米")),
    DeviceTypeRule("blender", "破壁机", "blender", Color(0xFFF59E0B), priority = 64,
        keywords = listOf("blender", "破壁机"), brands = listOf("九阳", "美的", "苏泊尔")),
    DeviceTypeRule("air_fryer", "空气炸锅", "air_fryer", Color(0xFFF59E0B), priority = 64,
        keywords = listOf("air fryer", "空气炸锅"), brands = listOf("美的", "九阳", "小熊")),
    DeviceTypeRule("charger", "充电头", "charger", Color(0xFF64748B), priority = 60,
        keywords = listOf("desktop charger", "charger", "charging station", "充电头", "桌面充电器", "多口充电器")),
    DeviceTypeRule("floor_cleaner", "洗地机", "floor_cleaner", Color(0xFF10B981), priority = 68,
        keywords = listOf("floor cleaner", "洗地机"), brands = listOf("追觅", "dreame", "石头", "roborock", "科沃斯", "ecovacs", "添可", "tineco")),
    DeviceTypeRule("reading_pen", "点读笔", "reading_pen", Color(0xFF14B8A6), priority = 62,
        keywords = listOf("reading pen", "talking pen", "点读笔", "学习笔", "早教笔", "扫读笔", "词典笔"),
        brands = listOf("小达人", "洪恩", "有道", "步步高", "作业帮", "阿尔法蛋")),
    DeviceTypeRule("robot_vacuum", "扫地机器人", "cleaner", Color(0xFF10B981), priority = 74,
        keywords = listOf("robot vacuum", "vacuum robot", "扫地", "扫地机器人"), brands = listOf("科沃斯", "ecovacs", "石头", "roborock", "云鲸", "narwal", "小米")),
    DeviceTypeRule("vacuum", "吸尘器", "vacuum", Color(0xFF10B981), priority = 66,
        keywords = listOf("vacuum", "吸尘"), brands = listOf("戴森", "dyson", "追觅", "dreame", "美的")),
    DeviceTypeRule("dryer", "电动晾衣架", "dryer", Color(0xFF22C55E), priority = 64,
        keywords = listOf("晾衣架", "clothes rack"), brands = listOf("好太太", "邦先生", "小米")),
    DeviceTypeRule("bath_heater", "浴霸", "heater", Color(0xFFF97316), priority = 64,
        keywords = listOf("浴霸"), brands = listOf("奥普", "美的", "欧普")),
    DeviceTypeRule("air_purifier", "空气净化器", "air_purifier", Color(0xFF06B6D4), priority = 70,
        keywords = listOf("air purifier", "空气净化器", "净化器"), brands = listOf("小米", "352", "美的", "飞利浦")),
    DeviceTypeRule("humidifier", "加湿器", "humidifier", Color(0xFF0EA5E9), priority = 64,
        keywords = listOf("humidifier", "加湿器"), brands = listOf("小熊", "美的", "小米")),
    DeviceTypeRule("dehumidifier", "除湿机", "dehumidifier", Color(0xFF0EA5E9), priority = 64,
        keywords = listOf("dehumidifier", "除湿机"), brands = listOf("美的", "格力", "德业")),
    DeviceTypeRule("hair_dryer", "吹风机", "aircon", Color(0xFF06B6D4), priority = 60,
        keywords = listOf("hair dryer", "吹风机"), brands = listOf("戴森", "徕芬", "追觅")),
    DeviceTypeRule("toilet", "智能马桶", "toilet", Color(0xFF0EA5E9), priority = 66,
        keywords = listOf("toilet", "马桶"), brands = listOf("九牧", "恒洁", "toto", "箭牌")),
    DeviceTypeRule("scale", "体重秤", "scale", Color(0xFF64748B), priority = 64,
        keywords = listOf("scale", "体脂秤", "体重秤"), brands = listOf("小米", "华为", "云麦"), aliases = listOf("体脂秤")),
    DeviceTypeRule("printer", "打印机", "printer", Color(0xFFF59E0B), wolDefault = false, priority = 74,
        keywords = listOf("printer", "print", "laserjet", "打印", "打印机"), brands = listOf("hp", "canon", "epson", "brother", "惠普", "佳能", "爱普生", "兄弟")),
    DeviceTypeRule("iot", "IoT", "iot", Color(0xFF10B981), priority = 40,
        keywords = listOf("iot", "smart", "mijia", "miio", "ble", "智能"), aliases = listOf("智能设备")),
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
        s.contains("台式") || s == "pc" || s.contains("台式主机") || s.contains("电脑主机") -> "desktop"
        s.contains("笔记") || s.contains("laptop") || s.contains("macbook") -> "laptop"
        s.contains("光猫") || s.contains("ont") || s.contains("onu") || s.contains("gpon") -> "ont"
        s.contains("软路由") || s.contains("pfsense") || s.contains("opnsense") || s.contains("istoreos") -> "soft_router"
        s == "ap" || s.contains("无线ap") || s.contains("wireless ap") || s.contains("access point") -> "ap"
        s.contains("交换机") || s.contains("network switch") || s.contains("ethernet switch") -> "network_switch"
        s.contains("网络设备") || s.contains("network device") || s.contains("edge gateway") -> "network_device"
        s.contains("路由") || s.contains("网关") -> "router"
        s.contains("iphone") || s.contains("苹果手机") -> "iphone"
        s.contains("华为手机") || s.contains("huawei phone") || s.contains("mate60") || s.contains("mate 60") || s.contains("mate70") || s.contains("mate 70") || s.contains("pura") || s.contains("nova") -> "huawei_phone"
        s.contains("手机") || s.contains("phone") -> "phone"
        s.contains("平板") || s.contains("ipad") || s.contains("matepad") || s.contains("galaxy tab") || s.contains("pad") -> "tablet"
        s.contains("儿童手表") || s.contains("电话手表") || s.contains("小天才") || s.contains("米兔") -> "child_watch"
        s.contains("手表") || s.contains("手环") || s.contains("watch") -> "watch"
        s.contains("机顶盒") || s.contains("set top box") || s.contains("set-top box") -> "set_top_box"
        s.contains("电视盒") || s.contains("tv box") -> "tv_box"
        s.contains("游戏机") || s.contains("playstation") || s.contains("xbox") -> "game_console"
        s.contains("服务器") || s.contains("server") -> "server"
        s.contains("工控机") || s.contains("industrial pc") -> "industrial"
        s.contains("音箱") || s.contains("音响") || s.contains("speaker") -> "speaker"
        s.contains("燃气热水器") || s.contains("gas water heater") || s.contains("燃热") || s.contains("天然气热水器") -> "gas_water_heater"
        s.contains("热水") -> "water_heater"
        s.contains("空调控制器") || s.contains("空调伴侣") -> "aircon_controller"
        s.contains("立式空调") || s.contains("柜机空调") -> "floor_aircon"
        s.contains("新风") || s.contains("fresh air") -> "fresh_air"
        s.contains("空调") -> "aircon"
        s.contains("冰箱") -> "fridge"
        s.contains("洗衣") || s.contains("洗烘") -> "washer"
        s.contains("智慧屏") || s.contains("智能屏") || s.contains("smart display") || s.contains("中控屏") -> "smart_display"
        s.contains("智能面板") || s.contains("场景面板") || s.contains("smart panel") || s.contains("control panel") -> "smart_panel"
        s.contains("电视") -> "tv"
        s.contains("投影") -> "projector"
        s.contains("客厅灯") || s.contains("living room light") || s.contains("长方形吸顶灯") || s.contains("矩形吸顶灯") || s.contains("客厅吸顶灯") -> "living_room_light"
        s.contains("床头灯") || s.contains("小夜灯") || s.contains("bedside lamp") || s.contains("night lamp") || s.contains("night light") -> "bedside_lamp"
        s.contains("灯带") || s.contains("light strip") || s.contains("led strip") -> "light_strip"
        s.contains("吸顶灯") || s.contains("ceiling light") || s.contains("顶灯") -> "ceiling_light"
        s.contains("台灯") || s.contains("desk lamp") || s.contains("table lamp") || s.contains("阅读灯") -> "desk_lamp"
        s.contains("落地灯") || s.contains("floor lamp") || s.contains("立灯") -> "floor_lamp"
        s.contains("灯") -> "light"
        s.contains("窗帘") || s.contains("curtain") -> "curtain"
        s.contains("摄像") || s.contains("camera") -> "camera"
        s.contains("打印") || s.contains("printer") -> "printer"
        s.contains("扫地") -> "robot_vacuum"
        s.contains("洗地机") || s.contains("floor cleaner") -> "floor_cleaner"
        s.contains("点读笔") || s.contains("学习笔") || s.contains("早教笔") || s.contains("扫读笔") || s.contains("词典笔") || s.contains("reading pen") || s.contains("talking pen") -> "reading_pen"
        s.contains("饮水") -> "water_dispenser"
        s.contains("微波炉") -> "microwave"
        s.contains("电压力锅") || s.contains("压力锅") -> "pressure_cooker"
        s.contains("破壁机") || s.contains("blender") -> "blender"
        s.contains("灶具") || s.contains("燃气灶") || s.contains("智能灶") || s.contains("smart stove") || s.contains("stove") || s.contains("cooker") -> "smart_stove"
        s.contains("晾衣架") || s.contains("clothes rack") -> "dryer"
        s.contains("除湿机") || s.contains("dehumidifier") -> "dehumidifier"
        s.contains("智能马桶") || s.contains("马桶") || s.contains("toilet") -> "toilet"
        s.contains("吸尘") || s.contains("vacuum") -> "vacuum"
        s.contains("取暖器") || s.contains("电暖器") || s.contains("暖风机") -> "room_heater"
        s.contains("风扇") || s.contains("循环扇") -> "fan"
        s.contains("遥控器") -> "remote"
        s.contains("充电头") || s.contains("桌面充电器") || s.contains("多口充电器") -> "charger"
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
