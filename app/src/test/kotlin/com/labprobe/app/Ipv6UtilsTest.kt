package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test

class Ipv6UtilsTest {
    private val address = "2409:8a50::1234"

    @Test
    fun validCandidateReplacesFailedCandidate() {
        val result = mergeIpv6Candidates(
            listOf(Ipv6AddressCandidate(address, state = "FAILED", source = "router_ndp")),
            listOf(Ipv6AddressCandidate(address, state = "REACHABLE", source = "router_ndp")),
        )
        assertEquals("REACHABLE", result.single().state)
    }

    @Test
    fun failedCandidateCannotReplaceValidCandidate() {
        val result = mergeIpv6Candidates(
            listOf(Ipv6AddressCandidate(address, state = "REACHABLE", source = "router_ndp")),
            listOf(Ipv6AddressCandidate(address, state = "INCOMPLETE", source = "router_ndp")),
        )
        assertEquals("REACHABLE", result.single().state)
    }

    @Test
    fun compactCurrentGlobalAddressWinsOverLongPrimaryAddress() {
        val short = "2409:8a50:2e41:b700::1c3b"
        val long = "2409:8a50:2e41:b700:8401:3f89:8f04:e660"
        val result = pickBestIpv6(
            emptyList(),
            listOf(
                Ipv6AddressCandidate(long, state = "REACHABLE", source = "hub_primary", primary = true),
                Ipv6AddressCandidate(short, state = "STALE", source = "router_ndp", currentPrefix = true),
            ),
        )
        assertEquals(normalizeIpv6(short), result.best)
    }

    @Test
    fun tentativeAndDeprecatedAddressesAreExcluded() {
        val badShort = "2409:8a50:2e41:b700::1c3b"
        val good = "2409:8a50:2e41:b700:7e2b:e1ff:fe13:bef4"
        val result = pickBestIpv6(
            emptyList(),
            listOf(
                Ipv6AddressCandidate(badShort, state = "TENTATIVE", source = "router_ndp", currentPrefix = true),
                Ipv6AddressCandidate(good, state = "REACHABLE", source = "router_ndp", currentPrefix = true),
            ),
        )
        assertEquals(normalizeIpv6(good), result.best)
        assertEquals(1, result.hiddenCount)
    }

}
