package com.labprobe.app

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class OfficialCloudSession(
    val account: String,
    val token: String,
    val expireTime: Long,
)

internal data class OfficialCloudBinding(
    val serial: String,
    val projectId: String,
    val projectName: String,
    val createTime: String,
    val meshSerials: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("serial", serial)
        put("projectId", projectId)
        put("projectName", projectName)
        put("createTime", createTime)
        put("meshSerials", JSONArray(meshSerials))
    }
}

internal data class OfficialCloudMember(
    val uid: String,
    val macs: Set<String>,
)

internal data class OfficialNativeConfirmation(
    val operationId: String,
    val officialUid: String,
    val bindingVersion: Int,
)

internal class OfficialCloudException(val code: String, message: String) : Exception(message)

internal interface OfficialCloudGateway {
    fun login(account: String, password: String): OfficialCloudSession
    fun refresh(session: OfficialCloudSession): OfficialCloudSession
    fun logout(session: OfficialCloudSession)
    fun projects(session: OfficialCloudSession): List<JSONObject>
    fun discoverBinding(session: OfficialCloudSession, serial: String): OfficialCloudBinding
    fun members(session: OfficialCloudSession, binding: OfficialCloudBinding): List<OfficialCloudMember>
    fun add(session: OfficialCloudSession, binding: OfficialCloudBinding, mac: String, name: String): OfficialCloudMember
    fun remove(session: OfficialCloudSession, binding: OfficialCloudBinding, member: OfficialCloudMember)
}

