#!/usr/bin/env python3
"""Build161: durable IPv6 mapping desired state and restored history release."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
PORTMAP = SRC / "PortMapping.kt"
STORE = SRC / "PortMappingRuleStore.kt"
MAIN = SRC / "MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build161 anchor: {label}")
    return text.replace(old, new, 1)


def patch_api(text: str) -> str:
    marker = "class PortMapApi(private val prefs: AppPrefs) {"
    snapshot = '''data class PortMapListSnapshot(
    val rules: List<PortMapRule>,
    val agent: PortMapAgentInfo,
    val rulesLoaded: Boolean,
    val rulesRevision: Long,
    val rulesUpdatedAt: String,
)

'''
    if snapshot not in text:
        text = text.replace(marker, snapshot + marker, 1)

    start = text.index("    suspend fun list(): Pair<List<PortMapRule>, PortMapAgentInfo>")
    end = text.index("\n    suspend fun create", start)
    method = '''    suspend fun list(): PortMapListSnapshot = withContext(Dispatchers.IO) {
        val root = JSONObject(get("/api/portmaps"))
        val range = root.optJSONObject("portRange") ?: JSONObject()
        val array = root.optJSONArray("rules") ?: JSONArray()
        val rows = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parsePortMapRule) }
        val agent = PortMapAgentInfo(
            online = root.optBoolean("agentOnline", false),
            router = cleanApiText(root.optString("router", "router")),
            lastSeenAt = cleanApiText(root.optString("agentLastSeenAt")),
            portMin = range.optInt("min", 20000),
            portMax = range.optInt("max", 20020),
            protocolVersion = cleanApiText(root.optString("protocolVersion")),
            hubVersion = cleanApiText(root.optString("hubVersion")),
            agentVersion = cleanApiText(root.optString("agentVersion")),
            relayVersion = cleanApiText(root.optString("relayVersion")),
            capabilities = compactPortCapabilities(root.opt("capabilities")),
            state = cleanApiText(root.optString("agentState")).ifBlank {
                if (root.optBoolean("agentOnline", false)) "online" else "offline"
            },
            ageSeconds = root.optLong("agentAgeSeconds", 0L),
        )
        PortMapListSnapshot(
            rules = rows,
            agent = agent,
            rulesLoaded = root.optBoolean("rulesLoaded", false),
            rulesRevision = root.optLong("rulesRevision", 0L).coerceAtLeast(0L),
            rulesUpdatedAt = cleanApiText(root.optString("rulesUpdatedAt")),
        )
    }
'''
    return text[:start] + method + text[end:]


def patch_screen(text: str) -> str:
    text = replace_once(
        text,
        '''private object PortMappingMemoryCache {
    var rules: List<PortMapRule> = emptyList()
    var devices: List<DeviceItem> = emptyList()
    var agent: PortMapAgentInfo? = null
}''',
        '''private object PortMappingMemoryCache {
    var rules: List<PortMapRule> = emptyList()
    var rulesRevision: Long = 0L
    var rulesUpdatedAt: String = ""
    var devices: List<DeviceItem> = emptyList()
    var agent: PortMapAgentInfo? = null
}''',
        "mapping memory revision",
    )

    old_setup = '''fun PortMappingScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { PortMapApi(prefs) }
    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val presenceStore = remember(prefs.hub, prefs.token, prefs.hubDns) { AgentPresenceStoreRegistry.get(prefs) }
    val liveAgent by presenceStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(PortMappingMemoryCache.rules) }
    var devices by remember { mutableStateOf(PortMappingMemoryCache.devices) }
    var agent by remember { mutableStateOf(PortMappingMemoryCache.agent ?: PortMapAgentInfo(false, "router", "", 20000, 20020)) }
    var loading by remember { mutableStateOf(PortMappingMemoryCache.agent == null && PortMappingMemoryCache.rules.isEmpty()) }'''
    new_setup = '''fun PortMappingScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { PortMapApi(prefs) }
    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val presenceStore = remember(prefs.hub, prefs.token, prefs.hubDns) { AgentPresenceStoreRegistry.get(prefs) }
    val liveAgent by presenceStore.state.collectAsState()
    val persistentRules = remember(prefs.hub, prefs.hubDns) { PortMappingRuleStore.load(context, prefs) }
    val initialRules = remember(prefs.hub, prefs.hubDns) {
        PortMappingMemoryCache.rules.ifEmpty { persistentRules.rules }
    }
    val scope = rememberCoroutineScope()
    var rules by remember(prefs.hub, prefs.hubDns) { mutableStateOf(initialRules) }
    var rulesRevision by remember(prefs.hub, prefs.hubDns) {
        mutableLongStateOf(maxOf(PortMappingMemoryCache.rulesRevision, persistentRules.revision))
    }
    var rulesUpdatedAt by remember(prefs.hub, prefs.hubDns) {
        mutableStateOf(PortMappingMemoryCache.rulesUpdatedAt.ifBlank { persistentRules.updatedAt })
    }
    var devices by remember { mutableStateOf(PortMappingMemoryCache.devices) }
    var agent by remember { mutableStateOf(PortMappingMemoryCache.agent ?: PortMapAgentInfo(false, "router", "", 20000, 20020)) }
    var loading by remember { mutableStateOf(PortMappingMemoryCache.agent == null && initialRules.isEmpty()) }'''
    text = replace_once(text, old_setup, new_setup, "persistent mapping setup")

    text = replace_once(
        text,
        '''    fun markSyncing(rule: PortMapRule, action: String) {''',
        '''    fun commitRulesLocally(next: List<PortMapRule>, revision: Long = rulesRevision, updatedAt: String = rulesUpdatedAt) {
        rules = next
        rulesRevision = revision.coerceAtLeast(rulesRevision)
        rulesUpdatedAt = updatedAt.ifBlank { rulesUpdatedAt }
        PortMappingMemoryCache.rules = next
        PortMappingMemoryCache.rulesRevision = rulesRevision
        PortMappingMemoryCache.rulesUpdatedAt = rulesUpdatedAt
        PortMappingRuleStore.save(context, prefs, next, rulesRevision, rulesUpdatedAt)
    }

    fun markSyncing(rule: PortMapRule, action: String) {''',
        "local mapping commit",
    )

    old_refresh = '''        runCatching {
            val (newRules, newAgent) = api.list()
            rules = newRules
            agent = newAgent
            PortMappingMemoryCache.rules = newRules
            PortMappingMemoryCache.agent = newAgent
            presenceStore.acceptHttp(newAgent)
            if (devices.isEmpty()) devices = deviceApi.getDevices(true)
            PortMappingMemoryCache.devices = devices
            message = ""
        }.onFailure { message = it.message ?: "加载失败" }'''
    new_refresh = '''        runCatching {
            val snapshot = api.list()
            val newAgent = snapshot.agent
            val explicitNewerEmpty = snapshot.rulesLoaded && snapshot.rules.isEmpty() && snapshot.rulesRevision > rulesRevision
            val mayAccept = snapshot.rules.isNotEmpty() || explicitNewerEmpty || (rules.isEmpty() && !persistentRules.hasDocument)
            if (mayAccept) {
                commitRulesLocally(snapshot.rules, snapshot.rulesRevision, snapshot.rulesUpdatedAt)
            }
            agent = newAgent
            PortMappingMemoryCache.agent = newAgent
            presenceStore.acceptHttp(newAgent)
            if (devices.isEmpty()) devices = deviceApi.getDevices(true)
            PortMappingMemoryCache.devices = devices
            message = if (!mayAccept && snapshot.rules.isEmpty() && rules.isNotEmpty()) {
                "Hub 本次未返回规则，已保留 APP 中的映射设置"
            } else ""
        }.onFailure {
            message = if (rules.isNotEmpty()) "映射状态暂未同步，已保留全部设置" else (it.message ?: "加载失败")
        }'''
    text = replace_once(text, old_refresh, new_refresh, "authoritative refresh")

    text = text.replace(
        '''                    runCatching { api.delete(selected.id) }
                        .onSuccess { selectedId = null }''',
        '''                    runCatching { api.delete(selected.id) }
                        .onSuccess {
                            commitRulesLocally(rules.filterNot { it.id == selected.id })
                            selectedId = null
                        }''',
        1,
    )

    old_save = '''                    runCatching {
                        if (draft.id.isBlank()) api.create(draft) else api.update(draft.id, draft)
                    }.onSuccess {
                        editDraft = null
                        refresh(true)
                    }.onFailure { message = it.message ?: "保存失败" }'''
    new_save = '''                    runCatching {
                        if (draft.id.isBlank()) api.create(draft) else api.update(draft.id, draft)
                    }.onSuccess { saved ->
                        val next = if (rules.any { it.id == saved.id }) {
                            rules.map { if (it.id == saved.id) saved else it }
                        } else rules + saved
                        commitRulesLocally(next)
                        editDraft = null
                        refresh(true)
                    }.onFailure { message = it.message ?: "保存失败" }'''
    text = replace_once(text, old_save, new_save, "save local rule")

    text = text.replace(
        'Text("暂无端口映射", fontWeight = FontWeight.Black, color = LabV2.Ink)',
        'Text("暂无端口映射设置", fontWeight = FontWeight.Black, color = LabV2.Ink)',
    )
    text = text.replace(
        'Text("创建 6→4 或 6→6 TCP 四层反代规则", fontSize = 10.5.sp, color = LabV2.InkMuted)',
        'Text("规则保存在 Hub 与 APP；Agent 离线不会删除设置", fontSize = 10.5.sp, color = LabV2.InkMuted)',
    )
    return text


def patch_version() -> None:
    gradle = GRADLE.read_text(encoding="utf-8")
    gradle = gradle.replace("versionCode = 160", "versionCode = 161")
    gradle = gradle.replace('versionName = "0.10.18"', 'versionName = "0.10.19"')
    GRADLE.write_text(gradle, encoding="utf-8")

    main = MAIN.read_text(encoding="utf-8")
    main = main.replace(
        '"v$NAME build$CODE · 状态闭环与后台任务"',
        '"v$NAME build$CODE · 终端历史与映射持久化"',
    )
    MAIN.write_text(main, encoding="utf-8")


def verify() -> None:
    combined = "\n".join(path.read_text(encoding="utf-8") for path in (PORTMAP, STORE, MAIN, GRADLE))
    required = (
        "data class PortMapListSnapshot",
        'rulesLoaded = root.optBoolean("rulesLoaded", false)',
        "PortMappingRuleStore.load(context, prefs)",
        "explicitNewerEmpty",
        "Hub 本次未返回规则，已保留 APP 中的映射设置",
        "映射状态暂未同步，已保留全部设置",
        "commitRulesLocally(rules.filterNot",
        "规则保存在 Hub 与 APP；Agent 离线不会删除设置",
        "versionCode = 161",
        'versionName = "0.10.19"',
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build161 verification failed: {missing}")
    forbidden = (
        "val (newRules, newAgent) = api.list()",
        "PortMappingMemoryCache.rules = newRules",
    )
    remaining = [value for value in forbidden if value in PORTMAP.read_text(encoding="utf-8")]
    if remaining:
        raise RuntimeError(f"mapping empty-overwrite path remains: {remaining}")


def apply() -> None:
    text = PORTMAP.read_text(encoding="utf-8")
    text = patch_api(text)
    text = patch_screen(text)
    PORTMAP.write_text(text, encoding="utf-8")
    patch_version()
    verify()
    print("build161 durable mapping rules and history release finalized")


if __name__ == "__main__":
    apply()
