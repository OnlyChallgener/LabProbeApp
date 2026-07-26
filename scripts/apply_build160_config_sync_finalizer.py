#!/usr/bin/env python3
"""Finalize build160 persistent config synchronization over the single WSS."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
WSS = SRC / "HubMqttClient.kt"
MAIN = SRC / "MainActivity.kt"
API = SRC / "RouterControlApi.kt"
REPOSITORY = SRC / "RouterRepository.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing config sync anchor: {label}")
    return text.replace(old, new, 1)


def patch_wss() -> None:
    text = WSS.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "    private val onTaskUpdate: (String) -> Unit = {},\n    private val onAgentUpdate: (String) -> Unit = {},",
        "    private val onTaskUpdate: (String) -> Unit = {},\n    private val onConfigUpdate: (String) -> Unit = {},\n    private val onAgentUpdate: (String) -> Unit = {},",
        "config callback",
    )
    text = replace_once(
        text,
        '''                        "task" -> if (data != null) onTaskUpdate(data.toString())
                        "agent" -> if (data != null) onAgentUpdate(data.toString())''',
        '''                        "task" -> if (data != null) onTaskUpdate(data.toString())
                        "config" -> if (data != null) onConfigUpdate(data.toString())
                        "agent" -> if (data != null) onAgentUpdate(data.toString())''',
        "config frame",
    )
    WSS.write_text(text, encoding="utf-8")


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''        onTaskUpdate = { raw -> RouterTaskRepositoryRegistry.get(prefs).acceptRealtime(raw) },
        onAgentUpdate = { raw -> AgentPresenceStoreRegistry.get(prefs).acceptRealtime(raw) },''',
        '''        onTaskUpdate = { raw -> RouterTaskRepositoryRegistry.get(prefs).acceptRealtime(raw) },
        onConfigUpdate = { raw -> RouterRepositoryRegistry.get(prefs).acceptConfigRealtime(raw) },
        onAgentUpdate = { raw -> AgentPresenceStoreRegistry.get(prefs).acceptRealtime(raw) },''',
        "config delivery",
    )
    MAIN.write_text(text, encoding="utf-8")


def patch_api() -> None:
    text = API.read_text(encoding="utf-8")
    for name in ("parseNativePortRules", "parseUpnp", "parseFirewall", "parseDdnsList"):
        text = text.replace(f"private fun {name}", f"internal fun {name}")
    API.write_text(text, encoding="utf-8")


def patch_repository() -> None:
    text = REPOSITORY.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "import kotlinx.coroutines.flow.asStateFlow\n",
        "import kotlinx.coroutines.flow.asStateFlow\nimport org.json.JSONObject\n",
        "JSONObject import",
    )
    text = replace_once(
        text,
        "    private val generations = ConcurrentHashMap<String, AtomicLong>()\n",
        "    private val generations = ConcurrentHashMap<String, AtomicLong>()\n"
        "    private val configRevisions = ConcurrentHashMap<String, AtomicLong>()\n",
        "config revisions",
    )
    method = r'''    fun acceptConfigRealtime(raw: String) {
        scope.launch {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            val resource = root.optString("resource")
            val data = root.optJSONObject("data") ?: return@launch
            val source = root.optString("source", "sync")
            val revision = root.optLong("revision", 0L)
            val frameAt = root.optLong("updatedAt", 0L).let { if (it > 0L) it * 1000L else System.currentTimeMillis() }
            val revisionKey = when (resource) {
                "portMappings" -> "portMappings"
                "firewall", "ddns", "upnp" -> resource
                else -> return@launch
            }
            val seen = configRevisions.getOrPut(revisionKey) { AtomicLong(0L) }
            if (revision > 0L && revision <= seen.get()) return@launch
            when (resource) {
                "ddns" -> {
                    val old = _ddns.value
                    if (old.mutating && source != "command") return@launch
                    if (source != "command" && old.updatedAt > frameAt) return@launch
                    val seq = sequence("ddns").incrementAndGet()
                    applyDdnsRead(seq, parseDdnsList(data))
                }
                "upnp" -> {
                    val old = _upnp.value
                    if (old.mutating && source != "command") return@launch
                    if (source != "command" && old.updatedAt > frameAt) return@launch
                    val seq = sequence("upnp").incrementAndGet()
                    applyUpnp(seq, parseUpnp(data))
                }
                "portMappings" -> {
                    val old = _portMappings.value
                    if (old.mutating && source != "command") return@launch
                    if (source != "command" && old.updatedAt > frameAt) return@launch
                    val seq = sequence("portMappings").incrementAndGet()
                    applyPortMappings(seq, parseNativePortRules(data))
                }
                "firewall" -> {
                    val old = _firewall.value
                    if (old.mutating && source != "command") return@launch
                    if (source != "command" && old.updatedAt > frameAt) return@launch
                    val seq = sequence("firewall").incrementAndGet()
                    applyFirewall(seq, parseFirewall(data))
                }
            }
            if (revision > 0L) seen.updateAndGet { previous -> maxOf(previous, revision) }
        }
    }

'''
    text = replace_once(text, "    fun start() {\n", method + "    fun start() {\n", "config receiver")
    REPOSITORY.write_text(text, encoding="utf-8")


def verify() -> None:
    combined = "\n".join(path.read_text(encoding="utf-8") for path in (WSS, MAIN, API, REPOSITORY))
    required = (
        "onConfigUpdate: (String) -> Unit",
        '"config" -> if (data != null) onConfigUpdate(data.toString())',
        "RouterRepositoryRegistry.get(prefs).acceptConfigRealtime(raw)",
        "fun acceptConfigRealtime(raw: String)",
        "private val configRevisions",
        'old.mutating && source != "command"',
        "internal fun parseNativePortRules",
        "internal fun parseUpnp",
        "internal fun parseFirewall",
        "internal fun parseDdnsList",
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"config WSS verification failed: {missing}")


def apply() -> None:
    patch_wss()
    patch_main()
    patch_api()
    patch_repository()
    verify()
    print("build160 persistent router config WSS synchronization finalized")


if __name__ == "__main__":
    apply()
