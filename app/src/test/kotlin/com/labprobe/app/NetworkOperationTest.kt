package com.labprobe.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class NetworkOperationTest {
    private fun operations() = NetworkOperations(
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test
    fun publishesImmediateProgressAndRejectsDuplicate() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val operations = operations()

        assertTrue(operations.launch("profile:a", "正在开始…") { report ->
            report("正在同步…")
            release.await()
            report("同步完成")
        })
        assertEquals(NetworkOperationState("profile:a", "正在同步…", true), operations.state.value)
        assertFalse(operations.launch("profile:b", "不应启动") { })

        release.complete(Unit)
        assertEquals("同步完成", operations.state.value?.label)
        assertFalse(operations.state.value?.running ?: true)
        assertEquals(1L, operations.state.value?.completedVersion)
    }

    @Test
    fun retainsErrorAndAllowsRetry() = runBlocking {
        val operations = operations()

        assertTrue(operations.launch("gateway", "正在保存…") {
            error("Agent 未确认应用")
        })
        assertFalse(operations.state.value?.running ?: true)
        assertEquals("Agent 未确认应用", operations.state.value?.error)

        assertTrue(operations.launch("gateway", "正在重试…") { report -> report("重试完成") })
        assertFalse(operations.state.value?.running ?: true)
        assertNull(operations.state.value?.error)
        assertEquals(2L, operations.state.value?.completedVersion)
    }

    @Test
    fun consumesOnlyTheMatchingCompletedError() = runBlocking {
        val operations = operations()

        assertTrue(operations.launch("wg:gateway", "正在停用…") {
            error("HTTP 502")
        })
        assertNull(operations.consumeCompletedError("stun:"))
        assertEquals("HTTP 502", operations.state.value?.error)

        val consumed = operations.consumeCompletedError("wg:")
        assertNotNull(consumed)
        assertEquals("wg:gateway", consumed?.targetId)
        assertEquals("HTTP 502", consumed?.message)
        assertNull(operations.state.value?.error)
    }

    @Test
    fun lateReportCannotOverwriteNextOperation() = runBlocking {
        lateinit var lateReport: (String) -> Unit
        val operations = operations()

        assertTrue(operations.launch("first", "正在执行…") { report ->
            lateReport = report
            report("首次完成")
        })
        assertTrue(operations.launch("second", "正在执行…") { report -> report("第二次完成") })

        lateReport("过期进度")
        assertEquals("second", operations.state.value?.targetId)
        assertEquals("第二次完成", operations.state.value?.label)
        assertEquals(2L, operations.state.value?.completedVersion)
    }

    @Test
    fun recordsCancellationSeparately() = runBlocking {
        val job = SupervisorJob()
        val operations = NetworkOperations(CoroutineScope(job + Dispatchers.Unconfined))
        val waiting = CompletableDeferred<Unit>()

        assertTrue(operations.launch("cancelled", "正在等待…") { waiting.await() })
        job.cancel()

        assertFalse(operations.state.value?.running ?: true)
        assertEquals("操作已取消", operations.state.value?.error)
        assertEquals(1L, operations.state.value?.completedVersion)
    }
}
