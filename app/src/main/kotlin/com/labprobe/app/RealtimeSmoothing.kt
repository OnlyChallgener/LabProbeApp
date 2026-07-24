package com.labprobe.app

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * One-second presentation filter for compact realtime samples.
 *
 * A router ``fast`` frame may arrive about once per second and terminal samples
 * about once every two seconds. Each real sample can change the visible number
 * at most once; there is no 200 ms animation loop and no generated intermediate
 * sequence. Continuous values use a single EMA step, while connection counts and
 * online/offline events always use the latest real integers.
 */
class RealtimeDisplaySmoother {
    companion object {
        const val FRAME_INTERVAL_MS = 1_000L
        const val STALE_WARNING_AGE_MS = 10_000L
        private const val ROUTER_SAMPLE_WEIGHT = 0.72
        private const val DEVICE_SAMPLE_WEIGHT = 0.72
        private const val SLOW_METRIC_WEIGHT = 0.58
    }

    private data class RouterVector(
        val uploadBps: Double = 0.0,
        val downloadBps: Double = 0.0,
        val cpuPercent: Double = 0.0,
        val memoryPercent: Double = 0.0,
        val temperatureC: Double = 0.0,
    )

    private data class RouterDiscrete(
        val totalUploadBytes: Long = 0L,
        val totalDownloadBytes: Long = 0L,
        val uptimeSeconds: Long = 0L,
        val onlineDeviceCount: Int = 0,
        val ipv4Connections: Long = 0L,
        val ipv6Connections: Long = 0L,
        val ipv4HalfConnections: Long = 0L,
        val ipv6HalfConnections: Long = 0L,
        val cps: Long = 0L,
    )

    private data class RouterRenderKey(
        val vector: RouterVector,
        val discrete: RouterDiscrete,
        val sampleEpochMs: Long,
        val stale: Boolean,
    )

    private data class DeviceTrack(
        var displayedUpload: Double,
        var displayedDownload: Double,
        var targetUpload: Double,
        var targetDownload: Double,
        var connectionCount: Int,
        var dirty: Boolean,
    )

    private data class DeviceDisplay(
        val uploadBps: Long,
        val downloadBps: Long,
        val connectionCount: Int,
    )

    private var routerInitialized = false
    private var routerDisplayed = RouterVector()
    private var routerTarget = RouterVector()
    private var routerDiscrete = RouterDiscrete()
    private var routerDirty = false
    private var routerSampleEpochMs = 0L
    private var routerInitialAgeMs = Long.MAX_VALUE
    private var routerReceivedAt = 0L
    private var lastRouterRender: RouterRenderKey? = null

    private val deviceTracks = LinkedHashMap<String, DeviceTrack>()
    private val deviceDisplaySnapshot = LinkedHashMap<String, DeviceDisplay>()
    private var devicesDirty = false
    private var devicesSampleEpochMs = 0L
    private var devicesInitialAgeMs = Long.MAX_VALUE
    private var devicesReceivedAt = 0L
    private var devicesOnlineCount: Int? = null
    private var lastDevicePreparedAt = Long.MIN_VALUE

    fun acceptRouter(payload: JSONObject, now: Long = SystemClock.elapsedRealtime()) {
        val epochMs = payload.optLong("sampleEpochMs", 0L)
        if (epochMs <= 0L) return
        val ageMs = payload.optLong("sampleAgeMs", 0L).coerceAtLeast(0L)

        if (epochMs == routerSampleEpochMs) {
            routerInitialAgeMs = ageMs
            routerReceivedAt = now
            return
        }
        if (epochMs < routerSampleEpochMs) return

        val target = RouterVector(
            uploadBps = payload.optDouble("uploadBps", 0.0).coerceAtLeast(0.0),
            downloadBps = payload.optDouble("downloadBps", 0.0).coerceAtLeast(0.0),
            cpuPercent = payload.optDouble("cpuPercent", 0.0).coerceAtLeast(0.0),
            memoryPercent = payload.optDouble("memoryPercent", 0.0).coerceAtLeast(0.0),
            temperatureC = payload.optDouble("temperatureC", 0.0),
        )
        if (!routerInitialized) {
            routerDisplayed = target
            routerInitialized = true
        }
        routerTarget = target
        routerDirty = true
        routerSampleEpochMs = epochMs
        routerInitialAgeMs = ageMs
        routerReceivedAt = now
        routerDiscrete = RouterDiscrete(
            totalUploadBytes = payload.optLong("totalUploadBytes", 0L).coerceAtLeast(0L),
            totalDownloadBytes = payload.optLong("totalDownloadBytes", 0L).coerceAtLeast(0L),
            uptimeSeconds = payload.optLong("uptimeSeconds", 0L).coerceAtLeast(0L),
            onlineDeviceCount = payload.optInt("onlineDeviceCount", 0).coerceAtLeast(0),
            ipv4Connections = payload.optLong("ipv4Connections", 0L).coerceAtLeast(0L),
            ipv6Connections = payload.optLong("ipv6Connections", 0L).coerceAtLeast(0L),
            ipv4HalfConnections = payload.optLong("ipv4HalfConnections", 0L).coerceAtLeast(0L),
            ipv6HalfConnections = payload.optLong("ipv6HalfConnections", 0L).coerceAtLeast(0L),
            cps = payload.optLong("cps", 0L).coerceAtLeast(0L),
        )
    }

