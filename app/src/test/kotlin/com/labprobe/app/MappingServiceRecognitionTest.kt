package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun favoriteHintBeatsPortFallback() {
        val result = recognizeMappingService(
            protocol = "TCP",
            internalPort = "443",
            clientIp = "192.168.1.50",
            favoriteHints = mapOf("192.168.1.50" to "SSH"),
        )
        assertEquals("SSH", result.serviceType)
        assertEquals("ssh", result.iconId)
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

    @Test fun zeroTrafficDoesNotReserveMetricRow() {
        val rule = PortMapRule(
            id = "map-1", name = "Plex", enabled = true, mode = "6to4", listenPort = 20001,
            targetType = "manual", targetMode = "ipv4", targetIpv4 = "192.168.1.50",
            targetIpv6 = "", targetIpv6Suffix = "", targetMac = "", targetPort = 32400,
            serviceType = "", transportProtocol = "TCP", preferCurrentPrefix = true,
            expiresAt = null, leaseSeconds = 0L, maxConnections = 32, idleTimeoutSec = 300,
            actualState = "running", runtime = PortMapRuntime(state = "running", startedAt = 1L),
        )
        assertFalse(hasPortMapRuntimeData(rule))
        assertTrue(hasPortMapRuntimeData(rule.copy(runtime = rule.runtime.copy(totalUploadBytes = 1L))))
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
