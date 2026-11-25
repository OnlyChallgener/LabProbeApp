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
            targetConnections = 150_000,
            cps = 9_999
        ).normalized()

        assertEquals("240e::1", config.host)
        assertEquals(131_072, config.targetConnections)
        assertEquals(2_000, config.cps)
    }

    @Test
    fun extremeModeRaisesCpsCeilingWhileSafeModeStaysBounded() {
        val safe = TcpPeakConfig(host = "example.com", cps = 9_999)
        val extreme = safe.copy(extremeMode = true)

        assertEquals("CPS 必须是 1–2000", safe.validationError())
        assertEquals(2_000, safe.normalized().cps)
        assertEquals(null, extreme.validationError())
        assertEquals(9_999, extreme.normalized().cps)
        assertEquals(10_000, extreme.copy(cps = 99_999).normalized().cps)
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
                extremeMode = true,
            ),
            expiresAtEpochMs = now + 60_000L,
        )

        assertEquals(command.config, TcpPeakPendingAiCommand.fromJson(command.toJson(), now)?.config)
        assertEquals(null, TcpPeakPendingAiCommand.fromJson(command.toJson(), now + 60_001L))
    }

    @Test
    fun wanIdDefaultsEmptyAndNormalizesWhitespaceAndCase() {
        assertEquals("", TcpPeakConfig(host = "example.com").wanId)
        val raw = TcpPeakConfig(host = "example.com", wanId = " WAN1\t")

        assertEquals(null, raw.validationError())
        assertEquals("wan1", raw.normalized().wanId)
    }

    @Test
    fun wanIdAcceptsStableIdsAndRejectsUnstableValues() {
        val valid = listOf("a".repeat(64), "wan.1")
        val invalid = listOf("-wan1", "_wan1", "wan/1", "wan:1", "wan 1", "../wan1", "线路1", "a".repeat(65))

        valid.forEach { wanId ->
            assertEquals(null, TcpPeakConfig(host = "example.com", wanId = wanId).validationError())
        }
        invalid.forEach { wanId ->
            assertEquals("线路 ID 格式无效", TcpPeakConfig(host = "example.com", wanId = wanId).validationError())
        }
    }

    @Test
    fun pendingAiCommandJsonRoundTripPreservesWanIdAndAcceptsLegacyMissingValue() {
        val now = 1_000_000L
        val command = TcpPeakPendingAiCommand(
            id = "confirm-wan",
            config = TcpPeakConfig(host = "example.com", wanId = "wan2").normalized(),
            expiresAtEpochMs = now + 60_000L,
        )
        val json = command.toJson()

        assertEquals("wan2", JSONObject(json).getString("wanId"))
        assertEquals("wan2", TcpPeakPendingAiCommand.fromJson(json, now)?.config?.wanId)
        val legacy = JSONObject(json).apply { remove("wanId") }
        assertEquals("", TcpPeakPendingAiCommand.fromJson(legacy.toString(), now)?.config?.wanId)
    }

    @Test
    fun relayStartJsonAlwaysCarriesNormalizedWanIdIncludingLegacyEmptyValue() {
        val selected = TcpPeakConfig(host = "example.com", wanId = " WAN2 ").toRelayStartJson()
        val legacy = TcpPeakConfig(host = "example.com").toRelayStartJson()

        assertTrue(selected.has("wanId"))
        assertEquals("wan2", selected.getString("wanId"))
        assertTrue(legacy.has("wanId"))
        assertEquals("", legacy.getString("wanId"))
    }

    @Test
    fun relaySnapshotJsonPreservesWanIdAndLegacyEmptyValue() {
        val snapshot = TcpPeakSnapshot.fromRelayJson(
            JSONObject().put(
                "task",
                JSONObject().put("id", "relay-wan2").put("state", "running").put("wanId", "wan2")
            )
        )
        val legacy = TcpPeakSnapshot.fromRelayJson(
            JSONObject().put("task", JSONObject().put("id", "legacy").put("state", "running"))
        )

        assertEquals("wan2", snapshot.wanId)
        assertEquals("", legacy.wanId)
    }

    @Test
    fun snapshotPollingRetainsKnownWanIdWithoutOverwritingReportedValue() {
        val previous = TcpPeakSnapshot(taskId = "relay-1", side = TcpPeakSide.RELAY, wanId = "wan2")

        assertEquals("wan2", previous.copy(wanId = "").withRetainedWanId("wan2").wanId)
        assertEquals("wan1", previous.copy(wanId = "").withRetainedWanId("wan1").wanId)
        assertEquals("", previous.copy(wanId = "").withRetainedWanId("").wanId)
        assertEquals("wan1", previous.copy(wanId = "wan1").withRetainedWanId("wan2").wanId)
    }

    @Test
    fun historyJsonRoundTripPreservesSnapshotWanIdAndLegacyEmptyValue() {
        val now = 1_000_000L
        val history = TcpPeakHistory(
            id = "relay-wan2",
            startedEpochMs = now,
            side = TcpPeakSide.RELAY,
            host = "example.com",
            port = 443,
            family = TcpPeakFamily.BOTH,
            snapshot = TcpPeakSnapshot(
                taskId = "relay-wan2",
                side = TcpPeakSide.RELAY,
                wanId = "wan2",
                state = "completed",
            ),
        )
        val json = history.toJson()

        assertEquals("wan2", json.getJSONObject("snapshot").getString("wanId"))
        assertEquals("wan2", TcpPeakHistory.fromJson(json)?.snapshot?.wanId)
        val legacy = JSONObject(json.toString()).apply { getJSONObject("snapshot").remove("wanId") }
        assertEquals("", TcpPeakHistory.fromJson(legacy)?.snapshot?.wanId)
    }

    @Test
    fun relayWanSelectionPrefersDefaultEgressAndKeepsExplicitSelection() {
        val wans = listOf(
            WanInfo(wanId = "wan1", configured = true),
            WanInfo(wanId = "wan2", configured = true, defaultEgress = true),
        )

        val defaultSelection = tcpPeakWanSelectionState(TcpPeakSide.RELAY, "", wans)
        val explicitSelection = tcpPeakWanSelectionState(TcpPeakSide.RELAY, " WAN1 ", wans)
        assertEquals("wan2", defaultSelection.selectedWanId)
        assertEquals(listOf("wan1", "wan2"), defaultSelection.options.map(WanInfo::wanId))
        assertTrue(defaultSelection.showSelector)
        assertEquals("wan1", explicitSelection.selectedWanId)
    }

    @Test
    fun unknownCurrentWanIsPreservedForLegacyRecords() {
        val state = tcpPeakWanSelectionState(
            TcpPeakSide.RELAY,
            "legacy0",
            listOf(WanInfo(wanId = "wan", configured = true)),
        )

        assertEquals("legacy0", state.selectedWanId)
        assertEquals(listOf("wan", "legacy0"), state.options.map(WanInfo::wanId))
        assertTrue(state.showSelector)
    }

    @Test
    fun relayWanSelectionFallsBackToFirstConfiguredWan() {
        val wans = listOf(
            WanInfo(wanId = "wan1", configured = false),
            WanInfo(wanId = "wan2", configured = true),
            WanInfo(wanId = "wan3", configured = true),
        )

        val state = tcpPeakWanSelectionState(TcpPeakSide.RELAY, "", wans)

        assertEquals("wan2", state.selectedWanId)
        assertEquals(listOf("wan2", "wan3"), state.options.map(WanInfo::wanId))
        assertTrue(state.showSelector)
    }

    @Test
    fun singleConfiguredWanIsSelectedAutomaticallyWithoutSelector() {
        val wans = listOf(
            WanInfo(wanId = "wan1", configured = true),
            WanInfo(wanId = "wan2", configured = false),
            WanInfo(wanId = "bad/id", configured = true),
        )

        val state = tcpPeakWanSelectionState(TcpPeakSide.RELAY, "", wans)

        assertEquals("wan1", state.selectedWanId)
        assertEquals(listOf("wan1"), state.options.map(WanInfo::wanId))
        assertFalse(state.showSelector)
    }

    @Test
    fun appSideNeverSelectsWanAndLegacyHubWithoutWansStaysEmpty() {
        val wans = listOf(
            WanInfo(wanId = "wan1", configured = true),
            WanInfo(wanId = "wan2", configured = true, defaultEgress = true),
        )

        val app = tcpPeakWanSelectionState(TcpPeakSide.APP, "wan1", wans)
        val legacyHub = tcpPeakWanSelectionState(TcpPeakSide.RELAY, "", emptyList())
        assertEquals("", app.selectedWanId)
        assertTrue(app.options.isEmpty())
        assertFalse(app.showSelector)
        assertEquals("", legacyHub.selectedWanId)
        assertTrue(legacyHub.options.isEmpty())
        assertFalse(legacyHub.showSelector)
    }

    @Test
    fun wanLabelIsEmptyForLegacySnapshotsAndIdentifiesConfiguredLine() {
        assertEquals("", tcpPeakWanLabel(""))
        assertEquals("", tcpPeakWanLabel("  "))
        assertEquals("线路 WAN2", tcpPeakWanLabel("wan2"))
    }
}
