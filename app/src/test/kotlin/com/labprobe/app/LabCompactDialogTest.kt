package com.labprobe.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class LabCompactDialogTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun existingButtonsKeepTheirOwnSingleOutlineOrFill() {
        compose.setContent {
            LabCompactAlertDialog(
                onDismissRequest = {},
                title = { Text("清理 Agent 文件") },
                text = { Text("将删除 Agent 备份和已失效的临时安装文件。") },
                dismissButton = { OutlinedButton(onClick = {}) { Text("取消") } },
                confirmButton = { Button(onClick = {}) { Text("确认清理") } },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("确认清理").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/lab-compact-dialog-existing-buttons.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun longContentKeepsActionVisible() {
        compose.setContent {
            LabCompactAlertDialog(
                onDismissRequest = {},
                title = { Text("操作说明") },
                text = { Column { repeat(30) { Text("说明第 $it 行") } } },
                dismissButton = { TextButton(onClick = {}) { Text("取消") } },
                confirmButton = { TextButton(onClick = {}) { Text("确定") } },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("操作说明").assertIsDisplayed()
        compose.onNodeWithText("确定").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/lab-compact-dialog-long-content.png")
    }
}
