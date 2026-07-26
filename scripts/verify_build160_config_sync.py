#!/usr/bin/env python3
"""Verify build160 persistent configuration synchronization over WSS."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
WSS = SRC / "HubMqttClient.kt"
MAIN = SRC / "MainActivity.kt"
API = SRC / "RouterControlApi.kt"
REPOSITORY = SRC / "RouterRepository.kt"


def main() -> None:
    combined = "\n".join(path.read_text(encoding="utf-8") for path in (WSS, MAIN, API, REPOSITORY))
    required = (
        "onConfigUpdate: (String) -> Unit",
        '"config" -> if (data != null) onConfigUpdate(data.toString())',
        "RouterRepositoryRegistry.get(prefs).acceptConfigRealtime(raw)",
        "fun acceptConfigRealtime(raw: String)",
        "private val configRevisions",
        'old.mutating && source != "command"',
        'source != "command" && old.updatedAt > frameAt',
        "internal fun parseNativePortRules",
        "internal fun parseUpnp",
        "internal fun parseFirewall",
        "internal fun parseDdnsList",
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise SystemExit(f"build160 config WSS invariants missing: {missing}")
    forbidden = (
        '"config" -> Unit',
        "fun acceptConfigRealtime(raw: String) {\n        TODO",
    )
    found = [value for value in forbidden if value in combined]
    if found:
        raise SystemExit(f"build160 config WSS forbidden placeholders: {found}")
    print("build160 persistent config WSS synchronization verified")


if __name__ == "__main__":
    main()
