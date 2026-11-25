package com.labprobe.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfficialAccountRepositoryTest {
    @Test
    fun refreshPostsExplicitHubRefreshAction() = runBlocking {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        var path = ""
        var method = ""
        var body: JSONObject? = null
        val repository = OfficialAccountRepository(prefs) { requestedPath, requestedMethod, requestedBody ->
            path = requestedPath
            method = requestedMethod
            body = requestedBody
            JSONObject("""{
                "ok":true,"account":"186****3092","authorized":true,"state":"partial",
                "projectCount":1,"matchedRouterCount":1,"hubAuthorization":"partial","routers":[]
            }""")
        }

        val snapshot = repository.refresh()

        assertEquals("/api/official-account", path)
        assertEquals("POST", method)
        assertEquals("refresh", body?.getString("action"))
        assertFalse(body?.has("password") == true)
        assertEquals("partial", snapshot.state)
    }

    @Test
    fun officialAccountRequiresHttpsEvenForPrivateLanHttp() {
        assertEquals("https://hub.example.com", requireOfficialAccountHttps("https://hub.example.com"))
        assertThrows(IllegalArgumentException::class.java) {
            requireOfficialAccountHttps("http://192.168.1.2:58443")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireOfficialAccountHttps("http://hub.example.com")
        }
    }
}
