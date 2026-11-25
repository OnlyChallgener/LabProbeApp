package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NatDiagnosticModelTest {
    @Test
    fun idleTaskDoesNotFabricateCompletedStages() {
        val items = natTimelineItems(RouterTaskSnapshot.idle("nat"), RouterNatResult())
        assertTrue(items.all { it.state == NatStageState.PENDING })
        assertTrue(items.all { it.timestamp == 0L })
    }

    @Test
    fun queuedTaskMarksOnlyRequestStageCurrent() {
        val task = RouterTaskSnapshot(kind = "nat", state = "queued", stage = "queued", startedAt = 100L)
        val items = natTimelineItems(task, RouterNatResult())
        assertEquals(NatStageState.CURRENT, items[0].state)
        assertEquals(NatStageState.PENDING, items[1].state)
        assertEquals(NatStageState.PENDING, items[2].state)
    }

    @Test
    fun completedTaskUsesRealLogAndResultTimestamps() {
        val task = RouterTaskSnapshot(
            kind = "nat",
            state = "succeeded",
            stage = "finished",
            startedAt = 100L,
            updatedAt = 121L,
            elapsedSeconds = 21L,
            log = listOf("2026-11-12 10:40:07 [NAT 检测] 开始"),
        )
        val result = RouterNatResult(status = "completed", timestamp = 121L, natType = "port-restricted cone")
        val items = natTimelineItems(task, result)
        assertTrue(items.all { it.state == NatStageState.DONE })
        assertEquals(100L, items[0].timestamp)
        assertTrue(items[1].timestamp > 0L)
        assertEquals(121L, items[2].timestamp)
    }

    @Test
    fun submitFailureDoesNotFabricateRequestStage() {
        val task = RouterTaskSnapshot(kind = "nat", state = "failed", stage = "submit_failed")
        val items = natTimelineItems(task, RouterNatResult())
        assertEquals(NatStageState.FAILED, items[0].state)
        assertEquals(NatStageState.PENDING, items[1].state)
        assertEquals(NatStageState.FAILED, items[2].state)
    }

    @Test
    fun failedTaskNeverMarksResultComplete() {
        val task = RouterTaskSnapshot(kind = "nat", state = "failed", stage = "router_running", startedAt = 100L)
        val items = natTimelineItems(task, RouterNatResult())
        assertEquals(NatStageState.FAILED, items[2].state)
        assertTrue(items.none { it.state == NatStageState.DONE && it.title == "结果完成" })
    }
}
