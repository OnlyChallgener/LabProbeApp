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
    }

    @Test fun optionsContainOnlyOnlineConfigurableWans() {
        val wans = listOf(
            WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false),
            WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = true),
        )
        assertEquals(listOf("wan"), stunWanOptions(wans))

        val dualOnline = wans.map { it.copy(online = true) }
        assertEquals(listOf("wan", "wan1"), stunWanOptions(dualOnline))
    }

    @Test fun newDraftUsesPreferredOnlineWan() {
        val inventory = WanInventory(wans = listOf(
            WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = true, defaultEgress = true),
            WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = true),
        ))
        assertEquals("wan", newStunDraft(inventory).wanId)
        assertEquals("wan1", newStunDraft(inventory.copy(wans = inventory.wans.map {
            if (it.wanId == "wan") it.copy(online = false) else it
        })).wanId)
        assertEquals("wan", newStunDraft(null).wanId)
    }

    @Test fun wan1OnlyOnlineDoesNotOfferPrimaryWan() {
        val wans = listOf(
            WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = false),
            WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = true),
        )
        assertEquals(listOf("wan1"), stunWanOptions(wans))
    }

    @Test fun historyLabelsRequireDualOnlineLines() {
        val singleOnline = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = true),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false),
            ),
            onlineCount = 1,
        )
        val dualOnline = singleOnline.copy(
            wans = singleOnline.wans.map { it.copy(online = true) },
            onlineCount = 2,
        )
        assertFalse(stunHistoryShowsWanLabels(singleOnline))
        assertTrue(stunHistoryShowsWanLabels(dualOnline))
    }
}
