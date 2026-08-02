#!/usr/bin/env python3
"""Apply legacy migrations and the current checked-in source corrections."""
from pathlib import Path
import re
import runpy

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
if match is None:
    raise RuntimeError("missing Android versionCode")

build_code = int(match.group(1))
if build_code < 167:
    names = ("prepare_android_sources_build166.py", "apply_build167_labrelay_sync.py")
elif build_code == 167:
    names = ("apply_build167_labrelay_sync.py",)
else:
    names = ()

if build_code >= 170:
    names += ("apply_build170_fixes.py",)

for name in names:
    try:
        runpy.run_path(str(ROOT / "scripts" / name), run_name="__main__")
    except SystemExit as exc:
        if exc.code not in (None, 0):
            raise

if not names:
    print(f"build{build_code} sources require no prepare-time migration")
