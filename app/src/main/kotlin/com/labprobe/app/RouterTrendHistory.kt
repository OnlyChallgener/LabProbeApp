package com.labprobe.app

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
        val point = RouterTrendSample(epoch, elapsed, values[0], values[1], values[2], values[3])
        mutableSamples.value = (previous.dropWhile { it.elapsedMs < elapsed - RETENTION_MS } + point)
            .takeLast(MAX_SAMPLES)
    }
}
