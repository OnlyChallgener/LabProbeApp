package com.labprobe.app

import org.junit.Assert.*
import org.junit.Test

class StunWanGateTest {
    @Test fun secondaryStunRequiresVerifiedIdentity() {
        assertFalse(stunWanVerified("wan1", "wan1", "unknown"))
        assertFalse(stunWanVerified("wan1", "wan", "verified"))
        assertFalse(stunWanVerified("wan1", "", ""))
        assertTrue(stunWanVerified("wan1", "wan1", "verified"))
        assertTrue(stunWanVerified("wan", "", ""))
    }
    @Test fun draftLineIsSerializedAndChangingItRequiresBindingUpdate() {
        val old = StunDraft(wanId = "wan")
        val next = old.copy(wanId = "WAN1")
        assertEquals("wan1", next.toJson().getString("wanId"))
        assertTrue(stunDraftBindingChanged(old, next))
        assertFalse(stunWanSelectable("wan1", emptySet()))
        assertTrue(stunWanSelectable("wan1", setOf("wan1")))
    }
}
