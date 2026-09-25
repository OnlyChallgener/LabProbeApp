package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRealtimeFallbackTest {
    @Test
    fun acceptsOnlyNewAndFreshRouterSamples() {
        val sample = JSONObject()
            .put("sampleEpochMs", 2_000L)
            .put("sampleAgeMs", 500L)
            .put("stale", false)

        assertTrue(usableLiteRealtimeSample(sample, 1_000L))
        assertFalse(usableLiteRealtimeSample(sample, 2_000L))
        assertFalse(usableLiteRealtimeSample(sample.put("stale", true), 1_000L))
        assertFalse(usableLiteRealtimeSample(sample.put("stale", false).put("sampleAgeMs", 15_000L), 1_000L))
    }
}
