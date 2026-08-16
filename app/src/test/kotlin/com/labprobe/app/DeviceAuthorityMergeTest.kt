package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthorityMergeTest {
    private fun device(
        online: Boolean,
        offlineAt: String,
        lastSeenAt: String,
        todayUpload: String,
        todayDownload: String,
        followed: Boolean? = null,
        ip: String = "192.168.5.23",
    ) = DeviceItem(
        name = "华为Mate60",
        mac = "24:1a:e6:bb:16:d9",
        online = online,
        ip = ip,
        ssid = "Ruijie-s8067",
        band = "5G",
        rssi = "-87",
        rxrate = "",
        onlineSince = "",
        offlineAt = offlineAt,
        onlineDurationText = "",
        lastSeenAt = lastSeenAt,
        followedOverride = followed,
        todayUpload = todayUpload,
        todayDownload = todayDownload,
    )

    @Test
    fun offlineArchiveOverridesStaleWatchedCopyAndKeepsFollowedFlag() {
        val watched = device(
            online = false,
            offlineAt = "2026-08-02 08:25:27",
            lastSeenAt = "2026-08-02 08:25:27",
            todayUpload = "9.51M",
            todayDownload = "85.14M",
            followed = true,
        )
        val authoritativeOffline = device(
            online = false,
            offlineAt = "2026-08-02 15:29:39",
            lastSeenAt = "2026-08-02 15:29:39",
            todayUpload = "24.88M",
            todayDownload = "250.26M",
            followed = false,
        )

        val merged = mergeSharedDeviceState(
            watched = listOf(watched, authoritativeOffline),
            online = emptyList(),
        ).single()

        assertFalse(merged.online)
        assertEquals("2026-08-02 15:29:39", merged.offlineAt)
        assertEquals("2026-08-02 15:29:39", merged.lastSeenAt)
        assertEquals("24.88M", merged.todayUpload)
        assertEquals("250.26M", merged.todayDownload)
        assertTrue(merged.followedOverride == true)
    }

    @Test
    fun currentOnlineRecordOverridesOfflineArchiveAndKeepsFollowedFlag() {
        val watched = device(false, "2026-08-02 08:25:27", "2026-08-02 08:25:27", "9.51M", "85.14M", true)
        val offline = device(false, "2026-08-02 15:29:39", "2026-08-02 15:29:39", "24.88M", "250.26M", false)
        val online = device(true, "", "2026-08-02 19:10:00", "31.20M", "310.40M", followed = false)

        val merged = mergeSharedDeviceState(listOf(watched, offline), listOf(online)).single()

        assertTrue(merged.online)
        assertEquals("2026-08-02 19:10:00", merged.lastSeenAt)
        assertEquals("31.20M", merged.todayUpload)
        assertEquals("310.40M", merged.todayDownload)
        assertTrue(merged.followedOverride == true)
    }
}
