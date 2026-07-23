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
    val ipv6Candidates: List<Ipv6AddressCandidate> = emptyList(),
    val manufacture: String = "",
    val devType: String = "",
    val osType: String = "",
    val hostName: String = "",
    val wolMode: String = "",
    val connectType: String = "",
    val remark: String = "",
    val manualType: String = "",
    val wolEnabledOverride: Boolean? = null,
    val followedOverride: Boolean? = null,
    val todayUpload: String = "",
    val todayDownload: String = "",
    val totalUpload: String = "",
    val totalDownload: String = "",
    val realtimeUploadBytes: Long = 0L,
    val realtimeDownloadBytes: Long = 0L,
    val connectionCount: Int = 0,
    val todayOnlineDurationSec: Long = 0L,
    val todayOnlineDurationText: String = "",
    val todayOnlineDate: String = ""
)

data class DeviceVisualProfile(
    val type: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val wolCandidate: Boolean,
    val confidence: Int,
    val note: String,
    val iconKey: String = "unknown"
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
    val mac: String = "",
    val manufacture: String = "",
    val devType: String = "",
    val osType: String = "",
    val hostName: String = "",
    val remark: String = "",
    val manualType: String = ""
)
