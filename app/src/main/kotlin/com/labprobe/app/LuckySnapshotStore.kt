package com.labprobe.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal const val LUCKY_CONNECTIONS_TTL_MS = 60_000L
internal const val LUCKY_RULES_TTL_MS = 30_000L
internal const val LUCKY_CERTIFICATES_TTL_MS = 60_000L
internal const val LUCKY_ACCOUNTS_TTL_MS = 300_000L

internal data class LuckyInstanceSnapshot(
    val rules: List<JSONObject> = emptyList(),
    val certificates: List<JSONObject> = emptyList(),
    val accounts: List<JSONObject> = emptyList(),
    val rulesAt: Long = 0L,
    val certificatesAt: Long = 0L,
    val accountsAt: Long = 0L,
)

/** Only display DTOs are persisted. Credential input and raw Lucky responses never enter this store. */
internal class LuckySnapshotStore(context: Context, hub: String, token: String) {
    private val prefs = context.applicationContext.getSharedPreferences("labprobe_lucky_snapshots", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest("$hub\u0000$token".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private var document = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
    val selectedId: String get() = document.optString("selectedId")
    val selectedTab: Int get() = document.optInt("tab").coerceIn(0, 1)
    val connectionsAt: Long get() = document.optLong("connectionsAt")
    fun connections(): List<JSONObject> = rows(document.optJSONArray("connections"))
    fun connectionsFresh(now: Long = System.currentTimeMillis()): Boolean =
        connectionsAt > 0L && now - connectionsAt < LUCKY_CONNECTIONS_TTL_MS
    fun instance(id: String): LuckyInstanceSnapshot? {
        val data = document.optJSONObject("instances")?.optJSONObject(id) ?: return null
        return LuckyInstanceSnapshot(
            rows(data.optJSONArray("rules")), rows(data.optJSONArray("certificates")), rows(data.optJSONArray("accounts")),
            data.optLong("rulesAt"), data.optLong("certificatesAt"), data.optLong("accountsAt"),
        )
    }
    fun select(id: String, tab: Int) { document.put("selectedId", id).put("tab", tab); persist() }
    fun saveConnections(items: List<JSONObject>) {
        document.put("connections", JSONArray(items.map { only(it, setOf("id", "name", "url")) }))
        document.put("connectionsAt", System.currentTimeMillis())
        val ids = items.map { it.optString("id") }.toSet()
        document.optJSONObject("instances")?.let { cached -> cached.keys().asSequence().toList().filter { it !in ids }.forEach(cached::remove) }
        persist()
    }
    fun saveInstance(id: String, snapshot: LuckyInstanceSnapshot) {
        val rules = snapshot.rules.map { row ->
            only(row, setOf("id", "name", "domain", "backend", "port", "tls", "network", "listenIp", "enabled", "editable", "subruleCount")).also { safe ->
                row.optJSONArray("routes")?.let { routes ->
                    safe.put("routes", JSONArray(rows(routes).map { only(it, setOf("id", "name", "domains", "backends", "type", "enabled", "editable")) }))
                }
            }
        }
        val certs = snapshot.certificates.map { only(it, setOf("id", "name", "domains", "expires", "enabled", "issuing", "failed", "automatic")) }
        val accounts = snapshot.accounts.map { only(it, setOf("id", "name", "provider")) }
        val instances = document.optJSONObject("instances") ?: JSONObject().also { document.put("instances", it) }
        instances.put(id, JSONObject()
            .put("rules", JSONArray(rules)).put("certificates", JSONArray(certs)).put("accounts", JSONArray(accounts))
            .put("rulesAt", snapshot.rulesAt).put("certificatesAt", snapshot.certificatesAt).put("accountsAt", snapshot.accountsAt))
        persist()
    }

    fun saveRules(id: String, rules: List<JSONObject>) {
        val current = instance(id) ?: LuckyInstanceSnapshot()
        saveInstance(id, current.copy(rules = rules, rulesAt = System.currentTimeMillis()))
    }

    fun saveCertificates(id: String, certificates: List<JSONObject>) {
        val current = instance(id) ?: LuckyInstanceSnapshot()
        saveInstance(id, current.copy(certificates = certificates, certificatesAt = System.currentTimeMillis()))
    }

    fun saveAccounts(id: String, accounts: List<JSONObject>) {
        val current = instance(id) ?: LuckyInstanceSnapshot()
        saveInstance(id, current.copy(accounts = accounts, accountsAt = System.currentTimeMillis()))
    }
    private fun persist() { prefs.edit().putString(key, document.toString()).apply() }
    private fun only(value: JSONObject, keys: Set<String>) = JSONObject().also { result ->
        keys.forEach { field -> if (value.has(field)) result.put(field, value.get(field)) }
    }
    private fun rows(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
