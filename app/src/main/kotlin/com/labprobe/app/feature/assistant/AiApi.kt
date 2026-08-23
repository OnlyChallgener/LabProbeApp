package com.labprobe.app.feature.assistant

import android.content.Context
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

    suspend fun chat(messages: List<AiMessage>): AiReply = withContext(Dispatchers.IO) {
        val current = settings.read()
        require(current.enabled) { "请先启用 Hub AI" }
        require(hubUrl.isNotBlank()) { "请先填写 Hub 地址" }
        val body = JSONObject().put("messages", JSONArray().apply {
            messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }).toString()
        request(hubUrl.trimEnd('/') + "/api/ai/chat", "POST", body).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(180)}")
            val root = JSONObject(text)
            val content = root.optJSONObject("message")?.optString("content").orEmpty()
            require(content.isNotBlank()) { "AI 返回为空" }
            val usage = root.optJSONObject("usage")
            AiReply(
                content,
                AiTokenSummary(
                    prompt = usage?.optInt("prompt_tokens", 0) ?: 0,
                    completion = usage?.optInt("completion_tokens", 0) ?: 0,
                ),
            )
        }
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
