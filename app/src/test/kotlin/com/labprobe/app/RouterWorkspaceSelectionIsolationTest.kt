package com.labprobe.app

import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RouterWorkspaceSelectionIsolationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val listHub = "http://192.168.5.46:58443"
    private val be50Hub = "http://100.66.1.4:58444"
    private lateinit var be50: RouterWorkspace
    private lateinit var be50Prefs: AppPrefs

    @Before
    fun setup() {
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        RouterWorkspaceStore.resetActive()
        AppPrefs(context).hubRoot = listHub
        be50 = LocalRouterWorkspaceRegistry.add(context, listHub, "BE50")
        be50Prefs = AppPrefs(context, be50.routerId)
        be50Prefs.hubRoot = be50Hub
        be50Prefs.hub = be50Hub
        be50Prefs.routerDisplayName = "BE50"
        be50Prefs.routerFirmwareFamily = "BE50"
    }

    private fun be72Listing(): JSONObject = JSONObject(
        """{"defaultRouterId":"BE72","routers":[
            {"routerId":"BE72","name":"BE72","model":"BE72-PRO","basePath":"","online":true}
        ]}""",
    )

    private fun selectBe50() = RouterWorkspaceStore.activate(context, listHub, be50.routerId)

    private fun applyBackgroundListing(root: JSONObject): List<RouterWorkspace> {
        RouterWorkspaceStore.recordServerDefault(context, listHub, root.getString("defaultRouterId"))
        RouterWorkspaceStore.cacheList(context, listHub, root)
        val selectedId = RouterWorkspaceStore.activeWorkspaceId(context)
        return preserveSelectedRouterWorkspace(
            loadVisibleRouterWorkspaces(context, listHub, RouterWorkspaceStore.cachedList(context, listHub)),
            selectedId, be50,
        )
    }

    @Test
    fun delayedBe72ListingCannotReplaceBe50ChosenAfterRequestStarted() = runBlocking {
        val reply = CompletableDeferred<JSONObject>()
        val pendingList = async(start = CoroutineStart.UNDISPATCHED) {
            applyBackgroundListing(reply.await())
        }
        selectBe50()
        val activation = RouterWorkspaceStore.activationVersion()
        reply.complete(be72Listing())
        val rows = pendingList.await()

        assertTrue(rows.first { it.isDefault }.name.contains("BE72"))
        assertTrue(rows.any { it.routerId == be50.routerId })
        assertEquals(be50.routerId, RouterWorkspaceStore.activeWorkspaceId(context))
        assertEquals(be50.routerId, RouterWorkspaceStore.remembered(context, listHub))
        assertTrue(RouterWorkspaceStore.isActive(be50.routerId, activation))
        assertEquals(be50Hub, AppPrefs.current(context).hub)
    }

    @Test
    fun connectionChangeInvalidatesOldTasksAndRetainsSelectedCard() {
        selectBe50()
        val previousActivation = RouterWorkspaceStore.activationVersion()
        RouterWorkspaceStore.hubConnectionChanged()
        val rows = applyBackgroundListing(be72Listing())

        assertFalse(RouterWorkspaceStore.isActive(be50.routerId, previousActivation))
        assertEquals(be50.routerId, RouterWorkspaceStore.activeWorkspaceId(context))
        assertEquals(be50.routerId, AppPrefs.current(context).workspaceId)
        assertTrue(rows.any { it.routerId == be50.routerId })
        assertEquals(be50Hub, AppPrefs.current(context).hub)
    }

    @Test
    fun appRestartRestoresIndependentCardEvenWhenListHubChanges() {
        selectBe50()
        RouterWorkspaceStore.resetActive()
        val otherListHub = "http://other-list.test:58443"
        AppPrefs(context).hubRoot = otherListHub
        val rows = loadVisibleRouterWorkspaces(context, otherListHub, listOf(defaultRouterWorkspace()))

        assertEquals(be50.routerId, RouterWorkspaceStore.restoreActive(context, otherListHub, rows))
        assertEquals(be50Hub, AppPrefs.current(context).hub)
    }

    @Test
    fun cacheUpdatesAndMissingMetadataKeepBe50SelectionAndIdentity() {
        selectBe50()
        val activation = RouterWorkspaceStore.activationVersion()
        be50Prefs.cacheDevices = """[{"mac":"AA:BB:CC:DD:EE:01","name":"BE50 terminal","online":true}]"""
        be50Prefs.cacheOnlineDevices = be50Prefs.cacheDevices
        AppPrefs(context).cacheDevices = "[]"
        AppPrefs(context).cacheOnlineDevices = "[]"
        val counts = workspaceDeviceCounts(context, be50.routerId)
        val rows = preserveSelectedRouterWorkspace(
            listOf(defaultRouterWorkspace().copy(name = "BE72", online = false)), be50.routerId, be50,
        )

        assertEquals(1, counts?.onlineDeviceCount)
        assertEquals(be50, rows.first { it.routerId == be50.routerId })
        assertEquals(be50.routerId, RouterWorkspaceStore.activeWorkspaceId(context))
        assertTrue(RouterWorkspaceStore.isActive(be50.routerId, activation))
        assertEquals(be50Hub, AppPrefs.current(context).hub)
    }

    @Test
    fun deletingAnotherCardDoesNotSelectDefaultOrForgetBe50() {
        val other = LocalRouterWorkspaceRegistry.add(context, listHub, "另一台BE72")
        selectBe50()
        forgetRouterWorkspace(context, listHub, other.routerId)
        val remaining = loadVisibleRouterWorkspaces(context, listHub, listOf(defaultRouterWorkspace()))

        assertEquals(be50.routerId, workspaceSelectionAfterDeletion(be50.routerId, other.routerId, remaining))
        assertEquals(be50.routerId, RouterWorkspaceStore.remembered(context, listHub))
        assertEquals(be50.routerId, RouterWorkspaceStore.activeWorkspaceId(context))
    }

    @Test
    fun staleListingClientCannotRequestOrCacheUsingChangedListHub() {
        selectBe50()
        val rootPrefs = AppPrefs(context)
        val api = RouterWorkspaceApi(context, rootPrefs)
        rootPrefs.hubRoot = "http://new-list.test:58443"

        assertThrows(IllegalStateException::class.java) {
            runBlocking { api.list() }
        }
        assertEquals(be50.routerId, RouterWorkspaceStore.activeWorkspaceId(context))
        assertEquals(be50Hub, AppPrefs.current(context).hub)
    }

    @Test
    fun independentDefaultIsSavedLocallyWithoutWritingToBe72HubOrChangingActiveCard() = runBlocking {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                val body = "{\"ok\":true,\"defaultRouterId\":\"BE50\"}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val rootPrefs = AppPrefs(context)
            rootPrefs.hubRoot = "http://127.0.0.1:${server.address.port}"
            RouterWorkspaceStore.activate(context, rootPrefs.hubRoot, DEFAULT_ROUTER_WORKSPACE_ID)
            val activation = RouterWorkspaceStore.activationVersion()
            be50Prefs.routerHubId = "BE50"

            assertFalse(setRouterWorkspaceStartupDefault(context, rootPrefs, be50))

            assertEquals(0, requests.get())
            assertEquals(DEFAULT_ROUTER_WORKSPACE_ID, RouterWorkspaceStore.activeWorkspaceId(context))
            assertTrue(RouterWorkspaceStore.isActive(DEFAULT_ROUTER_WORKSPACE_ID, activation))
            assertEquals(be50.routerId, RouterWorkspaceStore.remembered(context, rootPrefs.hubRoot))
        } finally {
            server.stop(0)
        }
    }
}
