#!/usr/bin/env python3
"""Keep the About-page release line aligned with the actual APK version.

The About card reads AppVersion.CHANGELOG while the version line reads
BuildConfig. Older source generators left CHANGELOG at v0.10.6, which allowed a
new APK to display an obsolete release note. This patch runs last and derives
the visible release title from BuildConfig-backed AppVersion.NAME/CODE.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"

APP_VERSION_BLOCK = '''object AppVersion {
    val NAME: String get() = BuildConfig.VERSION_NAME
    val CODE: Int get() = BuildConfig.VERSION_CODE
    const val GITHUB = "https://github.com/OnlyChallgener/LabProbeApp"
    val CHANGELOG: List<Pair<String, List<String>>>
        get() = listOf(
            "v$NAME build$CODE · 实时连接租约与离线节流" to listOf(
                "APP 实时数字只订阅 Hub WSS 小样本，不再订阅完整 Dashboard",
                "首次进入和 WSS 重连后只读取一次 Hub 内存缓存做状态校准",
                "APP 退到后台或实时链路断开时暂停平滑渲染和高频实时需求"
            )
        )
}'''


def apply() -> None:
    text = MAIN.read_text(encoding="utf-8")
    pattern = re.compile(
        r'object AppVersion \{.*?\n\}\n\nprivate val LabTypography:',
        re.DOTALL,
    )
    replacement = APP_VERSION_BLOCK + "\n\nprivate val LabTypography:"
    next_text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise RuntimeError(f"expected one AppVersion block, replaced {count}")

    required = (
        '"v$NAME build$CODE · 实时连接租约与离线节流"',
        'val CHANGELOG: List<Pair<String, List<String>>>',
    )
    missing = [item for item in required if item not in next_text]
    if missing:
        raise RuntimeError(f"version log verification failed: {missing}")
    if "v0.10.6 build134 · 首页关注与概览显示修复" in next_text:
        raise RuntimeError("obsolete v0.10.6 About-page changelog remains")

    MAIN.write_text(next_text, encoding="utf-8")
    print("About-page version log aligned with BuildConfig")


if __name__ == "__main__":
    apply()
