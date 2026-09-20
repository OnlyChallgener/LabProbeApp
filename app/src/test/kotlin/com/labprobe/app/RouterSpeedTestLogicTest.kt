package com.labprobe.app

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
}
