package com.labprobe.app.feature.assistant

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiNotificationCursorTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetStore() {
        context.getSharedPreferences("labprobe_ai", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun cursorIsMonotonicAcrossStoreInstancesAndHubScoped() {
        val first = AiSettingsStore(context)
        assertTrue(first.saveLastNotificationId("hub-a#test-key", 12))
        assertTrue(AiSettingsStore(context).saveLastNotificationId("hub-a#test-key", 7))
        assertEquals(12, first.lastNotificationId("hub-a#test-key"))
        assertEquals(0, first.lastNotificationId("hub-b#test-key"))
        val keys = context.getSharedPreferences("labprobe_ai", Context.MODE_PRIVATE).all.keys
        assertTrue(keys.none { it.contains("test-key") || it.contains("hub-a") })
    }

    @Test fun legacyCursorIsPreservedDuringMigration() {
        val identity = "legacy-hub#test-key"
        context.getSharedPreferences("labprobe_ai", Context.MODE_PRIVATE).edit()
            .putInt("last_notification_id_${identity.hashCode()}", 20).commit()
        val store = AiSettingsStore(context)
        assertEquals(20, store.lastNotificationId(identity))
        assertTrue(store.saveLastNotificationId(identity, 3))
        assertEquals(20, AiSettingsStore(context).lastNotificationId(identity))
    }
}
