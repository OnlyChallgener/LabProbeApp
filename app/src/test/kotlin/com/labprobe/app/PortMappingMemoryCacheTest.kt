package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PortMappingMemoryCacheTest {
    @Test
    fun routerSwitchDoesNotReuseRulesAgentOrWarmDevices() {
        val be72 = PortMappingMemoryCache.forScope("http://cache-be72:58443", "token-be72")
        val be50 = PortMappingMemoryCache.forScope("http://cache-be50:58444", "token-be50")
        be72.rules = listOf(rule("be72-rule"))
        be72.rulesRevision = 92L
        be72.snapshotRevision = 105L
        be72.agent = PortMapAgentInfo(true, "BE72", "now", 20000, 20020)
        be72.devices = listOf(device("BE72 NAS"))
        be72.devicesUpdatedAt = System.currentTimeMillis()

        assertNotSame(be72, be50)
        assertTrue(be50.rules.isEmpty())
        assertEquals(0L, be50.rulesRevision)
        assertEquals(0L, be50.snapshotRevision)
        assertNull(be50.agent)
        assertFalse(be50.isDevicesFresh())
        assertTrue(be50.devices.isEmpty())
        assertSame(be72, PortMappingMemoryCache.forScope("http://cache-be72:58443/", "token-be72"))
    }

    @Test
    fun differentTokensOnSameHubHaveIndependentCache() {
        val first = PortMappingMemoryCache.forScope("http://cache-token:58444", "Aa")
        val second = PortMappingMemoryCache.forScope("http://cache-token:58444", "BB")
        first.rules = listOf(rule("first-router"))
        first.agent = PortMapAgentInfo(true, "BE72", "now", 20000, 20020)

        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotSame(first, second)
        assertTrue(second.rules.isEmpty())
        assertNull(second.agent)
    }

    @Test
    fun delayedResponseKeepsWritingItsCapturedScope() {
        val requested = PortMappingMemoryCache.forScope("http://cache-delayed-a:58443", "a")
        val active = PortMappingMemoryCache.forScope("http://cache-delayed-b:58444", "b")
        active.rules = listOf(rule("active-rule"))
        active.devices = listOf(device("BE50 device"))

        requested.rules = listOf(rule("late-be72-rule"))
        requested.rulesRevision = 999L
        requested.devices = listOf(device("late BE72 device"))
        requested.agent = PortMapAgentInfo(true, "BE72", "late", 20000, 20020)

        assertEquals(listOf("active-rule"), active.rules.map { it.id })
        assertEquals(listOf("BE50 device"), active.devices.map { it.name })
        assertEquals(0L, active.rulesRevision)
        assertNull(active.agent)
    }

    private fun rule(id: String) = PortMapRule(
        id = id, name = id, enabled = true, mode = "6to4", listenPort = 20000,
        targetMode = "ipv4", targetIpv4 = "192.168.5.46", targetIpv6 = "",
        targetIpv6Suffix = "", targetMac = "", targetPort = 443,
        preferCurrentPrefix = true, expiresAt = null, leaseSeconds = 0L,
        maxConnections = 32, idleTimeoutSec = 300,
    )

    private fun device(name: String) = DeviceItem(
        name = name, mac = "aa:bb:cc:dd:ee:ff", online = true, ip = "192.168.5.46",
        ssid = "", band = "", rssi = "", rxrate = "", onlineSince = "", offlineAt = "",
        onlineDurationText = "", lastSeenAt = "",
    )
}
