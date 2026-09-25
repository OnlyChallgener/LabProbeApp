package com.labprobe.app

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Keeps device-local page caches separate for each router selected under a Hub.
 * The default workspace uses the legacy file so existing installations retain data.
 */
fun routerScopedPreferences(context: Context, legacyName: String): SharedPreferences {
    val prefs = AppPrefs.current(context)
    val name = if (prefs.workspaceId == DEFAULT_ROUTER_WORKSPACE_ID) {
        legacyName
    } else {
        val identity = prefs.hub.trimEnd('/') + "#" + prefs.workspaceId
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        legacyName + "_" + digest
    }
    return context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
}