    fun acceptDevices(payload: JSONObject, now: Long = SystemClock.elapsedRealtime()) {
        val epochMs = payload.optLong("sampleEpochMs", 0L)
        if (epochMs <= 0L) return
        val ageMs = payload.optLong("sampleAgeMs", 0L).coerceAtLeast(0L)

        if (epochMs == devicesSampleEpochMs) {
            devicesInitialAgeMs = ageMs
            devicesReceivedAt = now
            return
        }
        if (epochMs < devicesSampleEpochMs) return

        val rows = payload.optJSONArray("devices") ?: JSONArray()
        val delta = payload.optBoolean("delta", false)
        if (payload.has("onlineDeviceCount")) {
            devicesOnlineCount = payload.optInt("onlineDeviceCount", 0).coerceAtLeast(0)
        }
        val seen = HashSet<String>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val mac = cleanMac(row.optString("mac"))
            if (mac.isBlank()) continue
            seen += mac
            val targetUpload = row.optLong("uploadBps", 0L).coerceAtLeast(0L).toDouble()
            val targetDownload = row.optLong("downloadBps", 0L).coerceAtLeast(0L).toDouble()
            val previous = deviceTracks[mac]
            if (previous == null) {
                deviceTracks[mac] = DeviceTrack(
                    displayedUpload = targetUpload,
                    displayedDownload = targetDownload,
                    targetUpload = targetUpload,
                    targetDownload = targetDownload,
                    connectionCount = row.optInt("connectionCount", 0).coerceAtLeast(0),
                    dirty = true,
                )
            } else {
                previous.targetUpload = targetUpload
                previous.targetDownload = targetDownload
                previous.connectionCount = row.optInt("connectionCount", 0).coerceAtLeast(0)
                previous.dirty = true
            }
        }
        if (!delta) deviceTracks.keys.retainAll(seen)
        devicesDirty = true
        devicesSampleEpochMs = epochMs
        devicesInitialAgeMs = ageMs
        devicesReceivedAt = now
        lastDevicePreparedAt = Long.MIN_VALUE
    }

    fun renderRouter(base: JSONObject?, now: Long = SystemClock.elapsedRealtime()): JSONObject? {
        if (!routerInitialized) return null
        val ageMs = effectiveAge(routerInitialAgeMs, routerReceivedAt, now)
        val stale = ageMs > STALE_WARNING_AGE_MS
        if (routerDirty) {
            routerDisplayed = RouterVector(
                uploadBps = blendRate(routerDisplayed.uploadBps, routerTarget.uploadBps, ROUTER_SAMPLE_WEIGHT),
                downloadBps = blendRate(routerDisplayed.downloadBps, routerTarget.downloadBps, ROUTER_SAMPLE_WEIGHT),
                cpuPercent = blend(routerDisplayed.cpuPercent, routerTarget.cpuPercent, SLOW_METRIC_WEIGHT),
                memoryPercent = blend(routerDisplayed.memoryPercent, routerTarget.memoryPercent, SLOW_METRIC_WEIGHT),
                temperatureC = blend(routerDisplayed.temperatureC, routerTarget.temperatureC, SLOW_METRIC_WEIGHT),
            )
            routerDirty = false
        }
        val rounded = RouterVector(
            uploadBps = routerDisplayed.uploadBps.roundToLong().toDouble(),
            downloadBps = routerDisplayed.downloadBps.roundToLong().toDouble(),
            cpuPercent = roundedTenth(routerDisplayed.cpuPercent),
            memoryPercent = roundedTenth(routerDisplayed.memoryPercent),
            temperatureC = roundedTenth(routerDisplayed.temperatureC),
        )
        val discrete = devicesOnlineCount?.let { routerDiscrete.copy(onlineDeviceCount = it) } ?: routerDiscrete
        val key = RouterRenderKey(rounded, discrete, routerSampleEpochMs, stale)
        if (key == lastRouterRender) return null
        lastRouterRender = key

        val sample = JSONObject()
            .put("sampleEpochMs", routerSampleEpochMs)
            .put("sampleAgeMs", ageMs)
            .put("stale", stale)
            .put("uploadBps", rounded.uploadBps.toLong())
            .put("downloadBps", rounded.downloadBps.toLong())
            .put("cpuPercent", rounded.cpuPercent)
            .put("memoryPercent", rounded.memoryPercent)
            .put("temperatureC", rounded.temperatureC)
            .put("totalUploadBytes", discrete.totalUploadBytes)
            .put("totalDownloadBytes", discrete.totalDownloadBytes)
            .put("uptimeSeconds", discrete.uptimeSeconds)
            .put("onlineDeviceCount", discrete.onlineDeviceCount)
            .put("ipv4Connections", discrete.ipv4Connections)
            .put("ipv6Connections", discrete.ipv6Connections)
            .put("ipv4HalfConnections", discrete.ipv4HalfConnections)
            .put("ipv6HalfConnections", discrete.ipv6HalfConnections)
            .put("cps", discrete.cps)
        return mergeLiteRouterRealtime(base, sample).also {
            it.put("realtimeSampleAgeMs", ageMs)
            it.put("realtimeSmoothing", !stale)
        }
    }

    fun renderDevices(items: List<DeviceItem>, now: Long = SystemClock.elapsedRealtime()): List<DeviceItem> {
        if (items.isEmpty() || deviceTracks.isEmpty()) return items
        prepareDeviceDisplay(now)
        if (deviceDisplaySnapshot.isEmpty()) return items
        var changed = false
        val next = items.map { item ->
            val value = deviceDisplaySnapshot[cleanMac(item.mac)] ?: return@map item
            if (
                item.realtimeUploadBytes == value.uploadBps &&
                item.realtimeDownloadBytes == value.downloadBps &&
                item.connectionCount == value.connectionCount
            ) {
                item
            } else {
                changed = true
                item.copy(
                    realtimeUploadBytes = value.uploadBps,
                    realtimeDownloadBytes = value.downloadBps,
                    connectionCount = value.connectionCount,
                )
            }
        }
        return if (changed) next else items
    }

    private fun prepareDeviceDisplay(now: Long) {
        if (lastDevicePreparedAt == now) return
        if (devicesDirty) {
            for (track in deviceTracks.values) {
                if (!track.dirty) continue
                track.displayedUpload = blendRate(track.displayedUpload, track.targetUpload, DEVICE_SAMPLE_WEIGHT)
                track.displayedDownload = blendRate(track.displayedDownload, track.targetDownload, DEVICE_SAMPLE_WEIGHT)
                track.dirty = false
            }
            deviceDisplaySnapshot.clear()
            for ((mac, track) in deviceTracks) {
                deviceDisplaySnapshot[mac] = DeviceDisplay(
                    uploadBps = track.displayedUpload.roundToLong().coerceAtLeast(0L),
                    downloadBps = track.displayedDownload.roundToLong().coerceAtLeast(0L),
                    connectionCount = track.connectionCount,
                )
            }
            devicesDirty = false
        }
        lastDevicePreparedAt = now
    }

    /** Pause presentation work without erasing values already shown by Compose. */
    fun pause() = reset()

    fun reset() {
        routerInitialized = false
        routerDisplayed = RouterVector()
        routerTarget = RouterVector()
        routerDiscrete = RouterDiscrete()
        routerDirty = false
        routerSampleEpochMs = 0L
        routerInitialAgeMs = Long.MAX_VALUE
        routerReceivedAt = 0L
        lastRouterRender = null
        deviceTracks.clear()
        deviceDisplaySnapshot.clear()
        devicesDirty = false
        devicesSampleEpochMs = 0L
        devicesInitialAgeMs = Long.MAX_VALUE
        devicesReceivedAt = 0L
        devicesOnlineCount = null
        lastDevicePreparedAt = Long.MIN_VALUE
    }

    private fun blend(previous: Double, target: Double, weight: Double): Double {
        if (!previous.isFinite() || previous == 0.0) return target
        if (!target.isFinite()) return previous
        return previous + (target - previous) * weight
    }

    private fun blendRate(previous: Double, target: Double, baseWeight: Double): Double {
        if (!previous.isFinite() || previous <= 0.0) return target.coerceAtLeast(0.0)
        if (!target.isFinite()) return previous.coerceAtLeast(0.0)
        if (target <= 0.0) return previous * 0.28
        val low = min(previous, target).coerceAtLeast(1.0)
        val high = max(previous, target)
        val ratio = high / low
        val weight = when {
            ratio >= 8.0 -> 0.88
            ratio >= 3.0 -> 0.80
            abs(target - previous) < 512.0 -> 0.58
            else -> baseWeight
        }
        return blend(previous, target, weight).coerceAtLeast(0.0)
    }

    private fun roundedTenth(value: Double): Double = (value * 10.0).roundToLong() / 10.0

    private fun effectiveAge(initialAge: Long, receivedAt: Long, now: Long): Long {
        if (receivedAt <= 0L || initialAge == Long.MAX_VALUE) return Long.MAX_VALUE
        return initialAge + (now - receivedAt).coerceAtLeast(0L)
    }
}
