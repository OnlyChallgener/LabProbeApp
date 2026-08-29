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
                        add(AiMessage(role, content))
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
            "PATCH" -> builder.patch((json ?: "{}").toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        return http.newCall(builder.build()).execute()
    }
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

private fun defaultAiHttpClient() = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
