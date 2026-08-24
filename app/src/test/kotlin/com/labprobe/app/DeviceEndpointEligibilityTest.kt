package com.labprobe.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEndpointEligibilityTest {
    private fun device(
        name: String,
        manufacture: String = "",
        devType: String = "",
    ) = DeviceItem(
        name = name,
        mac = "02:00:00:00:00:01",
        online = true,
        ip = "192.168.5.20",
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
    )

    @Test
    fun mobilePhonesAreNotOfferedAsPublicTargets() {
        assertFalse(isDeviceUsableForPublicEndpoint(device("iQOO Neo3")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("华为Mate60")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("Honor V40", devType = "phone")))
    }

    @Test
    fun smartHomeAndConsumerMediaDevicesAreNotOffered() {
        assertFalse(isDeviceUsableForPublicEndpoint(device("TP-LINK", manufacture = "TP-LINK")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("TCL电视")))
        assertFalse(isDeviceUsableForPublicEndpoint(device("客厅摄像头")))
    }

    @Test
    fun serversAndNasRemainSelectable() {
        assertTrue(isDeviceUsableForPublicEndpoint(device("Lab NAS", devType = "nas")))
        assertTrue(isDeviceUsableForPublicEndpoint(device("home-server", devType = "server")))
        assertTrue(isDeviceUsableForPublicEndpoint(device("unrecognised-host")))
    }
}
