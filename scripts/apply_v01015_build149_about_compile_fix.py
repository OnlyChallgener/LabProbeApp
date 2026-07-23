#!/usr/bin/env python3
"""Repair only the Settings/About Text call after legacy regex generators.

The anchor deliberately requires `极客网探` followed immediately by a newline
and `版本 ... build ...`. It cannot match the separate version dialog title
`极客网探 v...` or consume unrelated page code.
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
        r'\s*Text\(\s*"极客网探(?:\\n|\n)版本\s+\$\{AppVersion\.NAME\}\s+build\s+\$\{AppVersion\.CODE\}(?:\\n|\n).*?lineHeight\s*=\s*19\.sp\s*\)',
        re.DOTALL,
    )
    text, count = pattern.subn(lambda _match: ABOUT_BLOCK, text, count=1)
    if count != 1:
        valid = (
            '版本 ${AppVersion.NAME} build ${AppVersion.CODE}' in text
            and '${AppVersion.CHANGELOG.firstOrNull()?.first.orEmpty()}' in text
            and '""".trimIndent()' in text
        )
        if not valid:
            raise RuntimeError(f"expected one Settings/About Text call, replaced {count}")

    forbidden = (
        "v0.10.6：首页关注列表与今日概览底部滑动已修复。",
        "v0.10.6 build134 · 首页关注与概览显示修复",
    )
    if any(item in text for item in forbidden):
        raise RuntimeError("obsolete v0.10.6 Settings/About text remains")
    if 'Text("极客网探 v${AppVersion.NAME}"' not in text:
        raise RuntimeError("version dialog title was unexpectedly modified")
    if 'fun SettingsScreen(' not in text or 'fun HomeScreen(' not in text:
        raise RuntimeError("unrelated page code was unexpectedly modified")

    MAIN.write_text(text, encoding="utf-8")
    print("build149 Settings/About Kotlin string repaired without touching other pages")


if __name__ == "__main__":
    apply()
