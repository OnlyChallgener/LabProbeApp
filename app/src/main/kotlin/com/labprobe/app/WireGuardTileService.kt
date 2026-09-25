package com.labprobe.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知栏下拉里的 WireGuard 快捷开关。
 *
 * 为什么值得单独一个入口：家庭宽带在 CGNAT 后面，能不能连回来取决于 UDP 打洞是否还保持，
 * 人在外面要的是"一步连上/断开"，而不是打开 App 翻到路由工具里再点。点磁贴属于用户主动
 * 动作，系统允许它启动 VPN。TileService 是 API 24，我们 minSdk 26，不需要版本门槛。
 *
 * 真正的连接逻辑在 [toggleWireGuardShortcut]，和首页那个开关共用同一份。
 */
class WireGuardTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        if (previouslySelectedRouterNeedsConfirmation()) {
            collapse(launchApp())
            return
        }
        val prefs = AppPrefs.current(applicationContext)
        scope.launch {
            val outcome = toggleWireGuardShortcut(applicationContext, prefs)
            render()
            when (outcome) {
                // 磁贴不是 Activity，弹不出系统 VPN 授权框：把意图交给用户点下去。
                is WireGuardShortcutResult.NeedsPermission -> collapse(outcome.intent)
                WireGuardShortcutResult.NeedsSetup -> collapse(launchApp())
                is WireGuardShortcutResult.Failed -> collapse(launchApp())
                else -> Unit
            }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        if (previouslySelectedRouterNeedsConfirmation()) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "打开 App 选择路由"
            tile.updateTile()
            return
        }
        val prefs = AppPrefs.current(applicationContext)
        scope.launch {
            val tunnelState = wireGuardShortcutState(applicationContext, prefs)
            tile.state = if (tunnelState.tunnelUp) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = when {
                tunnelState.handshaked -> "已连回家"
                tunnelState.tunnelUp -> "握手中"
                else -> "连回家庭网"
            }
            tile.updateTile()
        }
    }

    private fun previouslySelectedRouterNeedsConfirmation(): Boolean {
        if (RouterWorkspaceStore.activeWorkspaceId(applicationContext) != DEFAULT_ROUTER_WORKSPACE_ID) return false
        val hubRoot = AppPrefs(applicationContext).hubRoot
        return RouterWorkspaceStore.remembered(applicationContext, hubRoot) != DEFAULT_ROUTER_WORKSPACE_ID
    }

    private fun launchApp(): Intent? = packageManager.getLaunchIntentForPackage(packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun collapse(intent: Intent?) {
        if (intent == null) return
        runCatching { startActivityAndCollapse(intent) }
            .onFailure {
                // 个别 ROM 在磁贴里不允许直接拉起；状态已经刷新，用户至少看得见结果。
                if (it !is ActivityNotFoundException) throw it
            }
    }
}
