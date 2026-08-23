package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StunModelTest {
    @Test
    fun sharedServiceTemplatesApplySuggestedPortAndTransport() {
        val https = applyStunService(StunDraft(), stunTemplate("HTTPS"))
        val wireGuard = applyStunService(StunDraft(), stunTemplate("WireGuard"))
        val dns = applyStunService(StunDraft(), stunTemplate("DNS"))

        assertEquals("443", https.targetPort)
        assertEquals("TCP", https.transportProtocol)
        assertEquals("51820", wireGuard.targetPort)
        assertEquals("UDP", wireGuard.transportProtocol)
        assertEquals("53", dns.targetPort)
        assertEquals("UDP", dns.transportProtocol)
    }

    @Test
    fun draftUsesOnlyTheSimpleStunInputs() {
        val json = StunDraft(
            serviceType = "SSH",
            transportProtocol = "TCP",
            targetIpv4 = "192.168.5.46",
            targetPort = "22",
        ).toJson()

        assertEquals("SSH", json.getString("serviceType"))
        assertEquals("TCP", json.getString("transportProtocol"))
        assertEquals("192.168.5.46", json.getString("targetIpv4"))
        assertEquals(22, json.getInt("targetPort"))
        assertTrue(json.getBoolean("enabled"))
        assertEquals(5, json.length())
    }

    @Test
    fun stunFavoriteRoundTripKeepsLinkAndCurrentEndpoint() {
        val before = FavoriteShortcut(
            id = "stun-stun-1",
            title = "NAS HTTPS",
            description = "STUN 穿透 · HTTPS",
            iconType = "builtin",
            iconValue = "server",
            lanUrl = "https://192.168.5.46:443",
            wanUrl = "https://203.0.113.9:20001",
            order = 0,
            type = "stun",
            stunRuleId = "stun-1",
            localEndpoint = "https://192.168.5.46:443",
            remoteEndpoint = "https://203.0.113.9:20001",
            serviceType = "HTTPS",
        )

        val after = parseFavoriteShortcutsJson(serializeFavoriteShortcutsJson(listOf(before))).single()
        assertEquals("stun", after.type)
        assertEquals("stun-1", after.stunRuleId)
        assertEquals(before.remoteEndpoint, after.remoteEndpoint)
    }
}
