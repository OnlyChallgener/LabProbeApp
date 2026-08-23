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
    val confirmation: AiToolConfirmation? = null,
)

data class AiToolHint(
    val id: String,
    val name: String,
    val example: String,
    val risk: String,
)

data class AiToolConfirmation(
    val confirmationId: String,
    val toolId: String,
    val title: String,
    val summary: String,
    val executor: String,
    val arguments: Map<String, String>,
    val expiresAt: String,
)

data class AiNotification(
    val id: Int,
    val kind: String,
    val title: String,
    val content: String,
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
