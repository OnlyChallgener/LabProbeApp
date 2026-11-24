package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSpeedTestStabilityTest {
    @Test
    fun constantRateStopsAfterWarmupAndStableWindow() {
        val tracker = SpeedStabilityTracker()
        var last: SpeedStabilityTracker.Snapshot? = null
        var elapsed = 0L
        while (elapsed < 15_000L) {
            elapsed += 250L
            last = tracker.add(elapsed, 100.0)
            if (last.done) break
        }
        requireNotNull(last)
        assertTrue(last.done)
        assertTrue(last.stable)
        assertEquals(100.0, last.averageMbps, 0.01)
        assertEquals(100.0, last.peakMbps, 0.01)
        assertTrue(elapsed >= 9_000L)
    }

    @Test
    fun warmupSamplesAreExcludedFromTheReportedAverage() {
        val tracker = SpeedStabilityTracker()
        var last: SpeedStabilityTracker.Snapshot? = null
        var elapsed = 0L
        while (elapsed < 15_000L) {
            elapsed += 250L
            last = tracker.add(elapsed, if (elapsed <= 2_000L) 1_000.0 else 100.0)
            if (last.done) break
        }
        requireNotNull(last)
        assertTrue(last.stable)
        assertEquals(100.0, last.averageMbps, 0.01)
        assertEquals(100.0, last.peakMbps, 0.01)
    }

    @Test
    fun noisyRateRunsToTheLimitAndIsNotMarkedStable() {
        val tracker = SpeedStabilityTracker()
        var last: SpeedStabilityTracker.Snapshot? = null
        var elapsed = 0L
        var flip = false
        while (elapsed < 40_000L) {
            elapsed += 250L
            flip = !flip
            last = tracker.add(elapsed, if (flip) 120.0 else 20.0)
            if (last.done) break
        }
        requireNotNull(last)
        assertTrue(last.done)
        assertFalse(last.stable)
        assertTrue(last.reason.contains("时限"))
        // 单方向 15 秒封顶，包含预热。
        assertEquals(15_000L, elapsed)
    }

    @Test
    fun bothDirectionsNeedEightEffectiveSecondsBeforeTheyCanStop() {
        val tracker = SpeedStabilityTracker()
        var last: SpeedStabilityTracker.Snapshot? = null
        var elapsed = 0L
        while (elapsed < 20_000L) {
            elapsed += 250L
            last = tracker.add(elapsed, 100.0)
            if (last.done) break
        }
        requireNotNull(last)
        assertTrue(last.stable)
        // 2s 预热 + 8s 最少有效 + 1.5s 平台确认
        assertEquals(11_500L, elapsed)

        // 上下行各建一个 tracker：下载完成后上行从零重新计时，互不借用稳定窗口。
        val upload = SpeedStabilityTracker()
        assertFalse(upload.add(10_000L, 100.0).done)
        assertFalse(upload.add(12_000L, 100.0).done)
    }

    @Test
    fun aSingleLiveNodeStillLeavesTheFullListAvailable() {
        val onlyOne = listOf(
            PhoneSpeedTestNode("1101", "广州电信", "61.144.6.18", 65499, "广东", "广州", "电信")
        )
        val merged = GlobalSpeedProtocol.mergeNodes(onlyOne)
        assertTrue(merged.size >= 7)
        // 服务端按出口推荐的节点必须排在最前，兜底节点不能被当成首选。
        assertEquals("1101", merged.first().id)
        assertEquals(1, merged.count { it.id == "1101" })
        assertEquals("电信", GlobalSpeedProtocol.carrierOf("深圳电信"))
        assertEquals("联通", GlobalSpeedProtocol.carrierOf("北京联通"))
    }
}