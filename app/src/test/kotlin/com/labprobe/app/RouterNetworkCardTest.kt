package com.labprobe.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RouterNetworkCardTest {
    @get:Rule val compose = createComposeRule()

    private val wan = RouterWanUi(
        wanId = "wan", displayName = "WAN", configured = true, enabled = true, online = true,
        defaultEgress = true, proto = "PPPoE", ipv4 = "198.51.100.1", ipv6 = "2001:db8::1",
        gateway = "198.51.100.254", gateway6 = "", dns = listOf("1.1.1.1"), uptimeSeconds = 3600,
        physicalPortId = 5, physicalPortName = "WAN", linkSpeedMbps = 2500,
        operator = "运营商 A", pppoeUsername = "user-a", broadbandPassword = "password-a",
        uploadBps = 1_000_000, downloadBps = 2_000_000,
    )
    private val wan1 = RouterWanUi(
        wanId = "wan1", displayName = "WAN1", configured = true, enabled = true, online = false,
        defaultEgress = false, proto = "DHCP", ipv4 = "", ipv6 = "", gateway = "", gateway6 = "",
        dns = emptyList(), uptimeSeconds = 0, physicalPortId = 6, physicalPortName = "LAN2/WAN1",
        linkSpeedMbps = 2500, dhcpLease = "2 小时",
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun dualWanButtonsExpandOnlyOneLineAt360dp() {
        assertDualWanBehavior()
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun dualWanButtonsExpandOnlyOneLineAt393dp() {
        assertDualWanBehavior()
    }

    private fun assertDualWanBehavior() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(14.dp)) {
                    RouterNetworkCard(RouterDashboardUi(
                        name = "BE72", model = "BE72", online = true,
                        lanIpv4 = "192.168.5.1", netmask = "255.255.255.0", lanMac = "aa:bb:cc:dd:ee:ff",
                        apName = "LabProbe", apHostName = "BE72",
                        wans = listOf(wan, wan1.copy(online = true, ipv4 = "198.51.100.2", gateway = "198.51.100.254")),
                    ))
                }
            }
        }
        compose.onNodeWithContentDescription("展开 WAN 详情").assertIsDisplayed()
        compose.onNodeWithContentDescription("展开 WAN1 详情").assertIsDisplayed()
        compose.onNodeWithText("宽带账号").assertDoesNotExist()
        compose.onNodeWithText("DHCP 租期").assertDoesNotExist()

        compose.onNodeWithContentDescription("展开 WAN1 详情").performClick()
        compose.onNodeWithText("DHCP 租期").assertIsDisplayed()
        compose.onNodeWithText("宽带账号").assertDoesNotExist()

        compose.onNodeWithContentDescription("展开 WAN 详情").performClick()
        compose.onNodeWithText("宽带账号").assertIsDisplayed()
        compose.onNodeWithText("DHCP 租期").assertDoesNotExist()
        compose.onNodeWithText("password-a").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun offlineWan1IsHiddenWhileWanStaysSingleLine() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(14.dp)) {
                    RouterNetworkCard(RouterDashboardUi(
                        name = "BE72", model = "BE72", online = true,
                        wans = listOf(wan, wan1),
                    ))
                }
            }
        }
        compose.onNodeWithText("WAN 信息").assertIsDisplayed()
        compose.onNodeWithContentDescription("展开 WAN1 详情").assertDoesNotExist()
        compose.onNodeWithText("WAN1").assertDoesNotExist()
        compose.onNodeWithText("离线").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun singleWanDoesNotShowLineButtons() {
        compose.setContent {
            MaterialTheme {
                RouterNetworkCard(RouterDashboardUi(wans = listOf(wan)))
            }
        }
        compose.onNodeWithContentDescription("展开 WAN 详情").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开 WAN1 详情").assertDoesNotExist()
        compose.onNodeWithText("WAN 信息").assertIsDisplayed()
    }
}
