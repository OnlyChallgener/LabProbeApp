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

internal data class WireGuardShortcutState(val tunnelUp: Boolean, val handshaked: Boolean)

/**
 * 隧道接口起来了不等于连上了：路由器不认这把公钥时接口照样 UP，却一个包都收不到。
 * 只有近期握过手才能对用户说「已连接」，否则就是「握手中」。
 */
internal suspend fun wireGuardShortcutState(context: Context, prefs: AppPrefs): WireGuardShortcutState =
    withContext(Dispatchers.IO) {
        val status = runCatching {
            WireGuardTunnelController.get(context.applicationContext, prefs).status()
        }.getOrNull()
        WireGuardShortcutState(
            tunnelUp = status?.running == true,
            handshaked = status?.running == true && status.latestHandshakeAt > 0L &&
                System.currentTimeMillis() - status.latestHandshakeAt < 180_000L,
        )
    }
