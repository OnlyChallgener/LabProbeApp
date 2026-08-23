package com.labprobe.app.feature.assistant

data class AiSettings(
    val enabled: Boolean = false,
    val model: String = "deepseek-v4-flash",
    val baseUrl: String = "",
    val hasApiKey: Boolean = false
)

data class AiMessage(val role: String, val content: String, val createdAt: Long = System.currentTimeMillis())

data class AiTokenSummary(val prompt: Int = 0, val completion: Int = 0) {
    val total: Int get() = prompt + completion
}

data class AiReply(
    val content: String,
    val usage: AiTokenSummary = AiTokenSummary(),
    val conversationId: String? = null,
)

data class AiUsageSummary(
    val requests: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val todayRequests: Int = 0,
    val todayTotalTokens: Int = 0,
)

sealed class AiConnectionState {
    data object Idle : AiConnectionState()
    data object Testing : AiConnectionState()
    data class Success(val message: String = "连接成功") : AiConnectionState()
    data class Failure(val message: String) : AiConnectionState()
}
