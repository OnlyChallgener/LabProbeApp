package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouterTrendHistoryTest {
    private fun sample(epoch: Long, upload: Long = 100L) = JSONObject()
        .put("sampleEpochMs", epoch).put("sampleAgeMs", 0)
        .put("uploadBps", upload).put("downloadBps", 900L)
        .put("ipv4Connections", 65).put("ipv6Connections", 38)

    @Test fun retainsRawSpikeBeforeDisplaySmoothing() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptRouter(sample(1_000, 100), now = 1_000)
        smoother.renderRouter(JSONObject(), now = 1_000)
        smoother.acceptRouter(sample(2_000, 90_000), now = 2_000)
        val raw = smoother.trendHistory.samples.value.last()
        val displayed = smoother.renderRouter(JSONObject(), now = 2_000)!!
            .getJSONObject("telemetry").getJSONObject("wan").getLong("uploadBps")
        assertEquals(90_000L, raw.uploadBps)
        assertTrue(displayed < raw.uploadBps)
        assertEquals(103L, raw.sessions)
    }

    @Test fun skipsDuplicateOutOfOrderMissingAndStaleSamplesButKeepsZero() {
        val history = RouterTrendHistory()
        history.record(sample(1_000, 0), 1_000)
        history.record(sample(1_000, 100), 2_000)
        history.record(sample(500), 2_000)
        history.record(sample(2_000).apply { remove("ipv6Connections") }, 2_000)
        history.record(sample(3_000).put("sampleAgeMs", 11_000), 3_000)
        history.record(sample(4_000).put("stale", true), 4_000)
        history.record(sample(5_000).put("uploadBps", -1), 5_000)
        history.record(sample(6_000).put("ipv4Connections", 6e18).put("ipv6Connections", 6e18), 6_000)
        assertEquals(1, history.samples.value.size)
        assertEquals(0L, history.samples.value.single().uploadBps)
    }

    @Test fun boundsRetentionAndKeepsRealGapInsteadOfInventingPoints() {
        val history = RouterTrendHistory()
        for (second in 1..1_000) history.record(sample(second * 1_000L), second * 1_000L)
        val points = history.samples.value
        assertEquals(901, points.size)
        assertEquals(100_000L, points.first().elapsedMs)
        history.record(sample(1_030_000L).put("sampleAgeMs", 500), 1_030_500L)
        assertEquals(30_000L, history.samples.value.last().elapsedMs - points.last().elapsedMs)
        repeat(2_000) { history.record(sample(1_031_000L + it), 1_031_000L + it) }
        assertEquals(RouterTrendHistory.MAX_SAMPLES, history.samples.value.size)
    }
}
