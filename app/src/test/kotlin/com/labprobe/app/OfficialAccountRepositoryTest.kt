package com.labprobe.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfficialAccountRepositoryTest {
    private val binding = OfficialCloudBinding(
        serial = "SN72",
        projectId = "p72",
        projectName = "Home",
        createTime = "2026-01-01 00:00:00",
        meshSerials = listOf("SN72"),
    )

    private class FakeCloud(
        private val binding: OfficialCloudBinding,
        var session: OfficialCloudSession? = null,
    ) : OfficialCloudGateway {
        var logins = 0
        var refreshes = 0
        var logouts = 0
        var failDiscover = false
        override fun login(account: String, password: String): OfficialCloudSession {
            logins++
            return OfficialCloudSession(account, "cloud-secret", System.currentTimeMillis() + 600_000).also { session = it }
        }
        override fun refresh(session: OfficialCloudSession): OfficialCloudSession {
            refreshes++
            return session.copy(token = "rotated-secret", expireTime = System.currentTimeMillis() + 1_200_000)
                .also { this.session = it }
        }
        override fun logout(session: OfficialCloudSession) { logouts++ }
        override fun projects(session: OfficialCloudSession): List<JSONObject> = emptyList()
        override fun discoverBinding(session: OfficialCloudSession, serial: String): OfficialCloudBinding {
            if (failDiscover) throw OfficialCloudException("official_unavailable", "cloud unavailable")
            return binding.copy(serial = serial)
        }
        override fun members(session: OfficialCloudSession, binding: OfficialCloudBinding): List<OfficialCloudMember> = emptyList()
        override fun add(session: OfficialCloudSession, binding: OfficialCloudBinding, mac: String, name: String) =
            OfficialCloudMember("A".repeat(32), setOf(compactMac(mac)))
        override fun remove(session: OfficialCloudSession, binding: OfficialCloudBinding, member: OfficialCloudMember) = Unit
    }

    private class MemoryStorage : OfficialAccountStorage {
        private var session: OfficialCloudSession? = null
        override fun get(): OfficialCloudSession? = session
        override fun set(session: OfficialCloudSession) { this.session = session }
        override fun clear() { session = null }
    }

    @Test
    fun loginAuthenticatesDirectlyWithCloudAndSendsOnlyBindingMetadataToHub() = runBlocking {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        prefs.hubRoot = "http://192.168.1.2:58443"
        val cloud = FakeCloud(binding)
        val store = MemoryStorage()
        var posted: JSONObject? = null
        val repository = OfficialAccountRepository(prefs, request = { path, method, body ->
            when {
                method == "GET" -> JSONObject("""{"ok":true,"authorized":false,"state":"unauthorized","routers":[{"routerId":"default","serialNumber":"SN72","online":true}]}""")
                method == "POST" -> {
                    posted = body
                    JSONObject("""{"ok":true,"account":"u***@example.com","authorized":true,"state":"authorized","projectCount":1,"matchedRouterCount":1,"hubAuthorization":"synced","routers":[{"routerId":"default","serialNumber":"SN72","authorized":true,"matched":true,"projectId":"p72","bindingVersion":1,"createTime":"2026-01-01 00:00:00","meshSerials":["SN72"]}]}""")
                }
                else -> error("unexpected $method $path")
            }
        }, cloud = cloud, store = store)

        val snapshot = repository.login("owner@example.com", "password-never-sent-to-hub")

        assertEquals(1, cloud.logins)
        assertTrue(snapshot.authorized)
        assertEquals("cloud-secret", store.get()?.token)
        val body = posted!!
        assertEquals("bind", body.getString("action"))
        assertFalse(body.has("password"))
        assertFalse(body.has("token"))
        assertFalse(body.has("refreshToken"))
        assertFalse(body.has("Authorization"))
        assertEquals("p72", body.getJSONArray("bindings").getJSONObject(0).getString("projectId"))
    }

    @Test
    fun refreshRotatesCloudTokenWithoutPostingCredentialsToHub() = runBlocking {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        val cloud = FakeCloud(binding)
        val store = MemoryStorage()
        store.set(OfficialCloudSession("owner@example.com", "old-secret", System.currentTimeMillis() + 600_000))
        var posted: JSONObject? = null
        val repository = OfficialAccountRepository(prefs, request = { _, method, body ->
            if (method == "GET") JSONObject("""{"ok":true,"authorized":false,"state":"unauthorized","routers":[{"routerId":"default","serialNumber":"SN72"}]}""")
            else {
                posted = body
                JSONObject("""{"ok":true,"authorized":true,"state":"authorized","routers":[]}""")
            }
        }, cloud = cloud, store = store)

        repository.refresh()

        assertEquals(1, cloud.refreshes)
        assertEquals("rotated-secret", store.get()?.token)
        assertFalse(posted?.has("token") == true)
        assertFalse(posted?.has("password") == true)
    }

    @Test
    fun newUserCanReadOfficialAccountOverPrivateHttpWithoutHttpsConfiguration() = runBlocking {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        prefs.hubRoot = "http://192.168.1.2:58443"
        var path = ""
        val repository = OfficialAccountRepository(prefs, request = { requestedPath, _, _ ->
            path = requestedPath
            JSONObject("""{"ok":true,"authorized":false,"state":"unauthorized","routers":[]}""")
        })

        val snapshot = repository.status(verify = false)

        assertEquals("/api/official-account?verify=0", path)
        assertFalse(snapshot.authorized)
        assertEquals("unauthorized", snapshot.state)
        assertEquals("", prefs.httpsAdminToken)
    }

    @Test
    fun legacyHubNeverReceivesPasswordAndFallsBackToLocalFeatures() = runBlocking {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        val cloud = FakeCloud(binding)
        val store = MemoryStorage()
        val repository = OfficialAccountRepository(prefs, request = { _, method, body ->
            when (method) {
                "GET" -> JSONObject("""{"ok":true,"authorized":false,"state":"unauthorized","routers":[{"routerId":"default","serialNumber":"SN72"}]}""")
                "POST" -> {
                    assertFalse(body?.has("password") == true)
                    assertFalse(body?.has("token") == true)
                    throw HubHttpException(400, "请填写官方账号和密码")
                }
                else -> error("unexpected $method")
            }
        }, cloud = cloud, store = store)

        val snapshot = repository.login("owner@example.com", "password")

        assertEquals("unsupported", snapshot.state)
        assertEquals("cloud-secret", store.get()?.token)
        assertFalse(snapshot.authorized)
    }

    @Test
    fun cloudVerificationFailureFallsBackToNativeChildGuard() = runBlocking {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        val cloud = FakeCloud(binding).apply { failDiscover = true }
        val store = MemoryStorage().apply {
            set(OfficialCloudSession("owner@example.com", "cloud-secret", System.currentTimeMillis() + 600_000))
        }
        val repository = OfficialAccountRepository(prefs, request = { _, _, _ ->
            JSONObject("""{"ok":true,"authorized":true,"state":"authorized","routers":[{"routerId":"default","serialNumber":"SN72","matched":true,"projectId":"p72","bindingVersion":1,"createTime":"2026-01-01 00:00:00","meshSerials":["SN72"]}]}""")
        }, cloud = cloud, store = store)
        val sync = OfficialMembershipSync(prefs, cloud, store) { repository }

        assertNull(sync.add("aa:bb:cc:dd:ee:01", "iPad"))
    }

    @Test
    fun officialAccountAcceptsPrivateLanHttpButStillRejectsPublicHttp() {
        assertEquals("https://hub.example.com", officialAccountHubAddress("https://hub.example.com"))
        assertEquals("http://192.168.1.2:58443", officialAccountHubAddress("http://192.168.1.2:58443"))
        assertEquals("http://10.0.0.8:58443", officialAccountHubAddress("http://10.0.0.8:58443"))
        assertEquals("http://172.16.4.8:58443", officialAccountHubAddress("http://172.16.4.8:58443"))
        assertEquals("http://[fd00::8]:58443", officialAccountHubAddress("http://[fd00::8]:58443"))
        assertThrows(IllegalArgumentException::class.java) {
            officialAccountHubAddress("http://hub.example.com")
        }
    }
}
