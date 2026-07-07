package com.labprobe.app

fun joinUrl(base: String, path: String): String {
    val cleanPath = path.trim()
    if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) return cleanPath

    val cleanBase = base.trim().trimEnd('/')
    if (cleanBase.isBlank()) return cleanPath
    if (cleanPath.isBlank()) return cleanBase

    return if (cleanPath.startsWith("/")) cleanBase + cleanPath else "$cleanBase/$cleanPath"
}
