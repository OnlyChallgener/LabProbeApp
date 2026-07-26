#!/usr/bin/env python3
"""Small compile-safety finalizer for build160 generated sources."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PORTMAP = ROOT / "app/src/main/kotlin/com/labprobe/app/PortMapping.kt"


def apply() -> None:
    text = PORTMAP.read_text(encoding="utf-8")
    text = text.replace('"stale" -> PortAmber', '"stale" -> Color(0xFFF59E0B)')
    if '"stale" -> PortAmber' in text:
        raise RuntimeError("undefined PortAmber remains")
    if '"stale" -> Color(0xFFF59E0B)' not in text:
        raise RuntimeError("Agent stale color missing")
    PORTMAP.write_text(text, encoding="utf-8")
    print("build160 compile-safe Agent color finalized")


if __name__ == "__main__":
    apply()
