package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisRulesTest {
    @Test
    fun unknownEvidenceIsNotReportedAsHealthy() {
        val result = summarize(emptyMap())

        assertEquals(HealthStatus.UNKNOWN, result.status)
        assertTrue(result.conclusion.startsWith("检测证据尚不完整"))
        assertTrue(result.conclusion.contains("另有 9 项未确认或未启用"))
    }

    @Test
    fun healthyCoreIsGoodWhenOptionalChecksAreUnconfigured() {
        val result = summarize(
            mapOf(
                DiagnosisCheck.GATEWAY to HealthStatus.GOOD,
                DiagnosisCheck.INTERNET to HealthStatus.GOOD,
                DiagnosisCheck.DNS to HealthStatus.GOOD,
            ),
        )

        assertEquals(HealthStatus.GOOD, result.status)
        assertEquals(
            "局域网、互联网和 DNS 检测正常。 另有 6 项未确认或未启用，未据此判定故障。",
            result.conclusion,
        )
    }

    @Test
    fun healthyGatewayAndPoorInternetExplainInternetQuality() {
        val result = summarize(
            mapOf(
                DiagnosisCheck.GATEWAY to HealthStatus.GOOD,
                DiagnosisCheck.INTERNET to HealthStatus.WARNING,
            ),
        )

        assertEquals(HealthStatus.WARNING, result.status)
        assertTrue(result.conclusion.startsWith("局域网正常，互联网连接质量存在异常。"))
    }

    @Test
    fun healthyRouterAndPoorIpv6ExplainIpv6Availability() {
        val result = summarize(
            mapOf(
                DiagnosisCheck.ROUTER to HealthStatus.GOOD,
                DiagnosisCheck.GATEWAY to HealthStatus.GOOD,
                DiagnosisCheck.INTERNET to HealthStatus.GOOD,
                DiagnosisCheck.DNS to HealthStatus.GOOD,
                DiagnosisCheck.IPV6 to HealthStatus.WARNING,
            ),
        )

        assertEquals(HealthStatus.WARNING, result.status)
        assertTrue(result.conclusion.startsWith("Router 在线，但手机所在网络的 IPv6 可用性存在异常。"))
    }

    @Test
    fun severityUsesErrorThenWarningThenHealthyCoreThenUnknown() {
        assertEquals(
            HealthStatus.ERROR,
            summarize(mapOf(DiagnosisCheck.GATEWAY to HealthStatus.ERROR)).status,
        )
        assertEquals(
            HealthStatus.ERROR,
            summarize(mapOf(DiagnosisCheck.RELAY to HealthStatus.ERROR)).status,
        )
        assertEquals(
            HealthStatus.WARNING,
            summarize(mapOf(DiagnosisCheck.STUN to HealthStatus.WARNING)).status,
        )
        assertEquals(
            HealthStatus.GOOD,
            summarize(
                mapOf(
                    DiagnosisCheck.GATEWAY to HealthStatus.GOOD,
                    DiagnosisCheck.INTERNET to HealthStatus.GOOD,
                    DiagnosisCheck.DNS to HealthStatus.GOOD,
                ),
            ).status,
        )
        assertEquals(HealthStatus.UNKNOWN, summarize(emptyMap()).status)
    }

    @Test
    fun conclusionPrefersGatewayAndInternetBeforeOptionalFailures() {
        val internetFailure = summarize(
            mapOf(
                DiagnosisCheck.GATEWAY to HealthStatus.GOOD,
                DiagnosisCheck.INTERNET to HealthStatus.ERROR,
                DiagnosisCheck.DNS to HealthStatus.ERROR,
                DiagnosisCheck.RELAY to HealthStatus.ERROR,
            ),
        )
        val gatewayFailure = summarize(
            mapOf(
                DiagnosisCheck.GATEWAY to HealthStatus.ERROR,
                DiagnosisCheck.INTERNET to HealthStatus.ERROR,
                DiagnosisCheck.DNS to HealthStatus.ERROR,
            ),
        )

        assertTrue(internetFailure.conclusion.startsWith("局域网正常，但手机到互联网的连接存在异常。"))
        assertTrue(gatewayFailure.conclusion.startsWith("手机到默认网关的连接存在异常"))
    }

    @Test
    fun gatewayWarningConclusionTakesPriorityOverOptionalFailure() {
        val result = summarize(
            mapOf(
                DiagnosisCheck.GATEWAY to HealthStatus.WARNING,
                DiagnosisCheck.RELAY to HealthStatus.WARNING,
            ),
        )

        assertEquals(HealthStatus.WARNING, result.status)
        assertTrue(result.conclusion.startsWith("默认网关响应需要关注"))
    }

    private fun summarize(statuses: Map<DiagnosisCheck, HealthStatus>): DiagnosisResult {
        val items = statuses.map { (check, status) -> DiagnosisItem(check = check, status = status) }
        return DiagnosisRules.summarize(items, completedAt = 42L)
    }
}
