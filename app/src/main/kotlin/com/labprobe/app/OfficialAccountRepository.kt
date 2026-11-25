package com.labprobe.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

internal class OfficialAccountRepository(private val prefs: AppPrefs) {
    private fun api() = HubApi(prefs, hubOverride = prefs.hubRoot, tokenOverride = prefs.token)

    private fun parse(root: JSONObject, requireOk: Boolean = true): OfficialAccountSnapshot {
        if (requireOk && !root.optBoolean("ok", true)) error(root.optString("error").ifBlank { "官方账号请求失败" })
        return parseOfficialAccountSnapshot(root)
    }

    suspend fun status(verify: Boolean = true): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        try {
            val root = api().requestJson("/api/official-account?verify=${if (verify) "1" else "0"}")
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
        parse(api().requestJson("/api/official-account", "POST", body))
    }

    suspend fun logout(): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        parse(api().requestJson("/api/official-account", "DELETE"), requireOk = false)
    }
}
