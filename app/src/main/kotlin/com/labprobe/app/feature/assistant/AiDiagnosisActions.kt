package com.labprobe.app.feature.assistant

import com.labprobe.app.DiagnosisProgress
import com.labprobe.app.DiagnosisRules
import com.labprobe.app.HealthStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AiDiagnosisAction { OPEN, START, SUMMARY }

private fun asksForDiagnosisExplanation(text: String): Boolean {
    val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
    return ("诊断" in normalized || "网络健康" in normalized) &&
        listOf("解释", "分析", "解读", "原因", "怎么办").any { it in normalized }
}

/** Narrow local shortcuts: informational questions and negative commands never start probes. */
fun parseAiDiagnosisAction(text: String): AiDiagnosisAction? {
    val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
    if (asksForDiagnosisExplanation(text)) return null
    if (listOf("不要", "不用", "别", "取消", "停止", "暂不", "不想", "无需").any { it in normalized }) return null
    val topic = listOf("网络健康", "网络诊断", "诊断网络", "诊断一下网络", "诊断结果", "诊断结论", "诊断进度", "诊断状态", "开始诊断", "重新诊断", "再诊断")
        .any { it in normalized }
    if (!topic) return null
    val asksAboutResult = listOf("结果", "结论", "进度", "状态", "完成了吗", "查完了吗").any { it in normalized }
    if (asksAboutResult && listOf("查看", "看看", "查询", "读取", "最近", "上次", "进度", "完成了吗", "查完了吗").any { it in normalized }) {
        return AiDiagnosisAction.SUMMARY
    }
    if (listOf("是什么", "什么意思", "怎么", "如何", "为什么", "介绍", "说明", "解释", "教程", "能否", "是否", "能不能").any { it in normalized }) return null
    if (listOf("开始", "启动", "发起", "重新诊断", "再诊断", "诊断一下", "帮我诊断网络").any { it in normalized }) return AiDiagnosisAction.START
    if (listOf("打开", "进入", "前往").any { it in normalized }) return AiDiagnosisAction.OPEN
    return null
}

/** Adds a bounded, address-free snapshot to the existing model request only for explanation. */
fun aiDiagnosisQuestion(text: String, progress: DiagnosisProgress): String {
    if (!asksForDiagnosisExplanation(text)) return text
    val safeResult = progress.result?.let { result ->
        val safeItems = result.items.map { item ->
            val numericMetric = Regex("(?:\\d+(?:\\.\\d+)? (?:ms|台)|\\d+ / \\d+)").matches(item.metric)
            val publicMetric = item.metric in setOf(
                "未配置", "在线", "待确认", "未检测", "无网关", "未响应", "无连接", "无数据",
                "IPv6 未验证", "已读取", "未确认", "未连接", "未启用", "未同步", "已握手", "等待握手", "超时", "失败",
            )
            item.copy(
                metric = if (numericMetric || publicMetric) item.metric else "详见本地结果",
                explanation = "",
                details = emptyList(),
            )
        }
        // Recompute the same deterministic conclusion instead of passing arbitrary free text.
        DiagnosisRules.summarize(safeItems, result.completedAt)
    }
    val safeProgress = progress.copy(result = safeResult)
    return buildString {
        append(text)
        append("\n\n[APP 已有网络诊断摘要]\n")
        append(aiDiagnosisSummary(safeProgress))
        append("\n[摘要结束]\n")
        append("请基于以上已有采样结果解释结论及可由用户检查的下一步。采样时间见摘要；这是历史检测证据，不是你刚刚执行的新检测。")
        append("没有完整结果时请说明尚未检测完成，不要编造指标；未确认或未启用不能推断为故障。")
        append("区分已观察到的事实和可能原因，不声称根因已证实，不执行或声称执行自动修复。")
    }
}

/** Uses only the existing deterministic result; never claims an LLM made a fresh diagnosis. */
fun aiDiagnosisSummary(progress: DiagnosisProgress): String {
    if (progress.running) {
        return "网络诊断正在进行，已完成 ${progress.items.size} 项，正在检查${progress.current?.title ?: "网络连接"}。完成后可查看诊断结果。"
    }
    if (progress.cancelled) {
        return "本次诊断已停止，已完成 ${progress.items.size} 项，尚未生成完整结论。可以说“开始网络诊断”重新检查。"
    }
    val result = progress.result ?: return "还没有本次应用运行期间的诊断结果。可以说“开始网络诊断”，或“打开网络健康”查看检查入口。"
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(result.completedAt))
    return buildString {
        append("最近一次网络诊断（$stamp）\n")
        append(result.conclusion)
        append("\n\n检查结果")
        result.items.forEach { item ->
            val status = when (item.status) {
                HealthStatus.GOOD -> "正常"
                HealthStatus.WARNING -> "需关注"
                HealthStatus.ERROR -> "异常"
                HealthStatus.UNKNOWN -> "未确认"
            }
            append("\n• ${item.check.title}：$status · ${item.metric}")
            if (item.status != HealthStatus.GOOD && item.explanation.isNotBlank()) append("\n  ${item.explanation}")
        }
        append("\n\n以上是已有检测数据的规则结论；“未确认”表示证据不足，不能据此判定故障。可打开网络健康查看详细结果，或重新诊断更新状态。")
    }
}
