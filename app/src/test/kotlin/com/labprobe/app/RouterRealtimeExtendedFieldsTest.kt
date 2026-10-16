package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterRealtimeExtendedFieldsTest {
    @Test
    fun missingSlowMetricsDoNotEraseMeasuredCpuMemoryOrRates() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptRouter(JSONObject("""
            {"sampleEpochMs":1000,"cpuPercent":7.5,"memoryPercent":31.2,
             "uploadBps":2680,"downloadBps":12000}
        """.trimIndent()), now = 1000L)
        val first = requireNotNull(smoother.renderRouter(JSONObject(), now = 1000L))
        smoother.acceptRouter(JSONObject("""
            {"sampleEpochMs":2000,"storagePercent":27,"cpuPercent":null,"memoryPercent":null}
        """.trimIndent()), now = 2000L)

        val second = requireNotNull(smoother.renderRouter(first, now = 2000L))
        val readings = routerStatusTelemetry(second)
        assertEquals(7.5, readings.cpuPercent, 0.001)
        assertEquals(31.2, readings.memoryPercent, 0.001)
        assertEquals(2680L, readings.uploadBps)
        assertEquals(12000L, readings.downloadBps)
    }

    @Test
    fun firstPartialSampleDoesNotInventCpuMemoryOrRates() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptRouter(JSONObject().put("sampleEpochMs", 1000L).put("storagePercent", 27), now = 1000L)
        val result = requireNotNull(smoother.renderRouter(JSONObject(), now = 1000L))
        val telemetry = result.getJSONObject("telemetry")
        val wan = telemetry.getJSONObject("wan")

        assertFalse(telemetry.has("cpuPercent"))
        assertFalse(telemetry.has("memoryPercent"))
        assertFalse(wan.has("uploadBps"))
        assertFalse(wan.has("downloadBps"))
    }

    @Test
    fun newRealCpuAndMemorySampleUpdatesTheDisplayedReading() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptRouter(JSONObject().put("sampleEpochMs", 1000L).put("cpuPercent", 4).put("memoryPercent", 30), now = 1000L)
        val first = requireNotNull(smoother.renderRouter(JSONObject(), now = 1000L))
        smoother.acceptRouter(JSONObject().put("sampleEpochMs", 2000L).put("cpuPercent", 8).put("memoryPercent", 40), now = 2000L)
        val result = requireNotNull(smoother.renderRouter(first, now = 2000L))
        val readings = routerStatusTelemetry(result)

        assertTrue(readings.cpuPercent > 4.0 && readings.cpuPercent <= 8.0)
        assertTrue(readings.memoryPercent > 30.0 && readings.memoryPercent <= 40.0)
    }

    @Test
    fun smootherKeepsRadioTemperaturesStorageAndUptime() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptRouter(
            JSONObject()
                .put("sampleEpochMs", 1_000L)
                .put("sampleAgeMs", 0L)
                .put("temperatureC", 52.0)
                .put("temperature2gC", 47.0)
                .put("temperature5gC", 50.0)
                .put("storagePercent", 18.0)
                .put("uptimeSeconds", 90_000L),
            now = 1_000L,
        )

        val result = requireNotNull(smoother.renderRouter(JSONObject(), now = 1_000L))
        val telemetry = result.getJSONObject("telemetry")

        assertEquals(47.0, telemetry.getDouble("temperature2gC"), 0.01)
        assertEquals(50.0, telemetry.getDouble("temperature5gC"), 0.01)
        assertEquals(18.0, telemetry.getDouble("storagePercent"), 0.01)
        assertEquals(90_000L, telemetry.getLong("uptimeSeconds"))
    }

    @Test
    fun parseDdnsListAcceptsServicesAndListKeys() {
        val jsonServices = JSONObject("""
            {"services":[{"service":"0","service_name":"aliyun.com","domain":"rj.lab86@shinya.icu","username":"ak","enable":true}]}
        """.trimIndent())
        val list1 = parseDdnsList(jsonServices)
        assertEquals(1, list1.size)
        assertEquals("rj.lab86@shinya.icu", list1[0].domain)

        val jsonList = JSONObject("""
            {"list":[{"service":"1","service_name":"aliyun.com","domain":"op.lab86@shinya.icu","username":"ak","enable":true}]}
        """.trimIndent())
        val list2 = parseDdnsList(jsonList)
        assertEquals(1, list2.size)
        assertEquals("op.lab86@shinya.icu", list2[0].domain)
    }
}
