package com.labprobe.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ChildCompactLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactHeaderAndCardsKeepActionsAndSwitchVisible() {
        compose.setContent {
            Surface {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChildDeviceHeader(newChildGuardDevice("phone", "华为Mate60手机", "phone").summary, {})
                    ChildInternetOfficialHero(6, 2, 4, "更新于 06:38")
                    MasterGuardCard(true, false, true, {})
                }
            }
        }
        compose.onNodeWithContentDescription("解除儿童守护").assertIsDisplayed()
        compose.onNodeWithText("华为Mate60手机").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsEnabled()
        captureScreenRoboImage("build/outputs/roborazzi/oct16-child-header-cards.png")
    }

    @Test fun sessionRowsShareALineAndLastRowScrollsAboveClose() {
        val entries = (1..16).map { index -> InternetUsageEntry(
            id = "app$index", appName = "应用$index", iconKey = "missing", durationMinutes = index,
            timeRange = "06:00 - 06:15", count = 1,
            sessions = listOf(AppUsageSession("06:00 - 06:15", "${index}分钟"))) }
        compose.setContent {
            LabCompactAlertDialog(onDismissRequest = {}, title = { Text("今日上网记录") },
                text = { ChildUsageLogContent(entries) }, confirmButton = { TextButton({}) { Text("关闭") } })
        }
        val range = compose.onAllNodesWithText("06:00 - 06:15")[0].fetchSemanticsNode().boundsInRoot
        val duration = compose.onNodeWithText("1分钟").fetchSemanticsNode().boundsInRoot
        assertEquals(range.center.y, duration.center.y, 2f)
        compose.onNodeWithText("16分钟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed()
        val last = compose.onNodeWithText("16分钟").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithText("关闭").fetchSemanticsNode().boundsInRoot
        assertTrue(last.bottom < close.top)
        captureScreenRoboImage("build/outputs/roborazzi/oct16-child-log-bottom.png")
    }

    @Test fun onlineDevicesSortFirstWithoutReorderingTheirPeers() {
        fun device(id: String, online: Boolean) = newChildGuardDevice(id, id, "phone")
            .copy(presence = ChildGuardPresence(online))
        val ordered = orderedChildGuardDevices(listOf(device("offline1", false), device("online1", true),
            device("offline2", false), device("online2", true)), emptyList())
        assertEquals(listOf("online1", "online2", "offline1", "offline2"), ordered.map { it.summary.deviceId })
    }
}
