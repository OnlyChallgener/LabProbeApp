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
    fun generatedConfigRoutesHomeLanAndSupportsFullTunnel() {
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
        assertTrue(config.contains("192.168.5.0/24"))
        assertTrue(config.contains("MTU = 1420"))
        assertFalse(config.contains("0.0.0.0/0"))

        val fullTunnel = profile.copy(allowedIps = listOf("0.0.0.0/0", "::/0"), mtu = 1380)
        val fullConfig = wireGuardQuickConfig(fullTunnel, "client-private-key")
        assertTrue(fullConfig.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertTrue(fullConfig.contains("MTU = 1380"))
        assertEquals("", wireGuardProfileError(fullTunnel, "client-private-key"))
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

    @Test
    fun testServerConfigDefaultsAndCustomParsing() {
        val config = WireGuardServerConfig(listenPort = 51826, mtu = 1380, enabled = false)
        assertEquals(51826, config.listenPort)
        assertEquals(1380, config.mtu)
        assertFalse(config.enabled)
        assertEquals("10.77.0.1/24", config.address)
    }

    @Test
    fun serverIsReadyOnlyAfterDesiredRevisionAndTargetInterfaceAreRunning() {
        val root = JSONObject()
            .put("revision", 9)
            .put("server", JSONObject()
                .put("interfaceName", "labwg0")
                .put("listenPort", 51820)
                .put("mtu", 1420)
                .put("address", "10.77.0.1/24")
                .put("enabled", true))
            .put("agentStatus", JSONObject()
                .put("revision", 9)
                .put("applyResult", JSONObject()
                    .put("revision", 9)
                    .put("ok", true)
                    .put("enabled", true))
                .put("capability", JSONObject()
                    .put("running", true)
                    .put("interfaces", JSONArray().put(JSONObject()
                        .put("name", "labwg0")
                        .put("running", true)))))

        val state = parseWireGuardServerState(root)

        assertEquals(9L, state.agentRevision)
        assertTrue(state.capabilityRunning)
        assertEquals(true, state.interfaceRunning)
        assertTrue(isWireGuardServerReady(state))
    }

    @Test
    fun enabledServerDoesNotStartWhenAgentRevisionOrInterfaceIsNotReady() {
        val root = JSONObject()
            .put("revision", 9)
            .put("server", JSONObject()
                .put("interfaceName", "labwg0")
                .put("enabled", true))
            .put("agentStatus", JSONObject()
                .put("revision", 8)
                .put("applyResult", JSONObject()
                    .put("revision", 8)
                    .put("ok", true)
                    .put("enabled", true))
                .put("capability", JSONObject()
                    .put("running", true)
                    .put("interfaces", JSONArray().put(JSONObject()
                        .put("name", "other-wg")
                        .put("running", true)))))

        assertFalse(isWireGuardServerReady(parseWireGuardServerState(root)))
    }

    @Test
    fun enablingServerPreservesTheCompleteCurrentServerDocument() {
        val peers = JSONArray().put(JSONObject().put("id", "phone").put("publicKey", "client-key"))
        val endpointProfiles = JSONArray().put(JSONObject().put("id", "ddns-home").put("endpointSource", "ddns"))
        val root = JSONObject()
            .put("revision", 14)
            .put("server", JSONObject()
                .put("interfaceName", "customwg")
                .put("listenPort", 51826)
                .put("mtu", 1380)
                .put("address", "10.88.0.1/24")
                .put("enabled", false)
                .put("peers", peers)
                .put("endpointProfiles", endpointProfiles)
                .put("futureField", "keep-me"))

        val payload = buildWireGuardServerEnablePayload(root)

        assertEquals(14L, payload.getLong("expectedRevision"))
        assertTrue(payload.getBoolean("enabled"))
        assertEquals("customwg", payload.getString("interfaceName"))
        assertEquals(51826, payload.getInt("listenPort"))
        assertEquals(1380, payload.getInt("mtu"))
        assertEquals("10.88.0.1/24", payload.getString("address"))
        assertEquals("phone", payload.getJSONArray("peers").getJSONObject(0).getString("id"))
        assertEquals("ddns-home", payload.getJSONArray("endpointProfiles").getJSONObject(0).getString("id"))
        assertEquals("keep-me", payload.getString("futureField"))
    }

    @Test
    fun currentApplyAndCapabilityErrorsAreExposed() {
        val applyFailure = WireGuardServerState(
            config = WireGuardServerConfig(revision = 12, enabled = true),
            applyResultRevision = 12,
            applyResultOk = false,
            applyError = "路由器端口被占用",
        )
        val capabilityFailure = WireGuardServerState(
            config = WireGuardServerConfig(revision = 12, enabled = true),
            agentRevision = 12,
            capabilityError = "WireGuard 内核不可用",
        )

        assertEquals("路由器端口被占用", wireGuardServerErrorForRevision(applyFailure, 12))
        assertEquals("WireGuard 内核不可用", wireGuardServerErrorForRevision(capabilityFailure, 12))
    }
}

