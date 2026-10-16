package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouterDiagnosisEvidenceTest {
    private val online = RouterHubStatus(state = "connected", connected = true, sessionConnected = true, dataAvailable = true)
    private fun sample(age: Long = 250L) = JSONObject()
        .put("sampleEpochMs", 1_000L).put("sampleAgeMs", age).put("stale", false)

    @Test fun freshHubSampleIsHealthyDespiteDifferentPhoneWallClock() {
        val item = routerDiagnosisItem(online, sample(), false, checkedAt = 1_790_000_000_000L)
        assertEquals(HealthStatus.GOOD, item.status)
        assertEquals("在线", item.metric)
    }

    @Test fun unavailableOrExpiredEvidenceDoesNotBecomeRouterFault() {
        val incomplete = listOf(null, sample(10_001L), sample().put("stale", true), sample().apply { remove("sampleAgeMs") })
        incomplete.forEach { frame ->
            val item = routerDiagnosisItem(online, frame, false)
            assertEquals(HealthStatus.UNKNOWN, item.status)
            val result = DiagnosisRules.summarize(listOf(item), 42L)
            assertEquals(HealthStatus.UNKNOWN, result.status)
            assertFalse(result.conclusion.contains("Router 状态异常"))
        }
        assertEquals(HealthStatus.UNKNOWN, routerDiagnosisItem(online.copy(dataAvailable = false), sample(), false).status)
    }

    @Test fun disconnectedManagementSessionStillNeedsAttention() {
        val item = routerDiagnosisItem(RouterHubStatus(state = "router_login_failed"), sample(), false)
        assertEquals(HealthStatus.WARNING, item.status)
        assertTrue(DiagnosisRules.summarize(listOf(item), 42L).conclusion.contains("Router 状态异常"))
    }

    @Test fun be50RequiresFreshAgentEvidence() {
        assertEquals(HealthStatus.UNKNOWN, routerDiagnosisItem(online, sample(), true).status)
        assertEquals(HealthStatus.GOOD, routerDiagnosisItem(online, sample().put("source", "agent_dashboard_push"), true).status)
        assertEquals(HealthStatus.UNKNOWN, routerDiagnosisItem(online, sample(10_001L).put("source", "agent_dashboard_push"), true).status)
    }
}
