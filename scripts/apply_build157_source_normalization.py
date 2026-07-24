#!/usr/bin/env python3
"""Normalize harmless trailing whitespace before exact generated-source patches."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILES = (
    ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt",
    ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlApi.kt",
    ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt",
    ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt",
    ROOT / "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt",
    ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt",
)


def apply() -> None:
    for path in FILES:
        text = path.read_text(encoding="utf-8")
        had_final_newline = text.endswith("\n")
        normalized = "\n".join(line.rstrip() for line in text.splitlines())
        if had_final_newline:
            normalized += "\n"
        path.write_text(normalized, encoding="utf-8")
    print("build157 generated Kotlin source whitespace normalized")


if __name__ == "__main__":
    apply()
