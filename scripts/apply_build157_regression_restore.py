#!/usr/bin/env python3
"""Restore user-facing fixes skipped by the build155/156 early prepare path.

This patch deliberately runs last. It restores the already approved NAT selector
UI, Chinese status/result text, cache-preserving refresh behavior and consistent
Hub/WSS state without changing the native realtime transport architecture.
"""
from pathlib import Path

from apply_v01015_router_stability import (
    patch_router_api as restore_router_api,
    patch_router_control as restore_router_control,
    patch_router_native as restore_router_native,
)
from apply_v01015_scoped_fixes import patch_nat_history_and_controls
from apply_v01015_final_scoped_fixes import (
    patch_generated_nat_protocol_context,
    patch_generated_network_diagnostic_cache,
)
from apply_v01015_nat_text_hotfix import apply as apply_nat_text_hotfix
from apply_v01015_requested_hotfix import patch_beta_message, patch_router_status_message

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
ROUTER_API = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlApi.kt"
ROUTER_SETTINGS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt"
ROUTER_STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build157 regression pattern: {label}")
    return text.replace(old, new, 1)


def patch_main_cache_and_connection() -> None:
    text = MAIN.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '    var cacheStatus: String get() = sp.getString("cache_status", "") ?: ""\n'
        '        set(v) = sp.edit().putString("cache_status", v).apply()\n'
        '    var cacheDevices: String get() = sp.getString("cache_devices", "") ?: ""\n',
        '    var cacheStatus: String get() = sp.getString("cache_status", "") ?: ""\n'
        '        set(v) = sp.edit().putString("cache_status", v).apply()\n'
        '    var cacheRouterDashboard: String get() = sp.getString("cache_router_dashboard_v1", "") ?: ""\n'
        '        set(v) = sp.edit().putString("cache_router_dashboard_v1", v).apply()\n'
        '    var cacheDevices: String get() = sp.getString("cache_devices", "") ?: ""\n',
        "router dashboard preference cache",
    )

    text = replace_once(
        text,
        '    var routerDashboard by mutableStateOf<JSONObject?>(null)\n',
        '    var routerDashboard by mutableStateOf<JSONObject?>(\n'
        '        prefs.cacheRouterDashboard.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }\n'
        '    )\n',
        "restore router dashboard cache",
    )

    old_methods = '''    suspend fun refreshRouterDashboard(silent: Boolean = true) {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        runCatching { HubApi(prefs).getRouterDashboard() }
            .onSuccess { routerDashboard = it; routerDashboardError = "" }
            .onFailure {
                routerDashboardError = it.message ?: "Hub无法获取路由器状态"
                if (!silent) message = routerDashboardError
            }
    }

    suspend fun requestRouterDashboardRefresh(): Long {
        val nonce = HubApi(prefs).requestRouterDashboardRefresh()
        repeat(6) {
            delay(700L)
            val latest = runCatching { HubApi(prefs).getRouterDashboard() }.getOrNull()
            if (latest != null) {
                routerDashboard = latest
                routerDashboardError = ""
                if (latest.optLong("refreshCompletedNonce", 0L) >= nonce) return nonce
            }
        }
        return nonce
    }'''
    new_methods = '''    suspend fun refreshRouterDashboard(silent: Boolean = true) {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        val previous = routerDashboard
        runCatching { HubApi(prefs).getRouterDashboard() }
            .onSuccess { latest ->
                routerDashboard = mergeRouterDashboardSnapshot(previous, latest)
                routerDashboard?.let { prefs.cacheRouterDashboard = it.toString() }
                routerDashboardError = ""
            }
            .onFailure { failure ->
                if (previous != null) {
                    routerDashboard = previous
                    routerDashboardError = ""
                } else {
                    routerDashboardError = appErrorZh(failure.message, "Hub 暂时无法获取路由器状态")
                    if (!silent) message = routerDashboardError
                }
            }
    }

    suspend fun requestRouterDashboardRefresh(): Long {
        val previous = routerDashboard
        val nonce = HubApi(prefs).requestRouterDashboardRefresh()
        repeat(4) {
            delay(350L)
            val latest = runCatching { HubApi(prefs).getRouterDashboard() }.getOrNull()
            if (latest != null) {
                routerDashboard = mergeRouterDashboardSnapshot(routerDashboard ?: previous, latest)
                routerDashboard?.let { prefs.cacheRouterDashboard = it.toString() }
                routerDashboardError = ""
                if (latest.optLong("refreshCompletedNonce", 0L) >= nonce) return nonce
            }
        }
        if (routerDashboard == null && previous != null) routerDashboard = previous
        return nonce
    }'''
    text = replace_once(text, old_methods, new_methods, "cache-preserving router refresh")

    helper = '''
private fun appErrorZh(raw: String?, fallback: String = "请求失败"): String {
    val text = raw.orEmpty().trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> fallback
        "hub is online, but router data is unavailable" in lower -> "Hub 已连接，正在等待路由器实时数据"
        "waiting for hub status" in lower -> "正在等待 Hub 状态"
        "timeout" in lower || "timed out" in lower -> "请求超时，请稍后重试"
        "unable to resolve" in lower || "unknown host" in lower || "dns" in lower -> "域名解析失败"
        "failed to connect" in lower || "connection refused" in lower -> "无法连接 Hub"
        "unauthorized" in lower || "forbidden" in lower || "http 401" in lower || "http 403" in lower -> "Hub 认证失败"
        else -> text
    }
}
'''
    if "private fun appErrorZh" not in text:
        anchor = 'class RouterStatusUnavailableException(message: String = "Hub 在线，但没有路由器数据") : HubRouterNoDataException(message)\n'
        if anchor not in text:
            raise RuntimeError("missing build157 regression pattern: app error helper anchor")
        text = text.replace(anchor, anchor + helper, 1)

    old_catch = '''            } catch (first: Exception) {
                val wasConnected = hubConnected
                hubConnected = false
                if (wasConnected) {
                    if (!silent) message = "Hub 已断开，正在自动重连..."
                    try {
                        val api = HubApi(prefs)

                        api.keepaliveWithRetry(5)
                        hubConnected = true
                        if (!silent) message = "重连成功，正在校准完整数据..."
                        syncFull(api, silent)
                    } catch (second: Exception) {
                        hubConnected = false
                        message = "Hub 已断开，自动重连 5 次失败 · 最后更新 ${prefs.lastRefresh.ifBlank { "未知" }}：${second.message}"
                    }
                } else if (attemptedReconnect) {
                    message = "Hub 已断开，自动重连 5 次失败 · 最后更新 ${prefs.lastRefresh.ifBlank { "未知" }}：${first.message}"
                } else {
                    message = "Hub 已断开，已保留数据 · 最后更新 ${prefs.lastRefresh.ifBlank { "未知" }}：${first.message}"
                }
            } finally {'''
    new_catch = '''            } catch (first: Exception) {
                val realtimeAlive = mqttConnected
                val wasConnected = hubConnected
                if (realtimeAlive) {
                    // A healthy WSS proves Hub is reachable. A failed full-sync endpoint
                    // must not flip the Hub card to disconnected or clear cached data.
                    hubConnected = true
                    if (!silent) message = "实时链路正常，完整数据同步暂时失败，已保留上次数据"
                } else {
                    hubConnected = false
                    if (wasConnected) {
                        if (!silent) message = "Hub 已断开，正在自动重连..."
                        try {
                            val api = HubApi(prefs)
                            api.keepaliveWithRetry(5)
                            hubConnected = true
                            if (!silent) message = "重连成功，正在校准完整数据..."
                            syncFull(api, silent)
                        } catch (second: Exception) {
                            hubConnected = false
                            message = "Hub 已断开，自动重连 5 次失败 · 最后更新 ${prefs.lastRefresh.ifBlank { "未知" }}：${appErrorZh(second.message)}"
                        }
                    } else if (attemptedReconnect) {
                        message = "Hub 已断开，自动重连 5 次失败 · 最后更新 ${prefs.lastRefresh.ifBlank { "未知" }}：${appErrorZh(first.message)}"
                    } else {
                        message = "Hub 已断开，已保留数据 · 最后更新 ${prefs.lastRefresh.ifBlank { "未知" }}：${appErrorZh(first.message)}"
                    }
                }
            } finally {'''
    text = replace_once(text, old_catch, new_catch, "consistent Hub and WSS state")

    old_changelog = '''            "v$NAME build$CODE · 路由字段与长连接完整修复" to listOf(
                "APP 启动优先建立 Hub 原生 WSS，完整 HTTP 同步不再阻塞连接",
                "长连接保活期间保留最后有效数据，短暂网络抖动不再反复显示重连",
                "终端在线卡片按真实在线数和 Hub 状态显示，不再错误停留在等待同步",
                "恢复真实路由功能页，并补齐 2.4G、5G 温度和存储占用字段"
            )'''
    new_changelog = '''            "v$NAME build$CODE · 路由交互与状态回归修复" to listOf(
                "恢复 NAT 协议与 WAN 类型白色大圆角下拉框和科技蓝阴影",
                "NAT 检测改为后台执行，检测过程、结果、超时与错误提示全部中文化",
                "路由状态、DDNS 与网络自检刷新保留上次有效数据，不再白屏",
                "统一 Hub、WSS 与路由器数据状态，避免实时正常却显示 Hub 断开"
            )'''
    text = replace_once(text, old_changelog, new_changelog, "build157 changelog")

    MAIN.write_text(text, encoding="utf-8")


