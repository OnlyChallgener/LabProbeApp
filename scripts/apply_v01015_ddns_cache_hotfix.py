#!/usr/bin/env python3
"""Apply the v0.10.15 DDNS cache fix after the generated router UI rewrite.

The base router UI generator expands ``DdnsRecordsSection`` before the v0.10.15
patches run, so the older compact-text matcher is no longer valid.  This patch
works only inside the DDNS section and is safe to run repeatedly.
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

REFRESH_BLOCK = '''    var rows by remember { mutableStateOf(RouterControlMemoryCache.ddnsRows) }
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
        expanded_pattern = re.compile(
            r'''    var rows by remember \{ mutableStateOf<List<DdnsRecord>>\(emptyList\(\)\) \}\n'''
            r'''    var loading by remember \{ mutableStateOf\(true\) \}\n'''
            r'''    var error by remember \{ mutableStateOf\(""\) \}\n'''
            r'''    var editing by remember \{ mutableStateOf<DdnsRecord\?>\(null\) \}\n'''
            r'''    var adding by remember \{ mutableStateOf\(false\) \}\n'''
            r'''    var deleteTarget by remember \{ mutableStateOf<DdnsRecord\?>\(null\) \}\n'''
            r'''    suspend fun refresh\(force: Boolean = false\) \{\n'''
            r'''        if \(!force\) loading = true\n'''
            r'''        runCatching \{ api\.ddns\(force\) \}\.onSuccess \{ rows = it; error = "" \}\.onFailure \{ error = it\.message\.orEmpty\(\) \}\n'''
            r'''        loading = false\n'''
            r'''    \}\n'''
        )
        compact_pattern = re.compile(
            r'''    var rows by remember \{ mutableStateOf<List<DdnsRecord>>\(emptyList\(\)\) \}\n'''
            r'''    var loading by remember \{ mutableStateOf\(true\) \}\n'''
            r'''    var error by remember \{ mutableStateOf\(""\) \}\n'''
            r'''    var editing by remember \{ mutableStateOf<DdnsRecord\?>\(null\) \}\n'''
            r'''    var adding by remember \{ mutableStateOf\(false\) \}\n'''
            r'''    var deleteTarget by remember \{ mutableStateOf<DdnsRecord\?>\(null\) \}\n'''
            r'''    suspend fun refresh\(force:Boolean=false\)\{ if\(!force\)loading=true;runCatching\{api\.ddns\(force\)\}\.onSuccess\{rows=it;error=""\}\.onFailure\{error=it\.message\.orEmpty\(\)\};loading=false \}\n'''
        )
        section, count = expanded_pattern.subn(REFRESH_BLOCK, section, count=1)
        if count == 0:
            section, count = compact_pattern.subn(REFRESH_BLOCK, section, count=1)
        if count == 0:
            raise RuntimeError("missing generated DDNS refresh block")

    # Every successful DDNS write must update the shared runtime cache as well.
    section = re.sub(
        r'''rows\s*=\s*it(?!\s*;\s*RouterControlMemoryCache\.ddnsRows\s*=\s*it)''',
        'rows = it; RouterControlMemoryCache.ddnsRows = it',
        section,
    )

    text = text[:start] + section + text[end:]
    ROUTER_CONTROL.write_text(text, encoding="utf-8")
    print("v0.10.15 DDNS cache hotfix applied")


if __name__ == "__main__":
    apply()
