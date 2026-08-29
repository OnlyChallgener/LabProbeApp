package com.labprobe.app.feature.assistant

data class AiSettings(
    val enabled: Boolean = false,
    val model: String = "deepseek-v4-flash",
    val baseUrl: String = "https://api.deepseek.com",
    val hasApiKey: Boolean = false
)

data class AiProviderConfig(
    val id: String = "",
    val name: String = "",
    val provider: String = "openai_compatible",
    val enabled: Boolean = true,
    val model: String = "deepseek-v4-flash",
    val baseUrl: String = "https://api.deepseek.com",
    val hasApiKey: Boolean = false,
    val tokenQuota: Long? = null,
)

data class AiConfigBundle(
    val enabled: Boolean = false,
    val configs: List<AiProviderConfig> = emptyList(),
)

data class AiMessage(
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class AiTokenSummary(val prompt: Int = 0, val completion: Int = 0) {
    val total: Int get() = prompt + completion
}

data class AiReply(
    val content: String,
    val usage: AiTokenSummary = AiTokenSummary(),
    val conversationId: String? = null,
    val confirmation: AiToolConfirmation? = null,
    val clientActions: List<AiClientAction> = emptyList(),
    val usageKnown: Boolean = true,
)

data class AiConversation(
    val id: String,
    val title: String,
    val updatedAt: String,
)

data class AiClientAction(
    val type: String,
    val route: String = "",
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
    val recent: List<AiUsageRecord> = emptyList(),
    val daily: List<AiUsageDay> = emptyList(),
    val storageConversations: Int = 0,
    val storageMessages: Int = 0,
    val storageBytes: Long = 0,
    val configUsage: List<AiConfigUsage> = emptyList(),
)

data class AiConfigUsage(
    val configId: String = "",
    val name: String = "",
    val model: String = "",
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val tokenQuota: Long? = null,
)

data class AiUsageDay(
    val date: String,
    val requests: Int,
    val totalTokens: Int,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int,
    val models: Map<String, Long> = emptyMap(),
)

data class AiUsageRecord(
    val id: Int,
    val conversationId: String,
    val provider: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val status: String,
    val usageKnown: Boolean,
    val createdAt: String,
)

sealed class AiConnectionState {
    data object Idle : AiConnectionState()
    data object Testing : AiConnectionState()
    data class Success(val message: String = "连接成功") : AiConnectionState()
    data class Failure(val message: String) : AiConnectionState()
}
