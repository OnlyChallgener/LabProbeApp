#!/usr/bin/env python3
"""Static guardrails for the Android material-polish reference screens."""

from __future__ import annotations

import hashlib
import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOKENS = ROOT / "app/src/main/kotlin/com/labprobe/app/ui/design/LabUiV2.kt"
MATERIAL = ROOT / "app/src/main/kotlin/com/labprobe/app/ui/design/LabMaterialPolish.kt"
DETAIL = ROOT / "app/src/main/kotlin/com/labprobe/app/DeviceDetailV2.kt"
SHEETS = ROOT / "app/src/main/kotlin/com/labprobe/app/ui/design/InteractionSheets.kt"
BUILD = ROOT / "app/build.gradle.kts"

EXPECTED_TYPOGRAPHY_SHA256 = "463ed38a10762afa6c63042209edc36f8d6fd0ae3ca40d412f13490f359d637f"
EXPECTED_LAYOUT = {
    "PageHorizontal": "14",
    "PageTop": "8",
    "SectionGap": "10",
    "CardGap": "8",
    "ListGap": "7",
    "CardHorizontal": "12",
    "CardVertical": "9",
    "RowGap": "6",
    "FieldHeight": "48",
}
ALLOWED_FILES = {
    ".gitignore",
    ".github/workflows/ui-material-reference.yml",
    "app/build.gradle.kts",
    "app/src/debug/kotlin/com/labprobe/app/MaterialReferencePreviews.kt",
    "app/src/main/kotlin/com/labprobe/app/DeviceDetailV2.kt",
    "app/src/main/kotlin/com/labprobe/app/DeviceTypePicker.kt",
    "app/src/main/kotlin/com/labprobe/app/MainActivity.kt",
    "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt",
    "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt",
    "app/src/main/kotlin/com/labprobe/app/ui/design/InteractionSheets.kt",
    "app/src/main/kotlin/com/labprobe/app/ui/design/LabDesignComponents.kt",
    "app/src/main/kotlin/com/labprobe/app/ui/design/LabMaterialPolish.kt",
    "app/src/main/kotlin/com/labprobe/app/ui/design/LabUiV2.kt",
    "app/src/test/kotlin/com/labprobe/app/MaterialReferenceScreenTest.kt",
    "design.md",
    "tools/check_material_reference_ui.py",
}


def fail(message: str) -> None:
    raise SystemExit(f"FAIL: {message}")


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def changed_files() -> set[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "origin/refactor_network_health_ui"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return {line.strip().replace("\\", "/") for line in result.stdout.splitlines() if line.strip()}


token_source = text(TOKENS)
match = re.search(r"object LabTypography \{.*?\n\}", token_source, re.S)
if not match:
    fail("LabTypography block is missing")
digest = hashlib.sha256(match.group().encode()).hexdigest()
if digest != EXPECTED_TYPOGRAPHY_SHA256:
    fail("typography size, weight, or line-height tokens changed")

for name, value in EXPECTED_LAYOUT.items():
    if not re.search(rf"val {name} = {value}\.dp\b", token_source):
        fail(f"layout token {name} changed")

material_source = text(MATERIAL)
if 'setOf("home", "devices", "tool_ping", "device_detail")' not in material_source:
    fail("reference route allowlist changed")
for forbidden in ("haze-glass", "hazeGlass", "GlassStyle", "refraction", "Refraction"):
    if forbidden in material_source or forbidden in text(BUILD):
        fail(f"Liquid Glass API or effect is prohibited: {forbidden}")
for forbidden_hex in ("EC4899", "D946EF", "A855F7", "9333EA", "C026D3"):
    if forbidden_hex in material_source.upper():
        fail(f"purple/pink token is prohibited: {forbidden_hex}")
if "LabMaterialDark" in material_source or "darkColorScheme" in material_source:
    fail("dark mode is outside phase-one scope")
if 'dev.chrisbanes.haze:haze:1.7.2' not in text(BUILD):
    fail("stable Haze 1.7.2 backdrop dependency is required")
if 'debugImplementation("androidx.compose.ui:ui-test-manifest")' not in text(BUILD):
    fail("hosted Compose screenshot activity manifest is missing")
if "blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S" not in material_source:
    fail("Android 11-and-below static fallback guard is missing")
if material_source.count(".hazeSource(") != 1 or "Source and effects are siblings" not in material_source:
    fail("backdrop source must remain an isolated sibling of glass effects")
if "clip = true" not in material_source:
    fail("rounded glass shadow must clip its content to the same shape")
for required_role in ("secondaryContainer", "tertiaryContainer", "surfaceTint = Color.Transparent"):
    if required_role not in material_source:
        fail(f"reference theme leaves an implicit Material purple role: {required_role}")
if token_source.count("if (polished) polishColors.surfaceInset") < 2:
    fail("reference text field and dropdown material states are missing")

sheet_source = text(SHEETS)
if "if (polished) {\n                        Spacer(Modifier.height(2.dp))" not in sheet_source:
    fail("representative settings sheet still uses a decorative divider")
if "if (polished) polishColors.accent else DEVICE_ICON_ACCENT" not in sheet_source:
    fail("representative sheet action does not use the restrained material accent")

detail_source = text(DETAIL)
if "Modifier.horizontalScroll(rememberScrollState())" not in detail_source:
    fail("single-line horizontally scrollable long address is missing")
if "if (!polished && value != \"--\") Icon" not in detail_source:
    fail("reference detail still shows a copy icon")

changed = changed_files()
unexpected = changed - ALLOWED_FILES
if unexpected:
    fail("out-of-scope files changed: " + ", ".join(sorted(unexpected)))

print("PASS: material reference UI guardrails")
print(f"PASS: typography frozen ({digest[:12]})")
print("PASS: scope, fallback, address, and no-Liquid-Glass checks")
