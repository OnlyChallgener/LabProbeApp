package com.labprobe.app

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouterImageStoreTest {
    @Test
    fun photoKeyIsIsolatedByHubAndWorkspace() {
        val first = routerImageScopeId("http://192.168.5.46:58443/", "home")
        assertEquals(first, routerImageScopeId("http://192.168.5.46:58443", "home"))
        assertNotEquals(first, routerImageScopeId("http://192.168.5.46:58443", "office"))
        assertNotEquals(first, routerImageScopeId("http://192.168.5.47:58443", "home"))
        assertEquals(64, first.length)
    }

    @Test
    fun landscapeImageIsCroppedFromCenterToSquare() {
        val source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        for (y in 0 until 2) {
            source.setPixel(0, y, Color.BLUE)
            source.setPixel(1, y, Color.RED)
            source.setPixel(2, y, Color.GREEN)
            source.setPixel(3, y, Color.BLUE)
        }
        val cropped = centerCropRouterBitmap(source, 2)
        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        assertEquals(Color.RED, cropped.getPixel(0, 0))
        assertEquals(Color.GREEN, cropped.getPixel(1, 0))
        assertEquals(Color.RED, cropped.getPixel(0, 1))
        assertEquals(Color.GREEN, cropped.getPixel(1, 1))
    }
}
