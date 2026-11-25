package com.labprobe.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class RouterPortsCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun footerUsesAggregateWanRatesWithoutChangingPortSpeeds() {
        compose.setContent {
            MaterialTheme {
                RouterPortsCard(
                    ui = RouterDashboardUi(
                        uploadBps = 1_500_000,
                        downloadBps = 2_500_000,
                        ports = listOf(RouterPortUi("WAN", true, true, false, false, "2500 Mbps")),
                    ),
                    onShowLegend = {},
                )
            }
        }
        compose.onNodeWithText("WAN总速率：").assertIsDisplayed()
        compose.onNodeWithText("1.5 Mbps").assertIsDisplayed()
        compose.onNodeWithText("2.5 Mbps").assertIsDisplayed()
        compose.onNodeWithText("2500 Mbps").assertIsDisplayed()
        compose.onNodeWithText("整机实时：").assertDoesNotExist()
        compose.onNodeWithText("WAN口速率").assertDoesNotExist()
    }
}