def patch_router_api_messages() -> None:
    text = ROUTER_API.read_text(encoding="utf-8")
    text = text.replace('val statusText: String = "Waiting for Hub status"', 'val statusText: String = "正在等待 Hub 状态"')
    text = text.replace('val message: String = "Hub is online, but router data is unavailable"', 'val message: String = "Hub 已连接，正在等待路由器实时数据"')
    text = text.replace('statusText = "Hub router data is available"', 'statusText = "Hub 与路由器数据连接正常"')
    text = text.replace('error.message ?: "Hub is online, but router data is unavailable"', 'error.message ?: "Hub 已连接，正在等待路由器实时数据"')
    text = text.replace('error.message ?: "Hub could not log in to the router"', 'error.message ?: "Hub 登录路由器失败"')
    text = text.replace('error.message ?: "Hub router request failed"', 'error.message ?: "Hub 路由请求失败"')
    text = text.replace('root.optString("message", "Hub is online, but router data is unavailable")', 'root.optString("message", "Hub 已连接，正在等待路由器实时数据")')
    text = text.replace('else -> status.message.ifBlank { "等待路由器状态" }', 'else -> routerApiMessageZh(status.message)')

    helper = '''
private fun routerApiMessageZh(raw: String): String {
    val text = raw.trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> "正在检查路由器状态"
        "hub is online, but router data is unavailable" in lower -> "Hub 已连接，正在等待路由器实时数据"
        "waiting for hub status" in lower -> "正在等待 Hub 状态"
        "timeout" in lower || "timed out" in lower -> "请求超时，请稍后重试"
        "login" in lower && ("failed" in lower || "error" in lower) -> "Hub 登录路由器失败"
        else -> text
    }
}
'''
    if "private fun routerApiMessageZh" not in text:
        anchor = '''data class RouterHubStatus(
    val state: String = "no_router_data",
    val connected: Boolean = false,
    val message: String = "Hub 已连接，正在等待路由器实时数据",
    val errorCode: String = "HUB_NO_ROUTER_DATA",
    val lastSuccessAt: Long = 0L
)
'''
        if anchor not in text:
            raise RuntimeError("missing build157 regression pattern: RouterHubStatus helper anchor")
        text = text.replace(anchor, anchor + helper, 1)

    old_failure = '''    fun markFailure(message: String) {
        snapshot = snapshot.copy(
            connected = false,
            statusText = message,
            lastError = message
        )
    }'''
    new_failure = '''    fun markFailure(message: String) {
        val localized = routerApiMessageZh(message)
        snapshot = snapshot.copy(
            connected = false,
            statusText = localized,
            lastError = localized
        )
    }'''
    text = replace_once(text, old_failure, new_failure, "localized RouterConnectionStore errors")
    ROUTER_API.write_text(text, encoding="utf-8")


