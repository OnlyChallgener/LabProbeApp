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
        '"v$NAME build$CODE · 原始启动图标细节还原" to listOf(',
        '"v$NAME build$CODE · 图标显示与 Relay 更新稳定性修复" to listOf(',
        "build174 changelog title",
    )
    text = replace_once(
        text,
        '                "前景缩至 0.74 自适应安全区，圆形和圆角方形桌面不再裁切",',
        '                "图标前景调整至 0.82，保留完整细节并提升桌面辨识度",\n'
        '                "Relay 更新优先按实际上报版本判断成功，避免旧失败状态误报",\n'
        '                "区分更新请求超时和已下发后的状态轮询超时",',
        "build174 changelog items",
    )
    MAIN.write_text(text, encoding="utf-8")


def patch_logo() -> None:
    text = LOGO.read_text(encoding="utf-8")
    text = replace_once(
        text,
        'android:scaleX="0.74"',
        'android:scaleX="0.82"',
        "launcher scaleX",
    )
    text = replace_once(
        text,
        'android:scaleY="0.74"',
        'android:scaleY="0.82"',
        "launcher scaleY",
    )
    text = text.replace(
        "The 0.74 scale keeps every ring, node and the three right-side dots",
        "The 0.82 scale keeps the original detail readable while preserving",
        1,
    ).replace(
        "inside the adaptive-icon safe zone on circle and rounded-square masks.",
        "safe margins on circle and rounded-square masks.",
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
        (gradle, 'versionCode = 174'),
        (gradle, 'versionName = "0.10.32"'),
        (main, '图标显示与 Relay 更新稳定性修复'),
        (main, '图标前景调整至 0.82，保留完整细节并提升桌面辨识度'),
        (main, 'Relay 更新优先按实际上报版本判断成功，避免旧失败状态误报'),
        (main, '区分更新请求超时和已下发后的状态轮询超时'),
        (logo, 'android:scaleX="0.82"'),
        (logo, 'android:scaleY="0.82"'),
        (logo, 'M1364 1237.5C1364 778.828'),
        (logo, 'M2700.36 1028.11C2698.99 1014.17'),
        (logo, 'M2715.47 1183.01C2714.11 1169.07'),
        (coordinator, 'var commandAccepted = false'),
        (coordinator, 'commandAccepted = true'),
        (coordinator, 'completedAgentUpdate(info)?.let { return it }'),
        (coordinator, '更新请求超时，尚未确认 Hub 已接收指令'),
        (tests, 'reportedCurrentVersionOverridesStaleFailedTaskState'),
        (tests, 'dispatchTimeoutDoesNotClaimCommandWasAccepted'),
        (tests, 'pollingTimeoutKeepsAcceptedCommandMessage'),
    )
    missing = [needle for source, needle in required if needle not in source]
    if missing:
        raise RuntimeError(f"build174 stability verification failed: {missing}")
    if 'android:scaleX="0.74"' in logo or 'android:scaleY="0.74"' in logo:
        raise RuntimeError("build173 launcher scale is still present")


def main() -> None:
    patch_main()
    patch_logo()
    verify()
    print("build174 launcher visibility and Relay update stability applied")


if __name__ == "__main__":
    main()
