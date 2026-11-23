package com.labprobe.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FavoriteSourcesMigrationTest {
    private fun card(id: String) = FavoriteShortcut(id, id, "", "builtin", "web", "192.168.5.2:80", "203.0.113.2:8080", 0, serviceType = "HTTP")
    private fun prefs(id: String) = AppPrefs(RuntimeEnvironment.getApplication(), id).apply { hubRoot = "http://127.0.0.1:1" }

    @Test fun oneTimeCleanupRemovesOldSourceCardsAndRetainsManualAndDomainCards() {
        val prefs = prefs("favorite_reset_test")
        val manual = card("manual")
        val domain = card("domain").copy(ddnsRecordId = "dns-1")
        prefs.favoriteSourceResetVersion = 0
        prefs.favoriteShortcutsJson = serializeFavoriteShortcutsJson(listOf(manual,
            card("stun").copy(type = "stun", stunRuleId = "s-1"),
            card("mapping").copy(mappingId = "map-1"), card("webhook-old"),
            card("bound").copy(remoteBinding = FavoriteEndpointBinding("home", "nas", "wan")), domain))
        assertEquals(listOf("manual", "domain"), prefs.favoriteShortcuts().map { it.id })
        val newlyBound = card("new").copy(remoteBinding = FavoriteEndpointBinding("stun", "s-1", "wan"))
        prefs.saveFavoriteShortcuts(prefs.favoriteShortcuts() + newlyBound)
        assertEquals(listOf("manual", "domain", "new"), prefs.favoriteShortcuts().map { it.id })
        assertEquals(1, prefs.favoriteSourceResetVersion)
    }

    @Test fun cleanupIsScopedToEachWorkspace() {
        val a = prefs("favorite_reset_a"); val b = prefs("favorite_reset_b")
        for (prefs in listOf(a, b)) {
            prefs.favoriteSourceResetVersion = 0
            prefs.favoriteShortcutsJson = serializeFavoriteShortcutsJson(listOf(card("webhook-old")))
        }
        assertTrue(a.favoriteShortcuts().isEmpty())
        assertEquals(0, b.favoriteSourceResetVersion)
        assertTrue(b.favoriteShortcuts().isEmpty())
    }

    @Test fun sourceLabelShowsNameAndHostPortWithoutSchemeOrPath() {
        val option = FavoriteAddressOption(FavoriteEndpointBinding("webhook", "nas", "wan"), "Webhook 推送", "绿联",
            "https://[240e:1234::8]:5001/admin?q=x", "HTTPS")
        assertEquals("绿联 - [240e:1234::8]:5001", favoriteSourceOptionLabel(option))
    }

    @Test fun plainHttpStunAndWebhookAddressesAreOfferedAndFollowPortChanges() {
        val prefs = prefs("favorite_source_options")
        prefs.cacheStatus = """{"vpnStunAddresses":[{"name":"绿联","address":"203.0.113.8:6011","source":"Webhook"}]}"""
        val stun = StunRule("s-1", "飞牛", true, 20001, targetIpv4 = "192.168.5.9", targetPort = 5666,
            serviceType = "HTTP", transportProtocol = "TCP", forwardMode = "router_native", actualState = "mapped",
            firewallState = "ready", nativeMappingState = "ready", runtime = StunRuntime(publicEndpoint = "203.0.113.9:5905", mappingFresh = true))
        val options = favoriteAddressOptions(prefs, emptyList(), listOf(stun))
        assertEquals("203.0.113.9:5905", options.first { it.binding == FavoriteEndpointBinding("stun", "s-1", "wan") }.endpoint)
        assertEquals("203.0.113.8:6011", options.first { it.binding.source == "webhook" }.endpoint)
        assertEquals("203.0.113.10:6012/a%2Fb?q=x%20y#top", favoriteFollowEndpoint("203.0.113.8:6011/a%2Fb?q=x%20y#top", "203.0.113.10:6012"))
        val chosen = card("mine").copy(remoteBinding = FavoriteEndpointBinding("stun", "s-1", "wan"))
        assertEquals("203.0.113.9:5905", followFavoriteAddressOptions(listOf(chosen), options).single().remoteEndpoint)
        assertTrue(followFavoriteAddressOptions(emptyList(), options).isEmpty())
    }
}
