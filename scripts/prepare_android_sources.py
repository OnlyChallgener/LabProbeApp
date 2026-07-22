#!/usr/bin/env python3
from pathlib import Path

from apply_next_release_fixes import apply as apply_next_release
from apply_refresh_stability_fixes import apply as apply_refresh_stability
from apply_release_text_fixes import apply as apply_release_texts
from apply_router_ui_fixes import patch_main, patch_router_ui
from apply_v01015_router_stability import apply as apply_v01015_stability
from apply_v01015_runtime_cache_hotfix import apply as apply_v01015_runtime_cache
from apply_wol_navigation_fix import apply as apply_wol_navigation

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"


if __name__ == "__main__":
    current = MAIN.read_text(encoding="utf-8")
    base_generated = (
        "HomeDdnsMiniCard(" in current
        and '"tool_router_nat" -> RouterNatDiagnosticScreen' in current
        and "v0.10.13 build143 · 路由诊断与首页联动" in current
    )
    refresh_generated = "v0.10.14 build144 · 实时刷新与页面稳定性修复" in current
    final_generated = "v0.10.15 build145 · 路由页面稳定与诊断交互修复" in current

    if not base_generated and not refresh_generated and not final_generated:
        patch_main()
        patch_router_ui()
        apply_wol_navigation()
        apply_release_texts()
        apply_next_release()

    if not refresh_generated and not final_generated:
        apply_refresh_stability()

    apply_v01015_stability()
    apply_v01015_runtime_cache()
    print("Android source fixes prepared")
