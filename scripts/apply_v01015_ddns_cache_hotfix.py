#!/usr/bin/env python3
"""Apply the v0.10.15 DDNS cache fix after the generated router UI rewrite.

The router UI is generated during Gradle preparation, so this patch deliberately
uses function boundaries instead of matching one exact formatting variant.
Only ``DdnsRecordsSection`` and its small in-memory cache are touched.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ROUTER_CONTROL = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"

CACHE_BLOCK = '''
private object RouterControlMemoryCache {
    var ddnsRows: List<DdnsRecord> = emptyList()
}
'''

STATE_AND_REFRESH_BLOCK = '''    var rows by remember { mutableStateOf(RouterControlMemoryCache.ddnsRows) }
    var loading by remember { mutableStateOf(rows.isEmpty()) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DdnsRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DdnsRecord?>(null) }
    suspend fun refresh(force: Boolean = false) {
        val hadRows = rows.isNotEmpty()
        if (!hadRows) loading = true
        runCatching { api.ddns(force) }
            .onSuccess { latest ->
                rows = latest
                RouterControlMemoryCache.ddnsRows = latest
                error = ""
            }
            .onFailure { failure ->
                if (!hadRows) error = failure.message.orEmpty()
            }
        loading = false
    }
'''


def apply() -> None:
    text = ROUTER_CONTROL.read_text(encoding="utf-8")

    if "private object RouterControlMemoryCache" not in text:
        anchor = 'private val RouterPage = Color(0xFFF5F8FD)\n'
        if anchor not in text:
            raise RuntimeError("missing RouterPage anchor for DDNS memory cache")
        text = text.replace(anchor, anchor + CACHE_BLOCK, 1)

    start = text.find("private fun DdnsRecordsSection")
    end = text.find("\n@Composable\nprivate fun DdnsCard", start)
    if start < 0 or end < 0:
        raise RuntimeError("missing DdnsRecordsSection boundaries")

    section = text[start:end]
    if "RouterControlMemoryCache.ddnsRows" not in section:
        state_start = section.find("    var rows by remember")
        launch_start = section.find("    LaunchedEffect(Unit) { refresh() }", state_start)
        if state_start < 0 or launch_start < 0:
            raise RuntimeError("missing generated DDNS state or refresh boundary")
        section = section[:state_start] + STATE_AND_REFRESH_BLOCK + section[launch_start:]

    # Keep the shared cache synchronized after add/edit/toggle/delete operations.
    # The negative lookbehind avoids matching the suffix of ``ddnsRows`` itself.
    section = re.sub(
        r'''(?<![\w.])rows\s*=\s*it(?!\s*;\s*RouterControlMemoryCache\.ddnsRows\s*=\s*it)''',
        'rows = it; RouterControlMemoryCache.ddnsRows = it',
        section,
    )

    text = text[:start] + section + text[end:]
    ROUTER_CONTROL.write_text(text, encoding="utf-8")
    print("v0.10.15 DDNS cache hotfix applied")


if __name__ == "__main__":
    apply()
