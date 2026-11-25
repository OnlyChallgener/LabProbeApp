package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6CandidateRetentionTest {
    private fun device(candidates: List<Ipv6AddressCandidate>) = DeviceItem(
        name = "NAS", mac = "aa:bb:cc:dd:ee:ff", online = true, ip = "192.168.5.46",
        ssid = "", band = "", rssi = "", rxrate = "", onlineSince = "", offlineAt = "",
        onlineDurationText = "", lastSeenAt = "",
        ipv6 = candidates.map { it.address }, ipv6Candidates = candidates,
    )

    @Test
    fun retentionKeepsOnlyBestThreeAndDropsHistoricalInvalidRejected() {
        val candidates = listOf(
            Ipv6AddressCandidate("2409:8a50:2e04::1", state = "reachable", source = "ndp", lastSeenAt = 500L),
            Ipv6AddressCandidate("2409:8a50:2e04::2", state = "reachable", source = "ndp", lastSeenAt = 400L),
            Ipv6AddressCandidate("2409:8a50:2e04::3", state = "stale", source = "ndp", lastSeenAt = 300L),
            Ipv6AddressCandidate("2409:8a50:2e04::4", state = "reachable", source = "ndp", lastSeenAt = 200L),
            Ipv6AddressCandidate("2409:8a50:2e04::5", state = "reachable", source = "ndp", historical = true),
            Ipv6AddressCandidate("fe80::1", state = "reachable", source = "ndp"),
            Ipv6AddressCandidate("2409:8a50:2e04::6", state = "failed", source = "ndp"),
        )
        val retained = retainBestIpv6Candidates(candidates)
        assertEquals(
            listOf("2409:8a50:2e04::1", "2409:8a50:2e04::2", "2409:8a50:2e04::4").map(::normalizeIpv6),
            retained.map { it.address },
        )
        assertFalse(retained.any { it.historical || it.state == "failed" || isInvalidIpv6(it.address) })
    }

    @Test
    fun deviceRetentionUpdatesBothListsWithoutMutatingSource() {
        val source = device(
            (1..5).map { Ipv6AddressCandidate("2409:8a50:2e04::$it", state = "reachable", source = "ndp") }
        )
        val retained = source.retainIpv6Candidates()
        assertEquals(3, retained.ipv6.size)
        assertEquals(3, retained.ipv6Candidates.size)
        assertEquals(5, source.ipv6.size)
        assertEquals(retained.ipv6, retained.ipv6Candidates.map { it.address })
    }

    @Test
    fun serializationNeverPersistsMoreThanThreeCandidates() {
        val source = device(
            (1..6).map { Ipv6AddressCandidate("2409:8a50:2e04::$it", state = "reachable", source = "ndp") }
        )
        val json = source.toJson()
        assertEquals(3, json.getJSONArray("ipv6List").length())
        assertEquals(3, json.getJSONArray("ipv6Candidates").length())
        assertTrue((0 until json.getJSONArray("ipv6List").length()).any { index ->
            normalizeIpv6(json.getJSONArray("ipv6List").getString(index)) == normalizeIpv6(json.getString("ipv6"))
        })
    }

    @Test
    fun mappingPickerKeepsRecommendedFirstAndLimitsAlternatives() {
        val preferred = Ipv6AddressCandidate("2409:8a50:2e04::9", primary = true, source = "hub_primary")
        val source = device(
            listOf(preferred) + (1..5).map { Ipv6AddressCandidate("2409:8a50:2e04::$it", state = "reachable", source = "ndp") }
        )
        val shown = portMappingIpv6Candidates(source)
        assertEquals(3, shown.size)
        assertEquals(normalizeIpv6("2409:8a50:2e04::9"), shown.first())
        assertEquals(3, shown.distinct().size)
    }

    @Test
    fun emptyAndSmallListsRemainUnchanged() {
        assertTrue(retainBestIpv6Candidates(emptyList()).isEmpty())
        val one = listOf(Ipv6AddressCandidate("2409:8a50:2e04::1"))
        assertEquals(one.map { normalizeIpv6(it.address) }, retainBestIpv6Candidates(one).map { it.address })
        assertTrue(portMappingIpv6Candidates(device(emptyList())).isEmpty())
    }
}
