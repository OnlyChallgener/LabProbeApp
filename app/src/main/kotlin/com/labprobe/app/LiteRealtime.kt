package com.labprobe.app

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dedicated small-payload client for startup calibration and temporary recovery
 * while authenticated Hub-native WSS is missing router frames.
 */
class LiteRealtimeApi(private val prefs: AppPrefs) {
    private val targetHub = prefs.hub
    private val targetToken = prefs.token
    private val targetDns = prefs.hubDns
    private val client = OkHttpClient.Builder()
        .dns(CustomDns(targetDns))
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2_500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun router(): JSONObject = get("/api/router/realtime")

    internal suspend fun routerReceived(): ReceivedLiteRealtimeSample = withContext(Dispatchers.IO) {
        ReceivedLiteRealtimeSample(router(), SystemClock.elapsedRealtime())
    }

    suspend fun devices(): JSONObject = get("/api/devices/realtime")

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        requireOriginalConnection()
        check(!prefs.pendingHubIdentity) { "正在确认目标路由器身份" }
        val base = normalizeBaseUrl(targetHub)
        if (base.isBlank()) error("Hub 地址为空")
        val request = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer $targetToken")
            .header("X-LabProbe-Token", targetToken)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            requireOriginalConnection()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val root = runCatching { JSONObject(text) }.getOrElse { error("实时接口返回无效 JSON") }
            if (!root.optBoolean("ok", false)) error(root.optString("error", "实时接口失败"))
            requireOriginalConnection()
            root
        }
    }

    private fun requireOriginalConnection() {
        check(prefs.hub == targetHub && prefs.token == targetToken && prefs.hubDns == targetDns) {
            "Hub 连接已改变，请重新读取当前路由器实时数据"
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val clean = raw.trim().trimEnd('/')
        if (clean.isBlank()) return ""
        return if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) clean else "http://$clean"
    }
}

internal data class ReceivedLiteRealtimeSample(val payload: JSONObject, val receivedAtElapsedMs: Long)

internal fun usableLiteRealtimeSample(
    sample: JSONObject,
    previousEpochMs: Long,
    elapsedSinceReceiptMs: Long = 0L,
): Boolean {
    return sample.optLong("sampleEpochMs", 0L) > previousEpochMs &&
        freshLiteRealtimeSample(sample, elapsedSinceReceiptMs)
}

/** Hub age and Android elapsed time share durations; their wall clocks need not agree. */
internal fun freshLiteRealtimeSample(
    sample: JSONObject,
    elapsedSinceReceiptMs: Long = 0L,
): Boolean {
    val epoch = sample.optLong("sampleEpochMs", 0L)
    val age = sample.optLong("sampleAgeMs", Long.MAX_VALUE)
    return epoch > 0L && !sample.optBoolean("stale", false) &&
        age in 0L..10_000L && elapsedSinceReceiptMs in 0L..(10_000L - age)
}

internal fun remainingLiteRealtimeFreshnessMs(sample: JSONObject, elapsedSinceReceiptMs: Long = 0L): Long =
    if (freshLiteRealtimeSample(sample, elapsedSinceReceiptMs)) {
        10_001L - sample.getLong("sampleAgeMs") - elapsedSinceReceiptMs
    } else 1L

/** Account for parsing/dispatch delay before passing a received sample to presentation. */
internal fun agedLiteRealtimeSample(sample: JSONObject, elapsedSinceReceiptMs: Long): JSONObject? {
    if (!freshLiteRealtimeSample(sample, elapsedSinceReceiptMs)) return null
    return JSONObject(sample.toString()).put("sampleAgeMs", sample.getLong("sampleAgeMs") + elapsedSinceReceiptMs)
}

/** Restore a fresh WSS stream without rolling back a slightly newer HTTP reading. */
internal fun recoveredWssFreshnessEpoch(
    sample: JSONObject, previousWssEpochMs: Long, displayedEpochMs: Long,
    elapsedSinceReceiptMs: Long = 0L,
): Long? {
    val epoch = sample.optLong("sampleEpochMs", 0L)
    if (epoch < previousWssEpochMs || !freshLiteRealtimeSample(sample, elapsedSinceReceiptMs)) return null
    return maxOf(epoch, displayedEpochMs)
}

fun mergeLiteRouterRealtime(base: JSONObject?, sample: JSONObject): JSONObject {
    val root = runCatching { JSONObject(base?.toString() ?: "{}") }.getOrDefault(JSONObject())
    val telemetry = root.optJSONObject("telemetry") ?: JSONObject().also { root.put("telemetry", it) }
    val wan = telemetry.optJSONObject("wan") ?: JSONObject().also { telemetry.put("wan", it) }
    val connections = telemetry.optJSONObject("connections") ?: JSONObject().also { telemetry.put("connections", it) }

    fun copyMeasurement(target: JSONObject, key: String, sourceKey: String = key) {
        if (!sample.has(sourceKey) || sample.isNull(sourceKey)) return
        val value = sample.optDouble(sourceKey, Double.NaN)
        if (value.isFinite() && value >= 0.0) target.put(key, sample.opt(sourceKey))
    }
    listOf("uploadBps", "downloadBps", "totalUploadBytes", "totalDownloadBytes").forEach {
        copyMeasurement(wan, it)
        copyMeasurement(root, it)
    }
    listOf("cpuPercent", "memoryPercent", "temperatureC", "temperature2gC", "temperature5gC",
        "storagePercent", "uptimeSeconds", "onlineDeviceCount").forEach {
        copyMeasurement(telemetry, it)
        copyMeasurement(root, it)
    }
    copyMeasurement(connections, "ipv4", "ipv4Connections")
    copyMeasurement(connections, "ipv4Half", "ipv4HalfConnections")
    copyMeasurement(connections, "cps")
    listOf("ipv4Connections", "ipv4HalfConnections", "cps").forEach { copyMeasurement(root, it) }
    if (sample.has("ipv6Connections") && !sample.isNull("ipv6Connections")) {
        copyMeasurement(connections, "ipv6", "ipv6Connections")
        copyMeasurement(connections, "ipv6Half", "ipv6HalfConnections")
        copyMeasurement(root, "ipv6Connections")
        copyMeasurement(root, "ipv6HalfConnections")
    } else {
        connections.remove("ipv6")
        connections.remove("ipv6Half")
        root.remove("ipv6Connections")
        root.remove("ipv6HalfConnections")
    }

    val epochMs = sample.optLong("sampleEpochMs", 0L)
    if (epochMs > 0L) {
        root.put("sampleEpochMs", epochMs)
        telemetry.put("sampleEpochMs", epochMs)
        root.put("telemetryEpoch", epochMs / 1000.0)
        root.put("telemetryAt", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(epochMs)))
    }
    if (sample.has("sampleAgeMs")) root.put("sampleAgeMs", sample.opt("sampleAgeMs"))
    if (sample.optString("source").isNotBlank()) root.put("source", sample.optString("source"))
    val stale = sample.optBoolean("stale", false)
    root.put("stale", stale)
    root.put("telemetryStale", stale)
    // A temporarily stale realtime stream is not proof that the router is
    // offline. Keep the previous online state while WSS reconnects.
    if (!stale) root.put("online", true)
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
