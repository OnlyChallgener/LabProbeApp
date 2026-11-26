package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterSpeedTestLogicTest {
    private fun sample(
        down: List<Double> = listOf(587.07),
        up: List<Double> = emptyList()
    ) = SpeedSampleSet(
        intf = "wan",
        down = down,
        up = up,
        currentDown = down.lastOrNull(),
        currentUp = up.lastOrNull(),
        latency = 1.68,
        jitter = 0.23,
        loss = 0.0
    )

    @Test
    fun collapsedCardStillNamesTheLastResult() {
        // 折叠着也不能看着像「不能测速」：上次结果要写在标题下面那一行。
        assertEquals("上次下行 575.41 Mbps · 时延 1.61 ms",
            speedTestCollapsedSummary(
                SpeedProgress(stat = "end", finished = true,
                    primary = sample(down = listOf(575.41)).copy(latency = 1.61)),
                running = false
            ))
        assertEquals("测速进行中…",
            speedTestCollapsedSummary(SpeedProgress("running", false, sample()), running = true))
        assertEquals("路由器直连宽带测速 · 节点可选",
            speedTestCollapsedSummary(null, running = false))
        assertEquals("上次下行 400.00 Mbps · 时延 1.68 ms",
            speedTestCollapsedSummary(
                SpeedProgress(stat = "end", finished = true,
                    primary = sample(down = listOf(100.0, 200.0, 900.0))),
                running = false
            ))
    }

    @Test
    fun gaugePositionUsesUniformDialSegmentsForOfficialSpeedThresholds() {

        assertEquals(0f, gaugePosition(-1f), 0f)
        assertEquals(1f / 16f, gaugePosition(5f), 0.001f)
        assertEquals(1f / 8f, gaugePosition(10f), 0.001f)
        assertEquals(2f / 8f, gaugePosition(50f), 0.001f)
        assertEquals(3f / 8f, gaugePosition(100f), 0.001f)
        assertEquals(4f / 8f, gaugePosition(500f), 0.001f)
        assertEquals(5f / 8f, gaugePosition(1000f), 0.001f)
        assertEquals(6f / 8f, gaugePosition(1500f), 0.001f)
        assertEquals(7f / 8f, gaugePosition(2000f), 0.001f)
        assertEquals(1f, gaugePosition(2500f), 0f)
        assertEquals(1f, gaugePosition(3000f), 0f)
    }

    @Test
    fun uploadPhaseNeedsLiveUploadSamplesAndNotTerminalProgress() {
        val upload = SpeedProgress(
            stat = "running",
            finished = false,
            primary = sample(up = listOf(49.23))
        )
        assertTrue(isSpeedTestUploadPhase(running = true, progress = upload))
        assertFalse(isSpeedTestUploadPhase(running = false, progress = upload))
        assertFalse(
            isSpeedTestUploadPhase(
                running = true,
                progress = upload.copy(stat = "end", finished = true)
            )
        )
        assertFalse(
            isSpeedTestUploadPhase(
                running = true,
                progress = SpeedProgress("running", false, sample())
            )
        )
        assertFalse(
            isSpeedTestUploadPhase(
                running = true,
                progress = upload.copy(stat = "idle")
            )
        )
    }

    @Test
    fun collapsedHistoryHasNoRowsAndExpandedHistoryIsCapped() {
        val history = (1L..12L).map { epoch ->
            SpeedHistoryRecord(epoch, "wan", 587.07, 49.23, 1.68)
        }
        assertTrue(visibleSpeedHistory(history, expanded = false).isEmpty())
        val expanded = visibleSpeedHistory(history, expanded = true)
        assertEquals(10, expanded.size)
        assertEquals(history.take(10), expanded)
    }

    @Test
    fun speedUnitFormatterKeepsNumericAndMissingValuesExplicit() {
        assertEquals("587.07 Mbps", formatSpeedWithUnit(587.07))
        assertEquals("-- Mbps", formatSpeedWithUnit(null))
    }

    @Test
    fun trendAxisIgnoresTheStartupSpikeInsteadOfFlatteningTheCurve() {
        // 固件第一个点是刚起测的瞬时尖峰（实测 1840.90），按峰值定上限会把几百兆的
        // 正常段压成一条贴底的直线；按 P90 取档后尖峰只留在顶部标签里。
        val firmware = listOf(1840.9) + List(80) { 580.0 }
        assertEquals(800.0, speedAxisMax(firmware), 0.001)
        assertEquals(150.0, speedAxisMax(listOf(120.0)), 0.001)
        assertEquals(1.2, speedAxisMax(listOf(1.0)), 0.001)
        assertEquals(10.0, speedAxisMax(emptyList()), 0.001)
        assertEquals(10.0, speedAxisMax(listOf(Double.NaN)), 0.001)
    }

    @Test
    fun timeAxisFollowsTheSamplingClockNotThePointCount() {
        // 手机每 250ms 一个采样点：27 个点只过了 6.75 秒，以前会标成「27s」。
        assertEquals(6_750L, speedSeriesSpanMs(List(27) { 1.0 }, PHONE_SPEED_SAMPLE_MS))
        assertEquals("7s", formatSpeedSpan(6_750L))
        // 固件每 100ms 一个点（每秒 10 个）：APP 每秒轮询一次，数组多 10 个点时
        // 时间轴只能多 1 秒。81 个点是一条 8.1 秒的曲线，不是 81 秒。
        assertEquals(100L, ROUTER_SPEED_SAMPLE_MS)
        assertEquals(8_100L, speedSeriesSpanMs(List(81) { 1.0 }, ROUTER_SPEED_SAMPLE_MS))
        assertEquals("8s", formatSpeedSpan(8_100L))
        assertEquals(1_000L, speedSeriesSpanMs(List(10) { 1.0 }, ROUTER_SPEED_SAMPLE_MS))
        assertEquals(0L, speedSeriesSpanMs(emptyList(), ROUTER_SPEED_SAMPLE_MS))
    }

    @Test
    fun averageCoversEveryReportedSampleAndSurvivesEmptySeries() {
        assertEquals(2.0, speedSeriesAverage(listOf(1.0, 2.0, 3.0))!!, 0.001)
        assertEquals(null, speedSeriesAverage(emptyList()))
        assertEquals(null, speedSeriesAverage(listOf(Double.NaN, -1.0)))
    }

    @Test
    fun firmwareFinalScoreUsesSeriesAverageInsteadOfTailSample() {
        val series = listOf(100.0, 200.0, 900.0)
        assertEquals(400.0, speedSeriesAverage(series)!!, 0.001)
        assertFalse(series.last() == speedSeriesAverage(series))
    }

    @Test
    fun routerPollingStopsOnFailureLimitAndDeadline() {
        assertEquals("finished", routerSpeedPollStopReason("end", 0, 0L))
        assertEquals("固件测速失败，请重试", routerSpeedPollStopReason("failed", 0, 0L))
        assertEquals("测速状态读取连续失败，请重试", routerSpeedPollStopReason("running", ROUTER_SPEED_POLL_FAILURE_LIMIT, 0L))
        assertEquals("测速状态读取超时，请重试", routerSpeedPollStopReason("running", 0, ROUTER_SPEED_POLL_TIMEOUT_MS))
        assertEquals(null, routerSpeedPollStopReason("running", ROUTER_SPEED_POLL_FAILURE_LIMIT - 1, ROUTER_SPEED_POLL_TIMEOUT_MS - 1))
    }

    @Test
    fun progressParsingPreservesEveryInterfaceAndHubOrder() {
        val wan1 = JSONObject()
            .put("intf", "wan1")
            .put("downspeed", JSONArray().put(200).put(220))
            .put("upspeed", JSONArray().put(20))
            .put("currentDown", 220)
            .put("currentUp", 20)
            .put("latency", 2.5)
            .put("jitter", 0.4)
            .put("loss", 0.1)
        val wan = JSONObject()
            .put("intf", "wan")
            .put("downspeed", JSONArray().put(500).put(510))
            .put("upspeed", JSONArray())
            .put("currentDown", 510)
            .put("latency", 1.5)
        val progress = speedTestProgressFrom(
            JSONObject()
                .put("stat", "running")
                .put("primary", wan1)
                .put("interfaces", JSONArray().put(wan1).put(wan))
        )

        assertEquals(listOf("wan1", "wan"), speedTestSamples(progress).map { it.intf })
        assertEquals("wan1", progress.primary?.intf)
        assertEquals(listOf(200.0, 220.0), speedTestSamples(progress)[0].down)
        assertEquals(1.5, speedTestSamples(progress)[1].latency!!, 0.001)
        // Upload phase belongs to WAN1 only; WAN has not entered its upload phase.
        assertTrue(isSpeedTestUploadPhase(true, progress, "wan1"))
        assertFalse(isSpeedTestUploadPhase(true, progress, "wan"))
        assertEquals(
            "WAN1 ↓ 220.00 Mbps · WAN ↓ 510.00 Mbps",
            speedTestCollapsedSummary(progress, running = false)
        )
    }

    @Test
    fun portSelectionUsesWanCapabilityNotArrayPosition() {
        val ports = speedTestPortsFrom(
            JSONObject().put(
                "ports",
                JSONArray()
                    .put(JSONObject().put("name", "WAN1").put("wan", true).put("testable", true))
                    .put(JSONObject().put("name", "LAN1").put("wan", false).put("testable", false))
                    .put(JSONObject().put("name", "WAN").put("wan", true).put("testable", true))
                    .put(JSONObject().put("name", "WAN2").put("wan", true).put("testable", false))
            )
        )

        assertEquals(listOf("wan", "wan1", SPEED_TEST_BOTH_INTERFACES),
            speedTestInterfaceOptions(ports).map { it.id })
        assertEquals(listOf("wan"), speedTestSelection(ports, "wan"))
        assertEquals(listOf("wan1"), speedTestSelection(ports, "wan1"))
        assertEquals(listOf("wan", "wan1"), speedTestSelection(ports, SPEED_TEST_BOTH_INTERFACES))
        // Missing/unknown selections retain the single-WAN default.
        assertEquals(listOf("wan"), speedTestSelection(ports, "missing"))
    }

    @Test
    fun unifiedWanInventoryDrivesOptionsAndHidesUnconfiguredWan1() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(
                    wanId = "wan", logicalInterface = "wan", supported = true,
                    configured = true, enabled = true, online = true,
                    capabilities = mapOf("speedtest" to true),
                ),
                WanInfo(
                    wanId = "wan1", logicalInterface = "wan1", supported = true,
                    configured = false, enabled = false, online = false,
                    capabilities = mapOf("speedtest" to false),
                ),
            ),
            configuredCount = 1,
        )
        assertEquals(listOf("wan"), speedTestInterfaceOptions(emptyList(), inventory).map { it.id })
        assertEquals(listOf("wan"), speedTestSelection(emptyList(), "both", inventory))

        val dual = inventory.copy(
            wans = inventory.wans.map { it.copy(configured = true, enabled = true, online = true, capabilities = mapOf("speedtest" to true)) },
            configuredCount = 2,
            onlineCount = 2,
        )
        assertEquals(
            listOf("wan", "wan1", SPEED_TEST_BOTH_INTERFACES),
            speedTestInterfaceOptions(emptyList(), dual).map { it.id },
        )
        assertEquals(listOf("wan", "wan1"), speedTestSelection(emptyList(), SPEED_TEST_BOTH_INTERFACES, dual))

        val secondaryDefault = dual.copy(wans = dual.wans.map {
            it.copy(defaultEgress = it.wanId == "wan1")
        })
        assertEquals(listOf("wan", "wan1", SPEED_TEST_BOTH_INTERFACES), speedTestInterfaceOptions(emptyList(), secondaryDefault).map { it.id })
        assertEquals(listOf("wan"), speedTestSelection(emptyList(), "missing", secondaryDefault))

        val wan1Only = dual.copy(wans = dual.wans.map {
            it.copy(online = it.wanId == "wan1")
        })
        assertEquals(listOf("wan1"), speedTestInterfaceOptions(emptyList(), wan1Only).map { it.id })
        assertEquals(listOf("wan1"), speedTestSelection(emptyList(), "both", wan1Only))
    }

    @Test
    fun legacyPortsWithoutWanFlagStillFallBackToTheOnlyTestableInterface() {
        val ports = speedTestPortsFrom(
            JSONObject().put(
                "ports",
                JSONArray()
                    .put(JSONObject().put("name", "LAN1").put("testable", false))
                    .put(JSONObject().put("name", "WAN1").put("testable", true))
            )
        )
        assertTrue(ports.last().wan)
        assertEquals(listOf("wan1"), speedTestSelection(ports, "wan"))
        assertEquals(listOf("wan1"), speedTestInterfaceOptions(ports).single().interfaces)
    }

    @Test
    fun historyParsingKeepsReorderedInterfaceResultsAssociated() {
        val history = speedTestHistoryFrom(
            JSONObject().put(
                "records",
                JSONArray().put(
                    JSONObject()
                        .put("epoch", 1_789_637_285L)
                        .put(
                            "interfaces",
                            JSONArray()
                                .put(JSONObject()
                                    .put("intf", "wan1")
                                    .put("currentDown", 222.5)
                                    .put("currentUp", 21.5)
                                    .put("latency", 3.5)
                                    .put("jitter", 0.5)
                                    .put("loss", 0.2))
                                .put(JSONObject()
                                    .put("intf", "wan")
                                    .put("currentDown", 587.07)
                                    .put("currentUp", 49.23)
                                    .put("latency", 1.68))
                        )
                        .put("primary", JSONObject().put("intf", "wan1").put("currentDown", 222.5))
                )
            )
        )

        val record = history.single()
        assertEquals(listOf("wan1", "wan"), speedTestHistoryInterfaces(record).map { it.intf })
        assertEquals(222.5, speedTestHistoryInterfaces(record)[0].downMbps!!, 0.001)
        assertEquals(587.07, speedTestHistoryInterfaces(record)[1].downMbps!!, 0.001)
        assertEquals(49.23, speedTestHistoryInterfaces(record)[1].upMbps!!, 0.001)
        assertEquals(0.2, speedTestHistoryInterfaces(record)[0].loss!!, 0.001)
    }

    @Test
    fun legacyPrimaryOnlyPayloadsAndRecordsFallBackToSingleInterface() {
        val progress = speedTestProgressFrom(
            JSONObject()
                .put("stat", "end")
                .put("primary", JSONObject().put("intf", "wan").put("currentDown", 575.41))
        )
        assertEquals(listOf("wan"), speedTestSamples(progress).map { it.intf })
        assertEquals(575.41, speedTestSamples(progress).single().currentDown!!, 0.001)

        val history = speedTestHistoryFrom(
            JSONObject().put(
                "records",
                JSONArray().put(
                    JSONObject()
                        .put("epoch", 12L)
                        .put("primary", JSONObject()
                            .put("intf", "wan")
                            .put("currentDown", 587.07)
                            .put("currentUp", 49.23)
                            .put("latency", 1.68))
                )
            )
        )
        assertEquals(listOf("wan"), speedTestHistoryInterfaces(history.single()).map { it.intf })
        val legacyRecord = SpeedHistoryRecord(12L, "wan", 587.07, 49.23, 1.68)
        assertEquals(listOf("wan"), speedTestHistoryInterfaces(legacyRecord).map { it.intf })
        assertEquals(587.07, speedTestHistoryInterfaces(legacyRecord).single().downMbps!!, 0.001)
    }

    @Test
    fun startBodyAcceptsInterfaceListAndKeepsNodeMappingPerInterface() {
        val body = speedTestStartBody(listOf("WAN", "wan1", "wan"), "308000101")
        val interfaces = body.getJSONArray("intf")
        assertEquals(listOf("wan", "wan1"), (0 until interfaces.length()).map { interfaces.getString(it) })
        val nodes = body.getJSONObject("nodes")
        assertEquals(setOf("wan", "wan1"), nodes.keys().asSequence().toSet())
        assertEquals("308000101", nodes.getString("wan"))
        assertEquals("308000101", nodes.getString("wan1"))
        assertEquals(listOf("wan"), speedTestStartBody(listOf("wan"), "0").getJSONArray("intf").let {
            (0 until it.length()).map(it::getString)
        })
    }
}
