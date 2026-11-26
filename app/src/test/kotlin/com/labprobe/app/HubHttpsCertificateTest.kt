package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubHttpsCertificateTest {
    @Test
    fun parsesHttpsStatusWithoutPrivateKeyMaterial() {
        val status = parseHttpsStatus(JSONObject("""{
            "enabled":true,"running":true,"supported":true,"port":58445,
            "domain":"hub.example.com","source":"manual","adminTokenRequired":true,
            "publicUrl":"https://hub.example.com:58445",
            "realtimeUrl":"wss://hub.example.com:58445/api/realtime/ws",
            "mqttUrl":"wss://hub.example.com:58445/mqtt",
            "certificate":{
                "domains":["hub.example.com","*.example.com"],
                "subject":"CN=hub.example.com","issuer":"CN=Test CA",
                "notBefore":1760000000,"notAfter":1893456000,
                "fingerprint":"sha256:abc","source":"manual"
            },
            "lastError":""
        }"""))

        assertTrue(status.enabled)
        assertTrue(status.running)
        assertEquals(58445, status.port)
        assertEquals(listOf("hub.example.com", "*.example.com"), status.certificate?.domains)
        assertEquals("CN=Test CA", status.certificate?.issuer)
        assertEquals("sha256:abc", status.certificate?.fingerprint)
        assertEquals("wss://hub.example.com:58445/api/realtime/ws", status.realtimeUrl)
        assertTrue(status.adminTokenRequired)
    }

    @Test
    fun missingCertificateStaysUnknownRatherThanFabricated() {
        val status = parseHttpsStatus(JSONObject("""{"enabled":false,"running":false}"""))

        assertFalse(status.enabled)
        assertFalse(status.running)
        assertEquals(58445, status.port)
        assertNull(status.certificate)
    }

    @Test
    fun certificateRemindersFireOnceAtSevenThreeAndZeroDays() {
        assertEquals(7, certificateReminderMilestone(7))
        assertEquals(7, certificateReminderMilestone(4))
        assertEquals(3, certificateReminderMilestone(3))
        assertEquals(3, certificateReminderMilestone(1))
        assertEquals(0, certificateReminderMilestone(0))
        assertEquals(null, certificateReminderMilestone(8))
        assertEquals(null, certificateReminderMilestone(-1))
    }

    @Test
    fun certificateEventIdentityIncludesExpirySoRenewalCanNotifyAgain() {
        val item = CertificateExpiryItem(
            id = "cert-1", note = "Hub", domain = "hub.example.com",
            organization = "", provider = "", appliedOn = "2026-01-01", expiresOn = "2026-12-01",
        )
        assertEquals("cert-1:2026-12-01:7", certificateEventKey(item, 7))
        assertNotEquals(certificateEventKey(item, 7), certificateEventKey(item.copy(expiresOn = "2027-12-01"), 7))
        assertNotEquals(certificateEventKey(item, 7, "hub-a"), certificateEventKey(item, 7, "hub-b"))
    }

    @Test
    fun realtimeUrlKeepsHttpsAndPrivateHttpTransportsSeparate() {
        assertEquals("wss://hub.example.com:58445/api/realtime/ws", hubRealtimeUrl("https://hub.example.com:58445/"))
        assertEquals("ws://192.168.1.2:58443/api/realtime/ws", hubRealtimeUrl("http://192.168.1.2:58443/"))
        assertEquals("", hubRealtimeUrl(""))
        assertTrue(hubHttpsUsesEncryptedTransport("https://hub.example.com:58445"))
        assertFalse(hubHttpsUsesEncryptedTransport("http://192.168.1.2:58443"))
    }

    @Test
    fun certificateRecordIdentitySeparatesHubsAndStaysStableOnUpdate() {
        val context = RuntimeEnvironment.getApplication()
        val first = AppPrefs(context, "hub-a")
        val second = AppPrefs(context, "hub-b")
        val status = parseHttpsStatus(JSONObject("""{
            "domain":"hub.example.com","port":58445,
            "certificate":{"fingerprint":"one","issuer":"CN=CA","notBefore":1760000000,"notAfter":1893456000}
        }"""))

        val firstId = hubHttpsCertificateRecordId(first, status.domain, status.port)
        val updatedStatus = parseHttpsStatus(JSONObject("""{
            "domain":"hub.example.com","port":58445,
            "certificate":{"fingerprint":"two","issuer":"CN=CA","notBefore":1760000000,"notAfter":1893456000}
        }"""))

        assertEquals(firstId, hubHttpsCertificateRecordId(first, updatedStatus.domain, updatedStatus.port))
        assertNotEquals(firstId, hubHttpsCertificateRecordId(second, status.domain, status.port))
        assertEquals("hub_https", hubHttpsCertificateExpiryItem(first, status)?.owner)
        assertEquals("CN=CA", hubHttpsCertificateExpiryItem(first, status)?.organization)
    }

    @Test
    fun automaticRecordUpdatesAndDeletesWithoutTouchingManualCertificates() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context, "hub-a")
        val manual = CertificateExpiryItem(
            id = "manual-1", note = "手动证书", domain = "manual.example.com",
            organization = "", provider = "", appliedOn = "2026-01-01", expiresOn = "2027-01-01",
        )
        prefs.saveCertificates(listOf(manual))
        val status = parseHttpsStatus(JSONObject("""{
            "domain":"hub.example.com","port":58445,
            "certificate":{"fingerprint":"one","issuer":"CN=CA","notBefore":1760000000,"notAfter":1893456000}
        }"""))
        val recordId = requireNotNull(hubHttpsCertificateRecordId(prefs, status.domain, status.port))
        val managed = requireNotNull(hubHttpsCertificateExpiryItem(prefs, status))

        updateHubHttpsCertificateRecord(context, prefs, recordId, managed)
        assertEquals(2, prefs.certificates().size)

        val updated = managed.copy(expiresOn = "2028-01-01")
        updateHubHttpsCertificateRecord(context, prefs, recordId, updated)
        assertEquals(2, prefs.certificates().size)
        assertEquals("2028-01-01", prefs.certificates().first { it.id == recordId }.expiresOn)

        updateHubHttpsCertificateRecord(context, prefs, recordId, null)
        assertEquals(listOf(manual), prefs.certificates())
    }
}
