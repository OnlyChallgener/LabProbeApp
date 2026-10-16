package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject

/** Keep the last usable dashboard while Hub is reconnecting or returning a partial live snapshot. */
fun routerDashboardHasContent(root: JSONObject?): Boolean {
    if (root == null || root.length() == 0) return false
    if (root.optBoolean("online", false)) return true

    val telemetry = root.optJSONObject("telemetry")
    val details = root.optJSONObject("details")
    val identity = details?.optJSONObject("identity")
    val wan = details?.optJSONObject("wan")
    val ap = details?.optJSONObject("ap")
    val meaningful = listOf(
        root.optString("router"),
        identity?.optString("hostname"),
        identity?.optString("model"),
        identity?.optString("serialNumber"),
        wan?.optString("ipv4"),
        wan?.optString("gateway"),
        ap?.optString("hostName"),
        ap?.optString("model"),
        ap?.optString("networkName")
    ).map { it.orEmpty().trim() }.filter { it.isNotBlank() && it != "--" && it != "router" && it != "null" }

    return meaningful.isNotEmpty() ||
        (details?.optJSONArray("ports")?.length() ?: 0) > 0 ||
        (telemetry != null && telemetry.length() > 0)
}

fun mergeRouterDashboardSnapshot(previous: JSONObject?, latest: JSONObject?): JSONObject? {
    if (latest == null || latest.length() == 0) return previous
    if (previous == null || previous.length() == 0) return JSONObject(latest.toString())

    val merged = JSONObject(previous.toString())
    mergeRouterObject(merged, latest)
    val previousSample = dashboardRealtimeSample(previous)
    val latestSample = dashboardRealtimeSample(latest)
    // Slow eWeb responses may finish after a new realtime frame. Merge their
    // identity/details without rolling the current measured values backwards.
    if (previousSample.optLong("sampleEpochMs") > latestSample.optLong("sampleEpochMs")) {
        clearDashboardMeasurements(merged)
        return mergeLiteRouterRealtime(merged, previousSample)
    }
    if (latestSample.optLong("sampleEpochMs") > 0L) {
        return mergeLiteRouterRealtime(merged, latestSample)
    }
    return merged
}

private fun clearDashboardMeasurements(root: JSONObject) {
    val telemetry = root.optJSONObject("telemetry")
    listOf("uploadBps", "downloadBps", "totalUploadBytes", "totalDownloadBytes").forEach {
        root.remove(it)
        telemetry?.optJSONObject("wan")?.remove(it)
    }
    listOf("cpuPercent", "memoryPercent", "temperatureC", "temperature2gC", "temperature5gC",
        "storagePercent", "uptimeSeconds", "onlineDeviceCount").forEach {
        root.remove(it)
        telemetry?.remove(it)
    }
    listOf("ipv4Connections", "ipv6Connections", "ipv4HalfConnections", "ipv6HalfConnections", "cps").forEach(root::remove)
    telemetry?.optJSONObject("connections")?.let { connections ->
        listOf("ipv4", "ipv6", "ipv4Half", "ipv6Half", "cps").forEach(connections::remove)
    }
    listOf("sampleAgeMs", "source").forEach(root::remove)
}

private fun dashboardRealtimeSample(root: JSONObject): JSONObject {
    val telemetry = root.optJSONObject("telemetry") ?: JSONObject()
    val wan = telemetry.optJSONObject("wan") ?: JSONObject()
    val connections = telemetry.optJSONObject("connections") ?: JSONObject()
    val sample = JSONObject().put("sampleEpochMs", routerStatusTelemetry(root).sampleEpochMs)
        .put("stale", root.optBoolean("telemetryStale", root.optBoolean("stale", false)))
    listOf("source", "sampleAgeMs").forEach { if (root.has(it)) sample.put(it, root.opt(it)) }
    fun copy(key: String, nested: JSONObject, nestedKey: String = key) {
        val value = measuredRouterNumber(root, key) ?: measuredRouterNumber(nested, nestedKey) ?: return
        sample.put(key, value)
    }
    listOf("uploadBps", "downloadBps", "totalUploadBytes", "totalDownloadBytes").forEach { copy(it, wan) }
    listOf("cpuPercent", "memoryPercent", "temperatureC", "temperature2gC", "temperature5gC",
        "storagePercent", "uptimeSeconds", "onlineDeviceCount").forEach { copy(it, telemetry) }
    copy("ipv4Connections", connections, "ipv4")
    copy("ipv6Connections", connections, "ipv6")
    copy("ipv4HalfConnections", connections, "ipv4Half")
    copy("ipv6HalfConnections", connections, "ipv6Half")
    copy("cps", connections)
    return sample
}

private fun mergeRouterObject(target: JSONObject, source: JSONObject) {
    val keys = source.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val incoming = source.opt(key)
        when (incoming) {
            is JSONObject -> {
                if (incoming.length() == 0 && target.optJSONObject(key)?.length()?.let { it > 0 } == true) continue
                val child = target.optJSONObject(key)?.let { JSONObject(it.toString()) } ?: JSONObject()
                mergeRouterObject(child, incoming)
                target.put(key, child)
            }
            is JSONArray -> {
                if (incoming.length() == 0 && target.optJSONArray(key)?.length()?.let { it > 0 } == true) continue
                target.put(key, JSONArray(incoming.toString()))
            }
            JSONObject.NULL, null -> if (!target.has(key)) target.put(key, JSONObject.NULL)
            is String -> {
                val old = target.optString(key).trim()
                if (incoming.isBlank() && old.isNotBlank() && old != "--") continue
                target.put(key, incoming)
            }
            else -> target.put(key, incoming)
        }
    }
}
