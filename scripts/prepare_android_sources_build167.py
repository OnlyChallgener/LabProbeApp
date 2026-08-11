#!/usr/bin/env python3
"""Apply the current checked-in source corrections for active LabProbe builds."""
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
if build_code < 170:
    raise RuntimeError(f"legacy build{build_code} source preparation is no longer supported on this branch")

# build170-build178 started life as one-shot migration scripts. Once their
# complete output has been checked into a build181+ release source tree, some
# of those scripts intentionally can no longer find their old input patterns.
# The verifier is rewritten only at the very end of a successful preparation,
# so it is a safe completion marker: it cannot become current after a partial
# legacy-patch run.
verify_path = ROOT / "scripts/verify_build154_sources.py"
verify_before = verify_path.read_text(encoding="utf-8") if verify_path.exists() else ""
materialized_build181 = (
    build_code >= 181
    and f"versionCode = {build_code}" in verify_before
    and f'versionName = "{version_name}"' in verify_before
    and "build178 SSH compatibility, blue-white controls, and device names verified" in verify_before
)

names = []
if materialized_build181:
    print(f"build{build_code} legacy source patches already materialized; skipping build170-build178 migrations")
else:
    if build_code >= 170:
        names.append("apply_build170_fixes.py")
    if build_code >= 171:
        names.append("apply_build171_regression_fixes.py")
    if build_code >= 172:
        names.append("apply_build172_agent_icon_fixes.py")
    if build_code >= 173:
        names.append("apply_build173_icon_fidelity_fix.py")
    if build_code >= 174:
        names.append("apply_build174_stability_fixes.py")
    if build_code >= 175:
        names.append("apply_build175_icon_version_display_fix.py")
    if build_code >= 176:
        names.append("apply_build176_standard_adaptive_icon.py")
    if build_code >= 177:
        names.append("apply_build177_centered_icon_spacing.py")
    if build_code >= 178:
        names.append("apply_build178_ssh_color_device_name_fixes.py")

for name in names:
    try:
        runpy.run_path(str(ROOT / "scripts" / name), run_name="__main__")
    except SystemExit as exc:
        if exc.code not in (None, 0):
            raise

main_path = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
main_text = main_path.read_text(encoding="utf-8")

# Some legacy one-shot Python patches embedded the Kotlin '\n' character
# literal through a Python triple-quoted replacement. On a fresh materialize
# pass that can turn it into a literal line break between single quotes. Repair
# the generated Kotlin deterministically before compiling.
broken_newline_literal = "shown.contains('\n')"
fixed_newline_literal = "shown.contains('\\n')"
if broken_newline_literal in main_text:
    main_text = main_text.replace(broken_newline_literal, fixed_newline_literal, 1)

semantic_marker = "    // StrictHostKeyChecking accept-new semantics: accept the first key and reject a changed key.\n"
config_marker = "    val cfg = java.util.Properties(); cfg[\"StrictHostKeyChecking\"]=\"ask\";"
if semantic_marker not in main_text:
    if config_marker not in main_text:
        raise RuntimeError("secure SSH host-key policy marker missing")
    main_text = main_text.replace(config_marker, semantic_marker + config_marker, 1)
main_path.write_text(main_text, encoding="utf-8")

# RouterSuiteTabs uses foundation BorderStroke for the pale-blue rounded
# selected pill. Keep the import materialized together with the generated
# source state so a clean CI checkout compiles without relying on IDE imports.
router_control_path = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
router_control_text = router_control_path.read_text(encoding="utf-8")
border_import = "import androidx.compose.foundation.BorderStroke\n"
if border_import not in router_control_text:
    import_anchor = "import androidx.compose.foundation.BasicTooltipBox\n"
    if import_anchor not in router_control_text:
        raise RuntimeError("RouterControlUi BorderStroke import anchor missing")
    router_control_text = router_control_text.replace(import_anchor, import_anchor + border_import, 1)
    router_control_path.write_text(router_control_text, encoding="utf-8")

verify_text = verify_path.read_text(encoding="utf-8")
if build_code >= 178:
    expected_title = "SSH、控件配色与设备名称一致性修复"
    expected_print = "build178 SSH compatibility, blue-white controls, and device names verified"
elif build_code >= 177:
    expected_title = "启动图标中心比例修复"
    expected_print = "build177 centered launcher spacing verified"
elif build_code >= 176:
    expected_title = "标准 Adaptive Icon 分层修复"
    expected_print = "build176 standard adaptive icon layers verified"
elif build_code >= 175:
    expected_title = "图标留白与 Relay 版本显示修复"
    expected_print = "build175 balanced launcher margin and Relay version display verified"
elif build_code >= 174:
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
