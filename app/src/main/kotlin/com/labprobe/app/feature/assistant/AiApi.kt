package com.labprobe.app.feature.assistant

import android.content.Context
import com.labprobe.app.AppPrefs
import com.labprobe.app.favoriteShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 流式请求在响应建立前失败；请求是否到达 Hub 不可判定，因此不得自动重跑。 */
class AiStreamNotEstablished(message: String) : IllegalStateException(message)

/** 流式协议已建立后的失败（上游报错、中断）——重跑会双倍消耗 token，调用方必须原样呈现。 */
class AiStreamProtocolException(
    message: String,
    val conversationId: String? = null,
    val userMessageId: Int = 0,
    val messageId: Int = 0,
) : IllegalStateException(message)

/** Only non-secret AI preferences and the Hub-reported key status live on the device. */
class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("labprobe_ai", Context.MODE_PRIVATE)

    fun read() = AiSettings(
        enabled = prefs.getBoolean("enabled", false),
        model = prefs.getString("model", "deepseek-v4-flash") ?: "deepseek-v4-flash",
        baseUrl = prefs.getString("base_url", "https://api.deepseek.com") ?: "https://api.deepseek.com",
        hasApiKey = prefs.getBoolean("has_key", false),
    )

    fun save(settings: AiSettings, hasApiKey: Boolean = settings.hasApiKey) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("model", settings.model.trim())
            .putString("base_url", settings.baseUrl.trim())
            .putBoolean("has_key", hasApiKey)
            .apply()
    }

    fun deleteKey() = prefs.edit().putBoolean("has_key", false).apply()

    fun lastNotificationId(hubIdentity: String): Int {
        val key = notificationCursorKey(hubIdentity)
        if (prefs.contains(key)) return prefs.getInt(key, 0).coerceAtLeast(0)
        // Preserve the cursor written by older builds while moving away from the
        // collision-prone String.hashCode preference key.
        return prefs.getInt("last_notification_id_${hubIdentity.hashCode()}", 0).coerceAtLeast(0)
    }

    fun saveLastNotificationId(hubIdentity: String, value: Int) = prefs.edit()
        .putInt(notificationCursorKey(hubIdentity), value.coerceAtLeast(0)).apply()

    private fun notificationCursorKey(hubIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hubIdentity.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "last_notification_id_$digest"
    }
}

