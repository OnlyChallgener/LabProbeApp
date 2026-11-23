package com.labprobe.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class FavoriteUiContractTest {
    @get:Rule val compose = createComposeRule()
    private val sample = FavoriteShortcut("test", "绿联 NAS", "描述不占主卡额外一层", "builtin", "ugreen",
        "https://192.168.5.46:5001", "https://nas.example.com:1234", 0, serviceType = "HTTPS")

    @Test fun iconEditsAddressCopiesAndNavigatesWithoutPopupAndLongPressShowsRemoveCross() {
        var edits = 0; var opens = 0; var copies = 0; var deletes = 0
        compose.setContent {
            var arranging by remember { mutableStateOf(false) }
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                    FavoriteShortcutCard(sample, "lan", arranging = arranging, onArrange = { arranging = true }, columns = 1, mapping = FavoriteMappingResolution(null, false),
                        stun = FavoriteStunResolution(null, false), accessReport = null, dragEnabled = true,
                        onOpen = { opens++ }, onEdit = { edits++ }, onCopyAddress = { copies++ },
                        onDelete = { deletes++ }, onViewMapping = {}, onMoveBy = {})
                }
            }
        }
        compose.onNodeWithText("HTTPS").assertDoesNotExist()
        compose.onNodeWithContentDescription("绿联 UGREEN").performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(0, opens) }
        compose.onNodeWithText("绿联 NAS").performClick()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, copies) }
        compose.onNodeWithText("复制地址").assertDoesNotExist()
        compose.onNodeWithText("跳转").assertDoesNotExist()
        compose.onNodeWithText("绿联 NAS").performTouchInput { longClick() }
        compose.onNodeWithText("复制地址").assertDoesNotExist()
        compose.onNodeWithContentDescription("移除收藏").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/favorites-arrangement.png")
        compose.onNodeWithContentDescription("移除收藏").performClick()
        compose.runOnIdle { assertEquals(1, deletes); assertEquals(1, edits) }

    }

    @Test fun draggingReordersStoredFavoritesAndRemovalFillsTheGap() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context, "favorite_grid_ui")
        prefs.hubRoot = "http://127.0.0.1:1"
        prefs.saveFavoriteShortcuts(listOf(sample.copy(id = "a", title = "第一张"), sample.copy(id = "b", title = "第二张", lanUrl = "https://[240e:1234:5678:abcd:1234:5678:abcd:1234]:5001/admin/dashboard", order = 1)))
        compose.setContent { MaterialTheme {
            FavoritesScreen(prefs, onOpenDns = {}, onOpenPortMapping = {}, onOpenSettings = {})
        } }
        val firstCard = compose.onNodeWithTag("favorite-card-a").fetchSemanticsNode().boundsInRoot
        val secondCard = compose.onNodeWithTag("favorite-card-b").fetchSemanticsNode().boundsInRoot
        assertEquals(firstCard.height, secondCard.height, 1f)
        captureScreenRoboImage("build/outputs/roborazzi/favorites-equal-height.png")
        val first = compose.onNodeWithText("第一张").fetchSemanticsNode().boundsInRoot.center
        val second = compose.onNodeWithText("第二张").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(first) }
        compose.mainClock.advanceTimeBy(650)
        compose.onRoot().performTouchInput { moveTo(second, delayMillis = 180) }
        compose.mainClock.advanceTimeBy(64)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/favorites-grid-drop.png")
        compose.waitUntil(5000) { prefs.favoriteShortcuts().map { it.id } == listOf("b", "a") }
        compose.onNodeWithText("完成").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("移除收藏").onFirst().performClick()
        compose.waitUntil(5000) { prefs.favoriteShortcuts().size == 1 }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/favorites-grid-arranged.png")
    }

    @Test fun externalCascadeUsesNameHostPortAndSavesSelectedBinding() {
        val option = FavoriteAddressOption(FavoriteEndpointBinding("stun", "s-1", "wan"), "STUN 规则", "飞牛", "203.0.113.9:5905", "HTTP")
        var saved: FavoriteShortcut? = null
        compose.setContent { MaterialTheme {
            FavoriteEditorSheet(sample, listOf(LabProbeDdnsRecord(id = "dns-1", hostname = "nas.example.com")),
                addressOptions = listOf(option,
                    option.copy(binding = FavoriteEndpointBinding("stun", "s-2", "wan"), name = "绿联", endpoint = "203.0.113.9:6011"),
                    option.copy(binding = FavoriteEndpointBinding("stun", "s-3", "wan"), name = "SSH", endpoint = "203.0.113.9:5906", serviceType = "SSH"),
                    option.copy(binding = FavoriteEndpointBinding("webhook", "nas", "wan"), group = "Webhook 推送"),
                    option.copy(binding = FavoriteEndpointBinding("mapping", "m-1", "wan"), group = "IPv6 端口映射"),
                    option.copy(binding = FavoriteEndpointBinding("home", "vpn", "wan"), group = "首页 VPN / 穿透")),
                onDismiss = {}, onSave = { saved = it })
        } }
        compose.onNodeWithContentDescription("选择外网地址来源").performScrollTo().performClick()
        compose.onNodeWithText("Webhook 推送").assertIsDisplayed()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/favorite-source-categories.png")
        compose.onNodeWithText("STUN 规则").performClick()
        compose.onNodeWithText("飞牛 - 203.0.113.9:5905").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/favorite-source-cascade.png")
        compose.onNodeWithText("飞牛 - 203.0.113.9:5905").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(option.binding, saved?.remoteBinding)
            assertEquals("https://203.0.113.9:5905", saved?.remoteEndpoint)
            assertEquals(sample.title, saved?.title)
        }
    }

    @Test fun popupIsTextOnlyCompactCenteredAndVisibleAtBottom() {
        var iconsDrawn = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.size(32.dp)) {
                    LabDropdownMenu(true, {}) {
                        LabDropdownMenuItem(text = { androidx.compose.material3.Text("编辑") }, onClick = {},
                            modifier = Modifier.testTag("menu-row"), leadingIcon = { iconsDrawn++ })
                        LabDropdownMenuItem(text = { androidx.compose.material3.Text("删除") }, onClick = {})
                    }
                }
            }
        } }
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed()
        val row = compose.onNodeWithTag("menu-row").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("编辑").fetchSemanticsNode().boundsInRoot
        assertEquals(row.center.x, label.center.x, 1f)
        assertTrue(row.height <= 50f)
        assertTrue(row.width >= 128f && row.width <= 132f)
        compose.runOnIdle { assertEquals(0, iconsDrawn) }
        captureScreenRoboImage("build/outputs/roborazzi/compact-text-dropdown.png")
    }

    @Test fun lanSelectionPairsWanAndSuffixRemainsBoundWhileManualAuthorityWins() {
        val lan = FavoriteAddressOption(FavoriteEndpointBinding("stun", "ssh", "lan"), "STUN 规则", "绿联 SSH", "192.168.5.46:2122", "SSH")
        val wan = lan.copy(binding = lan.binding.copy(side = "wan"), endpoint = "36.157.252.49:6011")
        var options by mutableStateOf(listOf(lan, wan))
        var saved: FavoriteShortcut? = null
        compose.setContent { MaterialTheme {
            FavoriteEditorSheet(sample.copy(title = "绿联 SSH", iconValue = "ssh", serviceType = "SSH", wanUrl = "", remoteEndpoint = ""),
                emptyList(), addressOptions = options, onDismiss = {}, onSave = { saved = it })
        } }
        compose.onNodeWithContentDescription("选择内网地址来源").performScrollTo().performClick()
        compose.onNodeWithText("STUN 规则").performClick()
        compose.onNodeWithText("绿联 SSH - 192.168.5.46:2122").performClick()
        compose.onNodeWithText("36.157.252.49:6011").assertExists()
        compose.onNodeWithText("36.157.252.49:6011").performScrollTo().performTextReplacement("36.157.252.49:6011/be72")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(wan.binding, saved?.remoteBinding) }
        compose.runOnIdle { options = listOf(lan, wan.copy(endpoint = "203.0.113.10:6012")) }
        compose.onNodeWithText("203.0.113.10:6012/be72").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/favorite-paired-addresses.png")
        compose.onNodeWithText("203.0.113.10:6012/be72").performTextReplacement("203.0.113.99:9000/be72")
        compose.runOnIdle { options = listOf(lan, wan.copy(endpoint = "203.0.113.20:6013")) }
        compose.onNodeWithText("203.0.113.99:9000/be72").assertExists()
        compose.onNodeWithContentDescription("选择内网地址来源").performScrollTo().performClick()
        compose.onNodeWithText("STUN 规则").performClick()
        compose.onNodeWithText("绿联 SSH - 192.168.5.46:2122").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertNull(saved?.remoteBinding)
            assertTrue(saved?.remoteEndpoint.orEmpty().endsWith("203.0.113.99:9000/be72"))
        }
    }

    @Test fun newRouterAndCameraArtworkPreview() {
        compose.setContent { MaterialTheme {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FavoritePresetIcon("router", 44)
                    androidx.compose.material3.Text("路由器")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FavoritePresetIcon("camera", 44)
                    androidx.compose.material3.Text("摄像头")
                }
            }
        } }
        compose.onNodeWithText("摄像头").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/favorite-new-icons.png")
    }

    @Test fun routerSwitcherPreviewRetainsFloatingBottomGap() {
        compose.setContent { MaterialTheme {
            RouterWorkspacePickerDialog(
                workspaces = listOf(
                    RouterWorkspace("be72", "BE72", "", "BE72", true, "/r/be72", deviceCount = 30, onlineDeviceCount = 10, followedOnlineCount = 1),
                    RouterWorkspace("be50", "BE50", "", "BE50", true, "/r/be50", deviceCount = 26, onlineDeviceCount = 8, followedOnlineCount = 0)),
                selectedId = "be72", listingConfirmed = true, loading = false, error = "",
                onSelect = {}, onEdit = {}, onAdd = {}, onRefresh = {}, onDismiss = {})
        } }
        compose.onNodeWithText("BE72").assertIsDisplayed()
        compose.onNodeWithText("BE50").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/router-switcher.png")
    }

    @Test fun editorKeepsActionsVisibleAndNameDescriptionSeparate() {
        compose.setContent {
            MaterialTheme { FavoriteEditorSheet(sample, listOf(LabProbeDdnsRecord(id = "dns-1", hostname = "nas.example.com")), onDismiss = {}, onSave = {}) }
        }
        compose.onNodeWithText("编辑收藏").assertIsDisplayed()
        compose.onNodeWithText("名称").assertIsDisplayed()
        compose.onNodeWithText("描述").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("图片网址").assertDoesNotExist()
        compose.onNodeWithText("恢复自动匹配").assertDoesNotExist()
        compose.onNodeWithText("上传").performScrollTo().assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/favorites-editor.png")
        compose.onNodeWithText("自动匹配").assertIsDisplayed().performClick()
        compose.onNodeWithText("自动匹配中").assertIsDisplayed()
    }
}
