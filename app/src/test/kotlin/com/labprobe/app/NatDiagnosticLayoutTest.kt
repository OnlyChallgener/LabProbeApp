package com.labprobe.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class NatDiagnosticLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val task = RouterTaskSnapshot(
        kind = "nat",
        taskId = "nat-1",
        state = "succeeded",
        stage = "finished",
        stageText = "检测完成",
        startedAt = 1795258800L,
        updatedAt = 1795258821L,
        lastRouterResponseAt = 1795258821L,
        elapsedSeconds = 21L,
        log = listOf("2026-11-12 10:40:07 [NAT 检测] 执行 NAT 类型检测"),
    )
    private val result = RouterNatResult(
        timestamp = 1795258821L,
        status = "completed",
        mode = "classic",
        natType = "port-restricted cone",
        externalAddress = "36.157.252.48:16871",
        stunPort = 3478,
        taskState = "succeeded",
        elapsedSeconds = 21L,
        lastRouterResponseAt = 1795258821L,
        requestedInterface = "wan",
        detectedInterface = "wan",
        log = "2026-11-12 10:40:07 [NAT 检测] 执行 NAT 类型检测",
    )

    private fun render() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NativeSelector(
                            label = "STUN 服务器",
                            value = "stun.voip.aebc.com",
                            options = listOf("stun.voip.aebc.com" to "stun.voip.aebc.com"),
                            expanded = false,
                            onExpandedChange = {},
                            onSelect = {},
                            modifier = Modifier.weight(1.35f),
                            leadingIcon = Icons.Rounded.Dns,
                        )
                        NativeCompactPortField(value = "3478", onValueChange = {}, modifier = Modifier.weight(.85f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NativeSelector(
                            label = "STUN 类型",
                            value = "RFC 3489",
                            options = listOf("classic" to "RFC 3489"),
                            expanded = false,
                            onExpandedChange = {},
                            onSelect = {},
                            modifier = Modifier.weight(1.35f),
                            leadingIcon = Icons.Rounded.Tune,
                        )
                        NativeSelector(
                            label = "WAN 类型",
                            value = "WAN",
                            options = listOf("wan" to "WAN"),
                            expanded = false,
                            onExpandedChange = {},
                            onSelect = {},
                            modifier = Modifier.weight(.85f),
                            leadingIcon = Icons.Rounded.Public,
                        )
                    }
                    NatDiagnosticColumns(task = task, result = result, loading = false)
                    NatHistoryRow(item = result, onDelete = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertLayout() {
        compose.onNodeWithText("STUN 服务器").assertIsDisplayed()
        compose.onNodeWithText("stun.voip.aebc.com").assertIsDisplayed()
        compose.onNodeWithText("STUN 端口").assertIsDisplayed()
        compose.onNodeWithText("STUN 类型").assertIsDisplayed()
        compose.onNodeWithText("WAN 类型").assertIsDisplayed()
        compose.onNodeWithText("诊断过程").assertIsDisplayed()
        compose.onNodeWithText("分析结果").assertIsDisplayed()
        compose.onNodeWithText("36.157.252.48:16871 · RFC3489 · WAN · 3478 端口").assertIsDisplayed()

        val labelBounds = compose.onNodeWithText("STUN 服务器").fetchSemanticsNode().boundsInRoot
        val valueBounds = compose.onNodeWithText("stun.voip.aebc.com").fetchSemanticsNode().boundsInRoot
        assertTrue("parameter label must sit above the field", labelBounds.bottom <= valueBounds.top)

        val processBounds = compose.onNodeWithText("诊断过程").onParent().fetchSemanticsNode().boundsInRoot
        val resultBounds = compose.onNodeWithText("分析结果").onParent().fetchSemanticsNode().boundsInRoot
        assertTrue("diagnostic columns must be side by side", processBounds.right < resultBounds.left)
        val totalWidth = resultBounds.right - processBounds.left
        val ratio = processBounds.width.toFloat() / totalWidth.toFloat()
        assertTrue("diagnostic ratio expected near 42:58, got $ratio", ratio in .36f..48f)

        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onAllNodesWithText("36.157.252.48:16871").fetchSemanticsNodes().forEach { node ->
            assertTrue(node.boundsInRoot.left >= rootBounds.left - 1 && node.boundsInRoot.right <= rootBounds.right + 1)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText("36.157.252.48:16871")[0]
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        val layout = layouts.single()
        assertEquals("36.157.252.48:16871".length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    @Test fun layoutFitsAt360dp() {
        render()
        assertLayout()
    }

    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    @Test fun layoutFitsAt393dp() {
        render()
        assertLayout()
    }
}
