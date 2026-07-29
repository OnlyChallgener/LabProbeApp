#!/usr/bin/env python3
"""Generate the existing Android sources, then apply all build165 corrections."""
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]


def run_script(path: Path) -> None:
    try:
        runpy.run_path(str(path), run_name="__main__")
    except SystemExit as exc:
        code = exc.code
        if code not in (None, 0):
            raise


run_script(ROOT / "scripts/prepare_android_sources.py")
run_script(ROOT / "scripts/apply_build165_user_fixes.py")
run_script(ROOT / "scripts/apply_build165_home_polish.py")
run_script(ROOT / "scripts/apply_build165_ci_compat.py")
