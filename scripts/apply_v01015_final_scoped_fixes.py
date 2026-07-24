#!/usr/bin/env python3
"""Final scoped fixes against the generated router UI source."""
from pathlib import Path

from apply_v01015_scoped_fixes import (
    patch_nat_history_and_controls,
    patch_network_diagnostic_cache,
)

ROOT = Path(__file__).resolve().parents[1]
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing final scoped router fix pattern: {label}")
    return text.replace(old, new, 1)


def patch_generated_nat_protocol_context() -> None:
    text = ROUTER_NATIVE.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '            mode = data.optString("mode", "classic"),',
        '''            mode = data.optString("mode").takeIf { it == "classic" || it == "5780" }
                ?: data.optString("requested_mode", "classic"),''',
        "NAT requested protocol parsing",
    )
    ROUTER_NATIVE.write_text(text, encoding="utf-8")


def patch_generated_network_diagnostic_cache() -> None:
    # RouterControlUi.kt is compact in the current generated source. Reuse the
    # compact/idempotent patch instead of the obsolete pretty-formatted matcher.
    patch_network_diagnostic_cache()


def apply() -> None:
    patch_nat_history_and_controls()
    patch_generated_nat_protocol_context()
    patch_generated_network_diagnostic_cache()
    print("final scoped NAT and diagnostic fixes applied")


if __name__ == "__main__":
    apply()
