package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortMapSnapshotAcceptanceTest {
    private val agent = PortMapAgentInfo(false, "Router", "", 20_000, 20_020)

    @Test
    fun unknownEmptySnapshotNeverErasesPersistedRules() {
        val snapshot = PortMapListSnapshot(
            rules = emptyList(),
            agent = agent,
            rulesLoaded = false,
            rulesRevision = 12,
            rulesUpdatedAt = "",
            revision = 12,
        )

        assertFalse(
            shouldAcceptPortMapSnapshot(
                snapshot,
                currentRules = emptyList(),
                currentRulesRevision = 11,
                currentSnapshotRevision = 11,
                hasPersistedDocument = true,
            ),
        )
    }

    @Test
    fun unknownNonEmptySnapshotNeverReplacesPersistedRules() {
        val snapshot = PortMapListSnapshot(
            rules = listOf(sampleRule("map-1")),
            agent = agent,
            rulesLoaded = false,
            rulesRevision = 12,
            rulesUpdatedAt = "",
            revision = 12,
        )

        assertFalse(
            shouldAcceptPortMapSnapshot(
                snapshot,
                currentRules = listOf(sampleRule("old-map")),
                currentRulesRevision = 11,
                currentSnapshotRevision = 11,
                hasPersistedDocument = true,
            ),
        )
    }

    @Test
    fun explicitNewerEmptySnapshotConfirmsAllRulesWereDeleted() {
        val snapshot = PortMapListSnapshot(
            rules = emptyList(),
            agent = agent,
            rulesLoaded = true,
            rulesRevision = 12,
            rulesUpdatedAt = "",
            revision = 12,
        )

        assertTrue(
            shouldAcceptPortMapSnapshot(
                snapshot,
                currentRules = emptyList(),
                currentRulesRevision = 11,
                currentSnapshotRevision = 11,
                hasPersistedDocument = true,
            ),
        )
    }

    @Test
    fun fullPortRangeStillPrefersUnprivilegedDefault() {
        val fullRange = PortMapAgentInfo(false, "Router", "", 1, 65535)
        assertEquals(20_000, nextPort(emptyList(), fullRange))
        assertEquals(20_001, nextPort(listOf(sampleRule("used")), fullRange))
    }

    @Test
    fun serviceIconsReuseFavoritePresetsByProtocol() {
        assertEquals("ssh", portMapFavoriteIconId("SSH"))
        assertEquals("windows", portMapFavoriteIconId("RDP"))
        assertEquals("wireguard", portMapFavoriteIconId("WireGuard"))
        assertEquals("openvpn", portMapFavoriteIconId("OpenVPN"))
        assertEquals("web", portMapFavoriteIconId("HTTPS"))
        assertEquals("web", portMapFavoriteIconId("DNS"))
    }

    private fun sampleRule(id: String) = PortMapRule(
        id = id,
        name = "测试映射",
        enabled = true,
        mode = "6to4",
        listenPort = 20_000,
        targetMode = "ipv4",
        targetIpv4 = "192.168.1.10",
        targetIpv6 = "",
        targetIpv6Suffix = "",
        targetMac = "aa:bb:cc:dd:ee:ff",
        targetPort = 80,
        preferCurrentPrefix = true,
        expiresAt = null,
        leaseSeconds = 0L,
        maxConnections = 32,
        idleTimeoutSec = 300,
    )
}
