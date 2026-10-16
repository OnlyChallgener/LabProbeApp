package com.labprobe.app

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTransportTest {
    @Test
    fun tricklingBodyStillReleasesTheCallAtItsOverallDeadline() {
        ServerSocket(0).use { server ->
            val sender = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n".toByteArray())
                        repeat(100) { out.write(' '.code); out.flush(); Thread.sleep(50L) }
                    }
                }
            }
            val base = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            val client = boundedSnapshotClient(base, deadlineMs = 500L)
            val started = System.nanoTime()
            val result = runCatching {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/api/sync/snapshot").build())
                    .execute().use { it.body!!.string() }
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(result.exceptionOrNull() is IOException)
            assertTrue("Trickling response occupied the call for $elapsed ms", elapsed < 2500L)
            sender.join(1500L)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
