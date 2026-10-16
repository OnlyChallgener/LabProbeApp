package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeFreshnessTrackerTest {
    @Test
    fun wallEpochIsOnlyAnOrderingKey() {
        val tracker = RealtimeFreshnessTracker()
        assertTrue(tracker.accept(9_999_999_999_999L, 9_901L, 50L))
        assertEquals(9_951L, tracker.deadlineElapsedMs)
        assertEquals(1L, tracker.remainingMs(9_950L))
        assertFalse(tracker.isFresh(9_951L))
    }

    @Test
    fun repeatedSameEpochWithResetAgeCannotExtendItsDeadline() {
        val tracker = RealtimeFreshnessTracker()
        assertTrue(tracker.accept(1_000L, 9_901L, 1_000L))
        for (now in listOf(3_000L, 5_000L, 7_000L, 9_000L)) {
            assertTrue(tracker.accept(1_000L, 9_901L, now))
            assertEquals(10_901L, tracker.deadlineElapsedMs)
        }
        assertFalse(tracker.accept(1_000L, 9_901L, 10_901L))
        assertFalse(tracker.accept(1_000L, 9_901L, 15_000L))
        assertEquals(0L, tracker.remainingMs(15_000L))
    }

    @Test
    fun olderWssCannotRenewTheNewerDisplayedHttpSample() {
        val tracker = RealtimeFreshnessTracker()
        assertTrue(tracker.accept(12_000L, 5_001L, 1_000L))
        assertTrue(tracker.accept(11_500L, 9_901L, 3_000L))
        assertEquals(12_000L, tracker.epoch)
        assertEquals(6_001L, tracker.deadlineElapsedMs)
        assertFalse(tracker.accept(11_500L, 9_901L, 6_001L))
    }

    @Test
    fun olderSameEpochReportsCanShortenButNotLengthenTheBudget() {
        val tracker = RealtimeFreshnessTracker()
        assertTrue(tracker.accept(1_000L, 10_001L, 1_000L))
        assertTrue(tracker.accept(1_000L, 501L, 3_000L))
        assertEquals(3_501L, tracker.deadlineElapsedMs)
        assertTrue(tracker.accept(1_000L, 10_001L, 3_100L))
        assertEquals(3_501L, tracker.deadlineElapsedMs)
    }

    @Test
    fun aGenuinelyNewSampleRestoresFreshnessAfterExpiry() {
        val tracker = RealtimeFreshnessTracker()
        assertTrue(tracker.accept(1_000L, 1L, 1_000L))
        assertFalse(tracker.isFresh(1_001L))
        assertTrue(tracker.accept(2_000L, 10_001L, 8_000L))
        assertEquals(2_000L, tracker.epoch)
        assertEquals(18_001L, tracker.deadlineElapsedMs)
    }

    @Test
    fun resetAndInvalidSamplesDoNotCreateFreshReadings() {
        val tracker = RealtimeFreshnessTracker()
        assertFalse(tracker.accept(0L, 10_001L, 1_000L))
        assertFalse(tracker.accept(1_000L, 0L, 1_000L))
        assertFalse(tracker.accept(1_000L, 10_001L, -1L))
        assertTrue(tracker.accept(1_000L, 10_001L, 1_000L))
        tracker.reset()
        assertEquals(0L, tracker.epoch)
        assertEquals(0L, tracker.deadlineElapsedMs)
        assertFalse(tracker.isFresh(1_000L))
        assertTrue(tracker.accept(500L, 10_001L, 2_000L))
    }
}
