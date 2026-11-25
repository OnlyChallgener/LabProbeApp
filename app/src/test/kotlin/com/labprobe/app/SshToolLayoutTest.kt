package com.labprobe.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class SshToolLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun prefs(): AppPrefs = AppPrefs(RuntimeEnvironment.getApplication()).apply {
        sshHost = "192.168.1.1"
        sshCommand = "uptime"
        sshSavePass = false
    }

    private fun render(prefs: AppPrefs) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { SshTool(prefs, {}) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun passwordVisibilityTogglesAndSaveCheckboxReportsChanges() {
        var saved by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                var visible by remember { mutableStateOf(false) }
                SshPasswordInput(
                    value = "secret",
                    visible = visible,
                    save = saved,
                    onValueChange = {},
                    onVisibilityChange = { visible = it },
                    onSaveChange = { saved = it },
                )
            }
        }
        compose.onNodeWithContentDescription("显示密码").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertIsDisplayed()
        compose.onAllNodesWithText("保存密码").assertCountEquals(0)

        compose.onNodeWithContentDescription("保存密码").assertIsDisplayed().performClick()
        assertFalse(saved)
    }

    @Test
    fun reenteringPageResetsPasswordToHidden() {
        val prefs = prefs()
        val generation = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                key(generation.value) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { SshTool(prefs, {}) }
                }
            }
        }
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertIsDisplayed()
        compose.runOnIdle { generation.value += 1 }
        compose.onNodeWithContentDescription("显示密码").assertIsDisplayed()
    }

    @Test
    fun hostPasswordAndCommandLabelsAndFieldsAlign() {
        val prefs = prefs()
        render(prefs)
        val labels = listOf("主机", "密码", "命令").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(labels.all { it.left == labels[0].left && it.width == labels[0].width })
        val host = compose.onNodeWithText("192.168.1.1").fetchSemanticsNode().boundsInRoot
        val password = compose.onNodeWithText("SSH 密码").fetchSemanticsNode().boundsInRoot
        val command = compose.onNodeWithText("uptime").fetchSemanticsNode().boundsInRoot
        assertTrue(host.left == password.left && host.left == command.left)
    }

    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    @Test
    fun layoutStillAlignsAt393dp() {
        val prefs = prefs()
        render(prefs)
        val labels = listOf("主机", "密码", "命令").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(labels.all { it.left == labels[0].left && it.width == labels[0].width })
    }
}
