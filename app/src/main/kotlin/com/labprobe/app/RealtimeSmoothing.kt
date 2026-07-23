package com.labprobe.app

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Presentation-only smoothing for small realtime samples.
 *
 * Real values and sample timestamps remain authoritative. Continuous values move
 * toward the newest real target over a short window; integer/event values change
 * immediately. No extrapolation or random movement is generated. When the real
 * sample becomes old the animation stops at the last real target.
 */
class RealtimeDisplaySmoother {
    companion object {
        const val FRAME_INTERVAL_MS = 200L
        const val TRANSITION_MS = 900L
        const val MAX_ANIMATION_SAMPLE_AGE_MS = 6_000L
        const val STALE_WARNING_AGE_MS = 8_000L
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
        val fromUpload: Double,
        val fromDownload: Double,
        val targetUpload: Double,
        val targetDownload: Double,
        val connectionCount: Int,
        val startedAt: Long,
        val animate: Boolean,
    )

    private var routerHasSample = false
    private var routerFrom = RouterVector()
    private var routerTarget = RouterVector()
    private var routerDiscrete = RouterDiscrete()
    private var routerStartedAt = 0L
    private var routerAnimate = false
    private var routerSampleEpochMs = 0L
    private var routerInitialAgeMs = Long.MAX_VALUE
    private var routerReceivedAt = 0L
    private var lastRouterRender: RouterRenderKey? = null

    private val deviceTracks = LinkedHashMap<String, DeviceTrack>()
    private var devicesSampleEpochMs = 0L
    private var devicesInitialAgeMs = Long.MAX_VALUE
    private var devicesReceivedAt = 0L

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
        val current = if (routerHasSample) routerVectorAt(now) else target
        val freshEnoughToAnimate = routerHasSample && ageMs <= MAX_ANIMATION_SAMPLE_AGE_MS

