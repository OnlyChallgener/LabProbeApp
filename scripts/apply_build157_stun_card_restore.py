#!/usr/bin/env python3
"""Keep the original home VPN/STUN address card visible and cache last rows."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build157 STUN card pattern: {label}")
    return text.replace(old, new, 1)


def apply() -> None:
    text = MAIN.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '    var cacheRouterDashboard: String get() = sp.getString("cache_router_dashboard_v1", "") ?: ""\n'
        '        set(v) = sp.edit().putString("cache_router_dashboard_v1", v).apply()\n'
        '    var cacheDevices: String get() = sp.getString("cache_devices", "") ?: ""\n',
        '    var cacheRouterDashboard: String get() = sp.getString("cache_router_dashboard_v1", "") ?: ""\n'
        '        set(v) = sp.edit().putString("cache_router_dashboard_v1", v).apply()\n'
        '    var cacheVpnRowsJson: String get() = sp.getString("cache_home_vpn_rows_v1", "[]") ?: "[]"\n'
        '        set(v) = sp.edit().putString("cache_home_vpn_rows_v1", v).apply()\n'
        '    var cacheDevices: String get() = sp.getString("cache_devices", "") ?: ""\n',
        "VPN/STUN cache preference",
    )

    old_rows = '''    val vpnRows = remember(state.status, nasV6, state.events) {
        buildVpnRowsForHome(data, nasV6, state.events)
    }
    val onlineCount = state.onlineDevices.size'''
    new_rows = '''    val liveVpnRows = remember(state.status, nasV6, state.events) {
        buildVpnRowsForHome(data, nasV6, state.events)
    }
    var cachedVpnRows by remember { mutableStateOf(decodeHomeVpnRows(prefs.cacheVpnRowsJson)) }
    LaunchedEffect(liveVpnRows) {
        if (liveVpnRows.isNotEmpty()) {
            cachedVpnRows = liveVpnRows
            prefs.cacheVpnRowsJson = encodeHomeVpnRows(liveVpnRows)
        }
    }
    val vpnRows = if (liveVpnRows.isNotEmpty()) liveVpnRows else cachedVpnRows
    val onlineCount = state.onlineDevices.size'''
    text = replace_once(text, old_rows, new_rows, "home VPN/STUN rows with last-value cache")

    text = replace_once(
        text,
        '''                    "vpn" -> if (vpnRows.isNotEmpty()) HealthVpnCard(
                        rows = vpnRows,''',
        '''                    "vpn" -> HealthVpnCard(
                        rows = vpnRows,''',
        "always-visible VPN/STUN home card",
    )

    old_body = '''        rows.forEachIndexed { idx, row ->
            HealthDataRowDisplay(row.first, row.second, maskAddressForUi(row.second, privacyMode), Color(0xFF0F172A))
            if (idx != rows.lastIndex) Spacer(Modifier.height(9.dp))
        }'''
    new_body = '''        if (rows.isEmpty()) {
            Text(
                "正在等待 STUN 地址同步，获取后会保留上次有效地址。",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = LabV2.InkMuted
            )
        } else {
            rows.forEachIndexed { idx, row ->
                HealthDataRowDisplay(row.first, row.second, maskAddressForUi(row.second, privacyMode), Color(0xFF0F172A))
                if (idx != rows.lastIndex) Spacer(Modifier.height(9.dp))
            }
        }'''
    text = replace_once(text, old_body, new_body, "empty-state text inside VPN/STUN card")

    helpers = '''
fun encodeHomeVpnRows(rows: List<Pair<String, String>>): String = JSONArray().apply {
    rows.forEach { (label, address) ->
        if (label.isNotBlank() && address.isNotBlank()) {
            put(JSONObject().put("label", label).put("address", address))
        }
    }
}.toString()

fun decodeHomeVpnRows(raw: String): List<Pair<String, String>> = runCatching {
    val array = JSONArray(raw.ifBlank { "[]" })
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val label = cleanApiText(item.optString("label"))
        val address = cleanApiText(item.optString("address"))
        if (label.isBlank() || address.isBlank()) null else label to address
    }.distinctBy { it.first.lowercase(Locale.getDefault()) }
}.getOrDefault(emptyList())

'''
    if "fun encodeHomeVpnRows" not in text:
        anchor = 'fun networkScore(hubOk: Boolean, exitOk: Boolean, vpnOk: Boolean, onlineCount: Int, events: List<EventItem>): Int {'
        index = text.find(anchor)
        if index < 0:
            raise RuntimeError("missing build157 STUN card pattern: helper insertion anchor")
        text = text[:index] + helpers + text[index:]

    required = (
        "cacheVpnRowsJson",
        "val liveVpnRows = remember",
        "decodeHomeVpnRows(prefs.cacheVpnRowsJson)",
        "prefs.cacheVpnRowsJson = encodeHomeVpnRows(liveVpnRows)",
        '"router" -> RouterSettingsHomeCard { onNavigate("router_settings") }',
        '"vpn" -> HealthVpnCard(',
        'onClick = { onNavigate("tool_router_ddns") }',
        '"score,mini,router,exit,vpn,devices,today"',
        'listOf("score", "mini", "router", "exit", "vpn", "devices", "today")',
        "正在等待 STUN 地址同步，获取后会保留上次有效地址。",
        "fun encodeHomeVpnRows",
        "fun decodeHomeVpnRows",
    )
    missing = [needle for needle in required if needle not in text]
    if missing:
        raise RuntimeError(f"build157 STUN card verification failed: {missing}")
    for forbidden in (
        '"vpn" -> if (vpnRows.isNotEmpty())',
        '"vpn" -> RouterSettingsHomeCard',
    ):
        if forbidden in text:
            raise RuntimeError(f"build157 home card regression remains: {forbidden}")

    MAIN.write_text(text, encoding="utf-8")
    print("build157 separate router settings and cached VPN/STUN address cards verified")


if __name__ == "__main__":
    apply()
