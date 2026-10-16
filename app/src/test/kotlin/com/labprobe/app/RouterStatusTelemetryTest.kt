package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RouterStatusTelemetryTest {
    @Test
    fun nestedRelayDashboardUsesTelemetryEpochForAllStatusCardReadings() {
        val readings = routerStatusTelemetry(JSONObject("""
            {"telemetryEpoch":1700000000.125,"telemetry":{
              "cpuPercent":7.5,"memoryPercent":31.2,
              "wan":{"uploadBps":2680,"downloadBps":12000}}}
        """.trimIndent()))

        assertEquals(1_700_000_000_125L, readings.sampleEpochMs)
        assertEquals(7.5, readings.cpuPercent, 0.001)
        assertEquals(31.2, readings.memoryPercent, 0.001)
        assertEquals(2680L, readings.uploadBps)
        assertEquals(12000L, readings.downloadBps)
    }

    @Test
    fun realFlatSampleWinsOverEmptyNestedEwebFields() {
        val readings = routerStatusTelemetry(JSONObject("""
            {"sampleEpochMs":1700000000000,"cpuPercent":12,"memoryPercent":40,
             "uploadBps":5000,"downloadBps":6000,"telemetry":{
               "cpuPercent":0,"memoryPercent":null,"wan":{"uploadBps":0,"downloadBps":0}}}
        """.trimIndent()))

        assertEquals(12.0, readings.cpuPercent, 0.001)
        assertEquals(40.0, readings.memoryPercent, 0.001)
        assertEquals(5000L, readings.uploadBps)
        assertEquals(6000L, readings.downloadBps)
    }

    @Test
    fun missingAndNullMetricsRemainUnknownWhileMeasuredZeroIsValid() {
        val readings = routerStatusTelemetry(JSONObject("""
            {"sampleEpochMs":1700000000000,"telemetry":{"cpuPercent":0,"memoryPercent":null,
             "wan":{"uploadBps":0}}}
        """.trimIndent()))

        assertEquals(0.0, readings.cpuPercent, 0.001)
        assertEquals(-1.0, readings.memoryPercent, 0.001)
        assertEquals(0L, readings.uploadBps)
        assertEquals(-1L, readings.downloadBps)
    }

    @Test
    fun placeholderMetricsWithoutAnySampleTimestampAreUnknown() {
        val readings = routerStatusTelemetry(JSONObject("""
            {"telemetry":{"cpuPercent":0,"memoryPercent":0,"wan":{"uploadBps":0,"downloadBps":0}}}
        """.trimIndent()))

        assertEquals(0L, readings.sampleEpochMs)
        assertEquals(-1.0, readings.cpuPercent, 0.001)
        assertEquals(-1.0, readings.memoryPercent, 0.001)
        assertEquals(-1L, readings.uploadBps)
        assertEquals(-1L, readings.downloadBps)
    }
}
