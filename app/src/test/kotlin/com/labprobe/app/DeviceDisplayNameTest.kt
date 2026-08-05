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
}
