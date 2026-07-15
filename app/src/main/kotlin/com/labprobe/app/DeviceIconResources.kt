package com.labprobe.app

import androidx.annotation.DrawableRes

@DrawableRes
fun deviceIconDrawable(iconKey: String): Int = when (iconKey.trim().lowercase()) {
    "phone" -> R.drawable.device_phone
    "tablet" -> R.drawable.device_tablet
    "laptop" -> R.drawable.device_laptop
    "desktop", "mini_pc", "server", "industrial", "game_console" -> R.drawable.device_desktop
    "nas" -> R.drawable.device_nas
    "watch" -> R.drawable.device_smart_watch
    "child_watch" -> R.drawable.device_child_watch
    "router", "ap" -> R.drawable.device_router
    "network_switch" -> R.drawable.device_network_switch
    "soft_router" -> R.drawable.device_soft_router
    "ont", "network_device" -> R.drawable.device_network_device
    "camera", "doorbell" -> R.drawable.device_camera
    "printer" -> R.drawable.device_printer
    "tv" -> R.drawable.device_tv
    "smart_display" -> R.drawable.device_smart_display
    "projector" -> R.drawable.device_projector
    "tv_box" -> R.drawable.device_tv_box
    "set_top_box" -> R.drawable.device_set_top_box
    "speaker" -> R.drawable.device_smart_speaker
    "smart_panel" -> R.drawable.device_smart_panel
    "aircon", "fresh_air" -> R.drawable.device_air_conditioner
    "floor_aircon" -> R.drawable.device_floor_air_conditioner
    "humidifier", "water" -> R.drawable.device_humidifier
    "air_purifier", "dehumidifier" -> R.drawable.device_air_purifier
    "room_heater", "bath_heater" -> R.drawable.device_heater
    "fan", "hair_dryer" -> R.drawable.device_fan
    "water_dispenser", "purifier", "water_purifier" -> R.drawable.device_water_dispenser
    "microwave" -> R.drawable.device_microwave
    "water_heater", "heater" -> R.drawable.device_water_heater
    "fridge" -> R.drawable.device_fridge
    "washer", "dryer" -> R.drawable.device_washer
    "dishwasher" -> R.drawable.device_dishwasher
    "air_fryer" -> R.drawable.device_air_fryer
    "pressure_cooker", "rice", "rice_cooker", "blender" -> R.drawable.device_pressure_cooker
    "hood", "range_hood" -> R.drawable.device_range_hood
    "cooker", "smart_stove", "oven" -> R.drawable.device_smart_stove
    "floor_cleaner" -> R.drawable.device_floor_cleaner
    "cleaner", "robot_vacuum", "vacuum" -> R.drawable.device_robot_vacuum
    "reading_pen" -> R.drawable.device_reading_pen
    "scale" -> R.drawable.device_scale
    "lock" -> R.drawable.device_smart_lock
    "socket" -> R.drawable.device_smart_socket
    "switch", "sensor" -> R.drawable.device_smart_switch
    "remote" -> R.drawable.device_remote
    "aircon_controller" -> R.drawable.device_ac_controller
    "charger" -> R.drawable.device_charger
    "iot", "light", "curtain", "toilet" -> R.drawable.device_iot
    else -> R.drawable.device_unknown
}
