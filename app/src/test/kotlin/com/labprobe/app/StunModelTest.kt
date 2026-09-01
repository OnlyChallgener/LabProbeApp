package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StunModelTest {
    @Test
    fun stunSnapshotDistinguishesUnknownRulesFromConfirmedEmptyRules() {
        assertFalse(parseStunSnapshot(JSONObject()).rulesLoaded)
        assertTrue(parseStunSnapshot(JSONObject().put("rules", org.json.JSONArray())).rulesLoaded)
        assertFalse(
            parseStunSnapshot(
                JSONObject().put("rules", org.json.JSONArray().put(JSONObject().put("name", "缺少规则 ID"))),
            ).rulesLoaded,
        )
    }

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
        assertEquals("", json.getString("name"))
        assertTrue(json.getBoolean("enabled"))
        assertEquals("manual", json.getString("targetType"))
        assertEquals(8, json.length())
    }

    @Test
    fun draftCarriesOptionalUserSelectedMiddlePort() {
        val automatic = StunDraft(
            targetIpv4 = "192.168.5.46",
            targetPort = "9443",
        ).toJson()
        assertTrue(automatic.has("listenPort"))
        assertEquals(0, automatic.getInt("listenPort"))

        val manualDraft = StunDraft(
            targetType = "router_self",
            targetIpv4 = "127.0.0.1",
            targetPort = "54133",
            listenPort = "3499",
        )
        val manual = manualDraft.toJson()
        assertEquals(3499, manual.getInt("listenPort"))
        assertEquals(null, stunDraftValidationError(manualDraft))
        assertEquals(
            "中间端口必须是 1024–65535",
            stunDraftValidationError(manualDraft.copy(listenPort = "1023")),
        )
    }

    @Test
    fun generatedTitleUsesStableServiceFallbackAndDoesNotPopulateRemark() {
        val rule = StunRule(
            id = "stun-1",
            name = "HTTPS · 192.168.5.46:8443",
            enabled = true,
            listenPort = 20_001,
            targetIpv4 = "192.168.5.46",
            targetPort = 9443,
            serviceType = "HTTPS",
            transportProtocol = "TCP",
            forwardMode = "router_native",
            actualState = "mapped",
            firewallState = "ready",
            nativeMappingState = "ready",
            runtime = StunRuntime(publicEndpoint = "111.23.167.91:10193"),
        )

        assertEquals("HTTPS 穿透", stunRuleTitle(rule))
        assertEquals(
            "",
            StunDraft.from(rule).toJson().getString("name"),
        )
    }

    @Test
    fun customTitleIsPreserved() {
        val rule = StunRule(
            id = "stun-1",
            name = "家庭 NAS",
            enabled = true,
            listenPort = 20_001,
            targetIpv4 = "192.168.5.46",
            targetPort = 9443,
            serviceType = "HTTPS",
            transportProtocol = "TCP",
            forwardMode = "router_native",
            actualState = "mapped",
            firewallState = "ready",
            nativeMappingState = "ready",
            runtime = StunRuntime(),
        )

        assertEquals("家庭 NAS", stunRuleTitle(rule))
        assertEquals("家庭 NAS", StunDraft.from(rule).toJson().getString("name"))
    }

    @Test
    fun editingStoppedRuleDoesNotRestartIt() {
        val rule = StunRule(
            id = "stun-1",
            name = "家庭 NAS",
            enabled = false,
            listenPort = 20_001,
            targetIpv4 = "192.168.5.46",
            targetPort = 9999,
            serviceType = "HTTP",
            transportProtocol = "TCP",
            forwardMode = "router_native",
            actualState = "stopped",
            firewallState = "ready",
            nativeMappingState = "ready",
            runtime = StunRuntime(publicEndpoint = "111.23.167.91:10193"),
        )

        val draft = StunDraft.from(rule)
        assertEquals(false, draft.toJson().getBoolean("enabled"))
        assertEquals(false, rule.ready)
    }

    @Test
    fun validatesIpv4PortAndRemarkBeforeSaving() {
        assertEquals("请输入有效的内网 IPv4 地址", stunDraftValidationError(StunDraft(targetIpv4 = "192.168.5.999")))
        assertEquals("目标端口必须是 1–65535", stunDraftValidationError(StunDraft(targetIpv4 = "192.168.5.46", targetPort = "0")))
        assertEquals("规则备注最多 64 个字符", stunDraftValidationError(StunDraft(targetIpv4 = "192.168.5.46", name = "a".repeat(65))))
        assertEquals(null, stunDraftValidationError(StunDraft(targetIpv4 = "192.168.5.46", targetPort = "9999", name = "NAS")))
    }

    @Test
    fun onlyHttpsCopyAddsAProtocolScheme() {
        assertEquals("https://111.23.167.91:10193", stunAddressForCopy("HTTPS", "111.23.167.91:10193"))
        assertEquals("https://111.23.167.91:10193", stunAddressForCopy("https", "http://111.23.167.91:10193"))
        assertEquals("111.23.167.91:10170", stunAddressForCopy("WireGuard", "111.23.167.91:10170"))
        assertEquals("111.23.167.91:10022", stunAddressForCopy("SSH", "111.23.167.91:10022"))
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

    @Test
    fun stoppedStunRuleRetainsFavoriteAndLastKnownAddress() {
        val existing = sampleStunFavorite()
        val stopped = sampleStunRule().copy(
            enabled = false,
            targetPort = 8080,
            serviceType = "HTTP",
            actualState = "stopped",
            runtime = StunRuntime(publicEndpoint = "198.51.100.20:20200"),
        )

        val updated = reconcileStunFavoriteItems(listOf(existing), listOf(stopped)).single()
        assertEquals("http://192.168.5.46:8080", updated.localEndpoint)
        assertEquals("http://203.0.113.9:20001", updated.remoteEndpoint)
        assertEquals(
            "已停止",
            favoriteServiceStatus(updated, "wan", stun = resolveFavoriteStun(updated, listOf(stopped))),
        )
    }

    @Test
    fun transientStunUnavailabilityDoesNotDeleteFavorite() {
        val existing = sampleStunFavorite()
        val transient = sampleStunRule().copy(
            actualState = "mapping",
            runtime = StunRuntime(publicEndpoint = "", mappingFresh = false),
        )

        val updated = reconcileStunFavoriteItems(listOf(existing), listOf(transient)).single()
        assertEquals(existing.remoteEndpoint, updated.remoteEndpoint)
        assertEquals("当前不可达", favoriteServiceStatus(updated, "wan", stun = resolveFavoriteStun(updated, listOf(transient))))
    }

    @Test
    fun authoritativeStunSnapshotUpdatesAddressAndRemovesDeletedLinks() {
        val existing = sampleStunFavorite()
        val unrelated = existing.copy(id = "manual", type = "manual", stunRuleId = null, order = 1)
        val refreshed = sampleStunRule().copy(
            runtime = StunRuntime(
                publicEndpoint = "198.51.100.20:20200",
                publicPort = 20200,
                mappingFresh = true,
            ),
        )

        assertEquals("https://198.51.100.20:20200", reconcileStunFavoriteItems(listOf(existing), listOf(refreshed)).single().remoteEndpoint)
        assertEquals(listOf(unrelated.copy(order = 0)), reconcileStunFavoriteItems(listOf(existing, unrelated), emptyList()))
    }

    private fun sampleStunFavorite() = FavoriteShortcut(
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

    private fun sampleStunRule() = StunRule(
        id = "stun-1",
        name = "NAS HTTPS",
        enabled = true,
        listenPort = 20_001,
        targetIpv4 = "192.168.5.46",
        targetPort = 443,
        serviceType = "HTTPS",
        transportProtocol = "TCP",
        forwardMode = "router_native",
        actualState = "mapped",
        firewallState = "ready",
        nativeMappingState = "ready",
        runtime = StunRuntime(
            publicEndpoint = "203.0.113.9:20001",
            publicPort = 20001,
            mappingFresh = true,
        ),
    )
}
