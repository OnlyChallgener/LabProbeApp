package com.labprobe.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「一步连回家庭网」的唯一实现，系统磁贴和首页开关都走这里。
 *
 * 抽出来是因为两处若各写一遍，迟早一边加了检查另一边忘了 —— 尤其是「没配好/没授权」
 * 这两种必须把用户送回 WireGuard 页面才能继续的情况。
 */
internal sealed class WireGuardShortcutResult {
    data object Connected : WireGuardShortcutResult()
    data object Disconnected : WireGuardShortcutResult()
    /** 一条配置都没有，或私钥不在本机 —— 得去页面里建/导入。 */
    data object NeedsSetup : WireGuardShortcutResult()
    /** 系统 VPN 授权还没给，磁贴里弹不出这个框，要把意图交给用户点。 */
    data class NeedsPermission(val intent: Intent) : WireGuardShortcutResult()
    data class Failed(val message: String) : WireGuardShortcutResult()
}

internal suspend fun toggleWireGuardShortcut(
    context: Context,
    prefs: AppPrefs,
): WireGuardShortcutResult = withContext(Dispatchers.IO) {
    val store = WireGuardProfileStore(context.applicationContext, prefs)
    val controller = WireGuardTunnelController.get(context.applicationContext, prefs)
    if (runCatching { controller.status().running }.getOrDefault(false)) {
        runCatching { controller.stop() }
        return@withContext WireGuardShortcutResult.Disconnected
    }
    // 「上次那条」优先：用户在页面里最后用的配置，就是他想要的默认目标。
    val wanted = prefs.wireGuardActiveProfileId.ifBlank { store.load().firstOrNull()?.id.orEmpty() }
    val profile = store.load().firstOrNull { it.id == wanted }
    val privateKey = profile?.let { store.privateKey(it.id) }.orEmpty()
    if (profile == null || privateKey.isBlank()) return@withContext WireGuardShortcutResult.NeedsSetup
    when (val result = controller.start(profile, privateKey)) {
        is WireGuardStartResult.Started -> WireGuardShortcutResult.Connected
        is WireGuardStartResult.PermissionRequired -> WireGuardShortcutResult.NeedsPermission(result.intent)
        is WireGuardStartResult.Failed -> WireGuardShortcutResult.Failed(result.message)
    }
}

internal suspend fun wireGuardShortcutRunning(context: Context, prefs: AppPrefs): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            WireGuardTunnelController.get(context.applicationContext, prefs).status().running
        }.getOrDefault(false)
    }
