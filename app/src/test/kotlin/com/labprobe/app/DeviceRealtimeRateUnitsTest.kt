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
    fun realtimeByteFieldsAreNotDividedAgain() {
        val parsed = parseDeviceArray("""
            [{"mac":"AA:BB:CC:DD:EE:01","realtimeUpBytes":335,"realtimeDownBytes":1500}]
        """.trimIndent()).single()

        assertEquals(335L, parsed.realtimeUploadBytes)
        assertEquals(1500L, parsed.realtimeDownloadBytes)
    }

    @Test
    fun liteRealtimeAcceptsLegacyByteFieldsWithoutDoubleConversion() {
        val devices = listOf(device("AA:BB:CC:DD:EE:01"))
        val result = mergeLiteDeviceRealtime(devices, JSONObject("""
            {"devices":[{"mac":"AA:BB:CC:DD:EE:01","realtimeUpBytes":335,"realtimeDownBytes":1500,"connectionCount":2}]}
        """.trimIndent()))

        assertEquals(335L, result[0].realtimeUploadBytes)
        assertEquals(1500L, result[0].realtimeDownloadBytes)
        assertEquals(2, result[0].connectionCount)
    }

    @Test
    fun fullSnapshotByteFieldsReachTheSingleAppSmoother() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptDevices(JSONObject("""
            {"sampleEpochMs":1700000000000,"fullSnapshot":true,"devices":[
              {"mac":"AA:BB:CC:DD:EE:01","realtimeUpBytes":335,"realtimeDownBytes":1500,"connectionCount":3}
            ]}
        """.trimIndent()), now = 1000L)

        val rendered = smoother.renderDevices(listOf(device("AA:BB:CC:DD:EE:01")), now = 1000L).single()
        assertEquals(335L, rendered.realtimeUploadBytes)
        assertEquals(1500L, rendered.realtimeDownloadBytes)
        assertEquals(3, rendered.connectionCount)
    }

    @Test
    fun canonicalBitRateTakesPrecedenceOverLegacyByteFields() {
        val row = JSONObject("""
            {"mac":"AA:BB:CC:DD:EE:01","uploadBps":2680,"downloadBps":12000,"realtimeUpBytes":999,"realtimeDownBytes":999}
        """.trimIndent())
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptDevices(JSONObject().put("sampleEpochMs", 1700000000000L).put("devices", org.json.JSONArray().put(row)), now = 1000L)

        val rendered = smoother.renderDevices(listOf(device("AA:BB:CC:DD:EE:01")), now = 1000L).single()
        assertEquals(335L, rendered.realtimeUploadBytes)
        assertEquals(1500L, rendered.realtimeDownloadBytes)
    }

    @Test
    fun legacyUnmarkedRealtimeFieldsRemainByteRates() {
        val parsed = parseDeviceArray("""
            [{"mac":"AA:BB:CC:DD:EE:01","realtimeUpload":335,"realtimeDownload":1500}]
        """.trimIndent()).single()

        assertEquals(335L, parsed.realtimeUploadBytes)
        assertEquals(1500L, parsed.realtimeDownloadBytes)
    }

    @Test
    fun markedRealtimeFieldsUseDeclaredByteUnit() {
        val parsed = parseDeviceArray("""
            [{"mac":"AA:BB:CC:DD:EE:01","realtimeRateUnit":"bytes/s","realtimeUpload":335,"realtimeDownload":1500}]
        """.trimIndent()).single()

        assertEquals(335L, parsed.realtimeUploadBytes)
        assertEquals(1500L, parsed.realtimeDownloadBytes)
    }

    @Test
    fun standardBitsPerSecondMarkerIsNotByteUnit() {
        val parsed = parseDeviceArray("""
            [{"mac":"AA:BB:CC:DD:EE:01","realtimeRateUnit":"b/s","realtimeUpload":2680,"realtimeDownload":12000}]
        """.trimIndent()).single()

        assertEquals(335L, parsed.realtimeUploadBytes)
        assertEquals(1500L, parsed.realtimeDownloadBytes)
    }

    @Test
    fun firstPartialDeltaPreservesMissingDeviceValues() {
        val existing = device("AA:BB:CC:DD:EE:01").copy(
            realtimeUploadBytes = 100L,
            realtimeDownloadBytes = 200L,
            connectionCount = 7,
        )
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptDevices(JSONObject("""
            {"sampleEpochMs":1700000000000,"delta":true,"devices":[{"mac":"AA:BB:CC:DD:EE:01","uploadBps":800}]}
        """.trimIndent()), now = 1000L)

        val rendered = smoother.renderDevices(listOf(existing), now = 1000L).single()
        assertEquals(100L, rendered.realtimeUploadBytes)
        assertEquals(200L, rendered.realtimeDownloadBytes)
        assertEquals(7, rendered.connectionCount)
    }

    @Test
    fun missingRatesDoNotOverwritePreviousSmoothedValues() {
        val smoother = RealtimeDisplaySmoother()
        smoother.acceptDevices(JSONObject("""
            {"sampleEpochMs":1700000000000,"devices":[{"mac":"AA:BB:CC:DD:EE:01","uploadBps":2680,"downloadBps":12000}]}
        """.trimIndent()), now = 1000L)
        smoother.renderDevices(listOf(device("AA:BB:CC:DD:EE:01")), now = 1000L)
        smoother.acceptDevices(JSONObject("""
            {"sampleEpochMs":1700000001000,"devices":[{"mac":"AA:BB:CC:DD:EE:01"}]}
        """.trimIndent()), now = 2000L)

        val rendered = smoother.renderDevices(listOf(device("AA:BB:CC:DD:EE:01")), now = 2000L).single()
        assertEquals(335L, rendered.realtimeUploadBytes)
        assertEquals(1500L, rendered.realtimeDownloadBytes)
    }

    @Test
    fun liteRealtimeMissingRatesPreserveExistingValues() {
        val existing = device("AA:BB:CC:DD:EE:01").copy(
            realtimeUploadBytes = 335L,
            realtimeDownloadBytes = 1500L,
            connectionCount = 3,
        )
        val result = mergeLiteDeviceRealtime(listOf(existing), JSONObject("""
            {"devices":[{"mac":"AA:BB:CC:DD:EE:01"}]}
        """.trimIndent()))

        assertEquals(existing, result.single())
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
