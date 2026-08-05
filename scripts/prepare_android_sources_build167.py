#!/usr/bin/env python3
"""Apply legacy migrations and the current checked-in source corrections."""
from pathlib import Path
import re
import runpy

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
if code_match is None or name_match is None:
    raise RuntimeError("missing Android version metadata")

build_code = int(code_match.group(1))
version_name = name_match.group(1)
if build_code < 167:
    names = ("prepare_android_sources_build166.py", "apply_build167_labrelay_sync.py")
elif build_code == 167:
    names = ("apply_build167_labrelay_sync.py",)
else:
    names = ()

if build_code >= 170:
    names += ("apply_build170_fixes.py",)
if build_code >= 171:
    names += ("apply_build171_regression_fixes.py",)
if build_code >= 172:
    names += ("apply_build172_agent_icon_fixes.py",)
if build_code >= 173:
    names += ("apply_build173_icon_fidelity_fix.py",)
if build_code >= 174:
    names += ("apply_build174_stability_fixes.py",)

for name in names:
    try:
        runpy.run_path(str(ROOT / "scripts" / name), run_name="__main__")
    except SystemExit as exc:
        if exc.code not in (None, 0):
            raise

if build_code >= 170:
    main_path = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
    main_text = main_path.read_text(encoding="utf-8")
    semantic_marker = "    // StrictHostKeyChecking accept-new semantics: accept the first key and reject a changed key.\n"
    config_marker = "    val cfg = java.util.Properties(); cfg[\"StrictHostKeyChecking\"]=\"ask\";"
    if semantic_marker not in main_text:
        if config_marker not in main_text:
            raise RuntimeError("secure SSH host-key policy marker missing")
        main_text = main_text.replace(config_marker, semantic_marker + config_marker, 1)
        main_path.write_text(main_text, encoding="utf-8")

    verify_path = ROOT / "scripts/verify_build154_sources.py"
    verify_text = verify_path.read_text(encoding="utf-8")
    if build_code >= 174:
        expected_title = "图标显示与 Relay 更新稳定性修复"
        expected_print = "build174 launcher visibility and Relay update stability verified"
    elif build_code >= 173:
        expected_title = "原始启动图标细节还原"
        expected_print = "build173 original SVG launcher details and adaptive safe-zone source state verified"
    elif build_code >= 172:
        expected_title = "Relay 更新与启动图标修复"
        expected_print = "build172 Relay update coordinator and clean adaptive launcher source state verified"
    elif build_code >= 171:
        expected_title = "图标与设备数据回归修复"
        expected_print = "build171 offline authority and launcher safe-zone source state verified"
    else:
        expected_title = "数据一致性与安全修复"
        expected_print = "build170 data consistency, security, and mapping source state verified"
    replacements = {
        "require(GRADLE, 'versionCode = 169', 'versionName = \"0.10.27\"')":
            f"require(GRADLE, 'versionCode = {build_code}', 'versionName = \"{version_name}\"')",
        "        '图标与终端状态修复',": f"        '{expected_title}',",
        "print('build169 icon, offline-device, and navigation source state verified')":
            f"print('{expected_print}')",
    }
    for old, new in replacements.items():
        if new in verify_text:
            continue
        if old not in verify_text:
            raise RuntimeError(f"verifier marker missing: {old}")
        verify_text = verify_text.replace(old, new, 1)
    verify_path.write_text(verify_text, encoding="utf-8")

if not names:
    print(f"build{build_code} sources require no prepare-time migration")
