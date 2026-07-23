#!/usr/bin/env python3
"""Wire router dashboard MQTT delivery into AppState with a 1s HTTP fallback.

The Hub already publishes its router /ws-derived memory snapshot to the retained
MQTT dashboard topic. HubMqttClient already subscribes to that topic, but the
AppState constructor did not provide the callback and RouterStatus therefore
fell back to a 15/20 second HTTP timer. This final generated-source patch is
narrow, idempotent and deliberately runs after all older UI generators.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
ROUTER_STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing realtime delivery fix pattern: {label}")
    return text.replace(old, new, 1)


def patch_app_state() -> None:
    text = MAIN.read_text(encoding="utf-8")

    text = replace_once(
        text,
        "    @Volatile private var foregroundActive = true\n    private val realtimeClient = HubMqttClient(\n",
        "    @Volatile private var foregroundActive = true\n"
        "    @Volatile private var routerDashboardMqttAt = 0L\n"
        "    private val realtimeClient = HubMqttClient(\n",
        "dashboard MQTT freshness timestamp",
    )

    text = replace_once(
        text,
        "        },\n        onRevision = { revision -> mqttRevisionSignals.trySend(revision) }\n    )\n",
        "        },\n"
        "        onRevision = { revision -> mqttRevisionSignals.trySend(revision) },\n"
        "        onRouterDashboard = { raw ->\n"
        "            stateScope.launch {\n"
        "                val latest = runCatching {\n"
        "                    val root = JSONObject(raw)\n"
        "                    root.optJSONObject(\"dashboard\")\n"
        "                        ?: root.optJSONObject(\"data\")\n"
        "                        ?: root\n"
        "                }.getOrNull() ?: return@launch\n"
        "                val merged = mergeRouterDashboardSnapshot(routerDashboard, latest)\n"
        "                routerDashboard = merged\n"
        "                prefs.cacheRouterDashboard = merged.toString()\n"
        "                routerDashboardMqttAt = SystemClock.elapsedRealtime()\n"
        "                routerDashboardError = \"\"\n"
        "            }\n"
        "        }\n"
        "    )\n",
        "router dashboard MQTT callback",
    )

    marker = "    suspend fun refreshRouterDashboard(silent: Boolean = true) {\n"
    freshness = (
        "    fun routerDashboardMqttFresh(maxAgeMs: Long = 2_500L): Boolean {\n"
        "        val receivedAt = routerDashboardMqttAt\n"
        "        return mqttConnected && receivedAt > 0L &&\n"
        "            SystemClock.elapsedRealtime() - receivedAt <= maxAgeMs\n"
        "    }\n\n"
    )
    if freshness not in text:
        if marker not in text:
            raise RuntimeError("missing realtime delivery fix pattern: dashboard refresh method")
        text = text.replace(marker, freshness + marker, 1)

    text = text.replace(
        '"v0.10.15 build145 · 路由页面稳定与诊断交互修复"',
        '"v0.10.15 build146 · 路由实时推送与刷新修复"',
        1,
    )

    required = (
        "onRouterDashboard = { raw ->",
        "routerDashboardMqttAt = SystemClock.elapsedRealtime()",
        "fun routerDashboardMqttFresh(maxAgeMs: Long = 2_500L)",
    )
    missing = [item for item in required if item not in text]
    if missing:
        raise RuntimeError(f"realtime delivery AppState verification failed: {missing}")

    MAIN.write_text(text, encoding="utf-8")


def patch_router_status() -> None:
    text = ROUTER_STATUS.read_text(encoding="utf-8")

    old_loop = '''    LaunchedEffect(state.mqttConnected) {
        while (isActive) {
            delay(if (state.mqttConnected) 15_000L else 20_000L)
            state.refreshRouterDashboard(silent = true)
        }
    }'''
    legacy_fast_loop = '''    LaunchedEffect(state.mqttConnected) {
        while (isActive) {
            delay(1_000L)
            state.refreshRouterDashboard(silent = true)
        }
    }'''
    new_loop = '''    LaunchedEffect(state.mqttConnected) {
        while (isActive) {
            delay(1_000L)
            if (!state.routerDashboardMqttFresh()) {
                state.refreshRouterDashboard(silent = true)
            }
        }
    }'''
    if new_loop not in text:
        if old_loop in text:
            text = text.replace(old_loop, new_loop, 1)
        elif legacy_fast_loop in text:
            text = text.replace(legacy_fast_loop, new_loop, 1)
        else:
            raise RuntimeError("missing realtime delivery fix pattern: router status refresh loop")

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
    text = replace_once(text, old_refresh, new_refresh, "non-blocking credential refresh")

    forbidden = (
        "delay(if (state.mqttConnected) 15_000L else 20_000L)",
        "runCatching { state.requestRouterCredentialsRefresh() }\n            refreshing = false",
    )
    present = [item for item in forbidden if item in text]
    if present:
        raise RuntimeError(f"stale realtime delivery patterns remain: {present}")
    required = (
        "delay(1_000L)",
        "if (!state.routerDashboardMqttFresh())",
        "launch { runCatching { state.requestRouterCredentialsRefresh() } }",
    )
    missing = [item for item in required if item not in text]
    if missing:
        raise RuntimeError(f"router status realtime verification failed: {missing}")

    ROUTER_STATUS.write_text(text, encoding="utf-8")


def apply() -> None:
    patch_app_state()
    patch_router_status()
    print("router dashboard MQTT delivery and 1s HTTP fallback applied")


if __name__ == "__main__":
    apply()
