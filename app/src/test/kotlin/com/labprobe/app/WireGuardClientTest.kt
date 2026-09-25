package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class WireGuardClientTest {
    @Test
    fun privateKeyIdentifiersStayIndependentAcrossRoutersAndHubs() {
        val profileId = "phone-shared"
        val firstHub = "http://192.168.5.46:58443"
        val secondHub = "http://office.example:58443"
        val legacy = wireGuardPrivateKeyStorageId("default", firstHub, firstHub, profileId)
        val office = wireGuardPrivateKeyStorageId("office", firstHub, firstHub, profileId)
        val otherHub = wireGuardPrivateKeyStorageId("default", secondHub, firstHub, profileId)
        assertEquals(profileId, legacy)
        assertTrue(office != legacy)
        assertTrue(otherHub != legacy)
        assertTrue(office != otherHub)
    }

    private fun portProfile(source: WireGuardEndpointSource, port: Int = 51820, follows: Boolean? = null) =
        WireGuardProfile(id = "phone", name = "Phone", endpointSource = source,
            endpointHost = "vpn.example.com", endpointPort = port, followsServerPort = follows)

    @Test
    fun gatewayPortChangesOnlyFollowingDdnsAndNeverManualOrStunPublicPorts() {
        val following = portProfile(WireGuardEndpointSource.DDNS, follows = true)
        assertEquals(51826, applyWireGuardServerConfig(following, 51826, 1380, 51820).endpointPort)
        val legacy = portProfile(WireGuardEndpointSource.DDNS)
        assertEquals(true, applyWireGuardServerConfig(legacy, 51826, 1380, 51820).followsServerPort)
        val externalMapping = portProfile(WireGuardEndpointSource.DDNS, port = 45000)
        val preserved = applyWireGuardServerConfig(externalMapping, 51826, 1380, 51820)
        assertEquals(45000, preserved.endpointPort)
        assertEquals(false, preserved.followsServerPort)
        val fixedSamePort = portProfile(WireGuardEndpointSource.DDNS, follows = false)
        assertEquals(51820, applyWireGuardServerConfig(fixedSamePort, 51826, 1380, 51820).endpointPort)
        val manual = portProfile(WireGuardEndpointSource.MANUAL)
        assertEquals(manual, applyWireGuardServerConfig(manual, 51826, 1380, 51820))
        val stun = portProfile(WireGuardEndpointSource.STUN, port = 42137)
        assertEquals(42137, applyWireGuardServerConfig(stun, 51826, 1380, 51820).endpointPort)
    }

    @Test
    fun existingManagedProfileCannotReprovisionWhenHubServerDocumentIsMissing() {
        val missing = JSONObject().put("server", JSONObject.NULL)
        val existing = portProfile(WireGuardEndpointSource.STUN).copy(serverPublicKey = "known-server-key")
        val failure = runCatching { requireManagedWireGuardServerForExistingProfile(missing, existing) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("已阻止覆盖"))

        requireManagedWireGuardServerForExistingProfile(missing, existing.copy(serverPublicKey = ""))
        requireManagedWireGuardServerForExistingProfile(
            JSONObject().put("server", JSONObject()), existing,
        )
    }
    private fun provisionProfile() = portProfile(WireGuardEndpointSource.STUN)
        .copy(interfaceAddresses = listOf("10.77.0.6/24"), endpointBindingId = "wg-rule")

    private fun provisionRoot(profile: WireGuardProfile = provisionProfile(), agentApplied: Boolean = true): JSONObject {
        val base = JSONObject().put("revision", 8).put("server", JSONObject()
            .put("interfaceName", "labwg0").put("address", "10.77.0.1/24")
            .put("listenPort", 51820).put("mtu", 1420).put("enabled", true)
            .put("peers", JSONArray()).put("endpointProfiles", JSONArray()))
        val server = buildWireGuardServerPayload(base, profile, "phone-public-key")
        server.remove("expectedRevision")
        return JSONObject().put("revision", 9).put("server", server)
            .put("agentStatus", JSONObject()
                .put("revision", if (agentApplied) 9 else 8)
                .put("applyResult", JSONObject()
                    .put("revision", if (agentApplied) 9 else 8)
                    .put("ok", true).put("enabled", true)
                    .put("interfaceName", "labwg0").put("controlBackend", "kernel-netlink")
                    .put("publicKey", "server-public-key"))
                .put("capability", JSONObject()
                    .put("wgToolAvailable", false).put("provisioningReady", true)
                    .put("controlBackend", "kernel-netlink")
                    .put("running", false).put("interfaces", JSONArray())))
    }

    @Test
    fun identicalDesiredPeerAndEndpointCanReuseExistingRevision() {
        val profile = provisionProfile()
        val root = provisionRoot(profile)
        assertTrue(wireGuardProvisionTargetMatches(root, profile, "phone-public-key"))
        assertTrue(isWireGuardServerConfigApplied(parseWireGuardServerState(root), parseWireGuardServerState(root).config))
        val peer = root.getJSONObject("server").getJSONArray("peers").getJSONObject(0)
        assertEquals("10.77.0.6/32", peer.getJSONArray("allowedIps").getString(0))
        assertFalse(wireGuardProvisionTargetMatches(root, profile.copy(interfaceAddresses = listOf("10.77.0.8/32")), "phone-public-key"))
        assertFalse(wireGuardProvisionTargetMatches(root, profile.copy(endpointBindingId = "other-rule"), "phone-public-key"))
        assertFalse(wireGuardProvisionTargetMatches(root, profile.copy(name = "Other phone"), "phone-public-key"))
        assertFalse(wireGuardProvisionTargetMatches(root, profile, "different-key"))
        val endpoint = root.getJSONObject("server").getJSONArray("endpointProfiles").getJSONObject(0)
        endpoint.put("port", 51821)
        assertFalse(wireGuardProvisionTargetMatches(root, profile, "phone-public-key"))
        endpoint.put("port", 51820).put("endpointSource", "ddns")
        assertFalse(wireGuardProvisionTargetMatches(root, profile, "phone-public-key"))
        endpoint.put("endpointSource", "stun")
        peer.put("allowedIps", JSONArray().put("10.77.0.0/24"))
        assertFalse(wireGuardProvisionTargetMatches(root, profile, "phone-public-key"))
        peer.put("allowedIps", JSONArray().put("10.77.0.6/32"))
        root.getJSONObject("server").getJSONArray("peers").put(
            JSONObject().put("id", "other-phone").put("publicKey", "phone-public-key"),
        )
        assertFalse(wireGuardProvisionTargetMatches(root, profile, "phone-public-key"))
    }

    @Test
    fun unchangedPendingRevisionIsPolledUntilAgentConfirms() = runBlocking {
        val profile = provisionProfile()
        val pending = provisionRoot(profile, agentApplied = false)
        val applied = provisionRoot(profile)
        var loads = 0
        var pauses = 0
        val result = awaitWireGuardProvision(
            parseWireGuardServerState(pending).config, profile, "phone-public-key",
            load = { loads++; applied }, pause = { pauses++ }, attempts = 3, initial = pending,
        )
        assertEquals(1, loads)
        assertEquals(1, pauses)
        assertEquals(9L, result.desiredRevision)
        assertEquals(9L, result.appliedRevision)
        assertEquals("server-public-key", result.profile.serverPublicKey)
    }

    @Test
    fun lostVerificationResponseRetainsSubmittedRevisionWithoutBlindResubmit() = runBlocking {
        val profile = provisionProfile()
        val expected = parseWireGuardServerState(provisionRoot(profile)).config
        var loads = 0
        var pauses = 0
        val failure = try {
            awaitWireGuardProvision(
                expected, profile, "phone-public-key",
                load = { loads++; throw IOException("connection dropped") },
                pause = { pauses++ }, attempts = 3,
            )
            null
        } catch (error: WireGuardPendingVerificationException) {
            error
        }
        assertEquals(3, loads)
        assertEquals(2, pauses)
        assertEquals(9L, failure?.submittedRevision)
        assertTrue(failure?.message.orEmpty().contains("已提交"))
        assertTrue(failure?.message.orEmpty().contains("待核对"))
    }

    @Test
    fun changedRemotePeerAfterSubmissionRequiresManualVerification() = runBlocking {
        val profile = provisionProfile()
        val changed = provisionRoot(profile)
        changed.getJSONObject("server").getJSONArray("peers").getJSONObject(0)
            .put("allowedIps", JSONArray().put("10.77.0.9/32"))
        val failure = try {
            awaitWireGuardProvision(
                parseWireGuardServerState(provisionRoot(profile)).config, profile, "phone-public-key",
                load = { changed }, pause = {}, attempts = 1,
            )
            null
        } catch (error: WireGuardPendingVerificationException) {
            error
        }
        assertEquals(9L, failure?.submittedRevision)
        assertTrue(failure?.message.orEmpty().contains("peer 或端点已变化"))
    }

    @Test
    fun portAuthorityRoundTripsAndNewAutomaticProfilesFollowGateway() {
        val explicit = portProfile(WireGuardEndpointSource.DDNS, follows = false)
        assertEquals(false, WireGuardProfile.fromJson(explicit.toJson())!!.followsServerPort)
        val legacy = portProfile(WireGuardEndpointSource.DDNS)
        assertEquals(null, WireGuardProfile.fromJson(legacy.toJson())!!.followsServerPort)
        assertEquals(true, WireGuardProfile.newProfile(WireGuardEndpointSource.DDNS).followsServerPort)
        assertEquals(false, WireGuardProfile.newProfile(WireGuardEndpointSource.MANUAL).followsServerPort)
    }

    @Test
    fun changingStunBindingResetsOldAddressAndRevision() {
        val old = portProfile(WireGuardEndpointSource.STUN, 42137).copy(endpointBindingId = "old", endpointRevision = 100, profileRevision = 5)
        val updated = editedWireGuardProfile(old, old.copy(endpointBindingId = "new"))
        assertEquals(0L, updated.endpointRevision)
        assertEquals(6L, updated.profileRevision)
        assertEquals("", updated.endpointHost)
        assertTrue(updated.endpointUpdateError.isNotBlank())
        val renamed = editedWireGuardProfile(old, old.copy(name = "New name"))
        assertEquals(100L, renamed.endpointRevision)
        assertEquals(old.endpoint, renamed.endpoint)
        val staleEditor = old.copy(name = "Renamed", endpointHost = "203.0.113.2", endpointPort = 1)
        assertEquals(old.endpoint, editedWireGuardProfile(old, staleEditor).endpoint)
    }

    private fun serverWithBindings(): JSONObject = JSONObject().put("revision", 7).put("server", JSONObject()
        .put("listenPort", 51820).put("mtu", 1420).put("enabled", false).put("futureField", "preserve")
        .put("peers", JSONArray()
            .put(JSONObject().put("id", "app-phone").put("publicKey", "shared-key"))
            .put(JSONObject().put("id", "app-tablet").put("publicKey", "shared-key")))
        .put("endpointProfiles", JSONArray()
            .put(JSONObject().put("id", "app-phone").put("name", "Phone").put("endpointSource", "stun")
                .put("stunRuleId", "shared-stun").put("port", 51820).put("resolvedEndpoint", "203.0.113.1:42137"))
            .put(JSONObject().put("id", "app-tablet").put("name", "Tablet").put("endpointSource", "stun")
                .put("stunRuleId", "shared-stun").put("port", 51820))
            .put(JSONObject().put("id", "ddns-default").put("endpointSource", "ddns").put("port", 51820)
                .put("hostname", "home.example.com").put("resolvedEndpoint", "home.example.com:51820"))
            .put(JSONObject().put("id", "ddns-external").put("endpointSource", "ddns").put("port", 45000)
                .put("hostname", "mapped.example.com").put("resolvedEndpoint", "mapped.example.com:45000"))))

    @Test
    fun settingsPayloadPreservesPeersUnknownFieldsAndExternalEndpoints() {
        val root = serverWithBindings()
        val payload = buildWireGuardServerSettingsPayload(root, 51826, 1380, "10.77.0.1/24", true)
        assertEquals(7L, payload.getLong("expectedRevision"))
        assertEquals("preserve", payload.getString("futureField"))
        assertEquals(2, payload.getJSONArray("peers").length())
        assertEquals(51820, root.getJSONObject("server").getInt("listenPort"))
        val endpoints = payload.getJSONArray("endpointProfiles")
        assertEquals(51826, endpoints.getJSONObject(0).getInt("port"))
        assertEquals("203.0.113.1:42137", endpoints.getJSONObject(0).getString("resolvedEndpoint"))
        assertEquals("home.example.com:51826", endpoints.getJSONObject(2).getString("resolvedEndpoint"))
        assertEquals(45000, endpoints.getJSONObject(3).getInt("port"))
        assertEquals("mapped.example.com:45000", endpoints.getJSONObject(3).getString("resolvedEndpoint"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun settingsPayloadRejectsInvalidPortInsteadOfClamping() {
        buildWireGuardServerSettingsPayload(serverWithBindings(), 70000, 1420, "10.77.0.1/24", true)
    }

    @Test
    fun deletingOneProfilePreservesSharedStunAndSharedKeyPeer() {
        val profile = portProfile(WireGuardEndpointSource.STUN).copy(endpointBindingId = "shared-stun")
        val root = serverWithBindings()
        val payload = buildWireGuardProfileRemovalPayload(root, profile)
        assertEquals(1, payload.getJSONArray("peers").length())
        assertEquals("app-tablet", payload.getJSONArray("peers").getJSONObject(0).getString("id"))
        assertEquals(3, payload.getJSONArray("endpointProfiles").length())
        assertEquals("shared-stun", payload.getJSONArray("endpointProfiles").getJSONObject(0).getString("stunRuleId"))
        assertEquals(listOf("Phone", "Tablet"), wireGuardStunDependents(root, "shared-stun"))
        assertTrue(wireGuardStunDependents(root, "missing").isEmpty())
        assertEquals(setOf("shared-stun"), wireGuardBoundStunIds(root, listOf(profile)))
    }

    @Test
    fun dependencySnapshotUsesOnlyExactServerRuleAndProfileIds() {
        val snapshot = parseWireGuardStunDependencySnapshot(serverWithBindings())

        assertEquals(2, snapshot.references.size)
        assertEquals(listOf("app-phone", "app-tablet"), snapshot.forRule("shared-stun").map { it.endpointProfileId })
        assertEquals(listOf("app-phone", "app-tablet"), snapshot.forRule("shared-stun").map { it.peerId })
        assertEquals("shared-stun", snapshot.forEndpointProfile("app-phone")?.ruleId)
        assertTrue(snapshot.forRule("missing").isEmpty())
    }

    @Test
    fun explicitRuleCleanupRemovesOnlyExactReferencesAndSameIdPeers() {
        val root = serverWithBindings()
        val plan = buildWireGuardRuleCleanupPlan(root, "shared-stun")

        assertEquals(7L, plan.payload.getLong("expectedRevision"))
        assertEquals(listOf("app-phone", "app-tablet"), plan.removedEndpointProfileIds)
        assertEquals(listOf("app-phone", "app-tablet"), plan.removedPeerIds)
        assertEquals(0, plan.payload.getJSONArray("peers").length())
        assertEquals(2, plan.payload.getJSONArray("endpointProfiles").length())
        assertEquals("ddns-default", plan.payload.getJSONArray("endpointProfiles").getJSONObject(0).getString("id"))
        assertEquals("preserve", plan.payload.getString("futureField"))
        assertEquals(2, root.getJSONObject("server").getJSONArray("peers").length())
    }

    @Test
    fun automaticToManualTransitionRemovesOnlyOwnedRowsAndKeepsStunRuleExternal() {
        val old = portProfile(WireGuardEndpointSource.STUN).copy(endpointBindingId = "shared-stun")
        val manual = old.copy(endpointSource = WireGuardEndpointSource.MANUAL, endpointBindingId = "")
        val plan = buildWireGuardProfileTransitionPlan(serverWithBindings(), old, manual, "")

        assertEquals(listOf("app-phone"), plan.removedEndpointProfileIds)
        assertEquals(listOf("app-phone"), plan.removedPeerIds)
        assertEquals(1, plan.payload.getJSONArray("peers").length())
        assertEquals("app-tablet", plan.payload.getJSONArray("peers").getJSONObject(0).getString("id"))
        assertEquals("shared-stun", plan.payload.getJSONArray("endpointProfiles").getJSONObject(0).getString("stunRuleId"))
        assertEquals("preserve", plan.payload.getString("futureField"))
    }

    @Test
    fun automaticRebindReplacesOwnedServerRowsWithoutCarryingOldEndpoint() {
        val old = portProfile(WireGuardEndpointSource.STUN).copy(endpointBindingId = "shared-stun")
        val rebound = editedWireGuardProfile(old, old.copy(endpointBindingId = "new-rule"))
        val plan = buildWireGuardProfileTransitionPlan(serverWithBindings(), old, rebound, "new-key")
        val endpoints = plan.payload.getJSONArray("endpointProfiles")
        val owned = (0 until endpoints.length()).map(endpoints::getJSONObject).single { it.getString("id") == "app-phone" }

        assertEquals("new-rule", owned.getString("stunRuleId"))
        assertEquals("", owned.getString("resolvedEndpoint"))
        assertEquals(1, (0 until endpoints.length()).count { endpoints.getJSONObject(it).optString("stunRuleId") == "shared-stun" })
        assertEquals(2, plan.payload.getJSONArray("peers").length())
    }

    @Test
    fun changingAutomaticClientAddressKeepsOldAddressUntilLocalTunnelCanRestart() {
        val old = portProfile(WireGuardEndpointSource.STUN).copy(
            endpointBindingId = "shared-stun",
            interfaceAddresses = listOf("10.77.0.6/24"),
        )
        val next = old.copy(interfaceAddresses = listOf("10.77.0.8/32"))
        val server = serverWithBindings()
        val oldPeers = server.getJSONObject("server").getJSONArray("peers")
        (0 until oldPeers.length()).map(oldPeers::getJSONObject)
            .single { it.getString("id") == "app-phone" }
            .put("allowedIps", JSONArray().put("10.77.0.0/24"))
        val plan = buildWireGuardProfileTransitionPlan(server, old, next, "client-key")
        val peers = plan.payload.getJSONArray("peers")
        val own = (0 until peers.length()).map(peers::getJSONObject)
            .single { it.getString("id") == "app-phone" }

        assertEquals(listOf("10.77.0.6/32", "10.77.0.8/32"),
            (0 until own.getJSONArray("allowedIps").length()).map { own.getJSONArray("allowedIps").getString(it) })
    }

    @Test
    fun mutationMessagesDistinguishNotSubmittedFromAcceptedPendingAgent() {
        val upstream = HubHttpException(502, "上游服务暂不可用")
        val read = wireGuardMutationFailureMessage(WireGuardMutationStage.READ_BEFORE_SUBMIT, upstream)
        val submit = wireGuardMutationFailureMessage(WireGuardMutationStage.SUBMIT, upstream)
        val wait = wireGuardMutationFailureMessage(WireGuardMutationStage.WAIT_AGENT, upstream)

        assertTrue(read.contains("尚未提交"))
        assertTrue(submit.contains("未确认接收"))
        assertTrue(wait.contains("已接受"))
        assertTrue(wait.contains("等待 Agent"))
        assertTrue(isWireGuardSubmissionUncertain(upstream))
        assertTrue(isWireGuardSubmissionUncertain(java.io.IOException("timeout")))
        assertFalse(isWireGuardSubmissionUncertain(HubHttpException(400, "bad payload")))
        assertFalse(isWireGuardSubmissionUncertain(HubHttpException(409, "conflict")))
        val rejected = wireGuardMutationFailureMessage(
            WireGuardMutationStage.SUBMIT,
            HubHttpException(400, "bad payload"),
        )
        assertTrue(rejected.contains("Hub 拒绝"))
        assertFalse(rejected.contains("未确认接收"))
    }

    @Test
    fun mutationPayloadDropsKnownRuntimeFieldsButPreservesFutureConfiguration() {
        val server = serverWithBindings().getJSONObject("server")
            .put("runtime", JSONObject().put("running", true))
            .put("serverPublicKey", "read-only")
        val payload = copyWireGuardServerForMutation(server)

        assertFalse(payload.has("runtime"))
        assertFalse(payload.has("serverPublicKey"))
        assertEquals("preserve", payload.getString("futureField"))
        assertEquals(4, payload.getJSONArray("endpointProfiles").length())
    }

    @Test
    fun uncertainStunCreateClaimsOnlyOneExactNewCompatibleRule() {
        fun row(id: String, port: Int, protocol: String = "UDP") = JSONObject()
            .put("id", id)
            .put("name", id)
            .put("enabled", true)
            .put("serviceType", "WireGuard")
            .put("transportProtocol", protocol)
            .put("targetType", "manual")
            .put("targetIpv4", "192.168.5.1")
            .put("targetPort", port)
            .put("forwardMode", "router_native")
        val after = parseStunSnapshot(JSONObject().put("rules", JSONArray()
            .put(row("before", 51820))
            .put(row("new-compatible", 51820))
            .put(row("new-wrong-port", 51826))
            .put(row("new-wrong-protocol", 51820, "TCP"))))

        assertEquals(
            listOf("new-compatible"),
            compatibleNewWireGuardStunRules(setOf("before"), after, 51820, "192.168.5.1").map { it.id },
        )
        assertEquals(
            2,
            compatibleNewWireGuardStunRules(emptySet(), after, 51820, "192.168.5.1").size,
        )
    }

    @Test
    fun newProfileNeverChangesServerListenPortOrEnablesDisabledServer() {
        val root = serverWithBindings()
        val profile = portProfile(WireGuardEndpointSource.DDNS, port = 51826, follows = true)
        val payload = buildWireGuardServerPayload(root, profile, "new-public-key")
        assertEquals(51820, payload.getInt("listenPort"))
        assertFalse(payload.getBoolean("enabled"))
        assertEquals("preserve", payload.getString("futureField"))
    }

    @Test
    fun boundStunNeverFallsBackToAnotherReadyRule() {
        val rule = parseStunSnapshot(JSONObject().put("rules", JSONArray().put(JSONObject()
            .put("id", "other").put("enabled", true).put("serviceType", "WireGuard")
            .put("transportProtocol", "UDP").put("targetPort", 51826)
            .put("targetType", "manual").put("targetIpv4", "192.168.1.1")
            .put("forwardMode", "router_native")))).rules.single()
        val missing = portProfile(WireGuardEndpointSource.STUN).copy(endpointBindingId = "deleted")
        assertEquals(null, boundWireGuardStunRule(missing, listOf(rule)))
        assertEquals(null, boundWireGuardStunRule(missing.copy(endpointBindingId = ""), listOf(rule)))
        assertEquals(rule, boundWireGuardStunRule(missing.copy(endpointBindingId = "other"), listOf(rule)))
        assertTrue(isWireGuardStunTarget(rule, "192.168.1.1"))
        assertFalse(isWireGuardStunTarget(rule.copy(transportProtocol = "TCP"), "192.168.1.1"))
        assertFalse(isWireGuardStunTarget(rule.copy(targetType = "manual", targetIpv4 = "192.168.1.99"), "192.168.1.1"))
        assertFalse(isWireGuardStunTarget(rule.copy(
            targetType = "router_self",
            targetIpv4 = "127.0.0.1",
            forwardMode = "relay_proxy",
        ), "192.168.1.1"))
        val legacy = rule.copy(
            targetType = "router_self",
            targetIpv4 = "127.0.0.1",
            forwardMode = "relay_proxy",
        )
        assertTrue(isLegacyWireGuardRelayStunTarget(legacy, 51826))
        assertFalse(isLegacyWireGuardRelayStunTarget(legacy.copy(targetPort = 51820), 51826))
        assertFalse(isLegacyWireGuardRelayStunTarget(legacy.copy(serviceType = "HTTPS"), 51826))
    }

    @Test
    fun appliedSettingsNeedExactConfigAndExplicitRevisionAcknowledgement() {
        val config = WireGuardServerConfig(listenPort = 51826, revision = 8)
        val ready = WireGuardServerState(config, agentRevision = 8, applyResultRevision = 8,
            applyResultOk = true, applyResultEnabled = true, capabilityRunning = true, interfaceRunning = true)
        assertTrue(isWireGuardServerConfigApplied(ready, config))
        assertFalse(isWireGuardServerConfigApplied(ready.copy(applyResultRevision = 7), config))
        assertFalse(isWireGuardServerConfigApplied(ready.copy(applyResultOk = null), config))
        assertFalse(isWireGuardServerConfigApplied(ready.copy(config = config.copy(listenPort = 51820)), config))
        assertFalse(isWireGuardServerConfigApplied(ready.copy(applyResultEnabled = false), config))
        val disabledConfig = config.copy(enabled = false)
        val disabled = ready.copy(config = disabledConfig, applyResultEnabled = false, interfaceRunning = false)
        assertTrue(isWireGuardServerConfigApplied(disabled, disabledConfig))
        assertFalse(isWireGuardServerConfigApplied(disabled.copy(interfaceRunning = true), disabledConfig))
    }

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
    fun homeLanRoutesComeFromMeasuredAddressesOnly() {
        assertEquals(listOf("192.168.5.0/24"), homeLanRouteCidrs("192.168.5.1"))
        assertEquals(listOf("192.168.5.0/24"), homeLanRouteCidrs("http://192.168.5.1:8080"))
        assertEquals(listOf("192.168.5.0/24"), privateHubLanRouteCidrs("http://192.168.5.46:58443"))
        assertTrue(privateHubLanRouteCidrs("http://111.23.167.101:58443").isEmpty())
        assertEquals(listOf("192.168.5.0/24"), homeLanRouteCidrs("", "mq.lab86.shinya.icu", "192.168.5.46"))
        assertTrue(homeLanRouteCidrs("router.local", "", null).isEmpty())
    }

    @Test
    fun manualConfigUsesOnlyTheRoutesEnteredByItsOwner() {
        val blankManual = WireGuardProfile.newProfile(WireGuardEndpointSource.MANUAL)
        assertTrue(blankManual.interfaceAddresses.isEmpty())
        assertTrue(blankManual.allowedIps.isEmpty())
        val profile = WireGuardProfile(
            id = "manual",
            name = "外部 VPN",
            endpointSource = WireGuardEndpointSource.MANUAL,
            endpointHost = "vpn.example.com",
            interfaceAddresses = listOf("10.8.0.5/24"),
            serverPublicKey = "server-public-key",
            allowedIps = listOf("10.8.0.0/24"),
        )
        assertEquals(listOf("10.8.0.0/24"), wireGuardEffectiveAllowedIps(profile, listOf("192.168.5.0/24")))
        val config = wireGuardQuickConfig(profile, "client-private-key", listOf("192.168.5.0/24"))
        assertEquals("AllowedIPs = 10.8.0.0/24", config.lineSequence().single { it.startsWith("AllowedIPs =") })
        assertFalse(config.contains("10.77.0.0/24"))
        assertFalse(config.contains("192.168.5.0/24"))
    }

    @Test
    fun serverPeerRoutesAreSingleClientHostsEvenWhenPhoneAddressUsesSubnetPrefix() {
        assertEquals(listOf("10.77.0.6/32", "2001:db8::6/128"),
            wireGuardPeerHostRoutes(listOf("10.77.0.6/24", "2001:db8::6/64")))
    }

    @Test
    fun generatedConfigAddsMeasuredHomeLanWithoutEditingTheProfile() {
        val profile = WireGuardProfile(
            id = "stun",
            name = "家庭 STUN",
            endpointSource = WireGuardEndpointSource.STUN,
            endpointHost = "203.0.113.8",
            serverPublicKey = "server-public-key",
            interfaceAddresses = listOf("10.77.0.3/32"),
            allowedIps = listOf("10.77.0.0/24"),
        )
        val config = wireGuardQuickConfig(profile, "client-private-key", listOf("192.168.5.0/24"))
        assertTrue(config.contains("AllowedIPs = 10.77.0.0/24, 192.168.5.0/24"))
    }

    @Test
    fun manualStoredRoutesAreNeverMigratedAway() {
        val stored = JSONObject()
            .put("id", "manual")
            .put("endpointSource", "manual")
            .put("endpointHost", "vpn.example.com")
            .put("allowedIps", JSONArray().put("192.168.1.0/24"))
        assertEquals(listOf("192.168.1.0/24"), WireGuardProfile.fromJson(stored)!!.allowedIps)
    }

    @Test
    fun legacyGuessedHomeLanIsDroppedWhenReadingStoredProfiles() {
        val stored = JSONObject()
            .put("id", "stun")
            .put("name", "家庭 WireGuard（STUN）")
            .put("endpointSource", "stun")
            .put("endpointHost", "203.0.113.8")
            .put("serverPublicKey", "server-public-key")
            .put("allowedIps", JSONArray().put("192.168.1.0/24"))
        val profile = WireGuardProfile.fromJson(stored)!!
        assertEquals(listOf("10.77.0.0/24"), profile.allowedIps)
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
            interfaceAddresses = listOf("10.77.0.3/24"),
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
    fun kernelNetlinkApplyCanProveReadinessWhenWgRuntimeIsNotObservable() {
        val root = JSONObject()
            .put("revision", 9)
            .put("server", JSONObject()
                .put("interfaceName", "labwg0")
                .put("enabled", true))
            .put("agentStatus", JSONObject()
                .put("revision", 9)
                .put("applyResult", JSONObject()
                    .put("revision", 9)
                    .put("ok", true)
                    .put("enabled", true)
                    .put("interfaceName", "labwg0")
                    .put("controlBackend", "kernel-netlink"))
                .put("capability", JSONObject()
                    .put("wgToolAvailable", false)
                    .put("provisioningReady", true)
                    .put("controlBackend", "kernel-netlink")
                    .put("running", false)
                    .put("interfaces", JSONArray())
                    .put("error", JSONObject.NULL)))

        assertTrue(isWireGuardServerReady(parseWireGuardServerState(root)))
    }

    @Test
    fun unobservableRuntimeStillRequiresExactSuccessfulKernelNetlinkApply() {
        val root = JSONObject()
            .put("revision", 9)
            .put("server", JSONObject()
                .put("interfaceName", "labwg0")
                .put("enabled", true))
            .put("agentStatus", JSONObject()
                .put("revision", 9)
                .put("applyResult", JSONObject()
                    .put("revision", 8)
                    .put("ok", true)
                    .put("enabled", true)
                    .put("interfaceName", "labwg0")
                    .put("controlBackend", "kernel-netlink"))
                .put("capability", JSONObject()
                    .put("wgToolAvailable", false)
                    .put("provisioningReady", true)
                    .put("controlBackend", "kernel-netlink")
                    .put("running", false)
                    .put("interfaces", JSONArray())))

        assertFalse(isWireGuardServerReady(parseWireGuardServerState(root)))

        root.getJSONObject("agentStatus")
            .getJSONObject("applyResult")
            .put("revision", 9)
            .put("interfaceName", "other-wg")
        assertFalse(isWireGuardServerReady(parseWireGuardServerState(root)))
    }

    @Test
    fun observableStoppedRuntimeCannotBeOverriddenBySuccessfulApplyResult() {
        val root = JSONObject()
            .put("revision", 9)
            .put("server", JSONObject()
                .put("interfaceName", "labwg0")
                .put("enabled", true))
            .put("agentStatus", JSONObject()
                .put("revision", 9)
                .put("applyResult", JSONObject()
                    .put("revision", 9)
                    .put("ok", true)
                    .put("enabled", true)
                    .put("interfaceName", "labwg0")
                    .put("controlBackend", "kernel-netlink"))
                .put("capability", JSONObject()
                    .put("wgToolAvailable", true)
                    .put("provisioningReady", true)
                    .put("controlBackend", "kernel-netlink")
                    .put("running", false)
                    .put("interfaces", JSONArray().put(JSONObject()
                        .put("name", "labwg0")
                        .put("running", false)))))

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

    @Test
    fun jsonNullAgentErrorsAreNotDisplayedAsLiteralNull() {
        val root = JSONObject()
            .put("revision", 12)
            .put("server", JSONObject()
                .put("interfaceName", "labwg0")
                .put("enabled", true))
            .put("agentStatus", JSONObject()
                .put("revision", 12)
                .put("applyResult", JSONObject()
                    .put("revision", 12)
                    .put("ok", true)
                    .put("error", JSONObject.NULL))
                .put("capability", JSONObject()
                    .put("running", false)
                    .put("error", JSONObject.NULL)))

        val state = parseWireGuardServerState(root)

        assertEquals("", state.applyError)
        assertEquals("", state.capabilityError)
        assertEquals("", wireGuardServerErrorForRevision(state, 12))
        assertFalse(isWireGuardServerReady(state))
    }

    @Test
    fun routerPeerSnapshotKeepsCreationTimeAndTreatsMissingAsUnknown() {
        val peers = listOf(
            WireGuardRouterPeer(
                id = "app-phone", name = "Phone", publicKey = "abc123456789",
                allowedIps = listOf("10.77.0.2/32"), referenced = true,
                createdAt = "2026-09-21T06:12:21Z",
            ),
            WireGuardRouterPeer(
                id = "app-old", name = "Old", publicKey = "def123456789",
                allowedIps = listOf("10.77.0.9/32"), referenced = false,
            ),
        )

        val decoded = decodeRouterPeers(routerPeersToJson(peers))

        assertEquals("2026-09-21T06:12:21Z", decoded.first().createdAt)
        // 没记过创建时间的老 peer 保持空，不能拿"今天"糊上去。
        assertEquals("", decoded[1].createdAt)
        assertEquals(listOf("app-phone", "app-old"), decoded.map { it.id })
        assertEquals(listOf(true, false), decoded.map { it.referenced })
    }

    @Test
    fun onlyPeerSideEditsNeedTheRouterLocalRoutesAndDnsNeverDo() {
        val base = WireGuardProfile(
            id = "phone",
            name = "家庭 WireGuard（STUN）",
            endpointSource = WireGuardEndpointSource.STUN,
            endpointHost = "111.23.167.101",
            endpointPort = 12354,
            endpointBindingId = "stun-1",
            interfaceAddresses = listOf("10.77.0.3/32"),
            serverPublicKey = "KEY123",
        )

        // 纯本机这一侧：路由网段 / DNS / MTU 都不该被 Hub 可达性挡住。
        assertFalse(wireGuardEditNeedsRouter(base, base.copy(allowedIps = listOf("10.77.0.0/24", "192.168.5.0/24"))))
        assertFalse(wireGuardEditNeedsRouter(base, base.copy(dnsServers = listOf("223.5.5.5"), mtu = 1400)))
        // 会写进路由器 peer 的字段。
        assertTrue(wireGuardEditNeedsRouter(base, base.copy(interfaceAddresses = listOf("10.77.0.6/32"))))
        assertFalse(wireGuardEditNeedsRouter(base, base.copy(name = "换名字")))
        assertFalse(wireGuardLocalTunnelConfigChanged(base, base.copy(name = "换名字")))
        assertTrue(wireGuardLocalTunnelConfigChanged(base, base.copy(allowedIps = listOf("192.168.5.0/24"))))
        assertTrue(wireGuardLocalTunnelConfigChanged(base, base.copy(dnsServers = listOf("223.5.5.5"))))
        assertTrue(wireGuardLocalTunnelConfigChanged(base, base.copy(mtu = 1400)))
        assertTrue(wireGuardEditNeedsRouter(base, base.copy(endpointBindingId = "stun-2")))
        assertTrue(wireGuardEditNeedsRouter(base, base.copy(serverPublicKey = "KEY456")))
    }
}
