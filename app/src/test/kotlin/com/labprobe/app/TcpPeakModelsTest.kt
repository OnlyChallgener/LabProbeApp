package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpPeakModelsTest {
    @Test
    fun relayPayloadWithMissingFieldsBecomesBoundedChineseState() {
        val snapshot = TcpPeakSnapshot.fromRelayJson(
            JSONObject().put("task", JSONObject().put("id", "relay-1").put("state", "running"))
        )

        assertTrue(snapshot.active)
        assertEquals("测试中", snapshot.status)
        assertEquals(0, snapshot.ipv4.current)
        assertFalse(snapshot.resourcesReleased)
    }

    @Test
    fun trendReplacesSameSecondAndKeepsBoundedWindow() {
        val running = TcpPeakSnapshot(
            state = "running",
            ipv4 = TcpPeakMetric(current = 10),
            ipv6 = TcpPeakMetric(current = 20)
        )
        val first = appendTcpPeakTrend(emptyList(), running, 1_000L, maximumPoints = 3)
        val sameSecond = appendTcpPeakTrend(first, running.copy(ipv4 = TcpPeakMetric(current = 11)), 1_900L, maximumPoints = 3)
        var current = sameSecond
        repeat(5) { index -> current = appendTcpPeakTrend(current, running, (index + 2L) * 1_000L, maximumPoints = 3) }

        assertEquals(11, sameSecond.single().ipv4Current)
        assertEquals(3, current.size)
    }

    @Test
    fun failureGuardStopsAtConsecutiveLimitAndBoundsPending() {
        val outcomes = List(TCP_PEAK_CONSECUTIVE_FAILURE_LIMIT) { false }

        assertEquals(
            "连续连接失败 200 次，停止新增连接",
            tcpPeakFailureStopReason(TCP_PEAK_CONSECUTIVE_FAILURE_LIMIT, outcomes, 500L)
        )
        assertEquals(0, tcpPeakRemainingFailureBudget(150, 50))
        assertEquals(80, tcpPeakRemainingFailureBudget(40, 80))
    }

    @Test
    fun failureGuardConfirmsPlatformOnlyWithRateStreakAndNoGrowth() {
        val poorWindow = List(20) { true } + List(80) { false }

        assertEquals(
            "连接成功率持续过低，已确认增长平台",
            tcpPeakFailureStopReason(50, poorWindow, 3_000L)
        )
        assertEquals(null, tcpPeakFailureStopReason(49, poorWindow, 3_000L))
        assertEquals(null, tcpPeakFailureStopReason(50, poorWindow, 2_999L))
    }

    @Test
    fun historyOnlyRetainsFourteenDays() {
        val now = 20L * 24L * 60L * 60L * 1_000L
        fun row(id: String, ageDays: Long) = TcpPeakHistory(
            id = id,
            startedEpochMs = now - ageDays * 24L * 60L * 60L * 1_000L,
            side = TcpPeakSide.APP,
            host = "example.com",
            port = 443,
            family = TcpPeakFamily.BOTH,
            snapshot = TcpPeakSnapshot(state = "completed")
        )
        val encoded = encodeTcpPeakHistory(listOf(row("new", 2), row("old", 15)), now)
        val parsed = parseTcpPeakHistory(encoded, now)

        assertEquals(listOf("new"), parsed.map(TcpPeakHistory::id))
    }

    @Test
    fun commonTargetConfigKeepsRelayLimitAsMeasurementRange() {
        val config = TcpPeakConfig(
            side = TcpPeakSide.RELAY,
            host = "[240e::1]",
            port = 443,
            targetConnections = 99_999,
            cps = 9_999
        ).normalized()

        assertEquals("240e::1", config.host)
        assertEquals(65_535, config.targetConnections)
        assertEquals(2_000, config.cps)
    }

    @Test
    fun aiCommandIsOneShotBoundedAndExpires() {
        val now = 1_000_000L
        val command = TcpPeakPendingAiCommand(
            id = "confirm-1",
            config = TcpPeakConfig(
                side = TcpPeakSide.APP,
                host = "example.com",
                port = 443,
                family = TcpPeakFamily.IPV4,
                targetConnections = 2_000,
                cps = 200,
            ),
            expiresAtEpochMs = now + 60_000L,
        )

        assertEquals(command.config, TcpPeakPendingAiCommand.fromJson(command.toJson(), now)?.config)
        assertEquals(null, TcpPeakPendingAiCommand.fromJson(command.toJson(), now + 60_001L))
    }
}
