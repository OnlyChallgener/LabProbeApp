package com.labprobe.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test

class RouterSlowDataCacheTest {
    @Test
    fun slowSettingsSnapshotsAreScopedToHubAndToken() {
        val first = RouterSlowDataCache.forScope("http://hub-a:58443", "token-a")
        val second = RouterSlowDataCache.forScope("http://hub-b:58444", "token-b")
        first.capabilities = RouterCapabilities(configured = true, firewall = true)

        assertNotSame(first, second)
        assertNull(second.capabilities)
        assertSame(first, RouterSlowDataCache.forScope("http://hub-a:58443/", "token-a"))
    }
}
