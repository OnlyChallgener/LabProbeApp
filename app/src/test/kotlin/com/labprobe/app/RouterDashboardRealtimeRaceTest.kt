package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RouterDashboardRealtimeRaceTest {
    @Test
    fun oldMissingMetricMustNotAcquireTheNewSamplesTimestamp() {
        val current = mergeLiteRouterRealtime(null, JSONObject()
            .put("sampleEpochMs", 20_000L).put("cpuPercent", 23.5))
        val late = JSONObject("""{"telemetryEpoch":10,"telemetry":{"memoryPercent":0}}""")

        val result = requireNotNull(mergeRouterDashboardSnapshot(current, late))
        assertFalse(result.has("memoryPercent"))
        assertFalse(result.getJSONObject("telemetry").has("memoryPercent"))
        assertEquals(-1.0, routerStatusTelemetry(result).memoryPercent, 0.001)
    }

    @Test
    fun lateSlowSnapshotKeepsNewerRealtimeReadingsAndAddsDetails() {
        val current = mergeLiteRouterRealtime(null, JSONObject()
            .put("sampleEpochMs", 20_000L).put("sampleAgeMs", 100L)
            .put("cpuPercent", 23.5).put("memoryPercent", 68.4)
            .put("uploadBps", 3000L).put("downloadBps", 14000L))
        val late = JSONObject("""{"telemetryEpoch":10,"telemetry":{"cpuPercent":4,
            "wan":{"uploadBps":10}},"details":{"identity":{"model":"BE50"}}} """.trimIndent())

        val result = requireNotNull(mergeRouterDashboardSnapshot(current, late))
        val readings = routerStatusTelemetry(result)
        assertEquals(20_000L, readings.sampleEpochMs)
        assertEquals(23.5, readings.cpuPercent, 0.001)
        assertEquals(3000L, readings.uploadBps)
        assertEquals("BE50", result.getJSONObject("details").getJSONObject("identity").getString("model"))
    }

    @Test
    fun newerNestedSnapshotUpdatesFlatAndNestedReadingsTogether() {
        val current = mergeLiteRouterRealtime(null, JSONObject()
            .put("sampleEpochMs", 10_000L).put("cpuPercent", 4).put("uploadBps", 10))
        val latest = JSONObject("""{"telemetryEpoch":20,"telemetry":{"cpuPercent":23.5,
            "memoryPercent":68.4,"wan":{"uploadBps":3000,"downloadBps":14000}}} """.trimIndent())

        val result = requireNotNull(mergeRouterDashboardSnapshot(current, latest))
        assertEquals(23.5, routerStatusTelemetry(result).cpuPercent, 0.001)
        assertEquals(3000L, result.getLong("uploadBps"))
        assertEquals(3000L, result.getJSONObject("telemetry").getJSONObject("wan").getLong("uploadBps"))
        assertEquals(20_000L, result.getLong("sampleEpochMs"))
    }
}
