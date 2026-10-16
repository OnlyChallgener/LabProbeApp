package com.labprobe.app

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import okhttp3.Call

/** Cancels requests belonging to the connection the user has replaced. */
internal object HubConnectionRequests {
    private data class Session(var generation: Long = 0, val calls: MutableSet<Call> = mutableSetOf())
    private val sessions = ConcurrentHashMap<String, Session>()
    private val cancelled = java.util.WeakHashMap<Call, Boolean>()
    fun key(workspace: String, hub: String, token: String) = workspaceDigest("$workspace|$hub|$token")
    @Synchronized fun generation(key: String): Long = sessions.getOrPut(key) { Session() }.generation
    @Synchronized fun requireCurrent(key: String, generation: Long) {
        if (generation(key) != generation) throw CancellationException("连接已切换，旧请求已停止")
    }
    @Synchronized fun register(key: String, generation: Long, call: Call) {
        requireCurrent(key, generation)
        sessions.getValue(key).calls.add(call)
    }
    @Synchronized fun wasCancelled(call: Call): Boolean = cancelled.containsKey(call)
    @Synchronized fun remove(key: String, call: Call) {
        sessions[key]?.calls?.remove(call)
        cancelled.remove(call)
    }
    @Synchronized fun cancel(key: String, invalidate: Boolean = true) {
        val session = sessions.getOrPut(key) { Session() }
        if (invalidate) session.generation++
        session.calls.toList().forEach { cancelled[it] = true; it.cancel() }
        session.calls.clear()
    }
}
