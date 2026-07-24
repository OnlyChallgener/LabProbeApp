#!/usr/bin/env python3
from pathlib import Path

from apply_next_release_fixes import apply as apply_next_release
from apply_refresh_stability_fixes import apply as apply_refresh_stability
from apply_release_text_fixes import apply as apply_release_texts
from apply_router_ui_fixes import patch_main, patch_router_ui
from apply_build155_home_navigation_restore import apply as apply_build155_home_navigation
from apply_v01015_build148_release_fix import apply as apply_build148_release_fix
from apply_v01015_build149_about_compile_fix import apply as apply_build149_about_compile_fix
from apply_v01015_build150_lite_realtime import apply as apply_build150_lite_realtime
from apply_v01015_build151_smooth_realtime import apply as apply_build151_smooth_realtime
from apply_v01015_build152_connection_gate import apply as apply_build152_connection_gate
from apply_v01015_build153_single_wss import apply as apply_build153_single_wss
from apply_v01015_build154_realtime_stability import apply as apply_build154_realtime_stability
from apply_v01015_ddns_cache_hotfix import apply as apply_v01015_ddns_cache
from apply_v01015_final_scoped_fixes import apply as apply_v01015_scoped
from apply_v01015_nat_text_hotfix import apply as apply_v01015_nat_text
from apply_v01015_realtime_delivery_fix import apply as apply_v01015_realtime_delivery
from apply_v01015_requested_hotfix import apply as apply_v01015_requested
from apply_v01015_router_stability import patch_main as patch_v01015_main
from apply_v01015_router_stability import patch_router_api as patch_v01015_router_api
from apply_v01015_router_stability import patch_router_native as patch_v01015_router_native
from apply_v01015_runtime_cache_hotfix import apply as apply_v01015_runtime_cache
from apply_v01015_version_log_fix import apply as apply_v01015_version_log
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
        or '"v$NAME build$CODE · 路由实时推送与刷新修复"' in current
        or '"v$NAME build$CODE · 实时刷新、NAT 历史与界面修复"' in current
        or '"v$NAME build$CODE · 实时刷新链路与版本信息修复"' in current
        or '"v$NAME build$CODE · 轻量实时接口与终端刷新修复"' in current
        or '"v$NAME build$CODE · 本地实时采样与缓存平滑显示"' in current
        or '"v$NAME build$CODE · 实时连接租约与离线节流"' in current
        or '"v$NAME build$CODE · Hub 原生 WSS 实时链路"' in current
        or '"v$NAME build$CODE · 原生 fast 秒级稳定刷新"' in current
    )

    # Realtime migrations must never bypass the established home navigation.
    # This runs before every fast-path build and is independently idempotent.
    if "private suspend fun calibrateRealtimeCache()" in current:
        apply_build155_home_navigation()
        apply_build150_lite_realtime()
        apply_build151_smooth_realtime()
        apply_build152_connection_gate()
        apply_build153_single_wss()
        apply_build154_realtime_stability()
        print("Android build154 WSS and build141 home navigation sources prepared")
        raise SystemExit(0)

    if not base_generated and not refresh_generated and not final_generated:
        patch_main()
        patch_router_ui()
        apply_wol_navigation()
        apply_release_texts()
        apply_next_release()

    if not refresh_generated and not final_generated:
        apply_refresh_stability()

    apply_v01015_ddns_cache()
    patch_v01015_router_native()
    patch_v01015_router_api()
    patch_v01015_main()
    apply_v01015_runtime_cache()
    apply_v01015_nat_text()
    apply_v01015_requested()
    apply_v01015_scoped()
    apply_v01015_realtime_delivery()
    apply_v01015_version_log()

    apply_build148_release_fix()
    apply_build149_about_compile_fix()
    apply_build150_lite_realtime()
    apply_build151_smooth_realtime()
    apply_build152_connection_gate()
    apply_build153_single_wss()
    apply_build154_realtime_stability()
    apply_build155_home_navigation()
    print("Android source fixes and build141 home navigation prepared")
