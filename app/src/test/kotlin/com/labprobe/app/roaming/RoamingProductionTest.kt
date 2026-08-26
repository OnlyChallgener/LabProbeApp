package com.labprobe.app.roaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoamingProductionTest {
    private fun wifi(
        atMs: Long,
        bssid: String?,
        ssid: String = "Lab",
        rssi: Int = -65
    ) = RoamWifiObservation(
        sessionId = 1L,
        observedAtNanos = atMs * 1_000_000L,
        elapsedMs = atMs,
        wallTimeText = "00:00:00.000",
        ssid = ssid,
        bssid = bssid,
        rssi = rssi,
        frequencyMhz = 5180,
        channel = 36,
        linkMbps = 866,
        txMbps = 600,
        rxMbps = 700
    )

    private fun ping(
        target: RoamProbeTarget,
        startedMs: Long,
        completedMs: Long,
        latencyMs: Int?,
        attempted: Boolean = true
    ) = RoamPingAttempt(
        sessionId = 1L,
        target = target,
        startedAtNanos = startedMs * 1_000_000L,
        completedAtNanos = completedMs * 1_000_000L,
        attempted = attempted,
        latencyMs = latencyMs
    )

    @Test
    fun `A invalid B produces one confirmed observation window`() {
        val detector = RoamDetector()
        assertNull(detector.observe(wifi(0, "aa:aa:aa:aa:aa:aa")))
        assertNull(detector.observe(wifi(50, null)))
        assertNull(detector.observe(wifi(100, "bb:bb:bb:bb:bb:bb")))
        val event = detector.observe(wifi(150, "bb:bb:bb:bb:bb:bb"))

        assertEquals("aa:aa:aa:aa:aa:aa", event?.oldBssid)
        assertEquals("bb:bb:bb:bb:bb:bb", event?.newBssid)
        assertEquals(100L, event?.observationMs)
        assertEquals(100_000_000L, event?.newObservedAtNanos)
    }

    @Test
    fun `single sample A B A glitch is rejected`() {
        val detector = RoamDetector()
        detector.observe(wifi(0, "aa:aa:aa:aa:aa:aa"))
        assertNull(detector.observe(wifi(50, "bb:bb:bb:bb:bb:bb")))
        assertNull(detector.observe(wifi(100, "aa:aa:aa:aa:aa:aa")))
        assertNull(detector.observe(wifi(150, "aa:aa:aa:aa:aa:aa")))
    }

    @Test
    fun `SSID change is not counted as same network BSSID switch`() {
        val detector = RoamDetector()
        detector.observe(wifi(0, "aa:aa:aa:aa:aa:aa", ssid = "Lab-A"))
        assertNull(detector.observe(wifi(50, "bb:bb:bb:bb:bb:bb", ssid = "Lab-B")))
        assertNull(detector.observe(wifi(100, "bb:bb:bb:bb:bb:bb", ssid = "Lab-B")))
    }

    @Test
    fun `gateway and WAN recovery and loss stay independent`() {
        val base = BssidSwitchEvent(
            oldSsid = "Lab",
            newSsid = "Lab",
            oldBssid = "aa:aa:aa:aa:aa:aa",
            newBssid = "bb:bb:bb:bb:bb:bb",
            oldObservedAtNanos = 100_000_000L,
            newObservedAtNanos = 150_000_000L,
            confirmedAtNanos = 200_000_000L,
            oldRssi = -74,
            newRssi = -58,
            oldFrequencyMhz = 2412,
            newFrequencyMhz = 5180,
            observationMs = 50L
        )
        val result = attachProbeImpacts(
            listOf(base),
            listOf(
                ping(RoamProbeTarget.GATEWAY, 120, 920, null),
                ping(RoamProbeTarget.WAN, 130, 170, 28),
                ping(RoamProbeTarget.GATEWAY, 950, 980, 12)
            ),
            final = true
        ).single()

        assertEquals(RoamImpactState.RECOVERED, result.gatewayImpact.state)
        assertEquals(1, result.gatewayImpact.lossCount)
        assertEquals(830L, result.gatewayImpact.recoveryAfterNewBssidMs)
        assertEquals(RoamImpactState.NO_OUTAGE_OBSERVED, result.wanImpact.state)
        assertEquals(0, result.wanImpact.lossCount)
        assertNull(result.wanImpact.recoveryAfterNewBssidMs)
    }

    @Test
    fun `not attempted probe does not enter loss denominator`() {
        val event = BssidSwitchEvent(
            "Lab", "Lab", "aa:aa:aa:aa:aa:aa", "bb:bb:bb:bb:bb:bb",
            100_000_000L, 150_000_000L, 200_000_000L,
            -70, -60, 2412, 5180, 50L
        )
        val result = attachProbeImpacts(
            listOf(event),
            listOf(ping(RoamProbeTarget.GATEWAY, 120, 130, null, attempted = false)),
            final = true
        ).single()
        assertEquals(RoamImpactState.NOT_MONITORED, result.gatewayImpact.state)
        assertEquals(0, result.gatewayImpact.attemptedCount)
        assertEquals(0, result.gatewayImpact.lossCount)
    }

    @Test
    fun `next BSSID switch prevents late recovery attribution`() {
        val detector = RoamDetector(confirmationSamples = 1)
        detector.observe(wifi(0, "aa:aa:aa:aa:aa:aa"))
        val first = detector.observe(wifi(50, "bb:bb:bb:bb:bb:bb"))!!
        detector.observe(wifi(100, "bb:bb:bb:bb:bb:bb"))
        val second = detector.observe(wifi(150, "cc:cc:cc:cc:cc:cc"))!!
        val measured = attachProbeImpacts(
            listOf(first, second),
            listOf(
                ping(RoamProbeTarget.GATEWAY, 60, 80, null),
                ping(RoamProbeTarget.GATEWAY, 170, 190, 10)
            ),
            final = true
        )
        assertEquals(RoamImpactState.UNRECOVERED, measured.first().gatewayImpact.state)
        assertNull(measured.first().gatewayImpact.recoveryAfterNewBssidMs)
    }

    @Test
    fun `band channel and interval quality calculations are deterministic`() {
        assertEquals("2.4G", wifiBandOf(2412))
        assertEquals(1, wifiChannelOf(2412))
        assertEquals("5G", wifiBandOf(5180))
        assertEquals(36, wifiChannelOf(5180))
        assertEquals("6G", wifiBandOf(5955))
        assertEquals(1, wifiChannelOf(5955))
        assertEquals(80L, percentile95Ms(listOf(50_000_000L, 52_000_000L, 80_000_000L)))
        assertTrue(isUsableBssid("aa:bb:cc:dd:ee:ff"))
    }
}
