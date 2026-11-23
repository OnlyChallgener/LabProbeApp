package com.labprobe.app

import android.content.Context
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouterWorkspaceGatewayMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val hub = "http://192.168.5.46:58443"
    private fun registry(defaultId: String = "be72") = JSONObject("""{
        "defaultRouterId":"$defaultId","routers":[
          {"routerId":"be72","name":"BE72","model":"BE72","online":true,"basePath":"/r/be72",
           "serialNumber":"SN-BE72","deviceCount":30,"onlineDeviceCount":10},
          {"routerId":"be50","name":"BE50","model":"BE50","online":true,"basePath":"/r/be50",
           "serialNumber":"SN-BE50","deviceCount":26,"onlineDeviceCount":8}
        ]}""")
    private fun dashboard(sn: String) = """{"details":{"ap":{"serialNumber":"$sn"}}}"""

    @Before fun reset() {
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        RouterWorkspaceStore.resetActive()
        AppPrefs(context).hubRoot = hub
    }

    @Test fun staleDefaultPlaceholderDoesNotInventAThirdRegistryEntry() {
        val rows = parseRouterWorkspaces(registry(), "default")
        assertEquals(listOf("default", "be50"), rows.map { it.routerId })
        assertEquals(listOf("be72", "be50"), rows.map { it.serverRouterId })
        assertEquals("/r/be72", rows.first().basePath)
    }

    @Test fun physicalLegacyIdentitySurvivesAChangedStartupDefault() {
        assertEquals("be72", registryLegacyServerId(registry("be50"), "default", "SN-BE72", ""))
        assertEquals("be72", registryLegacyServerId(registry("be50"), "be72", "", ""))
        assertEquals("be72", registryLegacyServerId(registry("be50"), "BE72", "", ""))
    }

    @Test fun wireAddressUsesConfirmedServerPathEvenForTheDefaultCard() {
        RouterWorkspaceStore.recordServerDefault(context, hub, "be72")
        RouterWorkspaceStore.recordLegacyServerId(context, hub, "be72")
        RouterWorkspaceStore.cacheList(context, hub, registry())
        assertEquals("$hub/r/be72", AppPrefs(context).hub)
        RouterWorkspaceStore.recordServerDefault(context, hub, "be50")
        assertEquals("$hub/r/be72", AppPrefs(context).hub)
    }

    @Test fun anUnconfirmedLocalDefaultNeverBecomesARemoteRoute() {
        RouterWorkspaceStore.recordServerDefault(context, hub, "default")
        RouterWorkspaceStore.recordServerDefault(context, hub, "be72")
        assertEquals(hub, AppPrefs(context).hub)
        assertFalse(AppPrefs(context).hub.endsWith("/r/default"))
    }

    @Test fun twoHistoricalCardsAreAttachedBySerialAndKeepTheirOwnSettings() {
        val be72 = LocalRouterWorkspaceRegistry.add(context, hub, "家里")
        val be50 = LocalRouterWorkspaceRegistry.add(context, hub, "另一处")
        val a = AppPrefs(context, be72.routerId)
        a.hub = hub
        a.cacheRouterDashboard = dashboard("SN-BE72")
        a.cacheStatus = "be72-history"
        val b = AppPrefs(context, be50.routerId)
        b.hubRoot = "http://100.66.1.4:58444"
        b.hub = b.hubRoot
        b.cacheRouterDashboard = dashboard("SN-BE50")
        b.cacheStatus = "be50-history"
        b.deviceOverridesJson = """[{"mac":"AA:BB:CC:DD:EE:01","followed":true}]"""
        RouterWorkspaceStore.recordLegacyServerId(context, hub, "be72")
        val rows = loadVisibleRouterWorkspaces(context, hub, parseRouterWorkspaces(registry(), "be72"), registryConfirmed = true)
        assertEquals(2, rows.size)
        assertEquals(setOf("be72", "be50"), rows.map { it.serverRouterId }.toSet())
        assertTrue(rows.none { it.localDraft })
        assertEquals("$hub/r/be72", AppPrefs(context, be72.routerId).hub)
        assertEquals("$hub/r/be50", AppPrefs(context, be50.routerId).hub)
        assertEquals("be72-history", AppPrefs(context, be72.routerId).cacheStatus)
        assertEquals("be50-history", AppPrefs(context, be50.routerId).cacheStatus)
        assertTrue(AppPrefs(context, be50.routerId).deviceOverridesJson.contains("followed"))
        assertEquals(26, rows.single { it.serverRouterId == "be50" }.deviceCount)
        assertEquals(be72.routerId, registeredWorkspaceSelection(context, hub, rows, "default"))
        assertEquals(be72.routerId, RouterWorkspaceStore.restoreActive(context, hub, rows))
    }

    @Test fun sameDisplayNameWithoutPhysicalEvidenceIsNeverSilentlyMerged() {
        val draft = LocalRouterWorkspaceRegistry.add(context, hub, "BE50")
        AppPrefs(context, draft.routerId).hub = "http://different-hub:58444"
        val rows = loadVisibleRouterWorkspaces(context, hub, parseRouterWorkspaces(registry(), "be72"), registryConfirmed = true)
        assertEquals(3, rows.size)
        assertTrue(rows.single { it.routerId == draft.routerId }.localDraft)
        assertEquals("http://different-hub:58444", AppPrefs(context, draft.routerId).hub)
        assertTrue(routerWorkspaceSummaryLine(rows.single { it.routerId == draft.routerId }).startsWith("独立配置"))
    }

    @Test fun aCachedRegistryAloneCannotRebindAnIndependentSavedConnection() {
        val draft = LocalRouterWorkspaceRegistry.add(context, hub, "BE50")
        val prefs = AppPrefs(context, draft.routerId)
        prefs.hubRoot = "http://100.66.1.4:58444"
        prefs.hub = prefs.hubRoot
        prefs.cacheRouterDashboard = dashboard("SN-BE50")
        val rows = loadVisibleRouterWorkspaces(context, hub, parseRouterWorkspaces(registry(), "be72"))
        assertEquals(3, rows.size)
        assertEquals("http://100.66.1.4:58444", prefs.hub)
    }

    @Test fun multipleAliasesOfOneRegisteredDeviceKeepTheSelectedAliasOnly() {
        val one = RouterWorkspace("local_one", "一", "", "", false, "", localDraft = true)
        val two = one.copy(routerId = "local_two")
        val rows = mergeRouterWorkspaces(parseRouterWorkspaces(registry(), "be72"), listOf(one, two),
            mapOf("local_one" to "be50", "local_two" to "be50"), preferredLocalId = "local_two")
        assertEquals(2, rows.size)
        assertEquals("local_two", rows.single { it.serverRouterId == "be50" }.routerId)
    }

    @Test fun repeatedSwitchesSendActualHttpRequestsOnlyToTheSelectedChannel() {
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            paths += path
            val id = when (path) {
                "/r/be72/api/router/realtime" -> "be72"
                "/r/be50/api/router/realtime" -> "be50"
                else -> "wrong-route"
            }
            val body = """{"ok":true,"router":"$id","memoryPercent":${if (id == "be72") 34 else 66}}""".toByteArray()
            exchange.sendResponseHeaders(if (id == "wrong-route") 404 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            AppPrefs(context).hubRoot = base
            RouterWorkspaceStore.recordLegacyServerId(context, base, "be72")
            RouterWorkspaceStore.recordServerDefault(context, base, "be72")
            RouterWorkspaceStore.cacheList(context, base, registry())
            AppPrefs(context, "be50").hubRoot = base
            repeat(8) { index ->
                val id = if (index % 2 == 0) "default" else "be50"
                val expected = if (id == "default") "be72" else "be50"
                val oldActivation = RouterWorkspaceStore.activationVersion()
                val oldId = RouterWorkspaceStore.activeWorkspaceId(context)
                RouterWorkspaceStore.activate(context, base, id)
                if (oldId != id) assertFalse(RouterWorkspaceStore.isActive(oldId, oldActivation))
                val sample = runBlocking { LiteRealtimeApi(AppPrefs(context, id)).router() }
                assertEquals(expected, sample.getString("router"))
                assertEquals(if (expected == "be72") 34 else 66, sample.getInt("memoryPercent"))
            }
            assertEquals(8, paths.size)
            assertTrue(paths.none { it.contains("/r/default/") || it == "/api/router/realtime" })
        } finally {
            server.stop(0)
        }
    }
}
