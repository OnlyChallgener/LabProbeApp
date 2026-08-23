package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardClientTest {
    @Test
    fun parsesIpv4AndBracketedIpv6Endpoints() {
        assertEquals("203.0.113.8" to 51820, parseWireGuardEndpoint("203.0.113.8:51820"))
        assertEquals("2001:db8::5" to 45000, parseWireGuardEndpoint("[2001:db8::5]:45000"))
        assertEquals("[2001:db8::5]:51820", formatWireGuardEndpoint("2001:db8::5", 51820))
    }

    @Test
    fun ddnsAndStunEventsCannotOverwriteEachOther() {
        val ddns = WireGuardProfile(id = "ddns", name = "DDNS", endpointSource = WireGuardEndpointSource.DDNS, endpointHost = "wg.example.com", endpointRevision = 4)
        val stun = WireGuardProfile(id = "stun", name = "STUN", endpointSource = WireGuardEndpointSource.STUN, endpointHost = "203.0.113.8", endpointRevision = 4)

        assertTrue(canApplyWireGuardEndpointUpdate(ddns, WireGuardEndpointSource.DDNS, 5))
        assertFalse(canApplyWireGuardEndpointUpdate(ddns, WireGuardEndpointSource.STUN, 5))
        assertFalse(canApplyWireGuardEndpointUpdate(stun, WireGuardEndpointSource.DDNS, 5))
        assertFalse(canApplyWireGuardEndpointUpdate(stun, WireGuardEndpointSource.STUN, 4))
    }

    @Test
    fun manualProfilesAreNeverEligibleForAutomaticEndpointUpdates() {
        val manual = WireGuardProfile(
            id = "manual",
            name = "我的配置",
            endpointSource = WireGuardEndpointSource.MANUAL,
            endpointHost = "vpn.example.com",
        )
        assertFalse(canApplyWireGuardEndpointUpdate(manual, WireGuardEndpointSource.DDNS, 1))
        assertFalse(canApplyWireGuardEndpointUpdate(manual, WireGuardEndpointSource.STUN, 1))
        assertFalse(canApplyWireGuardEndpointUpdate(manual, WireGuardEndpointSource.MANUAL, 1))
    }

    @Test
    fun configRevisionAndEndpointRevisionStayIndependent() {
        val profile = WireGuardProfile(
            id = "stun",
            name = "家庭 STUN",
            endpointSource = WireGuardEndpointSource.STUN,
            endpointHost = "203.0.113.8",
            profileRevision = 7,
            endpointRevision = 12,
        )
        val endpointRefresh = profile.copy(endpointHost = "203.0.113.9", endpointRevision = 13)
        assertEquals(7, endpointRefresh.profileRevision)
        assertEquals(13, endpointRefresh.endpointRevision)
    }

    @Test
    fun generatedConfigRoutesOnlyTheHomeLan() {
        val profile = WireGuardProfile(
            id = "ddns",
            name = "家庭",
            endpointSource = WireGuardEndpointSource.DDNS,
            endpointHost = "wg.example.com",
            interfaceAddresses = listOf("10.66.0.2/32"),
            serverPublicKey = "server-public-key",
            allowedIps = listOf("192.168.5.0/24"),
        )
        val config = wireGuardQuickConfig(profile, "client-private-key")
        assertTrue(config.contains("AllowedIPs = 192.168.5.0/24"))
        assertFalse(config.contains("0.0.0.0/0"))
        assertTrue(wireGuardProfileError(profile.copy(allowedIps = listOf("0.0.0.0/0")), "client-private-key").contains("MVP"))
    }
}
