package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LuckyRulePresentationTest {
    @Test fun preservesSubruleAssociationAndDoesNotZipUnrelatedDomainBackendLists() {
        val rule = JSONObject("""{"name":"dynv6","tls":true,"port":2186,"routes":[{"id":"b","domains":["fs.example.com"],"backends":["http://192.168.5.152:12769"],"type":"reverseproxy"},{"id":"a","domains":["ug.example.com","nas.example.com"],"backends":["https://192.168.5.44:9443","https://192.168.5.45:9443"],"type":"reverseproxy"}]}""")
        val routes = luckyRouteDisplays(rule)
        assertEquals(listOf("http://192.168.5.152:12769"), routes[0].backends)
        assertEquals(2, routes[1].backends.size)
        val copied = luckyRuleCopyText(rule)
        assertTrue(copied.contains("https://fs.example.com:2186\n→ http://192.168.5.152:12769"))
        assertTrue(copied.contains("https://ug.example.com:2186\n→ https://192.168.5.44:9443\n→ https://192.168.5.45:9443"))
        val old = JSONObject("""{"domain":"a.example.com, b.example.com","backend":"http://one, http://two","editable":false}""")
        assertTrue(luckyRouteDisplays(old).single().backends.isEmpty())
        assertTrue(luckyRuleCopyText(old).contains("映射关系待刷新"))
    }

    @Test fun ddnsSummaryShowsFailureCountAndNormalWithoutFailure() {
        assertEquals("2台异常", homeDdnsSubtitle(listOf(DdnsRecord(status = "error"), DdnsRecord(status = "failed"))))
        assertEquals("1台异常", homeDdnsSubtitle(listOf(DdnsRecord(status = "ok"), DdnsRecord(status = "异常"))))
        assertEquals("正常", homeDdnsSubtitle(listOf(DdnsRecord(status = "ok"))))
        assertEquals("同步中", homeDdnsSubtitle(null))
        assertEquals("同步异常", homeDdnsSubtitle(emptyList(), "network unavailable"))
    }
}
