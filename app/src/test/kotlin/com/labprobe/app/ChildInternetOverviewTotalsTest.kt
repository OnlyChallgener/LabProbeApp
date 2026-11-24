package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 概览统计卡的口径测试：总时长、24 小时桶、昨日对比。
 *
 * 这些断言对应真机需求里的硬约束 —— 多设备分别累计、同小时可超 60 分钟、
 * 桶之和必须等于总时长、未知设备不能把总数压成 0、昨日不完整不许给结论。
 */
class ChildInternetOverviewTotalsTest {

    private val today = "2026-11-23"
    private val yesterday = "2026-11-22"

    private fun bar(hour: Int, minutes: Int) = UsageBar(label = "${hour}点", minutes = minutes, hour = hour)

    private fun device(
        id: String,
        minutes: Int?,
        hourBars: List<UsageBar> = emptyList(),
        yesterdayMinutes: Int? = null,
        yesterdayRecorded: Boolean = false,
    ): ChildInternetDeviceState {
        val base = newChildGuardDevice(uid = id, name = id, iconKey = "laptop")
        return base.copy(
            todayUsage = InternetUsageSummary(minutes, hourBars, emptyList()),
            recentUsage = InternetUsageSummary(
                totalMinutes = null,
                bars = if (yesterdayRecorded) {
                    listOf(UsageBar(label = "昨天", minutes = yesterdayMinutes ?: 0, date = yesterday, hasData = true))
                } else {
                    emptyList()
                },
                entries = emptyList(),
            ),
            usage = ChildUsageStats(
                date = today,
                onlineMinutes = minutes,
                hasData = minutes != null,
            ),
        )
    }

    /** 电脑 162 + 手机 240 + 另一台 9 = 411 分钟，也就是 6小时51分钟。 */
    @Test
    fun todayTotalSumsEveryGuardedDevice() {
        val totals = childGuardDayTotals(
            devices = listOf(device("pc", 162), device("phone", 240), device("tablet", 9)),
            today = today,
            yesterday = yesterday,
        )
        assertEquals(411, totals.minutes)
        assertEquals("6小时51分钟", formatChildDuration(totals.minutes))
    }

    /** 多设备同时上网：同一个小时桶可以超过 60 分钟，且桶之和必须等于总时长。 */
    @Test
    fun hourBucketsMayExceedSixtyAndStillMatchTheTotal() {
        val totals = childGuardDayTotals(
            devices = listOf(
                device("pc", 150, listOf(bar(7, 60), bar(8, 90))),
                device("phone", 90, listOf(bar(8, 90))),
            ),
            today = today,
            yesterday = yesterday,
        )
        assertEquals(240, totals.minutes)
        assertEquals(240, totals.hourly.sum())
        assertEquals(60, totals.hourly[7])
        assertEquals(180, totals.hourly[8])
        assertTrue("单小时允许超过 60 分钟", totals.hourly[8] > 60)
    }

    /** 没有统计的设备不计入，但也绝不让总数变成 0；全部未知才是 null。 */
    @Test
    fun unknownDeviceNeverZeroesTheTotal() {
        val partial = childGuardDayTotals(
            devices = listOf(device("pc", 100), device("unknown", null)),
            today = today,
            yesterday = yesterday,
        )
        assertEquals(100, partial.minutes)

        val unknown = childGuardDayTotals(
            devices = listOf(device("unknown", null)),
            today = today,
            yesterday = yesterday,
        )
        assertNull(unknown.minutes)
        assertEquals(24, unknown.hourly.size)
    }

    /** 昨日只要缺一台设备的记录，就不下「较昨日」的结论。 */
    @Test
    fun yesterdayDeltaNeedsTheSameDeviceSet() {
        val incomplete = childGuardDayTotals(
            devices = listOf(
                device("pc", 100, yesterdayMinutes = 80, yesterdayRecorded = true),
                device("phone", 120),
            ),
            today = today,
            yesterday = yesterday,
        )
        assertNull(incomplete.yesterdayMinutes)

        val complete = childGuardDayTotals(
            devices = listOf(
                device("pc", 100, yesterdayMinutes = 80, yesterdayRecorded = true),
                device("phone", 120, yesterdayMinutes = 130, yesterdayRecorded = true),
            ),
            today = today,
            yesterday = yesterday,
        )
        assertEquals(210, complete.yesterdayMinutes)
    }

    /** 没有任何设备有今日记录时，卡片只能显示 `--`，不能显示 0 分钟。 */
    @Test
    fun emptyDayIsUnknownNotZero() {
        val totals = childGuardDayTotals(devices = emptyList(), today = today, yesterday = yesterday)
        assertNull(totals.minutes)
        assertTrue(!totals.hasData)
        assertEquals(24, totals.hourly.size)
    }

    /** 进度条默认按 8 小时折算；有真实限额时必须改用真实限额做分母。 */
    @Test
    fun usagePercentUsesEightHourReference() {
        assertEquals(0, childGuardUsagePercent(null))
        assertEquals(0, childGuardUsagePercent(0))
        assertEquals(34, childGuardUsagePercent(162))
        assertEquals(50, childGuardUsagePercent(240))
        assertEquals(100, childGuardUsagePercent(480))
        assertEquals(100, childGuardUsagePercent(900))
        // 真实限额优先：162 / 300 = 54%，240 / 300 = 80%，超过限额封顶 100%。
        assertEquals(54, childGuardUsagePercent(162, 300))
        assertEquals(80, childGuardUsagePercent(240, 300))
        assertEquals(100, childGuardUsagePercent(360, 300))
        // 0/负数限额等于没配限额，退回 8 小时参考值。
        assertEquals(34, childGuardUsagePercent(162, 0))
    }

    /** 限额标签只说有用的那一位，不写「2小时0分钟」这种不像人话的数字。 */
    @Test
    fun dailyLimitLabelStaysShort() {
        assertEquals("不限", childGuardLimitLabel(null))
        assertEquals("不限", childGuardLimitLabel(0))
        assertEquals("30分钟", childGuardLimitLabel(30))
        assertEquals("1小时", childGuardLimitLabel(60))
        assertEquals("1小时30分钟", childGuardLimitLabel(90))
        assertEquals("2小时", childGuardLimitLabel(120))
        assertEquals("6小时", childGuardLimitLabel(360))
    }
}
