package com.labprobe.app

import org.json.JSONObject

/** Freshness is a Hub-reported duration, never a comparison of two devices' clocks. */
internal fun routerDiagnosisItem(
    snapshot: RouterHubStatus,
    sample: JSONObject?,
    be50: Boolean,
    checkedAt: Long = System.currentTimeMillis(),
): DiagnosisItem {
    val connected = snapshot.connected || snapshot.sessionConnected
    val fresh = sample != null && freshLiteRealtimeSample(sample)
    val agentFresh = be50 && fresh && sample?.optString("source") == "agent_dashboard_push"
    val good = if (be50) agentFresh else connected && snapshot.dataAvailable && fresh
    val unconfigured = snapshot.state in setOf("checking", "unconfigured", "not_configured", "disabled")
    val status = when {
        good -> HealthStatus.GOOD
        be50 || unconfigured || connected -> HealthStatus.UNKNOWN
        else -> HealthStatus.WARNING
    }
    val explanation = when {
        be50 && good -> "BE50 本地采样正常"
        be50 -> "等待 BE50 本地采样；Hub 连接不代表路由器数据已更新"
        good -> "Hub 与 Router 控制链路正常"
        connected && !snapshot.dataAvailable -> "Router 会话在线，控制数据仍待确认"
        connected && sample != null && !fresh -> "Router 会话在线，实时采样已过期或不完整，等待更新"
        connected -> "Router 会话在线，等待有效实时采样"
        else -> "Hub 暂未确认 Router 在线"
    }
    return DiagnosisItem(
        DiagnosisCheck.ROUTER, status, if (good) "在线" else "待确认", explanation,
        listOf(
            "观测点：Hub → Router", "会话已连接：$connected", "控制数据可用：${snapshot.dataAvailable}",
            "实时采样年龄：${sample?.optLong("sampleAgeMs", -1L) ?: -1L} ms",
            "实时数据新鲜度已确认：$fresh",
        ), checkedAt,
    )
}
