package com.labprobe.app

import org.junit.Assert.assertFalse
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
}
