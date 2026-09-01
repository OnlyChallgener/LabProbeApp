package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceQuickAccessTest {
    @Test
    fun copyFormatUsesChineseProductAddressRules() {
        assertEquals("https://example.com:9443/path", serviceAddressForCopy("HTTPS", "example.com:9443/path"))
        assertEquals("example.com:8080", serviceAddressForCopy("HTTP", "http://example.com:8080/path"))
        assertEquals("[2001:db8::10]:22", serviceAddressForCopy("SSH", "ssh://[2001:db8::10]:22"))
        assertEquals("[2001:db8::20]:51820", serviceAddressForCopy("WireGuard", "[2001:db8::20]:51820"))
    }

    @Test
    fun onlySupportedServiceTypesExposeQuickAccess() {
        listOf("HTTPS", "HTTP", "RDP", "SSH", "WireGuard").forEach {
            assertTrue(it, serviceSupportsQuickAccess(it))
        }
        listOf("TCP", "UDP", "OpenVPN", "Custom", "").forEach {
            assertFalse(it, serviceSupportsQuickAccess(it))
            assertNull(serviceQuickAccessTarget(it, "example.com:1234"))
        }
    }

    @Test
    fun quickTargetsUseBrowserExternalRdpAndInternalTools() {
        assertEquals(
            ServiceQuickAccessTarget.Browser("http://[2001:db8::30]:8080"),
            serviceQuickAccessTarget("HTTP", "[2001:db8::30]:8080"),
        )
        assertEquals(
            ServiceQuickAccessTarget.Rdp("rdp://example.com:3389"),
            serviceQuickAccessTarget("RDP", "example.com:3389"),
        )
        assertEquals(
            ServiceQuickAccessTarget.Ssh("2001:db8::40", 2222),
            serviceQuickAccessTarget("SSH", "[2001:db8::40]:2222"),
        )
        assertEquals(ServiceQuickAccessTarget.WireGuard, serviceQuickAccessTarget("WireGuard", ""))
    }
}
