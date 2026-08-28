package com.labprobe.app.roaming

import kotlinx.coroutines.runBlocking
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
        assertNull(detector.observe(wifi(100, "b0:b0:b0:b0:b0:b0")))
        val event = detector.observe(wifi(150, "b0:b0:b0:b0:b0:b0"))

        assertEquals("aa:aa:aa:aa:aa:aa", event?.oldBssid)
        assertEquals("b0:b0:b0:b0:b0:b0", event?.newBssid)
        assertEquals(100L, event?.observationMs)
        assertEquals(100_000_000L, event?.newObservedAtNanos)
    }

    @Test
    fun `single sample A B A glitch is rejected`() {
        val detector = RoamDetector()
        detector.observe(wifi(0, "aa:aa:aa:aa:aa:aa"))
        assertNull(detector.observe(wifi(50, "b0:b0:b0:b0:b0:b0")))
        assertNull(detector.observe(wifi(100, "aa:aa:aa:aa:aa:aa")))
        assertNull(detector.observe(wifi(150, "aa:aa:aa:aa:aa:aa")))
    }

    @Test
    fun `SSID change is not counted as same network BSSID switch`() {
        val detector = RoamDetector()
        detector.observe(wifi(0, "aa:aa:aa:aa:aa:aa", ssid = "Lab-A"))
        assertNull(detector.observe(wifi(50, "b0:b0:b0:b0:b0:b0", ssid = "Lab-B")))
        assertNull(detector.observe(wifi(100, "b0:b0:b0:b0:b0:b0", ssid = "Lab-B")))
    }

    @Test
    fun `gateway and WAN recovery and loss stay independent`() {
        val base = BssidSwitchEvent(
            oldSsid = "Lab",
            newSsid = "Lab",
            oldBssid = "aa:aa:aa:aa:aa:aa",
            newBssid = "b0:b0:b0:b0:b0:b0",
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
        assertEquals(RoamImpactState.INSUFFICIENT_EVIDENCE, result.wanImpact.state)
        assertEquals(0, result.wanImpact.lossCount)
        assertNull(result.wanImpact.recoveryAfterNewBssidMs)
    }

    @Test
    fun `not attempted probe does not enter loss denominator`() {
        val event = BssidSwitchEvent(
            "Lab", "Lab", "aa:aa:aa:aa:aa:aa", "b0:b0:b0:b0:b0:b0",
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
        val first = detector.observe(wifi(50, "b0:b0:b0:b0:b0:b0"))!!
        detector.observe(wifi(100, "b0:b0:b0:b0:b0:b0"))
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
    fun `final loss without recovery remains unrecovered`() {
        val event = BssidSwitchEvent(
            "Lab", "Lab", "aa:aa:aa:aa:aa:aa", "b0:b0:b0:b0:b0:b0",
            100_000_000L, 150_000_000L, 200_000_000L,
            -72, -60, 2412, 5180, 50L
        )
        val measured = attachProbeImpacts(
            listOf(event),
            listOf(ping(RoamProbeTarget.WAN, 160, 900, null)),
            final = true
        ).single()

        assertEquals(RoamImpactState.UNRECOVERED, measured.wanImpact.state)
        assertEquals(1, measured.wanImpact.lossCount)
        assertNull(measured.wanImpact.recoveryAfterNewBssidMs)
    }

    @Test
    fun `session finish drains queued inputs into final snapshot`() = runBlocking {
        val session = RoamingSession(sessionId = 1L, publishIntervalNanos = Long.MAX_VALUE)
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(0, "aa:aa:aa:aa:aa:aa")))
        session.trySend(RoamingSessionInput.Ping(1L, ping(RoamProbeTarget.GATEWAY, 10, 20, null)))

        val snapshot = session.finish()

        assertEquals(false, snapshot.running)
        assertEquals(1, snapshot.wifiSampleCount)
        assertEquals(1, snapshot.gatewayStats.attemptedCount)
        assertEquals(1, snapshot.gatewayStats.lossCount)
    }

    @Test
    fun `full session probe totals survive bounded chart buffer`() = runBlocking {
        val session = RoamingSession(sessionId = 1L, publishIntervalNanos = Long.MAX_VALUE)
        repeat(6_001) { index ->
            session.trySend(RoamingSessionInput.Ping(1L, ping(RoamProbeTarget.WAN, index.toLong(), index + 1L, 12)))
        }

        val snapshot = session.finish()

        assertEquals(6_001, snapshot.wanStats.attemptedCount)
        assertEquals(6_001, snapshot.wanStats.successCount)
        assertEquals(6_000, snapshot.pingAttempts.size)
    }

    @Test
    fun `full session gap maximum survives more than legacy window`() = runBlocking {
        val session = RoamingSession(sessionId = 1L, publishIntervalNanos = Long.MAX_VALUE)
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(0, "aa:aa:aa:aa:aa:aa")))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(5_000, "aa:aa:aa:aa:aa:aa")))
        repeat(2_100) { index ->
            session.trySend(RoamingSessionInput.Wifi(1L, wifi(5_050L + index * 50L, "aa:aa:aa:aa:aa:aa")))
        }

        val snapshot = session.finish()

        assertEquals(50L, snapshot.wifiGapP95Ms)
        assertEquals(5_000L, snapshot.wifiGapMaxMs)
    }

    @Test
    fun `unrecovered switch loss exceeds bounded ping chart buffer`() = runBlocking {
        val session = RoamingSession(sessionId = 1L, publishIntervalNanos = Long.MAX_VALUE)
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(0, "aa:aa:aa:aa:aa:aa")))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(50, "b0:b0:b0:b0:b0:b0")))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(100, "b0:b0:b0:b0:b0:b0")))
        repeat(6_001) { index ->
            session.trySend(RoamingSessionInput.Ping(1L, ping(RoamProbeTarget.GATEWAY, 110L + index, 111L + index, null)))
        }

        val snapshot = session.finish()

        assertEquals(RoamImpactState.UNRECOVERED, snapshot.switches.single().gatewayImpact.state)
        assertEquals(6_001, snapshot.switches.single().gatewayImpact.lossCount)
    }

    @Test
    fun `ping inside next confirmation window is counted only by next switch`() = runBlocking {
        val session = RoamingSession(sessionId = 1L, publishIntervalNanos = Long.MAX_VALUE)
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(0, "aa:aa:aa:aa:aa:aa")))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(50, "b0:b0:b0:b0:b0:b0")))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(100, "b0:b0:b0:b0:b0:b0")))
        session.trySend(RoamingSessionInput.Ping(1L, ping(RoamProbeTarget.GATEWAY, 90, 110, null)))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(150, "cc:cc:cc:cc:cc:cc")))
        session.trySend(RoamingSessionInput.Ping(1L, ping(RoamProbeTarget.GATEWAY, 160, 170, null)))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(200, "cc:cc:cc:cc:cc:cc")))

        val snapshot = session.finish()

        assertEquals(1, snapshot.switches[0].gatewayImpact.lossCount)
        assertEquals(1, snapshot.switches[1].gatewayImpact.lossCount)
    }

    @Test
    fun `success inside next confirmation window cannot recover previous switch`() = runBlocking {
        val session = RoamingSession(sessionId = 1L, publishIntervalNanos = Long.MAX_VALUE)
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(0, "aa:aa:aa:aa:aa:aa")))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(50, "b0:b0:b0:b0:b0:b0")))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(100, "b0:b0:b0:b0:b0:b0")))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(150, "cc:cc:cc:cc:cc:cc")))
        session.trySend(RoamingSessionInput.Ping(1L, ping(RoamProbeTarget.GATEWAY, 160, 170, 12)))
        session.trySend(RoamingSessionInput.Wifi(1L, wifi(200, "cc:cc:cc:cc:cc:cc")))
        session.trySend(RoamingSessionInput.Ping(1L, ping(RoamProbeTarget.GATEWAY, 90, 210, null)))

        val snapshot = session.finish()

        assertEquals(RoamImpactState.UNRECOVERED, snapshot.switches[0].gatewayImpact.state)
        assertEquals(RoamImpactState.NO_OUTAGE_OBSERVED, snapshot.switches[1].gatewayImpact.state)
    }

    @Test
    fun `missing BSSID cannot grow pending impact buffer without bound`() = runBlocking {
        val session = RoamingSession(sessionId = 1L, publishIntervalNanos = Long.MAX_VALUE)
        repeat(MAX_PENDING_IMPACT_PINGS + 1) { index ->
            session.trySend(RoamingSessionInput.Ping(1L, ping(RoamProbeTarget.WAN, index.toLong(), index + 1L, null)))
        }

        val snapshot = session.finish()

        assertEquals(MAX_PENDING_IMPACT_PINGS + 1, snapshot.wanStats.attemptedCount)
        assertTrue(snapshot.impactWindowTruncated)
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
        assertEquals(false, isUsableBssid("ff:ff:ff:ff:ff:ff"))
        assertEquals(false, isUsableBssid("01:00:5e:00:00:01"))
    }
}
