package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterConfigAndReleaseNotesTest {
    @Test
    fun blankRouterPasswordIsOmitted() {
        val body = RouterConfigUpdate("BE72", "admin", "http://192.168.5.1", "").toJson()
        assertTrue(body.optBoolean("test"))
        assertFalse(body.has("password"))
    }

    @Test
    fun releaseNotesPreferReadableManifestFields() {
        val notes = normalizeReleaseNotes(JSONObject("""
            {"changelog":["修复实时同步","优化路由配置"],"message":{"text":"不要显示 JSON"}}
        """.trimIndent()))
        assertEquals("修复实时同步\n优化路由配置", notes)
        assertFalse(notes.startsWith("["))
    }

    @Test
    fun nestedReleaseNotesObjectUsesItemsInsteadOfRawJson() {
        val notes = normalizeReleaseNotes(JSONObject("""
            {"releaseNotes":{"version":"0.10.54","items":["修复版本日志","修复更新提示"]}}
        """.trimIndent()))
        assertEquals("修复版本日志\n修复更新提示", notes)
        assertFalse(notes.contains("\"items\""))
    }
}
