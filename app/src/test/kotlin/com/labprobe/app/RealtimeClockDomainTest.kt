package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeClockDomainTest {
    private fun sample(epoch: Long, age: Long = 0L): JSONObject = JSONObject()
        .put("sampleEpochMs", epoch).put("sampleAgeMs", age).put("stale", false)

    @Test
    fun realBe72AndBe50PushesPassDespiteClientClockBeingSlightlyBehind() {
        // Actual 2026-10-01 frames: old cross-device subtraction was -140/-102 ms.
        for ((epoch, age, clientWallClock) in listOf(
            Triple(1_790_813_850_415L, 1L, 1_790_813_850_275L),
            Triple(1_790_813_852_000L, 50L, 1_790_813_851_898L),
        )) {
            assertTrue(clientWallClock - epoch < 0L)
            val frame = sample(epoch, age)
            assertTrue(freshLiteRealtimeSample(frame))
            assertEquals(epoch, recoveredWssFreshnessEpoch(frame, 0L, 0L))
        }
    }

    @Test
    fun clientWallClockOffsetAndAdjustmentsCannotChangeReceiptFreshness() {
        val epoch = 1_790_813_850_000L
        val frame = sample(epoch, 250L)
        for (wallClock in listOf(epoch - 86_400_000L, epoch - 1_000L, epoch + 11_000L, epoch + 86_400_000L)) {
            assertTrue(wallClock != epoch)
            assertTrue(freshLiteRealtimeSample(frame, elapsedSinceReceiptMs = 100L))
            assertEquals(9651L, remainingLiteRealtimeFreshnessMs(frame, elapsedSinceReceiptMs = 100L))
        }
    }

    @Test
    fun hubAgeAndMonotonicDispatchDelayRetainExactExpiryBoundary() {
        val frame = sample(1000L, 9000L)
        assertTrue(freshLiteRealtimeSample(frame, 1000L))
        assertFalse(freshLiteRealtimeSample(frame, 1001L))
        assertEquals(1L, remainingLiteRealtimeFreshnessMs(frame, 1000L))
        val aged = requireNotNull(agedLiteRealtimeSample(frame, 750L))
        assertEquals(9750L, aged.getLong("sampleAgeMs"))
        assertEquals(9000L, frame.getLong("sampleAgeMs"))
        assertEquals(251L, remainingLiteRealtimeFreshnessMs(aged))
        assertNull(agedLiteRealtimeSample(frame, 1001L))
    }

    @Test
    fun invalidOrServerStaleSamplesRemainRejectedWithoutClientWallClock() {
        val invalid = listOf(
            sample(0L), sample(1000L, -1L), sample(1000L, 10_001L),
            sample(1000L).put("stale", true), sample(1000L).put("sampleAgeMs", JSONObject.NULL),
            sample(1000L).also { it.remove("sampleAgeMs") },
        )
        invalid.forEach { assertFalse(freshLiteRealtimeSample(it)) }
        assertFalse(freshLiteRealtimeSample(sample(1000L), -1L))
        assertFalse(freshLiteRealtimeSample(sample(1000L), Long.MAX_VALUE))
    }
}
