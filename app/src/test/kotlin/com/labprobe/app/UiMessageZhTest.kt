package com.labprobe.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMessageZhTest {
    private fun hasChinese(text: String) = text.any { it in '\u4e00'..'\u9fff' }

    @Test
    fun transportFailuresNeverReachTheUserAsBareEnglish() {
        // Android 的 ECONNABORTED 与 OkHttp 的连接超时原文都不含 "timeout" 字样，
        // 以前会整句英文直接显示在卡片上。
        assertTrue(hasChinese(uiMessageZh("Software caused connection abort")))
        assertTrue(
            hasChinese(
                uiMessageZh(
                    "failed to connect to /192.168.5.46 (port 58443) from /10.77.0.3 (port 45892) after 6000ms",
                ),
            ),
        )
    }

    @Test
    fun wireGuardMutationMessagesTranslateTheUnderlyingReason() {
        val waiting = wireGuardMutationFailureMessage(
            WireGuardMutationStage.WAIT_AGENT,
            java.net.SocketException("Software caused connection abort"),
        )
        assertFalse(waiting.contains("Software caused connection abort"))
        assertTrue(waiting.contains("连接被本机中断"))

        val reading = wireGuardMutationFailureMessage(
            WireGuardMutationStage.READ_BEFORE_SUBMIT,
            java.io.IOException("failed to connect to /192.168.5.46 after 6000ms"),
        )
        assertFalse(reading.contains("failed to connect"))
        assertTrue(reading.contains("连不上目标服务"))
    }
}
