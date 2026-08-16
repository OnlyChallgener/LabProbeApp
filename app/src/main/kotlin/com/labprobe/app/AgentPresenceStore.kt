package com.labprobe.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal class AgentPresenceStore {
    private val _state = MutableStateFlow<PortMapAgentInfo?>(null)
    val state: StateFlow<PortMapAgentInfo?> = _state.asStateFlow()

    fun acceptHttp(value: PortMapAgentInfo) {
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
    }

    fun acceptRealtime(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val previous = _state.value
        val stateName = root.optString("agentState").ifBlank {
            if (root.optBoolean("agentOnline", false)) "online" else "offline"
        }
        val next = PortMapAgentInfo(
            online = stateName == "online",
            router = root.optString("router").ifBlank { previous?.router ?: "router" },
            lastSeenAt = root.optString("agentLastSeenAt").ifBlank { previous?.lastSeenAt.orEmpty() },
            portMin = previous?.portMin ?: 20000,
            portMax = previous?.portMax ?: 20020,
            protocolVersion = previous?.protocolVersion.orEmpty(),
            hubVersion = previous?.hubVersion.orEmpty(),
            agentVersion = root.optString("agentVersion").ifBlank { previous?.agentVersion.orEmpty() },
            capabilities = previous?.capabilities.orEmpty(),
            state = stateName,
            ageSeconds = root.optLong("agentAgeSeconds", previous?.ageSeconds ?: 0L),
            lastSeenEpoch = root.optLong("agentLastSeenEpoch", previous?.lastSeenEpoch ?: 0L),
            revision = root.optLong("agentRevision", previous?.revision ?: 0L),
        )
        accept(next)
    }

    private fun merge(previous: PortMapAgentInfo?, next: PortMapAgentInfo): PortMapAgentInfo = next.copy(
        router = next.router.ifBlank { previous?.router ?: "router" },
        lastSeenAt = next.lastSeenAt.ifBlank { previous?.lastSeenAt.orEmpty() },
        protocolVersion = next.protocolVersion.ifBlank { previous?.protocolVersion.orEmpty() },
        hubVersion = next.hubVersion.ifBlank { previous?.hubVersion.orEmpty() },
        agentVersion = next.agentVersion.ifBlank { previous?.agentVersion.orEmpty() },
        capabilities = next.capabilities.ifBlank { previous?.capabilities.orEmpty() }
    )
}

internal object AgentPresenceStoreRegistry {
    private val stores = ConcurrentHashMap<String, AgentPresenceStore>()

    fun get(prefs: AppPrefs): AgentPresenceStore {
        val key = listOf(prefs.hub.trim(), prefs.token.trim(), prefs.hubDns.trim()).joinToString("|")
        return stores.getOrPut(key) { AgentPresenceStore() }
    }
}