class AiApiClient(
    private val settings: AiSettingsStore,
    private val hubUrl: String,
    private val hubToken: String,
    private val http: OkHttpClient = defaultAiHttpClient(),
    private val appPrefs: AppPrefs? = null,
) {
    /** Hub URL plus credential partitions process caches and notification cursors. */
    val identity: String = "${hubUrl.trimEnd('/')}#${hubToken.trim()}"
    suspend fun configs(): AiConfigBundle = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/config", "GET", null).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
            parseConfigBundle(JSONObject(body))
        }
    }

    suspend fun saveProviderConfig(config: AiProviderConfig, apiKey: String? = null): AiProviderConfig = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val payload = JSONObject()
            .put("name", config.name.trim())
            .put("provider", config.provider.trim().ifBlank { "openai_compatible" })
            .put("enabled", config.enabled)
            .put("model", config.model.trim())
            .put("baseUrl", config.baseUrl.trim())
            .put("tokenQuota", config.tokenQuota ?: JSONObject.NULL)
        if (config.id.isNotBlank()) payload.put("id", config.id)
        if (!apiKey.isNullOrBlank()) payload.put("apiKey", apiKey)
        val method = if (config.id.isBlank()) "POST" else "PUT"
        request(hubUrl.trimEnd('/') + "/api/ai/config", method, payload.toString()).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
            val root = JSONObject(body)
            val rows = root.optJSONArray("configs")
            val returned = if (rows != null) {
                val parsedRows = buildList {
                    for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { add(parseProviderConfig(it)) }
                }
                parsedRows.firstOrNull { config.id.isNotBlank() && it.id == config.id } ?: parsedRows.lastOrNull()
            } else null
            val parsed = returned ?: parseProviderConfig(root.optJSONObject("config") ?: root, config)
            parsed.copy(hasApiKey = config.hasApiKey || !apiKey.isNullOrBlank() || parsed.hasApiKey)
        }
    }

    suspend fun saveConfig(config: AiSettings, apiKey: String? = null): AiSettings = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val payload = JSONObject()
            .put("enabled", config.enabled)
            .put("model", config.model.trim())
            .put("baseUrl", config.baseUrl.trim())
        if (!apiKey.isNullOrBlank()) payload.put("apiKey", apiKey)
        request(hubUrl.trimEnd('/') + "/api/ai/config", "PUT", payload.toString()).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
            val root = JSONObject(body)
            AiSettings(
                enabled = root.optBoolean("enabled", config.enabled),
                model = root.optString("model", config.model),
                baseUrl = root.optString("baseUrl", config.baseUrl),
                hasApiKey = root.optString("apiKeyStatus") == "configured",
            )
        }
    }

    suspend fun testConnection(configId: String? = null): String = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val testUrl = if (!configId.isNullOrBlank() && configId != "legacy") {
            hubUrl.trimEnd('/') + "/api/ai/config/${java.net.URLEncoder.encode(configId, "UTF-8")}/test"
        } else {
            hubUrl.trimEnd('/') + "/api/ai/test"
        }
        request(testUrl, "POST", "{}").use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
            "连接成功"
        }
    }

    suspend fun deleteConfig() = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/config", "DELETE", null).use { response ->
            if (!response.isSuccessful && response.code != 204) error("HTTP ${response.code}")
        }
    }

    suspend fun usage(): AiUsageSummary = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
            request(hubUrl.trimEnd('/') + "/api/ai/usage", "GET", null).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(apiFailure(response.code, text))
                val root = JSONObject(text)
                val recentRoot = root.optJSONArray("recent") ?: JSONArray()
                val recent = buildList {
                    for (index in 0 until recentRoot.length()) {
                        val item = recentRoot.optJSONObject(index) ?: continue
                        add(AiUsageRecord(
                            id = item.optInt("id"),
                            conversationId = item.optString("conversation_id"),
                            provider = item.optString("provider"),
                            model = item.optString("model"),
                            promptTokens = item.optInt("prompt_tokens"),
                            completionTokens = item.optInt("completion_tokens"),
                            totalTokens = item.optInt("total_tokens"),
                            status = item.optString("status", "completed"),
                            usageKnown = when (val known = item.opt("usage_known")) {
                                is Boolean -> known
                                is Number -> known.toInt() != 0
                                else -> known?.toString()?.toBooleanStrictOrNull() ?: true
                            },
                            createdAt = item.optString("created_at"),
                        ))
                    }
                }
                val dailyRoot = root.optJSONArray("daily") ?: JSONArray()
                val daily = buildList {
                    for (index in 0 until dailyRoot.length()) {
                        val item = dailyRoot.optJSONObject(index) ?: continue
                        val modelsJson = item.optJSONObject("models") ?: JSONObject()
                        val models = buildMap {
                            val keys = modelsJson.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, modelsJson.optLong(key))
                            }
                        }
                        add(AiUsageDay(
                            date = item.optString("date"),
                            requests = item.optInt("requests"),
                            totalTokens = item.optInt("total_tokens"),
                            promptTokens = item.optInt("prompt_tokens", item.optInt("input_tokens", 0)),
                            completionTokens = item.optInt("completion_tokens", item.optInt("output_tokens", 0)),
                            cacheHitTokens = item.optInt("cache_hit_tokens"),
                            cacheMissTokens = item.optInt("cache_miss_tokens"),
                            models = models,
                        ))
                    }
                }
                val storage = root.optJSONObject("storage") ?: JSONObject()
                val configUsageRoot = root.optJSONArray("config_usage")
                    ?: root.optJSONArray("configUsage")
                    ?: root.optJSONArray("model_usage")
                    ?: root.optJSONArray("modelUsage")
                    ?: JSONArray()
                val configUsage = buildList {
                    for (index in 0 until configUsageRoot.length()) {
                        val item = configUsageRoot.optJSONObject(index) ?: continue
                        add(AiConfigUsage(
                            configId = item.optString("config_id", item.optString("configId")),
                            name = item.optString("name", item.optString("config_name", item.optString("configName"))),
                            model = item.optString("model"),
                            promptTokens = item.optLong("prompt_tokens", item.optLong("input_tokens", 0L)),
                            completionTokens = item.optLong("completion_tokens", item.optLong("output_tokens", 0L)),
                            totalTokens = item.optLong("total_tokens", item.optLong("used_tokens", 0L)),
                            tokenQuota = item.optNullableLong("token_quota", "tokenQuota", "quota_tokens"),
                        ))
                    }
                }
                AiUsageSummary(
                    requests = root.optInt("requests", 0),
                    promptTokens = root.optInt("prompt_tokens", 0),
                    completionTokens = root.optInt("completion_tokens", 0),
                    totalTokens = root.optInt("total_tokens", 0),
                    todayRequests = root.optInt("today_requests", 0),
                    todayTotalTokens = root.optInt("today_total_tokens", 0),
                    recent = recent,
                    daily = daily,
                    storageConversations = storage.optInt("conversations", 0),
                    storageMessages = storage.optInt("messages", 0),
                    storageBytes = storage.optLong("bytes", 0L),
                    configUsage = configUsage,
                )
            }
    }

    suspend fun conversationMessages(conversationId: String): List<AiMessage> = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/conversations/${java.net.URLEncoder.encode(conversationId, "UTF-8")}/messages", "GET", null).use { response ->
            val messagesText = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, messagesText))
            val raw = JSONObject(messagesText).optJSONArray("messages") ?: JSONArray()
            buildList {
                for (index in 0 until raw.length()) {
                    val item = raw.optJSONObject(index) ?: continue
                    val role = item.optString("role")
                    val content = item.optString("content")
                    if (role in setOf("user", "assistant", "system") && content.isNotBlank()) {
                        add(AiMessage(role, content, serverId = item.optInt("id")))
                    }
                }
            }
        }
    }

    suspend fun listConversations(limit: Int? = null): List<AiConversation> = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val suffix = limit?.let { "?limit=$it" }.orEmpty()
        request(hubUrl.trimEnd('/') + "/api/ai/conversations$suffix", "GET", null).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            val rows = JSONObject(text).optJSONArray("conversations") ?: JSONArray()
            buildList {
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: continue
                    add(AiConversation(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        updatedAt = item.optString("updated_at"),
                    ))
                }
            }
        }
    }

    suspend fun latestConversation(): Pair<String?, List<AiMessage>> = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/conversations?limit=1", "GET", null).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            val rows = JSONObject(text).optJSONArray("conversations") ?: JSONArray()
            val id = rows?.optJSONObject(0)?.optString("id")?.takeIf { !it.isNullOrBlank() }
            if (id == null) return@withContext null to emptyList()
            id to conversationMessages(id)
        }
    }

    suspend fun catalog(): List<AiToolHint> = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/catalog", "GET", null).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            val rows = JSONObject(text).optJSONArray("tools") ?: JSONArray()
            buildList {
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: continue
                    val examples = item.optJSONArray("examples")
                    val example = examples?.optString(0).orEmpty()
                    if (example.isNotBlank()) add(AiToolHint(
                        id = item.optString("id"),
                        name = item.optString("name", example),
                        example = example,
                        risk = item.optString("risk", "read"),
                    ))
                }
            }
        }
    }

    suspend fun notifications(afterId: Int): List<AiNotification> = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/notifications?after=${afterId.coerceAtLeast(0)}", "GET", null).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            val rows = JSONObject(text).optJSONArray("notifications") ?: JSONArray()
            buildList {
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: continue
                    add(AiNotification(
                        id = item.optInt("id"),
                        kind = item.optString("kind"),
                        title = item.optString("title"),
                        content = item.optString("content"),
                    ))
                }
            }
        }
    }

    /** Always ask the Hub which confirmation is still pending for this conversation. */
    suspend fun pendingConfirmation(conversationId: String): AiToolConfirmation? = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val encoded = java.net.URLEncoder.encode(conversationId, "UTF-8")
        request("${hubUrl.trimEnd('/')}/api/ai/conversations/$encoded/confirmations", "GET", null).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
            val root = JSONObject(body)
            val item = root.optJSONArray("confirmations")?.optJSONObject(0)
                ?: root.optJSONObject("confirmation")
                ?: root.takeIf { it.has("confirmationId") }
            item?.let(::parseToolConfirmation)
        }
    }

    /** Used after an ambiguous execute response; do not claim success without Hub state. */
    suspend fun toolConfirmationStatus(confirmationId: String): String = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(confirmationId, "UTF-8")
        request("${hubUrl.trimEnd('/')}/api/ai/tools/confirmations/$encoded", "GET", null).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
            val root = JSONObject(body)
            root.optString("status").ifBlank { root.optJSONObject("confirmation")?.optString("status").orEmpty() }
        }
    }

    suspend fun chat(message: String, conversationId: String? = null): AiReply = withContext(Dispatchers.IO) {
        val current = settings.read()
        require(current.enabled) { "请先启用 Hub AI" }
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val body = JSONObject().put("message", message).apply {
            if (!conversationId.isNullOrBlank()) put("conversationId", conversationId)
            localContext()?.let { put("clientContext", it) }
        }.toString()
        request(hubUrl.trimEnd('/') + "/api/ai/chat", "POST", body).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            parseChatReply(JSONObject(text))
        }
    }

    /**
     * 流式对话：消费 Hub 的 typed SSE（delta/tool/confirmation/done/error）。
     * onDelta 在主线程收到增量文本；返回值以 done/confirmation 载荷为准。
     *
     * 回退语义（用户视角：绝不因重试而双倍烧 token）：
     * - 响应建立前失败会抛 [AiStreamNotEstablished]；请求可能已经到达 Hub，不得自动重跑；
     * - 协议建立后的一切失败（上游报错、中断、超时）抛 [AiStreamProtocolException]，最终失败。
     */
    suspend fun chatStream(
        message: String,
        conversationId: String? = null,
        onDelta: (String) -> Unit,
        onReset: () -> Unit = {},
    ): AiReply {
        val current = settings.read()
        require(current.enabled) { "请先启用 Hub AI" }
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        return withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("message", message)
                .put("stream", true)
                .apply {
                    if (!conversationId.isNullOrBlank()) put("conversationId", conversationId)
                    localContext()?.let { put("clientContext", it) }
                }.toString()
            val response = try {
                request(hubUrl.trimEnd('/') + "/api/ai/chat", "POST", body, longRead = true)
            } catch (io: java.io.IOException) {
                throw AiStreamNotEstablished("连接 Hub 失败：${io.message ?: "网络错误"}")
            }
            response.use {
                if (!response.isSuccessful) {
                    throw streamProtocolFailure(response.code, response.body?.string().orEmpty())
                }
                if (!response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                    // 旧 Hub 直接返回完整 JSON。
                    return@withContext parseChatReply(JSONObject(response.body?.string().orEmpty()))
                }
                var reply: AiReply? = null
                var eventConversationId: String? = conversationId
                var eventUserMessageId = 0
                try {
                    val reader = response.body?.byteStream()?.bufferedReader(Charsets.UTF_8)
                        ?: throw AiStreamProtocolException("Hub 返回了空数据流", eventConversationId, eventUserMessageId)
                    reader.useLines { lines ->
                        for (raw in lines) {
                            if (!isActive) break
                            val line = raw.trim()
                            if (line.isEmpty() || line.startsWith(":") || !line.startsWith("data:")) continue
                            val event = try {
                                JSONObject(line.removePrefix("data:").trim())
                            } catch (error: Throwable) {
                                throw AiStreamProtocolException("AI 流数据格式错误：${error.message ?: "无法解析"}", eventConversationId, eventUserMessageId)
                            }
                            eventConversationId = event.optString("conversationId").takeIf { it.isNotBlank() } ?: eventConversationId
                            eventUserMessageId = event.optInt("userMessageId", eventUserMessageId)
                            when (event.optString("type")) {
                                "delta" -> {
                                    val piece = event.optString("content")
                                    if (piece.isNotEmpty()) withContext(Dispatchers.Main) { onDelta(piece) }
                                }
                                "reset" -> withContext(Dispatchers.Main) { onReset() }
                                "confirmation", "done" -> reply = try {
                                    parseChatReply(event)
                                } catch (error: Throwable) {
                                    throw AiStreamProtocolException("AI 流响应无效：${error.message ?: "无法解析"}", eventConversationId, eventUserMessageId)
                                }
                                "error" -> throw AiStreamProtocolException(
                                    message = event.optString("error").ifBlank { "AI 服务暂不可用" },
                                    conversationId = eventConversationId,
                                    userMessageId = eventUserMessageId,
                                    messageId = event.optInt("messageId"),
                                )
                            }
                            if (reply != null) break
                        }
                    }
                } catch (io: java.io.IOException) {
                    throw AiStreamProtocolException(
                        message = "AI 流连接中断：${io.message ?: "网络连接已断开"}",
                        conversationId = eventConversationId,
                        userMessageId = eventUserMessageId,
                    )
                }
                if (reply == null) ensureActive()
                reply ?: throw AiStreamProtocolException("AI 返回为空", eventConversationId, eventUserMessageId)
            }
        }
    }

    suspend fun confirmHubTool(confirmationId: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("confirmationId", confirmationId).toString()
        request(hubUrl.trimEnd('/') + "/api/ai/tools/confirm", "POST", payload).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            val result = JSONObject(text).optJSONObject("result") ?: JSONObject()
            result.optString("message").ifBlank { "操作已完成" }
        }
    }

    suspend fun cancelHubTool(confirmationId: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("confirmationId", confirmationId).toString()
        request(hubUrl.trimEnd('/') + "/api/ai/tools/cancel", "POST", payload).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            JSONObject(text).optJSONObject("result")?.optString("message").orEmpty().ifBlank { "操作已取消" }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val encoded = java.net.URLEncoder.encode(conversationId, "UTF-8")
        request(hubUrl.trimEnd('/') + "/api/ai/conversations/$encoded", "DELETE", null).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 204) error(apiFailure(response.code, body))
        }
    }

    /** 删除会话中的单条消息（不影响已记录的 Token 用量）。 */
    suspend fun deleteConversationMessage(conversationId: String, messageId: Int) = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val encoded = java.net.URLEncoder.encode(conversationId, "UTF-8")
        request(hubUrl.trimEnd('/') + "/api/ai/conversations/$encoded/messages/$messageId", "DELETE", null).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
        }
    }

    suspend fun renameConversation(conversationId: String, title: String): AiConversation = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "对话名称不能为空" }
        val encoded = java.net.URLEncoder.encode(conversationId, "UTF-8")
        val payload = JSONObject().put("title", cleanTitle).toString()
        request(hubUrl.trimEnd('/') + "/api/ai/conversations/$encoded", "PATCH", payload).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
            val item = JSONObject(body).optJSONObject("conversation") ?: JSONObject(body)
            AiConversation(
                id = item.optString("id", conversationId),
                title = item.optString("title", cleanTitle),
                updatedAt = item.optString("updated_at", item.optString("updatedAt")),
            )
        }
    }

    /** 把某配置置顶：对话固定使用第一个启用的配置。 */
    suspend fun promoteConfig(configId: String): AiProviderConfig = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/config/$configId/promote", "POST", null).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
            parseProviderConfig(JSONObject(body))
        }
    }

    /** 上移/下移配置（用户自定义排序，不再受添加时间约束）。 */
    suspend fun moveConfig(configId: String, direction: String) = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val payload = JSONObject().put("direction", direction).toString()
        request(hubUrl.trimEnd('/') + "/api/ai/config/$configId/move", "POST", payload).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
        }
    }

    /** 手动校准某配置的累计用量（插入带符号的调整行，历史保留）。 */
    /** Hub applies the calibration and quota update atomically. */
    suspend fun adjustUsage(configId: String, totalTokens: Long, tokenQuota: Long? = null, updateQuota: Boolean = false) = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val payload = JSONObject().put("configId", configId).put("totalTokens", totalTokens)
        if (updateQuota) payload.put("tokenQuota", tokenQuota ?: JSONObject.NULL)
        request(hubUrl.trimEnd('/') + "/api/ai/usage/adjust", "POST", payload).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
        }
    }

    /** 删除一条任务用量记录（不影响其他统计）。 */
    suspend fun deleteUsageRecord(usageId: Int) = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/usage/$usageId", "DELETE", null).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, body))
        }
    }

    suspend fun deleteProviderConfig(configId: String) = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val encoded = java.net.URLEncoder.encode(configId, "UTF-8")
        request(hubUrl.trimEnd('/') + "/api/ai/config/$encoded", "DELETE", null).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 204) error(apiFailure(response.code, body))
        }
    }

    suspend fun completeClientTool(confirmationId: String, ok: Boolean, message: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("confirmationId", confirmationId)
            .put("ok", ok)
            .put("message", message)
            .toString()
        request(hubUrl.trimEnd('/') + "/api/ai/tools/complete", "POST", payload).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            JSONObject(text).optJSONObject("result")?.optString("message").orEmpty().ifBlank { message }
        }
    }

    private fun localContext(): JSONObject? = appPrefs?.let { prefs ->
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("settings", JSONObject()
                .put("privacyMode", prefs.privacyMode)
                .put("favoriteNetworkMode", prefs.favoriteNetworkMode)
                .put("routerDisplayName", prefs.routerDisplayName))
            .put("favorites", JSONArray().apply {
                prefs.favoriteShortcuts().take(100).forEach { item ->
                    put(JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("description", item.description)
                        .put("localUrl", aiSafeContextUrl(item.localEndpoint.ifBlank { item.lanUrl }))
                        .put("remoteUrl", aiSafeContextUrl(item.remoteEndpoint.ifBlank { item.wanUrl }))
                        .put("serviceType", item.serviceType))
                }
            })
        val favorites = root.optJSONArray("favorites") ?: JSONArray()
        while (root.toString().toByteArray(Charsets.UTF_8).size > 32 * 1024 && favorites.length() > 0) {
            favorites.remove(favorites.length() - 1)
        }
        if (root.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024) root
        else JSONObject().put("schemaVersion", 1) // Never exceed the Hub's 32 KiB client-context cap.
    }

    private suspend fun request(url: String, method: String, json: String?, longRead: Boolean = false): okhttp3.Response {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (hubToken.isNotBlank()) builder.header("Authorization", "Bearer ${hubToken.trim()}")
        when (method) {
            "POST" -> builder.post((json ?: "{}").toRequestBody("application/json".toMediaType()))
            "PUT" -> builder.put((json ?: "{}").toRequestBody("application/json".toMediaType()))
            "PATCH" -> builder.patch((json ?: "{}").toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        return suspendCancellableCoroutine { continuation ->
            // Stream setup still uses the short connect timeout; only an established SSE read gets longer.
            val call = (if (longRead) http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() else http).newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }
    }
}

private fun aiSafeContextUrl(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val uri = java.net.URI(raw)
        val query = uri.rawQuery?.split('&')?.joinToString("&") { part ->
            val key = part.substringBefore('=')
            if (key.contains(Regex("token|key|secret|pass|auth|sign|session", RegexOption.IGNORE_CASE))) "$key=<redacted>" else part
        }
        java.net.URI(uri.scheme, null, uri.host, uri.port, uri.rawPath, query, null).toString()
    }.getOrDefault("")
}

