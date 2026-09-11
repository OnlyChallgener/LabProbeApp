package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDisplayNameTest {
    @Test
    fun userRemarkOverridesRouterName() {
        assertEquals(
            "华为Mate60",
            deviceDisplayName("华为Mate60", "Huawei Mate60", "HUAWEI-MATE60", "24:1A:E6:BB:16:D9")
        )
    }

    @Test
    fun routerNameIsUsedWhenRemarkIsBlank() {
        assertEquals(
            "Huawei Mate60",
            deviceDisplayName("", "Huawei Mate60", "HUAWEI-MATE60", "24:1A:E6:BB:16:D9")
        )
    }

    @Test
    fun hostNameIsUsedWhenBothNamesAreBlank() {
        assertEquals(
            "HUAWEI-MATE60",
            deviceDisplayName("", "", "HUAWEI-MATE60", "24:1A:E6:BB:16:D9")
        )
    }

    @Test
    fun macIsTheFinalFallback() {
        assertEquals(
            "24:1a:e6:bb:16:d9",
            deviceDisplayName("", "", "", "24:1A:E6:BB:16:D9")
        )
    }

    @Test
    fun manufacturerFormattingCapitalizesCorrectly() {
        assertEquals("Huawei", formatManufacturer("huawei"))
        assertEquals("Haier", formatManufacturer("haier"))
        assertEquals("Samsung", formatManufacturer("samsung"))
        assertEquals("Xiaomi", formatManufacturer("xiaomi"))
        assertEquals("TP-Link", formatManufacturer("tp-link"))
        assertEquals("OPPO", formatManufacturer("oppo"))
        assertEquals("VIVO", formatManufacturer("vivo"))
        assertEquals("OnePlus", formatManufacturer("oneplus"))
    }

    private fun testDevice(
        name: String = "",
        mac: String = "24:1a:e6:bb:16:d9",
        hostName: String = "",
        manufacture: String = ""
    ) = DeviceItem(
        name = name,
        mac = mac,
        online = true,
        ip = "192.168.1.100",
        ssid = "",
        band = "",
        rssi = "",
        rxrate = "",
        onlineSince = "",
        offlineAt = "",
        onlineDurationText = "",
        lastSeenAt = "",
        hostName = hostName,
        manufacture = manufacture
    )

    @Test
    fun resolvesManufacturerForSmartwatchAndModels() {
        val watch = testDevice(name = "SM-R940", mac = "f6:8b:2b:df:06:b0")
        assertEquals("Samsung", resolveDeviceManufacturer(watch))

        val kidWatch = testDevice(name = "360-KIDSWATCH-W370")
        assertEquals("360", resolveDeviceManufacturer(kidWatch))

        val mate20 = testDevice(hostName = "HUAWEI_Mate_20_Pro-37491c")
        assertEquals("Huawei", resolveDeviceManufacturer(mate20))

        val explicitWithFormat = testDevice(name = "My Phone", manufacture = "huawei")
        assertEquals("Huawei", resolveDeviceManufacturer(explicitWithFormat))
    }
}
