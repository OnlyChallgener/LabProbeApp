#!/usr/bin/env python3
"""Align the legacy source verifier with build166 release wording."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts/verify_build154_sources.py"

text = VERIFIER.read_text(encoding="utf-8")
text = text.replace("NAT 诊断、Agent 更新与映射界面修复", "首页视觉与映射状态修复")
text = text.replace("versionCode = 165", "versionCode = 166")
text = text.replace('versionName = "0.10.23"', 'versionName = "0.10.24"')
VERIFIER.write_text(text, encoding="utf-8")

for required in ("首页视觉与映射状态修复", "versionCode = 166", 'versionName = "0.10.24"'):
    if required not in text:
        raise SystemExit(f"build166 verifier value missing: {required}")
if "NAT 诊断、Agent 更新与映射界面修复" in text:
    raise SystemExit("build165 release title still required by verifier")
print("build166 legacy verifier finalized")
