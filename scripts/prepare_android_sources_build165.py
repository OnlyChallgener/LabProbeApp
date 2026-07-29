#!/usr/bin/env python3
"""Generate the existing Android sources, then apply the build165 corrections."""
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "scripts/prepare_android_sources.py"), run_name="__main__")
runpy.run_path(str(ROOT / "scripts/apply_build165_user_fixes.py"), run_name="__main__")
