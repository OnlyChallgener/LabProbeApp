#!/usr/bin/env python3
"""Make user mutations outrank background SWR reads in RouterRepository."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterRepository.kt"


def apply() -> None:
    text = TARGET.read_text(encoding="utf-8")

    read_guards = {
        '    suspend fun refreshDdns(force: Boolean = false) {\n        val key = "ddns"\n        val seq = sequence(key).incrementAndGet()\n        val old = _ddns.value':
        '    suspend fun refreshDdns(force: Boolean = false) {\n        val key = "ddns"\n        if (_ddns.value.mutating) return\n        val seq = sequence(key).incrementAndGet()\n        val old = _ddns.value',
        '    suspend fun refreshUpnp(force: Boolean = false) {\n        val key = "upnp"\n        val seq = sequence(key).incrementAndGet()\n        val old = _upnp.value':
        '    suspend fun refreshUpnp(force: Boolean = false) {\n        val key = "upnp"\n        if (_upnp.value.mutating) return\n        val seq = sequence(key).incrementAndGet()\n        val old = _upnp.value',
        '    suspend fun refreshPortMappings(force: Boolean = false) {\n        val key = "portMappings"\n        val seq = sequence(key).incrementAndGet()\n        val old = _portMappings.value':
        '    suspend fun refreshPortMappings(force: Boolean = false) {\n        val key = "portMappings"\n        if (_portMappings.value.mutating) return\n        val seq = sequence(key).incrementAndGet()\n        val old = _portMappings.value',
        '    suspend fun refreshFirewall(force: Boolean = false) {\n        val key = "firewall"\n        val seq = sequence(key).incrementAndGet()\n        val old = _firewall.value':
        '    suspend fun refreshFirewall(force: Boolean = false) {\n        val key = "firewall"\n        if (_firewall.value.mutating) return\n        val seq = sequence(key).incrementAndGet()\n        val old = _firewall.value',
    }
    for old, new in read_guards.items():
        if new not in text:
            if old not in text:
                raise RuntimeError(f"missing repository read guard anchor: {old.splitlines()[0]}")
            text = text.replace(old, new, 1)

    failures = {
        '.onFailure { _upnp.value = old.copy(mutating = false, error = message(it, "UPnP 设置失败"), generation = seq) }':
        '.onFailure { failure ->\n                if (sequence(key).get() == seq) {\n                    _upnp.value = old.copy(mutating = false, error = message(failure, "UPnP 设置失败"), generation = seq)\n                }\n            }',
        '.onFailure { _portMappings.value = old.copy(mutating = false, error = message(it, "端口映射设置失败"), generation = seq) }':
        '.onFailure { failure ->\n                if (sequence(key).get() == seq) {\n                    _portMappings.value = old.copy(mutating = false, error = message(failure, "端口映射设置失败"), generation = seq)\n                }\n            }',
        '.onFailure { _ddns.value = old.copy(mutating = false, error = message(it, "DDNS 设置失败"), generation = seq) }':
        '.onFailure { failure ->\n                if (sequence(key).get() == seq) {\n                    _ddns.value = old.copy(mutating = false, error = message(failure, "DDNS 设置失败"), generation = seq)\n                }\n            }',
        '.onFailure { _firewall.value = old.copy(mutating = false, error = message(it, "防火墙 设置失败"), generation = seq) }':
        '.onFailure { failure ->\n                if (sequence(key).get() == seq) {\n                    _firewall.value = old.copy(mutating = false, error = message(failure, "防火墙设置失败"), generation = seq)\n                }\n            }',
    }
    # Accept the exact existing Chinese text for the firewall fallback.
    failures['.onFailure { _firewall.value = old.copy(mutating = false, error = message(it, "防火墙设置失败"), generation = seq) }'] = failures.pop(
        '.onFailure { _firewall.value = old.copy(mutating = false, error = message(it, "防火墙 设置失败"), generation = seq) }'
    )
    for old, new in failures.items():
        if new not in text:
            if old not in text:
                raise RuntimeError(f"missing repository mutation failure anchor: {old[:60]}")
            text = text.replace(old, new, 1)

    required = (
        'if (_ddns.value.mutating) return',
        'if (_upnp.value.mutating) return',
        'if (_portMappings.value.mutating) return',
        'if (_firewall.value.mutating) return',
        'if (sequence(key).get() == seq) {\n                    _ddns.value = old.copy',
    )
    missing = [needle for needle in required if needle not in text]
    if missing:
        raise RuntimeError(f"repository conflict guard verification failed: {missing}")

    TARGET.write_text(text, encoding="utf-8")
    print("build158 repository mutation priority and stale-read conflict guards applied")


if __name__ == "__main__":
    apply()
