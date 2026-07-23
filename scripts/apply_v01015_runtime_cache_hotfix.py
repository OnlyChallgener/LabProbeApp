#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROUTER_CONTROL = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
ROUTER_NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"


def apply() -> None:
    control = ROUTER_CONTROL.read_text(encoding="utf-8")
    control = control.replace("private object RouterControlMemoryCache", "object RouterUiRuntimeCache")
    control = control.replace("RouterControlMemoryCache.ddnsRows", "RouterUiRuntimeCache.ddnsRows")
    ROUTER_CONTROL.write_text(control, encoding="utf-8")

    native = ROUTER_NATIVE.read_text(encoding="utf-8")
    old = '''    var rows by remember { mutableStateOf<List<DdnsRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
        runCatching { api.ddns() }.onSuccess { rows = it }
        loaded = true
    }
'''
    new = '''    var rows by remember { mutableStateOf(RouterUiRuntimeCache.ddnsRows) }
    var loaded by remember { mutableStateOf(rows.isNotEmpty()) }
    LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
        runCatching { api.ddns() }
            .onSuccess { latest ->
                rows = latest
                RouterUiRuntimeCache.ddnsRows = latest
            }
        loaded = true
    }
'''
    if new not in native:
        if old not in native:
            raise RuntimeError("missing v0.10.15 hotfix pattern: HomeDdnsMiniCard cache")
        native = native.replace(old, new, 1)
    ROUTER_NATIVE.write_text(native, encoding="utf-8")
    print("v0.10.15 shared DDNS runtime cache applied")


if __name__ == "__main__":
    apply()
