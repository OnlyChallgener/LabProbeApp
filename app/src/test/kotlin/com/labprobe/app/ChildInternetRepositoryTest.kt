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
        val wechat = SelectableAppItem("wechat", "微信", "4+岁", "wechat", selected = true, rdpiIds = childInternetRdpiIds("微信"))
        assertEquals(childInternetRdpiIds("微信"), expandRdpiIds(listOf(wechat)))
    }

    @Test
    fun planSerializesAndDeserializesStableHubContract() {
        val wechat = SelectableAppItem("social-1", "微信", "4+岁", "wechat", selected = true, rdpiIds = childInternetRdpiIds("微信"))
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
}