internal class OfficialCloudClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) : OfficialCloudGateway {
    companion object {
        private const val BASE = "https://shinyaapp.ruijie.com.cn"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    @Synchronized
    override fun login(account: String, password: String): OfficialCloudSession {
        val data = request("POST", "/api/v1/base/homeUser/accountPasswordLogin", body = JSONObject()
            .put("account", account.trim()).put("password", password))
        val token = data.optString("token").trim()
        if (token.isBlank()) throw OfficialCloudException("official_auth_required", "官方账号登录失败")
        return OfficialCloudSession(account.trim(), token, data.optLong("expireTime", 0L).coerceAtLeast(0L))
    }

    @Synchronized
    override fun refresh(session: OfficialCloudSession): OfficialCloudSession {
        val data = request("POST", "/api/v1/base/homeUser/refreshToken", token = session.token, body = JSONObject())
        val token = data.optString("token").ifBlank { session.token }
        if (token.isBlank()) throw OfficialCloudException("official_auth_required", "官方账号刷新失败")
        val expiry = if (data.has("expireTime") && !data.isNull("expireTime")) {
            data.optLong("expireTime", 0L).coerceAtLeast(0L)
        } else session.expireTime
        return session.copy(token = token, expireTime = expiry)
    }

    @Synchronized
    override fun logout(session: OfficialCloudSession) {
        request("POST", "/api/v1/base/homeUser/logout", token = session.token, body = JSONObject())
    }

    override fun projects(session: OfficialCloudSession): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var page = 1
        while (page <= 100) {
            val data = request("POST", "/homewlan/apMonitor/getProjectList2", token = session.token,
                body = JSONObject().put("startPage", page.toString()).put("pageSize", "500"))
            val rows = data.optJSONArray("dataList") ?: throw OfficialCloudException("official_response_invalid", "无法核对官方账号下的网络")
            var added = 0
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: throw OfficialCloudException("official_response_invalid", "官方网络列表格式异常")
                val id = row.optString("buildingId").trim()
                val identity = id.ifBlank { row.toString() }
                if (seen.add(identity)) {
                    result += row
                    added++
                }
            }
            val total = when {
                data.has("totalCount") -> data.optInt("totalCount", result.size)
                data.has("total") -> data.optInt("total", result.size)
                else -> result.size
            }
            if (rows.length() < 500 || result.size >= total) return result
            if (added == 0) throw OfficialCloudException("official_response_invalid", "官方网络列表分页异常")
            page++
        }
        throw OfficialCloudException("official_response_invalid", "官方网络列表超出读取范围")
    }

    fun routers(session: OfficialCloudSession, projectId: String): JSONObject {
        val day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
        val data = request("POST", "/homewlan/homeAp/getDeviceInfoList2", token = session.token,
            binding = OfficialCloudBinding("", projectId, "", "", emptyList()),
            body = JSONObject().put("day", day))
        if (data.optJSONArray("apList") == null) {
            throw OfficialCloudException("official_response_invalid", "无法核对官方路由器归属")
        }
        return data
    }

    override fun discoverBinding(session: OfficialCloudSession, serial: String): OfficialCloudBinding {
        val target = serial.trim().uppercase()
        if (target.isBlank()) throw OfficialCloudException("official_router_mismatch", "尚未确认当前路由器身份")
        val matches = mutableListOf<OfficialCloudBinding>()
        for (project in projects(session)) {
            val projectId = project.optString("buildingId").trim()
            if (projectId.isBlank()) continue
            val data = routers(session, projectId)
            if (data.optString("mainAp").trim().uppercase() != target) continue
            val ap = data.optJSONArray("apList")?.let { rows ->
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optString("sn").trim().uppercase() == target }
            } ?: continue
            val createTime = ap.optString("createTime").trim().replace('T', ' ').take(19)
            if (!Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(createTime)) {
                throw OfficialCloudException("official_response_invalid", "官方路由器创建时间异常")
            }
            val mesh = data.optJSONArray("apList")?.let { rows ->
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .map { it.optString("sn").trim().uppercase() }.filter { it.isNotBlank() }.distinct()
            }.orEmpty()
            matches += OfficialCloudBinding(target, projectId, project.optString("name").trim(), createTime, mesh)
        }
        if (matches.size != 1 || matches.map { it.projectId }.distinct().size != 1) {
            throw OfficialCloudException("official_router_mismatch", "官方账号未唯一匹配当前路由器")
        }
        return matches.single().also { members(session, it) }
    }

    override fun members(session: OfficialCloudSession, binding: OfficialCloudBinding): List<OfficialCloudMember> {
        val result = mutableListOf<OfficialCloudMember>()
        val seen = mutableSetOf<String>()
        var page = 1
        while (page <= 10) {
            val url = HttpUrl.Builder().apply {
                scheme("https")
                host("shinyaapp.ruijie.com.cn")
                addPathSegments("api/v1/netguard/staGuard/getProtectStaListPage")
                addQueryParameter("mainApSn", binding.serial)
                addQueryParameter("mainApCreateTime", binding.createTime)
                addQueryParameter("meshApSnList", binding.meshSerials.joinToString(","))
                addQueryParameter("pageIndex", page.toString())
                addQueryParameter("pageSize", "200")
            }.build()
            val data = request("GET", url, token = session.token, binding = binding)
            val rows = data.optJSONArray("rows") ?: throw OfficialCloudException("official_response_invalid", "官方守护名单读取失败")
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: throw OfficialCloudException("official_response_invalid", "官方守护名单格式异常")
                val uid = row.optString("staId").ifBlank { row.optString("uid") }.trim().uppercase()
                if (!Regex("[0-9A-F]{32}").matches(uid)) {
                    throw OfficialCloudException("official_response_invalid", "守护编号格式异常")
                }
                val values = row.optJSONArray("mergeMacs") ?: row.optJSONArray("macs") ?: JSONArray()
                val macs = (0 until values.length()).map { compactMac(values.optString(it)) }.toSet()
                if (macs.any { it.length != 12 }) throw OfficialCloudException("official_response_invalid", "守护名单格式异常")
                if (seen.add(uid)) result += OfficialCloudMember(uid, macs)
            }
            if (rows.length() < 200) return result
            page++
        }
        throw OfficialCloudException("official_response_invalid", "官方守护名单超出读取范围")
    }

    override fun add(session: OfficialCloudSession, binding: OfficialCloudBinding, mac: String, name: String): OfficialCloudMember {
        val compact = compactMac(mac)
        members(session, binding).firstOrNull { compact in it.macs }?.let { return it }
        request("POST", "/homewlan/childProtection/addSta", token = session.token, binding = binding,
            body = JSONObject().put("staList", JSONArray().put(JSONObject()
                .put("name", name.take(120)).put("staMac", compact).put("type", "unknown").put("manufacturer", ""))))
        return members(session, binding).firstOrNull { compact in it.macs }
            ?: throw OfficialCloudException("official_confirmation_pending", "官方名单尚未确认此次更改")
    }

    override fun remove(session: OfficialCloudSession, binding: OfficialCloudBinding, member: OfficialCloudMember) {
        request("POST", "/api/v1/netguard/staGuard/deleteSta", token = session.token, binding = binding,
            body = JSONObject().put("mainApSn", binding.serial).put("staId", member.uid))
        if (members(session, binding).any { it.uid == member.uid }) {
            throw OfficialCloudException("official_confirmation_pending", "官方名单尚未确认此次更改")
        }
    }

    private fun request(
        method: String,
        path: String,
        token: String = "",
        binding: OfficialCloudBinding? = null,
        body: JSONObject? = null,
    ): JSONObject = request(method, HttpUrl.Builder().apply {
        scheme("https")
        host("shinyaapp.ruijie.com.cn")
        if (!path.startsWith("/")) addPathSegment(path) else addPathSegments(path.removePrefix("/"))
    }.build(), token, binding, body)

    private fun request(
        method: String,
        url: HttpUrl,
        token: String,
        binding: OfficialCloudBinding?,
        body: JSONObject? = null,
    ): JSONObject {
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("type", "shinyaApp")
            .header("Cache-Control", "no-cache")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        binding?.projectId?.takeIf { it.isNotBlank() }?.let { builder.header("projectId", it) }
        val payload = body?.let { JSONObject(it.toString()) } ?: JSONObject()
        binding?.projectId?.takeIf { it.isNotBlank() }?.let { payload.put("projectId", it) }
        builder.method(method, if (method == "GET") null else payload.toString().toRequestBody(JSON))
        return try {
            http.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val code = if (response.code == 401 || response.code == 403) "official_auth_required" else "official_request_rejected"
                    throw OfficialCloudException(code, if (code == "official_auth_required") "官方账号授权已失效，请重新授权" else "官方服务未接受此次操作")
                }
                val root = JSONObject(raw)
                val code = root.optString("code")
                if (code != "0") {
                    val errorCode = when (code) {
                        "401", "403" -> "official_auth_required"
                        "500" -> "official_unavailable"
                        else -> "official_request_rejected"
                    }
                    throw OfficialCloudException(errorCode, when (errorCode) {
                        "official_auth_required" -> "官方账号授权已失效，请重新授权"
                        "official_unavailable" -> "官方服务暂时不可用"
                        else -> "官方服务未接受此次操作"
                    })
                }
                root.optJSONObject("data") ?: JSONObject()
            }
        } catch (error: OfficialCloudException) {
            throw error
        } catch (_: Exception) {
            throw OfficialCloudException("official_connection_pending", "官方服务连接失败，请稍后重试")
        }
    }
}

