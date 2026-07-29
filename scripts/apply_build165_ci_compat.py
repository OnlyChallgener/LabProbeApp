#!/usr/bin/env python3
"""Keep legacy source verifier aligned with build165 typography and wording."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts/verify_build154_sources.py"

text = VERIFIER.read_text(encoding="utf-8")
text = text.replace("'fontSize = 12.5.sp',", "'fontSize = 13.sp',")
text = text.replace("build164 terminal card and NAT parameter styling verified", "build165 NAT, Agent, mapping and home polish verified")
text = text.replace("APP v0.10.22 build164", "APP v0.10.23 build165")
VERIFIER.write_text(text, encoding="utf-8")

if "'fontSize = 12.5.sp'," in text:
    raise SystemExit("legacy NAT font verifier still requires 12.5sp")
if "'fontSize = 13.sp'," not in text:
    raise SystemExit("build165 NAT font verifier missing 13sp")
print("build165 legacy verifier compatibility applied")
