package com.labprobe.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h700dp-xhdpi")
class SshResultDialogRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val result = SshResultEntry(1L, "10-20 15:30", "192.168.1.1", "uname -a",
        "SSH-RESULT-PROBE\n" + (1..100).joinToString("\n") { "output line $it" })

    @Test fun recentResultOpensAndLongOutputScrollsWithActionsVisible() {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        prefs.clearSshResults(); prefs.addSshResult(result)
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { SshTool(prefs, {}) }
        } }
        compose.onNodeWithText("SSH-RESULT-PROBE", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("复制输出").assertIsDisplayed()
        compose.onNode(hasText("output line 100", substring = true) and hasAnyAncestor(isDialog())).performScrollTo()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNodeWithText("复制输出").assertDoesNotExist()
    }

    @Test fun historyResultOpensWithoutNestedScrollCrash() {
        val prefs = AppPrefs(RuntimeEnvironment.getApplication())
        prefs.clearSshResults(); prefs.addSshResult(result)
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { SshHistoryTool(prefs) }
        } }
        compose.onNodeWithText("SSH-RESULT-PROBE", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("复制输出").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
    }
}
