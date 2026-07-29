#!/usr/bin/env python3
from pathlib import Path

from apply_next_release_fixes import apply as apply_next_release
from apply_refresh_stability_fixes import apply as apply_refresh_stability
from apply_release_text_fixes import apply as apply_release_texts
from apply_router_ui_fixes import patch_main, patch_router_ui
from apply_build155_home_navigation_restore import apply as apply_build155_home_navigation
from apply_build155_connection_routes_sync import apply as apply_build155_connection_routes_sync
from apply_build155_wss_watchdog import apply as apply_build155_wss_watchdog
from apply_build156_router_fields import apply as apply_build156_router_fields
from apply_build157_source_normalization import apply as apply_build157_source_normalization
from apply_build157_regression_restore import apply as apply_build157_regression_restore
from apply_build157_stun_card_restore import apply as apply_build157_stun_card_restore
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
from apply_router_sync_presentation_fix import apply as apply_router_sync_presentation
from apply_router_sync_wording_finalizer import apply as apply_router_sync_wording_finalizer
from apply_nat_cancel_history_limit_fix import apply as apply_nat_cancel_history_limit
from apply_nat_beta_snapshot_final_fix_v2 import apply as apply_nat_beta_snapshot_final
from apply_build158_router_repository import apply as apply_build158_router_repository
from apply_build158_repository_conflict_guard import apply as apply_build158_repository_conflict_guard
from apply_build158_wss_preload_trigger import apply as apply_build158_wss_preload_trigger
from apply_build159_router_control_reliability import apply as apply_build159_router_control_reliability
from apply_build160_sync_task_ui import apply as apply_build160_sync_task_ui
from apply_build160_agent_presence_finalizer import apply as apply_build160_agent_presence_finalizer
from apply_build160_compile_finalizer import apply as apply_build160_compile_finalizer
from apply_build160_config_sync_finalizer import apply as apply_build160_config_sync_finalizer
from apply_build161_history_portmap_fix import apply as apply_build161_history_portmap_fix
from apply_build161_verifier_fix import apply as apply_build161_verifier_fix
from apply_build162_ddns_click_crash_fix import apply as apply_build162_ddns_click_crash_fix
from apply_build162_ddns_field_compat_fix import apply as apply_build162_ddns_field_compat_fix
from apply_build163_terminal_live_sync import apply as apply_build163_terminal_live_sync
from apply_build163_followup_fixes import apply as apply_build163_followup_fixes
from apply_wol_navigation_fix import apply as apply_wol_navigation

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"


def apply_build160() -> None:
    apply_build160_sync_task_ui()
    apply_build160_agent_presence_finalizer()
    apply_build160_compile_finalizer()
    apply_build160_config_sync_finalizer()


def apply_build161() -> None:
    apply_build160()
    apply_build161_history_portmap_fix()
    apply_build161_verifier_fix()


def apply_build162() -> None:
    apply_build161()
    apply_build162_ddns_click_crash_fix()
    apply_build162_ddns_field_compat_fix()


def apply_build163() -> None:
    apply_build162()
    apply_build163_terminal_live_sync()
    apply_build163_followup_fixes()


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
        or '"v$NAME build$CODE · 长连接启动与路由功能恢复"' in current
        or '"v$NAME build$CODE · 路由字段与长连接完整修复"' in current
        or '"v$NAME build$CODE · 路由交互与状态回归修复"' in current
        or '"v$NAME build$CODE · 统一路由数据源与无感预加载"' in current
        or '"v$NAME build$CODE · 路由控制队列与可靠指令"' in current
        or '"v$NAME build$CODE · 状态闭环与后台任务"' in current
        or '"v$NAME build$CODE · 终端历史与映射持久化"' in current
        or '"v$NAME build$CODE · 终端列表五秒实时同步"' in current
    )

    if "private suspend fun calibrateRealtimeCache()" in current:
        apply_build155_home_navigation()
        apply_build150_lite_realtime()
        apply_build151_smooth_realtime()
        apply_build152_connection_gate()
        apply_build153_single_wss()
        apply_build154_realtime_stability()
        apply_build155_connection_routes_sync()
        apply_build155_wss_watchdog()
        apply_build156_router_fields()
        apply_build157_source_normalization()
        apply_build157_regression_restore()
        apply_build157_stun_card_restore()
        apply_router_sync_presentation()
        apply_router_sync_wording_finalizer()
        apply_nat_cancel_history_limit()
        apply_nat_beta_snapshot_final()
        apply_build158_router_repository()
        apply_build158_repository_conflict_guard()
        apply_build158_wss_preload_trigger()
        apply_build159_router_control_reliability()
        apply_build163()
        print("Android build163 terminal realtime and daily-summary follow-up prepared")
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
    apply_build155_connection_routes_sync()
    apply_build155_wss_watchdog()
    apply_build156_router_fields()
    apply_build157_source_normalization()
    apply_build157_regression_restore()
    apply_build157_stun_card_restore()
    apply_router_sync_presentation()
    apply_router_sync_wording_finalizer()
    apply_nat_cancel_history_limit()
    apply_nat_beta_snapshot_final()
    apply_build158_router_repository()
    apply_build158_repository_conflict_guard()
    apply_build158_wss_preload_trigger()
    apply_build159_router_control_reliability()
    apply_build163()
    print("Android build163 terminal realtime and daily-summary follow-up prepared")
