package com.labprobe.app

import org.json.JSONObject

/** Shared status-card readings for flat realtime frames and nested dashboards. */
internal data class RouterStatusTelemetry(
    val sampleEpochMs: Long,
    val cpuPercent: Double,
    val memoryPercent: Double,
    val uploadBps: Long,
    val downloadBps: Long,
)

internal fun routerStatusTelemetry(root: JSONObject): RouterStatusTelemetry {
    val telemetry = root.optJSONObject("telemetry") ?: JSONObject()
    val wan = telemetry.optJSONObject("wan") ?: JSONObject()
    val epoch = root.optLong("sampleEpochMs", 0L).takeIf { it > 0L }
        ?: telemetry.optLong("sampleEpochMs", 0L).takeIf { it > 0L }
        ?: root.optDouble("telemetryEpoch", 0.0).takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * 1000.0).toLong() }
        ?: 0L

    fun number(key: String, nested: JSONObject): Double {
        if (epoch <= 0L) return -1.0
        return measuredRouterNumber(root, key) ?: measuredRouterNumber(nested, key) ?: -1.0
    }

    return RouterStatusTelemetry(
        sampleEpochMs = epoch,
        cpuPercent = number("cpuPercent", telemetry),
        memoryPercent = number("memoryPercent", telemetry),
        uploadBps = number("uploadBps", wan).toLong(),
        downloadBps = number("downloadBps", wan).toLong(),
    )
}

/** Null, missing, negative and invalid fields are unknown; a measured zero is valid. */
internal fun measuredRouterNumber(payload: JSONObject, key: String): Double? {
    if (!payload.has(key) || payload.isNull(key)) return null
    val raw = payload.opt(key)
    val value = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.trim().removeSuffix("%").trim().toDoubleOrNull()
        else -> null
    }
    return value?.takeIf { it.isFinite() && it >= 0.0 }
}
