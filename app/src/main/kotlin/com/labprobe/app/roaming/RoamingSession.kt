package com.labprobe.app.roaming

import kotlinx.coroutines.CoroutineScope
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
    val running: Boolean = true
)

internal sealed interface RoamingSessionInput {
    val sessionId: Long
    data class Wifi(override val sessionId: Long, val value: RoamWifiObservation) : RoamingSessionInput
    data class Ping(override val sessionId: Long, val value: RoamPingAttempt) : RoamingSessionInput
    data class Network(override val sessionId: Long, val value: RoamingNetworkLayerEvent) : RoamingSessionInput
    data class Finish(override val sessionId: Long) : RoamingSessionInput
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
    private val switches = mutableListOf<BssidSwitchEvent>()
    private val networkEvents = ArrayDeque<RoamingNetworkLayerEvent>()
    private val recentGaps = ArrayDeque<Long>()
    private var latestWifi: RoamWifiObservation? = null
    private var previousWifiNanos: Long? = null
    private var wifiSampleCount = 0
    private var lastDisplayAtNanos = Long.MIN_VALUE
    private var lastPublishAtNanos = Long.MIN_VALUE
    private var running = true
    private val _snapshot = MutableStateFlow(RoamingSessionSnapshot(sessionId = sessionId))
    val snapshot: StateFlow<RoamingSessionSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            for (event in input) {
                if (event.sessionId != sessionId) continue
                when (event) {
                    is RoamingSessionInput.Wifi -> reduceWifi(event.value)
                    is RoamingSessionInput.Ping -> {
                        pings.addLast(event.value)
                        while (pings.size > 10_000) pings.removeFirst()
                    }
                    is RoamingSessionInput.Network -> {
                        if (networkEvents.lastOrNull()?.label != event.value.label) networkEvents.addLast(event.value)
                        while (networkEvents.size > 80) networkEvents.removeFirst()
                    }
                    is RoamingSessionInput.Finish -> running = false
                }
                val now = when (event) {
                    is RoamingSessionInput.Wifi -> event.value.observedAtNanos
                    is RoamingSessionInput.Ping -> event.value.completedAtNanos
                    is RoamingSessionInput.Network -> event.value.observedAtNanos
                    is RoamingSessionInput.Finish -> latestWifi?.observedAtNanos ?: 0L
                }
                if (!running || lastPublishAtNanos == Long.MIN_VALUE || now - lastPublishAtNanos >= publishIntervalNanos) publish(now)
            }
        }
    }

    fun trySend(event: RoamingSessionInput): Boolean = input.trySend(event).isSuccess

    private fun reduceWifi(value: RoamWifiObservation) {
        wifiSampleCount += 1
        previousWifiNanos?.let { previous ->
            recentGaps.addLast((value.observedAtNanos - previous).coerceAtLeast(0L))
            while (recentGaps.size > 2_048) recentGaps.removeFirst()
        }
        previousWifiNanos = value.observedAtNanos
        latestWifi = value
        val switch = detector.observe(value)
        if (switch != null) switches += switch
        val shouldDisplay = lastDisplayAtNanos == Long.MIN_VALUE || value.observedAtNanos - lastDisplayAtNanos >= publishIntervalNanos || switch != null
        if (shouldDisplay) {
            displayWifi.addLast(value)
            lastDisplayAtNanos = value.observedAtNanos
            while (displayWifi.size > 1_200) displayWifi.removeFirst()
        }
    }

    private fun publish(now: Long) {
        lastPublishAtNanos = now
        val gaps = recentGaps.toList()
        _snapshot.value = RoamingSessionSnapshot(
            sessionId = sessionId,
            latestWifi = latestWifi,
            displayWifi = displayWifi.toList(),
            pingAttempts = pings.toList(),
            switches = attachProbeImpacts(switches, pings.toList(), final = !running),
            networkEvents = networkEvents.toList(),
            wifiSampleCount = wifiSampleCount,
            wifiGapP95Ms = percentile95Ms(gaps),
            wifiGapMaxMs = gaps.maxOrNull()?.div(1_000_000L),
            running = running
        )
    }

    fun close() {
        trySend(RoamingSessionInput.Finish(sessionId))
        input.close()
    }

    fun cancel() {
        input.close()
        scope.cancel()
    }
}
