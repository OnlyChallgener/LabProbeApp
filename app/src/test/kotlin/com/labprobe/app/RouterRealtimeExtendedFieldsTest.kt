package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RouterRealtimeExtendedFieldsTest {
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