def patch_router_settings_messages() -> None:
    text = ROUTER_SETTINGS.read_text(encoding="utf-8")
    helper = '''
private fun routerSettingsRawMessageZh(raw: String): String {
    val text = raw.trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> "正在检查路由器状态"
        "hub is online, but router data is unavailable" in lower -> "Hub 已连接，正在等待路由器实时数据"
        "waiting for hub status" in lower -> "正在等待 Hub 状态"
        "timeout" in lower || "timed out" in lower -> "状态请求超时，已保留上次结果"
        else -> text
    }
}
'''
    if "private fun routerSettingsRawMessageZh" not in text:
        anchor = 'private val SettingsBorder = Color(0xFFE3EAF4)\n'
        if anchor not in text:
            raise RuntimeError("missing build157 regression pattern: router settings message anchor")
        text = text.replace(anchor, anchor + helper, 1)
    text = text.replace('else -> status.message.ifBlank { "正在检查路由器状态" }', 'else -> routerSettingsRawMessageZh(status.message)')

    old_header = '''private fun RouterSettingsConnectionCard(status: RouterHubStatus, loading: Boolean, onClick: () -> Unit) {
    val connected = status.connected
    val accent = if (connected) SettingsGreen else SettingsAmber'''
    new_header = '''private fun RouterSettingsConnectionCard(status: RouterHubStatus, loading: Boolean, onClick: () -> Unit) {
    val connected = status.connected
    val hubOnline = loading || status.state in setOf("ready", "syncing", "no_router_data", "recovering") || status.errorCode == "HUB_NO_ROUTER_DATA"
    val accent = when {
        connected -> SettingsGreen
        hubOnline -> SettingsBlue
        else -> SettingsAmber
    }'''
    text = replace_once(text, old_header, new_header, "router settings Hub state semantics")
    text = text.replace(
        'Text(if (connected) "Hub 已连接路由器" else "检查 Hub 路由连接", fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = SettingsInk)',
        'Text(when { connected -> "路由器实时数据正常"; hubOnline -> "Hub 已连接"; else -> "Hub 连接异常" }, fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = SettingsInk)'
    )
    ROUTER_SETTINGS.write_text(text, encoding="utf-8")


