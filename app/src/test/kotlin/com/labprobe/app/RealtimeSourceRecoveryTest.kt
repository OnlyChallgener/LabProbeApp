package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeSourceRecoveryTest {
    private fun sample(epoch: Long, cpu: Int, age: Long = 500L): JSONObject = JSONObject()
        .put("sampleEpochMs", epoch).put("sampleAgeMs", age).put("stale", false).put("cpuPercent", cpu)

    @Test
    fun freshWssBehindHttpRecoversWithoutRollingBackDisplayedCpu() {
        val smoother = RealtimeDisplaySmoother()
        val http = sample(10_000L, 30)
        smoother.acceptRouter(http, now = 1000L)
        val current = requireNotNull(smoother.renderRouter(null, now = 1000L))
        val wss = sample(9_500L, 10)

        assertEquals(10_000L, recoveredWssFreshnessEpoch(wss, 8_000L, 10_000L))
        smoother.acceptRouter(wss, now = 1500L)
        val after = smoother.renderRouter(current, now = 1500L) ?: current
        assertEquals(30.0, routerStatusTelemetry(after).cpuPercent, 0.001)
        assertEquals(10_000L, routerStatusTelemetry(after).sampleEpochMs)
    }

    @Test
    fun consecutiveSlightlyLateWssFramesCanAllRestoreTheMainStream() {
        var previousWss = 8_000L
        for (httpEpoch in listOf(10_000L, 12_000L, 14_000L)) {
            val wssEpoch = httpEpoch - 500L
            assertEquals(httpEpoch, recoveredWssFreshnessEpoch(sample(wssEpoch, 20), previousWss, httpEpoch))
            previousWss = wssEpoch
        }
    }

    @Test
    fun staleOrOutOfOrderWssCannotDeclareRecovery() {
        assertNull(recoveredWssFreshnessEpoch(sample(9_000L, 20), 10_000L, 12_000L))
        assertNull(recoveredWssFreshnessEpoch(sample(10_000L, 20, 11_000L), 9_000L, 12_000L))
    }

    @Test
    fun nineSecondOldHttpSampleHasOnlyOneSecondOfFreshnessLeft() {
        assertEquals(1001L, remainingLiteRealtimeFreshnessMs(sample(1000L, 20, 9000L)))
        assertEquals(1L, remainingLiteRealtimeFreshnessMs(sample(1000L, 20, 9000L), 1001L))
    }
}
