package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialAccountEntryStatusTest {
    @Test fun partialAuthorizationIsNotComplete() {
        val snapshot = parseOfficialAccountSnapshot(JSONObject("""{"authorized":true,"state":"partial"}"""))
        assertEquals("部分授权", officialAccountEntryStatus(snapshot, false))
    }
    @Test fun failuresAndPendingAreNotReportedAsLoadingOrAuthorized() {
        assertEquals("读取失败", officialAccountEntryStatus(null, true))
        assertEquals("读取中", officialAccountEntryStatus(null, false))
        val snapshot = parseOfficialAccountSnapshot(JSONObject("""{"authorized":true,"state":"pending_unknown"}"""))
        assertEquals("退出待确认", officialAccountEntryStatus(snapshot, false))
    }
}
