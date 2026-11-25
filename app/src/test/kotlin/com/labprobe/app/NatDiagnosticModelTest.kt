package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class NatDiagnosticModelTest {
    @Test
    fun statusLabelsStayReadableForTaskStates() {
        assertEquals("等待检测", natStatusZh("idle"))
        assertEquals("检测中", natStatusZh("running"))
        assertEquals("检测完成", natStatusZh("success"))
        assertEquals("检测失败", natStatusZh("failed"))
    }

    @Test
    fun historyDateUsesTheRealRecordTimestamp() {
        val timestamp = 1_795_258_821L
        val expected = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            .format(java.util.Date(timestamp * 1000L))
        assertEquals(expected, natHistoryDate(timestamp))
    }

    @Test
    fun missingHistoryTimestampDoesNotInventADate() {
        assertEquals("--", natHistoryDate(0L))
    }
}
