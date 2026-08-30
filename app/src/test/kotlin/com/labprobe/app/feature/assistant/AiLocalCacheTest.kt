package com.labprobe.app.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLocalCacheTest {
    @Test
    fun namespaceIsStableAndDoesNotExposeCredentials() {
        val identity = "https://hub.example.test#super-secret-token"
        val first = aiCacheNamespace(identity)

        assertEquals(first, aiCacheNamespace(identity))
        assertTrue(first.startsWith("v1_") && first.endsWith("_"))
        assertFalse(first.contains("hub.example.test"))
        assertFalse(first.contains("super-secret-token"))
    }

    @Test
    fun namespaceSeparatesHubAndCredentialIdentities() {
        assertNotEquals(
            aiCacheNamespace("https://hub-a.example#token"),
            aiCacheNamespace("https://hub-b.example#token"),
        )
        assertNotEquals(
            aiCacheNamespace("https://hub.example#token-a"),
            aiCacheNamespace("https://hub.example#token-b"),
        )
    }
}
