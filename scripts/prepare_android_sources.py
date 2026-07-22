#!/usr/bin/env python3
from pathlib import Path

from apply_next_release_fixes import apply as apply_next_release
from apply_refresh_stability_fixes import apply as apply_refresh_stability
from apply_release_text_fixes import apply as apply_release_texts
from apply_router_ui_fixes import patch_main, patch_router_ui
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
    final_generated = "v0.10.14 build144 · 实时刷新与页面稳定性修复" in current

    if not base_generated and not final_generated:
        patch_main()
        patch_router_ui()
        apply_wol_navigation()
        apply_release_texts()
        apply_next_release()

    apply_refresh_stability()
    print("Android source fixes prepared")
