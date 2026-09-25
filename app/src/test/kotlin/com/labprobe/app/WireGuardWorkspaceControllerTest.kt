package com.labprobe.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WireGuardWorkspaceControllerTest {
    @Test
    fun stoppingOneWorkspaceCannotClearOtherRoutersProfile() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit()
            .clear().putString("hub", "http://192.168.5.46:58443").commit()
        val home = AppPrefs(context)
        val office = AppPrefs(context, "office")
        home.wireGuardActiveProfileId = "home-profile"
        office.wireGuardActiveProfileId = "office-profile"

        val controller = WireGuardTunnelController.get(context)
        assertSame(controller, WireGuardTunnelController.get(context))
        val officeStatus = controller.stop(office)

        assertFalse(officeStatus.running)
        assertEquals("home-profile", home.wireGuardActiveProfileId)
        assertEquals("", office.wireGuardActiveProfileId)
    }
}
