package com.labprobe.app

/**
 * Process-memory cache for router control data that changes slowly.
 *
 * The UI always renders the last successful value first and refreshes it in the
 * background. Reconnection, navigation and transient HTTP failures must never
 * replace usable data with empty placeholders.
 */
internal object RouterSlowDataCache {
    const val STATUS_TTL_MS = 15_000L
    const val MAPPING_TTL_MS = 30_000L
    const val SETTINGS_TTL_MS = 60_000L

    private var scopeKey: String = ""

    var hubStatus: RouterHubStatus? = null
    var hubStatusAt: Long = 0L
    var capabilities: RouterCapabilities? = null
    var capabilitiesAt: Long = 0L

    var portMappings: List<NativePortMapRule> = emptyList()
    var portMappingsLoaded: Boolean = false
    var portMappingsAt: Long = 0L

    var upnpState: UpnpState? = null
    var upnpAt: Long = 0L

    var firewallState: FirewallState? = null
    var firewallAt: Long = 0L

    var ddnsRows: List<DdnsRecord> = emptyList()
    var ddnsLoaded: Boolean = false
    var ddnsAt: Long = 0L

    fun ensureScope(hub: String, token: String) {
        val next = hub.trim().trimEnd('/') + "|" + token.hashCode()
        if (scopeKey == next) return
        scopeKey = next
        clearValues()
    }

    fun isFresh(updatedAt: Long, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        updatedAt > 0L && now - updatedAt in 0L..ttlMs

    private fun clearValues() {
        hubStatus = null
        hubStatusAt = 0L
        capabilities = null
        capabilitiesAt = 0L

        portMappings = emptyList()
        portMappingsLoaded = false
        portMappingsAt = 0L

        upnpState = null
        upnpAt = 0L

        firewallState = null
        firewallAt = 0L

        ddnsRows = emptyList()
        ddnsLoaded = false
        ddnsAt = 0L
    }
}
