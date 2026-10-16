package com.labprobe.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class OctoberStatusLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun heroKeepsLargeSessionCountsAndRatesVisible() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        RouterHeroCard(RouterDashboardUi(name = "BE72", online = true, frameEpochMs = 1,
                            ipv4Connections = 65535, ipv6Connections = 65534, uploadBps = 131750,
                            downloadBps = 120280, uptimeSeconds = 17 * 86400L, temperature = 49.4,
                            onlineDevices = 8), false, null, false, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("65535").assertIsDisplayed()
        compose.onNodeWithText("65534").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/oct05-router-hero.png")
    }
    @Test fun quietReportAndRemoveDialogAreCompact() {
        compose.setContent {
            Surface {
                ChildInternetReportScreen(newChildGuardDevice("phone", "华为Mate60", "phone").copy(
                    usage = ChildUsageStats(date = childGuardStatisticsDate(), hasData = false, stale = false)),
                    "今日", {}, {}, {})
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/oct05-empty-report.png")
    }
    @Test fun removalDialogKeepsWarningAndBothActions() {
        compose.setContent { ChildRemoveGuardDialog("HBN-AL00", {}, {}) }
        compose.waitForIdle()
        compose.onNodeWithText("解除守护").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/oct05-remove-dialog.png")
    }
}
