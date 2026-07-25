#!/usr/bin/env python3
"""Remove the last ambiguous router-control wording after generated patches run."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt"


def apply() -> None:
    text = SETTINGS.read_text(encoding="utf-8")
    replacements = {
        'status.connected && status.state == "ready" -> "路由器已连接，实时数据正常"':
            'status.connected && status.state == "ready" -> "路由控制链路正常"',
        'status.state == "syncing" -> "路由器已登录，正在等待实时数据"':
            'status.state == "syncing" -> "路由器会话已建立，正在等待控制数据"',
        'status.errorCode == "HUB_NO_ROUTER_DATA" -> "Hub 在线，但尚未获取到路由器数据"':
            'status.errorCode == "HUB_NO_ROUTER_DATA" -> "Hub 已连接，暂未取得路由控制数据"',
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    if "路由器已连接，实时数据正常" in text:
        raise RuntimeError("ambiguous router realtime wording still exists")
    SETTINGS.write_text(text, encoding="utf-8")
    print("router control/realtime wording finalized")


if __name__ == "__main__":
    apply()
