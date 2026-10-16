package com.labprobe.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubRealtimeTransportPolicyTest {
    @Test
    fun healthyKeepalivesKeepTheSocketEvenWhileRouterSamplingIsDelayed() {
        // A ready session still sends keepalives; router sample age is a data
        // freshness concern and is deliberately absent from transport policy.
        assertFalse(hubRealtimeTransportTimedOut(
            openedAt = 1000L, readyAt = 1100L, lastFrameAt = 61_000L, now = 64_000L,
        ))
    }

    @Test
    fun silentTransportTimesOutAtFortyFiveSeconds() {
        assertFalse(hubRealtimeTransportTimedOut(
            openedAt = 1000L, readyAt = 1100L, lastFrameAt = 2000L, now = 46_999L,
        ))
        assertTrue(hubRealtimeTransportTimedOut(
            openedAt = 1000L, readyAt = 1100L, lastFrameAt = 2000L, now = 47_000L,
        ))
    }

    @Test
    fun missingAuthenticatedReadyTimesOutAfterTwentySecondsDespiteOtherFrames() {
        assertFalse(hubRealtimeTransportTimedOut(
            openedAt = 1000L, readyAt = 0L, lastFrameAt = 20_999L, now = 20_999L,
        ))
        assertTrue(hubRealtimeTransportTimedOut(
            openedAt = 1000L, readyAt = 0L, lastFrameAt = 21_000L, now = 21_000L,
        ))
    }
}
