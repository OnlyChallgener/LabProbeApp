package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** 首页「儿童上网」小卡副标题：大字已经给了台数，副标题只许给状态。 */
class ChildInternetMiniCardTest {
    private fun device(id: String, scheduleState: String) = ChildInternetDeviceState(
        summary = ProtectedDeviceSummary(
            deviceId = id, name = id, iconKey = "missing", accentArgb = 0,
            status = GuardStatus.GUARDED
        ),
        plan = DeviceGuardPlan(),
        schedule = ChildGuardSchedule(state = scheduleState)
    )

    private fun overview(master: Boolean, vararg devices: ChildInternetDeviceState, error: String = "") =
        ChildInternetOverviewState(masterEnabled = master, devices = devices.toList(), error = error)

    @Test
    fun anEmptyGuardListSaysSoInsteadOfZero() {
        assertEquals("还没添加守护设备", childGuardMiniSubtitle(overview(true)))
        assertEquals("读不到 Hub 数据",
            childGuardMiniSubtitle(overview(true, error = "hub unreachable")))
    }

    @Test
    fun aSwitchedOffMasterIsNotReportedAsProtecting() {
        val state = overview(false, device("a", "blocked"), device("b", "unrestricted"))
        assertEquals("总开关已关", childGuardMiniSubtitle(state))
    }

    @Test
    fun blockedDevicesAreNamedBeforeEverythingElse() {
        val state = overview(true, device("a", "blocked"), device("b", "allowed"))
        assertEquals("1 台正停网", childGuardMiniSubtitle(state))
    }

    @Test
    fun allFreeVersusUnknownScheduleReadDifferently() {
        assertEquals("都在允许上网",
            childGuardMiniSubtitle(overview(true, device("a", "unrestricted"), device("b", "allowed"))))
        assertEquals("按计划放行",
            childGuardMiniSubtitle(overview(true, device("a", "partial"), device("b", "unrestricted"))))
    }

    @Test
    fun freshnessLabelReportsDataTimeNotSyncTime() {
        val now = System.currentTimeMillis() / 1000
        fun label(dataEpoch: Long?, stale: Boolean) =
            childGuardFreshnessLabel(dataEpoch = dataEpoch, stale = stale, refreshing = false, failed = false)
        org.junit.Assert.assertTrue(label(now, false).startsWith("更新于"))
        org.junit.Assert.assertTrue(label(now - 3 * 3600, false).startsWith("数据停在"))
        org.junit.Assert.assertTrue(label(now, stale = true).startsWith("数据停在"))
        org.junit.Assert.assertEquals("等待首次同步", label(null, false))
    }
}
