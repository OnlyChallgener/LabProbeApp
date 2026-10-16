package com.labprobe.app

import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRealtimeConnectionIsolationTest {
    private lateinit var prefs: AppPrefs
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "lite-realtime-test").apply { isDaemon = true }
    }
    private val arrived = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val receivedHeaders = LinkedBlockingQueue<Pair<String?, String?>>()

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("labprobe", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        RouterWorkspaceStore.resetActive()
        context.getSharedPreferences(
            routerWorkspacePreferencesName("local_lite_realtime_isolation", ""), Context.MODE_PRIVATE,
        ).edit().clear().commit()
        prefs = AppPrefs(context, "local_lite_realtime_isolation")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@LiteRealtimeConnectionIsolationTest.executor
            createContext("/api/") { exchange ->
                receivedHeaders.put(
                    exchange.requestHeaders.getFirst("Authorization") to
                        exchange.requestHeaders.getFirst("X-LabProbe-Token"),
                )
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                val body = "{\"ok\":true,\"router\":\"original\"}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        prefs.hub = "http://127.0.0.1:${server.address.port}"
        setTokenFixture("original-token")
    }

    @After
    fun cleanup() {
        release.countDown()
        if (::server.isInitialized) server.stop(0)
        executor.shutdownNow()
    }

    private fun setTokenFixture(token: String) {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            routerWorkspacePreferencesName(prefs.workspaceId, prefs.hubRoot), Context.MODE_PRIVATE,
        ).edit().putString("token", token).commit()
    }

    @Test
    fun changedConnectionDoesNotSendNewTokenToOriginalHub() {
        val api = LiteRealtimeApi(prefs)
        prefs.hub = "http://other.test:58444"
        setTokenFixture("other-token")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { api.router() }
        }
        assertTrue(receivedHeaders.isEmpty())
    }

    @Test
    fun delayedReplyIsRejectedAfterTokenChangesAndKeepsOriginalHeaders() = runBlocking {
        val api = LiteRealtimeApi(prefs)
        val response = async(Dispatchers.IO) { runCatching { api.devices() } }
        try {
            assertTrue("Original Hub did not receive the request", arrived.await(2, TimeUnit.SECONDS))
            setTokenFixture("other-token")
            release.countDown()

            assertTrue(response.await().exceptionOrNull() is IllegalStateException)
            assertEquals("Bearer original-token" to "original-token", receivedHeaders.poll())
            assertTrue(receivedHeaders.isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun changedDnsRejectsRequestBeforeSending() {
        val api = LiteRealtimeApi(prefs)
        prefs.hubDns = "1.1.1.1"

        assertThrows(IllegalStateException::class.java) {
            runBlocking { api.router() }
        }
        assertTrue(receivedHeaders.isEmpty())
    }

    @Test
    fun rebuiltClientUsesNewHubAndTokenWhileOriginalClientStaysRejected() {
        val originalApi = LiteRealtimeApi(prefs)
        val newHeaders = LinkedBlockingQueue<Pair<String?, String?>>()
        val newServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@LiteRealtimeConnectionIsolationTest.executor
            createContext("/api/") { exchange ->
                newHeaders.put(
                    exchange.requestHeaders.getFirst("Authorization") to
                        exchange.requestHeaders.getFirst("X-LabProbe-Token"),
                )
                val body = "{\"ok\":true,\"router\":\"new-hub\"}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            prefs.hub = "http://127.0.0.1:${newServer.address.port}"
            setTokenFixture("new-token")

            assertThrows(IllegalStateException::class.java) {
                runBlocking { originalApi.router() }
            }
            val newApi = LiteRealtimeApi(prefs)
            val response = runBlocking { newApi.router() }

            assertEquals("new-hub", response.optString("router"))
            assertEquals("Bearer new-token" to "new-token", newHeaders.poll())
            assertTrue(newHeaders.isEmpty())
            assertTrue(receivedHeaders.isEmpty())
        } finally {
            newServer.stop(0)
        }
    }
}
