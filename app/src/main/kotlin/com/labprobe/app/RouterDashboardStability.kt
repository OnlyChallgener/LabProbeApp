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
    return merged
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
