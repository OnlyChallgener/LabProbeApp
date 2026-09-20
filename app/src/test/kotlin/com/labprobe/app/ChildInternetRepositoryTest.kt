package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildInternetRepositoryTest {
    private companion object {
        /** 断言写死 Asia/Shanghai，不受跑测试的机器时区影响。 */
        const val SHANGHAI = "Asia/Shanghai"
    }

    private fun device(id: String = "AABBCCDDEEFF00112233445566778899") = ChildInternetDeviceState(
        summary = ProtectedDeviceSummary(id, "测试手机", "phone", 0, GuardStatus.UNRESTRICTED),
        plan = DeviceGuardPlan()
    )

    @Test
    fun successfulEmptyMembershipRemovesPreviouslyCachedDevices() {
        assertTrue(mergeChildGuardDeviceList(listOf(device()), emptyList(), ChildGuardCapabilities()).isEmpty())
    }

    @Test
    fun membershipRefreshPreservesMeasuredUsageButNotStaleBlockState() {
        val cached = device().copy(
            summary = device().summary.copy(status = GuardStatus.BLOCKED),
            usage = ChildUsageStats(date = "2026-09-19", onlineMinutes = 42, lateNightMinutes = 0, hasData = true),
            todayUsage = InternetUsageSummary(42, emptyList(), emptyList()),
            runtime = ChildGuardRuntimeState(deviceId = device().summary.deviceId, paused = true)
        )
        val fresh = device().copy(summary = device().summary.copy(name = "路由器新名称", isOnline = true))
        val result = mergeChildGuardDeviceList(listOf(cached), listOf(fresh, fresh), ChildGuardCapabilities()).single()
        assertEquals(42, result.todayUsage.totalMinutes)
        assertEquals(42, result.usage?.onlineMinutes)
        assertEquals("路由器新名称", result.summary.name)
        assertTrue(result.summary.isOnline)
        assertFalse(result.runtime.paused)
        assertEquals(GuardStatus.UNRESTRICTED, result.summary.status)
    }

    @Test
    fun uidNormalizationPreservesRouterUppercaseIdentity() {
        assertEquals("AABBCCDDEEFF00112233445566778899", childGuardDeviceKey("aabbccddeeff00112233445566778899"))
        assertEquals("aabbccddeeff", childGuardDeviceKey("AA-BB-CC-DD-EE-FF"))
    }

    @Test
    fun consecutiveMinuteRunsDisplayServerMinutesNotTheWallClockSpan() {
        val start = java.time.Instant.parse("2026-09-18T00:15:00Z").epochSecond
        val root = JSONObject().put("timezone", SHANGHAI).put("onlineMinutes", 3).put("apps", JSONArray().put(JSONObject()
            .put("app", "微信").put("minutes", 3).put("sessions", 1)
            .put("sessionRanges", JSONArray().put(JSONObject()
                .put("startEpoch", start).put("endEpoch", start + 180).put("minutes", 3)
                .put("activeSeconds", 120)))))
        val entry = parseChildGuardUsageReport(root).today.entries.single()
        // endEpoch 已是最后一个活跃分钟 +60，所以 08:15/16/17 显示 08:15–08:18。
        assertEquals("09-18 08:15 – 09-18 08:18", entry.sessions.single().timeRange)
        // 时长只认服务器的 minutes，不用 end-start，也不用 activeSeconds。
        assertEquals("3分钟", entry.sessions.single().durationText)
        assertEquals(3, entry.durationMinutes)
        assertEquals(1, entry.count)
    }

    @Test
    fun nonConsecutiveMinuteRunsStaySeparateRanges() {
        val first = java.time.Instant.parse("2026-09-18T00:15:00Z").epochSecond
        val ranges = JSONArray()
        listOf(first, first + 600, first + 1200).forEach { start ->
            ranges.put(JSONObject().put("startEpoch", start).put("endEpoch", start + 60).put("minutes", 1))
        }
        val root = JSONObject().put("timezone", SHANGHAI).put("onlineMinutes", 3).put("apps", JSONArray().put(JSONObject()
            .put("app", "微信").put("minutes", 3).put("sessions", 3).put("sessionRanges", ranges)))
        val entry = parseChildGuardUsageReport(root).today.entries.single()
        assertEquals(
            listOf("09-18 08:15 – 09-18 08:16", "09-18 08:25 – 09-18 08:26", "09-18 08:35 – 09-18 08:36"),
            entry.sessions.map { it.timeRange }
        )
        // 多段区间绝不塌成一条 08:15–08:36。
        assertEquals("", entry.timeRange)
    }

    @Test
    fun sessionRunWithoutServerMinutesIsNotDisplayed() {
        val start = java.time.Instant.parse("2026-09-18T00:15:00Z").epochSecond
        val root = JSONObject().put("timezone", SHANGHAI).put("onlineMinutes", 4).put("apps", JSONArray().put(JSONObject()
            .put("app", "微信").put("minutes", 4).put("sessionRanges", JSONArray().put(JSONObject()
                .put("startEpoch", start).put("endEpoch", start + 240)))))
        assertTrue(parseChildGuardUsageReport(root).today.entries.single().sessions.isEmpty())
    }

    @Test
    fun parallelAppMinutesAreNeverScaledToTheDeviceTotal() {
        val root = JSONObject().put("timezone", SHANGHAI)
            .put("onlineMinutes", 10).put("apps", JSONArray()
                .put(JSONObject().put("app", "微信").put("minutes", 8))
                .put(JSONObject().put("app", "抖音").put("minutes", 7)))
        val report = parseChildGuardUsageReport(root)
        assertEquals(10, report.today.totalMinutes)
        // 8+7 > 10 是并发使用的真实结果，不做任何缩放。
        assertEquals(listOf(8, 7), report.today.entries.map { it.durationMinutes })
    }

    @Test
    fun sessionsWithoutTimestampsNeverCreateInventedTimeline() {
        val root = JSONObject().put("timezone", SHANGHAI).put("onlineMinutes", 2).put("apps", JSONArray().put(JSONObject()
            .put("app", "微信").put("minutes", 2).put("sessions", 4)))
        val entry = parseChildGuardUsageReport(root).today.entries.single()
        assertTrue(entry.sessions.isEmpty())
        assertEquals("", entry.timeRange)
    }

    @Test
    fun invalidSessionTimestampIsSkippedWithoutLosingAppRow() {
        val root = JSONObject().put("timezone", SHANGHAI).put("onlineMinutes", 2).put("apps", JSONArray().put(JSONObject()
            .put("app", "微信").put("minutes", 2).put("sessionRanges", JSONArray().put(JSONObject()
                .put("startEpoch", Long.MAX_VALUE).put("endEpoch", Long.MAX_VALUE).put("minutes", 2)))))
        val entry = parseChildGuardUsageReport(root).today.entries.single()
        assertTrue(entry.sessions.isEmpty())
        assertEquals(2, entry.durationMinutes)
    }

    /** 流量卡片只认 Hub 的 traffic 块；没有块或全为零就没有卡片。 */
    @Test
    fun trafficBlockFeedsTheDeviceReportAndItsAbsenceHidesTheCard() {
        val root = JSONObject().put("onlineMinutes", 10).put("traffic", JSONObject()
            .put("txBytes", 27_338_079L).put("rxBytes", 840_542_158L).put("totalBytes", 867_880_237L)
            .put("daily", JSONArray().put(JSONObject().put("date", "2026-09-20")
                .put("txBytes", 1L).put("rxBytes", 2L).put("totalBytes", 3L))))
        val report = parseChildGuardUsageReport(root)
        val traffic = report.traffic
        assertNotNull(traffic)
        assertEquals(27_338_079L, traffic!!.todayTxBytes)
        assertEquals(840_542_158L, traffic.todayRxBytes)
        assertEquals(867_880_237L, traffic.todayTotalBytes)
        assertEquals("2026-09-20", traffic.daily.single().date)
        assertEquals(traffic, device().withUsage(report).usageReport)
        assertNull(parseChildGuardUsageReport(JSONObject().put("onlineMinutes", 5)).traffic)
        assertNull(parseChildGuardUsageReport(JSONObject().put("traffic", JSONObject()
            .put("txBytes", 0).put("rxBytes", 0).put("totalBytes", 0))).traffic)
    }

    @Test
    fun candidateWithoutAnOnlineFieldIsNotAssumedOnline() {
        val parsed = parseChildGuardCandidates(JSONObject().put("devices", JSONArray()
            .put(JSONObject().put("mac", "aa:bb:cc:dd:ee:02").put("hostname", "iPad"))))
        assertFalse(parsed.single().online)
    }

    /** 预览仓库没有假设备列表，乐观插入的设备也只带身份字段。 */
    @Test
    fun fakeRepositoryCarriesIdentityOnly() {
        assertEquals(emptyList<ChildInternetDeviceState>(), FakeChildInternetRepository.state.devices)
        FakeChildInternetRepository.ensureDevice("preview-device", "小明平板", "tablet", 0xFF7C5CE7.toInt())
        val added = FakeChildInternetRepository.state.devices.single()
        assertEquals("preview-device", added.summary.deviceId)
        assertEquals("小明平板", added.summary.name)
        // 缺的一律是 null / 空，不是编出来的 0 分钟或「一切正常」。
        assertNull(added.usage)
        assertNull(added.todayUsage.totalMinutes)
        assertTrue(added.attentionEntries.isEmpty())
        assertFalse(added.summary.isOnline)
        assertNull(added.usageReport)
        assertTrue(added.todayUsage.entries.isEmpty())
        assertTrue(added.todayUsage.bars.isEmpty())
        assertTrue(added.recentUsage.bars.isEmpty())
    }

    /** 家长请注意只列服务器逐日返回的那几天：缺 coverage 的那天是「暂无使用记录」，不是 0 分钟。 */
    @Test
    fun attentionUsesEachDaysActualNightMinutesAndPreservesMissingCoverage() {
        val report = parseChildGuardUsageReport(JSONObject().put("date", "2026-09-19")
            .put("onlineMinutes", 30).put("lateNightMinutes", 4)
            .put("coverage", JSONObject().put("status", "recorded"))
            .put("range", JSONObject().put("days", JSONArray()
                .put(JSONObject().put("date", "2026-09-17").put("hasData", false).put("lateNightMinutes", 0))
                .put(JSONObject().put("date", "2026-09-18").put("hasData", true).put("lateNightMinutes", 0))
                .put(JSONObject().put("date", "2026-09-19").put("hasData", true).put("lateNightMinutes", 4)))))
        val rows = report.attention
        assertEquals(4, report.stats.lateNightMinutes)
        assertEquals(listOf("2026-09-19", "2026-09-18", "2026-09-17"), rows.map { it.date })
        assertTrue(rows[0].message.contains("4分钟"))
        assertFalse(rows[0].normal)
        assertEquals(ChildAttentionState.ALERT, rows[0].state)
        assertTrue(rows[1].normal)
        assertFalse(rows[2].normal)
        assertFalse(rows[2].hasData)
        assertEquals("暂无使用记录", rows[2].message)
        assertEquals(ChildAttentionState.UNKNOWN, rows[2].state)
    }

    @Test
    fun zeroUsageRequiresRecordedCoverageToBeCalledHealthy() {
        val report = parseChildGuardUsageReport(JSONObject().put("date", "2026-09-19")
            .put("onlineMinutes", 0).put("lateNightMinutes", 0)
            .put("coverage", JSONObject().put("hasRecords", true))
            .put("range", JSONObject().put("days", JSONArray().put(JSONObject()
                .put("date", "2026-09-19").put("coverage", "recorded").put("lateNightMinutes", 0)))))
        assertTrue(report.stats.hasData)
        assertEquals(0, report.stats.onlineMinutes)
        assertEquals(ChildAttentionState.NONE, report.stats.attention)
        assertTrue(report.attention.single().normal)
        // 整份 payload 是空的：既不能写成 0 分钟，也不能写成「一切正常」。
        val blank = parseChildGuardUsageReport(JSONObject())
        assertFalse(blank.stats.hasData)
        assertNull(blank.stats.onlineMinutes)
        assertEquals(ChildAttentionState.UNKNOWN, blank.stats.attention)
        assertFalse(blank.attention.single().normal)
        assertEquals("暂无使用记录", blank.attention.single().message)
    }

    @Test
    fun wechatCatalogExpandsToEveryKnownRdpiSignature() {
        assertEquals(
            setOf("7-1-2-0", "7-1-2-3", "7-1-2-12", "7-1-2-14"),
            childInternetRdpiIds("微信")
        )
        val wechat = SelectableAppItem("wechat", "微信", "wechat", selected = true, rdpiIds = childInternetRdpiIds("微信"))
        assertEquals(childInternetRdpiIds("微信"), expandRdpiIds(listOf(wechat)))
    }

    @Test
    fun planSerializesAndDeserializesStableHubContract() {
        val wechat = SelectableAppItem("social-1", "微信", "wechat", selected = true, rdpiIds = childInternetRdpiIds("微信"))
        val plan = DeviceGuardPlan(
            id = "plan-1", configured = true, enabled = true, startTime = "08:00", endTime = "20:30",
            repeatDays = setOf(1, 3, 5), categories = listOf(AppCategoryPlan("social", "社交", true, listOf(wechat)))
        )
        val body = plan.toChildGuardJson()
        assertEquals("app_allowlist", body.getString("mode"))
        assertEquals(3, body.getJSONArray("weekdays").length())
        assertEquals(4, body.getJSONArray("applications").getJSONObject(0).getJSONArray("rdpiIds").length())

        val parsed = parseChildGuardPlans(JSONObject().put("plans", JSONArray().put(body))).single()
        assertEquals("plan-1", parsed.id)
        assertEquals(setOf(1, 3, 5), parsed.repeatDays)
        assertEquals(childInternetRdpiIds("微信"), parsed.allowedRdpiIds)
    }

    @Test
    fun runtimeEffectPolicyMapsNoneBoundAndUnknownPolicies() {
        val bound = setOf("pid-7")
        assertEquals(RuntimeEffectPolicy.NONE, mapRuntimeEffectPolicy("none", bound))
        assertEquals(RuntimeEffectPolicy.ACTIVE, mapRuntimeEffectPolicy("pid-7", bound))
        assertEquals(RuntimeEffectPolicy.UNKNOWN, mapRuntimeEffectPolicy("pid-other", bound))

        val runtime = parseChildGuardRuntime(JSONObject().put("runtime", JSONObject()
            .put("uid", "AA:BB")
            .put("policyIds", JSONArray().put("pid-7"))
            .put("effectPolicyId", "pid-7")
            .put("active", true)
        ))
        assertEquals(RuntimeEffectPolicy.ACTIVE, runtime.effectPolicy)
        assertFalse(runtime.paused)
    }

    @Test
    fun pcIsNotBlockedByPhoneTabletGateWhenCapabilitySupportsAppControl() {
        val supported = ChildGuardCapabilities(childGuard = true, rdpi = true, supported = true)
        assertTrue(supported.appManagementSupported)
        assertTrue(isExperimentalAppControlDevice("computer"))
        assertTrue(isExperimentalAppControlDevice("desktop"))
        assertFalse(isExperimentalAppControlDevice("phone"))
    }

    @Test
    fun routerDevIdentifyTypeUsesExistingLabProbeIconVocabulary() {
        val root = JSONObject().put("devices", JSONArray().put(JSONObject()
            .put("uid", "router-uid")
            .put("macs", JSONArray().put("1a:9c:c5:c5:b7:bb"))
            .put("name", "电脑")
            .put("deviceType", "pc")
        ))
        val parsed = parseChildGuardDevices(root).single().summary
        assertEquals("desktop", parsed.iconKey)
        assertTrue(isExperimentalAppControlDevice(parsed.iconKey))
    }

    @Test
    fun capabilitiesParserRequiresOriginalChildGuardAndRdpi() {
        val unsupported = parseChildGuardCapabilities(JSONObject().put("capabilities", JSONObject()
            .put("available", true).put("rdpiEnabled", false).put("appControlSupported", true)
        ))
        assertFalse(unsupported.appManagementSupported)
        val supported = parseChildGuardCapabilities(JSONObject().put("capabilities", JSONObject()
            .put("available", true).put("rdpiEnabled", true).put("appControlSupported", true).put("version", "2.1")
        ))
        assertTrue(supported.appManagementSupported)
        assertEquals("2.1", supported.version)
    }

    @Test
    fun routerUidAndStationMacResolveToTheSameProtectedDevice() {
        val summary = ProtectedDeviceSummary(
            deviceId = "9A59FF88998744D4B7B4FB05E3E0255C",
            name = "有线电脑",
            iconKey = "computer",
            accentArgb = 0,
            status = GuardStatus.UNRESTRICTED,
            macAddresses = setOf("1a:9c:c5:c5:b7:bb")
        )
        assertTrue(summary.matchesChildGuardDevice("1A-9C-C5-C5-B7-BB"))
        assertTrue(summary.matchesChildGuardDevice("9a59ff88998744d4b7b4fb05e3e0255c"))
        assertEquals("1a9cc5c5b7bb", childGuardDeviceKey("1A:9C:C5:C5:B7:BB"))
    }

    @Test
    fun originalWeekdayNamesDeserializeToUiNumbers() {
        val plan = JSONObject()
            .put("id", "router-plan")
            .put("enabled", true)
            .put("startTime", "08:00")
            .put("endTime", "09:00")
            .put("weekdays", JSONArray().put("mon").put("wed").put("sun"))
            .put("applications", JSONArray())
        val parsed = parseChildGuardPlans(JSONObject().put("plans", JSONArray().put(plan))).single()
        assertEquals(setOf(1, 3, 7), parsed.repeatDays)
    }

    @Test
    fun candidateListParsesDhcpLeaseShapeWithGuardStatus() {
        val root = JSONObject().put("ok", true).put("devices", JSONArray()
            .put(JSONObject().put("mac", "aa:bb:cc:dd:ee:01").put("ip", "192.168.1.101").put("hostname", "小明的手机").put("guarded", true).put("uid", "9A59FF88998744D4B7B4FB05E3E0255C").put("name", "华为 Mate60 手机"))
            .put(JSONObject().put("mac", "aa:bb:cc:dd:ee:02").put("ip", "192.168.1.102").put("hostname", "iPad").put("guarded", false))
        )
        val parsed = parseChildGuardCandidates(root)
        assertEquals(2, parsed.size)
        val guarded = parsed.first { it.guarded }
        assertEquals("9A59FF88998744D4B7B4FB05E3E0255C", guarded.uid)
        assertEquals("华为 Mate60 手机", guarded.displayName)
        val free = parsed.first { !it.guarded }
        assertEquals("iPad", free.displayName)
        assertEquals("", free.uid)
    }

    @Test
    fun usageReportParsesOfficialShapeIntoBothTabs() {
        val root = JSONObject()
            .put("ok", true)
            .put("date", "2026-09-18")
            .put("timezone", SHANGHAI)
            .put("source", "hub")
            // 秒字段是旧 payload 的残留：分钟数一律只认 onlineMinutes。
            .put("onlineSeconds", 421)
            .put("onlineMinutes", 120)
            .put("coverage", JSONObject().put("status", "recorded"))
            .put("hourly", JSONArray()
                .put(JSONObject().put("hour", 20).put("minutes", 45).put("txBytes", 1).put("rxBytes", 2))
                .put(JSONObject().put("hour", 21).put("minutes", 75)))
            .put("apps", JSONArray()
                .put(JSONObject().put("app", "抖音").put("minutes", 88).put("sessions", 4))
                .put(JSONObject().put("app", "微信").put("minutes", 32).put("sessions", 9)))
            .put("range", JSONObject()
                .put("start", "2026-09-09")
                .put("end", "2026-09-18")
                .put("days", JSONArray()
                    .put(JSONObject().put("date", "2026-09-09").put("onlineMinutes", 0))
                    .put(JSONObject().put("date", "2026-09-17").put("onlineMinutes", 30))
                    .put(JSONObject().put("date", "2026-09-18").put("onlineMinutes", 120)))
                .put("apps", JSONArray()
                    .put(JSONObject().put("app", "抖音").put("minutes", 300).put("sessions", 11))))

        val report = parseChildGuardUsageReport(root)
        assertEquals("hub", report.source)
        assertEquals("2026-09-18", report.stats.date)

        // 今日 tab: 柱子就是 Hub 给的那几个小时，缺席的小时不补 0 格。
        assertEquals(120, report.today.totalMinutes)
        assertEquals(listOf(20, 21), report.today.bars.map { it.hour })
        assertEquals(listOf("20点", "21点"), report.today.bars.map { it.label })
        assertEquals(listOf(45, 75), report.today.bars.map { it.minutes })
        assertEquals(listOf("抖音", "微信"), report.today.entries.map { it.appName })
        assertEquals(88, report.today.entries.first().durationMinutes)
        assertEquals(4, report.today.entries.first().count)
        assertEquals("douyin", report.today.entries.first().iconKey)
        // apps[] 没有 hourlyMinutes，就没有这一列，不拿总时长去摊。
        assertTrue(report.today.entries.first().hourlyMinutes.isEmpty())

        // 最近10天 tab: one bar per returned day, total = sum of the window.
        assertEquals(3, report.recent.bars.size)
        assertEquals(150, report.recent.totalMinutes)
        assertEquals("今天", report.recent.bars.last().label)
        assertEquals(300, report.recent.entries.single().durationMinutes)
    }

    @Test
    fun heartbeatOnlyAppsDoNotAppearAsZeroMinuteRows() {
        val root = JSONObject()
            .put("date", "2026-09-18")
            .put("onlineMinutes", 0)
            .put("coverage", JSONObject().put("hasRecords", true))
            .put("hourly", JSONArray())
            .put("apps", JSONArray()
                // 微信 kept a push channel alive all night: bytes but no active time.
                .put(JSONObject().put("app", "微信").put("minutes", 0).put("sessions", 1))
                .put(JSONObject().put("app", "抖音").put("minutes", 12).put("sessions", 2)))

        val report = parseChildGuardUsageReport(root)
        assertEquals(listOf("抖音"), report.today.entries.map { it.appName })
        assertEquals(0, report.today.totalMinutes)
    }

    /** 没有 coverage 就是没统计过：不画 24 格假框架，也不写 0 分钟。 */
    @Test
    fun anEmptyReportInventsNeitherBarsNorMinutes() {
        val report = parseChildGuardUsageReport(JSONObject().put("source", "empty"))
        assertTrue(report.today.bars.isEmpty())
        assertTrue(report.today.entries.isEmpty())
        assertNull(report.today.totalMinutes)
        assertNull(report.recent.totalMinutes)
        assertTrue(report.recent.bars.isEmpty())
        assertFalse(report.stats.hasData)
        assertEquals(ChildAttentionState.UNKNOWN, report.stats.attention)
    }

    @Test
    fun malformedUsageRowsAreSkippedRatherThanCrashing() {
        val root = JSONObject()
            .put("onlineMinutes", 30)
            .put("hourly", JSONArray().put(JSONObject().put("hour", 5).put("minutes", 30)).put(JSONObject().put("minutes", 1)))
            .put("apps", JSONArray().put(JSONObject().put("minutes", 5)).put(JSONObject().put("app", "百度").put("minutes", 5)))
        val report = parseChildGuardUsageReport(root)
        // 没有 hour 的那条小时记录被丢掉，而不是当成 0 点。
        assertEquals(30, report.today.bars.single().minutes)
        assertEquals(5, report.today.bars.single().hour)
        assertEquals(listOf("百度"), report.today.entries.map { it.appName })
    }

    /**
     * 「适用年龄」分级已按产品决策整体移除：官方那份来自锐捷云端应用目录，
     * 路由器本地目录没有真实数据源。这里有意的**没有**回归测试 ——
     * 移除是靠类型系统保证的（`SelectableAppItem` 不再有 `ageRating` 字段，
     * 想编造也编不出来），比运行时断言更强；`check_kotlin.py` 的
     * FORBIDDEN_IN_MAIN 另外禁掉 `ageRating` / `适用年龄` 字样防止回潮。
     */

    /** 移除年龄筛选后，分类原本的默认勾选行为不能被带坏。 */
    @Test
    fun catalogDefaultsStillSelectTheCuratedBaseline() {
        val categories = childInternetCatalogCategories()
        val keys = categories.map { it.id }
        assertEquals(listOf("education", "media", "games", "tools", "social", "ainews", "shopping", "stores"), keys)
        categories.forEach { category ->
            assertTrue("${category.id} should ship at least one app", category.apps.isNotEmpty())
            assertTrue("${category.id} should have a stable app id", category.apps.all { it.id.startsWith(category.id) })
        }
        assertEquals(setOf("education", "media", "tools"), categories.filter { it.enabled }.map { it.id }.toSet())
    }

    /**
     * 离线设备在三个小时后刷新，历史时段必须一字不动。会动的只有时钟，所以这里
     * 直接封掉守护统计这条链路上的读钟/编造符号：`Calendar.getInstance().HOUR_OF_DAY`
     * 正是把 08:20-08:31 平移成 11:20-11:31 的那个锚点。
     * 倒计时和临时屏蔽截止时间用 `System.currentTimeMillis` 是另一回事，不在禁用之列。
     */
    @Test
    fun productionSourcesNeverRebuildHistoryFromTheWallClock() {
        val root = java.io.File("src/main/kotlin")
        org.junit.Assume.assumeTrue("Production sources are not next to this module", root.isDirectory)
        val forbidden = listOf(
            "deriveMockSessions",
            "buildDefaultDailyBars",
            "deriveAttentionEntries",
            "Calendar.getInstance",
            "HOUR_OF_DAY",
            "LocalTime.now()",
            "LocalDateTime.now()"
        )
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.startsWith("ChildInternet") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val token = forbidden.firstOrNull { line.contains(it) } ?: return@mapIndexedNotNull null
                    "${file.name}:${index + 1} $token -> ${line.trim()}"
                }
            }.toList()
        assertTrue(
            "时段只能来自 Hub 的真实分钟桶，不能用当前时间推算：\n" + hits.joinToString("\n"),
            hits.isEmpty()
        )
    }
}
