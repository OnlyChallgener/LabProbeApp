#!/usr/bin/env python3
"""Final build154 realtime stability patch.

- Connect Hub-native WSS before optional HTTP memory calibration.
- Keep realtime display updates on a one-second cadence.
- Remove misleading Agent wording from router native fast status.
- Keep the actual version name and build code visible in the version dialog.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"

OLD_CHANGELOG = '''            "v$NAME build$CODE · Hub 原生 WSS 实时链路" to listOf(
                "APP 使用现有 APP Token 直接连接 Hub 原生 WSS，不再需要 MQTT 地址或账号密码",
                "路由 fast 与终端增量仅经 Hub 内存快照推送，HTTP 只用于首次与重连校准",
                "APP 退到后台或 WSS 断开时暂停平滑渲染和终端高频采样需求"
            )'''

NEW_CHANGELOG = '''            "v$NAME build$CODE · 原生 fast 秒级稳定刷新" to listOf(
                "路由实时状态直接使用路由器原生 WSS type=fast，不再等待 Agent 更新",
                "APP 与 Hub 使用长连接、协议 Ping 和帧看门狗保活，异常最多 5 秒重连",
                "所有实时数字每秒最多更新一次，不再出现一秒内连续闪动",
                "HTTP 只读取 Hub 内存做首次校准，不参与自动实时兜底"
            )'''

OLD_START = '''    suspend fun startRealtime() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        calibrateRealtimeCache()
        startRealtimeRendering()
        realtimeClient.start(prefs.hub, prefs.token)
    }'''

NEW_START = '''    suspend fun startRealtime() {
        if (prefs.hub.isBlank() || prefs.token.isBlank()) return
        startRealtimeRendering()
        realtimeClient.start(prefs.hub, prefs.token)
        // WSS starts immediately. HTTP only reads the current Hub memory frame
        // in the background and can never delay or replace realtime delivery.
        stateScope.launch { calibrateRealtimeCache() }
    }'''

OLD_VERSION_TITLE = 'title = { Text("极客网探 v${AppVersion.NAME}", fontWeight = FontWeight.Black) },'
NEW_VERSION_TITLE = 'title = { Text("极客网探 · 版本 ${AppVersion.NAME} build ${AppVersion.CODE}", fontWeight = FontWeight.Black) },'


def apply() -> None:
    main = MAIN.read_text(encoding="utf-8")
    if OLD_CHANGELOG in main:
        main = main.replace(OLD_CHANGELOG, NEW_CHANGELOG, 1)
    elif NEW_CHANGELOG not in main:
        raise RuntimeError("missing build153 changelog block")

    if OLD_START in main:
        main = main.replace(OLD_START, NEW_START, 1)
    elif NEW_START not in main:
        raise RuntimeError("missing realtime start method")

    if OLD_VERSION_TITLE in main:
        main = main.replace(OLD_VERSION_TITLE, NEW_VERSION_TITLE, 1)
    elif NEW_VERSION_TITLE not in main:
        raise RuntimeError("missing version dialog title")

    required_main = (
        '"v$NAME build$CODE · 原生 fast 秒级稳定刷新"',
        'realtimeClient.start(prefs.hub, prefs.token)',
        'stateScope.launch { calibrateRealtimeCache() }',
        'delay(RealtimeDisplaySmoother.FRAME_INTERVAL_MS)',
        '版本 ${AppVersion.NAME} build ${AppVersion.CODE}',
    )
    missing = [value for value in required_main if value not in main]
    if missing:
        raise RuntimeError(f"build154 MainActivity verification failed: {missing}")
    MAIN.write_text(main, encoding="utf-8")

    status = STATUS.read_text(encoding="utf-8")
    status = status.replace(
        'Text("实时数据稍旧，等待 Agent 更新", fontSize = 8.6.sp, color = LabV2.Amber, fontWeight = FontWeight.SemiBold)',
        'Text("实时链路正在自动重连", fontSize = 8.6.sp, color = LabV2.Amber, fontWeight = FontWeight.SemiBold)',
    )
    if "等待 Agent 更新" in status:
        raise RuntimeError("misleading Agent realtime text remains")
    if "实时链路正在自动重连" not in status:
        raise RuntimeError("missing WSS reconnect status text")
    STATUS.write_text(status, encoding="utf-8")
    print("build154 one-second native fast stability applied")


if __name__ == "__main__":
    apply()
