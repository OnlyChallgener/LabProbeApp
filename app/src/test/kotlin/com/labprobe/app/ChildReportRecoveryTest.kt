package com.labprobe.app

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChildReportRecoveryTest {
    @Test fun masterUsesOneConfirmedGlobalCommandEvenWithoutLoadedPlans() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        var enabled = false
        val repo = RealChildInternetRepository("BE72", ChildGuardTransport { path, method, body ->
            calls.add("$method $path")
            when {
                method == "POST" && "/master?" in path -> {
                    assertTrue(body!!.getBoolean("enabled"))
                    throw ChildGuardPendingException("master-command")
                }
                "/command/master-command" in path -> {
                    enabled = true
                    JSONObject().put("ok", true).put("masterEnabled", true)
                }
                else -> JSONObject().put("masterEnabled", enabled).put("devices", JSONArray())
            }
        }, Cache(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), commandPollMs = 1)
        try {
            repo.updateOverviewFromAggregate()
            assertTrue(repo.state.masterKnown)
            assertFalse(repo.state.masterEnabled)
            repo.setMasterEnabled(true)
            withTimeout(2000) { while (!repo.state.masterEnabled || repo.state.pendingDeviceIds.isNotEmpty()) delay(5) }
            assertEquals(1, calls.count { it.startsWith("POST ") })
            assertTrue(calls.any { "master-command" in it })
            assertFalse(calls.any { "/plans" in it })
            assertTrue(repo.state.devices.isEmpty())
        } finally { repo.close() }
    }

    @Test fun missingMasterStateNeverPretendsTheGlobalSwitchIsOff() = runBlocking {
        val repo = RealChildInternetRepository("BE72", ChildGuardTransport { _, _, _ ->
            JSONObject().put("devices", JSONArray())
        }, Cache(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            repo.updateOverviewFromAggregate()
            assertFalse(repo.state.masterKnown)
        } finally { repo.close() }
    }

    @Test fun offlineReportsSettleOnceAndResumeWhenDeviceReconnects() = runBlocking {
        var offlineIsOnline = false
        val reports = mutableMapOf<String, Int>()
        val repo = RealChildInternetRepository("BE72", ChildGuardTransport { path, _, _ ->
            if ("/overview" in path) JSONObject().put("date", childGuardStatisticsDate()).put("devices", JSONArray()
                .put(JSONObject().put("uid", "live").put("online", true))
                .put(JSONObject().put("uid", "quiet").put("online", offlineIsOnline)))
            else {
                val uid = path.substringAfter("/devices/").substringBefore('/')
                val count = (reports[uid] ?: 0) + 1
                reports[uid] = count
                payload(childGuardStatisticsDate(), count)
            }
        }, Cache(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            repeat(3) { repo.updateOverviewFromAggregate() }
            assertEquals(3, reports["live"])
            assertEquals(1, reports["quiet"])
            assertEquals(1, repo.state.devices.first { it.summary.deviceId == "quiet" }.usage?.onlineMinutes)
            offlineIsOnline = true
            repo.updateOverviewFromAggregate()
            assertEquals(2, reports["quiet"])
            offlineIsOnline = false
            repeat(2) { repo.updateOverviewFromAggregate() }
            assertEquals(3, reports["quiet"])
            assertEquals(6, reports["live"])
        } finally { repo.close() }
    }

    @Test fun offlineFailureDoesNotRetryEveryPollOrPretendUsageIsZero() = runBlocking {
        var reads = 0
        val repo = RealChildInternetRepository("BE72", ChildGuardTransport { path, _, _ ->
            if ("/overview" in path) JSONObject().put("date", childGuardStatisticsDate()).put("devices", JSONArray()
                .put(JSONObject().put("uid", "quiet").put("online", false)))
            else { reads++; throw java.net.SocketTimeoutException("timeout") }
        }, Cache(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            repeat(3) { repo.updateOverviewFromAggregate() }
            assertEquals(1, reads)
            val device = repo.state.devices.single()
            assertNull(device.usage?.onlineMinutes)
            assertEquals("今日暂无统计", childGuardTodayLine(device))
            assertFalse(childGuardTodayLine(device).contains("同步中"))
            assertEquals("设备离线 · 暂无统计记录", childReportFreshnessLabel(device))
        } finally { repo.close() }
    }

    @Test fun quietReportDoesNotClaimItIsStillWaitingForFirstSync() {
        val state = newChildGuardDevice("uid", "手机", "phone").copy(
            usage = ChildUsageStats(date = childGuardStatisticsDate(), hasData = false, stale = false))
        assertEquals("当日暂无上网记录", childReportFreshnessLabel(state))
        assertTrue(childReportFreshnessLabel(state.copy(usage = null)).contains("首次同步"))
        assertFalse(childReportFreshnessLabel(state.copy(usageError = "读取失败")).contains("暂无上网记录"))
    }
    @Test fun reportTransportTimeoutDoesNotClaimRouterLoginFailed() {
        val message = java.net.SocketTimeoutException("timeout").reportReadMessage()
        assertTrue(message.contains("报告"))
        assertTrue(message.contains("Hub"))
        assertFalse(message.contains("路由器响应超时"))
    }
    private class Cache : ChildGuardCache {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) { values[key] = value }
        override fun keys() = values.keys.toList()
    }
    private fun payload(date: String, minutes: Int = 72) = JSONObject()
        .put("date", date).put("timezone", "Asia/Shanghai").put("hasData", true)
        .put("onlineMinutes", minutes).put("range", JSONObject().put("days", JSONArray()
            .put(JSONObject().put("date", date).put("onlineMinutes", minutes))))

    @Test fun newerMembershipUidReplacesOldUidWhileSameNameOtherMacRemainsSeparate() = runBlocking {
        var rows = JSONArray().put(JSONObject().put("uid", "old-uid").put("name", "Mate60")
            .put("macs", JSONArray().put("aa:bb:cc:dd:ee:01")))
        val repo = RealChildInternetRepository("BE72", ChildGuardTransport { path, _, _ ->
            if ("/overview" in path) JSONObject().put("date", childGuardStatisticsDate()).put("devices", rows)
            else payload(childGuardStatisticsDate(), 12)
        }, Cache(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            repo.updateOverviewFromAggregate()
            rows = JSONArray().put(JSONObject().put("uid", "new-uid").put("name", "Mate60")
                .put("macs", JSONArray().put("aa:bb:cc:dd:ee:01")))
                .put(JSONObject().put("uid", "other-uid").put("name", "Mate60")
                    .put("macs", JSONArray().put("aa:bb:cc:dd:ee:02")))
            repo.updateOverviewFromAggregate()
            assertEquals(setOf("new-uid", "other-uid"), repo.state.devices.map { it.summary.deviceId }.toSet())
            assertEquals(2, repo.state.devices.size)
        } finally { repo.close() }
    }

    @Test fun oldLastDayIsNeverTodayAndDoesNotFillTodayCards() {
        val report = parseChildGuardUsageReport(payload("2026-01-01"), now = Instant.parse("2026-10-04T15:00:00Z"))
        val state = newChildGuardDevice("uid", "电脑", "desktop").withUsage(report)
        assertNotEquals("今天", report.recent.bars.last().label)
        assertNull(state.todayUsage.totalMinutes)
        assertNull(state.usage?.onlineMinutes)
        assertFalse(state.usageHasData)
        assertEquals("2026-01-01", state.usageDate)
    }

    @Test fun todayUsesPayloadTimeZoneAtMidnight() {
        val report = parseChildGuardUsageReport(payload("2026-10-05"), now = Instant.parse("2026-10-04T16:00:00Z"))
        assertEquals("今天", report.recent.bars.single().label)
        assertEquals(72, newChildGuardDevice("uid", "电脑", "desktop").withUsage(report).todayUsage.totalMinutes)
    }

    @Test fun repeatedReportsRefreshWithoutRequestingRouterPlans() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        var minutes = 1
        val job = SupervisorJob()
        val repo = RealChildInternetRepository("BE50", ChildGuardTransport { path, _, _ ->
            calls.add(path)
            if ("/overview" in path) return@ChildGuardTransport JSONObject().put("date", childGuardStatisticsDate())
                .put("devices", JSONArray().put(JSONObject().put("uid", "uid").put("name", "电脑")))
            check("usage-report" in path) { "Report must not call router plans or runtime" }
            payload(childGuardStatisticsDate(), minutes)
        }, Cache(), CoroutineScope(job + Dispatchers.Unconfined))
        try {
            repo.updateOverviewFromAggregate()
            calls.clear()
            suspend fun load() { val done = CompletableDeferred<Result<Unit>>(); repo.loadUsageReport("uid") { done.complete(it) }; withTimeout(2000) { done.await().getOrThrow() } }
            load()
            assertEquals(1, repo.state.devices.single().todayUsage.totalMinutes)
            minutes = 2
            load()
            assertEquals(2, repo.state.devices.single().todayUsage.totalMinutes)
            assertEquals(2, calls.size)
        } finally { repo.close() }
    }

    @Test fun pendingAddStopsWaitingAndRetryUsesSameCommand() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val job = SupervisorJob()
        val repo = RealChildInternetRepository("BE50", ChildGuardTransport { path, method, _ ->
            calls.add("$method $path")
            when {
                method == "POST" || "/command/" in path -> JSONObject().put("ok", true).put("pending", true).put("commandId", "same-command")
                else -> JSONObject().put("ok", true).put("date", childGuardStatisticsDate()).put("devices", JSONArray())
            }
        }, Cache(), CoroutineScope(job + Dispatchers.Unconfined), operationTimeoutMs = 180, commandPollMs = 20)
        try {
            repeat(2) {
                val done = CompletableDeferred<Result<Unit>>()
                repo.addGuardDevice("aa:bb:cc:dd:ee:ff", "测试手机") { done.complete(it) }
                assertTrue(withTimeout(2000) { done.await() }.isFailure)
                assertTrue(repo.state.pendingDeviceIds.isEmpty())
            }
            assertEquals(1, calls.count { it.startsWith("POST ") })
            assertTrue(calls.any { "same-command" in it })
        } finally { repo.close() }
    }

    @Test fun acknowledgedAddReadsActualMembershipWithoutPlanFanout() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val rows = JSONArray().put(JSONObject().put("uid", "new-uid").put("macs", JSONArray().put("aa:bb:cc:dd:ee:ff")))
            .put(JSONObject().put("uid", "existing-uid").put("macs", JSONArray().put("11:22:33:44:55:66")))
        val repo = RealChildInternetRepository("BE50", ChildGuardTransport { path, method, _ ->
            calls.add("$method $path")
            when {
                method == "POST" -> JSONObject().put("ok", true).put("uid", "new-uid")
                "usage-report" in path -> payload(childGuardStatisticsDate())
                else -> JSONObject().put("ok", true).put("date", childGuardStatisticsDate()).put("devices", rows)
            }
        }, Cache(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            val done = CompletableDeferred<Result<Unit>>()
            repo.addGuardDevice("aa:bb:cc:dd:ee:ff", "测试手机") { done.complete(it) }
            withTimeout(2000) { done.await().getOrThrow(); while (repo.state.devices.size != 2) delay(10) }
            assertTrue(calls.any { it.startsWith("GET /api/router/child-guard/devices?") })
            assertFalse(calls.any { "/plans" in it || "/runtime" in it })
        } finally { repo.close() }
    }
}
