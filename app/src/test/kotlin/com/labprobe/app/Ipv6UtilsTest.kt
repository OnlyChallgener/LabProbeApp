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
}
