package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteLinkModelTest {
    @Test
    fun oldFavoriteJsonDefaultsToManualWithoutMigration() {
        val raw = JSONArray().put(
            JSONObject()
                .put("id", "old-1")
                .put("title", "NAS")
                .put("description", "old")
                .put("iconType", "builtin")
                .put("iconValue", "server")
                .put("lanUrl", "http://192.168.5.10:5000")
                .put("wanUrl", "https://old.example.com:20000")
                .put("order", 4)
        ).toString()

        val favorite = parseFavoriteShortcutsJson(raw).single()
        assertEquals("manual", favorite.type)
        assertNull(favorite.mappingId)
        assertNull(favorite.ddnsRecordId)
        assertNull(favorite.deviceId)
        assertEquals("", favorite.localEndpoint)
        assertEquals("", favorite.remoteEndpoint)
        assertEquals("", favorite.serviceType)
        assertEquals(4, favorite.order)
    }

    @Test
    fun oldFavoriteRoundTripKeepsExistingFieldsAndOpenAddresses() {
        val raw = JSONArray().put(
            JSONObject()
                .put("id", "old-1")
                .put("title", "NAS")
                .put("description", "old")
                .put("iconType", "builtin")
                .put("iconValue", "server")
                .put("lanUrl", "http://192.168.5.10:5000")
                .put("wanUrl", "https://old.example.com:20000/admin")
                .put("order", 0)
        ).toString()

        val before = parseFavoriteShortcutsJson(raw).single()
        val after = parseFavoriteShortcutsJson(serializeFavoriteShortcutsJson(listOf(before))).single()
        assertEquals(before.copy(type = "manual"), after)
        assertEquals(before.lanUrl, after.lanUrl)
        assertEquals(before.wanUrl, after.wanUrl)
    }

    @Test
    fun mappingFavoriteRoundTripKeepsAssociationFields() {
        val before = FavoriteShortcut(
            id = "favorite-1",
            title = "NAS HTTPS",
            description = "remote service",
            iconType = "builtin",
            iconValue = "server",
            lanUrl = "http://192.168.5.46:443",
            wanUrl = "https://old.example.com:20000/admin",
            order = 0,
            type = "mapping",
            mappingId = "map-1",
            ddnsRecordId = "ddns-1",
            deviceId = "aa:bb:cc:dd:ee:ff",
            localEndpoint = "http://192.168.5.46:443",
            remoteEndpoint = "tcp://[::]:20000",
            serviceType = "HTTPS",
        )

        val after = parseFavoriteShortcutsJson(serializeFavoriteShortcutsJson(listOf(before))).single()
        assertEquals("mapping", after.type)
        assertEquals("map-1", after.mappingId)
        assertEquals("ddns-1", after.ddnsRecordId)
        assertEquals("aa:bb:cc:dd:ee:ff", after.deviceId)
        assertEquals(before.localEndpoint, after.localEndpoint)
        assertEquals(before.remoteEndpoint, after.remoteEndpoint)
        assertEquals("HTTPS", after.serviceType)
        assertEquals(before.wanUrl, after.wanUrl)
    }

    @Test
    fun mappingRuleBuildsServiceFavoriteWithoutDuplicatingRuleFields() {
        val favorite = favoriteFromPortMapRule(sampleRule("map-1"))
        assertEquals("mapping-map-1", favorite.id)
        assertEquals("mapping", favorite.type)
        assertEquals("map-1", favorite.mappingId)
        assertEquals("aa:bb:cc:dd:ee:ff", favorite.deviceId)
        assertEquals("HTTPS", favorite.serviceType)
        assertEquals("https://192.168.5.46:443", favorite.localEndpoint)
        assertEquals("", favorite.remoteEndpoint)
        assertEquals(favorite.localEndpoint, favorite.lanUrl)
    }

    @Test
    fun suffixMappingUsesTheSelectedFullIpv6SnapshotForLocalEndpoint() {
        val rule = sampleRule("map-6").copy(
            mode = "6to6",
            targetMode = "ipv6_suffix",
            targetIpv4 = "",
            targetIpv6 = "2409:8a50:2e40:8dc0:a9e5:169d:a7c8:9bfe",
            targetIpv6Suffix = "::a9e5:169d:a7c8:9bfe",
        )
        assertEquals(
            "https://[2409:8a50:2e40:8dc0:a9e5:169d:a7c8:9bfe]:443",
            favoriteFromPortMapRule(rule).localEndpoint,
        )
    }

    @Test
    fun suffixMappingCanUseCanonicalDeviceFallbackWhenSnapshotIsMissing() {
        val rule = sampleRule("map-6").copy(
            mode = "6to6",
            targetMode = "ipv6_suffix",
            targetIpv4 = "",
            targetIpv6 = "",
            targetIpv6Suffix = "::a9e5:169d:a7c8:9bfe",
        )
        assertEquals(
            "https://[2409:8a50:2e40:8dc0:a9e5:169d:a7c8:9bfe]:443",
            favoriteFromPortMapRule(rule, fallbackIpv6 = "2409:8a50:2e40:8dc0:a9e5:169d:a7c8:9bfe").localEndpoint,
        )
    }

    @Test
    fun serviceStatusUsesEndpointAndMissingMappingIsUnreachable() {
        val favorite = sampleFavorite(mappingId = "map-1").copy(
            localEndpoint = "http://192.168.5.46:443",
            remoteEndpoint = "https://remote.example.com:20000",
            serviceType = "HTTPS",
        )
        assertEquals("内网", favoriteServiceStatus(favorite, "lan", resolveFavoriteMapping(favorite, listOf(sampleRule("map-1")))))
        assertEquals("外网", favoriteServiceStatus(favorite, "wan", resolveFavoriteMapping(favorite, listOf(sampleRule("map-1")))))
        assertEquals("当前不可达", favoriteServiceStatus(favorite, "wan", resolveFavoriteMapping(favorite, emptyList())))
    }

    @Test
    fun mappingFavoriteResolvesExistingRule() {
        val rule = sampleRule("map-1")
        val result = resolveFavoriteMapping(sampleFavorite(mappingId = "map-1"), listOf(rule))
        assertSame(rule, result.rule)
        assertFalse(result.missing)
    }

    @Test
    fun missingMappingDoesNotInvalidateFavorite() {
        val favorite = sampleFavorite(mappingId = "missing")
        val result = resolveFavoriteMapping(favorite, emptyList())
        assertNull(result.rule)
        assertTrue(result.missing)
        assertEquals("mapping", favorite.type)
    }

    @Test
    fun ddnsHostnameReplacementPreservesUrlComponents() {
        val favorite = sampleFavorite(ddnsRecordId = "ddns-1").copy(
            wanUrl = "https://old.example.com:20000/test?a=1#fragment"
        )
        val snapshot = LabProbeDdnsSnapshot(
            records = listOf(LabProbeDdnsRecord(id = "ddns-1", hostname = "new.example.com"))
        )
        assertEquals(
            "https://new.example.com:20000/test?a=1#fragment",
            resolveFavoriteRemoteUrl(favorite, snapshot)
        )
    }

    @Test
    fun remoteEndpointUsesDdnsHostWithoutChangingTheExternalAddressFamily() {
        val favorite = sampleFavorite(ddnsRecordId = "ddns-1").copy(
            remoteEndpoint = "https://198.51.100.9:20000/test?a=1"
        )
        val snapshot = LabProbeDdnsSnapshot(
            records = listOf(LabProbeDdnsRecord(id = "ddns-1", hostname = "new.example.com"))
        )
        assertEquals(
            "https://new.example.com:20000/test?a=1",
            resolveFavoriteRemoteEndpoint(favorite, snapshot)
        )
    }

    @Test
    fun wildcardListenerIsNotExposedAsRemoteFavoriteEndpoint() {
        val favorite = sampleFavorite(ddnsRecordId = "missing").copy(remoteEndpoint = "tcp://[::]:20000", wanUrl = "")
        assertEquals("", resolveFavoriteRemoteEndpoint(favorite, LabProbeDdnsSnapshot()))
    }

    @Test
    fun routerDdnsAssociationResolvesItsConfiguredHostname() {
        val favorite = sampleFavorite(ddnsRecordId = "router:svc-1").copy(
            remoteEndpoint = "https://old.example.com:20000/path",
        )
        val native = listOf(DdnsRecord(serviceId = "svc-1", domain = "router.example.com"))
        assertEquals(
            "https://router.example.com:20000/path",
            resolveFavoriteRemoteEndpoint(favorite, LabProbeDdnsSnapshot(), nativeDdnsRecords = native),
        )
    }

    @Test
    fun serviceCopyUsesHostAndPortForNonWebProtocols() {
        assertEquals("example.com:22", favoriteAddressForCopy("ssh://example.com:22", "SSH"))
        assertEquals("[2409:8a50::10]:22", favoriteAddressForCopy("ssh://[2409:8a50::10]:22", "SSH"))
        assertEquals("https://example.com:9443/path", favoriteAddressForCopy("https://example.com:9443/path", "HTTPS"))
    }

    @Test
    fun linkedMappingBuildsItsRemoteEndpointFromTheCurrentDdnsHostname() {
        val favorite = sampleFavorite(mappingId = "map-1", ddnsRecordId = "ddns-1").copy(
            serviceType = "HTTPS",
            remoteEndpoint = "",
            wanUrl = "",
        )
        val snapshot = LabProbeDdnsSnapshot(
            records = listOf(LabProbeDdnsRecord(id = "ddns-1", hostname = "home.example.com"))
        )
        assertEquals(
            "https://home.example.com:20000",
            resolveFavoriteRemoteEndpoint(favorite, snapshot, listOf(sampleRule("map-1")))
        )
    }

    @Test
    fun ddnsReplacementHandlesUserInfoAndIpv6LiteralSource() {
        val favorite = sampleFavorite(ddnsRecordId = "ddns-1").copy(
            wanUrl = "https://user:pass@[2001:db8::1]:20000/test?a=1#fragment"
        )
        val snapshot = LabProbeDdnsSnapshot(
            records = listOf(LabProbeDdnsRecord(id = "ddns-1", hostname = "new.example.com"))
        )
        assertEquals(
            "https://user:pass@new.example.com:20000/test?a=1#fragment",
            resolveFavoriteRemoteUrl(favorite, snapshot)
        )
    }

    @Test
    fun missingDdnsRecordFallsBackToOriginalWanUrl() {
        val favorite = sampleFavorite(ddnsRecordId = "missing").copy(wanUrl = "https://old.example.com:20000/test")
        assertEquals(favorite.wanUrl, resolveFavoriteRemoteUrl(favorite, LabProbeDdnsSnapshot()))
        assertEquals(favorite.wanUrl, resolveFavoriteRemoteUrl(favorite, null))
    }

    @Test
    fun manualFavoriteKeepsExistingRemoteUrlBehavior() {
        val favorite = sampleFavorite(ddnsRecordId = null).copy(type = "manual", wanUrl = "https://manual.example.com/app")
        val snapshot = LabProbeDdnsSnapshot(records = listOf(LabProbeDdnsRecord(id = "ddns-1", hostname = "new.example.com")))
        assertEquals(favorite.wanUrl, resolveFavoriteRemoteUrl(favorite, snapshot))
        assertEquals("manual", favorite.type)
        assertFalse(resolveFavoriteMapping(favorite, emptyList()).missing)
    }

    private fun sampleFavorite(mappingId: String? = null, ddnsRecordId: String? = null) = FavoriteShortcut(
        id = "favorite-1",
        title = "NAS HTTPS",
        description = "remote service",
        iconType = "builtin",
        iconValue = "server",
        lanUrl = "http://192.168.5.46:443",
        wanUrl = "https://old.example.com:20000/admin",
        order = 0,
        type = if (mappingId != null) "mapping" else "manual",
        mappingId = mappingId,
        ddnsRecordId = ddnsRecordId,
    )

    private fun sampleRule(id: String) = PortMapRule(
        id = id,
        name = "NAS HTTPS",
        enabled = true,
        mode = "6to4",
        listenPort = 20000,
        targetMode = "ipv4",
        targetIpv4 = "192.168.5.46",
        targetIpv6 = "",
        targetIpv6Suffix = "",
        targetMac = "aa:bb:cc:dd:ee:ff",
        targetPort = 443,
        preferCurrentPrefix = true,
        expiresAt = null,
        leaseSeconds = 0L,
        maxConnections = 32,
        idleTimeoutSec = 300,
    )
}
