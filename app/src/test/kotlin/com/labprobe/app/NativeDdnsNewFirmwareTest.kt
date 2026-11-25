package com.labprobe.app

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** New ReyeeOS firmware (ddns_view) DDNS contract, mirrored from the HUB bridge. */
class NativeDdnsNewFirmwareTest {

    private fun nativeRecord() = JSONObject("""
        {"list":[{
          "serviceId":"ddns_6affba6f_4","service_name":"aliyun.com",
          "domain":"t.example.com","username":"ak","enabled":"1",
          "interface":"wan","use_ipv6":"0","record_type":"TXT",
          "zone":"example.com","zone_id":"","region":"cn-north-4",
          "txt_template":"{{ip}}:{{port}}","txt_family":"ipv4","external_port":6500,
          "nativeProtocol":"ddns_view","passwordConfigured":true,
          "last_log":1795143849,
          "status":"succeed","ip":"1.2.3.4"
        }]}
    """.trimIndent())

    @Test
    fun parsesNewFirmwareRecordTypeAndTxtFields() {
        val record = parseDdnsList(nativeRecord()).single()
        assertTrue(record.modernNative)
        assertEquals("TXT", record.effectiveRecordType)
        assertEquals("{{ip}}:{{port}}", record.txtTemplate)
        assertEquals("ipv4", record.txtFamily)
        assertEquals(6500, record.externalPort)
        assertEquals("cn-north-4", record.region)
        assertEquals("example.com", record.zone)
        assertTrue(record.passwordConfigured)
        assertEquals(1795143849L, record.lastUpdatedAt)
    }

    @Test
    fun toJsonSendsModernFieldsForNewFirmware() {
        val record = parseDdnsList(nativeRecord()).single()
        val json = record.toJson("secret")
        assertEquals("TXT", json.optString("record_type"))
        assertEquals("{{ip}}:{{port}}", json.optString("txt_template"))
        assertEquals(6500, json.optInt("external_port"))
        assertEquals("1", json.optString("enabled"))
        assertEquals("aliyun.com", json.optString("provider"))
        assertEquals("secret", json.optString("password"))
    }

    @Test
    fun toJsonKeepsLegacySchemaForOldFirmware() {
        val record = DdnsRecord(provider = "dnspod.cn", domain = "a.example.com", useIpv6 = true)
        assertFalse(record.modernNative)
        val json = record.toJson(null)
        assertEquals("dnspod.cn", json.optString("service_name"))
        assertEquals("1", json.optString("use_ipv6"))
        assertEquals("AAAA", record.effectiveRecordType)
        assertFalse(json.has("record_type"))
        assertFalse(json.has("txt_template"))
    }

    @Test
    fun providerCapabilitiesMatchReferenceFlags() {
        assertTrue(nativeDdnsSupportsRecordType("aliyun.com", "TXT"))
        assertTrue(nativeDdnsSupportsRecordType("aliyun.com", "AAAA"))
        // tencentcloud.com=0x07 -> TXT/AAAA/zone all supported
        assertTrue(nativeDdnsSupportsRecordType("tencentcloud.com", "TXT"))
        // no-ip.com=0x22 -> A/AAAA + region, no TXT
        assertTrue(nativeDdnsSupportsRecordType("no-ip.com", "AAAA"))
        assertFalse(nativeDdnsSupportsRecordType("no-ip.com", "TXT"))
        // oray.com=0x20 -> only A, no TXT/AAAA
        assertFalse(nativeDdnsSupportsRecordType("oray.com", "AAAA"))
        assertFalse(nativeDdnsSupportsRecordType("oray.com", "TXT"))
        assertTrue(nativeDdnsSupportsRecordType("oray.com", "A"))
        assertEquals("花生壳", nativeDdnsProviderLabel("oray.com"))
        assertEquals("腾讯云 API3", nativeDdnsProviderLabel("tencentcloud.com"))
        assertEquals("DynDNS", nativeDdnsProviderLabel("dyn.com"))
    }

    @Test
    fun parsesEntriesArrayWithoutProtocolOnEveryRow() {
        val payload = JSONObject("""
            {"nativeProtocol":"ddns_view","entries":[{"id":"id-1","provider":"aliyun.com","domain":"a.example.com"}]}
        """.trimIndent())
        val record = parseDdnsList(payload).single()
        assertEquals("id-1", record.serviceId)
        assertTrue(record.modernNative)
    }

    @Test
    fun parsesLastUpdateWhenFirmwareDoesNotExposeCurrentIp() {
        val payload = JSONObject("""
            {"nativeProtocol":"ddns_view","entries":[{"id":"id-1","provider":"aliyun.com","domain":"a.example.com","last_log":1795143849}]}
        """.trimIndent())
        val record = parseDdnsList(payload).single()
        assertEquals("", record.ip)
        assertEquals(1795143849L, record.lastUpdatedAt)
    }

