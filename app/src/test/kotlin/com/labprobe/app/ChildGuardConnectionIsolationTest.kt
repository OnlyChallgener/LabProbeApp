package com.labprobe.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChildGuardConnectionIsolationTest {
    private lateinit var prefs: AppPrefs

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        RouterWorkspaceStore.resetActive()
        context.getSharedPreferences(routerWorkspacePreferencesName("local_child_guard_isolation", ""), Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = AppPrefs(context, "local_child_guard_isolation")
        prefs.hub = "http://original.test:58443"
        setTokenFixture("test-original-token")
    }

    private fun setTokenFixture(token: String) {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            routerWorkspacePreferencesName(prefs.workspaceId, prefs.hubRoot), Context.MODE_PRIVATE,
        ).edit().putString("token", token).commit()
    }

    @Test
    fun delayedCommandPollRejectsAChangedHubBeforeSending() {
        val transport = HubChildGuardTransport(prefs)
        prefs.hub = "http://other.test:58444"

        assertThrows(IllegalStateException::class.java) {
            transport.request("/api/router/child-guard/commands/old-command", "GET", null)
        }
    }

    @Test
    fun oldReplyCannotWriteCacheAfterTokenChanges() {
        val cache = SharedChildGuardCache(prefs)
        prefs.childGuardUsageCacheJson = "{}"
        setTokenFixture("test-other-token")

        assertThrows(IllegalStateException::class.java) {
            cache.write("default|overview|2026-10-01", "{\"router\":\"old\"}")
        }
        assertEquals("{}", prefs.childGuardUsageCacheJson)
    }
}
