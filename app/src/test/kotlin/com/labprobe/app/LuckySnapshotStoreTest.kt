package com.labprobe.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    @Test fun granularSnapshotsKeepResourceTimestampsAndListenerFields() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("labprobe_lucky_snapshots", 0).edit().clear().commit()
        val store = LuckySnapshotStore(context, "http://hub/r/be72", "token")
        store.saveInstance("a", LuckyInstanceSnapshot(
            rules = emptyList(),
            certificates = listOf(JSONObject().put("id", "cert")),
            accounts = listOf(JSONObject().put("id", "account")),
            certificatesAt = 1234L,
            accountsAt = 5678L,
        ))
        store.saveRules("a", listOf(JSONObject().put("id", "rule").put("network", "tcp6").put("listenIp", "::")))
        val restored = LuckySnapshotStore(context, "http://hub/r/be72", "token").instance("a")!!
        assertEquals("tcp6", restored.rules.single().getString("network"))
        assertEquals("::", restored.rules.single().getString("listenIp"))
        assertEquals(1234L, restored.certificatesAt)
        assertEquals(5678L, restored.accountsAt)
        assertTrue(restored.rulesAt > 0L)
    }

    @Test fun refreshCoordinatorCoalescesAndDropsStaleResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = LuckyRefreshCoordinator(scope)
        val first = CompletableDeferred<Int>()
        val second = CompletableDeferred<Int>()
        var loads = 0
        val applied = mutableListOf<Int>()
        try {
            assertTrue(coordinator.submit("rules", { loads++; first.await() }, { applied += it }, {}, {}))
            assertFalse(coordinator.submit("rules", { loads++; second.await() }, { applied += it }, {}, {}))
            assertEquals(1, loads)
            coordinator.invalidate()
            assertTrue(coordinator.submit("rules", { loads++; second.await() }, { applied += it }, {}, {}))
            second.complete(2)
            yield()
            first.complete(1)
            yield()
            assertEquals(listOf(2), applied)
        } finally {
            scope.cancel()
        }
    }

    @Test fun postWriteRefreshReplacesAnActiveResourceRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = LuckyRefreshCoordinator(scope)
        val first = CompletableDeferred<Int>()
        val second = CompletableDeferred<Int>()
        val applied = mutableListOf<Int>()
        try {
            assertTrue(coordinator.submit("rules", { first.await() }, { applied += it }, {}, {}))
            assertTrue(coordinator.submit("rules", { second.await() }, { applied += it }, {}, {}, replace = true))
            second.complete(2)
            yield()
            first.complete(1)
            yield()
            assertEquals(listOf(2), applied)
        } finally {
            scope.cancel()
        }
    }
}
