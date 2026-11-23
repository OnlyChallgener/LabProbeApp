package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** 首页总数与在线数使用不同口径；计划放行不等于设备在线。 */
class ChildInternetMiniCardTest {
    private fun device(id: String, scheduleState: String, online: Boolean = false) = ChildInternetDeviceState(
        summary = ProtectedDeviceSummary(
            deviceId = id, name = id, iconKey = "missing", accentArgb = 0,
            status = GuardStatus.GUARDED, isOnline = online
        ),
        plan = DeviceGuardPlan(),
        schedule = ChildGuardSchedule(state = scheduleState)
    )

    private fun overview(master: Boolean, vararg devices: ChildInternetDeviceState, error: String = "") =
        ChildInternetOverviewState(masterEnabled = master, devices = devices.toList(), error = error)

    @Test
    fun anEmptyGuardListSaysSoInsteadOfZero() {
        assertEquals("未添加设备", childGuardMiniSubtitle(overview(true)))
        assertEquals("读取失败",
            childGuardMiniSubtitle(overview(true, error = "hub unreachable")))
    }

    @Test
    fun masterSwitchDoesNotChangePresenceCount() {
        val state = overview(false, device("a", "blocked", true), device("b", "unrestricted", true))
        assertEquals("2台在线", childGuardMiniSubtitle(state))
    }

    @Test
    fun blockedOnlineDeviceStillCountsAsOnline() {
        val state = overview(true, device("a", "blocked", true), device("b", "allowed"))
        assertEquals("1台在线", childGuardMiniSubtitle(state))
    }

    @Test
    fun allFreeVersusUnknownScheduleReadDifferently() {
        assertEquals("0台在线",
            childGuardMiniSubtitle(overview(true, device("a", "unrestricted"), device("b", "allowed"))))
        assertEquals("0台在线",
            childGuardMiniSubtitle(overview(true, device("a", "partial"), device("b", "unrestricted"))))
    }

    @Test fun overviewPresenceOverridesAnOlderSummary() {
        val offline = device("a", "allowed", true).copy(presence = ChildGuardPresence(online = false))
        assertEquals("0台在线", childGuardMiniSubtitle(overview(true, offline)))
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