def patch_nat_polish() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")
    text = text.replace(
        '''        listOf(
            "stun.hot-chilli.net",
            "stun.fitaauto.ru",
            "stun.internetcalls.com",
            "stun.miwifi.com",
            "stun.voip.aebc.com",''',
        '''        listOf(
            "stun.voip.aebc.com",
            "stun.miwifi.com",
            "stun.hot-chilli.net",
            "stun.internetcalls.com",
            "stun.fitaauto.ru",''',
        1,
    )
    text = text.replace('label = "检测协议"', 'label = "STUN 类型"')
    text = text.replace('label = "出口接口"', 'label = "WAN 类型"')
    text = text.replace('val shape = RoundedCornerShape(14.dp)', 'val shape = RoundedCornerShape(18.dp)', 2)
    text = text.replace('val serverShape = RoundedCornerShape(14.dp)', 'val serverShape = RoundedCornerShape(18.dp)')
    text = text.replace('RoundedCornerShape(16.dp)).background(Color.White)', 'RoundedCornerShape(18.dp)).background(Color.White)')
    text = text.replace(
        'modifier = Modifier.background(if (title == value) NativeBlue.copy(alpha = .08f) else Color.White)',
        'modifier = Modifier.background(Color.White)'
    )
    text = text.replace(
        'modifier = Modifier.background(if (host == server) NativeBlue.copy(alpha = .08f) else Color.White)',
        'modifier = Modifier.background(Color.White)'
    )
    text = text.replace('    else -> value\n}\n\nprivate fun natStatusZh', '    else -> if (value.any { it.code > 127 }) value else "未知 NAT 类型"\n}\n\nprivate fun natStatusZh', 1)
    text = text.replace('    else -> value.ifBlank { "等待检测" }\n}\n\nprivate fun natLogZh', '    else -> if (value.any { it.code > 127 }) value else "状态未知"\n}\n\nprivate fun natLogZh', 1)

    error_helper = '''
private fun natErrorZh(raw: String?): String {
    val text = raw.orEmpty().trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> "NAT 检测失败"
        "timeout" in lower || "timed out" in lower -> "检测请求超时，请更换 STUN 服务器或 WAN 类型后重试"
        "failed to connect" in lower || "connection refused" in lower -> "无法连接 Hub，请检查网络"
        "unknown host" in lower || "resolve" in lower || "dns" in lower -> "STUN 服务器域名解析失败"
        else -> if (text.any { it.code > 127 }) text else "NAT 检测失败，请稍后重试"
    }
}
'''
    if "private fun natErrorZh" not in text:
        anchor = 'private fun natLogZh(raw: String): String {'
        index = text.find(anchor)
        if index < 0:
            raise RuntimeError("missing build157 regression pattern: NAT error helper anchor")
        text = text[:index] + error_helper + '\n' + text[index:]

    start = text.find('fun RouterNatDiagnosticScreen')
    end = text.find('\n@Composable\nfun RouterBetaUpgradeScreen', start)
    if start < 0 or end < 0:
        raise RuntimeError("missing build157 regression pattern: NAT screen boundaries")
    section = text[start:end]
    section = section.replace('.onFailure { error = it.message.orEmpty() }', '.onFailure { error = natErrorZh(it.message) }')
    section = section.replace('.onFailure { error = it.message.orEmpty(); running = false }', '.onFailure { error = natErrorZh(it.message); running = false }')
    text = text[:start] + section + text[end:]
    ROUTER_NATIVE.write_text(text, encoding="utf-8")


