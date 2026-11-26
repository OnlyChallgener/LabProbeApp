package com.labprobe.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
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

    @Test fun settingsIconContainerIsSquareRoundedBlock() {
        compose.setContent {
            MaterialTheme {
                SettingsRoundedIconContainer(
                    background = androidx.compose.ui.graphics.Color.Blue,
                    modifier = Modifier.testTag("settings-icon"),
                ) { Text("图标") }
            }
        }
        val bounds = compose.onNodeWithTag("settings-icon").fetchSemanticsNode().boundsInRoot
        val expected = with(compose.density) { 38.dp.toPx() }
        assertEquals(expected, bounds.width, 1f)
        assertEquals(expected, bounds.height, 1f)
    }

    @Test fun upnpRouterAssetHasTransparentCorners() {
        val bitmap = BitmapFactory.decodeResource(
            org.robolectric.RuntimeEnvironment.getApplication().resources,
            R.drawable.upnp_router,
        )
        assertTrue(bitmap.hasAlpha())
        assertEquals(0, bitmap.getPixel(0, 0) ushr 24)
        assertEquals(0, bitmap.getPixel(bitmap.width - 1, 0) ushr 24)
        assertEquals(0, bitmap.getPixel(0, bitmap.height - 1) ushr 24)
        assertEquals(0, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1) ushr 24)
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
                    Column(verticalArrangement = Arrangement.spacedBy(LabV2.SectionGap)) {
                        OfficialAccountAuthorizedContent(snapshot, emptyList(), false, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("BE72").assertIsDisplayed()
        compose.onNodeWithText("BE50").assertIsDisplayed()
        compose.onNodeWithContentDescription("BE72 路由器").assertDoesNotExist()
        compose.onNodeWithContentDescription("BE50 路由器").assertDoesNotExist()
        val routerName = compose.onNodeWithText("BE72").fetchSemanticsNode().boundsInRoot
        // The card prioritizes the official project name over the local site name.
        val projectName = compose.onNodeWithText("家庭").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("第二").assertIsDisplayed()
        val sectionTitle = compose.onAllNodesWithText("已匹配路由器")[1].fetchSemanticsNode().boundsInRoot
        assertEquals(sectionTitle.left, routerName.left, 1f)
        assertEquals(routerName.left, projectName.left, 1f)
        compose.onNodeWithText("按真实 SN 匹配").assertDoesNotExist()
        compose.onNodeWithText("退出登录").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("退出登录").fetchSemanticsNode().boundsInRoot
        assertEquals(root.width - 56f, button.width, 1f)
        assertEquals(button.center.x, root.center.x, 1f)
        assertEquals(10f, LabV2.SectionGap.value, 0f)
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

    @Test fun partialAuthorizationIsNeverLabeledFullyAuthorized() {
        val row = OfficialRouterAuthorization(
            routerId = "be72", name = "BE72", site = "", model = "", serialNumber = "SN72",
            online = true, authorized = true, matched = false, projectId = "", projectName = "",
            syncState = "failed", error = "云端项目尚未匹配",
        )
        assertEquals("同步失败", officialRouterStatusZh(row))
        assertEquals("部分授权", officialRouterStatusZh(row.copy(syncState = "checking")))
        assertEquals("已匹配", officialRouterStatusZh(row.copy(matched = true, syncState = "synced")))
    }

    @Test fun matchedRouterRefreshInvokesExplicitCallback() {
        var refreshes = 0
        val snapshot = OfficialAccountSnapshot(
            account = "186****3092", authorized = true, state = "authorized",
            projectCount = 0, matchedRouterCount = 0, hubAuthorization = "synced", expireTime = 0L,
            routers = emptyList(),
        )
        compose.setContent {
            MaterialTheme {
                OfficialAccountAuthorizedContent(snapshot, emptyList(), false, { refreshes++ }, {})
            }
        }
        compose.onNode(hasClickAction() and hasContentDescription("刷新项目匹配"))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, refreshes) }
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
