#!/usr/bin/env python3
"""Align the long-lived Android verifier with build161 generated sources."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts/verify_build154_sources.py"


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


if __name__ == "__main__":
    apply()
