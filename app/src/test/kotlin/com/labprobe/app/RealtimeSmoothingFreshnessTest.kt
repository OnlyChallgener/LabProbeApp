package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSmoothingFreshnessTest {
    private fun sample(epoch: Long, age: Long) = JSONObject()
        .put("sampleEpochMs", epoch)
        .put("sampleAgeMs", age)
        .put("cpuPercent", 7.0)

    @Test
    fun repeatedAgeZeroDoesNotKeepOldRouterDataFresh() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptRouter(sample(1_000L, 100L), now = 1_000L)
        val first = requireNotNull(smoother.renderRouter(JSONObject(), now = 1_000L))
        for (now in listOf(3_000L, 5_000L, 7_000L, 9_000L)) {
            smoother.acceptRouter(sample(1_000L, 0L), now = now)
        }
        val expired = requireNotNull(smoother.renderRouter(first, now = 10_901L))
        assertTrue(expired.getBoolean("stale"))
        assertEquals(10_001L, expired.getLong("realtimeSampleAgeMs"))
    }

    @Test
    fun normalRepeatedFrameAgeKeepsTheOriginalExpiry() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptRouter(sample(1_000L, 100L), now = 1_000L)
        val first = requireNotNull(smoother.renderRouter(JSONObject(), now = 1_000L))
        smoother.acceptRouter(sample(1_000L, 2_100L), now = 3_000L)
        val expired = requireNotNull(smoother.renderRouter(first, now = 10_901L))
        assertTrue(expired.getBoolean("stale"))
        assertEquals(10_001L, expired.getLong("realtimeSampleAgeMs"))
    }

    @Test
    fun aNewMeasurementCanRestoreFreshPresentationAfterExpiry() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptRouter(sample(1_000L, 9_000L), now = 1_000L)
        val first = requireNotNull(smoother.renderRouter(JSONObject(), now = 1_000L))
        val expired = requireNotNull(smoother.renderRouter(first, now = 2_001L))
        assertTrue(expired.getBoolean("stale"))
        smoother.acceptRouter(sample(2_000L, 100L), now = 3_000L)
        val latest = requireNotNull(smoother.renderRouter(expired, now = 3_000L))
        assertFalse(latest.getBoolean("stale"))
        assertEquals(100L, latest.getLong("realtimeSampleAgeMs"))
    }
}
