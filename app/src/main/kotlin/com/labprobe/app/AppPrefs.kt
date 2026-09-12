package com.labprobe.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val DEFAULT_HUB = ""
internal const val DEFAULT_DNS1 = "223.5.5.5"
internal const val DEFAULT_DNS2 = "8.8.8.8"
internal const val DEFAULT_TOKEN = ""

class AppPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("labprobe", Context.MODE_PRIVATE)
    private val secureTokenStore = SecureTokenStore(context)
    private val secureSshPasswordStore = SecureSshPasswordStore(context)
    init {
        clearDeprecatedHookToken(context)
        val legacy = sp.getString("token", "").orEmpty().trim()
        if (secureTokenStore.get().isBlank() && legacy.isNotBlank()) secureTokenStore.set(legacy)
        if (sp.contains("token")) sp.edit().remove("token").apply()
        val legacySshPassword = sp.getString("ssh_password", "").orEmpty()
        if (secureSshPasswordStore.get().isBlank() && legacySshPassword.isNotBlank()) {
            secureSshPasswordStore.set(legacySshPassword)
        }
        if (sp.contains("ssh_password")) sp.edit().remove("ssh_password").apply()
    }
    var hub: String get() = normalizeHubAddressForDisplay(sp.getString("hub", DEFAULT_HUB) ?: DEFAULT_HUB)
        set(v) = sp.edit().putString("hub", normalizeHubAddressForDisplay(v)).apply()
    var token: String get() = secureTokenStore.get().ifBlank { DEFAULT_TOKEN }
        set(v) = secureTokenStore.set(v)
    var hubDns: String get() = sp.getString("hub_dns", DEFAULT_DNS1) ?: DEFAULT_DNS1
        set(v) = sp.edit().putString("hub_dns", v.trim()).apply()
    var autoRefresh: String get() = "实时"
        set(v) = sp.edit().putString("auto_refresh", v).apply()
    var ignoredUpdateCode: Int get() = sp.getInt("ignored_update_code", 0)
        set(v) = sp.edit().putInt("ignored_update_code", v).apply()
    var lastUpdateCheckAt: Long get() = sp.getLong("last_update_check_at", 0L)
        set(v) = sp.edit().putLong("last_update_check_at", v).apply()
    var agentUpdateInfoJson: String get() = sp.getString("agent_update_info_v1", "") ?: ""
        set(v) = sp.edit().putString("agent_update_info_v1", v).apply()
    var agentUpdateMessage: String get() = sp.getString("agent_update_message_v1", "") ?: ""
        set(v) = sp.edit().putString("agent_update_message_v1", v).apply()

    var homeOrder: String get() = sp.getString("home_order", "score,mini,router,exit,vpn,devices,today") ?: "score,mini,router,exit,vpn,devices,today"
        set(v) = sp.edit().putString("home_order", v).apply()
    var toolSectionOrder: String get() = sp.getString("tool_section_order", "net,public,device") ?: "net,public,device"
        set(v) = sp.edit().putString("tool_section_order", v).apply()
    var privacyMode: Boolean get() = sp.getBoolean("privacy_mode", false)
        set(v) = sp.edit().putBoolean("privacy_mode", v).apply()
    var favoriteShortcutsJson: String get() = sp.getString("favorite_shortcuts_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("favorite_shortcuts_v1", v).apply()
    var favoriteNetworkMode: String get() = sp.getString("favorite_network_mode", "lan") ?: "lan"
        set(v) = sp.edit().putString("favorite_network_mode", v).apply()
    /** WireGuard profile metadata only. Private keys are stored separately in Android Keystore. */
    var wireGuardProfilesJson: String get() = sp.getString("wireguard_profiles_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("wireguard_profiles_v1", v).apply()
    var wireGuardActiveProfileId: String get() = sp.getString("wireguard_active_profile_v1", "") ?: ""
        set(v) = sp.edit().putString("wireguard_active_profile_v1", v.trim()).apply()
    var routerLanUrl: String get() = sp.getString("router_lan_url_v1", "") ?: ""
        set(v) = sp.edit().putString("router_lan_url_v1", v.trim()).apply()
    var routerWanUrl: String get() = sp.getString("router_wan_url_v1", "") ?: ""
        set(v) = sp.edit().putString("router_wan_url_v1", v.trim()).apply()
    var routerDisplayName: String get() = sp.getString("router_display_name_v1", "") ?: ""
        set(v) = sp.edit().putString("router_display_name_v1", v.trim()).apply()
    var certificateExpiryJson: String get() = sp.getString("certificate_expiry_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("certificate_expiry_v1", v).apply()
    var certificateReminderKeysJson: String get() = sp.getString("certificate_reminder_keys_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("certificate_reminder_keys_v1", v).apply()
    var webhookPortOverridesJson: String get() = sp.getString("webhook_port_overrides_v1", "{}") ?: "{}"
        set(v) = sp.edit().putString("webhook_port_overrides_v1", v).apply()
    var webhookRemarkOverridesJson: String get() = sp.getString("webhook_remark_overrides_v1", "{}") ?: "{}"
        set(v) = sp.edit().putString("webhook_remark_overrides_v1", v).apply()

    fun getWebhookPortOverride(key: String): String {
        return try {
            val obj = JSONObject(webhookPortOverridesJson)
            obj.optString(key.lowercase(Locale.getDefault()).trim(), "")
        } catch (_: Exception) { "" }
    }

    fun setWebhookPortOverride(key: String, port: String) {
        val cleanKey = key.lowercase(Locale.getDefault()).trim()
        if (cleanKey.isBlank()) return
        try {
            val obj = JSONObject(webhookPortOverridesJson)
            if (port.isBlank()) {
                obj.remove(cleanKey)
            } else {
                obj.put(cleanKey, port.trim())
            }
            webhookPortOverridesJson = obj.toString()
        } catch (_: Exception) {
            val obj = JSONObject()
            if (port.isNotBlank()) obj.put(cleanKey, port.trim())
            webhookPortOverridesJson = obj.toString()
        }
    }

    fun getWebhookRemarkOverride(key: String): String {
        return try {
            val obj = JSONObject(webhookRemarkOverridesJson)
            obj.optString(key.lowercase(Locale.getDefault()).trim(), "")
        } catch (_: Exception) { "" }
    }

    fun setWebhookRemarkOverride(key: String, remark: String) {
        val cleanKey = key.lowercase(Locale.getDefault()).trim()
        if (cleanKey.isBlank()) return
        try {
            val obj = JSONObject(webhookRemarkOverridesJson)
            if (remark.isBlank()) {
                obj.remove(cleanKey)
            } else {
                obj.put(cleanKey, remark.trim())
            }
            webhookRemarkOverridesJson = obj.toString()
        } catch (_: Exception) {
            val obj = JSONObject()
            if (remark.isNotBlank()) obj.put(cleanKey, remark.trim())
            webhookRemarkOverridesJson = obj.toString()
        }
    }

    var routerExitIpv4: String get() = sp.getString("router_exit_ipv4_v1", "") ?: ""
        set(v) = sp.edit().putString("router_exit_ipv4_v1", v.trim()).apply()

    private fun historyLimit(key: String): Int = if (key.contains("ssh_cmd", true)) 6 else 3
    private fun getHistory(key: String): List<String> = (sp.getString(key, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotBlank() }.take(historyLimit(key))
    private fun putHistory(key: String, items: List<String>) { sp.edit().putString(key, items.distinct().take(historyLimit(key)).joinToString("\n")).apply() }
    fun history(key: String): List<String> {
        val values = getHistory("history_" + key)
        return if (key.equals("hub", true)) values.map(::normalizeHubAddressForDisplay).filter(String::isNotBlank).distinct() else values
    }
    fun addHistory(key: String, value: String) {
        val v = if (key.equals("hub", true)) normalizeHubAddressForDisplay(value) else value.trim()
        if (v.isBlank()) return
        val old = getHistory("history_" + key)
        val filtered = if (key.equals("hub", true)) {
            old.filter { normalizeHubAddressForDisplay(it) != v }
        } else {
            old.filter { it != v }
        }
        putHistory("history_" + key, listOf(v) + filtered)
    }
    fun removeHistory(key: String, value: String) {
        val target = if (key.equals("hub", true)) normalizeHubAddressForDisplay(value) else value
        putHistory(
            "history_" + key,
            getHistory("history_" + key).filter {
                if (key.equals("hub", true)) normalizeHubAddressForDisplay(it) != target else it != target
            }
        )
    }

    fun dnsQueryHistory(): List<DnsQueryHistory> {
        val arr = runCatching { JSONArray(sp.getString("dns_query_history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DnsQueryHistory(
                domain = o.optString("domain"),
                time = o.optString("time"),
                summary = o.optString("summary"),
                signature = o.optString("signature")
            )
        }.filter { it.domain.isNotBlank() && it.summary.isNotBlank() }.take(10)
    }

    fun addDnsQueryHistory(domain: String, records: List<DnsRecord>) {
        val d = domain.trim()
        if (d.isBlank() || records.isEmpty()) return
        val valid = records.filter { it.value.isNotBlank() && !it.value.startsWith("无记录") }
        if (valid.isEmpty()) return
        val signature = d + "|" + valid.map { it.type + ":" + it.value + ":" + it.operator }.distinct().sorted().joinToString(",")
        val old = dnsQueryHistory()
        if (old.any { it.signature == signature }) return
        val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        fun operatorText(op: String): String = op.replace(" · ", " ").trim()
        fun line(type: String): String? {
            val items = valid.filter { it.type == type }
            if (items.isEmpty()) return null
            return type + " " + items.joinToString(" / ") { r ->
                r.value + if (r.operator.isNotBlank()) " " + operatorText(r.operator) else ""
            }
        }
        val summary = listOfNotNull(line("A"), line("AAAA")).joinToString("\n")
        if (summary.isBlank()) return
        val arr = JSONArray()
        (listOf(DnsQueryHistory(d, now, summary, signature)) + old).take(10).forEach { h ->
            arr.put(JSONObject().put("domain", h.domain).put("time", h.time).put("summary", h.summary).put("signature", h.signature))
        }
        sp.edit().putString("dns_query_history", arr.toString()).apply()
    }

    fun clearDnsQueryHistory() { sp.edit().putString("dns_query_history", "[]").apply() }

    var cacheStatus: String get() = sp.getString("cache_status", "") ?: ""
        set(v) = sp.edit().putString("cache_status", v).apply()
    var cacheRouterDashboard: String get() = sp.getString("cache_router_dashboard_v1", "") ?: ""
        set(v) = sp.edit().putString("cache_router_dashboard_v1", v).apply()
    var cacheVpnRowsJson: String get() = sp.getString("cache_home_vpn_rows_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("cache_home_vpn_rows_v1", v).apply()
    var cacheStunRowsJson: String get() = sp.getString("cache_home_stun_rows_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("cache_home_stun_rows_v1", v).apply()
    var cacheDevices: String get() = sp.getString("cache_devices", "") ?: ""
        set(v) = sp.edit().putString("cache_devices", v).apply()
    var cacheOnlineDevices: String get() = sp.getString("cache_online_devices", "") ?: ""
        set(v) = sp.edit().putString("cache_online_devices", v).apply()
    var cacheOfflineDevices: String get() = sp.getString("cache_offline_devices_v1", "") ?: ""
        set(v) = sp.edit().putString("cache_offline_devices_v1", v).apply()
    var offlineHiddenKeysJson: String get() = sp.getString("offline_hidden_keys_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("offline_hidden_keys_v1", v).apply()
    var cacheEvents: String get() = sp.getString("cache_events", "") ?: ""
        set(v) = sp.edit().putString("cache_events", v).apply()
    var hiddenEventDatesJson: String get() = sp.getString("hidden_event_dates_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("hidden_event_dates_v1", v).apply()
    var eventNotificationBaselineReady: Boolean get() = sp.getBoolean("event_notification_baseline_ready", false)
        set(v) = sp.edit().putBoolean("event_notification_baseline_ready", v).apply()
    var wolDevicesJson: String get() = sp.getString("wol_devices_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("wol_devices_v1", v).apply()
    var deviceOverridesJson: String get() = sp.getString("device_overrides_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("device_overrides_v1", v).apply()
    var lastRefresh: String get() = sp.getString("last_refresh", "") ?: ""
        set(v) = sp.edit().putString("last_refresh", v).apply()
    var syncRevision: Long get() = sp.getLong("sync_revision_v1", 0L)
        set(v) = sp.edit().putLong("sync_revision_v1", v.coerceAtLeast(0L)).apply()
    var lastFullSyncAt: Long get() = sp.getLong("last_full_sync_at_v1", 0L)
        set(v) = sp.edit().putLong("last_full_sync_at_v1", v.coerceAtLeast(0L)).apply()
    var syncHub: String get() = normalizeHubAddressForDisplay(sp.getString("sync_hub_v1", "") ?: "")
        set(v) = sp.edit().putString("sync_hub_v1", normalizeHubAddressForDisplay(v)).apply()

    var pingHost: String get() = sp.getString("ping_host", "223.5.5.5") ?: "223.5.5.5"
        set(v) = sp.edit().putString("ping_host", v).apply()
    var pingCount: String get() = sp.getString("ping_count", "1000") ?: "1000"
        set(v) = sp.edit().putString("ping_count", v).apply()
    var pingInterval: String get() = sp.getString("ping_interval", "500") ?: "500"
        set(v) = sp.edit().putString("ping_interval", v).apply()
    var pingTimeout: String get() = sp.getString("ping_timeout", "自动") ?: "自动"
        set(v) = sp.edit().putString("ping_timeout", v).apply()
    var pingProtocol: String get() = sp.getString("ping_protocol", "ICMP") ?: "ICMP"
        set(v) = sp.edit().putString("ping_protocol", v).apply()
    var pingIpMode: String get() = sp.getString("ping_ip_mode", "IPv6优先") ?: "IPv6优先"
        set(v) = sp.edit().putString("ping_ip_mode", v).apply()
    var pingDnsMode: String get() = sp.getString("ping_dns_mode", "优先AAAA") ?: "优先AAAA"
        set(v) = sp.edit().putString("ping_dns_mode", v).apply()
    var pingPort: String get() = sp.getString("ping_port", "80") ?: "80"
        set(v) = sp.edit().putString("ping_port", v).apply()

    fun pingHistory(): List<PingHistoryEntry> {
        val raw = sp.getString("ping_history_v2", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PingHistoryEntry(
                id = o.optLong("id"),
                time = o.optString("time"),
                target = o.optString("target"),
                protocol = o.optString("protocol"),
                ipMode = o.optString("ipMode"),
                dnsMode = o.optString("dnsMode"),
                resolvedIp = o.optString("resolvedIp"),
                count = o.optInt("count"),
                sent = o.optInt("sent"),
                ok = o.optInt("ok"),
                loss = o.optInt("loss"),
                avg = if (o.has("avg") && !o.isNull("avg")) o.optInt("avg") else null,
                max = if (o.has("max") && !o.isNull("max")) o.optInt("max") else null,
                min = if (o.has("min") && !o.isNull("min")) o.optInt("min") else null,
                elapsedMs = o.optLong("elapsedMs"),
                rate = o.optDouble("rate"),
                bytes = raw.toByteArray().size
            )
        }.filter { it.target.isNotBlank() }.take(10)
    }

    fun pingHistoryBytes(): Int = (sp.getString("ping_history_v2", "[]") ?: "[]").toByteArray().size

    fun addPingHistory(entry: PingHistoryEntry) {
        val arr = JSONArray()
        (listOf(entry) + pingHistory()).take(10).forEach { h ->
            arr.put(JSONObject()
                .put("id", h.id)
                .put("time", h.time)
                .put("target", h.target)
                .put("protocol", h.protocol)
                .put("ipMode", h.ipMode)
                .put("dnsMode", h.dnsMode)
                .put("resolvedIp", h.resolvedIp)
                .put("count", h.count)
                .put("sent", h.sent)
                .put("ok", h.ok)
                .put("loss", h.loss)
                .put("avg", h.avg ?: JSONObject.NULL)
                .put("max", h.max ?: JSONObject.NULL)
                .put("min", h.min ?: JSONObject.NULL)
                .put("elapsedMs", h.elapsedMs)
                .put("rate", h.rate)
            )
        }
        sp.edit().putString("ping_history_v2", arr.toString()).apply()
    }

    fun clearPingHistory() { sp.edit().putString("ping_history_v2", "[]").apply() }

    var dnsDomain: String get() = sp.getString("dns_domain", "net86.dynv6.net") ?: "net86.dynv6.net"
        set(v) = sp.edit().putString("dns_domain", v).apply()
    var dns1: String get() = sp.getString("dns1", DEFAULT_DNS1) ?: DEFAULT_DNS1
        set(v) = sp.edit().putString("dns1", v).apply()
    var dns2: String get() = sp.getString("dns2", DEFAULT_DNS2) ?: DEFAULT_DNS2
        set(v) = sp.edit().putString("dns2", v).apply()
    var dnsRecord: String get() = sp.getString("dns_record", "ALL") ?: "ALL"
        set(v) = sp.edit().putString("dns_record", v).apply()
    var dnsUseSystem: Boolean get() = sp.getBoolean("dns_use_system", false)
        set(v) = sp.edit().putBoolean("dns_use_system", v).apply()

    var tcpHost: String get() = sp.getString("tcp_host", "192.168.5.46") ?: "192.168.5.46"
        set(v) = sp.edit().putString("tcp_host", v).apply()
    var tcpPort: String get() = sp.getString("tcp_port", "58443") ?: "58443"
        set(v) = sp.edit().putString("tcp_port", v).apply()
    var tcpTimeout: String get() = sp.getString("tcp_timeout", "1000") ?: "1000"
        set(v) = sp.edit().putString("tcp_timeout", v).apply()
    var tcpPeakHost: String get() = sp.getString("tcp_peak_host_v1", "") ?: ""
        set(v) = sp.edit().putString("tcp_peak_host_v1", v.trim()).apply()
    var tcpPeakPort: String get() = sp.getString("tcp_peak_port_v1", "443") ?: "443"
        set(v) = sp.edit().putString("tcp_peak_port_v1", v.trim()).apply()
    var tcpPeakTarget: String get() = sp.getString("tcp_peak_target_v1", "10000") ?: "10000"
        set(v) = sp.edit().putString("tcp_peak_target_v1", v.trim()).apply()
    var tcpPeakCps: String get() = sp.getString("tcp_peak_cps_v1", "500") ?: "500"
        set(v) = sp.edit().putString("tcp_peak_cps_v1", v.trim()).apply()
    var tcpPeakHistoryJson: String get() = sp.getString("tcp_peak_history_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("tcp_peak_history_v1", v).apply()
    var tcpPeakPendingAiCommandJson: String get() = sp.getString("tcp_peak_ai_command_v1", "") ?: ""
        set(v) = sp.edit().putString("tcp_peak_ai_command_v1", v).apply()
    var tcpPeakExtremeMode: Boolean get() = sp.getBoolean("tcp_peak_extreme_v1", false)
        set(v) = sp.edit().putBoolean("tcp_peak_extreme_v1", v).apply()
    var tcpPeakHostHistoryJson: String get() = sp.getString("tcp_peak_host_history_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("tcp_peak_host_history_v1", v).apply()

    fun tcpPeakHostHistory(): List<String> = runCatching {
        val arr = JSONArray(tcpPeakHostHistoryJson)
        (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }
    }.getOrDefault(emptyList())

    fun addTcpPeakHostHistory(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        val current = tcpPeakHostHistory().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val limited = current.take(5)
        val arr = JSONArray().apply { limited.forEach { put(it) } }
        tcpPeakHostHistoryJson = arr.toString()
    }

    fun removeTcpPeakHostHistory(host: String) {
        val trimmed = host.trim()
        val current = tcpPeakHostHistory().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        val arr = JSONArray().apply { current.forEach { put(it) } }
        tcpPeakHostHistoryJson = arr.toString()
    }
    var portProtocol: String get() = sp.getString("port_protocol", "TCP") ?: "TCP"
        set(v) = sp.edit().putString("port_protocol", v).apply()

    var natServer: String get() = sp.getString("nat_server", "stun.l.google.com") ?: "stun.l.google.com"
        set(v) = sp.edit().putString("nat_server", v.trim()).apply()
    var natPort: String get() = sp.getString("nat_port", "19302") ?: "19302"
        set(v) = sp.edit().putString("nat_port", v.trim()).apply()
    var natTimeout: String get() = sp.getString("nat_timeout", "1200") ?: "1200"
        set(v) = sp.edit().putString("nat_timeout", v.trim()).apply()
    var natIpMode: String get() = sp.getString("nat_ip_mode", "自动") ?: "自动"
        set(v) = sp.edit().putString("nat_ip_mode", v).apply()
    var natMode: String get() = sp.getString("nat_mode", "RFC5780") ?: "RFC5780"
        set(v) = sp.edit().putString("nat_mode", v).apply()

    var udpHost: String get() = sp.getString("udp_host", "stun.voip.aebc.com") ?: "stun.voip.aebc.com"
        set(v) = sp.edit().putString("udp_host", v.trim()).apply()
    var udpPort: String get() = sp.getString("udp_port", "3478") ?: "3478"
        set(v) = sp.edit().putString("udp_port", v.trim()).apply()
    var udpTimeout: String get() = sp.getString("udp_timeout", "1000") ?: "1000"
        set(v) = sp.edit().putString("udp_timeout", v.trim()).apply()
    var udpTemplate: String get() = sp.getString("udp_template", "STUN Binding") ?: "STUN Binding"
        set(v) = sp.edit().putString("udp_template", v).apply()
    var udpIpMode: String get() = sp.getString("udp_ip_mode", "自动") ?: "自动"
        set(v) = sp.edit().putString("udp_ip_mode", v).apply()

    fun natServers(mode: String): List<StunServerItem> {
        val key = if (mode == "RFC3489") "nat_servers_3489" else "nat_servers_5780"
        val fallback = if (mode == "RFC3489") listOf(StunServerItem("stun.miwifi.com", 3478)) else listOf(StunServerItem("stun.voip.aebc.com", 3478))
        val raw = sp.getString(key, null) ?: return fallback
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val h = o.optString("host").trim()
                val p = o.optInt("port", 3478).coerceIn(1, 65535)
                if (h.isBlank()) null else StunServerItem(h, p)
            }.ifEmpty { fallback }
        }.getOrDefault(fallback).take(10)
    }

    fun saveNatServers(mode: String, list: List<StunServerItem>) {
        val key = if (mode == "RFC3489") "nat_servers_3489" else "nat_servers_5780"
        val arr = JSONArray()
        list.distinctBy { it.host.lowercase(Locale.getDefault()) + ":" + it.port }.take(10).forEach { s ->
            arr.put(JSONObject().put("host", s.host).put("port", s.port))
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    fun addNatServer(mode: String, host: String, port: Int) {
        val h = host.trim()
        if (h.isBlank()) return
        saveNatServers(mode, listOf(StunServerItem(h, port.coerceIn(1, 65535))) + natServers(mode).filterNot { it.host.equals(h, true) && it.port == port })
    }

    fun deleteNatServer(mode: String, item: StunServerItem) {
        saveNatServers(mode, natServers(mode).filterNot { it.host.equals(item.host, true) && it.port == item.port })
    }

    fun resetNatServers(mode: String) {
        val key = if (mode == "RFC3489") "nat_servers_3489" else "nat_servers_5780"
        sp.edit().remove(key).apply()
    }

    fun natHistory(): List<NatHistoryEntry> {
        val raw = sp.getString("nat_history_v2", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                NatHistoryEntry(
                    id = o.optLong("id", i.toLong()),
                    time = o.optString("time"),
                    mode = o.optString("mode"),
                    server = o.optString("server"),
                    classicType = o.optString("classicType", "未知"),
                    confidence = o.optString("confidence", "低"),
                    mapped = o.optString("mapped"),
                    local = o.optString("local"),
                    ipv6 = o.optString("ipv6"),
                    operator = o.optString("operator"),
                    priority = o.optString("priority"),
                    elapsedMs = o.optLong("elapsedMs", 0L),
                    summary = o.optString("summary")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addNatHistory(entry: NatHistoryEntry) {
        val arr = JSONArray()
        (listOf(entry) + natHistory()).distinctBy { it.id }.take(50).forEach { h ->
            arr.put(JSONObject()
                .put("id", h.id)
                .put("time", h.time)
                .put("mode", h.mode)
                .put("server", h.server)
                .put("classicType", h.classicType)
                .put("confidence", h.confidence)
                .put("mapped", h.mapped)
                .put("local", h.local)
                .put("ipv6", h.ipv6)
                .put("operator", h.operator)
                .put("priority", h.priority)
                .put("elapsedMs", h.elapsedMs)
                .put("summary", h.summary))
        }
        sp.edit().putString("nat_history_v2", arr.toString()).apply()
    }

    fun deleteNatHistory(id: Long) {
        val arr = JSONArray()
        natHistory().filterNot { it.id == id }.forEach { h ->
            arr.put(JSONObject()
                .put("id", h.id).put("time", h.time).put("mode", h.mode).put("server", h.server)
                .put("classicType", h.classicType).put("confidence", h.confidence).put("mapped", h.mapped)
                .put("local", h.local).put("ipv6", h.ipv6).put("operator", h.operator).put("priority", h.priority)
                .put("elapsedMs", h.elapsedMs).put("summary", h.summary))
        }
        sp.edit().putString("nat_history_v2", arr.toString()).apply()
    }

    fun clearNatHistory() { sp.edit().putString("nat_history_v2", "[]").apply() }

    var sshHost: String get() = sp.getString("ssh_host", "192.168.5.1") ?: "192.168.5.1"
        set(v) = sp.edit().putString("ssh_host", v).apply()
    var sshPort: String get() = sp.getString("ssh_port", "54133") ?: "54133"
        set(v) = sp.edit().putString("ssh_port", v).apply()
    var sshUser: String get() = sp.getString("ssh_user", "root") ?: "root"
        set(v) = sp.edit().putString("ssh_user", v).apply()
    var sshSavePass: Boolean get() = sp.getBoolean("ssh_save_pass", false)
        set(v) = sp.edit().putBoolean("ssh_save_pass", v).apply()
    var sshPassword: String get() = secureSshPasswordStore.get()
        set(v) { secureSshPasswordStore.set(v); if (sp.contains("ssh_password")) sp.edit().remove("ssh_password").apply() }
    var sshCommand: String get() = sp.getString("ssh_cmd", "ip -6 neigh show") ?: "ip -6 neigh show"
        set(v) = sp.edit().putString("ssh_cmd", v).apply()

    fun sshResults(): List<SshResultEntry> {
        val raw = sp.getString("ssh_results_v1", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SshResultEntry(
                    id = o.optLong("id", i.toLong()),
                    time = o.optString("time"),
                    host = o.optString("host"),
                    command = o.optString("command"),
                    output = o.optString("output")
                )
            }
        }.getOrDefault(emptyList()).take(30)
    }

    fun sshResultsStorageBytes(): Long = (sp.getString("ssh_results_v1", "[]") ?: "[]")
        .toByteArray(Charsets.UTF_8)
        .size
        .toLong()

    fun addSshResult(entry: SshResultEntry) {
        val arr = JSONArray()
        (listOf(entry) + sshResults()).distinctBy { it.id }.take(30).forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("host", r.host)
                .put("command", r.command)
                .put("output", r.output))
        }
        sp.edit().putString("ssh_results_v1", arr.toString()).apply()
    }

    fun deleteSshResult(id: Long) {
        val arr = JSONArray()
        sshResults().filterNot { it.id == id }.forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("host", r.host)
                .put("command", r.command)
                .put("output", r.output))
        }
        sp.edit().putString("ssh_results_v1", arr.toString()).apply()
    }

    fun clearSshResults() { sp.edit().putString("ssh_results_v1", "[]").apply() }

    var traceHost: String get() = sp.getString("trace_host", "net86.dynv6.net") ?: "net86.dynv6.net"
        set(v) = sp.edit().putString("trace_host", v.trim()).apply()
    var traceMaxHops: String get() = sp.getString("trace_max_hops", "16") ?: "16"
        set(v) = sp.edit().putString("trace_max_hops", v.trim()).apply()
    var traceTimeout: String get() = sp.getString("trace_timeout", "1200") ?: "1200"
        set(v) = sp.edit().putString("trace_timeout", v.trim()).apply()
    var traceIpMode: String get() = sp.getString("trace_ip_mode", "IPv6优先") ?: "IPv6优先"
        set(v) = sp.edit().putString("trace_ip_mode", v).apply()

    fun traceHistory(): List<TraceHistoryEntry> {
        val raw = sp.getString("trace_history_v1", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TraceHistoryEntry(
                    id = o.optLong("id", i.toLong()),
                    time = o.optString("time"),
                    host = o.optString("host"),
                    ipMode = o.optString("ipMode"),
                    hops = o.optInt("hops", 0),
                    status = o.optString("status"),
                    output = o.optString("output")
                )
            }
        }.getOrDefault(emptyList()).take(15)
    }

    fun addTraceHistory(entry: TraceHistoryEntry) {
        val arr = JSONArray()
        (listOf(entry) + traceHistory()).distinctBy { it.id }.take(15).forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("host", r.host)
                .put("ipMode", r.ipMode)
                .put("hops", r.hops)
                .put("status", r.status)
                .put("output", r.output))
        }
        sp.edit().putString("trace_history_v1", arr.toString()).apply()
    }

    fun deleteTraceHistory(id: Long) {
        val arr = JSONArray()
        traceHistory().filterNot { it.id == id }.forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("host", r.host)
                .put("ipMode", r.ipMode)
                .put("hops", r.hops)
                .put("status", r.status)
                .put("output", r.output))
        }
        sp.edit().putString("trace_history_v1", arr.toString()).apply()
    }

    fun clearTraceHistory() { sp.edit().putString("trace_history_v1", "[]").apply() }

    fun roamingReports(): List<RoamingReport> {
        val raw = sp.getString("roaming_reports_v1", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val ev = o.optJSONArray("events")
                val switchArray = o.optJSONArray("switches")
                val networkArray = o.optJSONArray("networkEvents")
                RoamingReport(
                    id = o.optLong("id", i.toLong()),
                    time = o.optString("time"),
                    ssid = o.optString("ssid"),
                    targetMode = o.optString("targetMode"),
                    sampleMode = o.optString("sampleMode"),
                    durationSec = o.optInt("durationSec"),
                    sampleCount = o.optInt("sampleCount"),
                    gatewayMinMs = o.optNullableInt("gatewayMinMs"),
                    gatewayMaxMs = o.optNullableInt("gatewayMaxMs"),
                    gatewayAvgMs = o.optNullableInt("gatewayAvgMs"),
                    rssiBestDbm = o.optNullableInt("rssiBestDbm"),
                    rssiWorstDbm = o.optNullableInt("rssiWorstDbm"),
                    rssiAvgDbm = o.optNullableInt("rssiAvgDbm"),
                    speedMaxMbps = o.optNullableInt("speedMaxMbps"),
                    speedMinMbps = o.optNullableInt("speedMinMbps"),
                    speedAvgMbps = o.optNullableInt("speedAvgMbps"),
                    roamCount = o.optInt("roamCount"),
                    longestBreakMs = o.optNullableInt("longestBreakMs"),
                    lossNearRoam = o.optInt("lossNearRoam"),
                    stickyScore = o.optInt("stickyScore"),
                    bestCandidateGap = o.optNullableInt("bestCandidateGap"),
                    qualityScore = o.optInt("qualityScore"),
                    qualityLabel = o.optString("qualityLabel"),
                    conclusion = o.optString("conclusion"),
                    events = if (ev == null) emptyList() else (0 until ev.length()).map { idx -> ev.optString(idx) }.filter { it.isNotBlank() },
                    schemaVersion = o.optInt("schemaVersion", 1),
                    durationMs = o.optLong("durationMs", o.optInt("durationSec").toLong() * 1_000L),
                    longestObservationMs = o.optNullableLong("longestObservationMs"),
                    gatewayAttemptCount = o.optInt("gatewayAttemptCount"),
                    gatewayLossCount = o.optInt("gatewayLossCount"),
                    wanAttemptCount = o.optInt("wanAttemptCount"),
                    wanLossCount = o.optInt("wanLossCount"),
                    gatewayRoamLossCount = o.optInt("gatewayRoamLossCount", o.optInt("gatewayLossCount")),
                    wanRoamLossCount = o.optInt("wanRoamLossCount", o.optInt("wanLossCount")),
                    wanMinMs = o.optNullableInt("wanMinMs"),
                    wanMaxMs = o.optNullableInt("wanMaxMs"),
                    wanAvgMs = o.optNullableInt("wanAvgMs"),
                    gatewayRecoveryMaxMs = o.optNullableLong("gatewayRecoveryMaxMs"),
                    wanRecoveryMaxMs = o.optNullableLong("wanRecoveryMaxMs"),
                    networkDisconnectCount = o.optInt("networkDisconnectCount"),
                    wifiGapP95Ms = o.optNullableLong("wifiGapP95Ms"),
                    wifiGapMaxMs = o.optNullableLong("wifiGapMaxMs"),
                    impactWindowTruncated = o.optBoolean("impactWindowTruncated", false),
                    switches = if (switchArray == null) emptyList() else (0 until switchArray.length()).mapNotNull { idx ->
                        switchArray.optJSONObject(idx)?.let { RoamingSwitchReport.fromJson(it) }
                    },
                    networkEvents = if (networkArray == null) emptyList() else (0 until networkArray.length()).map { idx -> networkArray.optString(idx) }.filter { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList()).take(20)
    }

    fun addRoamingReport(report: RoamingReport) {
        val arr = JSONArray()
        (listOf(report) + roamingReports()).distinctBy { it.id }.take(20).forEach { r ->
            arr.put(r.toJson())
        }
        sp.edit().putString("roaming_reports_v1", arr.toString()).apply()
    }

    fun deleteRoamingReport(id: Long) {
        val arr = JSONArray()
        roamingReports().filterNot { it.id == id }.take(20).forEach { arr.put(it.toJson()) }
        sp.edit().putString("roaming_reports_v1", arr.toString()).apply()
    }

    fun clearRoamingReports() { sp.edit().putString("roaming_reports_v1", "[]").apply() }

    fun roamingReportsKb(): Int {
        val raw = sp.getString("roaming_reports_v1", "[]") ?: "[]"
        return ((raw.toByteArray(Charsets.UTF_8).size + 1023) / 1024).coerceAtLeast(1)
    }
}
