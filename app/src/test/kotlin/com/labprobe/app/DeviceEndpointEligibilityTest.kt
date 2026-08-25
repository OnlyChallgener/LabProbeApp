package com.labprobe.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEndpointEligibilityTest {
    private fun device(
        name: String,
        manufacture: String = "",
        devType: String = "",
        hostName: String = "",
        remark: String = "",
        online: Boolean = true,
        ip: String = "192.168.5.20",
    ) = DeviceItem(
        name = name,
        mac = "02:00:00:00:00:01",
        online = online,
        ip = ip,
        ssid = "",
        band = "",
        rssi = "",
        rxrate = "",
        onlineSince = "",
        offlineAt = "",
        onlineDurationText = "",
        lastSeenAt = "",
        manufacture = manufacture,
        devType = devType,
        hostName = hostName,
        remark = remark,
    )

    @Test
    fun mobilePhonesAndWatchesAreNotOfferedAsPublicTargets() {
        assertFalse(isDeviceUsableForPublicEndpoint(device("iQOO Neo3")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("华为Mate60")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("Honor V40", devType = "phone")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("android-client", devType = "mobile")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("SM-R940")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("Galaxy Watch 6")))
    }

    @Test
    fun camerasPrintersAndRouterManagementPagesRemainSelectable() {
        assertTrue(isDeviceUsableForPublicEndpoint(device("客厅摄像头")))
        assertTrue(isDeviceUsableForPublicEndpoint(device("TP-LINK", online = false)))
        assertTrue(isDeviceUsableForPublicEndpoint(device("办公室打印机", devType = "printer")))
        assertTrue(isDeviceUsableForPublicEndpoint(device("BE72 Web", devType = "router")))
    }

    @Test
    fun smartHomeAndConsumerMediaDevicesAreNotOffered() {
        assertFalse(isDeviceUsableForPublicEndpoint(device("客厅智能灯")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("TCL电视")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("小爱音箱")))
    }

    @Test
    fun serversAndNasRemainSelectable() {
        assertTrue(isDeviceUsableForPublicEndpoint(device("Lab NAS", devType = "nas")))
        assertTrue(isDeviceUsableForPublicEndpoint(device("home-server", devType = "server")))
        assertTrue(isDeviceUsableForPublicEndpoint(device("飞牛")))
        assertTrue(isDeviceUsableForPublicEndpoint(device("DH4300PLUS-F6A9")))
        assertTrue(isDeviceUsableForPublicEndpoint(device("技嘉电脑", online = false)))
        assertTrue(isDeviceUsableForPublicEndpoint(device("unrecognised-host", online = true)))
    }

    @Test
    fun unnamedMacOnlyAndOfflineNamelessDevicesAreNotOffered() {
        assertFalse(isDeviceUsableForPublicEndpoint(device("")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("da:ec:77:47:68:0e", online = false)))
        assertFalse(isDeviceUsableForPublicEndpoint(device("de:ec:39:e5:6d:d8", online = false)))
        assertFalse(isDeviceUsableForPublicEndpoint(device("20:b8:3d:1e:e6:e8", online = false)))
    }
}
