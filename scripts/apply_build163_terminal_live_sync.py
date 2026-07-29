#!/usr/bin/env python3
"""Build163: consume authoritative five-second terminal snapshots without page reloads."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
MAIN = SRC / "MainActivity.kt"
WSS = SRC / "HubMqttClient.kt"
GRADLE = ROOT / "app/build.gradle.kts"
VERIFIER = ROOT / "scripts/verify_build154_sources.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build163 anchor: {label}")
    return text.replace(old, new, 1)


def patch_wss(text: str) -> str:
    text = replace_once(
        text,
        "    private val onDevicesRealtime: (String) -> Unit = {},\n    private val onTaskUpdate: (String) -> Unit = {},",
        "    private val onDevicesRealtime: (String) -> Unit = {},\n    private val onDevicesSnapshot: (String) -> Unit = {},\n    private val onTaskUpdate: (String) -> Unit = {},",
        "devices snapshot callback",
    )
    text = replace_once(
        text,
        '                        "devices" -> if (data != null) onDevicesRealtime(data.toString())\n                        "task" -> if (data != null) onTaskUpdate(data.toString())',
        '                        "devices" -> if (data != null) onDevicesRealtime(data.toString())\n                        "devices_snapshot" -> if (data != null) onDevicesSnapshot(data.toString())\n                        "task" -> if (data != null) onTaskUpdate(data.toString())',
        "devices_snapshot dispatch",
    )
    return text


def patch_main(text: str) -> str:
    text = replace_once(
        text,
        '''        onDevicesRealtime = { raw ->
            stateScope.launch {
                if (!foregroundActive) return@launch
                runCatching { JSONObject(raw) }.getOrNull()?.let { realtimeSmoother.acceptDevices(it) }
            }
        },
        onTaskUpdate = { raw -> RouterTaskRepositoryRegistry.get(prefs).acceptRealtime(raw) },''',
        '''        onDevicesRealtime = { raw ->
            stateScope.launch {
                if (!foregroundActive) return@launch
                runCatching { JSONObject(raw) }.getOrNull()?.let { realtimeSmoother.acceptDevices(it) }
            }
        },
        onDevicesSnapshot = { raw ->
            stateScope.launch {
                if (!foregroundActive) return@launch
                acceptDevicesSnapshot(raw)
            }
        },
        onTaskUpdate = { raw -> RouterTaskRepositoryRegistry.get(prefs).acceptRealtime(raw) },''',
        "AppState full snapshot callback",
    )
    text = replace_once(
        text,
        "    var lastRouterRealtimeAt by mutableLongStateOf(0L)\n",
        "    var lastRouterRealtimeAt by mutableLongStateOf(0L)\n    private var lastDevicesSnapshotEpoch = 0L\n",
        "snapshot epoch guard",
    )
    method = '''    private fun acceptDevicesSnapshot(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (!root.optBoolean("accepted", true) || !root.optBoolean("fullSnapshot", false)) return
        val epoch = root.optLong("sampleEpochMs", 0L)
        if (epoch > 0L && epoch <= lastDevicesSnapshotEpoch) return
        val values = root.optJSONArray("devices") ?: return
        val fresh = applyDeviceOverrides(parseDeviceArray(values.toString()), deviceOverrides)
        val confirmedEmpty = root.optBoolean("confirmedEmpty", false)
        if (fresh.isEmpty() && onlineDevices.isNotEmpty() && !confirmedEmpty) return
        if (epoch > 0L) lastDevicesSnapshotEpoch = epoch

        // The full snapshot owns terminal identity, online time and traffic. The
        // compact devices frame only smooths instantaneous speed between snapshots.
        realtimeSmoother.acceptDevices(root)
        val previousOnline = onlineDevices
        val freshByMac = fresh.associateBy { cleanMac(it.mac) }
        val disappeared = previousOnline
            .filterNot { old -> freshByMac.containsKey(cleanMac(old.mac)) }
            .map { old ->
                old.copy(
                    online = false,
                    offlineAt = old.offlineAt.ifBlank { offlineNow() },
                    lastSeenAt = old.lastSeenAt.ifBlank { offlineNow() },
                )
            }
        val updatedWatched = devices.map { current ->
            val currentMac = cleanMac(current.mac)
            val currentFresh = freshByMac[currentMac] ?: return@map current
            currentFresh.copy(
                remark = current.remark.ifBlank { currentFresh.remark },
                manualType = current.manualType.ifBlank { currentFresh.manualType },
                wolEnabledOverride = current.wolEnabledOverride ?: currentFresh.wolEnabledOverride,
                followedOverride = current.followedOverride ?: currentFresh.followedOverride,
            )
        }

        if (fresh != onlineDevices) onlineDevices = fresh
        if (updatedWatched != devices) devices = updatedWatched
        refreshOfflineDevices(offlineDevices + disappeared, fresh)
        persistCachesAsync()
    }

'''
    anchor = "    private suspend fun calibrateRealtimeCache() {\n"
    if method not in text:
        if anchor not in text:
            raise RuntimeError("missing build163 terminal snapshot method anchor")
        text = text.replace(anchor, method + anchor, 1)
    return text


def patch_version() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    gradle = gradle.replace("versionCode = 162", "versionCode = 163")
    gradle = gradle.replace('versionName = "0.10.20"', 'versionName = "0.10.21"')
    GRADLE.write_text(gradle, encoding="utf-8")

    main = MAIN.read_text(encoding="utf-8")
    main = main.replace(
        '"v$NAME build$CODE · DDNS 页面点击闪退修复"',
        '"v$NAME build$CODE · 终端列表五秒实时同步"',
    )
    MAIN.write_text(main, encoding="utf-8")


def patch_verifier() -> None:
    text = VERIFIER.read_text(encoding="utf-8")
    text = text.replace("APP v0.10.20 build162", "APP v0.10.21 build163")
    text = text.replace(
        "'versionCode = 162', 'versionName = \"0.10.20\"'",
        "'versionCode = 163', 'versionName = \"0.10.21\"'",
    )
    text = text.replace("'DDNS 页面点击闪退修复',", "'终端列表五秒实时同步',")
    marker = "    forbid(ROUTER_CONTROL, 'PremiumCard(accent,Modifier.clickable(onClick=onEdit))')\n"
    checks = '''    require(
        HUB_MQTT,
        'private val onDevicesSnapshot: (String) -> Unit = {}',
        '"devices_snapshot" -> if (data != null) onDevicesSnapshot(data.toString())',
    )
    require(
        MAIN,
        'private fun acceptDevicesSnapshot(raw: String)',
        'lastDevicesSnapshotEpoch',
        'root.optBoolean("confirmedEmpty", false)',
        'persistCachesAsync()',
    )
'''
    if checks not in text:
        if marker not in text:
            raise RuntimeError("missing build163 verifier insertion point")
        text = text.replace(marker, marker + checks, 1)
    text = text.replace(
        "print('build162 DDNS editor isolation and field compatibility verified')",
        "print('build163 five-second full terminal snapshot merge verified')",
    )
    VERIFIER.write_text(text, encoding="utf-8")


def verify() -> None:
    combined = MAIN.read_text(encoding="utf-8") + "\n" + WSS.read_text(encoding="utf-8") + "\n" + GRADLE.read_text(encoding="utf-8")
    required = (
        "private val onDevicesSnapshot: (String) -> Unit = {}",
        '"devices_snapshot" -> if (data != null) onDevicesSnapshot(data.toString())',
        "private fun acceptDevicesSnapshot(raw: String)",
        "lastDevicesSnapshotEpoch",
        'root.optBoolean("confirmedEmpty", false)',
        "versionCode = 163",
        'versionName = "0.10.21"',
        "终端列表五秒实时同步",
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build163 terminal live sync verification failed: {missing}")


def apply() -> None:
    WSS.write_text(patch_wss(WSS.read_text(encoding="utf-8")), encoding="utf-8")
    MAIN.write_text(patch_main(MAIN.read_text(encoding="utf-8")), encoding="utf-8")
    patch_version()
    patch_verifier()
    verify()
    print("build163 authoritative five-second terminal snapshots merged without page reload")


if __name__ == "__main__":
    apply()
