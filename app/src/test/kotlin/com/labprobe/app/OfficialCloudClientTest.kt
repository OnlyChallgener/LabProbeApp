package com.labprobe.app

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialCloudClientTest {
    @Test
    fun cloudClientUsesFixedHttpsOriginAndNeverFollowsRedirects() {
        var capturedUrl = ""
        var capturedAuthorization = ""
        val http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
            val request = chain.request()
            capturedUrl = request.url.toString()
            capturedAuthorization = request.header("Authorization").orEmpty()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"code":"0","data":{"token":"cloud-token","expireTime":1893456000000}}""".toResponseBody())
                .build()
        }.build()
        val client = OfficialCloudClient(http)

        val session = client.login("owner@example.com", "password")

        assertEquals("https://shinyaapp.ruijie.com.cn/api/v1/base/homeUser/accountPasswordLogin", capturedUrl)
        assertEquals("", capturedAuthorization)
        assertEquals("cloud-token", session.token)
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
        assertTrue(session.expireTime > 0L)
    }
}
