#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"
FOREGROUND = ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
RADAR = ROOT / "app/src/main/res/drawable/ic_launcher_radar.xml"
NETWORK = ROOT / "app/src/main/res/drawable/ic_launcher_network.xml"
BACKGROUND = ROOT / "app/src/main/res/drawable/ic_launcher_background.xml"


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
        '"v$NAME build$CODE · 图标留白与 Relay 版本显示修复" to listOf(',
        '"v$NAME build$CODE · 标准 Adaptive Icon 分层修复" to listOf(',
        "build176 changelog title",
    )
    text = replace_once(
        text,
        '                "图标前景调整至 0.78，四周保留适度底色且主体不显空",',
        '                "启动图标改为标准 Adaptive Icon 分层，渐变背景、雷达装饰和网络主体独立",\n'
        '                "保留原图形与颜色，四周有可见底色且节点不再贴近桌面遮罩",',
        "build176 changelog items",
    )
    MAIN.write_text(text, encoding="utf-8")


def verify() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    main = MAIN.read_text(encoding="utf-8")
    foreground = FOREGROUND.read_text(encoding="utf-8")
    radar = RADAR.read_text(encoding="utf-8")
    network = NETWORK.read_text(encoding="utf-8")
    background = BACKGROUND.read_text(encoding="utf-8")

    code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if code_match is None or int(code_match.group(1)) < 176:
        raise RuntimeError("build176 migration requires versionCode >= 176")
    build_code = int(code_match.group(1))

    required = (
        (main, '标准 Adaptive Icon 分层修复'),
        (main, '启动图标改为标准 Adaptive Icon 分层，渐变背景、雷达装饰和网络主体独立'),
        (main, '保留原图形与颜色，四周有可见底色且节点不再贴近桌面遮罩'),
        (foreground, '@drawable/ic_launcher_radar'),
        (foreground, '@drawable/ic_launcher_network'),
        (radar, 'M1364 1237.5C1364 778.828'),
        (radar, 'M2700.36 1028.11C2698.99 1014.17'),
        (radar, 'M2715.47 1183.01C2714.11 1169.07'),
        (network, 'M2117.87 1136.12 2803.19 666.997'),
        (network, 'M2753 685C2753 618.174'),
        (network, 'M2058 1171C2058 1093.68'),
        (background, 'M0,0H108V108H0Z'),
        (background, '#FF087CAD'),
        (background, '#FF65DDD3'),
    )
    if build_code == 176:
        required += (
            (radar, 'android:scaleX="0.77"'),
            (radar, 'android:scaleY="0.77"'),
            (network, 'android:scaleX="0.80"'),
            (network, 'android:scaleY="0.80"'),
        )

    missing = [needle for source, needle in required if needle not in source]
    if missing:
        raise RuntimeError(f"build176 adaptive icon verification failed: {missing}")
    if '@drawable/ic_launcher_logo' in foreground:
        raise RuntimeError("legacy single-layer launcher foreground is still referenced")
    if 'android:scaleX="0.74"' in foreground or 'android:scaleX="0.78"' in foreground or 'android:scaleX="0.82"' in foreground:
        raise RuntimeError("foreground must compose independent layers without a global scale")


def main() -> None:
    patch_main()
    verify()
    print("build176 standard adaptive icon layers applied")


if __name__ == "__main__":
    main()
