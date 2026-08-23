package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

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

    @Test
    fun automaticProfilePayloadRegistersClientPeerAndKeepsExistingServerRows() {
        val oldPeer = JSONObject()
            .put("id", "tablet")
            .put("name", "Tablet")
            .put("publicKey", "other-public-key")
            .put("allowedIps", JSONArray().put("10.77.0.9/32"))
        val root = JSONObject()
            .put("revision", 7)
            .put("server", JSONObject()
                .put("interfaceName", "labwg0")
                .put("address", "10.77.0.1/24")
                .put("listenPort", 51820)
                .put("peers", JSONArray().put(oldPeer))
                .put("endpointProfiles", JSONArray()))
        val profile = WireGuardProfile(
            id = "wg-stun-phone",
            name = "手机 STUN",
            endpointSource = WireGuardEndpointSource.STUN,
            endpointHost = "203.0.113.8",
            endpointPort = 24567,
            interfaceAddresses = listOf("10.77.0.3/32"),
            endpointBindingId = "stun-wireguard",
        )

        val payload = buildWireGuardServerPayload(root, profile, "client-public-key")

        assertEquals(7L, payload.getLong("expectedRevision"))
        assertEquals(2, payload.getJSONArray("peers").length())
        val appPeer = payload.getJSONArray("peers").getJSONObject(1)
        assertEquals("client-public-key", appPeer.getString("publicKey"))
        assertEquals("10.77.0.3/32", appPeer.getJSONArray("allowedIps").getString(0))
        val endpoint = payload.getJSONArray("endpointProfiles").getJSONObject(0)
        assertEquals("stun", endpoint.getString("endpointSource"))
        assertEquals("stun-wireguard", endpoint.getString("stunRuleId"))
    }

    @Test
    fun parsesAgentPublicKeyAndRouterLanAddressFromRealHubShapes() {
        val root = JSONObject()
            .put("agentStatus", JSONObject().put("applyResult", JSONObject().put("publicKey", "server-public-key")))
        assertEquals("server-public-key", wireGuardServerPublicKey(root))
        assertEquals(
            "192.168.5.1",
            findRouterLanIpv4(JSONObject().put("router", JSONObject().put("lanIp", "192.168.5.1"))),
        )
        assertEquals(
            "10.0.0.1",
            findRouterLanIpv4(JSONObject().put("details", JSONObject().put("lan", JSONObject().put("ipv4", "10.0.0.1")))),
        )
    }
}
