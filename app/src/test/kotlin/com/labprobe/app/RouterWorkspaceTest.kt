package com.labprobe.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouterWorkspaceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        listOf("north", "south", "default").forEach { id ->
            listOf("http://192.168.5.46:58443", "http://other.example:58443").forEach { hub ->
                context.getSharedPreferences(routerWorkspacePreferencesName(id, hub), Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }
        RouterWorkspaceStore.resetActive()
    }

    @Test
    fun defaultKeepsLegacyDataAndOtherRoutersCannotReadIt() {
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit()
            .putString("hub", "http://192.168.5.46:58443")
            .putString("cache_status", """{"router":"old"}""")
            .putString("cache_devices", """[{"mac":"AA:BB:CC:DD:EE:FF"}]""")
            .putString("router_display_name_v1", "旧锐捷")
            .commit()
        val legacy = AppPrefs(context)

        val north = AppPrefs(context, "north")
        val south = AppPrefs(context, "south")
        assertEquals(legacy.cacheStatus, AppPrefs(context).cacheStatus)
        assertEquals("", north.cacheStatus)
        assertEquals("", south.cacheDevices)
        north.cacheStatus = """{"router":"north"}"""
        north.cacheDevices = """[{"mac":"AA:BB:CC:DD:EE:FF"}]"""
        north.routerDisplayName = "北边锐捷"
        south.cacheStatus = """{"router":"south"}"""
        assertEquals("""{"router":"old"}""", legacy.cacheStatus)
        assertEquals("""{"router":"north"}""", north.cacheStatus)
        assertEquals("""{"router":"south"}""", south.cacheStatus)
        assertEquals("旧锐捷", legacy.routerDisplayName)
        assertEquals("", south.routerDisplayName)
        assertEquals("http://192.168.5.46:58443/r/north", north.hub)
        assertEquals("http://192.168.5.46:58443/r/south", south.hub)
        assertEquals(legacy.hubRoot, north.hubRoot)
    }

    @Test
    fun sameRouterIdOnDifferentHubsHasSeparateSnapshots() {
        val first = AppPrefs(context, "north")
        first.hubRoot = "http://192.168.5.46:58443"
        first.cacheStatus = """{"hub":"first"}"""
        first.wireGuardProfilesJson = """[{"id":"one"}]"""
        first.hubRoot = "http://other.example:58443"
        assertEquals("", first.cacheStatus)
        assertEquals("[]", first.wireGuardProfilesJson)
        first.cacheStatus = """{"hub":"second"}"""
        first.hubRoot = "http://192.168.5.46:58443"
        assertEquals("""{"hub":"first"}""", first.cacheStatus)
        assertEquals("""[{"id":"one"}]""", first.wireGuardProfilesJson)
    }

    @Test
    fun selectionRequiresConfirmedHubListAndValidMember() {
        val rows = parseRouterWorkspaces(
            JSONObject(
                """{"routers":[
                    {"routerId":"default","name":"主路由","online":true,"basePath":""},
                    {"routerId":"north","name":"北边","site":"家","model":"锐捷","online":true,"basePath":"/r/north","deviceCount":8}
                ]}"""
            )
        )
        assertEquals(listOf("default", "north"), rows.map { it.routerId })
        assertEquals(8, rows.last().deviceCount)
        assertEquals("default", selectableWorkspaceId(rows, "north", listingConfirmed = false))
        assertEquals("north", selectableWorkspaceId(rows, "north", listingConfirmed = true))
        assertEquals("default", selectableWorkspaceId(rows, "unlisted", listingConfirmed = true))
    }

    @Test
    fun mapsHubDefaultRouterIdToOneLegacyWorkspaceCard() {
        val rows = parseRouterWorkspaces(
            JSONObject(
                """{"defaultRouterId":"home-reyee","routers":[
                    {"routerId":"home-reyee","name":"家里锐捷","site":"家","model":"锐捷","online":true,"basePath":"","deviceCount":7},
                    {"routerId":"office","name":"办公室锐捷","site":"公司","online":false,"basePath":"/r/office","deviceCount":3}
                ]}"""
            )
        )
        assertEquals(listOf("default", "office"), rows.map { it.routerId })
        assertEquals("家里锐捷", rows.first().name)
        assertEquals("家", rows.first().site)
        assertEquals(7, rows.first().deviceCount)
        assertEquals("", rows.first().basePath)
        assertEquals("http://hub.example:58443", workspaceHubUrl("http://hub.example:58443", rows.first().routerId))
        assertEquals("http://hub.example:58443/r/office", workspaceHubUrl("http://hub.example:58443", rows.last().routerId))
    }

    @Test
    fun acceptsDefaultRouterListedWithItsCanonicalPathButStillRoutesToRoot() {
        val rows = parseRouterWorkspaces(
            JSONObject(
                """{"defaultRouterId":"home-reyee","routers":[
                    {"routerId":"home-reyee","name":"家里锐捷","basePath":"/r/home-reyee"},
                    {"routerId":"office","name":"办公室锐捷","basePath":"/r/office"}
                ]}"""
            )
        )
        assertEquals(listOf("default", "office"), rows.map { it.routerId })
        assertEquals("家里锐捷", rows.first().name)
        assertEquals("", rows.first().basePath)
    }

    @Test
    fun rejectsMaliciousOrMismatchedBasePaths() {
        val rows = parseRouterWorkspaces(
            JSONObject(
                """{"routers":[
                    {"routerId":"../bad","basePath":"/r/../bad"},
                    {"routerId":"north","basePath":"https://evil.example/"},
                    {"routerId":"south","basePath":"/r/south"}
                ]}"""
            )
        )
        assertEquals(listOf("default", "south"), rows.map { it.routerId })
        assertFalse(validRouterWorkspaceId("../bad"))
        assertTrue(validRouterWorkspaceId("south"))
    }

    @Test
    fun singleRouterHubListFillsBothCountsAndNeverInventsZero() {
        val rows = parseRouterWorkspaces(
            JSONObject(
                """{"defaultRouterId":"default","multiRouter":false,"routers":[
                    {"routerId":"default","name":"客厅锐捷","site":"","model":"RG-EG310MG-P",
                     "online":true,"basePath":"","deviceCount":24,"onlineDeviceCount":9}
                ]}"""
            )
        )
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("default", row.routerId)
        assertEquals("客厅锐捷", row.name)
        assertEquals("RG-EG310MG-P", row.model)
        assertEquals(24, row.deviceCount)
        assertEquals(9, row.onlineDeviceCount)
        assertEquals("路由器在线 · 在线 9 / 共 24 台", routerWorkspaceCountsLine(row))
        // 缺字段 = 还不知道；0 = 真的没有设备。两者绝不能长得一样。
        assertEquals("路由器在线 · 设备数待同步", routerWorkspaceCountsLine(row.copy(deviceCount = null, onlineDeviceCount = null)))
        assertEquals("路由器在线 · 在线 0 / 共 0 台", routerWorkspaceCountsLine(row.copy(onlineDeviceCount = 0, deviceCount = 0)))
        assertEquals("路由器在线 · 在线 5 台", routerWorkspaceCountsLine(row.copy(deviceCount = null, onlineDeviceCount = 5)))
        assertEquals("路由器在线 · 共 5 台", routerWorkspaceCountsLine(row.copy(deviceCount = 5, onlineDeviceCount = null)))
        assertEquals("路由器离线 · 在线 5 台", routerWorkspaceCountsLine(row.copy(online = false, deviceCount = null, onlineDeviceCount = 5)))
    }

    @Test
    fun localRouterNameOverridesTheHubSuppliedOne() {
        assertEquals("默认路由器", defaultRouterWorkspace().withLocalRouterName(context).name)
        AppPrefs(context, DEFAULT_ROUTER_WORKSPACE_ID).routerDisplayName = "客厅那台"
        assertEquals("客厅那台", defaultRouterWorkspace().withLocalRouterName(context).name)
        val row = RouterWorkspace("north", "Ruijie BE72", "", "BE72-PRO", true, "/r/north", deviceCount = 25, onlineDeviceCount = 10)
        assertEquals("Ruijie BE72", row.withLocalRouterName(context).name)
        AppPrefs(context, "north").routerDisplayName = "北边锐捷"
        val renamed = row.withLocalRouterName(context)
        assertEquals("北边锐捷", renamed.name)
        // 只换名字，在线/台数这些真数据必须原样留着。
        assertEquals(row.copy(name = "北边锐捷"), renamed)
    }

    @Test
    fun forgettingANonDefaultRouterClearsItsPrefsAndTheSavedSelection() {
        val hub = "http://192.168.5.46:58443"
        val north = AppPrefs(context, "north")
        north.hubRoot = hub
        north.routerDisplayName = "北边锐捷"
        north.cacheStatus = """{"router":"north"}"""
        RouterWorkspaceStore.activate(context, hub, "north")
        assertEquals("north", RouterWorkspaceStore.remembered(context, hub))

        forgetRouterWorkspace(context, hub, "north")

        assertEquals("", AppPrefs(context, "north").routerDisplayName)
        assertEquals("", AppPrefs(context, "north").cacheStatus)
        assertEquals(DEFAULT_ROUTER_WORKSPACE_ID, RouterWorkspaceStore.remembered(context, hub))
    }

    @Test
    fun forgettingTheDefaultRouterIsRefused() {
        // 默认工作区的偏好和全局设置同一个文件，删它等于清空整个 App。
        assertThrows(IllegalArgumentException::class.java) {
            forgetRouterWorkspace(context, "http://192.168.5.46:58443", DEFAULT_ROUTER_WORKSPACE_ID)
        }
    }

    @Test
    fun countsLineKeepsUnknownApartFromZero() {
        assertEquals("路由器在线 · 设备数待同步", routerWorkspaceCountsLine(defaultRouterWorkspace().copy(online = true)))
        assertEquals("路由器在线 · 在线 0 / 共 0 台", routerWorkspaceCountsLine(
            defaultRouterWorkspace().copy(online = true, deviceCount = 0, onlineDeviceCount = 0)))
    }

    @Test
    fun activePrefsFollowConfirmedSelectionAndResetSafely() {
        assertEquals("default", AppPrefs.current(context).workspaceId)
        RouterWorkspaceStore.activate(context, "http://hub.example:58443", "north")
        assertEquals("north", AppPrefs.current(context).workspaceId)
        assertEquals("north", RouterWorkspaceStore.remembered(context, "http://hub.example:58443"))
        RouterWorkspaceStore.hubConnectionChanged()
        assertEquals("default", AppPrefs.current(context).workspaceId)
        assertEquals("north", RouterWorkspaceStore.remembered(context, "http://hub.example:58443"))
        RouterWorkspaceStore.activate(context, "http://hub.example:58443", "north")
        RouterWorkspaceStore.resetActive()
        assertEquals("default", AppPrefs.current(context).workspaceId)
        assertEquals("north", RouterWorkspaceStore.remembered(context, "http://hub.example:58443"))
    }
}
