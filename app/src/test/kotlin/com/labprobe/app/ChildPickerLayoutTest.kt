package com.labprobe.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
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
class ChildPickerLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun narrowScreenLargeText() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        listOf("opple-light-moat_very-long-device-name", "e8:4a:54:bd:bd:6e", "YOK-AN10").forEachIndexed { index, name ->
                            CandidateDeviceRow(ChildGuardDeviceCandidate("2c:19:5c:28:9a:66", "192.168.110.254", name,
                                guarded = index == 2, name = name), null, false, true, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/child-picker-narrow-large-font.png")
    }
}