    @Test
    fun parsesDdnsStatusContentDetectedIpAndLastSuccess() {
        val payload = JSONObject("""
            {"nativeProtocol":"ddns_view","list":[
              {"id":"a","provider":"aliyun.com","domain":"a.example.com","record_type":"A",
               "state":"success","content":"36.157.252.37","detected_ip":"36.157.252.37","last_success":1795259667},
              {"id":"aaaa","provider":"aliyun.com","domain":"aaaa.example.com","record_type":"AAAA",
               "state":"success","content":"2409:8a50:2e04:3990:39f4:eec9:9df6:f878",
               "detected_ip":"2409:8a50:2e04:3990:39f4:eec9:9df6:f878","last_success":1795259669},
              {"id":"txt","provider":"aliyun.com","domain":"t.example.com","record_type":"TXT",
               "state":"success","content":"36.157.252.37:6500","detected_ip":"36.157.252.37",
               "ip":"wrong-value","last_success":1795259670}
            ]}
        """.trimIndent())
        val records = parseDdnsList(payload)
        assertEquals("36.157.252.37", records[0].ip)
        assertEquals("2409:8a50:2e04:3990:39f4:eec9:9df6:f878", records[1].ip)
        assertEquals("36.157.252.37:6500", records[2].ip)
        assertEquals("success", records[2].status)
        assertEquals(1795259670L, records[2].lastUpdatedAt)
    }

    @Test
    fun txtUsesCompleteContentBeforeDetectedIpOrLegacyIp() {
        val payload = JSONObject("""
            {"list":[{"id":"txt","domain":"t.example.com","record_type":"TXT",
              "content":"2001:db8::1:6500","detected_ip":"2001:db8::1","ip":"2001:db8::1"}]}
        """.trimIndent())
        assertEquals("2001:db8::1:6500", parseDdnsList(payload).single().ip)
    }

    @Test
    fun addressRecordPrefersDetectedIpAndAcceptsCamelCaseLastSuccess() {
        val payload = JSONObject("""
            {"list":[{"id":"a","domain":"a.example.com","record_type":"A",
              "content":"198.51.100.2","detected_ip":"198.51.100.1","ip":"198.51.100.3",
              "lastSuccessAt":1795259667}]}
        """.trimIndent())
        val record = parseDdnsList(payload).single()
        assertEquals("198.51.100.1", record.ip)
        assertEquals(1795259667L, record.lastUpdatedAt)
    }

    @Test
    fun legacyIpStatusAndLastLogContractStillWorks() {
        val payload = JSONObject("""
            {"nativeProtocol":"ddnsCfg","list":[{"service":"legacy","domain":"legacy.example.com",
              "status":"succeed","ip":"203.0.113.10","last_log":1795143849}]}
        """.trimIndent())
        val record = parseDdnsList(payload).single()
        assertEquals("203.0.113.10", record.ip)
        assertEquals("succeed", record.status)
        assertEquals(1795143849L, record.lastUpdatedAt)
        assertEquals("203.0.113.10", nativeDdnsDetailText(record))
    }

    @Test
    fun detailTextDistinguishesSuccessFromMissingStatus() {
        assertEquals("更新成功", nativeDdnsDetailText(DdnsRecord(status = "success")))
        assertEquals("更新失败", nativeDdnsDetailText(DdnsRecord(status = "failed")))
        assertEquals("等待更新", nativeDdnsDetailText(DdnsRecord()))
    }

    @Test
    fun rejectsMissingMalformedAndNonObjectRecordArrays() {
        assertThrows(JSONException::class.java) { parseDdnsList(JSONObject()) }
        assertThrows(JSONException::class.java) { parseDdnsList(JSONObject("""{"list":{}}""")) }
        assertThrows(JSONException::class.java) { parseDdnsList(JSONObject("""{"list":[1]}""")) }
    }

    @Test
    fun rejectsMissingOrDuplicateIdsAndNeverUsesDomainAsId() {
        assertThrows(JSONException::class.java) {
            parseDdnsList(JSONObject("""{"nativeProtocol":"ddns_view","entries":[{"domain":"a.example.com"}]}"""))
        }
        assertThrows(JSONException::class.java) {
            parseDdnsList(JSONObject("""{"list":[{"id":"same"},{"id":"same"}]}"""))
        }
    }

    /**
     * 固件原生只有这 9 项；dynv6 / deSEC / Dynu / IPv64 是 LabProbe 自己 DDNS 的
     * 服务商，混进来的话用户选了固件不认识的值，保存必失败。
     */
    @Test
    fun providerListStaysOnTheFirmwareSet() {
        assertFalse(nativeDdnsProviders.any { it.contains("dynv6", ignoreCase = true) })
        assertTrue(nativeDdnsProviders.contains("oray.com"))
        assertEquals(9, nativeDdnsProviders.size)
        // 每一项都要有中文名，否则选择器里会出现裸的域名串。
        assertTrue(nativeDdnsProviders.all { nativeDdnsProviderLabel(it) != it })
        // 新增记录时表单能不能给出完整清单，取决于设备能力而不是记录上的协议字段。
        assertTrue(ddnsEditorUsesModernForm(nativeProtocol = "", nativeDdns = true))
        assertFalse(ddnsEditorUsesModernForm(nativeProtocol = "", nativeDdns = false))
        assertTrue(ddnsEditorUsesModernForm(nativeProtocol = "ddns_view", nativeDdns = false))
    }
}
