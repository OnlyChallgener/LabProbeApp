#!/usr/bin/env python3
"""Finalize build160 Agent presence and ensure only fresh data renders green."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
WSS = SRC / "HubMqttClient.kt"
MAIN = SRC / "MainActivity.kt"
PORTMAP = SRC / "PortMapping.kt"
STORE = SRC / "AgentPresenceStore.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing Agent presence anchor: {label}")
    return text.replace(old, new, 1)


def patch_wss() -> None:
    text = WSS.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "    private val onTaskUpdate: (String) -> Unit = {},\n    private val onRealtimeReady: (Boolean) -> Unit = {},",
        "    private val onTaskUpdate: (String) -> Unit = {},\n    private val onAgentUpdate: (String) -> Unit = {},\n    private val onRealtimeReady: (Boolean) -> Unit = {},",
        "Agent callback",
    )
    text = replace_once(
        text,
        '''                        "task" -> if (data != null) onTaskUpdate(data.toString())
                        "ready" -> if (!readyReceived) {''',
        '''                        "task" -> if (data != null) onTaskUpdate(data.toString())
                        "agent" -> if (data != null) onAgentUpdate(data.toString())
                        "ready" -> if (!readyReceived) {''',
        "Agent frame",
    )
    WSS.write_text(text, encoding="utf-8")


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''        onTaskUpdate = { raw -> RouterTaskRepositoryRegistry.get(prefs).acceptRealtime(raw) },
        onRealtimeReady = { reconnect ->''',
        '''        onTaskUpdate = { raw -> RouterTaskRepositoryRegistry.get(prefs).acceptRealtime(raw) },
        onAgentUpdate = { raw -> AgentPresenceStoreRegistry.get(prefs).acceptRealtime(raw) },
        onRealtimeReady = { reconnect ->''',
        "Agent delivery",
    )
    text = text.replace(
        'color = if (state.mqttConnected) LabV2.Green else LabV2.InkMuted',
        'color = if (state.realtimeDataFresh) LabV2.Green else if (state.mqttConnected) LabV2.Amber else LabV2.InkMuted',
    )
    text = text.replace(
        'tint = if (state.mqttConnected) LabV2.Green else LabV2.InkMuted',
        'tint = if (state.realtimeDataFresh) LabV2.Green else if (state.mqttConnected) LabV2.Amber else LabV2.InkMuted',
    )
    MAIN.write_text(text, encoding="utf-8")


def patch_portmap() -> None:
    text = PORTMAP.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''    val relayVersion: String = "",
    val capabilities: String = ""
)''',
        '''    val relayVersion: String = "",
    val capabilities: String = "",
    val state: String = if (online) "online" else "offline",
    val ageSeconds: Long = 0L
)''',
        "Agent state fields",
    )
    text = replace_once(
        text,
        '''            relayVersion = cleanApiText(root.optString("relayVersion")),
            capabilities = compactPortCapabilities(root.opt("capabilities"))
        )''',
        '''            relayVersion = cleanApiText(root.optString("relayVersion")),
            capabilities = compactPortCapabilities(root.opt("capabilities")),
            state = cleanApiText(root.optString("agentState")).ifBlank {
                if (root.optBoolean("agentOnline", false)) "online" else "offline"
            },
            ageSeconds = root.optLong("agentAgeSeconds", 0L)
        )''',
        "Agent HTTP parsing",
    )
    text = replace_once(
        text,
        '''    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(PortMappingMemoryCache.rules) }''',
        '''    val deviceApi = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    val presenceStore = remember(prefs.hub, prefs.token, prefs.hubDns) { AgentPresenceStoreRegistry.get(prefs) }
    val liveAgent by presenceStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(PortMappingMemoryCache.rules) }''',
        "Agent store",
    )
    text = replace_once(
        text,
        '''            PortMappingMemoryCache.rules = newRules
            PortMappingMemoryCache.agent = newAgent
            if (devices.isEmpty()) devices = deviceApi.getDevices(true)''',
        '''            PortMappingMemoryCache.rules = newRules
            PortMappingMemoryCache.agent = newAgent
            presenceStore.acceptHttp(newAgent)
            if (devices.isEmpty()) devices = deviceApi.getDevices(true)''',
        "accept HTTP presence",
    )
    text = replace_once(
        text,
        '''    LaunchedEffect(Unit) { refresh(silent = rules.isNotEmpty() || PortMappingMemoryCache.agent != null) }

    val visible = rules.filter {''',
        '''    LaunchedEffect(Unit) { refresh(silent = rules.isNotEmpty() || PortMappingMemoryCache.agent != null) }
    LaunchedEffect(liveAgent) {
        liveAgent?.let {
            agent = it
            PortMappingMemoryCache.agent = it
        }
    }

    val visible = rules.filter {''',
        "observe Agent presence",
    )
    text = replace_once(
        text,
        '''private fun PortMapAgentCard(agent: PortMapAgentInfo, loading: Boolean, onRefresh: () -> Unit) {
    val color = if (agent.online) PortGreen else PortRed''',
        '''private fun PortMapAgentCard(agent: PortMapAgentInfo, loading: Boolean, onRefresh: () -> Unit) {
    val presenceState = agent.state.ifBlank { if (agent.online) "online" else "offline" }
    val color = when (presenceState) {
        "online" -> PortGreen
        "stale" -> PortAmber
        else -> PortRed
    }''',
        "Agent card color",
    )
    text = replace_once(
        text,
        '''                    Text(if (agent.online) "Agent 在线" else "Agent 未连接", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (agent.lastSeenAt.isNotBlank()) Text(" · ${agent.lastSeenAt}", fontSize = 9.3.sp, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)''',
        '''                    Text(
                        when (presenceState) {
                            "online" -> "Agent 在线"
                            "stale" -> "Agent 状态稍旧"
                            else -> "Agent 未连接"
                        },
                        color = color,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (agent.lastSeenAt.isNotBlank()) Text(" · ${agent.lastSeenAt}", fontSize = 9.3.sp, color = LabV2.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)''',
        "Agent card wording",
    )
    PORTMAP.write_text(text, encoding="utf-8")


def verify() -> None:
    combined = "\n".join(path.read_text(encoding="utf-8") for path in (WSS, MAIN, PORTMAP, STORE))
    required = (
        "onAgentUpdate: (String) -> Unit",
        '"agent" -> if (data != null) onAgentUpdate(data.toString())',
        "AgentPresenceStoreRegistry.get(prefs).acceptRealtime(raw)",
        "val liveAgent by presenceStore.state.collectAsState()",
        "presenceStore.acceptHttp(newAgent)",
        '"stale" -> "Agent 状态稍旧"',
        "state.realtimeDataFresh",
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"Agent presence verification failed: {missing}")
    forbidden = (
        "color = if (state.mqttConnected) LabV2.Green",
        "tint = if (state.mqttConnected) LabV2.Green",
    )
    remaining = [value for value in forbidden if value in combined]
    if remaining:
        raise RuntimeError(f"false green realtime state remains: {remaining}")


def apply() -> None:
    patch_wss()
    patch_main()
    patch_portmap()
    verify()
    print("build160 Agent presence and truthful realtime colors finalized")


if __name__ == "__main__":
    apply()
