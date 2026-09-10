package com.labprobe.app

import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    @Test fun notificationDestinationsAreExplicit() {
        listOf("devices", "events", "daily", "ai_chat", "settings", "home").forEach {
            assertEquals(it, notificationRoute(it))
        }
        assertNull(notificationRoute(null))
        assertNull(notificationRoute(""))
        assertNull(notificationRoute("https://example.com"))
        assertNull(notificationRoute("tool_wireguard"))
    }
}
