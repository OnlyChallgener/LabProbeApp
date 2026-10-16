package com.labprobe.app

/**
 * Process-memory cache for router control data that changes slowly.
 *
 * The UI always renders the last successful value first and refreshes it in the
 * background. Reconnection, navigation and transient HTTP failures must never
 * replace usable data with empty placeholders.
 */
internal class RouterSlowDataCache private constructor() {
    val STATUS_TTL_MS = 15_000L
    val MAPPING_TTL_MS = 30_000L
    val SETTINGS_TTL_MS = 60_000L

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

    fun isFresh(updatedAt: Long, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        updatedAt > 0L && now - updatedAt in 0L..ttlMs

    companion object {
        private val scopes = java.util.concurrent.ConcurrentHashMap<String, RouterSlowDataCache>()

        fun forScope(hub: String, token: String): RouterSlowDataCache =
            scopes.computeIfAbsent(workspaceDigest(hub.trim().trimEnd('/') + "|" + token)) { RouterSlowDataCache() }
    }
}
