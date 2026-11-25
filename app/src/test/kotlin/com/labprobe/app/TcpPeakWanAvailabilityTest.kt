package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TcpPeakWanAvailabilityTest {
    @Test fun requiresOnlineCapabilityAndAdvertisedLine() {
        val root = JSONObject("""{"agentOnline":true,"agentCapabilities":["relay.perWanTcpSession"],"agentWanIds":["wan"]}""")
        assertEquals(setOf("wan"), tcpPeakAvailableWans(root))
        root.put("agentOnline", false)
        assertTrue(tcpPeakAvailableWans(root).isEmpty())
        root.put("agentOnline", true).remove("agentWanIds")
        assertTrue(tcpPeakAvailableWans(root).isEmpty())
    }
}
