package com.labprobe.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dedicated small-payload client for foreground numeric telemetry.
 * It is intentionally independent from full sync, revisions and dashboard JSON.
 */
class LiteRealtimeApi(private val prefs: AppPrefs) {
    private val client = OkHttpClient.Builder()
        .dns(CustomDns(prefs.hubDns))
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2_500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun router(): JSONObject = get("/api/router/realtime")

    suspend fun devices(): JSONObject = get("/api/devices/realtime")

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(prefs.hub)
        if (base.isBlank()) error("Hub 地址为空")
        val request = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer ${prefs.token}")
            .header("X-LabProbe-Token", prefs.token)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val root = runCatching { JSONObject(text) }.getOrElse { error("实时接口返回无效 JSON") }
            if (!root.optBoolean("ok", false)) error(root.optString("error", "实时接口失败"))
            root
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val clean = raw.trim().trimEnd('/')
        if (clean.isBlank()) return ""
        return if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) clean else "http://$clean"
    }
}

fun mergeLiteRouterRealtime(base: JSONObject?, sample: JSONObject): JSONObject {
    val root = runCatching { JSONObject(base?.toString() ?: "{}") }.getOrDefault(JSONObject())
    val telemetry = root.optJSONObject("telemetry") ?: JSONObject().also { root.put("telemetry", it) }
    val wan = telemetry.optJSONObject("wan") ?: JSONObject().also { telemetry.put("wan", it) }
    val connections = telemetry.optJSONObject("connections") ?: JSONObject().also { telemetry.put("connections", it) }

    wan.put("uploadBps", sample.optLong("uploadBps", wan.optLong("uploadBps", 0L)))
    wan.put("downloadBps", sample.optLong("downloadBps", wan.optLong("downloadBps", 0L)))
    wan.put("totalUploadBytes", sample.optLong("totalUploadBytes", wan.optLong("totalUploadBytes", 0L)))
    wan.put("totalDownloadBytes", sample.optLong("totalDownloadBytes", wan.optLong("totalDownloadBytes", 0L)))
    telemetry.put("cpuPercent", sample.optDouble("cpuPercent", telemetry.optDouble("cpuPercent", 0.0)))
    telemetry.put("memoryPercent", sample.optDouble("memoryPercent", telemetry.optDouble("memoryPercent", 0.0)))
    telemetry.put("temperatureC", sample.optDouble("temperatureC", telemetry.optDouble("temperatureC", 0.0)))
    telemetry.put("uptimeSeconds", sample.optLong("uptimeSeconds", telemetry.optLong("uptimeSeconds", 0L)))
    telemetry.put("onlineDeviceCount", sample.optInt("onlineDeviceCount", telemetry.optInt("onlineDeviceCount", 0)))
    connections.put("ipv4", sample.optLong("ipv4Connections", connections.optLong("ipv4", 0L)))
    connections.put("ipv6", sample.optLong("ipv6Connections", connections.optLong("ipv6", 0L)))
    connections.put("ipv4Half", sample.optLong("ipv4HalfConnections", connections.optLong("ipv4Half", 0L)))
    connections.put("ipv6Half", sample.optLong("ipv6HalfConnections", connections.optLong("ipv6Half", 0L)))
    connections.put("cps", sample.optLong("cps", connections.optLong("cps", 0L)))

    val epochMs = sample.optLong("sampleEpochMs", 0L)
    if (epochMs > 0L) {
        root.put("telemetryEpoch", epochMs / 1000.0)
        root.put("telemetryAt", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(epochMs)))
    }
    root.put("telemetryStale", sample.optBoolean("stale", false))
    root.put("online", !sample.optBoolean("stale", false))
    return root
}

fun mergeLiteDeviceRealtime(items: List<DeviceItem>, payload: JSONObject): List<DeviceItem> {
    val rows = payload.optJSONArray("devices") ?: JSONArray()
    if (rows.length() == 0) return items
    val runtime = HashMap<String, Triple<Long, Long, Int>>(rows.length())
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val mac = cleanMac(row.optString("mac"))
        if (mac.isBlank()) continue
        runtime[mac] = Triple(
            row.optLong("uploadBps", 0L).coerceAtLeast(0L),
            row.optLong("downloadBps", 0L).coerceAtLeast(0L),
            row.optInt("connectionCount", 0).coerceAtLeast(0),
        )
    }
    if (runtime.isEmpty()) return items
    var changed = false
    val next = items.map { item ->
        val value = runtime[cleanMac(item.mac)] ?: return@map item
        if (
            item.realtimeUploadBytes == value.first &&
            item.realtimeDownloadBytes == value.second &&
            item.connectionCount == value.third
        ) {
            item
        } else {
            changed = true
            item.copy(
                realtimeUploadBytes = value.first,
                realtimeDownloadBytes = value.second,
                connectionCount = value.third,
            )
        }
    }
    return if (changed) next else items
}
