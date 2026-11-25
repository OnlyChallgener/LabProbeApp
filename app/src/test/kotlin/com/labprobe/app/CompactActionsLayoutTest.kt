package com.labprobe.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class CompactActionsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun managedWireGuardTitleFitsBesideStatusOnNarrowScreen() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StunRuleCard(
                        rule = StunRule("wg", "WireGuard", true, 51820,
                            targetIpv4 = "192.168.5.1", targetPort = 51820,
                            serviceType = "WireGuard", transportProtocol = "UDP", forwardMode = "router_native",
                            actualState = "mapped", firewallState = "ready", nativeMappingState = "ready",
                            runtime = StunRuntime(publicEndpoint = "36.157.252.49:5899", mappingFresh = true)),
                        agentOnline = true, wireGuardManaged = true, deleting = false,
                        operationLabel = null, menuOpen = false, onOpenSsh = { _, _ -> },
                        onOpenWireGuard = {}, onMenu = {}, onCopy = {}, onHistory = {},
                        onEdit = {}, onToggle = {}, onDelete = {},
                    )
                    PillButton("配置 AI API", accent = LabV2.Cyan, onClick = {})
                    PillButton("关闭隐私模式", accent = LabV2.Cyan, onClick = {})
                    PillButton("检测中…", enabled = false, accent = LabV2.Cyan, onClick = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compact-actions-wireguard.png")
        compose.onNodeWithText("UDP · 自动管理").assertIsDisplayed()
        compose.onNodeWithText("路由器直连 → 192.168.5.1:51820").assertIsDisplayed()
        compose.onAllNodesWithText("动态地址").assertCountEquals(0)
        compose.onAllNodesWithText("流量不经过 LabRelay").assertCountEquals(0)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("WireGuard").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        val title = layouts.single()
        assertFalse(title.isLineEllipsized(0))
        assertEquals("The full title must be rendered", title.layoutInput.text.length, title.getLineEnd(0, visibleEnd = true))
        compose.onNodeWithText("地址已获取").assertIsDisplayed()
        compose.onNodeWithContentDescription("地址记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("快捷访问").assertIsDisplayed()
        compose.onNodeWithContentDescription("复制").assertIsDisplayed()
    }

    @Test
    fun proxyTrafficAndLongEndpointFitAt360dp() {
        val endpoint = "[2409:8a50:2e04:3990:39f4:eec9:9df6:f878]:20002"
        compose.setContent {
            MaterialTheme {
                StunRuleCard(
                    rule = StunRule(
                        id = "ssh",
                        name = "SSH 穿透",
                        enabled = true,
                        listenPort = 20002,
                        targetType = "router_self",
                        targetIpv4 = "127.0.0.1",
                        targetPort = 54133,
                        serviceType = "SSH",
                        transportProtocol = "TCP",
                        forwardMode = "relay_proxy",
                        actualState = "mapped",
                        firewallState = "ready",
                        nativeMappingState = "pending",
                        runtime = StunRuntime(
                            publicEndpoint = endpoint,
                            mappingFresh = true,
                            totalDownloadBytes = (21.1 * 1024 * 1024).toLong(),
                            totalUploadBytes = 330L * 1024L,
                        ),
                    ),
                    agentOnline = true,
                    wireGuardManaged = false,
                    deleting = false,
                    operationLabel = null,
                    menuOpen = false,
                    onOpenSsh = { _, _ -> },
                    onOpenWireGuard = {},
                    onMenu = {},
                    onCopy = {},
                    onHistory = {},
                    onEdit = {},
                    onToggle = {},
                    onDelete = {},
                )
            }
        }
        compose.waitForIdle()
        assertFullyVisible(endpoint)
        assertSingleLine("21.1 MB")
        assertSingleLine("330 KB")
        assertSingleLine("地址已获取")
        compose.onAllNodesWithText("下载").assertCountEquals(0)
        compose.onAllNodesWithText("上传").assertCountEquals(0)
    }

    @Config(qualifiers = "w393dp-h740dp-xhdpi")
    @Test
    fun nativeLongEndpointAndRouteFitAt393dpWithoutTraffic() {
        val endpoint = "[2409:8a50:2e04:3990:39f4:eec9:9df6:f878]:36495"
        val route = "路由器直连 → 192.168.5.98:5666"
        compose.setContent {
            MaterialTheme {
                StunRuleCard(
                    rule = StunRule(
                        id = "http",
                        name = "HTTP 穿透",
                        enabled = true,
                        listenPort = 36495,
                        targetIpv4 = "192.168.5.98",
                        targetPort = 5666,
                        serviceType = "HTTP",
                        transportProtocol = "TCP",
                        forwardMode = "router_native",
                        actualState = "mapped",
                        firewallState = "ready",
                        nativeMappingState = "ready",
                        runtime = StunRuntime(publicEndpoint = endpoint, mappingFresh = true),
                    ),
                    agentOnline = true,
                    wireGuardManaged = false,
                    deleting = false,
                    operationLabel = null,
                    menuOpen = false,
                    onOpenSsh = { _, _ -> },
                    onOpenWireGuard = {},
                    onMenu = {},
                    onCopy = {},
                    onHistory = {},
                    onEdit = {},
                    onToggle = {},
                    onDelete = {},
                )
            }
        }
        compose.waitForIdle()
        assertFullyVisible(endpoint)
        assertFullyVisible(route)
        assertSingleLine("地址已获取")
        compose.onNodeWithContentDescription("地址记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("快捷访问").assertIsDisplayed()
        compose.onNodeWithContentDescription("复制").assertIsDisplayed()
        compose.onAllNodesWithText("下载").assertCountEquals(0)
        compose.onAllNodesWithText("上传").assertCountEquals(0)
    }

    private fun assertSingleLine(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertEquals(text.length, layout.getLineEnd(0, visibleEnd = true))
    }

    private fun assertFullyVisible(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
}
