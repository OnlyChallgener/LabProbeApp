package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortMapEditorModelTest {
    @Test
    fun httpsTemplateUses443OnlyForNewDraft() {
        val draft = applyPortMapServiceTemplate(PortMapDraft.new("20001"), PORT_MAP_SERVICE_TEMPLATES.first { it.label == "HTTPS" })
        assertEquals("443", draft.targetPort)
    }

    @Test
    fun sshAndRdpTemplatesUseTheirTargetPorts() {
        val fresh = PortMapDraft.new("20001")
        assertEquals("22", applyPortMapServiceTemplate(fresh, PORT_MAP_SERVICE_TEMPLATES.first { it.label == "SSH" }).targetPort)
        assertEquals("3389", applyPortMapServiceTemplate(fresh, PORT_MAP_SERVICE_TEMPLATES.first { it.label == "RDP" }).targetPort)
    }

    @Test
    fun customTemplateLeavesTargetPortBlank() {
        val draft = applyPortMapServiceTemplate(
            PortMapDraft.new("20001").copy(targetPort = "443"),
            PORT_MAP_SERVICE_TEMPLATES.first { it.label == "自定义" }
        )
        assertEquals("", draft.targetPort)
        assertEquals("", draft.targetIpv4)
    }

    @Test
    fun selectedServiceTypeIsPersistedWithoutTemplateField() {
        val json = applyPortMapServiceTemplate(
            PortMapDraft.new("20001").copy(name = "NAS HTTPS"),
            PORT_MAP_SERVICE_TEMPLATES.first { it.label == "HTTPS" }
        ).toJson()
        assertEquals("NAS HTTPS", json.getString("name"))
        assertEquals(443, json.getInt("targetPort"))
        assertFalse(json.has("serviceTemplate"))
        assertEquals("HTTPS", json.getString("serviceType"))
    }

    @Test
    fun editPreservesExistingMappingParameters() {
        val original = sampleRule(enabled = false, mode = "6to6", targetPort = 8443)
        val edited = PortMapDraft.from(original).copy(name = "新名称")
        assertEquals(original.id, edited.id)
        assertEquals(original.mode, edited.mode)
        assertEquals(original.listenPort.toString(), edited.listenPort)
        assertEquals("8443", edited.targetPort)
        assertEquals(original.targetMac, edited.targetMac)
        assertFalse(edited.enabled)
    }

    @Test
    fun stoppedEditSaveDoesNotForceStart() {
        val stopped = PortMapDraft.from(sampleRule(enabled = false)).copy(name = "手动编辑")
        assertFalse(portMapDraftForSave(stopped).enabled)
    }

    @Test
    fun newSaveEnablesTheRule() {
        assertTrue(portMapDraftForSave(PortMapDraft.new("20001")).enabled)
    }

    @Test
    fun manualTargetPathRemainsAvailableWithoutDeviceSelection() {
        val draft = PortMapDraft.new("20001").copy(
            name = "手动服务",
            targetIpv4 = "192.168.5.46",
            targetPort = "8080",
            targetMac = ""
        )
        assertEquals("192.168.5.46", draft.targetIpv4)
        assertEquals("8080", draft.targetPort)
        assertEquals("", draft.targetMac)
    }

    @Test
    fun validationErrorsMapNearTheirFields() {
        assertEquals("service", portMapValidationField("请输入规则名称"))
        assertEquals("externalPort", portMapValidationField("监听端口必须在 20000-20020"))
        assertEquals("target", portMapValidationField("请输入目标 IPv4"))
        assertEquals("advanced", portMapValidationField("空闲超时应为 30-3600 秒"))
    }

    @Test
    fun suffixModeKeepsFullIpv6SnapshotAndFillsLower64Bits() {
        val full = "2409:8a50:2e40:8dc0:a9e5:169d:a7c8:9bfe"
        val draft = switchPortMapTargetMode(
            PortMapDraft.new("20001").copy(targetIpv6 = full),
            "ipv6_suffix",
        )
        assertEquals("ipv6_suffix", draft.targetMode)
        assertEquals(full, draft.targetIpv6Snapshot)
        assertEquals("::a9e5:169d:a7c8:9bfe", draft.targetIpv6Suffix)
    }

    @Test
    fun legacyDraftSerializesTcpTransportByDefault() {
        val json = PortMapDraft.new("20001").copy(
            name = "NAS",
            targetIpv4 = "192.168.5.46",
            targetPort = "443",
        ).toJson()
        assertEquals("TCP", json.getString("transportProtocol"))
    }

    @Test
    fun udpTemplatesPersistTheirProtocolAndPorts() {
        val dns = applyPortMapServiceTemplate(
            PortMapDraft.new("20001"),
            PORT_MAP_SERVICE_TEMPLATES.first { it.label == "DNS" },
        )
        val wireGuard = applyPortMapServiceTemplate(
            PortMapDraft.new("20002"),
            PORT_MAP_SERVICE_TEMPLATES.first { it.label == "WireGuard" },
        )
        assertEquals("UDP", dns.transportProtocol)
        assertEquals("53", dns.targetPort)
        assertEquals("UDP", wireGuard.transportProtocol)
        assertEquals("51820", wireGuard.targetPort)
    }

    @Test
    fun legacyUdpRulesUseTheSharedServiceTypeFallback() {
        assertEquals("DNS", defaultPortMapServiceType(53, "UDP"))
        assertEquals("OpenVPN", defaultPortMapServiceType(1194, "UDP"))
        assertEquals("WireGuard", defaultPortMapServiceType(51820, "UDP"))
        assertEquals("Custom", defaultPortMapServiceType(9999, "UDP"))
        assertEquals("TCP", defaultPortMapServiceType(9999, "TCP"))
    }

    @Test
    fun suffixModeUsesSavedIpv6SnapshotWhenFullFieldWasCleared() {
        val full = "2409:8a50:2e40:8dc0:a9e5:169d:a7c8:9bfe"
        val draft = switchPortMapTargetMode(
            PortMapDraft.new("20001").copy(targetIpv6Snapshot = full),
            "ipv6_suffix",
        )
        assertEquals("::a9e5:169d:a7c8:9bfe", draft.targetIpv6Suffix)
    }

    private fun sampleRule(enabled: Boolean, mode: String = "6to4", targetPort: Int = 443) = PortMapRule(
        id = "mapping-1",
        name = "旧服务",
        enabled = enabled,
        mode = mode,
        listenPort = 20001,
        targetMode = if (mode == "6to6") "ipv6_suffix" else "ipv4",
        targetIpv4 = "192.168.5.46",
        targetIpv6 = "",
        targetIpv6Suffix = "::1234",
        targetMac = "aa:bb:cc:dd:ee:ff",
        targetPort = targetPort,
        preferCurrentPrefix = true,
        expiresAt = null,
        leaseSeconds = 0L,
        maxConnections = 32,
        idleTimeoutSec = 300,
    )
}
