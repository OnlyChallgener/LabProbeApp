package com.labprobe.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PortMappingRuleStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("labprobe_port_mapping_rules_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun persistedMappingDocumentBelongsToItsHubAndToken() {
        val hub = "http://hub-a:58443"
        PortMappingRuleStore.save(context, hub, "token-a", emptyList(), 27L, "saved")

        assertFalse(PortMappingRuleStore.load(context, hub, "token-b").hasDocument)
        assertTrue(PortMappingRuleStore.load(context, "$hub/", "token-a").hasDocument)
        assertEquals(27L, PortMappingRuleStore.load(context, hub, "token-a").revision)
        assertFalse(PortMappingRuleStore.load(context, "http://hub-b:58444", "token-a").hasDocument)
    }
}
