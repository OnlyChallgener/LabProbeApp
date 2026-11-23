package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LuckySnapshotStoreTest {
    @Test fun restoresSelectedInstanceWithoutPersistingCredentialsAndKeepsInstancesSeparate() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("labprobe_lucky_snapshots", 0).edit().clear().commit()
        val store = LuckySnapshotStore(context, "http://hub-a/r/be72", "APP-TOKEN-PRIVATE")
        store.saveConnections(listOf(JSONObject().put("id", "a").put("name", "NAS").put("url", "http://nas/be72").put("token", "OPEN-TOKEN-PRIVATE"), JSONObject().put("id", "b")))
        val rule = JSONObject().put("id", "rule-a").put("name", "NAS").put("OpenToken", "SECRET").put("routes", JSONArray().put(JSONObject().put("domains", JSONArray().put("nas.example.com")).put("backends", JSONArray().put("http://192.168.1.2:80")).put("OtherParams", JSONObject().put("password", "SECRET"))))
        store.saveInstance("a", LuckyInstanceSnapshot(listOf(rule), listOf(JSONObject().put("id", "c").put("KeyBase64", "PRIVATE-KEY")), listOf(JSONObject().put("id", "d").put("accessSecret", "DNS-SECRET"))))
        store.saveInstance("b", LuckyInstanceSnapshot(listOf(JSONObject().put("id", "rule-b"))))
        store.select("a", 1)
        val restored = LuckySnapshotStore(context, "http://hub-a/r/be72", "APP-TOKEN-PRIVATE")
        assertEquals("a", restored.selectedId); assertEquals(1, restored.selectedTab)
        assertEquals("rule-a", restored.instance("a")!!.rules.single().getString("id"))
        assertEquals("rule-b", restored.instance("b")!!.rules.single().getString("id"))
        val disk = context.getSharedPreferences("labprobe_lucky_snapshots", 0).all.toString()
        listOf("APP-TOKEN-PRIVATE", "OPEN-TOKEN-PRIVATE", "PRIVATE-KEY", "DNS-SECRET", "OtherParams", "SECRET").forEach { assertFalse(it, disk.contains(it)) }
        assertTrue(LuckySnapshotStore(context, "http://hub-b/r/be72", "APP-TOKEN-PRIVATE").connections().isEmpty())
        assertTrue(LuckySnapshotStore(context, "http://hub-a/r/be72", "other-token").connections().isEmpty())
        restored.saveConnections(listOf(JSONObject().put("id", "a")))
        assertNull(restored.instance("b"))
    }
}
