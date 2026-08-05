#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"
RADAR = ROOT / "app/src/main/res/drawable/ic_launcher_radar.xml"
NETWORK = ROOT / "app/src/main/res/drawable/ic_launcher_network.xml"
FOREGROUND = ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one old pattern, found {count}")
    return text.replace(old, new, 1)


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '"v$NAME build$CODE · 标准 Adaptive Icon 分层修复" to listOf(',
        '"v$NAME build$CODE · 启动图标中心比例修复" to listOf(',
        "build177 changelog title",
    )
    text = replace_once(
        text,
        '                "启动图标改为标准 Adaptive Icon 分层，渐变背景、雷达装饰和网络主体独立",\n'
        '                "保留原图形与颜色，四周有可见底色且节点不再贴近桌面遮罩",',
        '                "雷达装饰层缩至 0.69，中心图案四周保留更充足的渐变底色",\n'
        '                "节点与连线层缩至 0.72，保持原图形、颜色和完整路径不变",',
        "build177 changelog items",
    )
    MAIN.write_text(text, encoding="utf-8")


def patch_layers() -> None:
    radar = RADAR.read_text(encoding="utf-8")
    radar = replace_once(radar, 'android:scaleX="0.77"', 'android:scaleX="0.69"', "radar scaleX")
    radar = replace_once(radar, 'android:scaleY="0.77"', 'android:scaleY="0.69"', "radar scaleY")
    RADAR.write_text(radar, encoding="utf-8")

    network = NETWORK.read_text(encoding="utf-8")
    network = replace_once(network, 'android:scaleX="0.80"', 'android:scaleX="0.72"', "network scaleX")
    network = replace_once(network, 'android:scaleY="0.80"', 'android:scaleY="0.72"', "network scaleY")
    NETWORK.write_text(network, encoding="utf-8")


def verify() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    main = MAIN.read_text(encoding="utf-8")
    radar = RADAR.read_text(encoding="utf-8")
    network = NETWORK.read_text(encoding="utf-8")
    foreground = FOREGROUND.read_text(encoding="utf-8")

    code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if code_match is None or int(code_match.group(1)) < 177:
        raise RuntimeError("build177 migration requires versionCode >= 177")

    required = (
        (main, '启动图标中心比例修复'),
        (main, '雷达装饰层缩至 0.69，中心图案四周保留更充足的渐变底色'),
        (main, '节点与连线层缩至 0.72，保持原图形、颜色和完整路径不变'),
        (foreground, '@drawable/ic_launcher_radar'),
        (foreground, '@drawable/ic_launcher_network'),
        (radar, 'android:scaleX="0.69"'),
        (radar, 'android:scaleY="0.69"'),
        (radar, 'M1364 1237.5C1364 778.828'),
        (radar, 'M2700.36 1028.11C2698.99 1014.17'),
        (radar, 'M2715.47 1183.01C2714.11 1169.07'),
        (network, 'android:scaleX="0.72"'),
        (network, 'android:scaleY="0.72"'),
        (network, 'M2117.87 1136.12 2803.19 666.997'),
        (network, 'M2753 685C2753 618.174'),
        (network, 'M2058 1171C2058 1093.68'),
    )
    missing = [needle for source, needle in required if needle not in source]
    if missing:
        raise RuntimeError(f"build177 centered icon verification failed: {missing}")
    if '@drawable/ic_launcher_logo' in foreground:
        raise RuntimeError("single-layer launcher foreground unexpectedly restored")
    if 'android:scaleX="0.77"' in radar or 'android:scaleY="0.77"' in radar:
        raise RuntimeError("build176 radar scale remains")
    if 'android:scaleX="0.80"' in network or 'android:scaleY="0.80"' in network:
        raise RuntimeError("build176 network scale remains")


def main() -> None:
    patch_main()
    patch_layers()
    verify()
    print("build177 centered launcher spacing applied")


if __name__ == "__main__":
    main()
