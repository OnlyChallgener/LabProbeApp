package com.labprobe.app.feature.assistant

import android.content.Context
import com.labprobe.app.AppPrefs
import com.labprobe.app.favoriteShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

    fun lastNotificationId(): Int = prefs.getInt("last_notification_id", 0).coerceAtLeast(0)

    fun saveLastNotificationId(value: Int) = prefs.edit().putInt("last_notification_id", value.coerceAtLeast(0)).apply()
}

class AiApiClient(
    private val settings: AiSettingsStore,
    private val hubUrl: String,
    private val hubToken: String,
    private val http: OkHttpClient = defaultAiHttpClient(),
    private val appPrefs: AppPrefs? = null,
) {
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

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/test", "POST", "{}").use { response ->
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
                            cacheHitTokens = item.optInt("cache_hit_tokens"),
                            cacheMissTokens = item.optInt("cache_miss_tokens"),
                            models = models,
                        ))
                    }
                }
                val storage = root.optJSONObject("storage") ?: JSONObject()
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
                        add(AiMessage(role, content))
                    }
                }
            }
        }
    }

    suspend fun listConversations(limit: Int = 20): List<AiConversation> = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/conversations?limit=$limit", "GET", null).use { response ->
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

    suspend fun chat(messages: List<AiMessage>, conversationId: String? = null): AiReply = withContext(Dispatchers.IO) {
        val current = settings.read()
        require(current.enabled) { "请先启用 Hub AI" }
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val body = JSONObject().put("messages", JSONArray().apply {
            messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }).apply {
            if (!conversationId.isNullOrBlank()) put("conversationId", conversationId)
            localContext()?.let { put("clientContext", it) }
        }.toString()
        request(hubUrl.trimEnd('/') + "/api/ai/chat", "POST", body).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiFailure(response.code, text))
            val root = JSONObject(text)
            val content = root.optJSONObject("message")?.optString("content").orEmpty()
            require(content.isNotBlank()) { "AI 返回为空" }
            val usage = root.optJSONObject("usage")
            val confirmation = root.optJSONObject("confirmation")?.let { confirmationRoot ->
                val preview = confirmationRoot.optJSONObject("preview") ?: JSONObject()
                val arguments = preview.optJSONObject("arguments") ?: JSONObject()
                AiToolConfirmation(
                    confirmationId = confirmationRoot.optString("confirmationId"),
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
            AiReply(
                content,
                AiTokenSummary(
                    prompt = usage?.optInt("prompt_tokens", 0) ?: 0,
                    completion = usage?.optInt("completion_tokens", 0) ?: 0,
                ),
                conversationId = root.optString("conversationId").takeIf { it.isNotBlank() },
                confirmation = confirmation,
                usageKnown = root.optBoolean("usageKnown", true),
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

    private fun localContext(): JSONObject? = appPrefs?.let { prefs ->
        JSONObject()
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
                        .put("localUrl", item.localEndpoint.ifBlank { item.lanUrl })
                        .put("remoteUrl", item.remoteEndpoint.ifBlank { item.wanUrl })
                        .put("serviceType", item.serviceType))
                }
            })
    }

    private fun request(url: String, method: String, json: String?): okhttp3.Response {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (hubToken.isNotBlank()) builder.header("Authorization", "Bearer ${hubToken.trim()}")
        when (method) {
            "POST" -> builder.post((json ?: "{}").toRequestBody("application/json".toMediaType()))
            "PUT" -> builder.put((json ?: "{}").toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        return http.newCall(builder.build()).execute()
    }
}

private fun apiFailure(code: Int, body: String): String {
    val detail = runCatching { JSONObject(body).optString("error") }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: body.take(180).takeIf { it.isNotBlank() }
        ?: "请求失败"
    return "HTTP $code：$detail"
}

private fun defaultAiHttpClient() = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
