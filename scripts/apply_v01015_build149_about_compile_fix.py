#!/usr/bin/env python3
"""Repair the final Settings/About Text call after legacy regex generators.

Use a Kotlin triple-quoted string so Python regular-expression replacement can
never turn escaped newlines into an invalid quoted Kotlin string.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"

ABOUT_BLOCK = '''
        Text(
            """
            极客网探
            版本 ${AppVersion.NAME} build ${AppVersion.CODE}
            ${AppVersion.CHANGELOG.firstOrNull()?.first.orEmpty()}
            """.trimIndent(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .70f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.5.sp,
            lineHeight = 19.sp
        )'''


def apply() -> None:
    text = MAIN.read_text(encoding="utf-8")
    pattern = re.compile(
        r'\s*Text\(\s*"极客网探.*?lineHeight\s*=\s*19\.sp\s*\)',
        re.DOTALL,
    )
    text, count = pattern.subn(lambda _match: ABOUT_BLOCK, text, count=1)
    if count != 1:
        # Idempotent path: a previous run already emitted the valid block.
        if '""".trimIndent()' not in text or '${AppVersion.CHANGELOG.firstOrNull()?.first.orEmpty()}' not in text:
            raise RuntimeError(f"expected one Settings/About Text call, replaced {count}")

    forbidden = (
        "v0.10.6：首页关注列表与今日概览底部滑动已修复。",
        "v0.10.6 build134 · 首页关注与概览显示修复",
    )
    if any(item in text for item in forbidden):
        raise RuntimeError("obsolete v0.10.6 Settings/About text remains")
    if '版本 ${AppVersion.NAME} build ${AppVersion.CODE}' not in text:
        raise RuntimeError("dynamic Settings/About version line missing")

    MAIN.write_text(text, encoding="utf-8")
    print("build149 Settings/About Kotlin string repaired")


if __name__ == "__main__":
    apply()
