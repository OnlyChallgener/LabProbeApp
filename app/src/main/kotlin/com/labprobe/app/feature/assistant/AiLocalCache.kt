package com.labprobe.app.feature.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Small, identity-scoped device cache for AI UI data.
 *
 * This cache deliberately contains no API key.  The Hub remains authoritative
 * for provider configuration, usage, confirmations and task records; cached
 * values are only used to make the UI useful while a background refresh runs.
 */
class AiLocalCache(context: Context, identity: String) {
    private val prefs = context.applicationContext
        .getSharedPreferences("labprobe_ai_cache", Context.MODE_PRIVATE)
    private val prefix = aiCacheNamespace(identity)

    fun conversationUpdatedAt(): Long = prefs.getLong(prefix + "conversation_updated_at", 0L)
    fun conversationsUpdatedAt(): Long = prefs.getLong(prefix + "conversations_updated_at", 0L)
    fun configsUpdatedAt(): Long = prefs.getLong(prefix + "configs_updated_at", 0L)
    fun usageUpdatedAt(): Long = prefs.getLong(prefix + "usage_updated_at", 0L)

    fun readConversation(): AiCachedConversation? = runCatching {
        val root = prefs.getString(prefix + "conversation", null)?.let(::JSONObject) ?: return null
        val id = root.optString("id").trim().takeIf { it.isNotBlank() } ?: return null
        val rows = root.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val content = item.optString("content")
                if (role in setOf("user", "assistant", "system") && content.isNotBlank()) {
                    add(AiMessage(role, content, serverId = item.optInt("serverId")))
                }
            }
        }
        AiCachedConversation(id, messages)
    }.getOrNull()

    fun writeConversation(id: String?, messages: List<AiMessage>) {
        val cleanId = id?.trim().orEmpty()
        if (cleanId.isBlank()) return
        val kept = boundedMessages(messages)
        val root = JSONObject().put("id", cleanId).put("messages", JSONArray().apply {
            kept.forEach { message ->
                put(JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
                    .put("serverId", message.serverId))
            }
        })
        prefs.edit()
            .putString(prefix + "conversation", root.toString())
            .putLong(prefix + "conversation_updated_at", System.currentTimeMillis())
            .apply()
    }

    fun clearConversation(id: String? = null) {
        val current = readConversation()
        if (id.isNullOrBlank() || current?.id == id) {
            prefs.edit().remove(prefix + "conversation").remove(prefix + "conversation_updated_at").apply()
        }
    }

    fun readConversations(): List<AiConversation> = runCatching {
        val rows = prefs.getString(prefix + "conversations", null)?.let(::JSONArray) ?: return emptyList()
        buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isNotBlank()) add(AiConversation(id, item.optString("title"), item.optString("updatedAt")))
            }
        }
    }.getOrDefault(emptyList())

    fun writeConversations(conversations: List<AiConversation>) {
        val rows = conversations.take(MAX_CONVERSATIONS).map { item ->
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("updatedAt", item.updatedAt)
        }
        prefs.edit()
            .putString(prefix + "conversations", JSONArray(rows).toString())
            .putLong(prefix + "conversations_updated_at", System.currentTimeMillis())
            .apply()
    }

    fun readConfigs(): AiConfigBundle? = runCatching {
        val root = prefs.getString(prefix + "configs", null)?.let(::JSONObject) ?: return null
        val rows = root.optJSONArray("configs") ?: JSONArray()
        val configs = buildList {
            for (index in 0 until rows.length()) {
                rows.optJSONObject(index)?.let { add(parseCachedConfig(it)) }
            }
        }
        AiConfigBundle(root.optBoolean("enabled", configs.any { it.enabled }), configs)
    }.getOrNull()

    fun writeConfigs(bundle: AiConfigBundle) {
        val root = JSONObject().put("enabled", bundle.enabled).put("configs", JSONArray().apply {
            bundle.configs.forEach { config ->
                put(JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("provider", config.provider)
                    .put("enabled", config.enabled)
                    .put("model", config.model)
                    .put("baseUrl", config.baseUrl)
                    // Only the boolean status is cached, never the secret itself.
                    .put("hasApiKey", config.hasApiKey)
                    .put("tokenQuota", config.tokenQuota ?: JSONObject.NULL))
            }
        })
        prefs.edit()
            .putString(prefix + "configs", root.toString())
            .putLong(prefix + "configs_updated_at", System.currentTimeMillis())
            .apply()
    }

    fun readUsage(): AiUsageSummary? = runCatching {
        val root = prefs.getString(prefix + "usage", null)?.let(::JSONObject) ?: return null
        parseCachedUsage(root)
    }.getOrNull()

    fun writeUsage(summary: AiUsageSummary) {
        val root = JSONObject()
            .put("requests", summary.requests)
            .put("promptTokens", summary.promptTokens)
            .put("completionTokens", summary.completionTokens)
            .put("totalTokens", summary.totalTokens)
            .put("todayRequests", summary.todayRequests)
            .put("todayTotalTokens", summary.todayTotalTokens)
            .put("storageConversations", summary.storageConversations)
            .put("storageMessages", summary.storageMessages)
            .put("storageBytes", summary.storageBytes)
            .put("recent", JSONArray().apply {
                summary.recent.take(MAX_RECENT_USAGE).forEach { item ->
                    put(JSONObject()
                        .put("id", item.id)
                        .put("conversationId", item.conversationId)
                        .put("provider", item.provider)
                        .put("model", item.model)
                        .put("promptTokens", item.promptTokens)
                        .put("completionTokens", item.completionTokens)
                        .put("totalTokens", item.totalTokens)
                        .put("status", item.status)
                        .put("usageKnown", item.usageKnown)
                        .put("createdAt", item.createdAt))
                }
            })
            .put("daily", JSONArray().apply {
                summary.daily.take(MAX_DAILY_USAGE).forEach { item ->
                    put(JSONObject()
                        .put("date", item.date)
                        .put("requests", item.requests)
                        .put("totalTokens", item.totalTokens)
                        .put("promptTokens", item.promptTokens)
                        .put("completionTokens", item.completionTokens)
                        .put("cacheHitTokens", item.cacheHitTokens)
                        .put("cacheMissTokens", item.cacheMissTokens)
                        .put("cacheReportedInputTokens", item.cacheReportedInputTokens)
                        .put("models", JSONObject().apply { item.models.forEach { (key, value) -> put(key, value) } }))
                }
            })
            .put("configUsage", JSONArray().apply {
                summary.configUsage.forEach { item ->
                    put(JSONObject()
                        .put("configId", item.configId)
                        .put("name", item.name)
                        .put("model", item.model)
                        .put("promptTokens", item.promptTokens)
                        .put("completionTokens", item.completionTokens)
                        .put("totalTokens", item.totalTokens)
                        .put("tokenQuota", item.tokenQuota ?: JSONObject.NULL))
                }
            })
        prefs.edit()
            .putString(prefix + "usage", root.toString())
            .putLong(prefix + "usage_updated_at", System.currentTimeMillis())
            .apply()
    }

    private fun parseCachedConfig(item: JSONObject) = AiProviderConfig(
        id = item.optString("id"),
        name = item.optString("name"),
        provider = item.optString("provider", "openai_compatible"),
        enabled = item.optBoolean("enabled", true),
        model = item.optString("model"),
        baseUrl = item.optString("baseUrl"),
        hasApiKey = item.optBoolean("hasApiKey", false),
        tokenQuota = item.optLong("tokenQuota").takeIf { it > 0L },
    )

    private fun parseCachedUsage(root: JSONObject): AiUsageSummary {
        val recent = buildList {
            val rows = root.optJSONArray("recent") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                add(AiUsageRecord(
                    id = item.optInt("id"),
                    conversationId = item.optString("conversationId"),
                    provider = item.optString("provider"),
                    model = item.optString("model"),
                    promptTokens = item.optInt("promptTokens"),
                    completionTokens = item.optInt("completionTokens"),
                    totalTokens = item.optInt("totalTokens"),
                    status = item.optString("status", "completed"),
                    usageKnown = item.optBoolean("usageKnown", true),
                    createdAt = item.optString("createdAt"),
                ))
            }
        }
        val daily = buildList {
            val rows = root.optJSONArray("daily") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val modelsRoot = item.optJSONObject("models") ?: JSONObject()
                val models = buildMap {
                    val keys = modelsRoot.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, modelsRoot.optLong(key))
                    }
                }
                add(AiUsageDay(
                    date = item.optString("date"),
                    requests = item.optInt("requests"),
                    totalTokens = item.optInt("totalTokens"),
                    promptTokens = item.optInt("promptTokens"),
                    completionTokens = item.optInt("completionTokens"),
                    cacheHitTokens = item.optInt("cacheHitTokens"),
                    cacheMissTokens = item.optInt("cacheMissTokens"),
                    cacheReportedInputTokens = item.optInt("cacheReportedInputTokens"),
                    models = models,
                ))
            }
        }
        val configUsage = buildList {
            val rows = root.optJSONArray("configUsage") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                add(AiConfigUsage(
                    configId = item.optString("configId"),
                    name = item.optString("name"),
                    model = item.optString("model"),
                    promptTokens = item.optLong("promptTokens"),
                    completionTokens = item.optLong("completionTokens"),
                    totalTokens = item.optLong("totalTokens"),
                    tokenQuota = item.optLong("tokenQuota").takeIf { it > 0L },
                ))
            }
        }
        return AiUsageSummary(
            requests = root.optInt("requests"),
            promptTokens = root.optInt("promptTokens"),
            completionTokens = root.optInt("completionTokens"),
            totalTokens = root.optInt("totalTokens"),
            todayRequests = root.optInt("todayRequests"),
            todayTotalTokens = root.optInt("todayTotalTokens"),
            recent = recent,
            daily = daily,
            storageConversations = root.optInt("storageConversations"),
            storageMessages = root.optInt("storageMessages"),
            storageBytes = root.optLong("storageBytes"),
            configUsage = configUsage,
        )
    }

    private fun boundedMessages(messages: List<AiMessage>): List<AiMessage> {
        val result = ArrayDeque<AiMessage>()
        var chars = 0
        messages.asReversed().forEach { message ->
            if (result.size >= MAX_MESSAGES || chars + message.content.length > MAX_MESSAGE_CHARS) return@forEach
            result.addFirst(message)
            chars += message.content.length
        }
        return result.toList()
    }

    private companion object {
        const val MAX_MESSAGES = 120
        const val MAX_MESSAGE_CHARS = 96 * 1024
        const val MAX_CONVERSATIONS = 100
        const val MAX_RECENT_USAGE = 100
        const val MAX_DAILY_USAGE = 30

    }
}

data class AiCachedConversation(val id: String, val messages: List<AiMessage>)

/** Stable partition key that never exposes the Hub token in preferences. */
internal fun aiCacheNamespace(identity: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
    return "v1_${digest}_"
}
