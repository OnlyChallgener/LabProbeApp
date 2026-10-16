package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRealtimeProjectionTest {
    @Test
    fun be50FlatMetricsReachRootNestedDashboardAndEveryStatusCardProjection() {
        val sample = JSONObject("""
            {"sampleEpochMs":1700000000125,"sampleAgeMs":9000,"stale":false,
             "source":"agent_dashboard_push","cpuPercent":7.5,"memoryPercent":31.2,
             "uploadBps":2680,"downloadBps":12000,"ipv4Connections":21}
        """.trimIndent())
        val result = mergeLiteRouterRealtime(JSONObject("""
            {"telemetry":{"cpuPercent":0,"memoryPercent":null,
             "wan":{"uploadBps":0,"downloadBps":0}}}
        """.trimIndent()), sample)
        val telemetry = result.getJSONObject("telemetry")
        val wan = telemetry.getJSONObject("wan")
        val readings = routerStatusTelemetry(result)

        assertEquals(1_700_000_000_125L, result.getLong("sampleEpochMs"))
        assertEquals(result.getLong("sampleEpochMs"), telemetry.getLong("sampleEpochMs"))
        assertEquals("agent_dashboard_push", result.getString("source"))
        assertEquals(9000L, result.getLong("sampleAgeMs"))
        assertEquals(7.5, result.getDouble("cpuPercent"), 0.001)
        assertEquals(7.5, telemetry.getDouble("cpuPercent"), 0.001)
        assertEquals(31.2, telemetry.getDouble("memoryPercent"), 0.001)
        assertEquals(2680L, result.getLong("uploadBps"))
        assertEquals(2680L, wan.getLong("uploadBps"))
        assertEquals(12000L, wan.getLong("downloadBps"))
        assertEquals(21L, telemetry.getJSONObject("connections").getLong("ipv4"))
        assertEquals(7.5, readings.cpuPercent, 0.001)
        assertEquals(31.2, readings.memoryPercent, 0.001)
        assertEquals(2680L, readings.uploadBps)
        assertEquals(12000L, readings.downloadBps)
    }

    @Test
    fun incompleteFirstSampleDoesNotFabricateZeroCpuMemoryOrRates() {
        val result = mergeLiteRouterRealtime(null, JSONObject("""
            {"sampleEpochMs":1700000000000,"sampleAgeMs":0,"cpuPercent":null,"storagePercent":27}
        """.trimIndent()))
        val telemetry = result.getJSONObject("telemetry")
        val wan = telemetry.getJSONObject("wan")

        assertFalse(result.has("cpuPercent"))
        assertFalse(result.has("memoryPercent"))
        assertFalse(result.has("uploadBps"))
        assertFalse(result.has("downloadBps"))
        assertFalse(telemetry.has("cpuPercent"))
        assertFalse(telemetry.has("memoryPercent"))
        assertFalse(wan.has("uploadBps"))
        assertFalse(wan.has("downloadBps"))
        assertEquals(27, telemetry.getInt("storagePercent"))
    }

    @Test
    fun partialUpdateRetainsRealPreviousMeasurementsAndDropsUnsupportedIpv6() {
        val base = mergeLiteRouterRealtime(null, JSONObject("""
            {"sampleEpochMs":1000,"cpuPercent":7.5,"memoryPercent":31.2,
             "uploadBps":2680,"downloadBps":12000,"ipv6Connections":12}
        """.trimIndent()))
        val result = mergeLiteRouterRealtime(base, JSONObject("""
            {"sampleEpochMs":2000,"cpuPercent":null,"memoryPercent":-1,"storagePercent":27}
        """.trimIndent()))
        val readings = routerStatusTelemetry(result)

        assertEquals(7.5, readings.cpuPercent, 0.001)
        assertEquals(31.2, readings.memoryPercent, 0.001)
        assertEquals(2680L, readings.uploadBps)
        assertEquals(12000L, readings.downloadBps)
        assertFalse(result.has("ipv6Connections"))
        assertFalse(result.getJSONObject("telemetry").getJSONObject("connections").has("ipv6"))
    }

    @Test
    fun repeatedFreshSampleIsFreshWithoutPretendingItIsANewerMeasurement() {
        val sample = JSONObject("""
            {"sampleEpochMs":1000,"sampleAgeMs":9000,"stale":false}
        """.trimIndent())
        assertTrue(freshLiteRealtimeSample(sample))
        assertFalse(usableLiteRealtimeSample(sample, previousEpochMs = 1000L))
        assertFalse(freshLiteRealtimeSample(sample, elapsedSinceReceiptMs = 1001L))
    }
}
