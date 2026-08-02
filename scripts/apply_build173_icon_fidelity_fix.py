#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"
LOGO = ROOT / "app/src/main/res/drawable/ic_launcher_logo.xml"


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
        '"v$NAME build$CODE · Relay 更新与启动图标修复" to listOf(',
        '"v$NAME build$CODE · 原始启动图标细节还原" to listOf(',
        "build173 changelog title",
    )
    text = replace_once(
        text,
        '                "启动图标拆分为全幅渐变背景和透明网络前景，清除四周脏边",',
        '                "启动图标按原始 SVG 逐路径还原雷达环、节点、连线和右侧三点",\n'
        '                "前景缩至 0.74 自适应安全区，圆形和圆角方形桌面不再裁切",',
        "build173 icon changelog",
    )
    MAIN.write_text(text, encoding="utf-8")


def verify() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    main = MAIN.read_text(encoding="utf-8")
    logo = LOGO.read_text(encoding="utf-8")
    required = (
        (gradle, 'versionCode = 173'),
        (gradle, 'versionName = "0.10.31"'),
        (main, '原始启动图标细节还原'),
        (main, '启动图标按原始 SVG 逐路径还原雷达环、节点、连线和右侧三点'),
        (main, '前景缩至 0.74 自适应安全区，圆形和圆角方形桌面不再裁切'),
        (logo, 'android:viewportWidth="2045"'),
        (logo, 'android:viewportHeight="2044"'),
        (logo, 'android:scaleX="0.74"'),
        (logo, 'android:scaleY="0.74"'),
        (logo, 'M1364 1237.5C1364 778.828'),
        (logo, 'M2700.36 1028.11C2698.99 1014.17'),
        (logo, 'M2715.47 1183.01C2714.11 1169.07'),
    )
    missing = [needle for source, needle in required if needle not in source]
    if missing:
        raise RuntimeError(f"build173 original icon verification failed: {missing}")
    if 'android:scaleX="0.84"' in logo or 'android:strokeWidth="5.2"' in logo:
        raise RuntimeError("simplified build172 launcher geometry is still present")


def main() -> None:
    patch_main()
    verify()
    print("build173 original SVG launcher details and adaptive safe-zone source state applied")


if __name__ == "__main__":
    main()
