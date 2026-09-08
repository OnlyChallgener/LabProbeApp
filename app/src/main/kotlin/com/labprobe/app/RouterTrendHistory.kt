package com.labprobe.app

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class RouterTrendSample(
    val epochMs: Long,
    val elapsedMs: Long,
    val uploadBps: Long,
    val downloadBps: Long,
    val ipv4: Long,
    val ipv6: Long,
) {
    val sessions: Long get() = ipv4 + ipv6
}

/** Bounded, raw telemetry history owned by the app state, never by a screen. */
internal class RouterTrendHistory {
    companion object {
        const val RETENTION_MS = 15 * 60_000L
        const val GAP_MS = 15_000L
        const val MAX_SAMPLES = 1_800
        const val MAX_VALID_SPEED_BPS = 10_000_000_000L // 10 Gbps physical link ceiling
    }

    private val mutableSamples = MutableStateFlow<List<RouterTrendSample>>(emptyList())
    val samples = mutableSamples.asStateFlow()

    @Synchronized
    fun record(payload: JSONObject, receivedElapsedMs: Long) {
        val epoch = payload.optLong("sampleEpochMs", 0L)
        val age = payload.optLong("sampleAgeMs", 0L).coerceAtLeast(0L)
        val previous = mutableSamples.value
        if (epoch <= 0L || epoch <= (previous.lastOrNull()?.epochMs ?: 0L)) return
        if (age > GAP_MS || payload.optBoolean("stale", false)) return
        val fields = listOf("uploadBps", "downloadBps", "ipv4Connections", "ipv6Connections")
        // Missing telemetry is unknown, not a zero-valued measurement.
        val values = fields.map { key ->
            payload.opt(key)?.toString()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }
                ?.toLong() ?: return
        }
        if (values[2] > Long.MAX_VALUE - values[3]) return
        val elapsed = receivedElapsedMs - age
        if (elapsed <= (previous.lastOrNull()?.elapsedMs ?: Long.MIN_VALUE)) return
        val upload = if (values[0] > MAX_VALID_SPEED_BPS) previous.lastOrNull()?.uploadBps ?: 0L else values[0]
        val download = if (values[1] > MAX_VALID_SPEED_BPS) previous.lastOrNull()?.downloadBps ?: 0L else values[1]
        val point = RouterTrendSample(epoch, elapsed, upload, download, values[2], values[3])
        mutableSamples.value = (previous.dropWhile { it.elapsedMs < elapsed - RETENTION_MS } + point)
            .takeLast(MAX_SAMPLES)
    }

    @Synchronized
    fun backfill(payload: JSONObject, receivedElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val array = payload.optJSONArray("samples") ?: return
        if (array.length() == 0) return
        val serverEpoch = payload.optLong("serverEpochMs", 0L)
        val incoming = mutableListOf<RouterTrendSample>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val epoch = item.optLong("epochMs", item.optLong("sampleEpochMs", 0L))
            if (epoch <= 0L) continue
            val upload = item.optLong("uploadBps", -1L)
            val download = item.optLong("downloadBps", -1L)
            if (upload < 0L || download < 0L) continue
            val v4 = item.optLong("ipv4", item.optLong("ipv4Connections", 0L)).coerceAtLeast(0L)
            val v6 = item.optLong("ipv6", item.optLong("ipv6Connections", 0L)).coerceAtLeast(0L)
            val safeUpload = minOf(upload, MAX_VALID_SPEED_BPS)
            val safeDownload = minOf(download, MAX_VALID_SPEED_BPS)
            incoming.add(RouterTrendSample(epochMs = epoch, elapsedMs = 0L, uploadBps = safeUpload, downloadBps = safeDownload, ipv4 = v4, ipv6 = v6))
        }
        if (incoming.isEmpty()) return

        val refEpoch = if (serverEpoch > 0L) serverEpoch else maxOf(incoming.last().epochMs, System.currentTimeMillis())

        val existing = mutableSamples.value
        val mergedMap = LinkedHashMap<Long, RouterTrendSample>()
        existing.forEach { mergedMap[it.epochMs] = it }
        incoming.forEach { mergedMap[it.epochMs] = it }

        val sortedList = mergedMap.values.sortedBy { it.epochMs }
        val alignedList = sortedList.map { sample ->
            val ageMs = (refEpoch - sample.epochMs).coerceAtLeast(0L)
            val elapsed = receivedElapsedMs - ageMs
            sample.copy(elapsedMs = elapsed)
        }

        val cutoffElapsed = receivedElapsedMs - RETENTION_MS
        mutableSamples.value = alignedList
            .filter { it.elapsedMs >= cutoffElapsed }
            .takeLast(MAX_SAMPLES)
    }
}
