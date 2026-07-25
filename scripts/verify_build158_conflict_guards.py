#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/com/labprobe/app/RouterRepository.kt"
text = path.read_text(encoding="utf-8")
required = (
    'if (_ddns.value.mutating) return',
    'if (_upnp.value.mutating) return',
    'if (_portMappings.value.mutating) return',
    'if (_firewall.value.mutating) return',
    'if (sequence(key).get() == seq) {\n                    _ddns.value = old.copy',
    'if (sequence(key).get() == seq) {\n                    _upnp.value = old.copy',
    'if (sequence(key).get() == seq) {\n                    _portMappings.value = old.copy',
    'if (sequence(key).get() == seq) {\n                    _firewall.value = old.copy',
)
missing = [needle for needle in required if needle not in text]
if missing:
    raise SystemExit(f"missing repository mutation-priority guards: {missing}")
print("build158 repository mutation-priority guards verified")
