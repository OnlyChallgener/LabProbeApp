package com.labprobe.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class DeviceItem(
    val name: String,
    val mac: String,
    val online: Boolean,
    val ip: String,
    val ssid: String,
    val band: String,
    val rssi: String,
    val rxrate: String,
    val onlineSince: String,
    val offlineAt: String,
    val onlineDurationText: String,
    val lastSeenAt: String,
    val ipv6: List<String> = emptyList(),
    val manufacture: String = "",
    val devType: String = "",
    val osType: String = "",
    val hostName: String = "",
    val wolMode: String = "",
    val connectType: String = "",
    val remark: String = "",
    val manualType: String = "",
    val wolEnabledOverride: Boolean? = null
)

data class DeviceVisualProfile(
    val type: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val wolCandidate: Boolean,
    val confidence: Int,
    val note: String
)

data class EventItem(
    val id: Int,
    val title: String,
    val type: String,
    val name: String,
    val oldValue: String,
    val newValue: String,
    val time: String,
    val ip: String = "",
    val rssi: String = "",
    val band: String = "",
    val rxrate: String = "",
    val ssid: String = "",
    val onlineSince: String = "",
    val offlineAt: String = "",
    val onlineDurationText: String = "",
    val mac: String = ""
)