def patch_router_status_refresh() -> None:
    text = ROUTER_STATUS.read_text(encoding="utf-8")
    old = '''    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching { state.requestRouterDashboardRefresh() }
                .onFailure { state.refreshRouterDashboard(silent = false) }
            runCatching { state.requestRouterCredentialsRefresh() }
            refreshing = false
        }
    }'''
    new = '''    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching { state.requestRouterDashboardRefresh() }
                .onFailure { state.refreshRouterDashboard(silent = false) }
            refreshing = false
            launch { runCatching { state.requestRouterCredentialsRefresh() } }
        }
    }'''
    text = replace_once(text, old, new, "non-blocking router refresh")
    text = text.replace("实时数据稍旧，等待 Agent 更新", "实时数据暂时未变化，已保留上次结果")
    text = text.replace("实时链路正在自动重连", "实时数据暂时未变化，已保留上次结果")
    ROUTER_STATUS.write_text(text, encoding="utf-8")


def verify() -> None:
    main = MAIN.read_text(encoding="utf-8")
    native = ROUTER_NATIVE.read_text(encoding="utf-8")
    api = ROUTER_API.read_text(encoding="utf-8")
    settings = ROUTER_SETTINGS.read_text(encoding="utf-8")
    required = {
        "MainActivity.kt": (
            "cacheRouterDashboard",
            "mergeRouterDashboardSnapshot(previous, latest)",
            "val realtimeAlive = mqttConnected",
            "实时链路正常，完整数据同步暂时失败，已保留上次数据",
            "路由交互与状态回归修复",
        ),
        "RouterNativeToolsUi.kt": (
            'label = "STUN 类型"',
            'label = "WAN 类型"',
            "private fun NativeSelector",
            "nativeBlueShadow",
            "RoundedCornerShape(18.dp)",
            "natStatusZh",
            "natTypeZh",
            "natLogZh",
            "natErrorZh",
        ),
        "RouterControlApi.kt": (
            "正在等待 Hub 状态",
            "Hub 已连接，正在等待路由器实时数据",
            "routerApiMessageZh",
        ),
        "RouterSettingsUi.kt": (
            "routerSettingsRawMessageZh",
            'connected -> "路由器实时数据正常"',
            'hubOnline -> "Hub 已连接"',
        ),
    }
    values = {
        "MainActivity.kt": main,
        "RouterNativeToolsUi.kt": native,
        "RouterControlApi.kt": api,
        "RouterSettingsUi.kt": settings,
    }
    missing = [f"{name}: {needle}" for name, needles in required.items() for needle in needles if needle not in values[name]]
    if missing:
        raise RuntimeError(f"build157 regression verification failed: {missing}")
    nat_start = native.find("fun RouterNatDiagnosticScreen")
    nat_end = native.find("fun RouterBetaUpgradeScreen", nat_start)
    nat_section = native[nat_start:nat_end]
    if "FilterChip(" in nat_section:
        raise RuntimeError("build157 NAT screen still contains regressed FilterChip controls")
    for forbidden in (
        "Waiting for Hub status",
        "Hub is online, but router data is unavailable",
        "Hub router data is available",
    ):
        if forbidden in api:
            raise RuntimeError(f"build157 English router status remains: {forbidden}")


def apply() -> None:
    # Restore the exact approved fixes that the build155/156 early exit skipped.
    restore_router_control()
    restore_router_native()
    restore_router_api()
    patch_nat_history_and_controls()
    patch_generated_nat_protocol_context()
    patch_generated_network_diagnostic_cache()
    apply_nat_text_hotfix()
    patch_router_status_message()
    patch_beta_message()

    # Apply the new regression guards and state consistency fixes last.
    patch_main_cache_and_connection()
    patch_router_api_messages()
    patch_router_settings_messages()
    patch_nat_polish()
    patch_router_status_refresh()
    verify()
    print("build157 router UX, Chinese text, cache and Hub state regressions restored")


if __name__ == "__main__":
    apply()
