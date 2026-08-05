package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUpdateCoordinatorTest {
    @Test
    fun installedVersionMatchesLatest() {
        assertTrue(agentVersionAtLeast("0.2.15", "0.2.15"))
    }

    @Test
    fun installedVersionCanBeNewerThanManifest() {
        assertTrue(agentVersionAtLeast("v0.2.16", "0.2.15"))
    }

    @Test
    fun staleInstalledVersionStillRequiresUpdate() {
        assertFalse(agentVersionAtLeast("0.2.14", "0.2.15"))
    }

    @Test
    fun unknownVersionNeverCountsAsCurrent() {
        assertFalse(agentVersionAtLeast("未知", "0.2.15"))
        assertFalse(agentVersionAtLeast("0.2.15", "未知"))
    }

    @Test
    fun reportedCurrentVersionOverridesStaleFailedTaskState() {
        val completed = completedAgentUpdate(
            AgentUpdateInfo(
                currentVersion = "0.2.15",
                latestVersion = "0.2.15",
                updateAvailable = true,
                state = "failed",
                message = "旧任务状态残留",
                lastSeenAt = "2026-08-05 12:00:00",
            ),
        )

        assertNotNull(completed)
        assertEquals("completed", completed?.state)
        assertFalse(completed?.updateAvailable ?: true)
        assertEquals("Relay 已更新到 0.2.15", completed?.message)
    }

    @Test
    fun staleVersionDoesNotOverrideFailedTaskState() {
        val completed = completedAgentUpdate(
            AgentUpdateInfo(
                currentVersion = "0.2.14",
                latestVersion = "0.2.15",
                updateAvailable = true,
                state = "failed",
                message = "真实更新失败",
                lastSeenAt = "2026-08-05 12:00:00",
            ),
        )

        assertNull(completed)
    }

    @Test
    fun dispatchTimeoutDoesNotClaimCommandWasAccepted() {
        assertEquals(
            "更新请求超时，尚未确认 Hub 已接收指令",
            agentUpdateErrorMessage(
                raw = "timeout",
                update = true,
                commandAccepted = false,
            ),
        )
    }

    @Test
    fun pollingTimeoutKeepsAcceptedCommandMessage() {
        assertEquals(
            "更新指令已下发，等待 Relay 重新上报",
            agentUpdateErrorMessage(
                raw = "timed out",
                update = true,
                commandAccepted = true,
            ),
        )
    }
}