private fun parseChatReply(root: JSONObject): AiReply {
    val content = root.optJSONObject("message")?.optString("content").orEmpty()
    require(content.isNotBlank()) { "AI 返回为空" }
    val usage = root.optJSONObject("usage")
    val confirmation = root.optJSONObject("confirmation")?.let(::parseToolConfirmation)
    return AiReply(
        content,
        AiTokenSummary(
            prompt = usage?.optInt("prompt_tokens", 0) ?: 0,
            completion = usage?.optInt("completion_tokens", 0) ?: 0,
        ),
        conversationId = root.optString("conversationId").takeIf { it.isNotBlank() },
        confirmation = confirmation,
        usageKnown = root.optBoolean("usageKnown", true),
        messageId = root.optInt("messageId"),
        userMessageId = root.optInt("userMessageId"),
        clientActions = buildList {
            val actions = root.optJSONArray("clientActions") ?: JSONArray()
            for (index in 0 until actions.length()) {
                val item = actions.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (type.isNotBlank()) add(AiClientAction(type, item.optString("route")))
            }
        },
    )
}

private fun parseToolConfirmation(confirmationRoot: JSONObject): AiToolConfirmation {
    val preview = confirmationRoot.optJSONObject("preview") ?: confirmationRoot
    val arguments = preview.optJSONObject("arguments") ?: JSONObject()
    return AiToolConfirmation(
        confirmationId = confirmationRoot.optString("confirmationId", confirmationRoot.optString("id")),
        toolId = preview.optString("toolId"),
        title = preview.optString("title", "需要确认"),
        summary = preview.optString("summary"),
        executor = preview.optString("executor", "hub"),
        arguments = buildMap {
            val keys = arguments.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, arguments.opt(key)?.toString().orEmpty())
            }
        },
        expiresAt = confirmationRoot.optString("expiresAt"),
    )
}

