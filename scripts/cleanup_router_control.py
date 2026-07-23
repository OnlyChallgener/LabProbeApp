#!/usr/bin/env python3
"""Normalize generated router-control UI after idempotent integration runs."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"


def main() -> None:
    text = UI.read_text(encoding="utf-8")

    # Keep a single copy of every import.
    normalized = []
    seen_imports = set()
    for line in text.splitlines():
        if line.startswith("import "):
            if line in seen_imports:
                continue
            seen_imports.add(line)
        normalized.append(line)
    text = "\n".join(normalized) + "\n"

    # RouterFeatureRail must contain exactly one compact connection/settings icon.
    start = text.find("fun RouterFeatureRail(")
    end = text.find("\n}\n\nprivate enum class RouterGlyph", start)
    if start >= 0 and end > start:
        section = text[start:end]
        settings_pattern = re.compile(
            r'''\n\s*Spacer\(Modifier\.width\(5\.dp\)\)\n\s*Surface\(\n\s*onClick = onConnection,\n\s*shape = CircleShape,\n\s*color = RouterBlue\.copy\(alpha = \.08f\),\n\s*modifier = Modifier\.size\(30\.dp\)\n\s*\) \{\n\s*Box\(contentAlignment = Alignment\.Center\) \{\n\s*Icon\(Icons\.Rounded\.Settings, "路由器连接", Modifier\.size\(16\.dp\), tint = RouterBlue\)\n\s*}\n\s*}'''
        )
        matches = list(settings_pattern.finditer(section))
        if len(matches) > 1:
            keep = matches[0]
            rebuilt = section[: keep.end()]
            cursor = keep.end()
            for duplicate in matches[1:]:
                rebuilt += section[cursor : duplicate.start()]
                cursor = duplicate.end()
            rebuilt += section[cursor:]
            text = text[:start] + rebuilt + text[end:]

    UI.write_text(text, encoding="utf-8")
    print("Router-control UI normalized.")


if __name__ == "__main__":
    main()
