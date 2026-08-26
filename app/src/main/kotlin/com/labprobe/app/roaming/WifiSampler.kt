package com.labprobe.app.roaming

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class WifiSampler(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    @Volatile private var lastScanRequestAtNanos: Long = Long.MIN_VALUE

    suspend fun sample(
        sessionId: Long,
        startedAtNanos: Long,
        candidate: RoamCandidateSnapshot
    ): RoamWifiObservation = withContext(Dispatchers.IO) {
        val info = runCatching { wifi?.connectionInfo }.getOrNull()
        // Timestamp the returned snapshot, not the start of the Binder call.
        val observedAtNanos = SystemClock.elapsedRealtimeNanos()
        val rawBssid = info?.bssid?.trim()
        val bssid = rawBssid?.lowercase(Locale.US)?.takeIf(::isUsableBssid)
        val ssid = info?.ssid?.removeSurrounding("\"")?.trim().orEmpty().takeUnless {
            it.isBlank() || it.equals("<unknown ssid>", true)
        } ?: "unknown"
        val frequency = runCatching { info?.frequency ?: 0 }.getOrDefault(0).takeIf { it in 2400..7125 }
        val rssi = runCatching { info?.rssi ?: -127 }.getOrDefault(-127).takeIf { it in -120..0 }
        RoamWifiObservation(
            sessionId = sessionId,
            observedAtNanos = observedAtNanos,
            elapsedMs = ((observedAtNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L),
            wallTimeText = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date()),
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            frequencyMhz = frequency,
            channel = frequency?.let(::wifiChannelOf),
            linkMbps = runCatching { info?.linkSpeed ?: 0 }.getOrDefault(0).takeIf { it > 0 },
            txMbps = runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info?.txLinkSpeedMbps ?: 0 else 0 }.getOrDefault(0).takeIf { it > 0 },
            rxMbps = runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info?.rxLinkSpeedMbps ?: 0 else 0 }.getOrDefault(0).takeIf { it > 0 },
            candidate = candidate,
            unavailableReason = when {
                bssid != null -> null
                rawBssid.equals("00:00:00:00:00:00", true) -> "Wi-Fi 切换中：BSSID 暂不可用"
                else -> "系统暂未提供 BSSID"
            }
        )
    }

    /** Reads the current scan cache first, then requests a future refresh. */
    suspend fun refreshCandidate(current: RoamWifiObservation?, requestScan: Boolean): RoamCandidateSnapshot = withContext(Dispatchers.IO) {
        if (current == null || current.ssid == "unknown" || current.bssid == null) {
            return@withContext RoamCandidateSnapshot(status = "候选 AP：等待有效 Wi-Fi 信息")
        }
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val matches = runCatching {
            wifi?.scanResults.orEmpty().asSequence()
                .filter { it.SSID?.trim().orEmpty() == current.ssid }
                .filter { isUsableBssid(it.BSSID) && !it.BSSID.equals(current.bssid, true) }
                .filter { it.level in -120..0 }
                .sortedByDescending { it.level }
                .toList()
        }.getOrDefault(emptyList())
        val best = matches.firstOrNull()
        val resultAgeMs = best?.timestamp?.takeIf { it > 0L }?.let { timestampMicros ->
            ((nowNanos / 1_000L - timestampMicros) / 1_000L).coerceAtLeast(0L)
        }
        val shouldRequestScan = requestScan && (
            lastScanRequestAtNanos == Long.MIN_VALUE || nowNanos - lastScanRequestAtNanos >= 30_000_000_000L
        )
        val scanAccepted = if (shouldRequestScan) {
            lastScanRequestAtNanos = nowNanos
            runCatching { wifi?.startScan() == true }.getOrDefault(false)
        } else null
        RoamCandidateSnapshot(
            bssid = best?.BSSID?.lowercase(Locale.US),
            rssi = best?.level,
            observedAtNanos = nowNanos,
            resultAgeMs = resultAgeMs,
            sameSsidCount = matches.size,
            status = buildString {
                append(if (best == null) "候选 AP：缓存中未发现同 SSID 候选" else "候选 AP：系统扫描缓存观察值")
                when (scanAccepted) {
                    true -> append(" · 已请求后台刷新")
                    false -> append(" · 刷新被系统拒绝/限流")
                    null -> Unit
                }
            }
        )
    }

    suspend fun gatewayAddress(): String? = withContext(Dispatchers.IO) {
        val value = runCatching { wifi?.dhcpInfo?.gateway ?: 0 }.getOrDefault(0)
        value.takeIf { it != 0 }?.let {
            listOf(it and 0xff, it shr 8 and 0xff, it shr 16 and 0xff, it shr 24 and 0xff).joinToString(".")
        }
    }
}
