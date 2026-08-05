package com.labprobe.app

/**
 * Returns the single user-facing device title used across home, device lists,
 * detail cards and event summaries. A user-edited remark always wins over the
 * raw name reported by the router or Hub.
 */
internal fun deviceDisplayName(
    remark: String,
    name: String,
    hostName: String,
    mac: String
): String = cleanApiText(remark)
    .ifBlank { cleanApiText(name) }
    .ifBlank { cleanApiText(hostName) }
    .ifBlank { cleanMac(mac) }

fun deviceDisplayName(device: DeviceItem): String = deviceDisplayName(
    remark = device.remark,
    name = device.name,
    hostName = device.hostName,
    mac = device.mac
)
