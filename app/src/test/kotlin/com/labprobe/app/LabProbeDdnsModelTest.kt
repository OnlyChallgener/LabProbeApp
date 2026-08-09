package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class LabProbeDdnsModelTest {
    @Test
    fun recordJsonKeepsAAndAAAAIndependentAndDoesNotAddCredentialsByDefault() {
        val json = LabProbeDdnsRecord(
            provider = "cloudflare",
            hostname = "home.example.com",
            recordTypes = listOf("A", "AAAA"),
        ).toJson()

        assertEquals("cloudflare", json.getString("provider"))
        assertEquals(2, json.getJSONArray("recordTypes").length())
        assertFalse(json.has("credentials"))
    }

    @Test
    fun configuredRecordSavedWithoutCredentialInputDoesNotClearTheSecret() {
        val json = LabProbeDdnsRecord(
            id = "existing",
            provider = "cloudflare",
            hostname = "home.example.com",
            credentialsConfigured = true,
        ).toJson(emptyMap())

        assertFalse(json.has("credentials"))
    }

    @Test
    fun credentialsAreOnlyAddedWhenTheUserEnteredThem() {
        val json = LabProbeDdnsRecord(provider = "duckdns", hostname = "home").toJson(mapOf("token" to "new-token"))

        assertTrue(json.has("credentials"))
        assertEquals("new-token", json.getJSONObject("credentials").getString("token"))
    }

    @Test
    fun hubSnapshotParsingKeepsStatesAndDetectedPublishedSeparate() {
        val providers = JSONArray().apply {
            listOf("alidns", "dnspod", "cloudflare", "dynv6", "duckdns", "desec", "dynu", "ipv64").forEach {
                put(JSONObject().put("id", it).put("supportsA", true).put("supportsAAAA", true))
            }
        }
        val root = JSONObject()
            .put("address", JSONObject()
                .put("detectedIpv6", "2409::1")
                .put("ipv6State", "public")
                .put("ipv6Source", "default-route:wan6")
                .put("detectedAt", 1_786_249_000L))
            .put("providers", providers)
            .put("records", JSONArray().put(JSONObject()
                .put("id", "r1")
                .put("provider", "cloudflare")
                .put("hostname", "home.example.com")
                .put("recordTypes", JSONArray().put("AAAA"))
                .put("detectedIpv6", "2409::1")
                .put("ipv6State", "public")
                .put("publishedIpv6", "2409::old")
                .put("recordValues", JSONObject().put("TXT", "verification"))
                .put("publishedValues", JSONObject().put("TXT", "old-verification"))
                .put("status", "detected")
                .put("credentialsConfigured", true)))

        val snapshot = parseLabProbeDdns(root)
        val record = snapshot.records.single()
        assertEquals(8, snapshot.providers.size)
        assertEquals(listOf("AAAA"), record.recordTypes)
        assertEquals("2409::1", record.detectedIpv6)
        assertEquals("2409::old", record.publishedIpv6)
        assertEquals("verification", record.recordValues["TXT"])
        assertEquals("old-verification", record.publishedValues["TXT"])
        assertEquals("public", record.ipv6State)
        assertEquals("detected", record.status)
        assertTrue(record.credentialsConfigured)
        assertEquals("default-route:wan6", snapshot.address.ipv6Source)
    }

    @Test
    fun invalidHubStatusAndAddressStateAreSafeDefaults() {
        val root = JSONObject().put("records", JSONArray().put(JSONObject()
            .put("id", "r1")
            .put("provider", "duckdns")
            .put("hostname", "home")
            .put("recordTypes", JSONArray().put("A").put("AAAA"))
            .put("status", "public")
            .put("ipv4State", "public")
            .put("ipv6State", "cgnat")))

        val record = parseLabProbeDdns(root).records.single()
        assertEquals("waiting", record.status)
        assertEquals("public", record.ipv4State)
        assertEquals("unavailable", record.ipv6State)
        assertEquals(listOf("A", "AAAA"), record.recordTypes)
    }

    @Test
    fun cnameSelectionExcludesAddressAndTxtTypes() {
        assertEquals(listOf("CNAME"), normalizeLabProbeRecordTypes(listOf("A", "AAAA", "CNAME", "TXT")))
        assertEquals(listOf("A", "AAAA", "TXT"), normalizeLabProbeRecordTypes(listOf("A", "AAAA", "TXT")))
    }

    @Test
    fun cnameAndTxtValuesUseIndependentFields() {
        val json = LabProbeDdnsRecord(
            provider = "cloudflare",
            hostname = "alias.example.com",
            recordTypes = listOf("CNAME"),
            recordValues = mapOf("CNAME" to "target.example.net."),
            publishedValues = mapOf("CNAME" to "old.example.net"),
        ).toJson()

        assertEquals(listOf("CNAME"), (0 until json.getJSONArray("recordTypes").length()).map { json.getJSONArray("recordTypes").getString(it) })
        assertEquals("target.example.net", json.getJSONObject("recordValues").getString("CNAME"))
        assertFalse(json.has("detectedIpv4"))
        assertFalse(json.has("publishedIpv4"))
    }

    @Test
    fun txtValueSerializationPreservesUserWhitespace() {
        val value = "  v=spf1 include:example.test  "
        val json = LabProbeDdnsRecord(
            provider = "cloudflare",
            hostname = "_verify.example.com",
            recordTypes = listOf("TXT"),
            recordValues = mapOf("TXT" to value),
        ).toJson()
        assertEquals(value, json.getJSONObject("recordValues").getString("TXT"))
    }

    @Test
    fun cnameValidationRejectsUrlAndSameOwnerButAcceptsDomain() {
        assertFalse(labProbeCnameTargetIsValid("alias.example.com", "https://target.example.net"))
        assertFalse(labProbeCnameTargetIsValid("alias.example.com", "alias.example.com"))
        assertTrue(labProbeCnameTargetIsValid("alias.example.com", "target.example.net."))
    }

    @Test
    fun providerCapabilitiesParseCnameTxtAndDuckdnsDoesNotSupportCname() {
        val root = JSONObject().put("providers", JSONArray()
            .put(JSONObject().put("id", "cloudflare").put("recordTypes", JSONArray().put("A").put("AAAA").put("CNAME").put("TXT")))
            .put(JSONObject().put("id", "duckdns").put("recordTypes", JSONArray().put("A").put("AAAA").put("TXT"))))
        val providers = parseLabProbeDdns(root).providers
        val cloudflare = providers.single { it.id == "cloudflare" }
        assertEquals(listOf("A", "AAAA", "CNAME", "TXT"), cloudflare.recordTypes)
        assertTrue(cloudflare.supports("A"))
        assertTrue(cloudflare.supports("AAAA"))
        assertTrue(cloudflare.supports("CNAME"))
        assertTrue(cloudflare.supports("TXT"))
        assertFalse(providers.single { it.id == "duckdns" }.supports("CNAME"))
        assertTrue(providers.single { it.id == "duckdns" }.supports("TXT"))
    }

    @Test
    fun validationAllowsTxtWithoutDetectedAddressAndRejectsUnsupportedProvider() {
        val txt = LabProbeDdnsRecord(provider = "cloudflare", hostname = "_verify.example.com", recordTypes = listOf("TXT"), recordValues = mapOf("TXT" to "ACME"))
        val duck = LabProbeDdnsProvider(id = "duckdns", recordTypes = listOf("A", "AAAA", "TXT"))
        assertEquals(null, labProbeRecordValidationError(txt, duck))
        assertEquals("当前服务商不支持所选记录类型", labProbeRecordValidationError(txt.copy(recordTypes = listOf("CNAME"), recordValues = mapOf("CNAME" to "target.example.net")), duck))
    }
}
