package com.labprobe.app

import androidx.annotation.DrawableRes

@DrawableRes
fun deviceIconDrawable(iconKey: String): Int = when (iconKey.trim().lowercase()) {
    "phone" -> R.drawable.device_phone
    "iphone" -> R.drawable.device_iphone
    "huawei_phone" -> R.drawable.device_huawei_phone
    "tablet" -> R.drawable.device_tablet
    "laptop" -> R.drawable.device_laptop
    "desktop" -> R.drawable.device_desktop
    "game_console" -> R.drawable.device_game_console
    "mini_pc" -> R.drawable.device_mini_pc
    "mac_mini" -> R.drawable.device_mac_mini
    "server" -> R.drawable.device_server
    "industrial" -> R.drawable.device_industrial_pc
    "nas" -> R.drawable.device_nas
    "watch" -> R.drawable.device_smart_watch
    "child_watch" -> R.drawable.device_child_watch
    "router" -> R.drawable.device_router
    "ap" -> R.drawable.device_ap
    "network_switch" -> R.drawable.device_network_switch
    "soft_router" -> R.drawable.device_soft_router
    "ont" -> R.drawable.device_ont
    "network_device" -> R.drawable.device_network_device
    "camera" -> R.drawable.device_camera
    "doorbell" -> R.drawable.device_doorbell
    "printer" -> R.drawable.device_printer
    "tv" -> R.drawable.device_tv
    "smart_display" -> R.drawable.device_smart_display
    "projector" -> R.drawable.device_projector
    "tv_box" -> R.drawable.device_tv_box
    "set_top_box" -> R.drawable.device_set_top_box
    "speaker" -> R.drawable.device_smart_speaker
    "soundbar" -> R.drawable.device_soundbar
    "smart_panel" -> R.drawable.device_smart_panel
    "aircon" -> R.drawable.device_air_conditioner
    "fresh_air" -> R.drawable.device_fresh_air
    "floor_aircon" -> R.drawable.device_floor_air_conditioner
    "humidifier", "water" -> R.drawable.device_humidifier
    "air_purifier" -> R.drawable.device_air_purifier
    "dehumidifier" -> R.drawable.device_dehumidifier
    "room_heater" -> R.drawable.device_heater
    "bath_heater" -> R.drawable.device_bath_heater
    "fan" -> R.drawable.device_fan
    "hair_dryer" -> R.drawable.device_hair_dryer
    "water_dispenser", "purifier", "water_purifier" -> R.drawable.device_water_dispenser
    "microwave" -> R.drawable.device_microwave
    "water_heater", "heater" -> R.drawable.device_water_heater
    "gas_water_heater" -> R.drawable.device_gas_water_heater
    "fridge" -> R.drawable.device_fridge
    "washer" -> R.drawable.device_washer
    "dryer" -> R.drawable.device_clothes_dryer
    "dishwasher" -> R.drawable.device_dishwasher
    "air_fryer" -> R.drawable.device_air_fryer
    "pressure_cooker", "rice", "rice_cooker" -> R.drawable.device_pressure_cooker
    "blender" -> R.drawable.device_blender
    "hood", "range_hood" -> R.drawable.device_range_hood
    "cooker", "smart_stove" -> R.drawable.device_smart_stove
    "oven", "steam_oven" -> R.drawable.device_steam_oven
    "floor_cleaner" -> R.drawable.device_floor_cleaner
    "cleaner", "robot_vacuum" -> R.drawable.device_robot_vacuum
    "vacuum" -> R.drawable.device_vacuum
    "reading_pen" -> R.drawable.device_reading_pen
    "scale" -> R.drawable.device_scale
    "lock" -> R.drawable.device_smart_lock
    "socket" -> R.drawable.device_smart_socket
    "power_strip" -> R.drawable.device_power_strip
    "switch" -> R.drawable.device_smart_switch
    "sensor" -> R.drawable.device_sensor
    "remote" -> R.drawable.device_remote
    "aircon_controller" -> R.drawable.device_ac_controller
    "aircon_companion" -> R.drawable.device_aircon_companion
    "charger" -> R.drawable.device_charger
    "desktop_charger" -> R.drawable.device_desktop_charger
    "usb_dock" -> R.drawable.device_usb_dock
    "headphones" -> R.drawable.device_headphones
    "wireless_mouse" -> R.drawable.device_wireless_mouse
    "wireless_keyboard" -> R.drawable.device_wireless_keyboard
    "wireless_earbuds" -> R.drawable.device_wireless_earbuds
    "smart_ring" -> R.drawable.device_smart_ring
    "all_in_one" -> R.drawable.device_all_in_one
    "light" -> R.drawable.device_smart_light
    "ceiling_light" -> R.drawable.device_ceiling_light
    "living_room_light" -> R.drawable.device_living_room_light
    "bedside_lamp" -> R.drawable.device_bedside_lamp
    "desk_lamp" -> R.drawable.device_desk_lamp
    "floor_lamp" -> R.drawable.device_floor_lamp
    "light_strip" -> R.drawable.device_light_strip
    "curtain" -> R.drawable.device_smart_curtain
    "iot" -> R.drawable.device_iot
    "toilet" -> R.drawable.device_smart_toilet
    else -> R.drawable.device_unknown
}
