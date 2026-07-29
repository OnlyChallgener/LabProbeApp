#!/usr/bin/env python3
"""Generate build165 sources, then apply the build166 UI/state corrections."""
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]


def run_script(path: Path) -> None:
    try:
        runpy.run_path(str(path), run_name="__main__")
    except SystemExit as exc:
        if exc.code not in (None, 0):
            raise


run_script(ROOT / "scripts/prepare_android_sources_build165.py")
run_script(ROOT / "scripts/apply_build166_ui_state_fixes.py")
run_script(ROOT / "scripts/apply_build166_portmap_followup.py")
run_script(ROOT / "scripts/apply_build166_verifier_final.py")
