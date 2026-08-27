package com.labprobe.app.roaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque

internal data class RoamingNetworkLayerEvent(
    val observedAtNanos: Long,
    val elapsedMs: Long,
    val timeText: String,
    val label: String,
    val kind: String
)

internal data class RoamingProbeStats(
    val attemptedCount: Int = 0,
    val lossCount: Int = 0,
    val successCount: Int = 0,
    val latencySumMs: Long = 0L,
    val minLatencyMs: Int? = null,
    val maxLatencyMs: Int? = null
)

internal data class RoamingWifiStats(
    val rssiCount: Int = 0,
    val rssiSumDbm: Long = 0L,
    val bestRssiDbm: Int? = null,
    val worstRssiDbm: Int? = null,
    val speedCount: Int = 0,
    val speedSumMbps: Long = 0L,
    val maxSpeedMbps: Int? = null,
    val minSpeedMbps: Int? = null,
    val weakDurationMs: Long = 0L,
    val stickyDurationMs: Long = 0L
)

internal data class RoamingSessionSnapshot(
    val sessionId: Long,
    val latestWifi: RoamWifiObservation? = null,
    val displayWifi: List<RoamWifiObservation> = emptyList(),
    val pingAttempts: List<RoamPingAttempt> = emptyList(),
    val switches: List<BssidSwitchEvent> = emptyList(),
    val networkEvents: List<RoamingNetworkLayerEvent> = emptyList(),
    val wifiSampleCount: Int = 0,
    val wifiGapP95Ms: Long? = null,
    val wifiGapMaxMs: Long? = null,
    val gatewayStats: RoamingProbeStats = RoamingProbeStats(),
    val wanStats: RoamingProbeStats = RoamingProbeStats(),
    val wifiStats: RoamingWifiStats = RoamingWifiStats(),
    val running: Boolean = true
)

internal sealed interface RoamingSessionInput {
    val sessionId: Long
    data class Wifi(override val sessionId: Long, val value: RoamWifiObservation) : RoamingSessionInput
    data class Ping(override val sessionId: Long, val value: RoamPingAttempt) : RoamingSessionInput
    data class Network(override val sessionId: Long, val value: RoamingNetworkLayerEvent) : RoamingSessionInput
    data class Finish(
        override val sessionId: Long,
        val completed: CompletableDeferred<RoamingSessionSnapshot>
    ) : RoamingSessionInput
}

