package com.labprobe.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("WireGuard").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        val title = layouts.single()
        assertFalse(title.isLineEllipsized(0))
        assertEquals("The full title must be rendered", title.layoutInput.text.length, title.getLineEnd(0, visibleEnd = true))
    }
}
