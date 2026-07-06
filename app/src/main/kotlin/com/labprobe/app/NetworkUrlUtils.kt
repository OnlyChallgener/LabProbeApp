package com.labprobe.app

/**
 * Safe URL join helper for Hub API calls.
 *
 * MainActivity / HubClient may pass paths like:
 *   /api/devices
 *   api/devices
 *   /api/daily?date=2026-07-07
 * This helper keeps query strings intact and avoids duplicate or missing slashes.
 */
fun joinUrl(base: String, path: String): String {
    val cleanPath = path.trim()
    if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) return cleanPath

    val cleanBase = base.trim().trimEnd('/')
    if (cleanBase.isBlank()) return cleanPath
    if (cleanPath.isBlank()) return cleanBase

    return if (cleanPath.startsWith("/")) {
        cleanBase + cleanPath
    } else {
        "$cleanBase/$cleanPath"
    }
}
