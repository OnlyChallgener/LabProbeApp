package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildInternetRepositoryTest {
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
            todayMinutes = 0,
            hasAttention = false,
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
            .put("source", "hub")
            .put("onlineSeconds", 7200)
            .put("onlineMinutes", 120)
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
        assertEquals("2026-09-18", report.date)

        // 今日 tab: a full 24-bar frame with the real hours filled in.
        assertEquals(120, report.today.totalMinutes)
        assertEquals(24, report.today.bars.size)
        assertEquals(45, report.today.bars[20].minutes)
        assertEquals(75, report.today.bars[21].minutes)
        assertEquals(0, report.today.bars[0].minutes)
        assertEquals(listOf("抖音", "微信"), report.today.entries.map { it.appName })
        assertEquals(88, report.today.entries.first().durationMinutes)
        assertEquals(4, report.today.entries.first().count)
        assertEquals("douyin", report.today.entries.first().iconKey)

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
            .put("hourly", JSONArray())
            .put("apps", JSONArray()
                // 微信 kept a push channel alive all night: bytes but no active time.
                .put(JSONObject().put("app", "微信").put("minutes", 0).put("sessions", 1))
                .put(JSONObject().put("app", "抖音").put("minutes", 12).put("sessions", 2)))

        val report = parseChildGuardUsageReport(root)
        assertEquals(listOf("抖音"), report.today.entries.map { it.appName })
        assertEquals(0, report.today.totalMinutes)
    }

    @Test
    fun anEmptyReportStillRendersAFullBarFrame() {
        val report = parseChildGuardUsageReport(JSONObject().put("source", "empty"))
        assertEquals(24, report.today.bars.size)
        assertTrue(report.today.bars.all { it.minutes == 0 })
        assertTrue(report.today.entries.isEmpty())
        assertEquals(0, report.recent.totalMinutes)
        assertTrue(report.recent.bars.isEmpty())
    }

    @Test
    fun malformedUsageRowsAreSkippedRatherThanCrashing() {
        val root = JSONObject()
            .put("onlineMinutes", 30)
            .put("hourly", JSONArray().put(JSONObject().put("hour", 5).put("minutes", 30)).put(JSONObject().put("minutes", 1)))
            .put("apps", JSONArray().put(JSONObject().put("minutes", 5)).put(JSONObject().put("app", "百度").put("minutes", 5)))
        val report = parseChildGuardUsageReport(root)
        assertEquals(30, report.today.bars[5].minutes)
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
        assertEquals(listOf("education", "media", "games", "tools", "social", "shopping", "stores"), keys)
        categories.forEach { category ->
            assertTrue("${category.id} should ship at least one app", category.apps.isNotEmpty())
            assertTrue("${category.id} should have a stable app id", category.apps.all { it.id.startsWith(category.id) })
        }
        assertEquals(setOf("education", "media", "tools"), categories.filter { it.enabled }.map { it.id }.toSet())
    }
}
