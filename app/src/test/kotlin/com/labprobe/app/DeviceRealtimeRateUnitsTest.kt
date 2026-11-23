package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRealtimeRateUnitsTest {
    private fun device(mac: String) = DeviceItem(
        name = mac, mac = mac, online = true, ip = "", ssid = "", band = "",
        rssi = "", rxrate = "", onlineSince = "", offlineAt = "",
        onlineDurationText = "", lastSeenAt = "",
    )

    @Test
    fun rawFirmwareBitRatesConvertToDeviceByteRatesWithoutSwappingDirections() {
        val parsed = parseDeviceArray("""
            [{"mac":"AA:BB:CC:DD:EE:01","flowUp":2680,"flowDown":12000}]
        """.trimIndent()).single()

        assertEquals(335L, parsed.realtimeUploadBytes)
        assertEquals(1500L, parsed.realtimeDownloadBytes)
    }

    @Test
    fun liteRealtimeConvertsEveryDeviceAndPreservesIdleZero() {
        val devices = listOf(device("AA:BB:CC:DD:EE:01"), device("AA:BB:CC:DD:EE:02"))
        val result = mergeLiteDeviceRealtime(devices, JSONObject("""
            {"devices":[
              {"mac":"AA:BB:CC:DD:EE:01","uploadBps":2680,"downloadBps":12000,"connectionCount":2},
              {"mac":"AA:BB:CC:DD:EE:02","uploadBps":0,"downloadBps":0,"connectionCount":0}
            ]}
        """.trimIndent()))

        assertEquals(335L, result[0].realtimeUploadBytes)
        assertEquals(1500L, result[0].realtimeDownloadBytes)
        assertEquals(0L, result[1].realtimeUploadBytes)
        assertEquals(0L, result[1].realtimeDownloadBytes)
    }

    @Test
    fun smoothedLiveRatesAreConvertedOnlyWhenProjectedToDeviceRows() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptDevices(JSONObject("""
            {"sampleEpochMs":1700000000000,"sampleAgeMs":0,"devices":[
              {"mac":"AA:BB:CC:DD:EE:01","uploadBps":2680,"downloadBps":12000,"connectionCount":3}
            ]}
        """.trimIndent()), now = 1000L)

        val rendered = smoother.renderDevices(listOf(device("AA:BB:CC:DD:EE:01")), now = 1000L).single()
        assertEquals(335L, rendered.realtimeUploadBytes)
        assertEquals(1500L, rendered.realtimeDownloadBytes)
        assertEquals(3, rendered.connectionCount)
    }
}
