package com.labprobe.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addressFieldTogglesHttpsWithoutLosingIpv6PortOrPath() {
        var value by mutableStateOf("http://[2001:db8::1]:58443/r/be72")
        compose.setContent {
            MaterialTheme {
                SettingsAddressField(
                    label = "Hub 地址",
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "Hub",
                    history = {},
                )
            }
        }
        compose.onNodeWithContentDescription("HTTPS").performClick()
        compose.runOnIdle { assertEquals("https://[2001:db8::1]:58443/r/be72", value) }
        compose.onNodeWithContentDescription("HTTPS").performClick()
        compose.runOnIdle { assertEquals("http://[2001:db8::1]:58443/r/be72", value) }
    }

    @Test fun passwordEyeStartsHiddenAndToggles() {
        compose.setContent {
            MaterialTheme {
                SettingsPasswordField("APP Token", "secret", {}, placeholder = "Token")
            }
        }
        compose.onNodeWithContentDescription("显示APP Token").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("隐藏APP Token").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("显示APP Token").assertIsDisplayed()
    }

    @Test fun expandableCardHidesContentWhenCollapsed() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SettingsExpandableCard(
                    title = "路由器配置",
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    leading = { Text("图标") },
                ) { Text("管理地址") }
            }
        }
        compose.onNodeWithText("管理地址").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开路由器配置").performClick()
        compose.onNodeWithText("管理地址").assertIsDisplayed()
        compose.onNodeWithContentDescription("折叠路由器配置").performClick()
        compose.onNodeWithText("管理地址").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun authorizedAccountFits360dpAndHasFullWidthLogout() {
        assertAuthorizedAccountLayout()
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun authorizedAccountFits393dpAndHasFullWidthLogout() {
        assertAuthorizedAccountLayout()
    }

    private fun assertAuthorizedAccountLayout() {
        val snapshot = OfficialAccountSnapshot(
            account = "186****3092",
            authorized = true,
            state = "authorized",
            projectCount = 2,
            matchedRouterCount = 2,
            hubAuthorization = "synced",
            expireTime = 0L,
            routers = listOf(
                OfficialRouterAuthorization("be72", "BE72", "家庭网络", "BE72", "SN72", true, true, true, "P1", "家庭", "synced", ""),
                OfficialRouterAuthorization("be50", "BE50", "第二个网络", "BE50", "SN50", true, true, true, "P2", "第二", "synced", ""),
            ),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(14.dp)) {
                    OfficialAccountAuthorizedContent(snapshot, emptyList(), false, {}, {})
                }
            }
        }
        compose.onNodeWithText("BE72").assertIsDisplayed()
        compose.onNodeWithText("BE50").assertIsDisplayed()
        compose.onNodeWithText("按真实 SN 匹配").assertDoesNotExist()
        compose.onNodeWithText("退出登录").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("退出登录").fetchSemanticsNode().boundsInRoot
        assertEquals(root.width - 56f, button.width, 1f)
        assertEquals(button.center.x, root.center.x, 1f)
    }

    @Test fun partialLogoutKeepsFailedHubVisibleInsteadOfClaimingSuccess() {
        val snapshot = OfficialAccountSnapshot(
            account = "186****3092",
            authorized = false,
            state = "failed",
            projectCount = 0,
            matchedRouterCount = 0,
            hubAuthorization = "failed",
            expireTime = 0L,
            routers = listOf(
                OfficialRouterAuthorization("be72", "BE72", "家庭网络", "BE72", "SN72", true, true, true, "P1", "家庭", "failed", "云端退出失败"),
            ),
            error = "部分 HUB 退出失败",
        )
        compose.setContent {
            MaterialTheme {
                OfficialAccountAuthorizedContent(snapshot, emptyList(), false, {}, {})
            }
        }
        compose.onNodeWithText("退出未完成").assertIsDisplayed()
        compose.onNodeWithText("BE72").assertIsDisplayed()
        compose.onNodeWithText("云端退出失败").assertIsDisplayed()
    }

    @Test fun officialAccountParserPreservesPartialStateWithoutSecrets() {
        val root = JSONObject("""{
            "ok":true,"account":"186****3092","authorized":true,"state":"partial",
            "projectCount":2,"matchedRouterCount":1,"hubAuthorization":"partial",
            "token":"must-not-be-exposed",
            "routers":[{"routerId":"be72","name":"BE72","online":true,"authorized":true,"matched":true,"syncState":"synced"}]
        }""")
        val parsed = parseOfficialAccountSnapshot(root)
        assertEquals("partial", parsed.state)
        assertEquals(1, parsed.matchedRouterCount)
        assertEquals("be72", parsed.routers.single().routerId)
        assertFalse(parsed.toString().contains("must-not-be-exposed"))
    }
}
