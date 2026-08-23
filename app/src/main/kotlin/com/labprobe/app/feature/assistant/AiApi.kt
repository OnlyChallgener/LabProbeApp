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
            if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(180)}")
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
            if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(180)}")
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
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(180)}")
            val root = JSONObject(text)
            AiUsageSummary(
                requests = root.optInt("requests", 0),
                promptTokens = root.optInt("prompt_tokens", 0),
                completionTokens = root.optInt("completion_tokens", 0),
                totalTokens = root.optInt("total_tokens", 0),
                todayRequests = root.optInt("today_requests", 0),
                todayTotalTokens = root.optInt("today_total_tokens", 0),
            )
        }
    }

    suspend fun latestConversation(): Pair<String?, List<AiMessage>> = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/conversations?limit=1", "GET", null).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(180)}")
            val rows = JSONObject(text).optJSONArray("conversations")
            val id = rows?.optJSONObject(0)?.optString("id")?.takeIf { !it.isNullOrBlank() }
            if (id == null) return@withContext null to emptyList()
            request(hubUrl.trimEnd('/') + "/api/ai/conversations/${java.net.URLEncoder.encode(id, "UTF-8")}/messages", "GET", null).use { messagesResponse ->
                val messagesText = messagesResponse.body?.string().orEmpty()
                if (!messagesResponse.isSuccessful) error("HTTP ${messagesResponse.code}: ${messagesText.take(180)}")
                val raw = JSONObject(messagesText).optJSONArray("messages") ?: JSONArray()
                val messages = buildList {
                    for (index in 0 until raw.length()) {
                        val item = raw.optJSONObject(index) ?: continue
                        val role = item.optString("role")
                        val content = item.optString("content")
                        if (role in setOf("user", "assistant", "system") && content.isNotBlank()) {
                            add(AiMessage(role, content))
                        }
                    }
                }
                id to messages
            }
        }
    }

    suspend fun catalog(): List<AiToolHint> = withContext(Dispatchers.IO) {
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        request(hubUrl.trimEnd('/') + "/api/ai/catalog", "GET", null).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(180)}")
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
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(180)}")
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
            )
        }
    }

    suspend fun confirmHubTool(confirmationId: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("confirmationId", confirmationId).toString()
        request(hubUrl.trimEnd('/') + "/api/ai/tools/confirm", "POST", payload).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(180)}")
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

private fun defaultAiHttpClient() = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
