package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class HubTransportSecurityTest {
    @Test
    fun httpsIsAcceptedForPublicHosts() {
        assertEquals("https://hub.example.com", validateHubTransportAddress("https://hub.example.com"))
    }

    @Test
    fun privateLanHttpIsAccepted() {
        assertEquals("http://192.168.5.2:58443", validateHubTransportAddress("http://192.168.5.2:58443"))
        assertEquals("http://[fd00::2]:58443", validateHubTransportAddress("http://[fd00::2]:58443"))
    }

    @Test
    fun hubAddressNormalizationRules() {
        // User requested tests:
        // 192.168.5.46:58443
        // http://192.168.5.46:58443
        // https://example.com
        // 前两个最终都应规范化为：http://192.168.5.46:58443
        assertEquals("http://192.168.5.46:58443", validateHubTransportAddress("192.168.5.46:58443"))
        assertEquals("http://192.168.5.46:58443", validateHubTransportAddress("http://192.168.5.46:58443"))
        assertEquals("https://example.com", validateHubTransportAddress("https://example.com"))

        assertEquals("http://192.168.5.46:58443", normalizeHubBaseUrl("192.168.5.46:58443"))
        assertEquals("http://192.168.5.46:58443", normalizeHubBaseUrl("http://192.168.5.46:58443"))
        assertEquals("https://example.com", normalizeHubBaseUrl("https://example.com"))

        assertEquals("192.168.5.46:58443", normalizeHubAddressForDisplay("192.168.5.46:58443"))
        assertEquals("192.168.5.46:58443", normalizeHubAddressForDisplay("http://192.168.5.46:58443"))
        assertEquals("https://example.com", normalizeHubAddressForDisplay("https://example.com"))
    }

    @Test
    fun publicCleartextIsRejected() {
        try {
            validateHubTransportAddress("http://8.8.8.8:58443")
            fail("public cleartext Hub should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            validateHubTransportAddress("8.8.8.8:58443")
            fail("public cleartext Hub without scheme should also be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
