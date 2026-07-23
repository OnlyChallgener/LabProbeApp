#!/usr/bin/env python3
from pathlib import Path

from apply_next_release_fixes import apply as apply_next_release
from apply_refresh_stability_fixes import apply as apply_refresh_stability
from apply_release_text_fixes import apply as apply_release_texts
from apply_router_ui_fixes import patch_main, patch_router_ui
from apply_v01015_ddns_cache_hotfix import apply as apply_v01015_ddns_cache
from apply_v01015_final_scoped_fixes import apply as apply_v01015_scoped
from apply_v01015_nat_text_hotfix import apply as apply_v01015_nat_text
from apply_v01015_realtime_delivery_fix import apply as apply_v01015_realtime_delivery
from apply_v01015_requested_hotfix import apply as apply_v01015_requested
from apply_v01015_router_stability import patch_main as patch_v01015_main
from apply_v01015_router_stability import patch_router_api as patch_v01015_router_api
from apply_v01015_router_stability import patch_router_native as patch_v01015_router_native
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
    final_generated = (
        "v0.10.15 build145 · 路由页面稳定与诊断交互修复" in current
        or "v0.10.15 build146 · 路由实时推送与刷新修复" in current
    )

    if not base_generated and not refresh_generated and not final_generated:
        patch_main()
        patch_router_ui()
        apply_wol_navigation()
        apply_release_texts()
        apply_next_release()

    if not refresh_generated and not final_generated:
        apply_refresh_stability()

    # The base router UI generator rewrites DdnsRecordsSection, so apply the
    # cache-preserving DDNS patch against that generated form first. The
    # remaining v0.10.15 patches can then run independently and idempotently.
    apply_v01015_ddns_cache()
    patch_v01015_router_native()
    patch_v01015_router_api()
    patch_v01015_main()
    apply_v01015_runtime_cache()
    apply_v01015_nat_text()
    apply_v01015_requested()
    # Keep user-requested UI/history fixes after the generated router pages.
    apply_v01015_scoped()
    # This must remain last: it wires the already-subscribed MQTT dashboard
    # topic into AppState and replaces the generated 15/20s timer with a 1s
    # HTTP fallback without allowing older scripts to overwrite it.
    apply_v01015_realtime_delivery()
    print("Android source fixes prepared")
