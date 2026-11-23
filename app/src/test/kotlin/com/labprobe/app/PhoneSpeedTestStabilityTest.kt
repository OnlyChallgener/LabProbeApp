package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSpeedTestStabilityTest {
    @Test
    fun constantRateStopsAfterWarmupAndStableWindow() {
        val tracker = SpeedStabilityTracker()
        var last: SpeedStabilityTracker.Snapshot? = null
        var elapsed = 0L
        while (elapsed < 15_000L) {
            elapsed += 250L
            last = tracker.add(elapsed, 100.0)
            if (last.done) break
        }
        requireNotNull(last)
        assertTrue(last.done)
        assertTrue(last.stable)
        assertEquals(100.0, last.averageMbps, 0.01)
        assertEquals(100.0, last.peakMbps, 0.01)
        assertTrue(elapsed >= 9_000L)
    }

    @Test
    fun warmupSamplesAreExcludedFromTheReportedAverage() {
        val tracker = SpeedStabilityTracker()
        var last: SpeedStabilityTracker.Snapshot? = null
        var elapsed = 0L
        while (elapsed < 15_000L) {
            elapsed += 250L
            last = tracker.add(elapsed, if (elapsed <= 2_000L) 1_000.0 else 100.0)
            if (last.done) break
        }
        requireNotNull(last)
        assertTrue(last.stable)
        assertEquals(100.0, last.averageMbps, 0.01)
        assertEquals(100.0, last.peakMbps, 0.01)
    }

    @Test
    fun noisyRateRunsToTheLimitAndIsNotMarkedStable() {
        val tracker = SpeedStabilityTracker()
        var last: SpeedStabilityTracker.Snapshot? = null
        var elapsed = 0L
        var flip = false
        while (elapsed < 30_000L) {
            elapsed += 250L
            flip = !flip
            last = tracker.add(elapsed, if (flip) 120.0 else 20.0)
            if (last.done) break
        }
        requireNotNull(last)
        assertTrue(last.done)
        assertFalse(last.stable)
        assertTrue(last.reason.contains("时限"))
    }
}