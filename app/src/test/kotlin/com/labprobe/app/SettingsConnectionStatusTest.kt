package com.labprobe.app

import org.junit.Assert.*
import org.junit.Test

class SettingsConnectionStatusTest {
    private fun status(
        message: String = "",
        stateMessage: String = "",
        realtimeState: HubRealtimeState = HubRealtimeState.Disabled,
        hubConnected: Boolean = false,
        mqttConnected: Boolean = false,
        realtimeDataFresh: Boolean = false,
        lastRouterRealtimeAt: Long = 0L,
        realtimeFallbackActive: Boolean = false,
    ) = settingsConnectionStatus(
        message, stateMessage, realtimeState, hubConnected, mqttConnected,
        realtimeDataFresh, lastRouterRealtimeAt, realtimeFallbackActive,
    )

    @Test fun hubFailureIsShownOnceWithItsSpecificReason() {
        val result = status(
            message = "Hub 连接失败：UnknownHost",
            stateMessage = "Hub 连接失败：UnknownHost",
            realtimeState = HubRealtimeState.Reconnecting(2, 5, "UnknownHost"),
        )
        assertEquals("Hub 域名解析失败", result.message)
        assertEquals(SettingsConnectionKind.Hub, result.kind)
        assertEquals(SettingsConnectionTone.Error, result.tone)
        assertFalse(result.message.contains("原因："))
    }

    @Test fun tokenFailureTakesPriorityOverGenericRealtimeFailure() {
        val result = status(
            message = "实时链路恢复中",
            stateMessage = "实时链路恢复中",
            realtimeState = HubRealtimeState.Reconnecting(1, 5, "HTTP 401 unauthorized"),
        )
        assertEquals("Token 认证失败，请检查 APP Token", result.message)
        assertEquals(SettingsConnectionKind.Token, result.kind)
        assertEquals(SettingsConnectionTone.Error, result.tone)
    }

    @Test fun routerAuthenticationFailureIsNotMisreportedAsTokenFailure() {
        val result = status(message = "路由器配置读取失败：Hub 登录路由器失败")
        assertEquals("路由器读取失败，请检查管理连接", result.message)
        assertEquals(SettingsConnectionKind.Router, result.kind)
        assertEquals(SettingsConnectionTone.Warning, result.tone)
    }

    @Test fun realtimeFailureKeepsTheActionFailureWithoutDuplicatingReason() {
        val result = status(message = "校准失败：请求超时", stateMessage = "实时链路恢复中")
        assertEquals("校准失败：请求超时", result.message)
        assertEquals(SettingsConnectionKind.Realtime, result.kind)
        assertEquals(SettingsConnectionTone.Warning, result.tone)
        assertEquals(1, Regex("超时").findAll(result.message).count())
    }

    @Test fun connectingIsBlueAndDoesNotKeepStaleFailureText() {
        val result = status(
            message = "Hub 连接失败：connection refused",
            stateMessage = "Hub 连接失败：connection refused",
            realtimeState = HubRealtimeState.Connecting,
        )
        assertEquals("正在连接 Hub", result.message)
        assertEquals(SettingsConnectionTone.Connecting, result.tone)
    }

    @Test fun recoveredConnectionClearsStaleHubAndTokenErrors() {
        val result = status(
            message = "Hub 认证失败：APP Token 错误",
            stateMessage = "Hub 连接失败：UnknownHost",
            realtimeState = HubRealtimeState.Connected,
            hubConnected = true,
            mqttConnected = true,
            realtimeDataFresh = true,
            lastRouterRealtimeAt = 123L,
        )
        assertEquals("实时同步正常", result.message)
        assertEquals(SettingsConnectionKind.Normal, result.kind)
        assertEquals(SettingsConnectionTone.Success, result.tone)
    }

    @Test fun routerFailureRemainsVisibleWhenHubIsHealthy() {
        val result = status(
            message = "路由器配置读取失败：connection refused",
            realtimeState = HubRealtimeState.Connected,
            hubConnected = true,
            mqttConnected = true,
            realtimeDataFresh = true,
        )
        assertEquals("路由器读取失败，请检查管理连接", result.message)
        assertEquals(SettingsConnectionKind.Router, result.kind)
    }

    @Test fun missingRouterDataIsARealtimeWarningRatherThanHealthy() {
        val result = status(
            realtimeState = HubRealtimeState.Connected,
            hubConnected = true,
            mqttConnected = true,
            lastRouterRealtimeAt = 123L,
        )
        assertEquals("Hub 已连接，等待路由器最新数据", result.message)
        assertEquals(SettingsConnectionKind.Realtime, result.kind)
        assertEquals(SettingsConnectionTone.Warning, result.tone)
    }

    @Test fun fallbackDataIsSuccessfulWithoutPrimaryRealtimeConnection() {
        val result = status(
            realtimeState = HubRealtimeState.Reconnecting(2, 5, ""),
            hubConnected = true,
            realtimeDataFresh = true,
            realtimeFallbackActive = true,
        )
        assertEquals("实时数据由备用链路更新", result.message)
        assertEquals(SettingsConnectionTone.Success, result.tone)
    }

    @Test fun idleStateIsNeutral() {
        val result = status()
        assertEquals("等待连接 Hub", result.message)
        assertEquals(SettingsConnectionTone.Neutral, result.tone)
    }
}
