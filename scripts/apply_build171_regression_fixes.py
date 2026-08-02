#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


def patch_main() -> None:
    path = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '"v$NAME build$CODE · 数据一致性与安全修复" to listOf(',
        '"v$NAME build$CODE · 图标与设备数据回归修复" to listOf(',
        "build171 changelog title",
    )
    text = replace_once(
        text,
        '                "启动图标替换为新版青绿网络节点 Logo，并同步自适应圆形图标",',
        '                "启动图标加入自适应安全边距，顶部和两侧网络节点完整显示",',
        "launcher changelog",
    )
    text = replace_once(
        text,
        '                "关注与离线列表统一按 MAC 使用同一份最新设备记录",',
        '                "离线列表保持独立权威数据，关注列表只引用在线或离线最新记录",',
        "device authority changelog",
    )

    wrong_order = "mergeSharedDeviceState(state.offlineDevices + state.devices, state.onlineDevices)"
    correct_order = "mergeSharedDeviceState(state.devices + state.offlineDevices, state.onlineDevices)"
    if wrong_order in text:
        text = text.replace(wrong_order, correct_order)
    elif correct_order not in text:
        raise RuntimeError("build171 device authority merge call missing")

    wrong_tabs = '''    val list = when (mode) {
        "online" -> shared.filter { it.online }
        "offline" -> shared.filterNot { it.online }
        else -> followed
    }'''
    correct_tabs = '''    val list = when (mode) {
        "online" -> state.onlineDevices
        "offline" -> state.offlineDevices
        else -> followed
    }'''
    if wrong_tabs in text:
        text = text.replace(wrong_tabs, correct_tabs, 1)
    elif correct_tabs not in text:
        raise RuntimeError("build171 independent online/offline tabs missing")

    path.write_text(text, encoding="utf-8")


def patch_device_merge() -> None:
    path = ROOT / "app/src/main/kotlin/com/labprobe/app/WolManagementPanel.kt"
    text = path.read_text(encoding="utf-8")
    old = '    watched.forEach { if (it.mac.isNotBlank()) map[cleanMac(it.mac)] = it }'
    new = '''    watched.forEach { device ->
        if (device.mac.isBlank()) return@forEach
        val key = cleanMac(device.mac)
        val old = map[key]
        map[key] = if (old == null) device else mergePreferFreshDevice(old, device)
    }'''
    text = replace_once(text, old, new, "authoritative duplicate device merge")
    path.write_text(text, encoding="utf-8")


def main() -> None:
    patch_main()
    patch_device_merge()
    print("build171 offline authority and launcher regression fixes applied")


if __name__ == "__main__":
    main()
