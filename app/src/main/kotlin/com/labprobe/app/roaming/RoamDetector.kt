package com.labprobe.app.roaming

import kotlin.math.roundToLong

internal const val INVALID_ANDROID_BSSID = "02:00:00:00:00:00"

internal fun isUsableBssid(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank() || normalized.equals(INVALID_ANDROID_BSSID, true) || normalized == "00:00:00:00:00:00") return false
    return Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$").matches(normalized)
}

internal fun wifiBandOf(frequencyMhz: Int): String = when (frequencyMhz) {
    in 2400..2500 -> "2.4G"
    in 4900..5900 -> "5G"
    in 5925..7125 -> "6G"
    else -> "未知"
}

internal fun wifiChannelOf(frequencyMhz: Int): Int? = when (frequencyMhz) {
    2484 -> 14
    in 2412..2472 -> if ((frequencyMhz - 2407) % 5 == 0) (frequencyMhz - 2407) / 5 else null
    in 4910..4980 -> if ((frequencyMhz - 4000) % 5 == 0) (frequencyMhz - 4000) / 5 else null
    in 5000..5900 -> if ((frequencyMhz - 5000) % 5 == 0) (frequencyMhz - 5000) / 5 else null
    5935 -> 2
    in 5955..7115 -> if ((frequencyMhz - 5950) % 5 == 0) (frequencyMhz - 5950) / 5 else null
    else -> null
}

internal data class RoamCandidateSnapshot(
    val bssid: String? = null,
    val rssi: Int? = null,
    val observedAtNanos: Long = 0L,
    val resultAgeMs: Long? = null,
    val sameSsidCount: Int = 0,
    val status: String = "候选 AP：未启用"
)

internal data class RoamWifiObservation(
    val sessionId: Long,
    val observedAtNanos: Long,
    val elapsedMs: Long,
    val wallTimeText: String,
    val ssid: String,
    val bssid: String?,
    val rssi: Int?,
    val frequencyMhz: Int?,
    val channel: Int?,
    val linkMbps: Int?,
    val txMbps: Int?,
    val rxMbps: Int?,
    val candidate: RoamCandidateSnapshot = RoamCandidateSnapshot(),
    val unavailableReason: String? = null
)

internal enum class RoamProbeTarget { GATEWAY, WAN }

internal data class RoamPingAttempt(
    val sessionId: Long,
    val target: RoamProbeTarget,
    val startedAtNanos: Long,
    val completedAtNanos: Long,
    val attempted: Boolean,
    val latencyMs: Int?,
    val failureReason: String? = null
)

internal enum class RoamImpactState { NOT_MONITORED, NO_OUTAGE_OBSERVED, PENDING, RECOVERED, UNRECOVERED }

internal data class RoamTargetImpact(
    val state: RoamImpactState = RoamImpactState.NOT_MONITORED,
    val attemptedCount: Int = 0,
    val lossCount: Int = 0,
    val recoveryAfterNewBssidMs: Long? = null
)

internal data class BssidSwitchEvent(
    val oldSsid: String,
    val newSsid: String,
    val oldBssid: String,
    val newBssid: String,
    val oldObservedAtNanos: Long,
    val newObservedAtNanos: Long,
    val confirmedAtNanos: Long,
    val oldRssi: Int?,
    val newRssi: Int?,
    val oldFrequencyMhz: Int?,
    val newFrequencyMhz: Int?,
    val observationMs: Long,
    val gatewayImpact: RoamTargetImpact = RoamTargetImpact(),
    val wanImpact: RoamTargetImpact = RoamTargetImpact()
)

/**
 * Detects an observed BSSID transition from public WifiInfo snapshots.
 *
 * Two consecutive observations of the new BSSID are required to reject a
 * one-sample A -> B -> A glitch. The event remains anchored to the first new
 * observation, so confirmation does not inflate the observation window.
 */
internal class RoamDetector(private val confirmationSamples: Int = 2) {
    private var lastConfirmed: RoamWifiObservation? = null
    private var pendingFirst: RoamWifiObservation? = null
    private var pendingCount = 0

    fun reset() {
        lastConfirmed = null
        pendingFirst = null
        pendingCount = 0
    }

