#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
ROUTER_STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"
ROUTER_API = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlApi.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing v0.10.14 patch pattern: {label}")
    return text.replace(old, new, 1)


def patch_main() -> None:
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
        "router dashboard cache preference",
    )

    text = replace_once(
        text,
        '    var routerDashboard by mutableStateOf<JSONObject?>(null)\n',
        '    var routerDashboard by mutableStateOf<JSONObject?>(\n'
        '        prefs.cacheRouterDashboard.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }\n'
        '    )\n',
        "restore cached router dashboard",
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
            .onFailure {
                if (previous != null) {
                    routerDashboard = previous
                    routerDashboardError = ""
                } else {
                    routerDashboardError = it.message ?: "Hub 暂时无法获取路由器状态"
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
    text = replace_once(text, old_methods, new_methods, "stable router dashboard refresh")

    text = replace_once(
        text,
        '                HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", LabV2.Purple, Modifier.weight(1f)) { onNavigate("tool_ssh") }\n',
        '                HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", Color(0xFF94A3B8), Modifier.weight(1f)) { onNavigate("tool_ssh") }\n',
        "light gray SSH shortcut",
    )

    old_changelog = '''        "v0.10.13 build143 · 路由诊断与首页联动" to listOf(
            "路由器状态页改为约2秒读取Hub内存中的WSS快照，实时速率和连接数更快更新",
            "首页快捷卡调整为SSH与DDNS，路由设置总入口继续保留",
            "新增路由器原生RFC3489/RFC5780 NAT诊断与最近10次历史",
            "新增ReyeeOS Beta在线检查，暂不猜测安装参数"
        )'''
    new_changelog = '''        "v0.10.14 build144 · 实时刷新与页面稳定性修复" to listOf(
            "路由状态改为约1秒读取Hub快照，快数据不再被终端列表查询阻塞",
            "刷新时保留并合并上一份完整数据，避免页面内容短暂变白",
            "路由连接与数据同步状态全部改为中文，不再出现英文提示",
            "首页SSH快捷小卡改为浅灰色，手动刷新改为非阻塞"
        )'''
    text = replace_once(text, old_changelog, new_changelog, "v0.10.14 changelog")

    MAIN.write_text(text, encoding="utf-8")


def patch_router_status() -> None:
    text = ROUTER_STATUS.read_text(encoding="utf-8")
    if '            delay(1_000L)\n' not in text:
        if '            delay(2_000L)\n' in text:
            text = text.replace('            delay(2_000L)\n', '            delay(1_000L)\n', 1)
        elif '            delay(if (state.mqttConnected) 15_000L else 20_000L)\n' in text:
            text = text.replace(
                '            delay(if (state.mqttConnected) 15_000L else 20_000L)\n',
                '            delay(1_000L)\n',
                1,
            )
        else:
            raise RuntimeError("missing v0.10.14 patch pattern: router status poll interval")

    old_refresh = '''    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching { state.requestRouterDashboardRefresh() }
                .onFailure { state.refreshRouterDashboard(silent = false) }
            runCatching { state.requestRouterCredentialsRefresh() }
            refreshing = false
        }
    }'''
    new_refresh = '''    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching { state.requestRouterDashboardRefresh() }
                .onFailure { state.refreshRouterDashboard(silent = false) }
            refreshing = false
            launch { runCatching { state.requestRouterCredentialsRefresh() } }
        }
    }'''
    text = replace_once(text, old_refresh, new_refresh, "non-blocking router status refresh")
    text = text.replace("实时数据稍旧，等待 Agent 更新", "实时数据稍旧，等待 Hub 实时数据")
    text = text.replace("实时数据稍旧，等待 Hub WebSocket 更新", "实时数据稍旧，等待 Hub 实时数据")
    ROUTER_STATUS.write_text(text, encoding="utf-8")


def patch_router_api() -> None:
    text = ROUTER_API.read_text(encoding="utf-8")
    translations = {
        "Waiting for Hub status": "等待 Hub 状态",
        "Hub is online, but router data is unavailable": "Hub 在线，路由器数据正在同步",
        "Hub router data is available": "Hub 已连接，路由器数据可用",
        "Hub could not log in to the router": "Hub 登录路由器失败",
        "Hub router request failed": "Hub 路由请求失败",
    }
    for old, new in translations.items():
        text = text.replace(old, new)
    ROUTER_API.write_text(text, encoding="utf-8")


def apply() -> None:
    patch_main()
    patch_router_status()
    patch_router_api()
    print("v0.10.14 refresh stability fixes applied")


if __name__ == "__main__":
    apply()