private fun parseConfigBundle(root: JSONObject): AiConfigBundle {
    val configsRoot = root.optJSONArray("configs")
    val configs = if (configsRoot != null) {
        buildList {
            for (index in 0 until configsRoot.length()) {
                configsRoot.optJSONObject(index)?.let { add(parseProviderConfig(it)) }
            }
        }
    } else {
        val configured = root.optBoolean(
            "configured",
            root.optString("apiKeyStatus") == "configured" || root.optString("apiKey") == "configured",
        )
        if (configured) listOf(parseProviderConfig(root).copy(id = "legacy")) else emptyList()
    }
    return AiConfigBundle(
        enabled = root.optBoolean("enabled", configs.any { it.enabled }),
        configs = configs,
    )
}

private fun parseProviderConfig(root: JSONObject, fallback: AiProviderConfig = AiProviderConfig()): AiProviderConfig {
    val model = root.optString("model", fallback.model)
    return AiProviderConfig(
        id = root.optString("id", root.optString("configId", fallback.id)),
        name = root.optString("name", root.optString("label", fallback.name)).ifBlank { model },
        provider = root.optString("provider", fallback.provider),
        enabled = root.optBoolean("enabled", fallback.enabled),
        model = model,
        baseUrl = root.optString("baseUrl", root.optString("base_url", fallback.baseUrl)),
        hasApiKey = root.optString("apiKeyStatus", root.optString("apiKey")) == "configured" || fallback.hasApiKey,
        tokenQuota = root.optNullableLong("tokenQuota", "token_quota", "modelQuotaTokens", "model_quota_tokens", "quota_tokens") ?: fallback.tokenQuota,
    )
}