        routerFrom = current
        routerTarget = target
        routerStartedAt = now
        routerAnimate = freshEnoughToAnimate && current != target
        routerHasSample = true
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
        val freshEnoughToAnimate = devicesSampleEpochMs > 0L && ageMs <= MAX_ANIMATION_SAMPLE_AGE_MS
        val seen = HashSet<String>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val mac = cleanMac(row.optString("mac"))
            if (mac.isBlank()) continue
            seen += mac
            val previous = deviceTracks[mac]
            val current = previous?.let { deviceValuesAt(it, now) }
                ?: (row.optLong("uploadBps", 0L).coerceAtLeast(0L).toDouble() to
                    row.optLong("downloadBps", 0L).coerceAtLeast(0L).toDouble())
            val targetUpload = row.optLong("uploadBps", 0L).coerceAtLeast(0L).toDouble()
            val targetDownload = row.optLong("downloadBps", 0L).coerceAtLeast(0L).toDouble()
            deviceTracks[mac] = DeviceTrack(
                fromUpload = current.first,
                fromDownload = current.second,
                targetUpload = targetUpload,
                targetDownload = targetDownload,
                connectionCount = row.optInt("connectionCount", 0).coerceAtLeast(0),
                startedAt = now,
                animate = freshEnoughToAnimate &&
                    (current.first != targetUpload || current.second != targetDownload),
            )
        }
        deviceTracks.keys.retainAll(seen)
        devicesSampleEpochMs = epochMs
        devicesInitialAgeMs = ageMs
        devicesReceivedAt = now
    }

    fun renderRouter(base: JSONObject?, now: Long = SystemClock.elapsedRealtime()): JSONObject? {
        if (!routerHasSample) return null
        val ageMs = effectiveAge(routerInitialAgeMs, routerReceivedAt, now)
        val stale = ageMs > STALE_WARNING_AGE_MS
        val vector = if (ageMs > MAX_ANIMATION_SAMPLE_AGE_MS) routerTarget else routerVectorAt(now)
        val rounded = RouterVector(
            uploadBps = vector.uploadBps.roundToLong().toDouble(),
            downloadBps = vector.downloadBps.roundToLong().toDouble(),
            cpuPercent = roundedTenth(vector.cpuPercent),
            memoryPercent = roundedTenth(vector.memoryPercent),
            temperatureC = roundedTenth(vector.temperatureC),
        )
        val key = RouterRenderKey(rounded, routerDiscrete, routerSampleEpochMs, stale)
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
            .put("totalUploadBytes", routerDiscrete.totalUploadBytes)
            .put("totalDownloadBytes", routerDiscrete.totalDownloadBytes)
            .put("uptimeSeconds", routerDiscrete.uptimeSeconds)
            .put("onlineDeviceCount", routerDiscrete.onlineDeviceCount)
            .put("ipv4Connections", routerDiscrete.ipv4Connections)
            .put("ipv6Connections", routerDiscrete.ipv6Connections)
            .put("ipv4HalfConnections", routerDiscrete.ipv4HalfConnections)
            .put("ipv6HalfConnections", routerDiscrete.ipv6HalfConnections)
            .put("cps", routerDiscrete.cps)
        return mergeLiteRouterRealtime(base, sample).also {
            it.put("realtimeSampleAgeMs", ageMs)
            it.put("realtimeSmoothing", !stale)
        }
    }

    fun renderDevices(items: List<DeviceItem>, now: Long = SystemClock.elapsedRealtime()): List<DeviceItem> {
        if (items.isEmpty() || deviceTracks.isEmpty()) return items
        val ageMs = effectiveAge(devicesInitialAgeMs, devicesReceivedAt, now)
        val allowAnimation = ageMs <= MAX_ANIMATION_SAMPLE_AGE_MS
        var changed = false
        val next = items.map { item ->
            val track = deviceTracks[cleanMac(item.mac)] ?: return@map item
            val values = if (allowAnimation) deviceValuesAt(track, now) else track.targetUpload to track.targetDownload
            val upload = values.first.roundToLong().coerceAtLeast(0L)
            val download = values.second.roundToLong().coerceAtLeast(0L)
            if (
                item.realtimeUploadBytes == upload &&
                item.realtimeDownloadBytes == download &&
                item.connectionCount == track.connectionCount
            ) {
                item
            } else {
                changed = true
                item.copy(
                    realtimeUploadBytes = upload,
                    realtimeDownloadBytes = download,
                    connectionCount = track.connectionCount,
                )
            }
        }
        return if (changed) next else items
    }

    fun reset() {
        routerHasSample = false
        routerFrom = RouterVector()
        routerTarget = RouterVector()
        routerDiscrete = RouterDiscrete()
        routerSampleEpochMs = 0L
        routerInitialAgeMs = Long.MAX_VALUE
        routerReceivedAt = 0L
        lastRouterRender = null
        deviceTracks.clear()
        devicesSampleEpochMs = 0L
        devicesInitialAgeMs = Long.MAX_VALUE
        devicesReceivedAt = 0L
    }

    private fun routerVectorAt(now: Long): RouterVector {
        if (!routerAnimate) return routerTarget
        val progress = progress(routerStartedAt, now)
        if (progress >= 1.0) {
            routerAnimate = false
            return routerTarget
        }
        return RouterVector(
            uploadBps = interpolate(routerFrom.uploadBps, routerTarget.uploadBps, progress),
            downloadBps = interpolate(routerFrom.downloadBps, routerTarget.downloadBps, progress),
            cpuPercent = interpolate(routerFrom.cpuPercent, routerTarget.cpuPercent, progress),
            memoryPercent = interpolate(routerFrom.memoryPercent, routerTarget.memoryPercent, progress),
            temperatureC = interpolate(routerFrom.temperatureC, routerTarget.temperatureC, progress),
        )
    }

    private fun deviceValuesAt(track: DeviceTrack, now: Long): Pair<Double, Double> {
        if (!track.animate) return track.targetUpload to track.targetDownload
        val progress = progress(track.startedAt, now)
        return interpolate(track.fromUpload, track.targetUpload, progress) to
            interpolate(track.fromDownload, track.targetDownload, progress)
    }

    private fun progress(startedAt: Long, now: Long): Double {
        val linear = ((now - startedAt).coerceAtLeast(0L).toDouble() / TRANSITION_MS).coerceIn(0.0, 1.0)
        return linear * linear * (3.0 - 2.0 * linear)
    }

    private fun interpolate(from: Double, to: Double, progress: Double): Double = from + (to - from) * progress

    private fun roundedTenth(value: Double): Double = (value * 10.0).roundToLong() / 10.0

    private fun effectiveAge(initialAge: Long, receivedAt: Long, now: Long): Long {
        if (receivedAt <= 0L || initialAge == Long.MAX_VALUE) return Long.MAX_VALUE
        return initialAge + (now - receivedAt).coerceAtLeast(0L)
    }
}
