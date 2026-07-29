#!/usr/bin/env python3
"""Build167: monotonic Hub revisions for Agent presence and port-map runtime."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
STORE = SRC / "AgentPresenceStore.kt"
PORTMAP = SRC / "PortMapping.kt"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build167 anchor: {label}")
    return text.replace(old, new, 1)


def patch_store() -> None:
    text = STORE.read_text(encoding="utf-8")
    text = replace_once(text, '''    fun acceptHttp(value: PortMapAgentInfo) {
        _state.value = merge(_state.value, value)
    }''', '''    fun acceptHttp(value: PortMapAgentInfo) {
        accept(value)
    }

    private fun accept(next: PortMapAgentInfo) {
        val previous = _state.value
        if (previous != null && !isNewer(previous, next)) return
        _state.value = merge(previous, next)
    }

    private fun isNewer(previous: PortMapAgentInfo, next: PortMapAgentInfo): Boolean {
        if (next.revision > 0L || previous.revision > 0L) return next.revision >= previous.revision
        return next.lastSeenEpoch >= previous.lastSeenEpoch
    }''', "presence monotonic accept")
    text = replace_once(text, '''        _state.value = merge(previous, next)
    }

    private fun merge''', '''        accept(next)
    }

    private fun merge''', "realtime uses monotonic accept")
    text = replace_once(text, '''            ageSeconds = root.optLong("agentAgeSeconds", previous?.ageSeconds ?: 0L)
        )''', '''            ageSeconds = root.optLong("agentAgeSeconds", previous?.ageSeconds ?: 0L),
            lastSeenEpoch = root.optLong("agentLastSeenEpoch", previous?.lastSeenEpoch ?: 0L),
            revision = root.optLong("agentRevision", previous?.revision ?: 0L),
        )''', "realtime revision parsing")
    STORE.write_text(text, encoding="utf-8")


def patch_portmap() -> None:
    text = PORTMAP.read_text(encoding="utf-8")
    text = replace_once(text, '''    val state: String = if (online) "online" else "offline",
    val ageSeconds: Long = 0L
)''', '''    val state: String = if (online) "online" else "offline",
    val ageSeconds: Long = 0L,
    val lastSeenEpoch: Long = 0L,
    val revision: Long = 0L,
)''', "agent revision fields")
    text = replace_once(text, '''    val rulesRevision: Long,
    val rulesUpdatedAt: String,
)''', '''    val rulesRevision: Long,
    val rulesUpdatedAt: String,
    val revision: Long,
)''', "snapshot revision field")
    text = replace_once(text, '''            ageSeconds = root.optLong("agentAgeSeconds", 0L),
        )''', '''            ageSeconds = root.optLong("agentAgeSeconds", 0L),
            lastSeenEpoch = root.optLong("agentLastSeenEpoch", 0L),
            revision = root.optLong("agentRevision", 0L),
        )''', "HTTP presence revision")
    text = replace_once(text, '''            rulesUpdatedAt = cleanApiText(root.optString("rulesUpdatedAt")),
        )''', '''            rulesUpdatedAt = cleanApiText(root.optString("rulesUpdatedAt")),
            revision = root.optLong("revision", 0L).coerceAtLeast(0L),
        )''', "HTTP runtime revision")
    text = replace_once(text, '''    var rulesUpdatedAt: String = ""
    var devices: List<DeviceItem> = emptyList()''', '''    var rulesUpdatedAt: String = ""
    var snapshotRevision: Long = 0L
    var devices: List<DeviceItem> = emptyList()''', "memory runtime revision")
    text = replace_once(text, '''    var rulesUpdatedAt by remember(prefs.hub, prefs.hubDns) {
        mutableStateOf(PortMappingMemoryCache.rulesUpdatedAt.ifBlank { persistentRules.updatedAt })
    }''', '''    var rulesUpdatedAt by remember(prefs.hub, prefs.hubDns) {
        mutableStateOf(PortMappingMemoryCache.rulesUpdatedAt.ifBlank { persistentRules.updatedAt })
    }
    var snapshotRevision by remember(prefs.hub, prefs.hubDns) {
        mutableLongStateOf(PortMappingMemoryCache.snapshotRevision)
    }''', "screen runtime revision")
    text = replace_once(text, '''    fun commitRulesLocally(next: List<PortMapRule>, revision: Long = rulesRevision, updatedAt: String = rulesUpdatedAt) {
        rules = next
        rulesRevision = revision.coerceAtLeast(rulesRevision)
        rulesUpdatedAt = updatedAt.ifBlank { rulesUpdatedAt }
        PortMappingMemoryCache.rules = next
        PortMappingMemoryCache.rulesRevision = rulesRevision
        PortMappingMemoryCache.rulesUpdatedAt = rulesUpdatedAt
        PortMappingRuleStore.save(context, prefs, next, rulesRevision, rulesUpdatedAt)
    }''', '''    fun commitRulesLocally(next: List<PortMapRule>, revision: Long = rulesRevision, updatedAt: String = rulesUpdatedAt, sourceRevision: Long = snapshotRevision) {
        rules = next
        rulesRevision = revision.coerceAtLeast(rulesRevision)
        snapshotRevision = sourceRevision.coerceAtLeast(snapshotRevision)
        rulesUpdatedAt = updatedAt.ifBlank { rulesUpdatedAt }
        PortMappingMemoryCache.rules = next
        PortMappingMemoryCache.rulesRevision = rulesRevision
        PortMappingMemoryCache.snapshotRevision = snapshotRevision
        PortMappingMemoryCache.rulesUpdatedAt = rulesUpdatedAt
        PortMappingRuleStore.save(context, prefs, next, rulesRevision, rulesUpdatedAt)
    }''', "commit runtime revision")
    text = replace_once(text, '''            val explicitNewerEmpty = snapshot.rulesLoaded && snapshot.rules.isEmpty() && snapshot.rulesRevision > rulesRevision
            val mayAccept = snapshot.rules.isNotEmpty() || explicitNewerEmpty || (rules.isEmpty() && !persistentRules.hasDocument)
            if (mayAccept) {
                commitRulesLocally(snapshot.rules, snapshot.rulesRevision, snapshot.rulesUpdatedAt)
            }''', '''            val explicitNewerEmpty = snapshot.rulesLoaded && snapshot.rules.isEmpty() && snapshot.rulesRevision > rulesRevision
            val sourceIsCurrent = snapshot.revision >= snapshotRevision
            val mayAccept = sourceIsCurrent && (snapshot.rules.isNotEmpty() || explicitNewerEmpty || (rules.isEmpty() && !persistentRules.hasDocument))
            if (mayAccept) {
                commitRulesLocally(snapshot.rules, snapshot.rulesRevision, snapshot.rulesUpdatedAt, snapshot.revision)
            }''', "reject stale HTTP runtime")
    PORTMAP.write_text(text, encoding="utf-8")


def patch_version() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    gradle = gradle.replace("versionCode = 166", "versionCode = 167")
    gradle = gradle.replace('versionName = "0.10.24"', 'versionName = "0.10.25"')
    GRADLE.write_text(gradle, encoding="utf-8")


def apply() -> None:
    patch_store()
    patch_portmap()
    patch_version()
    print("build167 Hub revision ordering and stale-runtime rejection applied")


if __name__ == "__main__":
    apply()
