package com.labprobe.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterRepositoryRefreshTest {
    @Test fun forcedRefreshBlocksBackgroundRefreshUntilAllForceCallsFinish() {
        val gate = ForcedRefreshGate()
        assertTrue(gate.tryBegin("upnp", force = false))
        gate.finish("upnp", force = false)

        assertTrue(gate.tryBegin("upnp", force = true))
        assertFalse(gate.tryBegin("upnp", force = false))
        assertTrue(gate.tryBegin("upnp", force = true))

        gate.finish("upnp", force = true)
        assertFalse(gate.tryBegin("upnp", force = false))
        gate.finish("upnp", force = true)
        assertTrue(gate.tryBegin("upnp", force = false))
    }

    @Test fun forcedRefreshGateKeepsResourcesIndependent() {
        val gate = ForcedRefreshGate()
        assertTrue(gate.tryBegin("upnp", force = true))
        assertTrue(gate.tryBegin("portMappings", force = false))
        assertFalse(gate.tryBegin("upnp", force = false))
        gate.finish("upnp", force = true)
        assertTrue(gate.tryBegin("upnp", force = false))
    }
}
