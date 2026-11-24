package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingServiceRecognitionTest {
    private fun device(
        name: String,
        ip: String,
        manufacture: String = "",
        devType: String = "",
        manualType: String = "",
    ) = DeviceItem(
        name = name,
        mac = "AA:BB:CC:DD:EE:01",
        online = true,
        ip = ip,
        ssid = "",
        band = "",
        rssi = "",
        rxrate = "",
        onlineSince = "",
        offlineAt = "",
        onlineDurationText = "",
        lastSeenAt = "",
        manufacture = manufacture,
        devType = devType,
        manualType = manualType,
    )

    @Test fun explicitTypeAlwaysWinsOverNameDeviceAndPort() {
        val result = recognizeMappingService(
            name = "Plex SSH",
            explicitType = "HTTPS",
            protocol = "UDP",
            internalPort = "22",
            clientIp = "192.168.1.50",
            devices = listOf(device("飞牛NAS", "192.168.1.50", "fnos", "nas")),
        )
        assertEquals("HTTPS", result.serviceType)
        assertEquals("web", result.iconId)
    }

    @Test fun strongNameKeywordsAreCaseInsensitiveAndTokenMatched() {
        assertEquals("SSH", recognizeMappingService("OpenSSH service").serviceType)
        assertEquals("RDP", recognizeMappingService("remote desktop").serviceType)
        assertEquals("DLNA", recognizeMappingService("MiniDLNA").serviceType)
        assertEquals("Plex", recognizeMappingService("PLEX media").serviceType)
        assertEquals("qBittorrent", recognizeMappingService("qBittorrent").serviceType)
        assertEquals("", recognizeMappingService("wgcloud").serviceType)
        assertEquals("", recognizeMappingService("complex").serviceType)
        assertEquals("", recognizeMappingService("network switch").serviceType)
    }

    @Test fun internalPortRulesUseProtocolAndNeverAmbiguousPorts() {
        assertEquals("SSH", recognizeMappingService(protocol = "TCP", internalPort = "22").serviceType)
        assertEquals("SSH", recognizeMappingService(protocol = "TCP", internalPort = "54133").serviceType)
        assertEquals("", recognizeMappingService(protocol = "UDP", internalPort = "22").serviceType)
        assertEquals("", recognizeMappingService(protocol = "UDP", internalPort = "1900").serviceType)
        assertEquals("", recognizeMappingService(protocol = "TCP", internalPort = "554").serviceType)
        assertEquals("HTTPS", recognizeMappingService(protocol = "TCP", internalPort = "443").serviceType)
    }

    @Test fun nasBrandPortsRequireDeviceContext() {
        val unknown = recognizeMappingService(protocol = "TCP", internalPort = "9443")
        assertEquals("", unknown.serviceType)
        assertEquals("tcp", unknown.iconId)

        val ugreen = recognizeMappingService(
            protocol = "TCP",
            internalPort = "9443",
            clientIp = "192.168.1.50",
            devices = listOf(device("UGREEN NAS", "192.168.1.50", "UGREEN", "nas")),
        )
        assertEquals("绿联NAS", ugreen.serviceType)
        assertEquals("ugreen", ugreen.iconId)

        val fnos = recognizeMappingService(
            protocol = "TCP",
            internalPort = "5666",
            clientIp = "192.168.1.51",
            devices = listOf(device("飞牛NAS", "192.168.1.51", "fnOS", "nas")),
        )
        assertEquals("飞牛NAS", fnos.serviceType)
        assertEquals("fnos", fnos.iconId)
    }

    @Test fun deviceMatchUsesIpAndPreservesOriginalNameFallback() {
        val result = recognizeMappingService(
            name = "NAT-PMP Mapping",
            protocol = "UDP",
            internalPort = "5351",
            clientIp = "192.168.1.66",
            devices = listOf(device("客厅主机", "192.168.1.66", "", "desktop")),
        )
        assertEquals("", result.serviceType)
        assertEquals("NAT-PMP", result.mechanism)
        assertEquals("udp", result.iconId)
    }

    @Test fun favoriteHintBeatsPortFallbackOnlyForExactEndpoint() {
        val hint = FavoriteServiceHint(
            host = "192.168.1.50", port = 443, serviceType = "SSH", protocol = "TCP",
            iconId = "ssh", title = "NAS SSH", mappingOwned = true,
        )
        val exact = recognizeMappingService(
            protocol = "TCP", internalPort = "443", clientIp = "192.168.1.50",
            favoriteHints = listOf(hint),
        )
        assertEquals("SSH", exact.serviceType)
        assertEquals("ssh", exact.iconId)
        val wrongPort = recognizeMappingService(
            protocol = "TCP", internalPort = "8443", clientIp = "192.168.1.50",
            favoriteHints = listOf(hint),
        )
        assertEquals("", wrongPort.serviceType)
        val wrongProtocol = recognizeMappingService(
            protocol = "UDP", internalPort = "443", clientIp = "192.168.1.50",
            favoriteHints = listOf(hint),
        )
        assertEquals("", wrongProtocol.serviceType)
    }

    @Test fun sameDeviceServicesAreMatchedIndependentlyByPortAndProtocol() {
        val favorites = listOf(
            FavoriteShortcut(
                id = "ssh", title = "NAS SSH", description = "", iconType = "builtin", iconValue = "ssh",
                lanUrl = "192.168.1.50:22", wanUrl = "", order = 0, serviceType = "SSH", protocol = "TCP",
            ),
            FavoriteShortcut(
                id = "plex", title = "Plex", description = "", iconType = "builtin", iconValue = "media",
                lanUrl = "192.168.1.50:32400", wanUrl = "", order = 1, serviceType = "Plex", protocol = "TCP",
            ),
        )
        val hints = favoriteServiceHints(favorites)
        val ssh = recognizeMappingService(
            protocol = "TCP", internalPort = "22", clientIp = "192.168.1.50",
            devices = listOf(device("UGREEN NAS", "192.168.1.50", "UGREEN", "nas")), favoriteHints = hints,
        )
        val plex = recognizeMappingService(
            protocol = "TCP", internalPort = "32400", clientIp = "192.168.1.50",
            devices = listOf(device("UGREEN NAS", "192.168.1.50", "UGREEN", "nas")), favoriteHints = hints,
        )
        assertEquals("SSH", ssh.serviceType)
        assertEquals("Plex", plex.serviceType)
        assertEquals("media", plex.iconId)
    }

    @Test fun strongPortRecognitionBeatsDeviceBrandFallback() {
        val result = recognizeMappingService(
            protocol = "TCP", internalPort = "22", clientIp = "192.168.1.50",
            devices = listOf(device("UGREEN NAS", "192.168.1.50", "UGREEN", "nas")),
        )
        assertEquals("SSH", result.serviceType)
        assertEquals("ssh", result.iconId)
    }

    @Test fun favoriteDefaultPortsAreMatched() {
        val hints = favoriteServiceHints(
            listOf(
                FavoriteShortcut(
                    id = "https", title = "NAS HTTPS", description = "", iconType = "builtin", iconValue = "web",
                    lanUrl = "https://nas.example.test", wanUrl = "", order = 0, serviceType = "HTTPS",
                )
            )
        )
        assertEquals(443, hints.single().port)
        val result = recognizeMappingService(
            protocol = "TCP", internalPort = "443", clientIp = "nas.example.test", favoriteHints = hints,
        )
        assertEquals("HTTPS", result.serviceType)
    }

    @Test fun equivalentIpv6FavoriteHostFormsMatch() {
        val favorite = FavoriteShortcut(
            id = "ipv6-plex", title = "IPv6 Plex", description = "", iconType = "builtin", iconValue = "media",
            lanUrl = "http://[2409:8a50:2e41:b700::3333:4444]:32400", wanUrl = "", order = 0,
            serviceType = "Plex", protocol = "TCP",
        )
        val hints = favoriteServiceHints(listOf(favorite))
        assertEquals(1, hints.size)
        assertEquals(32400, hints.single().port)
        assertEquals("TCP", hints.single().protocol)
        assertEquals(normalizeIpv6("2409:8a50:2e41:b700::3333:4444"), normalizeIpv6(hints.single().host))
        val result = recognizeMappingService(
            protocol = "TCP", internalPort = "32400",
            clientIp = "2409:8a50:02e41:b700:0000:0000:3333:4444",
            favoriteHints = hints,
        )
        assertEquals("Plex", result.serviceType)
    }

    @Test fun genericFavoriteProtocolIsNotAWildcard() {
        val favorite = FavoriteShortcut(
            id = "generic", title = "Generic TCP", description = "", iconType = "builtin", iconValue = "tcp",
            lanUrl = "192.168.1.50:9000", wanUrl = "", order = 0, serviceType = "TCP",
        )
        val hints = favoriteServiceHints(listOf(favorite))
        assertEquals("TCP", hints.single().protocol)
        assertEquals(
            "TCP",
            recognizeMappingService(protocol = "TCP", internalPort = "9000", clientIp = "192.168.1.50", favoriteHints = hints).serviceType,
        )
        assertEquals(
            "",
            recognizeMappingService(protocol = "UDP", internalPort = "9000", clientIp = "192.168.1.50", favoriteHints = hints).serviceType,
        )
    }

    @Test fun stunFavoriteProtocolFollowsSourceRule() {
        val existing = FavoriteShortcut(
            id = "stun-1", title = "STUN", description = "", iconType = "builtin", iconValue = "server",
            lanUrl = "192.168.1.50:22", wanUrl = "", order = 0, type = "stun", stunRuleId = "stun-1",
            serviceType = "SSH", protocol = "TCP",
        )
        val rule = StunRule(
            id = "stun-1", name = "STUN", enabled = true, listenPort = 20001,
            targetIpv4 = "192.168.1.50", targetPort = 22, serviceType = "SSH", transportProtocol = "UDP",
            forwardMode = "router_native", actualState = "mapped", firewallState = "ready", nativeMappingState = "ready",
            runtime = StunRuntime(),
        )
        assertEquals("UDP", favoriteFromStunRule(rule, existing)!!.protocol)
    }

    @Test fun missingMetricsUsePlaceholdersButExplicitZeroDoesNot() {
        val rule = PortMapRule(
            id = "map-1", name = "Plex", enabled = true, mode = "6to4", listenPort = 20001,
            targetType = "manual", targetMode = "ipv4", targetIpv4 = "192.168.1.50",
            targetIpv6 = "", targetIpv6Suffix = "", targetMac = "", targetPort = 32400,
            serviceType = "", transportProtocol = "TCP", preferCurrentPrefix = true,
            expiresAt = null, leaseSeconds = 0L, maxConnections = 32, idleTimeoutSec = 300,
        )
        assertEquals("—", portMapMetricValue(rule, PortMapRuntimeMetric.UPLOAD_BYTES, "0B"))
        val available = rule.copy(runtime = rule.runtime.copy(availableMetrics = setOf(PortMapRuntimeMetric.UPLOAD_BYTES)))
        assertEquals("0B", portMapMetricValue(available, PortMapRuntimeMetric.UPLOAD_BYTES, "0B"))
    }

    @Test fun natPmpMechanismSurvivesApplicationRecognition() {
        val result = recognizeMappingService(
            name = "NAT-PMP Mapping", protocol = "TCP", internalPort = "22",
            favoriteHints = listOf(FavoriteServiceHint("192.168.1.50", 22, "SSH", "TCP", "ssh", "NAS SSH", true)),
            clientIp = "192.168.1.50",
        )
        assertEquals("SSH", result.serviceType)
        assertEquals("NAT-PMP", result.mechanism)
    }

    @Test fun manualDeviceTypeParticipatesInRecognition() {
        val nas = recognizeMappingService(
            protocol = "TCP",
            internalPort = "32400",
            clientIp = "192.168.1.50",
            devices = listOf(device("Generic storage", "192.168.1.50", manualType = "nas")),
        )
        assertEquals("NAS", nas.serviceType)
        val camera = recognizeMappingService(
            protocol = "TCP",
            internalPort = "8080",
            clientIp = "192.168.1.51",
            devices = listOf(device("Generic camera", "192.168.1.51", manualType = "camera")),
        )
        assertEquals("视频监控", camera.serviceType)
    }

    @Test fun editingImplicitRuleDoesNotPersistInferredType() {
        val rule = PortMapRule(
            id = "map-1", name = "Plex", enabled = true, mode = "6to4", listenPort = 20001,
            targetType = "manual", targetMode = "ipv4", targetIpv4 = "192.168.1.50",
            targetIpv6 = "", targetIpv6Suffix = "", targetMac = "", targetPort = 32400,
            serviceType = "", transportProtocol = "TCP", preferCurrentPrefix = true,
            expiresAt = null, leaseSeconds = 0L, maxConnections = 32, idleTimeoutSec = 300,
        )
        assertEquals("", PortMapDraft.from(rule).serviceType)
    }

    @Test fun zeroRuntimeMetricsRemainAvailableWhenPresent() {
        val parsed = parsePortMapRule(JSONObject("""
            {"id":"map-1","name":"Plex","enabled":true,"mode":"6to4","listenPort":20001,
             "targetIpv4":"192.168.1.50","targetPort":32400,"runtime":{
               "state":"running","activeConnections":0,"totalUploadBytes":0,"totalDownloadBytes":0,
               "totalUploadPackets":0,"totalDownloadPackets":0}}
        """.trimIndent()))
        assertEquals(PortMapRuntimeMetric.entries.toSet(), parsed.runtime.availableMetrics)
        val missing = parsePortMapRule(JSONObject("""
            {"id":"map-1","name":"Plex","enabled":true,"mode":"6to4","listenPort":20001,
             "targetIpv4":"192.168.1.50","targetPort":32400,"runtime":{"state":"running"}}
        """.trimIndent()))
        assertTrue(missing.runtime.availableMetrics.isEmpty())
    }

    @Test fun upnpStatusUsesOnlySnapshotAndServiceState() {
        val enabled = UpnpState(enabled = true, mappings = listOf(UpnpMapping(name = "Plex")))
        val disabled = enabled.copy(enabled = false)
        assertEquals("已映射", upnpMappingStatus(enabled, stale = false, error = ""))
        assertEquals("已停用", upnpMappingStatus(disabled, stale = false, error = ""))
        assertEquals("待同步", upnpMappingStatus(enabled, stale = true, error = ""))
        assertEquals("待同步", upnpMappingStatus(enabled, stale = false, error = "timeout"))
        assertEquals("待同步", upnpMappingStatus(null, stale = false, error = ""))
    }

    @Test fun upnpParserPreservesRawNameAndRealFields() {
        val state = parseUpnp(JSONObject("""
            {"enable_upnp":"true","wan":"wan","upnpds":[
              {"name":"NAT-PMP Mapping","sip":"192.168.1.120","proto":"udp","sport":"5351","dport":"49152"},
              {"name":"","sip":"192.168.1.50","proto":"TCP","sport":"32400","dport":"32400"}
            ]}
        """.trimIndent()))
        assertTrue(state.enabled)
        assertEquals("WAN", state.wan)
        assertEquals("NAT-PMP Mapping", state.mappings[0].name)
        assertEquals("", state.mappings[1].name)
        assertEquals("49152", state.mappings[0].externalPort)
        assertEquals("32400", state.mappings[1].internalPort)
    }
}
