#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"
LOGO = ROOT / "app/src/main/res/drawable/ic_launcher_logo.xml"
COORDINATOR = ROOT / "app/src/main/kotlin/com/labprobe/app/AgentUpdateCoordinator.kt"
TEST = ROOT / "app/src/test/kotlin/com/labprobe/app/AgentUpdateCoordinatorTest.kt"


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
        '"v$NAME build$CODE · 图标显示与 Relay 更新稳定性修复" to listOf(',
        '"v$NAME build$CODE · 图标留白与 Relay 版本显示修复" to listOf(',
        "build175 changelog title",
    )
    text = replace_once(
        text,
        '                "图标前景调整至 0.82，保留完整细节并提升桌面辨识度",',
        '                "图标前景调整至 0.78，四周保留适度底色且主体不显空",\n'
        '                "已安装版本高于旧更新清单时，最新版本不再倒退显示",',
        "build175 changelog items",
    )
    MAIN.write_text(text, encoding="utf-8")


def patch_logo() -> None:
    text = LOGO.read_text(encoding="utf-8")
    text = replace_once(
        text,
        'android:scaleX="0.82"',
        'android:scaleX="0.78"',
        "launcher scaleX",
    )
    text = replace_once(
        text,
        'android:scaleY="0.82"',
        'android:scaleY="0.78"',
        "launcher scaleY",
    )
    text = text.replace(
        "The 0.82 scale keeps the original detail readable while preserving",
        "The 0.78 scale leaves visible background around every edge without",
        1,
    ).replace(
        "safe margins on circle and rounded-square masks.",
        "making the original network mark look undersized.",
        1,
    )
    LOGO.write_text(text, encoding="utf-8")


def verify() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    main = MAIN.read_text(encoding="utf-8")
    logo = LOGO.read_text(encoding="utf-8")
    coordinator = COORDINATOR.read_text(encoding="utf-8")
    tests = TEST.read_text(encoding="utf-8")

    required = (
        (gradle, 'versionCode = 175'),
        (gradle, 'versionName = "0.10.33"'),
        (main, '图标留白与 Relay 版本显示修复'),
        (main, '图标前景调整至 0.78，四周保留适度底色且主体不显空'),
        (main, '已安装版本高于旧更新清单时，最新版本不再倒退显示'),
        (logo, 'android:scaleX="0.78"'),
        (logo, 'android:scaleY="0.78"'),
        (logo, 'M1364 1237.5C1364 778.828'),
        (logo, 'M2700.36 1028.11C2698.99 1014.17'),
        (logo, 'M2715.47 1183.01C2714.11 1169.07'),
        (coordinator, 'internal fun normalizeAgentVersionInfo'),
        (coordinator, 'latestVersion = info.currentVersion'),
        (coordinator, 'parseStoredAgentInfo(prefs.agentUpdateInfoJson)'),
        (tests, 'newerInstalledVersionDoesNotShowOlderManifestAsLatest'),
    )
    missing = [needle for source, needle in required if needle not in source]
    if missing:
        raise RuntimeError(f"build175 verification failed: {missing}")
    if 'android:scaleX="0.82"' in logo or 'android:scaleY="0.82"' in logo:
        raise RuntimeError("build174 oversized launcher scale is still present")
    if 'android:scaleX="0.74"' in logo or 'android:scaleY="0.74"' in logo:
        raise RuntimeError("build173 undersized launcher scale is still present")


def main() -> None:
    patch_main()
    patch_logo()
    verify()
    print("build175 balanced launcher margin and Relay version display applied")


if __name__ == "__main__":
    main()
