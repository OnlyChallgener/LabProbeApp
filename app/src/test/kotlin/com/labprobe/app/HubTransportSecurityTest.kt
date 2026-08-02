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
    fun publicCleartextIsRejected() {
        try {
            validateHubTransportAddress("http://8.8.8.8:58443")
            fail("public cleartext Hub should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
