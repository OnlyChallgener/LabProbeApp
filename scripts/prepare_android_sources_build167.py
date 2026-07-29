#!/usr/bin/env python3
"""Generate build167 sources without replaying legacy patches twice."""
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
names = ("apply_build167_labrelay_sync.py",)
if 'versionCode = 167' not in (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"):
    names = ("prepare_android_sources_build166.py", *names)

for name in names:
    try:
        runpy.run_path(str(ROOT / "scripts" / name), run_name="__main__")
    except SystemExit as exc:
        if exc.code not in (None, 0):
            raise
