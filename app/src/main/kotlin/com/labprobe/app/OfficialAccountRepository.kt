package com.labprobe.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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
    val createTime: String = "",
    val meshSerials: List<String> = emptyList(),
    val bindingVersion: Int = 0,
    val accountFingerprint: String = "",
) {
    internal fun cloudBinding(): OfficialCloudBinding? {
        if (serialNumber.isBlank() || projectId.isBlank() || bindingVersion <= 0) return null
        return OfficialCloudBinding(serialNumber, projectId, projectName, createTime, meshSerials)
    }
}

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
            val mesh = row.optJSONArray("meshSerials") ?: JSONArray()
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
                    createTime = row.optString("createTime").trim(),
                    meshSerials = (0 until mesh.length()).map { mesh.optString(it).trim() }.filter { it.isNotBlank() },
                    bindingVersion = row.optInt("bindingVersion", 0),
                    accountFingerprint = row.optString("accountFingerprint").trim(),
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

internal fun officialAccountHubAddress(raw: String): String =
    validateHubTransportAddress(raw)

internal class OfficialAccountRepository(
    private val prefs: AppPrefs,
    private val request: OfficialAccountRequest = { path, method, body ->
        HubApi(
            prefs,
            hubOverride = officialAccountHubAddress(prefs.hubRoot),
            tokenOverride = prefs.token,
        ).requestJson(path, method, body)
    },
    private val cloud: OfficialCloudGateway = OfficialCloudClient(),
    private val store: OfficialAccountStorage = OfficialAccountStore(prefs.context),
) {
    private fun parse(root: JSONObject, requireOk: Boolean = true): OfficialAccountSnapshot {
        if (requireOk && !root.optBoolean("ok", true)) error(root.optString("error").ifBlank { "官方账号请求失败" })
        return parseOfficialAccountSnapshot(root)
    }

    suspend fun status(verify: Boolean = true): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        val session = store.get()
        if (session == null) {
            val root = request("/api/official-account?verify=0", "GET", null)
            parse(root).copy(authorized = false, state = if (parse(root).state == "unsupported") "unsupported" else "unauthorized")
        } else {
            val current = if (verify) refreshIfNeeded(session) else session
            if (verify) syncBindings(current) else mergeCloudStatus(current, request("/api/official-account?verify=0", "GET", null))
        }
    }

    suspend fun login(account: String, password: String): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        val session = cloud.login(account, password)
        store.set(session)
        syncBindings(session)
    }

    suspend fun refresh(): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        val session = store.get() ?: return@withContext status(verify = false)
        val refreshed = cloud.refresh(session)
        store.set(refreshed)
        syncBindings(refreshed)
    }

    suspend fun logout(): OfficialAccountSnapshot = withContext(Dispatchers.IO) {
        val session = store.get()
        var cloudError = ""
        if (session != null) {
            try {
                cloud.logout(session)
            } catch (error: Exception) {
                cloudError = error.message.orEmpty()
            }
        }
        store.clear()
        val root = try {
            request("/api/official-account", "DELETE", null)
        } catch (error: HubHttpException) {
            if (error.statusCode == 404 || error.statusCode == 405) {
                JSONObject().put("ok", true).put("authorized", false).put("state", "unauthorized")
            } else throw error
        }
        parse(root, requireOk = false).let {
            if (cloudError.isBlank()) it else it.copy(state = "failed", error = cloudError, authorized = false)
        }
    }

    private fun refreshIfNeeded(session: OfficialCloudSession): OfficialCloudSession {
        if (session.expireTime <= 0L || session.expireTime > System.currentTimeMillis() + 60_000L) return session
        return cloud.refresh(session).also(store::set)
    }

    private fun syncBindings(session: OfficialCloudSession): OfficialAccountSnapshot {
        val status = request("/api/official-account?verify=0", "GET", null)
        val snapshot = parse(status)
        if (snapshot.state == "unsupported") return snapshot
        val bindings = JSONArray()
        val errors = mutableListOf<String>()
        for (row in snapshot.routers) {
            if (row.serialNumber.isBlank()) continue
            try {
                bindings.put(cloud.discoverBinding(session, row.serialNumber).toJson().apply {
                    put("account", session.account)
                    put("accountFingerprint", fingerprint(session.account))
                    put("expireTime", session.expireTime)
                    put("verifiedAt", System.currentTimeMillis() / 1000.0)
                })
            } catch (error: Exception) {
                errors += error.message.orEmpty()
            }
        }
        if (bindings.length() == 0) {
            throw IllegalStateException(errors.firstOrNull() ?: "尚未确认路由器序列号，无法绑定官方项目")
        }
        val body = JSONObject().apply {
            put("action", "bind")
            put("account", session.account)
            put("accountFingerprint", fingerprint(session.account))
            put("expireTime", session.expireTime)
            put("verifiedAt", System.currentTimeMillis() / 1000.0)
            put("bindings", bindings)
            if (bindings.length() == 1) put("binding", bindings.getJSONObject(0))
        }
        val bound = try {
            parse(request("/api/official-account", "POST", body))
        } catch (error: HubHttpException) {
            val legacy = error.statusCode in setOf(404, 405, 410) ||
                (error.statusCode == 400 && error.message.orEmpty().contains("账号和密码"))
            if (legacy) {
                return snapshot.copy(state = "unsupported", authorized = false,
                    error = "当前 Hub 暂不支持官方绑定同步；本地路由器功能不受影响")
            }
            throw error
        }
        return if (errors.isEmpty()) bound else bound.copy(
            state = if (bound.authorized) "partial" else bound.state,
            error = errors.first(),
        )
    }

    private fun mergeCloudStatus(session: OfficialCloudSession, root: JSONObject): OfficialAccountSnapshot =
        parse(root).copy(
            account = session.account,
            expireTime = session.expireTime,
            authorized = parse(root).authorized && session.expireTime > System.currentTimeMillis(),
        )

    private fun fingerprint(account: String): String =
        MessageDigest.getInstance("SHA-256").digest(account.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

internal fun officialAccountEntryStatus(snapshot: OfficialAccountSnapshot?, failed: Boolean): String = when {
    failed -> "读取失败"
    snapshot == null -> "读取中"
    !snapshot.supported -> "不支持"
    snapshot.state == "conflict" -> "账号冲突"
    snapshot.state == "partial" -> "部分授权"
    snapshot.state == "failed" -> "退出未完成"
    snapshot.state == "pending_unknown" -> "退出待确认"
    snapshot.state == "checking" -> "确认中"
    snapshot.authorized -> "已授权"
    else -> "未授权"
}
