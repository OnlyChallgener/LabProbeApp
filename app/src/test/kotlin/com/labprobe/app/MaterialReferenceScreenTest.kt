package com.labprobe.app

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
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class MaterialReferenceScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(screen: String) {
        compose.setContent { MaterialReferencePreviewScreen(screen) }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/material-$screen.png")
    }

    @Test fun home() = capture("home")
    @Test fun devices() = capture("devices")
    @Test fun detail() = capture("detail")
    @Test fun ping() = capture("ping")
    @Test fun pingResult() = capture("result")
    @Test fun sheet() = capture("sheet")
    @Test fun dialog() = capture("dialog")
}
