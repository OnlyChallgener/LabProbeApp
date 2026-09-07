package com.labprobe.app

enum class HealthStatus { GOOD, WARNING, ERROR, UNKNOWN }

enum class DiagnosisCheck(val title: String) {
    ROUTER("Router"), GATEWAY("局域网"), INTERNET("互联网"), DNS("DNS"),
    IPV6("IPv4 / IPv6"), DEVICES("设备实时状态"), RELAY("Relay"), STUN("STUN"), WIREGUARD("WireGuard")
}

data class DiagnosisItem(
    val check: DiagnosisCheck,
    val status: HealthStatus = HealthStatus.UNKNOWN,
    val metric: String = "待检测",
    val explanation: String = "开始诊断后查看结果",
    val details: List<String> = emptyList(),
    val checkedAt: Long = 0L,
)

data class DiagnosisResult(
    val items: List<DiagnosisItem>,
    val status: HealthStatus,
    val conclusion: String,
    val completedAt: Long,
)

data class DiagnosisProgress(
    val items: List<DiagnosisItem> = emptyList(),
    val current: DiagnosisCheck? = null,
    val running: Boolean = false,
    val result: DiagnosisResult? = null,
    val cancelled: Boolean = false,
)