    fun observe(sample: RoamWifiObservation): BssidSwitchEvent? {
        val bssid = sample.bssid?.lowercase()?.takeIf(::isUsableBssid) ?: return null
        val current = lastConfirmed
        if (current == null) {
            lastConfirmed = sample.copy(bssid = bssid)
            return null
        }
        val currentBssid = current.bssid?.lowercase().orEmpty()
        if (sample.ssid != current.ssid) {
            lastConfirmed = sample.copy(bssid = bssid)
            pendingFirst = null
            pendingCount = 0
            return null
        }
        if (bssid == currentBssid) {
            lastConfirmed = sample.copy(bssid = bssid)
            pendingFirst = null
            pendingCount = 0
            return null
        }

        val pending = pendingFirst
        if (pending?.bssid.equals(bssid, ignoreCase = true)) {
            pendingCount += 1
        } else {
            pendingFirst = sample.copy(bssid = bssid)
            pendingCount = 1
        }
        if (pendingCount < confirmationSamples.coerceAtLeast(1)) return null

        val firstNew = pendingFirst ?: sample
        val event = BssidSwitchEvent(
            oldSsid = current.ssid,
            newSsid = firstNew.ssid,
            oldBssid = currentBssid,
            newBssid = bssid,
            oldObservedAtNanos = current.observedAtNanos,
            newObservedAtNanos = firstNew.observedAtNanos,
            confirmedAtNanos = sample.observedAtNanos,
            oldRssi = current.rssi,
            newRssi = firstNew.rssi,
            oldFrequencyMhz = current.frequencyMhz,
            newFrequencyMhz = firstNew.frequencyMhz,
            observationMs = ((firstNew.observedAtNanos - current.observedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        )
        lastConfirmed = sample.copy(bssid = bssid)
        pendingFirst = null
        pendingCount = 0
        return event
    }
}

internal fun attachProbeImpacts(
    switches: List<BssidSwitchEvent>,
    attempts: List<RoamPingAttempt>,
    final: Boolean = false
): List<BssidSwitchEvent> = switches.mapIndexed { index, event ->
    val boundary = switches.getOrNull(index + 1)?.oldObservedAtNanos ?: Long.MAX_VALUE
    fun impact(target: RoamProbeTarget): RoamTargetImpact {
        val scoped = attempts.asSequence()
            .filter { it.target == target && it.attempted }
            .filter { it.startedAtNanos >= event.oldObservedAtNanos && it.startedAtNanos < boundary }
            .sortedBy { it.startedAtNanos }
            .toList()
        if (scoped.isEmpty()) return RoamTargetImpact()
        val firstSuccess = scoped.firstOrNull {
            it.startedAtNanos >= event.newObservedAtNanos && it.latencyMs != null && it.completedAtNanos < boundary
        }
        val lossEnd = firstSuccess?.startedAtNanos ?: boundary
        val losses = scoped.count { it.latencyMs == null && it.startedAtNanos < lossEnd }
        if (losses == 0) {
            return RoamTargetImpact(
                state = if (firstSuccess != null) RoamImpactState.NO_OUTAGE_OBSERVED else if (final) RoamImpactState.UNRECOVERED else RoamImpactState.PENDING,
                attemptedCount = scoped.size,
                lossCount = 0
            )
        }
        return RoamTargetImpact(
            state = if (firstSuccess != null) RoamImpactState.RECOVERED else if (final) RoamImpactState.UNRECOVERED else RoamImpactState.PENDING,
            attemptedCount = scoped.size,
            lossCount = losses,
            recoveryAfterNewBssidMs = firstSuccess?.let {
                ((it.completedAtNanos - event.newObservedAtNanos) / 1_000_000L).coerceAtLeast(0L)
            }
        )
    }
    event.copy(gatewayImpact = impact(RoamProbeTarget.GATEWAY), wanImpact = impact(RoamProbeTarget.WAN))
}

internal fun percentile95Ms(gapsNanos: Collection<Long>): Long? {
    if (gapsNanos.isEmpty()) return null
    val sorted = gapsNanos.map { it.coerceAtLeast(0L) / 1_000_000.0 }.sorted()
    val index = ((sorted.size - 1) * .95).roundToLong().toInt().coerceIn(sorted.indices)
    return sorted[index].roundToLong()
}
