package com.labprobe.app

import org.junit.Assert.*
import org.junit.Test

class FavoriteSelectionModelTest {
    private fun card() = FavoriteShortcut("mine", "绿联 NAS", "保留备注", "builtin", "ugreen",
        "https://192.168.5.46:5001/a%2Fb?q=x%20y#section", "https://old.example:1234/login", 0,
        localEndpoint = "https://192.168.5.46:5001/a%2Fb?q=x%20y#section", remoteEndpoint = "https://old.example:1234/login",
        localBinding = FavoriteEndpointBinding("mapping", "map-1", "lan"),
        remoteBinding = FavoriteEndpointBinding("stun", "stun-1", "wan"), protocol = "TCP")

    @Test fun copyAlwaysUsesExternalAddressAndNeverFallsBackToLan() {
        val old = card()
        assertEquals("https://old.example:1234/login", favoriteExternalAddressForCopy(old))
        assertEquals("", favoriteExternalAddressForCopy(old.copy(remoteEndpoint = "", wanUrl = "")))
        assertEquals("https://new.example:5678/path", favoriteExternalAddressForCopy(old.copy(remoteEndpoint = "https://new.example:5678/path", wanUrl = "https://new.example:5678/path")))
    }

    @Test fun independentBindingsSurvivePersistenceAndManualIconStaysLocked() {
        val saved = card()
        assertEquals(saved, parseFavoriteShortcutsJson(serializeFavoriteShortcutsJson(listOf(saved))).single())
        assertFalse(saved.iconAutomatic)
    }

    @Test fun followingOneEndpointPreservesRawSuffixAndAllUserMetadata() {
        val old = card()
        val options = listOf(FavoriteAddressOption(old.localBinding!!, "IPv6 端口映射", "设备已改名", "https://192.168.5.99:8443", "HTTPS"))
        val next = followFavoriteAddressOptions(listOf(old), options).single()
        assertEquals("https://192.168.5.99:8443/a%2Fb?q=x%20y#section", next.localEndpoint)
        assertEquals(old.remoteEndpoint, next.remoteEndpoint)
        assertEquals(old.title, next.title)
        assertEquals(old.description, next.description)
        assertEquals(old.iconValue, next.iconValue)
    }

    @Test fun sourcesCannotCreateCardsOrClaimAnUnboundSameNameCard() {
        val source = FavoriteAddressOption(FavoriteEndpointBinding("home", "nas", "wan"), "首页", "NAS", "https://203.0.113.4:1234", "HTTPS")
        assertTrue(followFavoriteAddressOptions(emptyList(), listOf(source)).isEmpty())
        val manual = card().copy(localBinding = null, remoteBinding = null)
        assertEquals(manual, followFavoriteAddressOptions(listOf(manual), listOf(source)).single())
    }

    @Test fun disappearingSourceRetainsCardAndLastAddress() {
        val old = card()
        assertEquals(old, followFavoriteAddressOptions(listOf(old), emptyList()).single())
    }

    @Test fun distinctSourceIdsNeverMatchBySameAddressOrName() {
        val old = card()
        val unrelated = FavoriteAddressOption(FavoriteEndpointBinding("stun", "other", "wan"), "STUN", old.title, "https://203.0.113.4:1234", "HTTPS")
        assertEquals(old, followFavoriteAddressOptions(listOf(old), listOf(unrelated)).single())
    }

    @Test fun specificIconsWinAndShortAliasesDoNotMatchInsideOtherWords() {
        assertEquals("ugreen", suggestedFavoriteIcon("绿联 NAS", "SSH"))
        assertEquals("dsm", suggestedFavoriteIcon("Synology NAS", "HTTPS"))
        assertEquals("fnos", suggestedFavoriteIcon("飞牛 fnOS", "HTTP"))
        assertEquals("homeassistant", suggestedFavoriteIcon("Home-Assistant", "HTTP"))
        assertEquals("tplink", suggestedFavoriteIcon("TP-Link 路由器", "HTTP"))
        assertEquals("windows", suggestedFavoriteIcon("办公电脑", "RDP"))
        assertEquals("ssh", suggestedFavoriteIcon("终端", "SSH"))
        assertEquals("web", suggestedFavoriteIcon("wgcloud", "HTTP"))
        assertEquals("wireguard", suggestedFavoriteIcon("WG VPN", "UDP"))
        assertEquals("istoreos", suggestedFavoriteIcon("iStoreOS", "HTTP"))
        assertEquals("nodebaby", suggestedFavoriteIcon("节点小宝", "HTTP"))
    }
    @Test fun suffixEditsKeepBindingButAuthorityEditsOptOut() {
        assertTrue(favoriteSameEndpointAuthority("203.0.113.1:1234", "203.0.113.1:1234/be72?q=a%2Fb#top"))
        assertFalse(favoriteSameEndpointAuthority("203.0.113.1:1234", "203.0.113.2:1234/be72"))
        assertFalse(favoriteSameEndpointAuthority("203.0.113.1:1234", "203.0.113.1:4321/be72"))
        assertEquals("https://203.0.113.2:5678/be72?q=a%2Fb#top",
            favoriteFollowEndpoint("https://203.0.113.1:1234/be72?q=a%2Fb#top", "http://203.0.113.2:5678/ignored?source=x"))
        assertEquals("203.0.113.2:5678", favoriteFollowEndpoint("203.0.113.1:1234", "https://203.0.113.2:5678/ignored"))
    }

    @Test fun pairedWanUsesStableSourceAndId() {
        val lan = FavoriteAddressOption(FavoriteEndpointBinding("stun", "rule-a", "lan"), "STUN", "SSH", "192.168.5.46:22", "SSH")
        val wrong = lan.copy(binding = FavoriteEndpointBinding("stun", "rule-b", "wan"))
        val paired = lan.copy(binding = lan.binding.copy(side = "wan"), endpoint = "203.0.113.8:6011")
        assertEquals(paired, favoritePairedRemoteOption(lan, listOf(wrong, paired)))
        assertNull(favoritePairedRemoteOption(lan, listOf(wrong)))
        assertEquals("router", suggestedFavoriteIcon("通用路由器", "HTTP"))
        assertEquals("camera", suggestedFavoriteIcon("门口摄像头", "HTTP"))
    }

}
