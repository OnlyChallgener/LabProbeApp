package com.labprobe.app.feature.assistant

import com.labprobe.app.AppPrefs
import com.labprobe.app.FavoriteShortcut
import com.labprobe.app.favoriteShortcuts
import com.labprobe.app.saveFavoriteShortcuts
import java.net.URI
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Executes only explicitly allow-listed, user-confirmed changes on this device. */
class AiLocalToolExecutor(private val prefs: AppPrefs) {
    fun execute(confirmation: AiToolConfirmation): String {
        require(confirmation.executor == "app") { "这不是 APP 本地操作" }
        val expiresAt = runCatching { Instant.parse(confirmation.expiresAt).toEpochMilli() }.getOrDefault(0L)
        require(expiresAt == 0L || System.currentTimeMillis() <= expiresAt) { "确认已过期，请重新发起" }
        return when (confirmation.toolId) {
            "app.setting.update" -> updateSetting(confirmation.arguments)
            "app.favorite.add" -> addFavorite(confirmation.arguments)
            "app.favorite.remove" -> removeFavorite(confirmation.arguments)
            else -> error("APP 不支持该操作")
        }
    }

    private fun updateSetting(arguments: Map<String, String>): String {
        val setting = arguments["setting"].orEmpty()
        val value = arguments["value"].orEmpty().trim()
        return when (setting) {
            "privacyMode" -> {
                prefs.privacyMode = when (value.lowercase(Locale.ROOT)) {
                    "true", "1", "on", "开启", "打开", "开" -> true
                    "false", "0", "off", "关闭", "关" -> false
                    else -> error("隐私模式只能设置为开启或关闭")
                }
                "隐私模式已${if (prefs.privacyMode) "开启" else "关闭"}"
            }
            "favoriteNetworkMode" -> {
                prefs.favoriteNetworkMode = when (value.lowercase(Locale.ROOT)) {
                    "lan", "内网" -> "lan"
                    "wan", "外网" -> "wan"
                    else -> error("收藏默认网络只能设置为内网或外网")
                }
                "收藏默认网络已切换为${if (prefs.favoriteNetworkMode == "wan") "外网" else "内网"}"
            }
            "routerDisplayName" -> {
                require(value.isNotBlank() && value.length <= 64) { "路由器显示名称长度无效" }
                prefs.routerDisplayName = value
                "路由器显示名称已改为 $value"
            }
            else -> error("该 APP 设置不允许由 AI 修改")
        }
    }

    private fun normalizeUrl(raw: String, serviceType: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        require(value.length <= 512 && value.none { it.code < 32 }) { "收藏地址格式无效" }
        val scheme = when (serviceType.trim().uppercase(Locale.ROOT)) {
            "HTTPS" -> "https"
            "SSH" -> "ssh"
            "RDP" -> "rdp"
            "TELNET" -> "telnet"
            "TCP" -> "tcp"
            else -> "http"
        }
        val normalized = if (value.contains("://")) value else "$scheme://$value"
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(uri != null && !uri.scheme.isNullOrBlank()) { "收藏地址格式无效" }
        require(uri.scheme.lowercase(Locale.ROOT) in setOf("http", "https", "ssh", "rdp", "telnet", "tcp")) {
            "收藏地址协议不受支持"
        }
        return normalized
    }

    private fun addFavorite(arguments: Map<String, String>): String {
        val title = arguments["title"].orEmpty().trim()
        val description = arguments["description"].orEmpty().trim()
        val serviceType = arguments["serviceType"].orEmpty().trim().ifBlank { "HTTP" }
        require(title.isNotBlank() && title.length <= 64) { "收藏名称长度无效" }
        require(description.length <= 160 && serviceType.length <= 32) { "收藏内容过长" }
        val current = prefs.favoriteShortcuts()
        require(current.none { it.title.equals(title, ignoreCase = true) }) { "同名收藏已经存在" }
        val localUrl = normalizeUrl(arguments["localUrl"].orEmpty(), serviceType)
        val remoteUrl = normalizeUrl(arguments["remoteUrl"].orEmpty(), serviceType)
        require(localUrl.isNotBlank() || remoteUrl.isNotBlank()) { "收藏至少需要一个地址" }
        val item = FavoriteShortcut(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description,
            iconType = "builtin",
            iconValue = "web",
            lanUrl = localUrl,
            wanUrl = remoteUrl,
            order = current.size,
            type = "manual",
            localEndpoint = localUrl,
            remoteEndpoint = remoteUrl,
            serviceType = serviceType,
        )
        prefs.saveFavoriteShortcuts(current + item)
        return "已增加收藏：$title"
    }

    private fun removeFavorite(arguments: Map<String, String>): String {
        val target = arguments["favorite"].orEmpty().trim()
        require(target.isNotBlank()) { "缺少收藏 ID" }
        val current = prefs.favoriteShortcuts()
        val removed = current.firstOrNull { it.id == target } ?: error("收藏不存在或已删除")
        prefs.saveFavoriteShortcuts(current.filterNot { it.id == target })
        return "已删除收藏：${removed.title}"
    }
}
