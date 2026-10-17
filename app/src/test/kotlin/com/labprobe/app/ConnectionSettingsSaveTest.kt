package com.labprobe.app

import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionSettingsSaveTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: AppPrefs
    private val id = "local_save_test"
    @Before fun setup() {
        RouterWorkspaceStore.resetActive()
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(routerWorkspacePreferencesName(id, ""), Context.MODE_PRIVATE)
            .edit().clear().putString("token", "test-token").commit()
        prefs = AppPrefs(context, id)
        prefs.hub = "http://100.66.1.4:58443"
    }

    @Test fun offlineSaveSurvivesNewPageAndDoesNotChangeOtherRouter() {
        val other = AppPrefs(context, "local_other_save_test")
        other.hub = "http://100.66.1.4:58444"
        prefs.saveConnectionSettings("http://192.168.5.46:58443", "test-token", "223.5.5.5", "",
            RouterConfigUpdate("BE72", "admin", "http://192.168.5.1", "test-password"), "BE72")
        val reopened = AppPrefs(context, id)
        assertEquals("http://192.168.5.46:58443", reopened.hub)
        assertEquals("http://192.168.5.1", reopened.routerLanUrl)
        assertEquals("test-password", reopened.routerPassword)
        assertTrue(reopened.pendingHubIdentity)
        assertTrue(reopened.pendingRouterSettings)
        assertEquals("http://100.66.1.4:58444", other.hub)
    }

    @Test fun routeIdRewiresTheCardOntoItsChannel() {
        // 合并前端的根路径说不出要哪一台路由器，网关会直接 400。填了标识就必须真的带上
        // /r/<id>，否则这台卡片永远连不上 —— 用户看到的正是"一直连接待恢复"。
        prefs.saveConnectionSettings("http://192.168.5.46:58453", "test-token", "223.5.5.5", "BE50",
            null, "BE50")
        assertEquals("http://192.168.5.46:58453/r/be50", AppPrefs(context, id).hub)
    }

    @Test fun resavingTheShownAddressNeitherStacksTheChannelNorLooksLikeAChange() {
        prefs.saveConnectionSettings("http://192.168.5.46:58453", "test-token", "223.5.5.5", "be50", null, "BE50")
        val reopened = AppPrefs(context, id)
        reopened.pendingHubIdentity = false
        reopened.saveConnectionSettings(reopened.hub, "test-token", "223.5.5.5", "be50", null, "BE50")
        val saved = AppPrefs(context, id)
        assertEquals("http://192.168.5.46:58453/r/be50", saved.hub)
        assertFalse("照显示的原样再保存一次不该被判成连接变更", saved.pendingHubIdentity)
    }

    @Test fun clearingTheRouteIdDropsTheChannelBackToTheRoot() {
        prefs.saveConnectionSettings("http://192.168.5.46:58453", "test-token", "223.5.5.5", "be50", null, "BE50")
        val withChannel = AppPrefs(context, id)
        withChannel.saveConnectionSettings(withChannel.hub, "test-token", "223.5.5.5", "", null, "BE50")
        assertEquals("http://192.168.5.46:58453", AppPrefs(context, id).hub)
    }

    @Test fun pendingIdentityBlocksWritesBeforeOpeningANetworkConnection() {        prefs.pendingHubIdentity = true
        assertThrows(java.io.IOException::class.java) {
            HubApi(prefs).requestText("/api/router/child-guard/devices/uid", "DELETE")
        }
    }

    @Test fun addressChangeCancelsInFlightReadAndRejectsLateResponse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            runCatching {
                val body = "{\"old\":true}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        try {
            prefs.hub = "http://127.0.0.1:${server.address.port}"
            val api = HubApi(prefs)
            val pending = executor.submit<Throwable?> { runCatching { api.requestText("/slow") }.exceptionOrNull() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            prefs.saveConnectionSettings("http://192.168.5.46:58443", "test-token", "223.5.5.5", "", null, "BE72")
            assertTrue(pending.get(2, TimeUnit.SECONDS) is CancellationException)
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    @Test fun transportTimeoutIsNotMistakenForAnExplicitConnectionSwitch() {
        val client = OkHttpClient()
        val call = client.newCall(okhttp3.Request.Builder().url("http://localhost/").build())
        val key = HubConnectionRequests.key(id, "timeout", "test")
        HubConnectionRequests.register(key, HubConnectionRequests.generation(key), call)
        call.cancel()
        assertFalse(HubConnectionRequests.wasCancelled(call))
        HubConnectionRequests.cancel(key, invalidate = false)
        assertTrue(HubConnectionRequests.wasCancelled(call))
        HubConnectionRequests.remove(key, call)
    }

    @Test fun blankTokenKeepsTheKeyThatIsAlreadyStored() {
        // Moving a standalone Hub entry onto the merged front changes the workspace
        // identity, not the secret. Clearing the field must not lose the key.
        prefs.saveConnectionSettings("http://100.66.1.4:58443", "   ", "223.5.5.5", "be50", null, "BE50")
        val reopened = AppPrefs(context, id)
        assertEquals("test-token", reopened.token)
        assertEquals("be50", reopened.routerHubId)
        assertEquals("http://100.66.1.4:58443/r/be50", reopened.hub)
    }
}
