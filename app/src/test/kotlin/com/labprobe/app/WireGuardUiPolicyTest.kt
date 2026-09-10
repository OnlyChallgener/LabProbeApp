package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardUiPolicyTest {
    private fun rule(
        id: String,
        enabled: Boolean = true,
        targetType: String = "router_self",
        targetIpv4: String = "127.0.0.1",
        targetPort: Int = 51820,
        protocol: String = "UDP",
        serviceType: String = "WireGuard",
    ) = StunRule(
        id = id,
        name = id,
        enabled = enabled,
        listenPort = 0,
        targetType = targetType,
        targetIpv4 = targetIpv4,
        targetPort = targetPort,
        serviceType = serviceType,
        transportProtocol = protocol,
        forwardMode = "router_native",
        actualState = "mapped",
        firewallState = "ready",
        nativeMappingState = "ready",
        runtime = StunRuntime(),
    )

    @Test
    fun onlyWireGuardTargetsBelongToWireGuardStatusSurface() {
        assertTrue(isWireGuardOperationTarget("wireguard:gateway"))
        assertTrue(isWireGuardOperationTarget("wireguard:sync"))
        assertTrue(isWireGuardOperationTarget("wireguard:profile:home"))
        assertTrue(isWireGuardOperationTarget("wireguard:connect:home"))
        assertFalse(isWireGuardOperationTarget("stun:rule:wg-51820"))
        assertFalse(isWireGuardOperationTarget("favorites:refresh"))
    }

    @Test
    fun selectorKeepsEveryEligibleCurrentRouterRuleAndRejectsOthers() {
        val selected = selectableWireGuardStunRules(
            rules = listOf(
                rule("first"),
                rule("second", targetType = "manual", targetIpv4 = "192.168.5.1"),
                rule("disabled", enabled = false),
                rule("tcp", protocol = "TCP"),
                rule("other-service", serviceType = "DNS"),
                rule("wrong-port", targetPort = 51826),
                rule("foreign-router", targetType = "manual", targetIpv4 = "192.168.5.2"),
            ),
            listenPort = 51820,
            routerIp = "192.168.5.1",
        )

        assertEquals(listOf("first", "second"), selected.map { it.id })
    }

    @Test
    fun remoteMutationMessagesKeepPendingAndNotSubmittedSemanticsDistinct() {
        val pending = wireGuardRemoteMutationStatus(
            "配置",
            WireGuardRemoteMutationResult.PendingVerification(12L, "等待 Agent 回执"),
        )
        val rejected = wireGuardRemoteMutationStatus(
            "保存",
            WireGuardRemoteMutationResult.NotSubmitted("Hub 拒绝请求"),
        )

        assertTrue(pending.contains("已提交"))
        assertTrue(pending.contains("待核对"))
        assertTrue(pending.contains("原本地配置保持不变"))
        assertTrue(rejected.contains("失败，未更改"))
    }

    @Test
    fun unknownGatewaySubmissionIsNotReportedAsAccepted() {
        val unknown = wireGuardRemoteMutationStatus(
            "删除",
            WireGuardRemoteMutationResult.PendingVerification(null, "Hub 未确认接收"),
        )

        assertTrue(unknown.contains("提交结果待核对"))
        assertFalse(unknown.contains("已提交"))
    }

    @Test
    fun gatewayRefreshComparesDesiredSettingsBeforeAgentState() {
        val desired = WireGuardServerConfig(listenPort = 51826, mtu = 1420, address = "10.77.0.1/24", enabled = false)
        assertTrue(wireGuardServerConfigMatchesDesired(desired.copy(revision = 9), desired))
        assertFalse(wireGuardServerConfigMatchesDesired(desired.copy(listenPort = 51820), desired))
        assertFalse(wireGuardServerConfigMatchesDesired(desired.copy(enabled = true), desired))
    }
}