/** Single-consumer reducer that keeps high-frequency inputs out of Compose state. */
internal class RoamingSession(
    val sessionId: Long,
    private val publishIntervalNanos: Long = 250_000_000L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val input = Channel<RoamingSessionInput>(Channel.UNLIMITED)
    private val detector = RoamDetector()
    private val displayWifi = ArrayDeque<RoamWifiObservation>()
    private val pings = ArrayDeque<RoamPingAttempt>()
    private val pendingImpactPings = ArrayDeque<RoamPingAttempt>()
    private val switches = mutableListOf<BssidSwitchEvent>()
    private val networkEvents = ArrayDeque<RoamingNetworkLayerEvent>()
    private val wifiGapHistogram = java.util.TreeMap<Long, Long>()
    private var wifiGapCount = 0L
    private var wifiGapMaxMs: Long? = null
    private var latestWifi: RoamWifiObservation? = null
    private var confirmedBssid: String? = null
    private var previousWifiNanos: Long? = null
    private var wifiSampleCount = 0
    private var lastDisplayAtNanos = Long.MIN_VALUE
    private var lastPublishAtNanos = Long.MIN_VALUE
    private var running = true
    private var gatewayStats = RoamingProbeStats()
    private var wanStats = RoamingProbeStats()
    private var wifiStats = RoamingWifiStats()
    private val _snapshot = MutableStateFlow(RoamingSessionSnapshot(sessionId = sessionId))
    val snapshot: StateFlow<RoamingSessionSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            for (event in input) {
                if (event.sessionId != sessionId) continue
                when (event) {
                    is RoamingSessionInput.Wifi -> reduceWifi(event.value)
                    is RoamingSessionInput.Ping -> reducePing(event.value)
                    is RoamingSessionInput.Network -> {
                        if (networkEvents.lastOrNull()?.label != event.value.label) networkEvents.addLast(event.value)
                        while (networkEvents.size > 80) networkEvents.removeFirst()
                    }
                    is RoamingSessionInput.Finish -> {
                        running = false
                        flushPendingImpactPings(Long.MAX_VALUE)
                        closeLastSwitch()
                    }
                }
                val now = when (event) {
                    is RoamingSessionInput.Wifi -> event.value.observedAtNanos
                    is RoamingSessionInput.Ping -> event.value.completedAtNanos
                    is RoamingSessionInput.Network -> event.value.observedAtNanos
                    is RoamingSessionInput.Finish -> latestWifi?.observedAtNanos ?: 0L
                }
                if (!running || lastPublishAtNanos == Long.MIN_VALUE || now - lastPublishAtNanos >= publishIntervalNanos) publish(now)
                if (event is RoamingSessionInput.Finish) event.completed.complete(_snapshot.value)
            }
        }
    }

    fun trySend(event: RoamingSessionInput): Boolean = input.trySend(event).isSuccess

    private fun reduceWifi(value: RoamWifiObservation) {
        wifiSampleCount += 1
        previousWifiNanos?.let { previous ->
            val gapMs = ((value.observedAtNanos - previous).coerceAtLeast(0L) / 1_000_000L)
            wifiGapHistogram[gapMs] = (wifiGapHistogram[gapMs] ?: 0L) + 1L
            wifiGapCount += 1L
            wifiGapMaxMs = maxOf(wifiGapMaxMs ?: gapMs, gapMs)
        }
        previousWifiNanos = value.observedAtNanos
        latestWifi?.let { previous ->
            val durationMs = ((value.observedAtNanos - previous.observedAtNanos) / 1_000_000L).coerceIn(0L, 2_000L)
            wifiStats = wifiStats.addDuration(previous, durationMs)
        }
        latestWifi = value
        wifiStats = wifiStats.add(value)
        val switch = detector.observe(value)
        val observedBssid = value.bssid?.lowercase()?.takeIf(::isUsableBssid)
        if (confirmedBssid == null && observedBssid != null) confirmedBssid = observedBssid
        if (switch != null) {
            switches += switch
            flushPendingImpactPings(Long.MAX_VALUE)
            if (switches.size > 1) closeSwitch(switches.lastIndex - 1)
            confirmedBssid = switch.newBssid
        } else if (observedBssid != null && observedBssid == confirmedBssid) {
            flushPendingImpactPings(value.observedAtNanos)
        }
        val shouldDisplay = lastDisplayAtNanos == Long.MIN_VALUE || value.observedAtNanos - lastDisplayAtNanos >= publishIntervalNanos || switch != null
        if (shouldDisplay) {
            displayWifi.addLast(value)
            lastDisplayAtNanos = value.observedAtNanos
            while (displayWifi.size > 1_200) displayWifi.removeFirst()
        }
    }

    private fun reducePing(value: RoamPingAttempt) {
        pings.addLast(value)
        while (pings.size > 6_000) pings.removeFirst()
        if (!value.attempted) return
        if (value.target == RoamProbeTarget.GATEWAY) {
            gatewayStats = gatewayStats.add(value.latencyMs)
        } else {
            wanStats = wanStats.add(value.latencyMs)
        }
        pendingImpactPings.addLast(value)
    }

    private fun flushPendingImpactPings(beforeNanos: Long) {
        if (pendingImpactPings.isEmpty()) return
        val deferred = ArrayDeque<RoamPingAttempt>()
        while (pendingImpactPings.isNotEmpty()) {
            val attempt = pendingImpactPings.removeFirst()
            if (attempt.startedAtNanos < beforeNanos) routeImpactPing(attempt) else deferred.addLast(attempt)
        }
        pendingImpactPings.addAll(deferred)
    }

    private fun routeImpactPing(value: RoamPingAttempt) {
        val index = switches.indexOfLast { value.startedAtNanos >= it.oldObservedAtNanos }
        if (index < 0) return
        val boundary = switches.getOrNull(index + 1)?.oldObservedAtNanos ?: Long.MAX_VALUE
        if (value.startedAtNanos >= boundary) return
        val closed = boundary != Long.MAX_VALUE || !running
        val event = switches[index]
        val current = if (value.target == RoamProbeTarget.GATEWAY) event.gatewayImpact else event.wanImpact
        val attemptedCount = current.attemptedCount + 1
        val updated = when {
            value.startedAtNanos < event.newObservedAtNanos -> current.copy(
                state = if (value.latencyMs == null && closed) RoamImpactState.UNRECOVERED
                    else if (current.state == RoamImpactState.NOT_MONITORED) RoamImpactState.PENDING
                    else current.state,
                attemptedCount = attemptedCount,
                lossCount = current.lossCount + if (value.latencyMs == null && current.recoveryAfterNewBssidMs == null) 1 else 0
            )
            value.latencyMs != null && value.completedAtNanos < boundary && current.recoveryAfterNewBssidMs == null -> current.copy(
                state = if (current.lossCount > 0) RoamImpactState.RECOVERED else RoamImpactState.NO_OUTAGE_OBSERVED,
                attemptedCount = attemptedCount,
                recoveryAfterNewBssidMs = if (current.lossCount > 0) {
                    ((value.completedAtNanos - event.newObservedAtNanos) / 1_000_000L).coerceAtLeast(0L)
                } else null
            )
            value.latencyMs == null && current.recoveryAfterNewBssidMs == null && current.state != RoamImpactState.NO_OUTAGE_OBSERVED -> current.copy(
                state = if (closed) RoamImpactState.UNRECOVERED else RoamImpactState.PENDING,
                attemptedCount = attemptedCount,
                lossCount = current.lossCount + 1
            )
            else -> current.copy(attemptedCount = attemptedCount)
        }
        switches[index] = if (value.target == RoamProbeTarget.GATEWAY) event.copy(gatewayImpact = updated) else event.copy(wanImpact = updated)
    }

    private fun closeSwitch(index: Int) {
        if (index !in switches.indices) return
        fun close(impact: RoamTargetImpact): RoamTargetImpact {
            if (impact.state != RoamImpactState.PENDING) return impact
            return impact.copy(
                state = when {
                    impact.lossCount > 0 -> RoamImpactState.UNRECOVERED
                    impact.attemptedCount > 0 -> RoamImpactState.INSUFFICIENT_EVIDENCE
                    else -> RoamImpactState.NOT_MONITORED
                }
            )
        }
        val event = switches[index]
        switches[index] = event.copy(
            gatewayImpact = close(event.gatewayImpact),
            wanImpact = close(event.wanImpact)
        )
    }

    private fun closeLastSwitch() = closeSwitch(switches.lastIndex)

    private fun publish(now: Long) {
        lastPublishAtNanos = now
        val pingSnapshot = pings.toList()
        val impactedSwitches = switches.toList()
        _snapshot.value = RoamingSessionSnapshot(
            sessionId = sessionId,
            latestWifi = latestWifi,
            displayWifi = displayWifi.toList(),
            pingAttempts = pingSnapshot,
            switches = impactedSwitches,
            networkEvents = networkEvents.toList(),
            wifiSampleCount = wifiSampleCount,
            wifiGapP95Ms = wifiGapP95Ms(),
            wifiGapMaxMs = wifiGapMaxMs,
            gatewayStats = gatewayStats,
            wanStats = wanStats,
            wifiStats = wifiStats,
            running = running
        )
    }

    suspend fun finish(): RoamingSessionSnapshot {
        val completed = CompletableDeferred<RoamingSessionSnapshot>()
        if (!trySend(RoamingSessionInput.Finish(sessionId, completed))) return _snapshot.value
        val finalSnapshot = completed.await()
        input.close()
        scope.cancel()
        return finalSnapshot
    }

    fun cancel() {
        input.close()
        scope.cancel()
    }

    private fun wifiGapP95Ms(): Long? {
        if (wifiGapCount <= 0L) return null
        val targetRank = (wifiGapCount * 95L + 99L) / 100L
        var cumulative = 0L
        for ((gapMs, count) in wifiGapHistogram) {
            cumulative += count
            if (cumulative >= targetRank) return gapMs
        }
        return wifiGapMaxMs
    }
}

