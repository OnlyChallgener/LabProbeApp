package com.labprobe.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class OfficialRouterAuthorization(
    val routerId: String,
    val name: String,
    val site: String,
    val model: String,
    val serialNumber: String,
    val online: Boolean,
    val authorized: Boolean,
    val matched: Boolean,
    val projectId: String,
    val projectName: String,
    val syncState: String,
    val error: String,
)

data class OfficialAccountSnapshot(
    val account: String,
    val authorized: Boolean,
    val state: String,
    val projectCount: Int,
    val matchedRouterCount: Int,
    val hubAuthorization: String,
    val expireTime: Long,
    val routers: List<OfficialRouterAuthorization>,
    val error: String = "",
) {
    val supported: Boolean get() = state != "unsupported"
}

internal fun parseOfficialAccountSnapshot(root: JSONObject): OfficialAccountSnapshot {
    val routers = buildList {
        val rows = root.optJSONArray("routers") ?: JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            add(
                OfficialRouterAuthorization(
                    routerId = row.optString("routerId").trim(),
                    name = row.optString("name").trim(),
                    site = row.optString("site").trim(),
                    model = row.optString("model").trim(),
                    serialNumber = row.optString("serialNumber").trim(),
                    online = row.optBoolean("online"),
                    authorized = row.optBoolean("authorized"),
                    matched = row.optBoolean("matched"),
                    projectId = row.optString("projectId").trim(),
                    projectName = row.optString("projectName").trim(),
                    syncState = row.optString("syncState").trim(),
                    error = row.optString("error").trim(),
                )
            )
        }
    }
    return OfficialAccountSnapshot(
        account = root.optString("account").trim(),
        authorized = root.optBoolean("authorized"),
        state = root.optString("state").trim().ifBlank { if (root.optBoolean("authorized")) "authorized" else "unauthorized" },
        projectCount = root.optInt("projectCount", 0).coerceAtLeast(0),
        matchedRouterCount = root.optInt("matchedRouterCount", 0).coerceAtLeast(0),
        hubAuthorization = root.optString("hubAuthorization").trim(),
        expireTime = root.optLong("expireTime", 0L).coerceAtLeast(0L),
        routers = routers,
        error = root.optString("error").trim(),
    )
}

internal typealias OfficialAccountRequest = (String, String, JSONObject?) -> JSONObject

internal fun requireOfficialAccountHttps(raw: String): String {
    val normalized = validateHubTransportAddress(raw)
    val scheme = runCatching { URI(normalized).scheme?.lowercase() }.getOrNull()
    if (normalized.isNotBlank() && scheme != "https") {
        throw IllegalArgumentException("官方账号凭据仅允许通过 HTTPS Hub 地址传输")
    }
    return normalized
}

internal class OfficialAccountRepository(
    private val prefs: AppPrefs,
    private val request: OfficialAccountRequest = { path, method, body ->
        HubApi(
            prefs,
            hubOverride = requireOfficialAccountHttps(prefs.hubRoot),
            tokenOverride = prefs.token,
        ).requestJson(path, method, body)
    },
) {
    private fun parse(root: JSONObject, requireOk: Boolean = true): OfficialAccountSnapshot {
        if (requireOk && !root.optBoolean("ok", true)) error(root.optString("error").ifBlank { "官方账号请求失败" })
        return parseOfficialAccountSnapshot(root)
    }

    suspend fun status(verify: Boolean = true): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        try {
            val root = request("/api/official-account?verify=${if (verify) "1" else "0"}", "GET", null)
            parse(root)
        } catch (error: HubHttpException) {
            if (error.statusCode == 404 || error.statusCode == 405) {
                OfficialAccountSnapshot("", false, "unsupported", 0, 0, "unsupported", 0, emptyList(),
                    "当前 Hub 暂不支持锐捷官方账号")
            } else throw error
        }
    }

    suspend fun login(account: String, password: String): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        val body = JSONObject().put("account", account.trim()).put("password", password)
        parse(request("/api/official-account", "POST", body))
    }

    suspend fun refresh(): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        parse(request("/api/official-account", "POST", JSONObject().put("action", "refresh")))
    }

    suspend fun logout(): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        parse(request("/api/official-account", "DELETE", null), requireOk = false)
    }
}
