package com.labprobe.app.feature.assistant

import com.labprobe.app.DiagnosisCheck
import com.labprobe.app.DiagnosisItem
import com.labprobe.app.DiagnosisProgress
import com.labprobe.app.DiagnosisResult
import com.labprobe.app.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiDiagnosisActionsTest {
    @Test
    fun negativeDiagnosisCommandDoesNotStartDiagnosis() {
        assertNull(parseAiDiagnosisAction("不要开始网络诊断"))
    }

    @Test
    fun resultQueryReadsSummaryWithoutStartingDiagnosis() {
        assertEquals(
            AiDiagnosisAction.SUMMARY,
            parseAiDiagnosisAction("查看上次网络诊断结果"),
        )
    }

    @Test
    fun scoreAndDetailQueriesOpenFusedNetworkHealth() {
        assertEquals(
            AiDiagnosisAction.OPEN,
            parseAiDiagnosisAction("打开评分细则"),
        )
        assertEquals(
            AiDiagnosisAction.OPEN,
            parseAiDiagnosisAction("查看健康分"),
        )
        assertEquals(
            AiDiagnosisAction.OPEN,
            parseAiDiagnosisAction("查看网络健康"),
        )
    }

    @Test
    fun onlyExplanationQuestionsReceiveExistingDiagnosisContext() {
        val progress = DiagnosisProgress()

        assertEquals("查看上次网络诊断结果", aiDiagnosisQuestion("查看上次网络诊断结果", progress))
        assertEquals("开始网络诊断", aiDiagnosisQuestion("开始网络诊断", progress))
        assertTrue(aiDiagnosisQuestion("请解释网络诊断结果", progress).contains("APP 已有网络诊断摘要"))
        assertTrue(aiDiagnosisQuestion("为什么健康分被扣了？", progress).contains("APP 已有网络诊断摘要"))
    }

    @Test
    fun explanationContextIncludesSampleTimeButRemovesPrivateFreeText() {
        val completedAt = 1_893_456_000_000L
        val item = DiagnosisItem(
            check = DiagnosisCheck.GATEWAY,
            status = HealthStatus.ERROR,
            metric = "token=metric-secret",
            explanation = "credential-secret at https://10.0.0.1",
            details = listOf("details-secret user:pass@router.local"),
        )
        val progress = DiagnosisProgress(
            items = listOf(item),
            result = DiagnosisResult(
                items = listOf(item),
                status = HealthStatus.ERROR,
                conclusion = "arbitrary-conclusion-secret",
                completedAt = completedAt,
            ),
        )

        val question = aiDiagnosisQuestion("请分析网络健康诊断结果", progress)
        val expectedStamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(completedAt))

        assertTrue(question.contains(expectedStamp))
        assertTrue(question.contains("详见本地结果"))
        assertTrue(question.contains("手机到默认网关的连接存在异常"))
        listOf(
            "metric-secret",
            "credential-secret",
            "10.0.0.1",
            "details-secret",
            "router.local",
            "arbitrary-conclusion-secret",
        ).forEach { secret -> assertFalse(question.contains(secret)) }
    }

    @Test
    fun summaryDistinguishesRunningCancelledAndMissingResults() {
        val running = aiDiagnosisSummary(
            DiagnosisProgress(
                items = listOf(DiagnosisItem(check = DiagnosisCheck.ROUTER)),
                current = DiagnosisCheck.GATEWAY,
                running = true,
            ),
        )
        val cancelled = aiDiagnosisSummary(
            DiagnosisProgress(
                items = listOf(DiagnosisItem(check = DiagnosisCheck.ROUTER)),
                cancelled = true,
            ),
        )
        val missing = aiDiagnosisSummary(DiagnosisProgress())

        assertTrue(running.contains("正在进行"))
        assertTrue(running.contains("已完成 1 项"))
        assertTrue(cancelled.contains("已停止"))
        assertTrue(cancelled.contains("尚未生成完整结论"))
        assertTrue(missing.contains("还没有本次应用运行期间的诊断结果"))
    }
}
