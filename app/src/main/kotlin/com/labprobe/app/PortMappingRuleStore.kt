package com.labprobe.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable APP-side copy of user-authored IPv6 mapping rules.
 *
 * Hub remains authoritative, but Agent presence is never a reason to erase desired
 * configuration.  This store survives process death and protects the page from a
 * transient empty/error response until Hub supplies an explicit newer revision.
 */
data class PersistedPortMapSnapshot(
    val rules: List<PortMapRule> = emptyList(),
    val revision: Long = 0L,
    val updatedAt: String = "",
    val hasDocument: Boolean = false,
)

object PortMappingRuleStore {
    private const val STORE_NAME = "labprobe_port_mapping_rules_v1"

    private fun scopeKey(prefs: AppPrefs): String {
        val scope = listOf(prefs.hub.trim(), prefs.hubDns.trim()).joinToString("|")
        return "scope_${scope.hashCode().toUInt().toString(16)}"
    }

    fun load(context: Context, prefs: AppPrefs): PersistedPortMapSnapshot {
        val raw = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .getString(scopeKey(prefs), null)
            ?: return PersistedPortMapSnapshot()
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("rules") ?: JSONArray()
            val rules = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::decodeRule)
            }
            PersistedPortMapSnapshot(
                rules = rules,
                revision = root.optLong("revision", 0L).coerceAtLeast(0L),
                updatedAt = cleanApiText(root.optString("updatedAt")),
                hasDocument = true,
            )
        }.getOrDefault(PersistedPortMapSnapshot())
    }

    fun save(
        context: Context,
        prefs: AppPrefs,
        rules: List<PortMapRule>,
        revision: Long,
        updatedAt: String,
    ) {
        val root = JSONObject()
            .put("version", 1)
            .put("revision", revision.coerceAtLeast(0L))
            .put("updatedAt", updatedAt)
            .put("savedAt", System.currentTimeMillis())
            .put("rules", JSONArray().apply { rules.forEach { put(encodeRule(it)) } })
        context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(scopeKey(prefs), root.toString())
            .apply()
    }

    private fun encodeRule(rule: PortMapRule): JSONObject = JSONObject()
        .put("id", rule.id)
        .put("name", rule.name)
        .put("enabled", rule.enabled)
        .put("mode", rule.mode)
        .put("listenPort", rule.listenPort)
        .put("targetMode", rule.targetMode)
        .put("targetIpv4", rule.targetIpv4)
        .put("targetIpv6", rule.targetIpv6)
        .put("targetIpv6Suffix", rule.targetIpv6Suffix)
        .put("targetMac", rule.targetMac)
        .put("targetPort", rule.targetPort)
        .put("preferCurrentPrefix", rule.preferCurrentPrefix)
        .put("leaseSeconds", rule.leaseSeconds)
        .put("maxConnections", rule.maxConnections)
        .put("idleTimeoutSec", rule.idleTimeoutSec)
        .apply {
            if (rule.expiresAt == null) put("expiresAt", JSONObject.NULL)
            else put("expiresAt", rule.expiresAt)
        }

    private fun decodeRule(root: JSONObject): PortMapRule? {
        val id = cleanApiText(root.optString("id"))
        val name = cleanApiText(root.optString("name"))
        if (id.isBlank() || name.isBlank()) return null
        val enabled = root.optBoolean("enabled", false)
        val runtimeState = if (enabled) "waiting_agent" else "stopped"
        val expiresAt = if (!root.has("expiresAt") || root.isNull("expiresAt")) null
        else root.optLong("expiresAt").takeIf { it > 0L }
        return PortMapRule(
            id = id,
            name = name,
            enabled = enabled,
            mode = cleanApiText(root.optString("mode", "6to4")),
            listenPort = root.optInt("listenPort"),
            targetMode = cleanApiText(root.optString("targetMode")),
            targetIpv4 = cleanApiText(root.optString("targetIpv4")),
            targetIpv6 = cleanApiText(root.optString("targetIpv6")),
            targetIpv6Suffix = cleanApiText(root.optString("targetIpv6Suffix")),
            targetMac = cleanMac(root.optString("targetMac")),
            targetPort = root.optInt("targetPort"),
            preferCurrentPrefix = root.optBoolean("preferCurrentPrefix", true),
            expiresAt = expiresAt,
            leaseSeconds = root.optLong("leaseSeconds", 0L).coerceAtLeast(0L),
            maxConnections = root.optInt("maxConnections", 32),
            idleTimeoutSec = root.optInt("idleTimeoutSec", 300),
            desiredState = if (enabled) "running" else "stopped",
            actualState = runtimeState,
            syncState = "cached",
            revision = 0L,
            runtime = PortMapRuntime(state = runtimeState, expiresAt = expiresAt),
        )
    }
}