internal fun compactMac(value: String): String = value.replace(":", "").replace("-", "").trim().uppercase()

internal interface OfficialAccountStorage {
    fun get(): OfficialCloudSession?
    fun set(session: OfficialCloudSession)
    fun clear()
}

internal class OfficialAccountStore(context: android.content.Context) : OfficialAccountStorage {
    private val delegate = SecureOfficialAccountStore(context)
    override fun get(): OfficialCloudSession? = runCatching {
        val raw = delegate.get()
        if (raw.isBlank()) null else JSONObject(raw).let {
            OfficialCloudSession(it.optString("account"), it.optString("token"), it.optLong("expireTime"))
        }.takeIf { it.token.isNotBlank() }
    }.getOrNull()
    override fun set(session: OfficialCloudSession) = delegate.set(JSONObject().apply {
        put("account", session.account)
        put("token", session.token)
        put("expireTime", session.expireTime)
    }.toString())
    override fun clear() = delegate.set("")
}

internal class OfficialMembershipSync(
    private val prefs: AppPrefs,
    private val cloud: OfficialCloudGateway = OfficialCloudClient(),
    private val store: OfficialAccountStorage = OfficialAccountStore(prefs.context),
    private val accountRepository: () -> OfficialAccountRepository = { OfficialAccountRepository(prefs, cloud = cloud, store = store) },
) {
    fun add(mac: String, name: String): OfficialNativeConfirmation? {
        val session = store.get() ?: return null
        val existing = runBlocking { accountRepository().status(verify = false) }
        val existingRow = existing.routers.firstOrNull { it.routerId == prefs.workspaceId }
            ?: existing.routers.firstOrNull { it.matched }
            ?: return null
        if (!existingRow.matched) return null
        val snapshot = runBlocking { accountRepository().status(verify = true) }
        val row = snapshot.routers.firstOrNull { it.routerId == prefs.workspaceId }
            ?: snapshot.routers.firstOrNull { it.matched }
            ?: return null
        val binding = row.cloudBinding() ?: return null
        val member = cloud.add(session, binding, mac, name)
        return OfficialNativeConfirmation(java.util.UUID.randomUUID().toString(), member.uid, row.bindingVersion)
    }

    fun remove(macs: Collection<String>, uid: String): OfficialNativeConfirmation? {
        val session = store.get() ?: return null
        val existing = runBlocking { accountRepository().status(verify = false) }
        val existingRow = existing.routers.firstOrNull { it.routerId == prefs.workspaceId }
            ?: existing.routers.firstOrNull { it.matched }
            ?: return null
        if (!existingRow.matched) return null
        val snapshot = runBlocking { accountRepository().status(verify = true) }
        val row = snapshot.routers.firstOrNull { it.routerId == prefs.workspaceId }
            ?: snapshot.routers.firstOrNull { it.matched }
            ?: return null
        val binding = row.cloudBinding() ?: return null
        val compact = macs.map(::compactMac).toSet()
        val member = cloud.members(session, binding).firstOrNull { member ->
            member.uid.equals(uid, true) || member.macs.any { it in compact }
        } ?: return null
        cloud.remove(session, binding, member)
        return OfficialNativeConfirmation(java.util.UUID.randomUUID().toString(), member.uid, row.bindingVersion)
    }
}
