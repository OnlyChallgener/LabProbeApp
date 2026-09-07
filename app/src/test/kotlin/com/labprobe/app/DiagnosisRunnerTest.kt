package com.labprobe.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiagnosisRunnerTest {
    @Test
    fun reusedToolTimeoutDoesNotCancelWholeDiagnosis() = runBlocking {
        val result = DiagnosisRunner(timeoutMs = 1_000L).run(
            probe = { check ->
                if (check == DiagnosisCheck.ROUTER) kotlinx.coroutines.withTimeout(20L) { delay(200L) }
                goodItem(check)
            },
            onProgress = {},
        )
        assertEquals("检测超时", result.items.first().metric)
        assertEquals(DiagnosisCheck.entries.size, result.items.size)
        assertEquals(HealthStatus.GOOD, result.items.last().status)
    }

    @Test
    fun failedCheckDoesNotPreventLaterChecksFromRunning() = runBlocking {
        val invoked = mutableListOf<DiagnosisCheck>()

        val result = DiagnosisRunner(timeoutMs = 1_000L).run(
            probe = { check ->
                invoked += check
                if (check == DiagnosisCheck.ROUTER || check == DiagnosisCheck.DNS) {
                    error("secret endpoint must not escape")
                }
                goodItem(check)
            },
            clock = { 123L },
            onProgress = {},
        )

        assertEquals(DiagnosisCheck.entries, invoked)
        assertEquals(HealthStatus.UNKNOWN, result.items[0].status)
        assertEquals(HealthStatus.UNKNOWN, result.items[3].status)
        assertEquals(HealthStatus.GOOD, result.items.last().status)
        assertFalse(result.items[0].details.joinToString().contains("secret endpoint"))
    }

    @Test
    fun timedOutCheckIsRecordedAndRunnerContinues() = runBlocking {
        val invoked = mutableListOf<DiagnosisCheck>()

        val result = DiagnosisRunner(timeoutMs = 30L).run(
            probe = { check ->
                invoked += check
                if (check == DiagnosisCheck.ROUTER) delay(200L)
                goodItem(check)
            },
            clock = { 456L },
            onProgress = {},
        )

        assertEquals(DiagnosisCheck.entries, invoked)
        assertEquals(HealthStatus.UNKNOWN, result.items.first().status)
        assertEquals("检测超时", result.items.first().metric)
        assertEquals(HealthStatus.GOOD, result.items.last().status)
    }

    @Test
    fun blockingTimedOutProbeCannotDelayOrOverwriteResult() = runBlocking {
        val releaseProbe = CountDownLatch(1)
        val lateProbeFinished = CountDownLatch(1)
        val progress = mutableListOf<DiagnosisProgress>()
        lateinit var result: DiagnosisResult
        var progressCountAtReturn = 0

        try {
            result = withTimeout(2_000L) {
                DiagnosisRunner(timeoutMs = 30L).run(
                    probe = { check ->
                        if (check == DiagnosisCheck.ROUTER) {
                            while (true) {
                                try {
                                    releaseProbe.await()
                                    break
                                } catch (_: InterruptedException) {
                                    // Model a legacy blocking call that ignores cancellation.
                                }
                            }
                            lateProbeFinished.countDown()
                            DiagnosisItem(check, HealthStatus.ERROR, "迟到结果", "不应发布")
                        } else {
                            goodItem(check)
                        }
                    },
                    clock = { 500L },
                    onProgress = progress::add,
                )
            }

            assertEquals(1L, lateProbeFinished.count)
            assertEquals(HealthStatus.UNKNOWN, result.items.first().status)
            assertEquals("检测超时", result.items.first().metric)
            assertEquals(DiagnosisCheck.entries, result.items.map { it.check })
            progressCountAtReturn = progress.size
        } finally {
            releaseProbe.countDown()
        }

        assertTrue(lateProbeFinished.await(1L, TimeUnit.SECONDS))
        assertEquals(progressCountAtReturn, progress.size)
        assertEquals(HealthStatus.UNKNOWN, result.items.first().status)
    }

    @Test
    fun parentCancellationIsNotConvertedIntoUnknownResult() = runBlocking {
        val probeStarted = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()

        val job = launch {
            try {
                DiagnosisRunner(timeoutMs = 10_000L).run(
                    probe = {
                        probeStarted.complete(Unit)
                        awaitCancellation()
                    },
                    onProgress = {},
                )
            } catch (cancelled: CancellationException) {
                cancellationObserved.complete(Unit)
                throw cancelled
            }
        }

        probeStarted.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(cancellationObserved.isCompleted)
    }

    @Test
    fun progressVisitsAllNineChecksInDeclarationOrder() = runBlocking {
        val progress = mutableListOf<DiagnosisProgress>()

        val result = DiagnosisRunner(timeoutMs = 1_000L).run(
            probe = ::goodItem,
            clock = { 789L },
            onProgress = progress::add,
        )

        assertEquals(DiagnosisCheck.entries, progress.mapNotNull { it.current })
        assertEquals(DiagnosisCheck.entries.size * 2 + 1, progress.size)
        DiagnosisCheck.entries.forEachIndexed { index, check ->
            val starting = progress[index * 2]
            val completed = progress[index * 2 + 1]
            assertEquals(check, starting.current)
            assertEquals(index, starting.items.size)
            assertEquals(null, completed.current)
            assertEquals(index + 1, completed.items.size)
            assertTrue(starting.running)
            assertTrue(completed.running)
        }
        assertEquals(result, progress.last().result)
        assertFalse(progress.last().running)
    }

    private fun goodItem(check: DiagnosisCheck) = DiagnosisItem(
        check = check,
        status = HealthStatus.GOOD,
        metric = "正常",
        explanation = "检测正常",
    )
}
