package com.labprobe.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h420dp-xhdpi")
class ActionMenuHubAlignmentTest {
    @get:Rule val compose = createComposeRule()
    @Test fun actualActionPopupUsesHubProportionsAndRetainsClick() {
        var clicked = 0
        compose.setContent { MaterialTheme {
            var expanded by remember { mutableStateOf(true) }
            Box(Modifier.fillMaxSize().background(LabV2.BackgroundTop).padding(24.dp)) {
                Text("操作菜单 · 对标星耀家 HUB", color = LabV2.Ink)
                Box(Modifier.padding(top = 40.dp)) {
                    LabDropdownMenu(expanded, { expanded = false }, actionMenu = true) {
                        LabDropdownMenuItem({ Text("编辑") }, { clicked++; expanded = false })
                        LabDropdownMenuItem({ Text("复制") }, {})
                        LabDropdownMenuItem({ Text("删除", color = LabV2.Red) }, {})
                    }
                }
            }
        } }
        compose.onNodeWithText("编辑").assertHeightIsEqualTo(34.dp).assertWidthIsEqualTo(72.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("编辑").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(14.sp, layouts.single().layoutInput.style.fontSize)
        captureScreenRoboImage("build/outputs/roborazzi/action-menu-hub-aligned.png")
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("复制").assertDoesNotExist()
        assertEquals(1, clicked)
    }
}
