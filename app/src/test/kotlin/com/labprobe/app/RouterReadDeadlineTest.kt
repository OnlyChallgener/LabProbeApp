package com.labprobe.app

import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RouterReadDeadlineTest {
    @Test
    fun tricklingDashboardResponseCannotHoldRefreshForFortyFiveSeconds() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val id = "local_router_read_deadline"
        context.getSharedPreferences("labprobe_workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(routerWorkspacePreferencesName(id, ""), Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = AppPrefs(context, id)
        val arrived = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/api/router/dashboard") { exchange ->
                arrived.countDown()
                runCatching {
                    exchange.sendResponseHeaders(200, 128)
                    exchange.responseBody.use { body ->
                        repeat(128) {
                            body.write(' '.code)
                            body.flush()
                            Thread.sleep(250L)
                        }
                    }
                }
                exchange.close()
            }
            start()
        }
        try {
            prefs.hub = "http://127.0.0.1:${server.address.port}"
            context.getSharedPreferences(routerWorkspacePreferencesName(id, prefs.hubRoot), Context.MODE_PRIVATE)
                .edit().putString("token", "deadline-test-token").commit()
            val api = HubApi(prefs)
            val started = System.nanoTime()
            val result = runCatching { api.getRouterDashboard() }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertEquals(0L, arrived.count)
            assertTrue(result.isFailure)
            assertTrue("total deadline must stop a trickling body: ${elapsedMs}ms", elapsedMs in 5000L..7500L)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