private fun JSONObject.optNullableLong(vararg names: String): Long? {
    names.forEach { name ->
        if (has(name) && !isNull(name)) {
            val parsed = when (val value = opt(name)) {
                is Number -> value.toLong()
                else -> value?.toString()?.trim()?.toLongOrNull()
            }
            if (parsed != null && parsed > 0L) return parsed
        }
    }
    return null
}

private fun apiFailure(code: Int, body: String): String {
    val jsonDetail = runCatching { JSONObject(body).optString("error") }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.contains("<html", ignoreCase = true) && !it.contains("<!doctype", ignoreCase = true) }
    val plainBody = body.trim().takeIf {
        it.isNotBlank() && !it.startsWith("<") && !it.contains("<html", ignoreCase = true)
    }?.take(180)
    val detail = jsonDetail ?: plainBody ?: when (code) {
        502, 503, 504 -> "Hub 或上游 AI 服务暂时不可用，请稍后重试"
        401, 403 -> "Hub 鉴权失败，请检查连接设置"
        else -> "请求失败"
    }
    return "HTTP $code：$detail"
}

/** Preserve the Hub-side persisted message identities even when SSE returns HTTP 4xx/5xx. */
private fun streamProtocolFailure(code: Int, body: String): AiStreamProtocolException {
    val root = runCatching { JSONObject(body) }.getOrNull()
    val messageRoot = root?.optJSONObject("message")
    return AiStreamProtocolException(
        message = apiFailure(code, body),
        conversationId = root?.optString("conversationId")?.takeIf { it.isNotBlank() },
        userMessageId = root?.optInt("userMessageId") ?: 0,
        messageId = root?.optInt("messageId")?.takeIf { it > 0 }
            ?: messageRoot?.optInt("id")?.takeIf { it > 0 }
            ?: 0,
    )
}

private fun defaultAiHttpClient() = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
