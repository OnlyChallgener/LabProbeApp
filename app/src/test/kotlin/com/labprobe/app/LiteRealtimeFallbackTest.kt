package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRealtimeFallbackTest {
    @Test
    fun acceptsOnlyNewAndFreshRouterSamples() {
        val sample = JSONObject()
            .put("sampleEpochMs", 19_500L)
            .put("sampleAgeMs", 500L)
            .put("stale", false)

        assertTrue(usableLiteRealtimeSample(sample, 19_000L))
        assertFalse(usableLiteRealtimeSample(sample, 19_500L))
        assertFalse(usableLiteRealtimeSample(sample.put("stale", true), 19_000L))
        assertFalse(usableLiteRealtimeSample(sample.put("stale", false).put("sampleAgeMs", 15_000L), 19_000L))
        assertFalse(usableLiteRealtimeSample(sample.put("sampleAgeMs", 500L), 19_000L, elapsedSinceReceiptMs = 9501L))
    }
}
