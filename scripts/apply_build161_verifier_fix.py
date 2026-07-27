#!/usr/bin/env python3
"""Align the long-lived Android verifier with build161, then apply build162."""
from pathlib import Path

from apply_build162_ddns_click_crash_fix import apply as apply_build162_ddns_click_crash_fix

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts/verify_build154_sources.py"
CONTROL = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"


def apply() -> None:
    text = VERIFIER.read_text(encoding="utf-8")
    text = text.replace(
        "        '状态闭环与后台任务',",
        "        '终端历史与映射持久化',",
    )
    text = text.replace(
        "print('build160 truthful realtime state, Hub-owned task lifecycle and snapshot-preserving pages verified')",
        "print('build161 realtime, durable mapping and history presentation verified')",
    )
    if "'状态闭环与后台任务'," in text:
        raise RuntimeError("build160 release-note verifier remains")
    if "'终端历史与映射持久化'," not in text:
        raise RuntimeError("build161 release-note verifier missing")
    VERIFIER.write_text(text, encoding="utf-8")
    print("build161 legacy verifier release-note requirement updated")
    apply_build162_ddns_click_crash_fix()

    control = CONTROL.read_text(encoding="utf-8")
    marker = "    // Editing, switching and the overflow menu are separate hit targets."
    invariant = "    // whole card must never turn red"
    if invariant not in control:
        if marker not in control:
            raise RuntimeError("build162 DDNS card comment anchor missing")
        control = control.replace(marker, invariant + "\n" + marker, 1)
        CONTROL.write_text(control, encoding="utf-8")
    print("build162 DDNS informational-card invariant preserved")


if __name__ == "__main__":
    apply()