private fun RoamingProbeStats.add(latencyMs: Int?): RoamingProbeStats = copy(
    attemptedCount = attemptedCount + 1,
    lossCount = lossCount + if (latencyMs == null) 1 else 0,
    successCount = successCount + if (latencyMs != null) 1 else 0,
    latencySumMs = latencySumMs + (latencyMs ?: 0),
    minLatencyMs = latencyMs?.let { minOf(minLatencyMs ?: it, it) } ?: minLatencyMs,
    maxLatencyMs = latencyMs?.let { maxOf(maxLatencyMs ?: it, it) } ?: maxLatencyMs
)

private fun RoamingWifiStats.add(value: RoamWifiObservation): RoamingWifiStats {
    val rssiValue = value.rssi
    val speedValue = value.linkMbps
    return copy(
        rssiCount = rssiCount + if (rssiValue != null) 1 else 0,
        rssiSumDbm = rssiSumDbm + (rssiValue ?: 0),
        bestRssiDbm = rssiValue?.let { maxOf(bestRssiDbm ?: it, it) } ?: bestRssiDbm,
        worstRssiDbm = rssiValue?.let { minOf(worstRssiDbm ?: it, it) } ?: worstRssiDbm,
        speedCount = speedCount + if (speedValue != null) 1 else 0,
        speedSumMbps = speedSumMbps + (speedValue ?: 0),
        maxSpeedMbps = speedValue?.let { maxOf(maxSpeedMbps ?: it, it) } ?: maxSpeedMbps,
        minSpeedMbps = speedValue?.let { minOf(minSpeedMbps ?: it, it) } ?: minSpeedMbps
    )
}

private fun RoamingWifiStats.addDuration(value: RoamWifiObservation, durationMs: Long): RoamingWifiStats {
    val rssi = value.rssi ?: return this
    if (rssi > -70) return this
    val candidateRssi = value.candidate.rssi
    val candidateValid = value.candidate.bssid != null && candidateRssi != null && candidateRssi - rssi >= 10
    return copy(
        weakDurationMs = weakDurationMs + durationMs,
        stickyDurationMs = stickyDurationMs + if (candidateValid) durationMs else 0L
    )
}
