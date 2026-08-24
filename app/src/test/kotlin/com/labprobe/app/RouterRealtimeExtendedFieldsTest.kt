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
}
