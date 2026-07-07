package com.labprobe.app

import android.content.ClipData
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.os.PowerManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.content.Intent
import androidx.core.content.FileProvider
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.net.URLEncoder
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.min

private const val DEFAULT_HUB = ""
private const val DEFAULT_DNS1 = "223.5.5.5"
private const val DEFAULT_DNS2 = "8.8.8.8"
private const val DEFAULT_TOKEN = ""

object AppVersion {
    const val NAME = "0.9.15"
    const val CODE = 83
    const val GITHUB = "https://github.com/OnlyChallgener/LabProbeApp"
    val CHANGELOG = listOf(
        "v0.9.15 · 设备识别 / IPv6 / WOL 自测" to listOf(
            "终端卡片新增自动设备类型识别：手机、平板、电脑、NAS、路由、电视、打印机、摄像头、音箱、IoT 等",
            "设备列表支持显示 IPv6 地址，多地址自动折叠为主 IPv6 + 数量提示，点击可复制",
            "离线 PC / NAS 等候选设备显示 WOL 唤醒按钮，手机、平板、手表默认隐藏，减少误触"
        ),
        "v0.9.15 · 自动更新闭环与 Ping 图表热修" to listOf(
            "补齐 GitHub Release 更新闭环：启动自动检查、更新卡片、立即更新、后台下载、忽略本版、小红点",
            "更新下载显示包大小、进度、网速、失败原因；网速过慢时提示建议使用代理网络",
            "Ping 图表优化 Y 轴自适应区间，减少上方大留白；底部统计栏缩小字号和间距"
        ),
        "v0.9.15 · Posix Ping 引擎自测" to listOf(
            "ICMP 优先使用 android.system.Os 无特权 SOCK_DGRAM Socket，失败自动降级系统 ping",
            "单协程 Os.poll 事件驱动，高频采样按 pacing 发包，减少 Runtime.exec 与 UI 调度误差",
            "加入 Jacobson/Karels 动态 RTO、最近 50 RTT FIFO 抖动统计，波形图 Y 轴固定不被曲线覆盖"
        ),
        "v0.9.15 · Ping 图表与调度热修" to listOf(
            "Ping 波形图 Y 轴固定随视口显示，X/Y 数字更小更贴边，图形区域更大",
            "抖动改为最近 50 个成功 RTT 的 FIFO 相邻差平均，超时不参与计算",
            "ICMP 单进程采样减少 APP 调度造成的伪超时，后台/息屏时使用 WakeLock 尽量保持测试连续"
        ),
        "v0.9.15 · Ping 波形与高频采样热修" to listOf(
            "默认 Ping 次数改为 1000，补充 200/500/2000 下拉选项",
            "ICMP 保持单进程 ping 高频采样，TCP/HTTP 使用原生 Socket/OkHttp；图表改为可横向滑动波形",
            "超时支持自动匹配间隔，统计增加抖动/超时，低延迟网关场景自适应 Y 轴避免直线"
        ),
        "v0.9.15 · Hub/工具页联动热修" to listOf(
            "工具页卡片改为图标与标题并排，描述独立一行，卡片更紧凑但不遮挡",
            "网络状态运营商跟随 IPv4/IPv6 出口变化异步重新识别，避免旧运营商残留",
            "每日概览与每日总结继续使用规范化事件统计，过滤重复离线和在线 0 秒异常事件"
        ),
        "v0.9.15 · 拖拽阴影热修" to listOf(
            "移除拖拽外层矩形阴影，修复长按卡片时方形边角和底部长横杠",
            "拖拽时仅保留圆角卡片投影，排序标签阴影同步减弱"
        ),
        "v0.9.15 · 设备事件去重热修" to listOf(
            "终端事件按 MAC / 名称快照做状态机去重，离线后继续离线不再重复显示",
            "离线在线时长优先按 offlineAt - onlineSince 固化计算，避免历史刷新后时长倒退",
            "同一设备连续离线增加冷却保护，事件列表只保留真实状态变化"
        ),
        "v0.9.15 · 测速体系/DNS/图表热修" to listOf(
            "测速拆分为峰值外网测速、局域网测速和负载延迟测试，逻辑更清晰",
            "DNS 解析增加本机 DNS 小开关，支持系统解析和本机 DNS 显示",
            "测速与漫游图表 Y 轴数字加粗放大，提升可读性"
        ),
        "v0.9.15 · IPv6/峰值测速/漫游图表测试版" to listOf(
            "新增 IPv6 可用性测试：IPv4/IPv6 出口、AAAA、IPv6 Ping、IPv6 TCP 443 与优先级判断",
            "模板测速改为峰值测速逻辑，连续稳定后自动停止；图表支持点击曲线点显示数值",
            "无线漫游增加指定 Ping 目标、丢包率、协商速率；RSSI/延迟曲线支持点选数值",
            "优化拖动卡片悬浮圆角阴影，图表轴数字改深色半粗体，减少看不清问题"
        ),
        "v0.9.15 · 个人测试功能扩展" to listOf(
            "移除一键自测入口，新增模板测速、无线漫游、MTU/分片、DNS质量和服务监控五个独立工具",
            "新增页面采用紧凑双列参数框与科技蓝图标，输入框保留足够高度和内边距，避免文字被遮挡",
            "模板测速默认只做下载吞吐自测；无线漫游显示 SSID/BSSID/RSSI、延迟、丢包与漫游事件；MTU/DNS质量/服务监控用于个人测试"
        ),
        "v0.9.15 · 左滑删除显示热修" to listOf(
            "修复 SSH 执行结果和路由追踪历史未滑动也显示删除按钮的问题",
            "删除背景仅在左滑展开时显示，默认状态不再透出红色删除按钮",
            "保留 Ping 顶部工具栏紧凑化、SSH 局部复制和追踪历史功能"
        ),
        "v0.9.15 · Ping/SSH/追踪体验热修" to listOf(
            "Ping 延迟图表顶部工具栏缩矮，标题、胶囊和历史按钮更紧凑",
            "SSH 完整输出弹窗支持局部选择复制，左滑删除状态自动回收",
            "路由追踪支持实时过程显示与 15 条历史记录，历史可展开、复制、左滑删除"
        ),
        "v0.9.15 · DNS/UDP/SSH 体验热修" to listOf(
            "DNS 解析运营商识别改为统一快速判断，IPv6 前缀会立即显示电信/联通/移动",
            "UDP 模板切换自动填入默认目标和端口，右上角新增恢复默认按钮",
            "SSH 执行结果最多保留 6 条，支持左滑单条删除、长按复制、点击查看完整输出"
        ),
        "v0.9.15 · Traceroute 与 SSH 记录热修" to listOf(
            "新增路由追踪功能，支持追踪域名解析后的 IPv4 / IPv6 路径",
            "SSH 命令下拉保存最近 6 条，执行结果最多保留 5 条并支持点击复制真实输出",
            "DNS / TCP / UDP / NAT / SSH 配置框统一修复高度和双列对齐，避免文字被遮挡"
        ),
        "v0.9.15 · 工具页与更新入口热修" to listOf(
            "版本信息卡新增检测更新按钮，支持读取 GitHub Releases 最新版本",
            "网络状态 IPv4/IPv6 胶囊可跳转 DNS 解析，运营商增加本地快速推断",
            "DNS/端口/UDP/SSH 页面统一为科技蓝双列紧凑参数风格"
        ),
        "v0.9.15 · 网络/NAT重构热修" to listOf(
            "工具页网络状态卡改为 6 项：IPv4 出口、IPv6、本地 IP、运营商、优先级、NAT 类型",
            "NAT 检测拆分 RFC5780 行为发现与 RFC3489 TEST 1-4，支持服务器列表和历史记录",
            "端口测试与 UDP 探测拆分独立页面，避免两个入口共用同一逻辑"
        ),
        "v0.9.15 · 固定签名热修" to listOf(
            "GitHub Actions 支持固定 keystore 签名，避免每次构建签名变化导致无法覆盖安装",
            "debug 与 release 构建可共用同一把 Labprobe 上传签名，只要 versionCode 递增即可直接升级",
            "未配置签名密钥时继续 fallback 到默认 debug 签名，并在 Actions 中明确警告"
        ),
        "v0.9.15 · NAT 行为检测与工具页重构" to listOf(
            "新增 NAT 行为检测页面，按 RFC3489 传统 TEST 1/2/3/4 展示基础映射、换地址回包、换端口回包和映射一致性",
            "STUN 解析支持 MAPPED-ADDRESS、XOR-MAPPED-ADDRESS、CHANGED-ADDRESS、OTHER-ADDRESS，结果同时显示映射行为、过滤行为与传统分类",
            "工具页改为 One UI 2列磁贴布局，移除 整张卡片可直接进入 提示，并加入网络状态概览卡",
            "NAT 结果明确区分基础 STUN 与增强 STUN，服务器不支持 Changed/Other Address 时不再误判完整 NAT 类型"
        ),
        "v0.9.15 · 延迟测试视觉与稳定性热修" to listOf(
            "延迟测试页面标题再缩小，图表卡片标题改为 延迟，减少拥挤和省略号",
            "停止按钮统一科技蓝，参数与图表视觉继续贴近 One UI 卡片风格",
            "Y 轴固定最多 5 个点位，低延迟场景按 0/30/60/90/120 展示，X 轴继续使用真实时间",
            "ICMP 高频采样增加进程取消回收与 IPv6 ping 命令回退，提升测试稳定性"
        ),
        "v0.9.15 · 延迟测试 One UI 强化" to listOf(
            "Ping 页面升级为延迟测试：支持 ICMP / TCP Connect / HTTP HEAD / HTTP GET",
            "新增 IPv6 优先、IPv4 优先、仅 IPv6、仅 IPv4 与 DNS A/AAAA 策略",
            "所有参数框加入科技蓝小图标，页面标题与卡片高度继续收敛",
            "历史记录弹窗保存最近 10 次测试汇总，支持折叠查看与空间占用显示",
            "图表固定 1 秒聚合展示，原始数据实时采集，X 轴继续使用真实耗时"
        ),
        "v0.9.15 · Ping 真实耗时热修" to listOf(
            "Ping 曲线 X 轴改为真实墙钟耗时，不再按 次数 × 设定间隔 假算 9 秒",
            "30ms 高频采样优先使用单进程 ping -i 连续采样，避免每次启动 ping 进程造成 50 秒级拖慢",
            "设备不支持 ping -i 高频参数时自动回退逐次 ping，但仍按真实耗时绘制与统计",
            "日志增加 @实际秒数，底部统计增加耗时，方便和系统秒表核对"
        ),
        "v0.9.15 · 交互顺滑与每日同步" to listOf(
            "首页今日概览改为优先读取每日总结接口，和记录页每日总结保持同一天数据",
            "首页卡片改为整张卡可点：终端 / VPN / 出口 / 今日概览无需再点小按钮",
            "长按拖动改为先浮起、跟手移动，松手后再排序，避免中途突然跳位",
            "记录页左滑删除加入回弹和收起动画，删除前有短暂消隐过渡",
            "设置与输入框继续白底化，减少灰底感"
        ),
        "v0.9.14 · 编译修复" to listOf(
            "修复 graphicsLayer 导入错误导致的 Kotlin 编译失败",
            "保留 v0.9.13 的顶部白色导航、隐私模式和首页拖拽排序",
            "Hub 仍继续使用 v0.7.2"
        ),
        "v0.9.13 · One UI 精修与隐私模式" to listOf(
            "顶部导航白底化，设置与下拉框继续统一 One UI 质感",
            "VPN/STUN 钥匙图标可切换隐私模式，截图时隐藏公网地址",
            "首页今日概览只统计当天事件，避免继续显示昨天数据",
            "首页卡片支持长按浮起拖动排序，松手后自动保存顺序",
            "二级页面继续收敛为白卡、轻阴影、小色块点缀"
        ),
        "v0.9.12 · 编译修复与分体刷新按钮" to listOf(
            "修复 Kotlin 编译失败：移除不兼容的 using 动画扩展",
            "右上角刷新按钮改为分体按钮：左侧立即刷新，右侧小三角打开刷新间隔",
            "刷新下拉保留手动 / 3S / 10S / 20S，白底圆角菜单风格统一"
        ),
        "v0.9.11 · One UI 全页面统一与轻动画" to listOf(
            "终端、工具、记录、我的、每日总结统一使用浅色渐变背景与白色大圆角卡片",
            "页面切换改为 120ms 轻淡入淡出，取消大幅滑动和弹跳",
            "工具入口、事件流、设置与关于卡片统一 One UI 质感",
            "保留顶部胶囊导航在标题下方，不遮挡状态栏"
        ),
        "v0.9.10 · 顶部导航与刷新菜单修复" to listOf(
            "顶部图标导航回到标题下方，不再遮挡系统状态栏",
            "刷新按钮改为 One UI 圆角下拉菜单：立即刷新 / 手动 / 3S / 10S / 20S",
            "顶部导航第二个图标改为路由器图标",
            "主页面导航统一放在页面标题和内容卡片之间，保持 Samsung Health 式结构"
        ),
        "v0.9.9 · One UI 大统一版" to listOf(
            "顶部图标胶囊导航替代底部栏",
            "首页卡片联动跳转：终端、Hub、出口 Ping",
            "版本号弹窗显示最近版本，并支持跳转 GitHub",
            "关于页统一读取当前版本，避免版本号错乱",
            "全页面收敛为白色大圆角卡片，减少色块重叠"
        ),
        "v0.9.8 · One UI 首页预览" to listOf(
            "首页改成浅色渐变背景与仪表盘卡片",
            "新增网络健康得分、终端在线、VPN/STUN 小卡",
            "保留 OpenVPN / Lucky 首页兜底显示逻辑"
        ),
        "v0.9.7 · 地址状态修复" to listOf(
            "修复记录和总结有 OpenVPN / Lucky，但首页不显示",
            "从 Hub 当前状态和最近事件补齐 VPN/STUN 地址"
        ),
        "v0.9.6 · 事件与左滑修复" to listOf(
            "修复记录页左滑删除状态继承，避免误删",
            "Webhook 地址按服务名显示，不再固定 Lucky"
        )
    )
}

private val LabTypography: Typography = run {
    val t = Typography()
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = FontFamily.SansSerif),
        displayMedium = t.displayMedium.copy(fontFamily = FontFamily.SansSerif),
        displaySmall = t.displaySmall.copy(fontFamily = FontFamily.SansSerif),
        headlineLarge = t.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.SansSerif),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.SansSerif),
        titleMedium = t.titleMedium.copy(fontFamily = FontFamily.SansSerif),
        titleSmall = t.titleSmall.copy(fontFamily = FontFamily.SansSerif),
        bodyLarge = t.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = t.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = t.bodySmall.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = t.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        labelMedium = t.labelMedium.copy(fontFamily = FontFamily.SansSerif),
        labelSmall = t.labelSmall.copy(fontFamily = FontFamily.SansSerif)
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LabProbeApp(AppPrefs(this)) }
    }
}

class AppPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("labprobe", Context.MODE_PRIVATE)
    var hub: String get() = sp.getString("hub", DEFAULT_HUB) ?: DEFAULT_HUB
        set(v) = sp.edit().putString("hub", v.trim().trimEnd('/')).apply()
    var token: String get() = sp.getString("token", DEFAULT_TOKEN) ?: DEFAULT_TOKEN
        set(v) = sp.edit().putString("token", v.trim()).apply()
    var hubDns: String get() = sp.getString("hub_dns", DEFAULT_DNS1) ?: DEFAULT_DNS1
        set(v) = sp.edit().putString("hub_dns", v.trim()).apply()
    var dark: Boolean get() = sp.getBoolean("dark", false)
        set(v) = sp.edit().putBoolean("dark", v).apply()
    var autoRefresh: String get() = sp.getString("auto_refresh", "手动") ?: "手动"
        set(v) = sp.edit().putString("auto_refresh", v).apply()
    var ignoredUpdateCode: Int get() = sp.getInt("ignored_update_code", 0)
        set(v) = sp.edit().putInt("ignored_update_code", v).apply()
    var lastUpdateCheckAt: Long get() = sp.getLong("last_update_check_at", 0L)
        set(v) = sp.edit().putLong("last_update_check_at", v).apply()

    var homeOrder: String get() = sp.getString("home_order", "score,mini,exit,vpn,devices,today") ?: "score,mini,exit,vpn,devices,today"
        set(v) = sp.edit().putString("home_order", v).apply()
    var privacyMode: Boolean get() = sp.getBoolean("privacy_mode", false)
        set(v) = sp.edit().putBoolean("privacy_mode", v).apply()

    private fun historyLimit(key: String): Int = if (key.contains("ssh_cmd", true)) 6 else 3
    private fun getHistory(key: String): List<String> = (sp.getString(key, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotBlank() }.take(historyLimit(key))
    private fun putHistory(key: String, items: List<String>) { sp.edit().putString(key, items.distinct().take(historyLimit(key)).joinToString("\n")).apply() }
    fun history(key: String): List<String> = getHistory("history_" + key)
    fun addHistory(key: String, value: String) { val v = value.trim(); if (v.isNotBlank()) putHistory("history_" + key, listOf(v) + getHistory("history_" + key).filter { it != v }) }
    fun removeHistory(key: String, value: String) { putHistory("history_" + key, getHistory("history_" + key).filter { it != value }) }

    fun dnsQueryHistory(): List<DnsQueryHistory> {
        val arr = runCatching { JSONArray(sp.getString("dns_query_history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DnsQueryHistory(
                domain = o.optString("domain"),
                time = o.optString("time"),
                summary = o.optString("summary"),
                signature = o.optString("signature")
            )
        }.filter { it.domain.isNotBlank() && it.summary.isNotBlank() }.take(10)
    }

    fun addDnsQueryHistory(domain: String, records: List<DnsRecord>) {
        val d = domain.trim()
        if (d.isBlank() || records.isEmpty()) return
        val valid = records.filter { it.value.isNotBlank() && !it.value.startsWith("无记录") }
        if (valid.isEmpty()) return
        val signature = d + "|" + valid.map { it.type + ":" + it.value + ":" + it.operator }.distinct().sorted().joinToString(",")
        val old = dnsQueryHistory()
        if (old.any { it.signature == signature }) return
        val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        fun operatorText(op: String): String = op.replace(" · ", " ").trim()
        fun line(type: String): String? {
            val items = valid.filter { it.type == type }
            if (items.isEmpty()) return null
            return type + " " + items.joinToString(" / ") { r ->
                r.value + if (r.operator.isNotBlank()) " " + operatorText(r.operator) else ""
            }
        }
        val summary = listOfNotNull(line("A"), line("AAAA")).joinToString("\n")
        if (summary.isBlank()) return
        val arr = JSONArray()
        (listOf(DnsQueryHistory(d, now, summary, signature)) + old).take(10).forEach { h ->
            arr.put(JSONObject().put("domain", h.domain).put("time", h.time).put("summary", h.summary).put("signature", h.signature))
        }
        sp.edit().putString("dns_query_history", arr.toString()).apply()
    }

    fun clearDnsQueryHistory() { sp.edit().putString("dns_query_history", "[]").apply() }

    var cacheStatus: String get() = sp.getString("cache_status", "") ?: ""
        set(v) = sp.edit().putString("cache_status", v).apply()
    var cacheDevices: String get() = sp.getString("cache_devices", "") ?: ""
        set(v) = sp.edit().putString("cache_devices", v).apply()
    var cacheOnlineDevices: String get() = sp.getString("cache_online_devices", "") ?: ""
        set(v) = sp.edit().putString("cache_online_devices", v).apply()
    var cacheEvents: String get() = sp.getString("cache_events", "") ?: ""
        set(v) = sp.edit().putString("cache_events", v).apply()
    var wolDevicesJson: String get() = sp.getString("wol_devices_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("wol_devices_v1", v).apply()
    var deviceOverridesJson: String get() = sp.getString("device_overrides_v1", "[]") ?: "[]"
        set(v) = sp.edit().putString("device_overrides_v1", v).apply()
    var lastRefresh: String get() = sp.getString("last_refresh", "") ?: ""
        set(v) = sp.edit().putString("last_refresh", v).apply()

    var pingHost: String get() = sp.getString("ping_host", "223.5.5.5") ?: "223.5.5.5"
        set(v) = sp.edit().putString("ping_host", v).apply()
    var pingCount: String get() = sp.getString("ping_count", "1000") ?: "1000"
        set(v) = sp.edit().putString("ping_count", v).apply()
    var pingInterval: String get() = sp.getString("ping_interval", "500") ?: "500"
        set(v) = sp.edit().putString("ping_interval", v).apply()
    var pingTimeout: String get() = sp.getString("ping_timeout", "自动") ?: "自动"
        set(v) = sp.edit().putString("ping_timeout", v).apply()
    var pingProtocol: String get() = sp.getString("ping_protocol", "ICMP") ?: "ICMP"
        set(v) = sp.edit().putString("ping_protocol", v).apply()
    var pingIpMode: String get() = sp.getString("ping_ip_mode", "IPv6优先") ?: "IPv6优先"
        set(v) = sp.edit().putString("ping_ip_mode", v).apply()
    var pingDnsMode: String get() = sp.getString("ping_dns_mode", "优先AAAA") ?: "优先AAAA"
        set(v) = sp.edit().putString("ping_dns_mode", v).apply()
    var pingPort: String get() = sp.getString("ping_port", "80") ?: "80"
        set(v) = sp.edit().putString("ping_port", v).apply()

    fun pingHistory(): List<PingHistoryEntry> {
        val raw = sp.getString("ping_history_v2", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PingHistoryEntry(
                id = o.optLong("id"),
                time = o.optString("time"),
                target = o.optString("target"),
                protocol = o.optString("protocol"),
                ipMode = o.optString("ipMode"),
                dnsMode = o.optString("dnsMode"),
                resolvedIp = o.optString("resolvedIp"),
                count = o.optInt("count"),
                sent = o.optInt("sent"),
                ok = o.optInt("ok"),
                loss = o.optInt("loss"),
                avg = if (o.has("avg") && !o.isNull("avg")) o.optInt("avg") else null,
                max = if (o.has("max") && !o.isNull("max")) o.optInt("max") else null,
                min = if (o.has("min") && !o.isNull("min")) o.optInt("min") else null,
                elapsedMs = o.optLong("elapsedMs"),
                rate = o.optDouble("rate"),
                bytes = raw.toByteArray().size
            )
        }.filter { it.target.isNotBlank() }.take(10)
    }

    fun pingHistoryBytes(): Int = (sp.getString("ping_history_v2", "[]") ?: "[]").toByteArray().size

    fun addPingHistory(entry: PingHistoryEntry) {
        val arr = JSONArray()
        (listOf(entry) + pingHistory()).take(10).forEach { h ->
            arr.put(JSONObject()
                .put("id", h.id)
                .put("time", h.time)
                .put("target", h.target)
                .put("protocol", h.protocol)
                .put("ipMode", h.ipMode)
                .put("dnsMode", h.dnsMode)
                .put("resolvedIp", h.resolvedIp)
                .put("count", h.count)
                .put("sent", h.sent)
                .put("ok", h.ok)
                .put("loss", h.loss)
                .put("avg", h.avg ?: JSONObject.NULL)
                .put("max", h.max ?: JSONObject.NULL)
                .put("min", h.min ?: JSONObject.NULL)
                .put("elapsedMs", h.elapsedMs)
                .put("rate", h.rate)
            )
        }
        sp.edit().putString("ping_history_v2", arr.toString()).apply()
    }

    fun clearPingHistory() { sp.edit().putString("ping_history_v2", "[]").apply() }

    var dnsDomain: String get() = sp.getString("dns_domain", "net86.dynv6.net") ?: "net86.dynv6.net"
        set(v) = sp.edit().putString("dns_domain", v).apply()
    var dns1: String get() = sp.getString("dns1", DEFAULT_DNS1) ?: DEFAULT_DNS1
        set(v) = sp.edit().putString("dns1", v).apply()
    var dns2: String get() = sp.getString("dns2", DEFAULT_DNS2) ?: DEFAULT_DNS2
        set(v) = sp.edit().putString("dns2", v).apply()
    var dnsRecord: String get() = sp.getString("dns_record", "ALL") ?: "ALL"
        set(v) = sp.edit().putString("dns_record", v).apply()
    var dnsUseSystem: Boolean get() = sp.getBoolean("dns_use_system", false)
        set(v) = sp.edit().putBoolean("dns_use_system", v).apply()

    var tcpHost: String get() = sp.getString("tcp_host", "192.168.5.46") ?: "192.168.5.46"
        set(v) = sp.edit().putString("tcp_host", v).apply()
    var tcpPort: String get() = sp.getString("tcp_port", "58443") ?: "58443"
        set(v) = sp.edit().putString("tcp_port", v).apply()
    var tcpTimeout: String get() = sp.getString("tcp_timeout", "1000") ?: "1000"
        set(v) = sp.edit().putString("tcp_timeout", v).apply()
    var portProtocol: String get() = sp.getString("port_protocol", "TCP") ?: "TCP"
        set(v) = sp.edit().putString("port_protocol", v).apply()

    var natServer: String get() = sp.getString("nat_server", "stun.l.google.com") ?: "stun.l.google.com"
        set(v) = sp.edit().putString("nat_server", v.trim()).apply()
    var natPort: String get() = sp.getString("nat_port", "19302") ?: "19302"
        set(v) = sp.edit().putString("nat_port", v.trim()).apply()
    var natTimeout: String get() = sp.getString("nat_timeout", "1200") ?: "1200"
        set(v) = sp.edit().putString("nat_timeout", v.trim()).apply()
    var natIpMode: String get() = sp.getString("nat_ip_mode", "自动") ?: "自动"
        set(v) = sp.edit().putString("nat_ip_mode", v).apply()
    var natMode: String get() = sp.getString("nat_mode", "RFC5780") ?: "RFC5780"
        set(v) = sp.edit().putString("nat_mode", v).apply()

    var udpHost: String get() = sp.getString("udp_host", "stun.voip.aebc.com") ?: "stun.voip.aebc.com"
        set(v) = sp.edit().putString("udp_host", v.trim()).apply()
    var udpPort: String get() = sp.getString("udp_port", "3478") ?: "3478"
        set(v) = sp.edit().putString("udp_port", v.trim()).apply()
    var udpTimeout: String get() = sp.getString("udp_timeout", "1000") ?: "1000"
        set(v) = sp.edit().putString("udp_timeout", v.trim()).apply()
    var udpTemplate: String get() = sp.getString("udp_template", "STUN Binding") ?: "STUN Binding"
        set(v) = sp.edit().putString("udp_template", v).apply()
    var udpIpMode: String get() = sp.getString("udp_ip_mode", "自动") ?: "自动"
        set(v) = sp.edit().putString("udp_ip_mode", v).apply()

    fun natServers(mode: String): List<StunServerItem> {
        val key = if (mode == "RFC3489") "nat_servers_3489" else "nat_servers_5780"
        val fallback = if (mode == "RFC3489") listOf(StunServerItem("stun.miwifi.com", 3478)) else listOf(StunServerItem("stun.voip.aebc.com", 3478))
        val raw = sp.getString(key, null) ?: return fallback
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val h = o.optString("host").trim()
                val p = o.optInt("port", 3478).coerceIn(1, 65535)
                if (h.isBlank()) null else StunServerItem(h, p)
            }.ifEmpty { fallback }
        }.getOrDefault(fallback).take(10)
    }

    fun saveNatServers(mode: String, list: List<StunServerItem>) {
        val key = if (mode == "RFC3489") "nat_servers_3489" else "nat_servers_5780"
        val arr = JSONArray()
        list.distinctBy { it.host.lowercase(Locale.getDefault()) + ":" + it.port }.take(10).forEach { s ->
            arr.put(JSONObject().put("host", s.host).put("port", s.port))
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    fun addNatServer(mode: String, host: String, port: Int) {
        val h = host.trim()
        if (h.isBlank()) return
        saveNatServers(mode, listOf(StunServerItem(h, port.coerceIn(1, 65535))) + natServers(mode).filterNot { it.host.equals(h, true) && it.port == port })
    }

    fun deleteNatServer(mode: String, item: StunServerItem) {
        saveNatServers(mode, natServers(mode).filterNot { it.host.equals(item.host, true) && it.port == item.port })
    }

    fun resetNatServers(mode: String) {
        val key = if (mode == "RFC3489") "nat_servers_3489" else "nat_servers_5780"
        sp.edit().remove(key).apply()
    }

    fun natHistory(): List<NatHistoryEntry> {
        val raw = sp.getString("nat_history_v2", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                NatHistoryEntry(
                    id = o.optLong("id", i.toLong()),
                    time = o.optString("time"),
                    mode = o.optString("mode"),
                    server = o.optString("server"),
                    classicType = o.optString("classicType", "未知"),
                    confidence = o.optString("confidence", "低"),
                    mapped = o.optString("mapped"),
                    local = o.optString("local"),
                    ipv6 = o.optString("ipv6"),
                    operator = o.optString("operator"),
                    priority = o.optString("priority"),
                    elapsedMs = o.optLong("elapsedMs", 0L),
                    summary = o.optString("summary")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addNatHistory(entry: NatHistoryEntry) {
        val arr = JSONArray()
        (listOf(entry) + natHistory()).distinctBy { it.id }.take(50).forEach { h ->
            arr.put(JSONObject()
                .put("id", h.id)
                .put("time", h.time)
                .put("mode", h.mode)
                .put("server", h.server)
                .put("classicType", h.classicType)
                .put("confidence", h.confidence)
                .put("mapped", h.mapped)
                .put("local", h.local)
                .put("ipv6", h.ipv6)
                .put("operator", h.operator)
                .put("priority", h.priority)
                .put("elapsedMs", h.elapsedMs)
                .put("summary", h.summary))
        }
        sp.edit().putString("nat_history_v2", arr.toString()).apply()
    }

    fun deleteNatHistory(id: Long) {
        val arr = JSONArray()
        natHistory().filterNot { it.id == id }.forEach { h ->
            arr.put(JSONObject()
                .put("id", h.id).put("time", h.time).put("mode", h.mode).put("server", h.server)
                .put("classicType", h.classicType).put("confidence", h.confidence).put("mapped", h.mapped)
                .put("local", h.local).put("ipv6", h.ipv6).put("operator", h.operator).put("priority", h.priority)
                .put("elapsedMs", h.elapsedMs).put("summary", h.summary))
        }
        sp.edit().putString("nat_history_v2", arr.toString()).apply()
    }

    fun clearNatHistory() { sp.edit().putString("nat_history_v2", "[]").apply() }

    var sshHost: String get() = sp.getString("ssh_host", "192.168.5.1") ?: "192.168.5.1"
        set(v) = sp.edit().putString("ssh_host", v).apply()
    var sshPort: String get() = sp.getString("ssh_port", "54133") ?: "54133"
        set(v) = sp.edit().putString("ssh_port", v).apply()
    var sshUser: String get() = sp.getString("ssh_user", "root") ?: "root"
        set(v) = sp.edit().putString("ssh_user", v).apply()
    var sshSavePass: Boolean get() = sp.getBoolean("ssh_save_pass", false)
        set(v) = sp.edit().putBoolean("ssh_save_pass", v).apply()
    var sshPassword: String get() = sp.getString("ssh_password", "") ?: ""
        set(v) = sp.edit().putString("ssh_password", v).apply()
    var sshCommand: String get() = sp.getString("ssh_cmd", "ip -6 neigh show") ?: "ip -6 neigh show"
        set(v) = sp.edit().putString("ssh_cmd", v).apply()

    fun sshResults(): List<SshResultEntry> {
        val raw = sp.getString("ssh_results_v1", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SshResultEntry(
                    id = o.optLong("id", i.toLong()),
                    time = o.optString("time"),
                    host = o.optString("host"),
                    command = o.optString("command"),
                    output = o.optString("output")
                )
            }
        }.getOrDefault(emptyList()).take(6)
    }

    fun addSshResult(entry: SshResultEntry) {
        val arr = JSONArray()
        (listOf(entry) + sshResults()).distinctBy { it.id }.take(6).forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("host", r.host)
                .put("command", r.command)
                .put("output", r.output))
        }
        sp.edit().putString("ssh_results_v1", arr.toString()).apply()
    }

    fun deleteSshResult(id: Long) {
        val arr = JSONArray()
        sshResults().filterNot { it.id == id }.forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("host", r.host)
                .put("command", r.command)
                .put("output", r.output))
        }
        sp.edit().putString("ssh_results_v1", arr.toString()).apply()
    }

    fun clearSshResults() { sp.edit().putString("ssh_results_v1", "[]").apply() }

    var traceHost: String get() = sp.getString("trace_host", "net86.dynv6.net") ?: "net86.dynv6.net"
        set(v) = sp.edit().putString("trace_host", v.trim()).apply()
    var traceMaxHops: String get() = sp.getString("trace_max_hops", "16") ?: "16"
        set(v) = sp.edit().putString("trace_max_hops", v.trim()).apply()
    var traceTimeout: String get() = sp.getString("trace_timeout", "1200") ?: "1200"
        set(v) = sp.edit().putString("trace_timeout", v.trim()).apply()
    var traceIpMode: String get() = sp.getString("trace_ip_mode", "IPv6优先") ?: "IPv6优先"
        set(v) = sp.edit().putString("trace_ip_mode", v).apply()

    fun traceHistory(): List<TraceHistoryEntry> {
        val raw = sp.getString("trace_history_v1", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TraceHistoryEntry(
                    id = o.optLong("id", i.toLong()),
                    time = o.optString("time"),
                    host = o.optString("host"),
                    ipMode = o.optString("ipMode"),
                    hops = o.optInt("hops", 0),
                    status = o.optString("status"),
                    output = o.optString("output")
                )
            }
        }.getOrDefault(emptyList()).take(15)
    }

    fun addTraceHistory(entry: TraceHistoryEntry) {
        val arr = JSONArray()
        (listOf(entry) + traceHistory()).distinctBy { it.id }.take(15).forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("host", r.host)
                .put("ipMode", r.ipMode)
                .put("hops", r.hops)
                .put("status", r.status)
                .put("output", r.output))
        }
        sp.edit().putString("trace_history_v1", arr.toString()).apply()
    }

    fun deleteTraceHistory(id: Long) {
        val arr = JSONArray()
        traceHistory().filterNot { it.id == id }.forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("host", r.host)
                .put("ipMode", r.ipMode)
                .put("hops", r.hops)
                .put("status", r.status)
                .put("output", r.output))
        }
        sp.edit().putString("trace_history_v1", arr.toString()).apply()
    }

    fun clearTraceHistory() { sp.edit().putString("trace_history_v1", "[]").apply() }
}

data class DnsRecord(val value: String, val type: String, val source: String, val operator: String = "")
data class DnsQueryHistory(val domain: String, val time: String, val summary: String, val signature: String)
data class SshResultEntry(val id: Long, val time: String, val host: String, val command: String, val output: String)
data class TraceHistoryEntry(val id: Long, val time: String, val host: String, val ipMode: String, val hops: Int, val status: String, val output: String)
data class PingPoint(val index: Int, val ms: Int?, val text: String, val elapsedMs: Long)
data class PingRunResult(val points: List<PingPoint>, val elapsedMs: Long, val mode: String, val protocol: String = "ICMP", val resolvedIp: String = "")
data class PingBucket(val startMs: Long, val avgMs: Int?, val peakMs: Int?, val hasLoss: Boolean, val sampleCount: Int)
data class PingHistoryEntry(
    val id: Long,
    val time: String,
    val target: String,
    val protocol: String,
    val ipMode: String,
    val dnsMode: String,
    val resolvedIp: String,
    val count: Int,
    val sent: Int,
    val ok: Int,
    val loss: Int,
    val avg: Int?,
    val max: Int?,
    val min: Int?,
    val elapsedMs: Long,
    val rate: Double,
    val bytes: Int = 0
)

data class NetworkBrief(val transport: String, val hasV4: Boolean, val hasV6: Boolean)
data class NetworkProfile(
    val ipv4Exit: String,
    val ipv6Address: String,
    val natType: String,
    val operator: String,
    val localIp: String,
    val priority: String
)
data class StunServerItem(val host: String, val port: Int) { override fun toString(): String = "$host:$port" }
data class NatHistoryEntry(
    val id: Long,
    val time: String,
    val mode: String,
    val server: String,
    val classicType: String,
    val confidence: String,
    val mapped: String,
    val local: String,
    val ipv6: String,
    val operator: String,
    val priority: String,
    val elapsedMs: Long,
    val summary: String
)
data class StunEndpoint(val address: String, val port: Int) { override fun toString(): String = "$address:$port" }
data class StunResponse(
    val mapped: StunEndpoint?,
    val changed: StunEndpoint?,
    val other: StunEndpoint?,
    val source: StunEndpoint,
    val elapsedMs: Long
)
data class NatStep(val title: String, val status: String, val detail: String, val success: Boolean?)
data class NatRunResult(
    val title: String,
    val summary: String,
    val mapped: StunEndpoint?,
    val local: StunEndpoint?,
    val other: StunEndpoint?,
    val mappingBehavior: String,
    val filteringBehavior: String,
    val classicType: String,
    val confidence: String,
    val steps: List<NatStep>,
    val elapsedMs: Long,
    val serverUsed: String? = null
)

class AppState(private val prefs: AppPrefs) {
    var status by mutableStateOf<JSONObject?>(prefs.cacheStatus.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() })
    var deviceOverrides by mutableStateOf(parseDeviceOverrides(prefs.deviceOverridesJson))
    var devices by mutableStateOf(applyDeviceOverrides(parseDeviceArray(prefs.cacheDevices), deviceOverrides))
    var onlineDevices by mutableStateOf(applyDeviceOverrides(parseDeviceArray(prefs.cacheOnlineDevices), deviceOverrides))
    var events by mutableStateOf(normalizeDeviceEvents(parseEvents(prefs.cacheEvents)))
    var wolDevices by mutableStateOf(parseWolDevices(prefs.wolDevicesJson))
    var loading by mutableStateOf(false)
    var hubConnected by mutableStateOf(prefs.lastRefresh.isNotBlank() && prefs.hub.isNotBlank())
    var message by mutableStateOf(if (prefs.lastRefresh.isBlank()) "等待刷新" else "最后成功：${prefs.lastRefresh}")

    suspend fun refreshAll(forceHealth: Boolean = false) {
        if (prefs.hub.isBlank()) {
            message = "Hub 地址为空，请先输入"
            return
        }
        loading = true
        try {
            val api = HubApi(prefs)
            if (!hubConnected || forceHealth) {
                message = "正在连接 Hub，最多尝试 3 次..."
                api.healthWithRetry(3)
                hubConnected = true
            }
            message = "正在刷新数据..."
            fetchData(api)
        } catch (first: Exception) {
            if (hubConnected) {
                message = "刷新失败，正在重连 Hub..."
                try {
                    val api = HubApi(prefs)
                    api.healthWithRetry(3)
                    hubConnected = true
                    message = "重连成功，正在刷新数据..."
                    fetchData(api)
                } catch (second: Exception) {
                    hubConnected = false
                    message = "连接失败，保留缓存：${second.message}"
                }
            } else {
                hubConnected = false
                message = "连接失败，保留缓存：${first.message}"
            }
        } finally {
            loading = false
        }
    }

    private suspend fun fetchData(api: HubApi) {
        val stRoot = api.getStatus()
        val devWatched = api.getDevices(false)
        val devOnline = api.getDevices(true)
        val evs = normalizeDeviceEvents(api.getEvents())
        status = stRoot
        val devOnlineWithIpv6 = applyDeviceOverrides(mergeIpv6NeighborsFromStatus(stRoot, devOnline), deviceOverrides)
        val devWatchedWithIpv6 = applyDeviceOverrides(mergeIpv6NeighborsFromStatus(stRoot, devWatched), deviceOverrides)
        val mergedDevices = applyDeviceOverrides(mergeDeviceCache(devices, devWatchedWithIpv6), deviceOverrides)
        devices = mergedDevices
        onlineDevices = devOnlineWithIpv6
        events = evs
        prefs.cacheStatus = stRoot.toString()
        // 保存合并后的关注终端缓存，而不是只保存 Hub 本次返回值。
        // 这样离线设备在 Hub 短时间字段缺失、APP 重启后，仍能保留最后 IP / SSID / 频段 / 速率 / 信号。
        prefs.cacheDevices = JSONArray(mergedDevices.map { it.toJson() }).toString()
        prefs.cacheOnlineDevices = JSONArray(devOnlineWithIpv6.map { it.toJson() }).toString()
        prefs.cacheEvents = JSONArray(evs.map { it.toJson() }).toString()
        prefs.lastRefresh = nowClock()
        hubConnected = true
        message = "刷新成功：${prefs.lastRefresh}"
    }

    fun markHubChanged() {
        hubConnected = false
        message = "Hub 设置已变更，请测试或刷新"
    }

    fun saveDeviceOverride(mac: String, remark: String, typeInput: String, wolEnabledOverride: Boolean?) {
        val clean = cleanMac(mac)
        if (!isValidMac(clean)) {
            message = "MAC 地址无效，未保存设备备注"
            return
        }
        val normalizedType = normalizeDeviceTypeToken(typeInput).ifBlank { typeInput.trim() }
        val item = DeviceOverrideConfig(
            mac = clean,
            remark = remark.trim(),
            typeId = normalizedType,
            wolEnabledOverride = wolEnabledOverride,
            updatedAt = System.currentTimeMillis()
        )
        deviceOverrides = (listOf(item) + deviceOverrides.filterNot { it.mac.equals(clean, ignoreCase = true) }).take(200)
        prefs.deviceOverridesJson = deviceOverridesToJson(deviceOverrides)
        devices = applyDeviceOverrides(devices, deviceOverrides)
        onlineDevices = applyDeviceOverrides(onlineDevices, deviceOverrides)
        message = "已保存设备备注：${item.remark.ifBlank { clean }}"
    }

    suspend fun wakeDevice(ctx: Context, device: DeviceItem): String {
        val mac = device.mac
        if (!isValidMac(mac)) throw RuntimeException("MAC 地址无效，无法 WOL")
        var lastError: Throwable? = null
        if (prefs.hub.isNotBlank()) {
            runCatching {
                val resp = HubApi(prefs).sendWol(mac)
                val msg = resp.optString("message").ifBlank { "Hub 已发送 WOL" }
                message = msg
                return msg
            }.onFailure { lastError = it }
        }
        val sent = sendWakeOnLanLocal(ctx, mac)
        val msg = if (sent > 0) "已发送 WOL 魔术包 · $sent 个广播地址" else "WOL 发送失败"
        message = msg
        if (sent <= 0) throw RuntimeException(lastError?.message ?: msg)
        return msg
    }

    suspend fun wakeMac(ctx: Context, mac: String): String {
        val clean = cleanMac(mac)
        if (!isValidMac(clean)) throw RuntimeException("MAC 地址无效，无法 WOL")
        val device = (onlineDevices + devices).firstOrNull { it.mac.equals(clean, ignoreCase = true) } ?: DeviceItem(
            name = clean,
            mac = clean,
            online = false,
            ip = "",
            ssid = "",
            band = "",
            rssi = "",
            rxrate = "",
            onlineSince = "",
            offlineAt = "",
            onlineDurationText = "",
            lastSeenAt = "",
            wolMode = "on"
        )
        return wakeDevice(ctx, device)
    }

    fun addOrUpdateWolDevice(item: WolDeviceConfig) {
        val clean = cleanMac(item.mac)
        if (!isValidMac(clean)) {
            message = "MAC 地址无效，未保存 WOL 设备"
            return
        }
        val fixed = item.copy(mac = clean, id = item.id.ifBlank { clean }, typeId = normalizeDeviceTypeToken(item.typeId).ifBlank { item.typeId.ifBlank { "desktop" } }, updatedAt = System.currentTimeMillis())
        wolDevices = (listOf(fixed) + wolDevices.filterNot { it.mac.equals(clean, ignoreCase = true) }).take(80)
        prefs.wolDevicesJson = wolDevicesToJson(wolDevices)
        saveDeviceOverride(clean, fixed.remark, fixed.typeId, fixed.enabled)
        message = "已保存 WOL 设备：${fixed.remark.ifBlank { fixed.mac }}"
    }

    fun toggleWolDevice(mac: String, enabled: Boolean) {
        val clean = cleanMac(mac)
        wolDevices = wolDevices.map { if (it.mac.equals(clean, ignoreCase = true)) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it }
        prefs.wolDevicesJson = wolDevicesToJson(wolDevices)
        message = if (enabled) "已启用 WOL" else "已关闭 WOL"
    }

    fun deleteWolDevice(mac: String) {
        val clean = cleanMac(mac)
        wolDevices = wolDevices.filterNot { it.mac.equals(clean, ignoreCase = true) }
        prefs.wolDevicesJson = wolDevicesToJson(wolDevices)
        message = "已删除 WOL 设备"
    }

    suspend fun deleteEvent(event: EventItem) {
        if (event.id <= 0) { events = events.filterNot { it == event }; return }
        runCatching { HubApi(prefs).deleteEvent(event.id) }
        events = events.filterNot { it.id == event.id }
        prefs.cacheEvents = JSONArray(events.map { it.toJson() }).toString()
        message = "已删除事件，可通过刷新同步最新记录"
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabProbeApp(prefs: AppPrefs) {
    var dark by remember { mutableStateOf(prefs.dark) }
    var route by remember { mutableStateOf("home") }
    var autoRefresh by remember { mutableStateOf(prefs.autoRefresh) }
    val state = remember { AppState(prefs) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var latestUpdate by remember { mutableStateOf<GitHubUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateChecking by remember { mutableStateOf(false) }
    var ignoredUpdateCode by remember { mutableStateOf(prefs.ignoredUpdateCode) }
    var downloadUi by remember { mutableStateOf(UpdateDownloadUi()) }
    var showUpdateBar by remember { mutableStateOf(true) }
    var installAfterDownload by remember { mutableStateOf(false) }
    fun pendingUpdate(): Boolean = latestUpdate?.let { it.hasUpdate && ignoredUpdateCode != it.versionCode } == true
    fun openGithub(info: GitHubUpdateInfo? = latestUpdate) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info?.htmlUrl?.takeIf { it.isNotBlank() } ?: AppVersion.GITHUB))) }
    }
    fun startUpdateDownload(info: GitHubUpdateInfo?, installAfter: Boolean) {
        val target = info ?: return
        installAfterDownload = installAfter
        showUpdateBar = true
        scope.launch {
            downloadUi = UpdateDownloadUi(phase = "downloading", total = target.apkSize)
            runCatching {
                downloadUpdateApk(context, target) { progress -> downloadUi = progress }
            }.onSuccess { file ->
                downloadUi = UpdateDownloadUi(phase = "done", downloaded = file.length(), total = target.apkSize, filePath = file.absolutePath)
                if (installAfterDownload) installApk(context, file)
            }.onFailure { e ->
                downloadUi = UpdateDownloadUi(phase = "error", total = target.apkSize, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    LaunchedEffect(Unit) {
        state.refreshAll()
        delay(1500L)
        updateChecking = true
        runCatching { fetchGithubLatestInfo() }
            .onSuccess { info ->
                latestUpdate = info
                prefs.lastUpdateCheckAt = System.currentTimeMillis()
                if (info.hasUpdate && prefs.ignoredUpdateCode != info.versionCode) showUpdateDialog = true
            }
        updateChecking = false
    }
    LaunchedEffect(autoRefresh) {
        val sec = autoRefresh.removeSuffix("S").toIntOrNull() ?: 0
        if (sec > 0) {
            while (true) {
                delay(sec * 1000L)
                if (!state.loading) state.refreshAll()
            }
        }
    }

    val light = lightColorScheme(
        primary = Color(0xFF2D63D8), secondary = Color(0xFF7C3AED), tertiary = Color(0xFFF59E0B),
        background = Color(0xFFF6F8FC), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF101827)
    )
    val darkScheme = darkColorScheme(
        primary = Color(0xFF74A7FF), secondary = Color(0xFFA98BFF), tertiary = Color(0xFFFFC567),
        background = Color(0xFF090D18), surface = Color(0xFF111827), onSurface = Color(0xFFEFF6FF)
    )

    MaterialTheme(colorScheme = if (dark) darkScheme else light, typography = LabTypography) {
        val mainRoutes = listOf("home", "devices", "tools", "events", "settings")
        val navTitles = listOf("总览", "终端", "工具", "记录", "我的")
        val navIcons = listOf(Icons.Rounded.Dashboard, Icons.Rounded.Router, Icons.Rounded.Build, Icons.Rounded.History, Icons.Rounded.Person)
        val normalized = when {
            route.startsWith("tool_") -> "tools"
            route == "daily" -> "events"
            else -> route
        }
        val selected = mainRoutes.indexOf(normalized).let { if (it < 0) 0 else it }
        val navigate: (String) -> Unit = { target -> route = target }
        BackHandler(route.startsWith("tool_") || route == "daily") {
            route = when (route) {
                "daily" -> "events"
                "tool_nat_history" -> "tool_nat"
                else -> "tools"
            }
        }

        val topNav: @Composable () -> Unit = {
            OneUiTopNav(navTitles, navIcons, selected) { route = mainRoutes[it] }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).appBackground()) {
                AnimatedContent(
                    targetState = route,
                    label = "route",
                    transitionSpec = {
                        fadeIn(animationSpec = tween(120)) togetherWith
                            fadeOut(animationSpec = tween(90))
                    }
                ) { r ->
                    when (r) {
                        "home" -> HomeScreen(prefs, state, autoRefresh, { autoRefresh = it; prefs.autoRefresh = it }, { scope.launch { state.refreshAll() } }, navigate, topNav, pendingUpdate(), onUpdateFound = { info -> latestUpdate = info; showUpdateDialog = true }) { showUpdateDialog = true }
                        "devices" -> DevicesScreen(state, topNav)
                        "tools" -> ToolsHomeScreen(prefs, topNav) { route = it }
                        "events" -> EventsScreen(state, { scope.launch { state.refreshAll() } }, { route = "daily" }, topNav)
                        "daily" -> DailyScreen(prefs) { route = "events" }
                        "settings" -> SettingsScreen(prefs, state, dark, autoRefresh, { dark = it; prefs.dark = it }, { autoRefresh = it; prefs.autoRefresh = it }, topNav)
                        "tool_ping" -> PingScreen(prefs) { route = "tools" }
                        "tool_dns" -> DnsScreen(prefs) { route = "tools" }
                        "tool_port" -> PortProbeScreen(prefs) { route = "tools" }
                        "tool_udp" -> UdpProbeScreen(prefs) { route = "tools" }
                        "tool_trace" -> TraceScreen(prefs) { route = "tools" }
                        "tool_nat" -> NatScreen(prefs, { route = "tools" }) { route = "tool_nat_history" }
                        "tool_nat_history" -> NatHistoryScreen(prefs) { route = "tool_nat" }
                        "tool_ssh" -> SshScreen(prefs) { route = "tools" }
                        "tool_ipv6" -> Ipv6TestScreen(prefs) { route = "tools" }
                        "tool_roam" -> WifiRoamingScreen(prefs) { route = "tools" }
                        "tool_mtu" -> MtuScreen(prefs) { route = "tools" }
                        "tool_dns_quality" -> DnsQualityScreen(prefs) { route = "tools" }
                        "tool_service" -> ServiceMonitorScreen(prefs) { route = "tools" }
                        else -> HomeScreen(prefs, state, autoRefresh, { autoRefresh = it; prefs.autoRefresh = it }, { scope.launch { state.refreshAll() } }, navigate, topNav, pendingUpdate(), onUpdateFound = { info -> latestUpdate = info; showUpdateDialog = true }) { showUpdateDialog = true }
                    }
                }
                if (showUpdateDialog && latestUpdate != null) {
                    UpdateDialogCard(
                        info = latestUpdate!!,
                        state = downloadUi,
                        checking = updateChecking,
                        onDismiss = { showUpdateDialog = false },
                        onImmediate = { startUpdateDownload(latestUpdate, true) },
                        onBackground = { showUpdateDialog = false; startUpdateDownload(latestUpdate, false) },
                        onIgnore = { latestUpdate?.let { prefs.ignoredUpdateCode = it.versionCode; ignoredUpdateCode = it.versionCode }; showUpdateDialog = false },
                        onGithub = { openGithub() },
                        onInstall = { downloadUi.filePath.takeIf { it.isNotBlank() }?.let { installApk(context, File(it)) } },
                        onRetry = { startUpdateDownload(latestUpdate, installAfterDownload) }
                    )
                }
                if (showUpdateBar && downloadUi.phase != "idle") {
                    Box(Modifier.align(Alignment.BottomCenter).zIndex(8f)) {
                        UpdateFloatingBar(
                            state = downloadUi,
                            onShow = { showUpdateDialog = true },
                            onHide = { showUpdateBar = false },
                            onInstall = { downloadUi.filePath.takeIf { it.isNotBlank() }?.let { installApk(context, File(it)) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Modifier.appBackground(): Modifier {
    val isDark = MaterialTheme.colorScheme.background.red < 0.1f
    val brush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF0B1020),
                Color(0xFF111827),
                Color(0xFF1A1628),
                Color(0xFF0B1020)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFDDEBFF),
                Color(0xFFF4F8FF),
                Color(0xFFFFF2D2),
                Color(0xFFF6F8FC)
            )
        )
    }
    return background(brush)
}

@Composable
fun ScreenShell(
    title: String,
    subtitle: String,
    action: (@Composable RowScope.() -> Unit)? = null,
    topNav: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            action?.invoke(this)
        }
        topNav?.invoke()
        content()
    }
}

@Composable
fun DetailShell(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 2.dp,
                modifier = Modifier.size(40.dp)
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ArrowBack, null, modifier = Modifier.size(20.dp)) } }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 10.8.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        content()
    }
}

@Composable
fun OneUiTopNav(titles: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.92f),
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(5.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            titles.forEachIndexed { i, t ->
                val active = i == selected
                Surface(
                    onClick = { onSelect(i) },
                    shape = RoundedCornerShape(24.dp),
                    color = if (active) MaterialTheme.colorScheme.surface.copy(alpha = 0.98f) else Color.Transparent,
                    shadowElevation = if (active) 2.dp else 0.dp,
                    modifier = Modifier.height(40.dp).weight(1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icons[i],
                            contentDescription = t,
                            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExpressiveCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    headerAction: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(1.dp, shape, clip = false),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.82f))
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(accent.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp)) }
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f), maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
                }
                if (headerAction != null) {
                    Spacer(Modifier.width(8.dp))
                    headerAction.invoke(this)
                }
            }
            content()
        }
    }
}

@Composable
fun PillButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, accent: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), modifier = Modifier.fillMaxWidth()) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)) }
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun InfoRow(label: String, value: String?, copyable: Boolean = false) {
    val ctx = LocalContext.current
    val text = value?.takeIf { it.isNotBlank() } ?: "未获取"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(76.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (copyable && value?.isNotBlank() == true) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).clickable { copy(ctx, value) }, verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
            }
        } else {
            Text(text, Modifier.weight(1f), color = if (value.isNullOrBlank()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun InfoRowVisible(label: String, value: String?, copyable: Boolean = false) {
    if (!value.isNullOrBlank()) InfoRow(label, value, copyable)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDropdown(keyName: String, prefs: AppPrefs, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val items = remember(tick, keyName) { prefs.history(keyName) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = items.isNotEmpty(), modifier = Modifier.size(30.dp)) {
            Icon(Icons.Rounded.ArrowDropDown, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.995f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.widthIn(min = 230.dp, max = 340.dp).padding(vertical = 6.dp)
        ) {
            Text("最近使用", modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.52f))
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                    onClick = { onPick(item); expanded = false },
                    trailingIcon = {
                        IconButton(onClick = { prefs.removeHistory(keyName, item); tick++ }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha=.55f))
                        }
                    },
                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface)
                )
            }
        }
    }
}

@Composable
fun labOutlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.58f),
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.32f),
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary
)

@Composable
fun CompactHistoryInput(label: String, hint: String, value: String, onValueChange: (String) -> Unit, historyKey: String, prefs: AppPrefs, keyboardType: KeyboardType = KeyboardType.Text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(48.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 11.5.sp, maxLines = 1)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, fontSize = 11.5.sp, maxLines = 1) },
            singleLine = true,
            trailingIcon = { HistoryDropdown(historyKey, prefs) { onValueChange(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(22.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            colors = labOutlinedColors(),
            modifier = Modifier.weight(1f).height(52.dp)
        )
    }
}

@Composable
fun CompactLabeledInput(label: String, hint: String, value: String, onValueChange: (String) -> Unit, keyboardType: KeyboardType = KeyboardType.Text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(48.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 11.5.sp, maxLines = 1)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, fontSize = 11.5.sp, maxLines = 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(22.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            colors = labOutlinedColors(),
            modifier = Modifier.weight(1f).height(56.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSelectInput(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(48.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 11.5.sp, maxLines = 1)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                shape = RoundedCornerShape(18.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                colors = labOutlinedColors(),
                modifier = Modifier.menuAnchor().fillMaxWidth().height(52.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(22.dp),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.995f),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) },
                        onClick = { onChange(option); expanded = false },
                        leadingIcon = if (option == value) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }) else null
                    )
                }
            }
        }
    }
}


private val ParamFieldHeight = 58.dp
private val ParamFieldRadius = 18.dp

@Composable
fun ParamFrame(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = modifier.height(ParamFieldHeight),
        shape = RoundedCornerShape(ParamFieldRadius),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

@Composable
fun TinyParamInput(label: String, value: String, onValueChange: (String) -> Unit, keyboardType: KeyboardType = KeyboardType.Number, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp)
        )
        ParamFrame(Modifier.fillMaxWidth()) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.8.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TinyParamSelect(label: String, value: String, options: List<String>, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                label,
                fontSize = 10.6.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp)
            )
            ParamFrame(Modifier.fillMaxWidth().clickable { expanded = true }) {
                Text(
                    value + "ms",
                    fontSize = 13.8.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.995f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option + "ms", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif) },
                    onClick = { onChange(option); expanded = false },
                    leadingIcon = if (option == value) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }) else null
                )
            }
        }
    }
}


@Composable
fun FieldIconBox(icon: ImageVector, accent: Color = Color(0xFF2563EB)) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = .11f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(17.dp), tint = accent) }
}

@Composable
fun CompactIconHistoryInput(label: String, hint: String, value: String, onValueChange: (String) -> Unit, historyKey: String, prefs: AppPrefs, icon: ImageVector, keyboardType: KeyboardType = KeyboardType.Text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(50.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 11.4.sp, maxLines = 1)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, fontSize = 11.3.sp, maxLines = 1) },
            singleLine = true,
            leadingIcon = { FieldIconBox(icon) },
            trailingIcon = { HistoryDropdown(historyKey, prefs) { onValueChange(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(22.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.2.sp, fontWeight = FontWeight.SemiBold),
            colors = labOutlinedColors(),
            modifier = Modifier.weight(1f).height(56.dp)
        )
    }
}

@Composable
fun TinyParamInputIcon(label: String, value: String, onValueChange: (String) -> Unit, icon: ImageVector, keyboardType: KeyboardType = KeyboardType.Number, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 10.4.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), maxLines = 1, modifier = Modifier.padding(start = 2.dp))
        ParamFrame(Modifier.fillMaxWidth()) {
            FieldIconBox(icon)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.2.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TinyParamSelectIcon(label: String, value: String, options: List<String>, onChange: (String) -> Unit, icon: ImageVector, modifier: Modifier = Modifier, suffix: String = "") {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 10.4.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), maxLines = 1, modifier = Modifier.padding(start = 2.dp))
            ParamFrame(Modifier.fillMaxWidth().clickable { expanded = true }) {
                FieldIconBox(icon)
                Text(
                    value + suffix,
                    fontSize = 13.0.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(22.dp), containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.995f), tonalElevation = 6.dp, shadowElevation = 10.dp) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option + suffix, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif) },
                    onClick = { onChange(option); expanded = false },
                    leadingIcon = if (option == value) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Color(0xFF2563EB)) }) else null
                )
            }
        }
    }
}


@Composable
fun TinyHistoryParamInputIcon(label: String, hint: String, value: String, onValueChange: (String) -> Unit, historyKey: String, prefs: AppPrefs, icon: ImageVector, keyboardType: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 10.6.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), maxLines = 1, modifier = Modifier.padding(start = 2.dp))
        ParamFrame(Modifier.fillMaxWidth()) {
            FieldIconBox(icon)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.8.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f)
            )
            HistoryDropdown(historyKey, prefs) { onValueChange(it) }
        }
    }
}

@Composable
fun TinyInfoParam(label: String, value: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 10.6.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), maxLines = 1, modifier = Modifier.padding(start = 2.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(ParamFieldHeight),
            shape = RoundedCornerShape(ParamFieldRadius),
            color = accent.copy(alpha = .08f),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .12f))
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FieldIconBox(icon, accent)
                Text(value, fontSize = 12.7.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.70f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LabeledHistoryInput(label: String, hint: String, value: String, onValueChange: (String) -> Unit, historyKey: String, prefs: AppPrefs, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(58.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, fontSize = 12.sp, maxLines = 1) },
            singleLine = true,
            trailingIcon = { HistoryDropdown(historyKey, prefs) { onValueChange(it) } },
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(22.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            colors = labOutlinedColors(),
            modifier = Modifier.weight(1f).height(56.dp)
        )
    }
}

@Composable
fun LabeledInput(label: String, hint: String, value: String, onValueChange: (String) -> Unit, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(58.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(value = value, onValueChange = onValueChange, placeholder = { Text(hint, fontSize = 12.sp, maxLines = 1) }, singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None, keyboardOptions = KeyboardOptions(keyboardType = keyboardType), shape = RoundedCornerShape(22.dp), textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), colors = labOutlinedColors(), modifier = Modifier.weight(1f).height(56.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectInput(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(58.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 12.sp, maxLines = 1)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(value = value, onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, shape = RoundedCornerShape(22.dp), textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), colors = labOutlinedColors(), modifier = Modifier.menuAnchor().fillMaxWidth().height(60.dp))
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(22.dp), containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.995f), tonalElevation = 6.dp, shadowElevation = 10.dp) {
                options.forEach { DropdownMenuItem(text = { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }, onClick = { onChange(it); expanded = false }) }
            }
        }
    }
}

@Composable
fun VersionBadge(hasUpdate: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clickable { onClick() }) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.White.copy(alpha = 0.72f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.90f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Text(
                "v${AppVersion.NAME}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF2563EB),
                maxLines = 1
            )
        }
        if (hasUpdate) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
                    .border(1.dp, Color.White, CircleShape)
            )
        }
    }
}


data class GitHubUpdateInfo(
    val tag: String,
    val name: String,
    val body: String,
    val versionCode: Int,
    val htmlUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long
) {
    val hasUpdate: Boolean get() = versionCode > AppVersion.CODE
}

data class UpdateDownloadUi(
    val phase: String = "idle",
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val speedBytes: Long = 0L,
    val filePath: String = "",
    val error: String = "",
    val slow: Boolean = false
) {
    val percent: Int get() = if (total <= 0L) 0 else ((downloaded * 100L / total).coerceIn(0L, 100L)).toInt()
}

private fun parseReleaseBuildCode(tag: String, name: String): Int {
    val text = "$tag $name"
    Regex("build[-_ ]?(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    Regex("[._-](\\d+)$").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return 0
}

private fun formatBytesShort(bytes: Long): String = when {
    bytes <= 0L -> "未知"
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec <= 0L -> "0 KB/s"
    bytesPerSec >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
}

suspend fun fetchGithubLatestInfo(): GitHubUpdateInfo = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val req = Request.Builder()
        .url("https://api.github.com/repos/OnlyChallgener/LabProbeApp/releases/latest")
        .header("User-Agent", "Labprobe/${AppVersion.NAME}")
        .build()
    client.newCall(req).execute().use { resp ->
        val bodyText = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            if (resp.code == 404) error("未找到 GitHub Release，请先发布 Release 并上传 APK")
            error("GitHub HTTP ${resp.code}")
        }
        val json = JSONObject(bodyText)
        val tag = json.optString("tag_name", "")
        val name = json.optString("name", tag.ifBlank { "未知版本" })
        val body = json.optString("body", "")
        val htmlUrl = json.optString("html_url", AppVersion.GITHUB)
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apkName = ""
        var apkUrl = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.optString("name")
            if (n.endsWith(".apk", ignoreCase = true)) {
                apkName = n
                apkUrl = a.optString("browser_download_url")
                apkSize = a.optLong("size", 0L)
                break
            }
        }
        if (apkUrl.isBlank()) error("最新 Release 没有 APK 附件")
        GitHubUpdateInfo(tag, name, body, parseReleaseBuildCode(tag, name), htmlUrl, apkName, apkUrl, apkSize)
    }
}

suspend fun checkGithubLatestSummary(): String = withContext(Dispatchers.IO) {
    runCatching {
        val info = fetchGithubLatestInfo()
        val state = if (info.hasUpdate) "发现新版本" else "当前已是最新版本"
        "$state：${info.name}\n更新包：${info.apkName} · ${formatBytesShort(info.apkSize)}"
    }.getOrElse { e ->
        "检测失败：${e.message ?: e.javaClass.simpleName}"
    }
}

suspend fun downloadUpdateApk(context: Context, info: GitHubUpdateInfo, onProgress: (UpdateDownloadUi) -> Unit): File = withContext(Dispatchers.IO) {
    val dir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates").apply { mkdirs() }
    val file = File(dir, info.apkName.ifBlank { "LabProbe-update-${info.versionCode}.apk" })
    if (file.exists() && info.apkSize > 0 && file.length() == info.apkSize) {
        onProgress(UpdateDownloadUi(phase = "done", downloaded = file.length(), total = info.apkSize, filePath = file.absolutePath))
        return@withContext file
    }
    val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    val req = Request.Builder().url(info.apkUrl).header("User-Agent", "Labprobe/${AppVersion.NAME}").build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("下载失败：GitHub HTTP ${resp.code}")
        val body = resp.body ?: error("下载失败：响应为空")
        val total = if (body.contentLength() > 0) body.contentLength() else info.apkSize
        val tmp = File(dir, file.name + ".part")
        val buf = ByteArray(64 * 1024)
        var downloaded = 0L
        val start = SystemClock.elapsedRealtime().coerceAtLeast(1L)
        var lastEmit = 0L
        body.byteStream().use { input ->
            FileOutputStream(tmp).use { output ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmit >= 250L || (total > 0 && downloaded >= total)) {
                        val elapsed = (now - start).coerceAtLeast(1L)
                        val speed = downloaded * 1000L / elapsed
                        onProgress(UpdateDownloadUi("downloading", downloaded, total, speed, slow = elapsed > 12_000L && speed in 1L until 45_000L))
                        lastEmit = now
                    }
                }
            }
        }
        if (total > 0 && downloaded < total) error("下载失败：文件不完整 ${formatBytesShort(downloaded)} / ${formatBytesShort(total)}")
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        onProgress(UpdateDownloadUi(phase = "done", downloaded = file.length(), total = total, filePath = file.absolutePath))
        file
    }
}

fun installApk(context: Context, file: File) {
    if (!file.exists() || file.length() <= 0L) {
        Toast.makeText(context, "安装失败：APK 文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "安装失败：${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
}

@Composable
fun VersionInfoDialog(onDismiss: () -> Unit, onUpdateFound: (GitHubUpdateInfo) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppVersion.GITHUB))) }
                onDismiss()
            }) { Text("GitHub", fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭", fontWeight = FontWeight.Bold) } },
        title = { Text("极客网探 v${AppVersion.NAME}", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            checking = true
                            updateText = "正在检测 GitHub Release..."
                            runCatching { fetchGithubLatestInfo() }
                                .onSuccess { info ->
                                    if (info.hasUpdate) {
                                        checking = false
                                        onDismiss()
                                        onUpdateFound(info)
                                    } else {
                                        updateText = "当前已是最新版本：${info.name}\n更新包：${info.apkName} · ${formatBytesShort(info.apkSize)}"
                                        checking = false
                                    }
                                }
                                .onFailure { e ->
                                    updateText = "检测失败：${e.message ?: e.javaClass.simpleName}"
                                    checking = false
                                }
                        }
                    },
                    enabled = !checking,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(17.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(if (checking) "检测中..." else "检测更新", fontWeight = FontWeight.Black, color = Color.White)
                }
                if (updateText.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF2563EB).copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .12f))) {
                        Text(updateText, Modifier.fillMaxWidth().padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .78f), lineHeight = 17.sp)
                    }
                }
                AppVersion.CHANGELOG.forEach { (title, items) ->
                    Text(title, fontWeight = FontWeight.Black, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    items.take(5).forEach { item ->
                        Text("• $item", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), lineHeight = 17.sp)
                    }
                }
            }
        },
        shape = RoundedCornerShape(30.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )

}


@Composable
fun UpdateDialogCard(
    info: GitHubUpdateInfo,
    state: UpdateDownloadUi,
    checking: Boolean,
    onDismiss: () -> Unit,
    onImmediate: () -> Unit,
    onBackground: () -> Unit,
    onIgnore: () -> Unit,
    onGithub: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(if (info.hasUpdate) "发现新版本" else "版本更新", fontWeight = FontWeight.Black, fontSize = 21.sp) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF2563EB).copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .12f))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(info.name, fontSize = 15.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        Text("当前 build ${AppVersion.CODE} → 最新 build ${info.versionCode}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .70f))
                        Text("安装包：${info.apkName} · ${formatBytesShort(info.apkSize)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .70f))
                    }
                }
                if (info.body.isNotBlank()) {
                    Text("更新内容", fontSize = 13.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    Text(info.body.lineSequence().filter { it.isNotBlank() }.take(10).joinToString("\n"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), lineHeight = 17.sp)
                }
                if (state.phase != "idle") {
                    val total = if (state.total > 0) state.total else info.apkSize
                    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF14B8A6).copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = .12f))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            val status = when (state.phase) {
                                "downloading" -> "下载中 ${state.percent}% · ${formatSpeed(state.speedBytes)}"
                                "done" -> "下载完成 · ${formatBytesShort(state.downloaded)}"
                                "error" -> "下载失败"
                                else -> "准备下载"
                            }
                            Text(status, fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                            if (state.phase == "downloading") LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)), color = Color(0xFF2563EB), trackColor = Color(0xFF2563EB).copy(alpha = .12f))
                            if (state.phase == "downloading") Text("${formatBytesShort(state.downloaded)} / ${formatBytesShort(total)}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                            if (state.slow) Text("下载网速偏慢，建议切换代理网络后重试。", fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = Color(0xFFF59E0B))
                            if (state.error.isNotBlank()) Text(state.error, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444), lineHeight = 16.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.phase == "done" && state.filePath.isNotBlank()) {
                    Button(onClick = onInstall, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))) { Text("安装", fontWeight = FontWeight.Black) }
                } else if (state.phase == "error") {
                    Button(onClick = onRetry, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))) { Text("重新下载", fontWeight = FontWeight.Black) }
                } else {
                    Button(onClick = onImmediate, enabled = state.phase != "downloading" && !checking, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))) { Text("立即更新", fontWeight = FontWeight.Black) }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onIgnore, enabled = info.hasUpdate) { Text("忽略本版", fontWeight = FontWeight.Bold) }
                TextButton(onClick = onBackground, enabled = state.phase != "downloading") { Text("后台下载", fontWeight = FontWeight.Bold) }
                TextButton(onClick = onGithub) { Text("GitHub", fontWeight = FontWeight.Bold) }
            }
        }
    )
}

@Composable
fun UpdateFloatingBar(state: UpdateDownloadUi, onShow: () -> Unit, onHide: () -> Unit, onInstall: () -> Unit) {
    if (state.phase == "idle") return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .14f)),
        shadowElevation = 6.dp
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(18.dp), tint = Color(0xFF2563EB))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val text = when (state.phase) {
                    "downloading" -> "正在后台下载 ${state.percent}% · ${formatSpeed(state.speedBytes)}"
                    "done" -> "更新包已下载，点击安装"
                    "error" -> "下载失败：${state.error.ifBlank { "未知错误" }}"
                    else -> "准备下载更新"
                }
                Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.phase == "downloading") LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)), color = Color(0xFF2563EB), trackColor = Color(0xFF2563EB).copy(alpha = .12f))
            }
            Spacer(Modifier.width(8.dp))
            if (state.phase == "done" && state.filePath.isNotBlank()) TextButton(onClick = onInstall) { Text("安装", fontWeight = FontWeight.Black) } else TextButton(onClick = onShow) { Text("详情", fontWeight = FontWeight.Black) }
            IconButton(onClick = onHide, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)) }
        }
    }
}

@Composable
fun HomeRefreshMenuButton(autoRefresh: String, loading: Boolean, onRefresh: () -> Unit, onAuto: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.94f),
            shadowElevation = 4.dp,
            tonalElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                        .clickable(enabled = !loading) { onRefresh() }
                        .padding(start = 13.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp), tint = Color(0xFF2563EB))
                    Spacer(Modifier.width(6.dp))
                    Text(if (loading) "刷新中" else "刷新", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                }
                Box(
                    Modifier
                        .height(24.dp)
                        .width(1.dp)
                        .background(Color(0xFFE2E8F0))
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                        .clickable { expanded = true }
                        .padding(start = if (autoRefresh == "手动") 9.dp else 8.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (autoRefresh != "手动") {
                        Text(autoRefresh, fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFF2563EB), maxLines = 1)
                        Spacer(Modifier.width(2.dp))
                    }
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp), tint = Color(0xFF64748B))
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White.copy(alpha = 0.995f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.widthIn(min = 156.dp).padding(vertical = 6.dp)
        ) {
            DropdownMenuItem(
                text = { Text("手动", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) },
                onClick = { onAuto("手动"); expanded = false },
                leadingIcon = { if (autoRefresh == "手动") Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Color(0xFF2563EB)) }
            )
            listOf("3S", "10S", "20S").forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) },
                    onClick = { onAuto(option); expanded = false },
                    leadingIcon = { if (autoRefresh == option) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Color(0xFF2563EB)) }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(prefs: AppPrefs, state: AppState, autoRefresh: String, onAuto: (String) -> Unit, onRefresh: () -> Unit, onNavigate: (String) -> Unit, topNav: @Composable () -> Unit, hasPendingUpdate: Boolean = false, onUpdateFound: (GitHubUpdateInfo) -> Unit = {}, onUpdateClick: () -> Unit = {}) {
    var showVersion by remember { mutableStateOf(false) }
    var privacyMode by remember { mutableStateOf(prefs.privacyMode) }
    var homeOrder by remember { mutableStateOf(normalizeHomeOrder(prefs.homeOrder)) }
    fun saveHomeOrder(newOrder: List<String>) {
        val n = normalizeHomeOrder(newOrder.joinToString(","))
        homeOrder = n
        prefs.homeOrder = n.joinToString(",")
    }
    val data = (state.status?.optJSONObject("data") ?: state.status)
    val nas = data?.optJSONObject("nas")
    val router = data?.optJSONObject("router")
    val nasV6 = safeNasIpv6ForUi(nas, router)
    val vpnRows = remember(data?.toString(), nasV6, state.events) {
        buildVpnRowsForHome(data, nasV6, state.events)
    }
    val onlineCount = state.onlineDevices.size
    val watchedCount = state.devices.size
    val exitOk = !cleanApiText(nas?.optString("exitIpv4")).isBlank() || !cleanApiText(nas?.optString("exitIpv6")).isBlank()
    val vpnOk = vpnRows.isNotEmpty()
    val hubOk = prefs.hub.isNotBlank() && state.hubConnected
    val score = networkScore(hubOk, exitOk, vpnOk, onlineCount, state.events)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFDDEBFF),
                        Color(0xFFF4F8FF),
                        Color(0xFFFFF2D2),
                        Color(0xFFF6F8FC)
                    )
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("极客网探", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    VersionBadge(hasUpdate = hasPendingUpdate) { if (hasPendingUpdate) onUpdateClick() else showVersion = true }
                }
                Text("家庭网络仪表盘", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B), maxLines = 1)
            }
            HomeRefreshMenuButton(
                autoRefresh = autoRefresh,
                loading = state.loading,
                onRefresh = onRefresh,
                onAuto = onAuto
            )
        }

        topNav()

        if (showVersion) VersionInfoDialog(onDismiss = { showVersion = false }, onUpdateFound = onUpdateFound)

        homeOrder.forEach { cardKey ->
            key(cardKey) {
                HomeReorderableCard(
                    cardKey = cardKey,
                    order = homeOrder,
                    onOrder = { saveHomeOrder(it) }
                ) {
                    when (cardKey) {
                    "score" -> HealthScoreCard(
                        score = score,
                        hubOk = hubOk,
                        exitOk = exitOk,
                        vpnOk = vpnOk,
                        onlineCount = onlineCount,
                        lastRefresh = prefs.lastRefresh,
                        message = state.message,
                        onNavigate = onNavigate
                    )
                    "mini" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HealthMiniCard(
                            title = "终端在线",
                            value = "${onlineCount}",
                            unit = "台",
                            icon = Icons.Rounded.Devices,
                            accent = Color(0xFF22C55E),
                            subtitle = if (watchedCount > 0) "关注 $watchedCount 台" else "等待同步",
                            modifier = Modifier.weight(1f).clickable { onNavigate("devices") }
                        )
                        HealthMiniCard(
                            title = "VPN / STUN",
                            value = "${vpnRows.size}",
                            unit = "条",
                            icon = Icons.Rounded.VpnKey,
                            accent = Color(0xFF7C3AED),
                            subtitle = vpnRows.firstOrNull()?.first ?: "暂无地址",
                            modifier = Modifier.weight(1f).clickable { onNavigate("events") }
                        )
                    }
                    "exit" -> HealthExitCard(nas, router, privacyMode) { onNavigate("tool_ping") }
                    "vpn" -> if (vpnRows.isNotEmpty()) HealthVpnCard(
                        rows = vpnRows,
                        privacyMode = privacyMode,
                        onTogglePrivacy = {
                            privacyMode = !privacyMode
                            prefs.privacyMode = privacyMode
                        },
                        onClick = { onNavigate("events") }
                    )
                    "devices" -> HealthDevicesCard(state) { onNavigate("devices") }
                    "today" -> HealthTodayCard(prefs, state, prefs.lastRefresh) { onNavigate("daily") }
                    }
                }
            }
        }
    }
}

fun routerWan6Rows(router: JSONObject?): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    val arr = router?.optJSONArray("wan6List")
        ?: router?.optJSONArray("router_wan6_list")
        ?: router?.optJSONArray("routerWan6List")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ip = cleanApiText(o.optString("ip", o.optString("address", o.optString("value"))))
            if (ip.isBlank() || rows.any { it.second == ip }) continue
            val name = cleanApiText(o.optString("name")).ifBlank {
                if (o.optBoolean("primary", i == 0)) "主用 WAN" else "备用 WAN"
            }
            rows += name to ip
        }
    }
    if (rows.isEmpty()) {
        val ip = cleanApiText(router?.optString("wanIpv6") ?: router?.optString("routerWan6") ?: "")
        if (ip.isNotBlank()) rows += "路由 WAN6" to ip
    }
    return rows
}

fun primaryRouterWan6(router: JSONObject?): String = routerWan6Rows(router).firstOrNull()?.second.orEmpty()

fun safeNasIpv6ForUi(nas: JSONObject?, router: JSONObject?): String {
    // NAS IPv6 必须按 Hub/NAS 本机探测结果显示。
    // 即使它和路由 WAN6 相同，也不能隐藏；WireGuard 也依赖这个字段生成 [NAS IPv6]:51820。
    // 路由 WAN6 和 NAS IPv6 是两个独立展示字段，防止 buildfix30 误删 NAS 出口。
    return cleanApiText(nas?.optString("exitIpv6"))
}

fun buildVpnRowsForHome(data: JSONObject?, nasV6: String, events: List<EventItem>): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    fun addVpnRow(labelRaw: String?, addrRaw: String?) {
        val addr = cleanApiText(addrRaw)
        if (addr.isBlank()) return
        val label = vpnServiceLabel(cleanApiText(labelRaw).ifBlank { "STUN" })
        val sameLabelIndex = rows.indexOfFirst { it.first.equals(label, ignoreCase = true) }
        if (sameLabelIndex >= 0) {
            rows[sameLabelIndex] = label to addr
            return
        }
        if (rows.none { it.second == addr }) rows += label to addr
    }

    val wg = if (nasV6.isNotBlank()) "[$nasV6]:51820" else data?.optJSONObject("wireguard")?.optString("publicAddress").orEmpty()
    addVpnRow("WireGuard", wg)

    val list = data?.optJSONArray("vpnStunAddresses") ?: data?.optJSONArray("vpnAddresses")
    if (list != null) {
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            addVpnRow(o.optString("name", o.optString("service")), o.optString("address", o.optString("stun")))
        }
    }

    val vpnObj = data?.optJSONObject("vpn")
    if (vpnObj != null) {
        val keys = vpnObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = vpnObj.optJSONObject(key)
            val label = cleanApiText(obj?.optString("name")).ifBlank { key }
            val addr = cleanApiText(obj?.optString("address")?.ifBlank { obj.optString("stun") } ?: "")
            addVpnRow(label, addr)
        }
    }

    val luckyObj = data?.optJSONObject("luckyStun")
    val luckyDirect = when (val raw = data?.opt("luckyStun")) {
        is String -> cleanApiText(raw)
        else -> cleanApiText(luckyObj?.optString("address")?.ifBlank { luckyObj.optString("stun") })
    }
    val luckyLabel = cleanApiText(luckyObj?.optString("name")).ifBlank { "Lucky" }
    addVpnRow(luckyLabel, luckyDirect)

    val stunObj = data?.optJSONObject("stun")
    addVpnRow(cleanApiText(stunObj?.optString("name")).ifBlank { "STUN" }, stunObj?.optString("publicAddress") ?: stunObj?.optString("address"))

    if (rows.size <= 1) {
        events.asSequence()
            .filter { e ->
                val n = (e.name + " " + e.title + " " + e.type).lowercase(Locale.getDefault())
                n.contains("openvpn") || n.contains("lucky") || n.contains("easytier") || n.contains("wireguard") || n.contains("stun")
            }
            .forEach { e ->
                val rawName = cleanApiText(e.name).ifBlank {
                    cleanApiText(e.title)
                        .replace("STUN 地址变化", "")
                        .replace("地址变化", "")
                        .trim()
                }
                val addr = cleanApiText(e.newValue).ifBlank { cleanApiText(e.ip) }
                addVpnRow(rawName.ifBlank { "STUN" }, addr)
            }
    }
    return rows
}

fun networkScore(hubOk: Boolean, exitOk: Boolean, vpnOk: Boolean, onlineCount: Int, events: List<EventItem>): Int {
    var score = 64
    if (hubOk) score += 12
    if (exitOk) score += 10
    if (vpnOk) score += 7
    if (onlineCount > 0) score += 5
    val recentBad = events.take(8).count { it.type.contains("ddns", true) || it.type.contains("offline", true) }
    score -= recentBad.coerceAtMost(4) * 2
    return score.coerceIn(0, 99)
}

@Composable
fun OneUiSegmentBar() {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.58f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            val items = listOf(Icons.Rounded.Dashboard, Icons.Rounded.Router, Icons.Rounded.VpnKey, Icons.Rounded.Devices, Icons.Rounded.History)
            items.forEachIndexed { idx, icon ->
                val selected = idx == 0
                Box(
                    Modifier
                        .height(40.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (selected) Color.White else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = if (selected) Color(0xFF0F172A) else Color(0xFF64748B), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun HealthCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth().shadow(5.dp, RoundedCornerShape(30.dp), clip = false),
        shape = RoundedCornerShape(30.dp),
        color = Color.White.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.95f))
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), content = content)
    }
}

@Composable
fun HealthScoreCard(score: Int, hubOk: Boolean, exitOk: Boolean, vpnOk: Boolean, onlineCount: Int, lastRefresh: String, message: String, onNavigate: (String) -> Unit) {
    HealthCard(Modifier.clickable { onNavigate("settings") }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("网络健康得分", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(score.toString(), fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), lineHeight = 52.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (score >= 85) "优秀" else if (score >= 70) "良好" else "待优化", fontSize = 14.sp, fontWeight = FontWeight.Black, color = if (score >= 85) Color(0xFF16A34A) else Color(0xFFF59E0B), modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(message.replace("刷新成功：", "最后刷新 ").ifBlank { "等待刷新" }, fontSize = 11.5.sp, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            WeeklyMiniBars(score)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            HealthStatusBadge("Hub", if (hubOk) "就绪" else "未连", if (hubOk) Color(0xFF16A34A) else Color(0xFFEF4444), Modifier.weight(1f).clickable { onNavigate("settings") })
            HealthStatusBadge("出口", if (exitOk) "正常" else "无数据", if (exitOk) Color(0xFF0EA5E9) else Color(0xFF64748B), Modifier.weight(1f).clickable { onNavigate("tool_ping") })
            HealthStatusBadge("VPN", if (vpnOk) "已记录" else "无数据", if (vpnOk) Color(0xFF7C3AED) else Color(0xFF64748B), Modifier.weight(1f).clickable { onNavigate("events") })
        }
    }
}

@Composable
fun WeeklyMiniBars(score: Int) {
    Canvas(Modifier.width(112.dp).height(76.dp)) {
        val barW = 9.dp.toPx()
        val gap = 8.dp.toPx()
        val base = size.height - 14.dp.toPx()
        val maxH = 52.dp.toPx()
        val values = listOf(score - 11, score - 7, score - 4, score - 2, score - 5, score, score - 1).map { it.coerceIn(35, 98) }
        values.forEachIndexed { i, v ->
            val x = i * (barW + gap)
            val h = maxH * (v / 100f)
            drawLine(Color(0xFFE8EEF7), Offset(x + barW / 2, base), Offset(x + barW / 2, base - maxH), strokeWidth = barW, cap = StrokeCap.Round)
            drawLine(if (i >= 5) Color(0xFF22C55E) else Color(0xFF93C5FD), Offset(x + barW / 2, base), Offset(x + barW / 2, base - h), strokeWidth = barW, cap = StrokeCap.Round)
        }
        drawCircle(Color(0xFF3B82F6), radius = 5.dp.toPx(), center = Offset(6 * (barW + gap) + barW / 2, base - maxH * (values.last() / 100f)))
    }
}

@Composable
fun HealthStatusBadge(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = color.copy(alpha = .10f), tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(label, fontSize = if (label.length > 4) 9.sp else 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun HealthMiniCard(title: String, value: String, unit: String, icon: ImageVector, accent: Color, subtitle: String, modifier: Modifier = Modifier) {
    HealthCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), lineHeight = 30.sp)
                    Spacer(Modifier.width(3.dp))
                    Text(unit, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), modifier = Modifier.padding(bottom = 4.dp))
                }
                Text(subtitle, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun HealthSectionTitle(title: String, subtitle: String?, icon: ImageVector, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun HealthDataRow(label: String, value: String?, accent: Color = Color(0xFF0F172A)) {
    HealthDataRowDisplay(label, value, value, accent)
}

@Composable
fun HealthDataRowDisplay(label: String, realValue: String?, displayValue: String?, accent: Color = Color(0xFF0F172A)) {
    val ctx = LocalContext.current
    val real = cleanApiText(realValue)
    val display = cleanApiText(displayValue)
    if (real.isBlank() && display.isBlank()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(86.dp), color = Color(0xFF64748B), fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).clickable(enabled = real.isNotBlank()) { copy(ctx, real) }, verticalAlignment = Alignment.CenterVertically) {
            Text(display.ifBlank { real }, color = accent, fontWeight = FontWeight.Black, fontSize = 13.2.sp, maxLines = 1)
        }
    }
}

@Composable
fun HealthExitCard(nas: JSONObject?, router: JSONObject?, privacyMode: Boolean, onClick: () -> Unit = {}) {
    HealthCard(Modifier.clickable { onClick() }) {
        HealthSectionTitle("出口与路由", "NAS 出口、路由 WAN6，点地址复制。", Icons.Rounded.Public, Color(0xFF0EA5E9))
        Spacer(Modifier.height(13.dp))
        HealthDataRowDisplay("NAS IPv4", nas?.optString("exitIpv4"), maskAddressForUi(nas?.optString("exitIpv4"), privacyMode))
        Spacer(Modifier.height(9.dp))
        val nasIpv6 = safeNasIpv6ForUi(nas, router)
        HealthDataRowDisplay("NAS IPv6", nasIpv6, maskAddressForUi(nasIpv6, privacyMode))
        val wan6Rows = routerWan6Rows(router)
        wan6Rows.forEach { (label, value) ->
            Spacer(Modifier.height(9.dp))
            HealthDataRowDisplay(if (wan6Rows.size <= 1) "路由 WAN6" else label, value, maskAddressForUi(value, privacyMode))
        }
    }
}

@Composable
fun HealthVpnCard(rows: List<Pair<String, String>>, privacyMode: Boolean, onTogglePrivacy: () -> Unit, onClick: () -> Unit = {}) {
    HealthCard(Modifier.clickable { onClick() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF7C3AED).copy(alpha = .12f))
                    .clickable { onTogglePrivacy() },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (privacyMode) Icons.Rounded.VisibilityOff else Icons.Rounded.VpnKey, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("VPN / STUN 地址", fontSize = 17.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (privacyMode) "隐私模式已开启，点击左侧图标恢复显示。" else "按服务名显示，点击钥匙可隐藏公网地址。", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(13.dp))
        rows.forEachIndexed { idx, row ->
            HealthDataRowDisplay(row.first, row.second, maskAddressForUi(row.second, privacyMode), Color(0xFF0F172A))
            if (idx != rows.lastIndex) Spacer(Modifier.height(9.dp))
        }
    }
}

@Composable
fun HealthDevicesCard(state: AppState, onClick: () -> Unit = {}) {
    HealthCard(Modifier.clickable { onClick() }) {
        HealthSectionTitle("关注终端", "在线状态、信号与最后离线信息。", Icons.Rounded.Devices, Color(0xFFF59E0B))
        Spacer(Modifier.height(12.dp))
        if (state.devices.isEmpty()) {
            Text("暂无缓存，点击刷新。", color = Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        state.devices.take(4).forEachIndexed { idx, d ->
            HealthDeviceLine(d)
            if (idx != state.devices.take(4).lastIndex) Spacer(Modifier.height(11.dp))
        }
    }
}

@Composable
fun HealthDeviceLine(d: DeviceItem) {
    val accent = if (d.online) Color(0xFF16A34A) else Color(0xFFEF4444)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name.ifBlank { d.mac }, fontSize = 13.6.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = listOf(d.ip, d.ssid, d.band, d.rxrate).map { cleanApiText(it) }.filter { it.isNotBlank() }.joinToString(" · ")
            Text(info.ifBlank { if (d.online) "在线信息待刷新" else "暂无历史详情" }, fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val third = if (d.online) {
                listOfNotNull(
                    cleanApiText(d.onlineDurationText).takeIf { it.isNotBlank() }?.let { "在线 $it" },
                    cleanApiText(d.onlineSince).takeIf { it.isNotBlank() }?.let { "上线 $it" }
                ).joinToString(" · ")
            } else {
                listOfNotNull(
                    cleanApiText(d.offlineAt).takeIf { it.isNotBlank() }?.let { "离线 $it" },
                    cleanApiText(d.rssi).takeIf { it.isNotBlank() }?.let { "最后信号 ${if (it.endsWith("dBm")) it else it + "dBm"}" }
                ).joinToString(" · ")
            }
            if (third.isNotBlank()) Text(third, fontSize = 10.8.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF94A3B8), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = .10f), tonalElevation = 0.dp) {
            Text(if (d.online) "在线" else "离线", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
    }
}

data class HomeDailySnapshot(
    val up: Int,
    val down: Int,
    val vpn: Int,
    val ddns: Int,
    val hasNote: Boolean,
    val source: String
)

fun eventMatchesDate(time: String, date: String): Boolean {
    val t = cleanApiText(time)
    return t.startsWith(date) || t.contains(date)
}

fun homeDailyFromEvents(events: List<EventItem>, date: String, source: String = "本地事件缓存"): HomeDailySnapshot {
    val todayEvents = normalizeDeviceEvents(events).filter { eventMatchesDate(it.time, date) }
    val up = todayEvents.count { it.type == "device_online" }
    val down = todayEvents.count { it.type == "device_offline" }
    val vpn = todayEvents.count {
        val t = (it.type + " " + it.title + " " + it.name).lowercase(Locale.getDefault())
        t.contains("vpn") || t.contains("stun") || t.contains("wireguard") || t.contains("openvpn") || t.contains("lucky")
    }
    val ddns = todayEvents.count { it.type.contains("ddns", ignoreCase = true) || it.title.contains("DDNS", ignoreCase = true) }
    return HomeDailySnapshot(up, down, vpn, ddns, false, source)
}

fun homeDailyFromApi(root: JSONObject, date: String, fallback: HomeDailySnapshot): HomeDailySnapshot {
    val daily = root.optJSONObject("daily") ?: root
    val summary = daily.optJSONObject("summary") ?: JSONObject()
    val note = cleanApiText(daily.optString("note"))
    return HomeDailySnapshot(
        // 设备上线/下线统一使用 APP 本地规范化事件，避免 Hub 旧缓存重复离线继续影响首页。
        up = fallback.up,
        down = fallback.down,
        vpn = summary.optInt("vpnChanges", fallback.vpn),
        ddns = summary.optInt("ddnsChanges", fallback.ddns),
        hasNote = note.isNotBlank(),
        source = "已同步每日总结 $date · 设备统计已本地去重"
    )
}

fun localDailyDeviceSummary(events: List<EventItem>, date: String): JSONArray {
    val arr = JSONArray()
    val today = normalizeDeviceEvents(events).filter { eventMatchesDate(it.time, date) && (it.type == "device_online" || it.type == "device_offline") }
    val grouped = today.groupBy { eventDeviceKey(it).ifBlank { it.name.ifBlank { it.title } } }
    grouped.values.sortedBy { it.firstOrNull()?.name ?: it.firstOrNull()?.title ?: "" }.forEach { list ->
        val latest = list.maxByOrNull { parseEventMillis(it.time) ?: 0L } ?: return@forEach
        val name = latest.name.ifBlank { latest.title.removeSuffix(" 上线").removeSuffix(" 离线") }.ifBlank { "未知终端" }
        val online = list.count { it.type == "device_online" }
        val offline = list.count { it.type == "device_offline" }
        val totalOnlineSeconds = list.filter { it.type == "device_offline" }.mapNotNull { parseDurationSeconds(it.onlineDurationText) }.sum()
        arr.put(JSONObject()
            .put("name", name)
            .put("online", online)
            .put("offline", offline)
            .put("onlineDurationText", if (totalOnlineSeconds > 0) formatDurationMs(totalOnlineSeconds * 1000L) else latest.onlineDurationText)
            .put("lastIp", latest.ip)
            .put("lastSignal", listOfNotNull(
                latest.rssi.takeIf { it.isNotBlank() }?.let { "$it dBm" },
                latest.band.takeIf { it.isNotBlank() },
                latest.rxrate.takeIf { it.isNotBlank() }
            ).joinToString(" "))
        )
    }
    return arr
}

@Composable
fun HealthTodayCard(prefs: AppPrefs, state: AppState, lastRefresh: String, onClick: () -> Unit = {}) {
    val today = todayDateString()
    val fallback = remember(state.events, today) { homeDailyFromEvents(state.events, today) }
    var snapshot by remember(today, prefs.hub, prefs.token) { mutableStateOf(fallback) }

    LaunchedEffect(today, prefs.hub, prefs.token, lastRefresh, state.events.size) {
        snapshot = fallback
        if (prefs.hub.isNotBlank()) {
            runCatching { HubApi(prefs).getDaily(today) }
                .onSuccess { snapshot = homeDailyFromApi(it, today, fallback) }
                .onFailure { snapshot = fallback.copy(source = "每日总结暂不可用，已用本地事件兜底") }
        }
    }

    HealthCard(Modifier.clickable { onClick() }) {
        HealthSectionTitle("今日概览", "和记录页每日总结同步，点卡片查看详情。", Icons.Rounded.CalendarMonth, Color(0xFF2563EB))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthStatusBadge("设备上线", "${snapshot.up} 次", Color(0xFF16A34A), Modifier.weight(1f))
            HealthStatusBadge("设备下线", "${snapshot.down} 次", Color(0xFFEF4444), Modifier.weight(1f))
            HealthStatusBadge("VPN-STUN", "${snapshot.vpn} 次", Color(0xFF7C3AED), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthStatusBadge("DDNS", "${snapshot.ddns} 次", Color(0xFF0EA5E9), Modifier.weight(1f))
            HealthStatusBadge("备注", if (snapshot.hasNote) "1 条" else "0 条", Color(0xFF64748B), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(snapshot.source + " · 最后成功 ${lastRefresh.ifBlank { "-" }}", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HomeReorderableCard(cardKey: String, order: List<String>, onOrder: (List<String>) -> Unit, content: @Composable () -> Unit) {
    var dragging by remember(cardKey) { mutableStateOf(false) }
    var dragY by remember(cardKey) { mutableStateOf(0f) }
    val scale by animateFloatAsState(if (dragging) 1.025f else 1f, animationSpec = tween(140), label = "home-card-scale")
    val thresholdPx = with(LocalDensity.current) { 92.dp.toPx() }

    fun commitOrder() {
        val current = order.indexOf(cardKey)
        if (current < 0) return
        val steps = (dragY / thresholdPx).roundToInt().coerceIn(-current, order.lastIndex - current)
        if (steps == 0) return
        val next = order.toMutableList()
        val item = next.removeAt(current)
        next.add((current + steps).coerceIn(0, next.size), item)
        onOrder(next)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 5f else 0f)
            // 这里只做位移和缩放，不再给整张 Box 加阴影。
            // 整张 Box 是 fillMaxWidth 的矩形，给它加 shadow 会在拖拽时露出方形阴影和底部长横杠。
            .offset { IntOffset(0, if (dragging) dragY.roundToInt() else 0) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (dragging) 0.985f else 1f
                shadowElevation = 0f
                clip = false
            }
            .pointerInput(cardKey, order) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true; dragY = 0f },
                    onDragEnd = {
                        commitOrder()
                        dragging = false
                        dragY = 0f
                    },
                    onDragCancel = { dragging = false; dragY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragY += dragAmount.y
                    }
                )
            }
    ) {
        // 不再包一层额外阴影/裁剪层，直接移动原卡片内容。
        // 原卡片本身已经是圆角 Surface；额外 fillMaxWidth 阴影会变成方形灰块。
        Box(Modifier.fillMaxWidth()) {
            content()
        }
        if (dragging) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.96f),
                shadowElevation = 4.dp
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.OpenWith, null, Modifier.size(15.dp), tint = Color(0xFF2563EB))
                    Spacer(Modifier.width(5.dp))
                    Text("拖动排序", fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = Color(0xFF2563EB))
                }
            }
        }
    }
}


@Composable
fun StatusCard(prefs: AppPrefs, state: AppState, autoRefresh: String, onAuto: (String) -> Unit) {
    ExpressiveCard("状态总览", state.message, Icons.Rounded.Dashboard, Color(0xFF2D63D8)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Hub", if (prefs.hub.isBlank()) "未设" else if (state.hubConnected) "就绪" else "待连", Color(0xFF2D63D8))
            StatusPill("终端", "${state.onlineDevices.size} 在线", Color(0xFFF59E0B))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(0.95f)) { CompactSelectInput("刷新", autoRefresh, listOf("手动", "3S", "10S", "20S"), onAuto) }
            Text("最后成功 ${prefs.lastRefresh.ifBlank { "-" }}", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f))
        }
    }
}

@Composable
fun ExitCard(nas: JSONObject?, router: JSONObject?) {
    ExpressiveCard("出口与路由", "NAS 出口、路由 WAN6，点地址复制。", Icons.Rounded.Public, Color(0xFF0EA5E9)) {
        InfoRowVisible("NAS IPv4", nas?.optString("exitIpv4"), true)
        InfoRowVisible("NAS IPv6", safeNasIpv6ForUi(nas, router), true)
        val wan6Rows = routerWan6Rows(router)
        wan6Rows.forEach { (label, value) ->
            InfoRowVisible(if (wan6Rows.size <= 1) "路由 WAN6" else label, value, true)
        }
    }
}

@Composable
fun VpnCard(rows: List<Pair<String, String>>) {
    ExpressiveCard("VPN / STUN 地址", null, Icons.Rounded.VpnKey, Color(0xFF7C3AED)) {
        rows.forEach { (label, value) -> InfoRowVisible(label, value, true) }
    }
}

fun vpnServiceLabel(key: String): String = when (key.lowercase(Locale.getDefault())) {
    "lucky", "lucky_stun" -> "Lucky"
    "wg", "wireguard" -> "WireGuard"
    "openvpn", "open_vpn" -> "OpenVPN"
    "easytier", "easy_tier" -> "EasyTier"
    else -> key.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

@Composable
fun DevicesHomeCard(state: AppState) {
    ExpressiveCard("关注终端", "在线时长、下线发现时间精确到秒。", Icons.Rounded.Devices, Color(0xFFF59E0B)) {
        if (state.devices.isEmpty()) Text("暂无缓存，点击刷新。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
        state.devices.take(4).forEach { DeviceLine(it, details = true) }
    }
}

@Composable
fun StatusPill(label: String, value: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = .12f)) {
        Text("$label $value", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = color, fontWeight = FontWeight.Black, fontSize = 11.5.sp, maxLines = 1)
    }
}

@Composable
fun DevicesScreen(state: AppState, topNav: @Composable () -> Unit) = ScreenShell("终端", "设备识别 · IPv6 · WOL 唤醒", topNav = topNav) {
    var mode by remember { mutableStateOf("watch") }
    var detailMac by remember { mutableStateOf<String?>(null) }
    val list = if (mode == "online") state.onlineDevices else state.devices
    val shared = remember(state.devices, state.onlineDevices) { mergeSharedDeviceState(state.devices, state.onlineDevices) }
    val wolCount = remember(state.wolDevices) { state.wolDevices.count { it.enabled } }
    val detailDevice = remember(detailMac, shared) { detailMac?.let { mac -> shared.firstOrNull { it.mac.equals(mac, ignoreCase = true) } } }
    ExpressiveCard("终端同步", "${if (mode == "online") "全部在线" else if (mode == "wol") "WOL设备" else "关注设备"} · ${if (mode == "wol") state.wolDevices.size else list.size} 台 · WOL $wolCount", Icons.Rounded.Devices, Color(0xFFF59E0B)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = mode == "watch", onClick = { mode = "watch" }, label = { Text("关注", fontSize = 12.sp) })
            FilterChip(selected = mode == "online", onClick = { mode = "online" }, label = { Text("全部在线", fontSize = 12.sp) })
            FilterChip(selected = mode == "wol", onClick = { mode = "wol" }, label = { Text("WOL", fontSize = 12.sp) })
        }
        Text(state.message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (mode == "wol") {
        WolManagementPanel(state)
    } else {
        list.forEach { d -> DeviceSmartCard(state, d, onOpenDetails = { detailMac = d.mac }) }
    }
    detailDevice?.let { d -> LabDeviceDetailSheet(state = state, device = d, onDismiss = { detailMac = null }) }
}


@Composable
fun DeviceSmartCard(state: AppState, d: DeviceItem, onOpenDetails: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var editingDevice by remember { mutableStateOf(false) }
    val profile = remember(d.name, d.remark, d.manualType, d.mac, d.manufacture, d.osType, d.hostName, d.wolMode, d.connectType, d.ssid, d.band, d.rssi, d.rxrate) { inferDeviceProfile(d) }
    val wifi = remember(d.ssid, d.band, d.rssi, d.rxrate, d.connectType) { hasWifiInfo(d) }
    ExpressiveCard(
        title = d.remark.ifBlank { d.name.ifBlank { d.mac } },
        subtitle = if (wifi) listOf(profile.label, d.mac).filter { it.isNotBlank() }.joinToString(" · ") else "",
        icon = profile.icon,
        accent = profile.accent,
        headerAction = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                androidx.compose.material3.Surface(onClick = { editingDevice = true }, modifier = Modifier.size(28.dp), shape = CircleShape, color = profile.accent.copy(alpha = .10f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Edit, null, tint = profile.accent, modifier = Modifier.size(15.dp)) }
                }
                Surface(shape = RoundedCornerShape(99.dp), color = if (d.online) Color(0xFFDCFCE7) else Color(0xFFFFE4E6)) {
                    Text(if (d.online) "在线" else "离线", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = if (d.online) Color(0xFF16A34A) else Color(0xFFEF4444), fontSize = 10.5.sp, fontWeight = FontWeight.Black)
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpenDetails, onLongClick = onOpenDetails)
    ) {
        DeviceSmartInfo(d, profile)
        if (!d.online && profile.wolCandidate) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = profile.accent.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, profile.accent.copy(alpha = .14f))) {
                    Text("${profile.note} · 点击唤醒后会发送 3 轮魔术包", Modifier.padding(horizontal = 11.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            val msg = runCatching { state.wakeDevice(ctx, d) }.getOrElse { "WOL失败：${it.message}" }
                            toast(ctx, msg)
                            busy = false
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14B8A6)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Icon(Icons.Rounded.Power, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (busy) "发送中" else "唤醒", fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }
    }
    if (editingDevice) {
        DeviceOverrideEditDialog(
            device = d,
            state = state,
            onDismiss = { editingDevice = false }
        )
    }
}

@Composable
fun DeviceSmartInfo(d: DeviceItem, profile: DeviceVisualProfile) {
    val ctx = LocalContext.current
    val ip4 = cleanApiText(d.ip).ifBlank { cleanApiText(d.lastKnownIp()) }.ifBlank { "--" }
    val v6 = d.ipv6.filter { it.isNotBlank() }.distinct()
    val wifi = hasWifiInfo(d)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (wifi) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceMiniMetric("IPv4", ip4, Icons.Rounded.Public, Color(0xFF2563EB), Modifier.weight(1f), copyValue = cleanApiText(d.ip), allowScroll = true)
                val v6Full = bestIpv6ForDisplay(v6)
                val v6Text = v6Full.ifBlank { "--" }.let { if (it == "--") it else shortIpv6(it) + if (v6.size > 1) " +${v6.size - 1}" else "" }
                DeviceMiniMetric("IPv6", v6Text, Icons.Rounded.SettingsEthernet, Color(0xFF06B6D4), Modifier.weight(1f), copyValue = v6Full, allowScroll = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val radio = listOf(d.ssid, d.band, d.rxrate).map { cleanApiText(it) }.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "--" }
                DeviceMiniMetric("链路", radio, Icons.Rounded.Wifi, Color(0xFF22C55E), Modifier.weight(1f), copyValue = radio.takeIf { it != "--" }.orEmpty(), allowScroll = true)
                val signal = cleanApiText(d.rssi).takeIf { it.isNotBlank() }?.let { if (it.endsWith("dBm")) it else "${it}dBm" } ?: "--"
                DeviceMiniMetric("信号", signal, Icons.Rounded.WifiTethering, Color(0xFFF59E0B), Modifier.weight(1f), copyValue = signal.takeIf { it != "--" }.orEmpty(), allowScroll = true)
            }
            DeviceFooterLine(d = d, profile = profile, showTime = true)
        } else {
            WiredDeviceInfo(d = d, profile = profile, ip4 = ip4, ipv6List = v6)
        }
        if (v6.size > 1) {
            Text("IPv6 共 ${v6.size} 个：${v6.take(2).joinToString(" · ") { shortIpv6(it) }}${if (v6.size > 2) " · …" else ""}", Modifier.clickable { copy(ctx, v6.joinToString("\n")) }.horizontalScroll(rememberScrollState()), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .46f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private fun DeviceItem.lastKnownIp(): String = ip

@Composable
fun WiredDeviceInfo(d: DeviceItem, profile: DeviceVisualProfile, ip4: String, ipv6List: List<String>) {
    val v6Full = bestIpv6ForDisplay(ipv6List)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceMiniMetric("IPv4", ip4, Icons.Rounded.Public, Color(0xFF2563EB), Modifier.weight(1f), copyValue = cleanApiText(d.ip), allowScroll = true)
            DeviceMiniMetric("MAC", d.mac.ifBlank { "--" }, Icons.Rounded.SettingsEthernet, Color(0xFF64748B), Modifier.weight(1f), copyValue = d.mac, allowScroll = true)
        }
        DeviceMiniMetric(
            label = "IPv6",
            value = v6Full.ifBlank { "--" },
            icon = Icons.Rounded.SettingsEthernet,
            color = Color(0xFF06B6D4),
            modifier = Modifier.fillMaxWidth(),
            copyValue = v6Full,
            allowScroll = true
        )
        DeviceFooterLine(d = d, profile = profile, showTime = false)
    }
}

@Composable
fun DeviceFooterLine(d: DeviceItem, profile: DeviceVisualProfile, showTime: Boolean) {
    val timeText = if (showTime) {
        if (d.online) {
            listOfNotNull(
                cleanApiText(d.onlineDurationText).takeIf { it.isNotBlank() }?.let { "在线 $it" },
                cleanApiText(d.onlineSince).takeIf { it.isNotBlank() }?.let { "上线 $it" }
            ).joinToString(" · ")
        } else {
            listOfNotNull(
                cleanApiText(d.offlineAt).takeIf { it.isNotBlank() }?.let { "离线 $it" },
                cleanApiText(d.lastSeenAt).takeIf { it.isNotBlank() }?.let { "最后 $it" }
            ).joinToString(" · ")
        }
    } else "有线设备"

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = profile.accent.copy(alpha = .10f)) {
            Text(profile.label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = profile.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
        if (timeText.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                timeText,
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (showTime) .54f else .62f),
                fontSize = 10.8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}


@Composable
fun DeviceMiniMetric(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, copyValue: String = "", allowScroll: Boolean = false) {
    val ctx = LocalContext.current
    Surface(modifier = modifier.clickable(enabled = copyValue.isNotBlank()) { copy(ctx, copyValue) }, shape = RoundedCornerShape(18.dp), color = color.copy(alpha = .075f), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .12f))) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f), maxLines = 1)
                val textModifier = if (allowScroll && value != "--") Modifier.horizontalScroll(rememberScrollState()) else Modifier
                Text(value, modifier = textModifier, fontSize = 11.2.sp, fontWeight = FontWeight.Black, color = if (value == "--") MaterialTheme.colorScheme.onSurface.copy(alpha = .35f) else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = if (allowScroll) TextOverflow.Clip else TextOverflow.Ellipsis)
            }
        }
    }
}


@Composable
fun DeviceOverrideEditDialog(device: DeviceItem, state: AppState, onDismiss: () -> Unit) {
    LabDeviceEditSheet(device = device, state = state, onDismiss = onDismiss)
}

@Composable
fun DeviceLine(d: DeviceItem, details: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (d.online) Color(0xFF16A34A) else Color(0xFFEF4444)))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name.ifBlank { d.mac }, fontWeight = FontWeight.Black, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val mainInfo = listOf(d.ip, d.ssid, d.band, d.rxrate).map { cleanApiText(it) }.filter { it.isNotBlank() }.joinToString(" · ")
            val mainFallback = if (d.online) "在线信息待刷新" else "离线 · 暂无历史详情"
            Text(mainInfo.ifBlank { mainFallback }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (details) {
                val parts = if (d.online) {
                    listOfNotNull(
                        cleanApiText(d.onlineDurationText).takeIf { it.isNotBlank() }?.let { "在线 $it" },
                        cleanApiText(d.onlineSince).takeIf { it.isNotBlank() }?.let { "上线 $it" }
                    )
                } else {
                    listOfNotNull(
                        cleanApiText(d.offlineAt).takeIf { it.isNotBlank() }?.let { "离线 $it" },
                        cleanApiText(d.rssi).takeIf { it.isNotBlank() }?.let { "最后信号 ${if (it.endsWith("dBm")) it else it + "dBm"}" }
                    )
                }
                val stateText = parts.joinToString(" · ").ifBlank { if (d.online) "在线" else "离线 · 暂无历史详情" }
                Text(stateText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(if (d.online) "在线" else "离线", color = if (d.online) Color(0xFF16A34A) else Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun ToolsHomeScreen(prefs: AppPrefs, topNav: @Composable () -> Unit, open: (String) -> Unit) = ScreenShell("工具", "网络诊断 · 本地系统", topNav = topNav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(detectNetworkProfile(ctx, prefs)) }

    fun profileOwnerTarget(p: NetworkProfile): String {
        val v4 = cleanApiText(p.ipv4Exit)
        val v6 = cleanApiText(p.ipv6Address)
        return when {
            v4.isNotBlank() && v4 !in listOf("未测", "未知") && !isPrivateOrLocalIp(v4) -> v4
            v6.isNotBlank() && v6 !in listOf("未见", "未知") && !isPrivateOrLocalIp(v6) -> v6
            else -> ""
        }
    }

    fun reloadNetworkProfile(forceCarrier: Boolean = true) {
        val base = detectNetworkProfile(ctx, prefs)
        val target = profileOwnerTarget(base)
        profile = if (forceCarrier && target.isNotBlank()) base.copy(operator = "识别中") else base
        if (forceCarrier && target.isNotBlank()) {
            scope.launch {
                val owner = withContext(Dispatchers.IO) {
                    runCatching { operatorLookup(target, prefs) }.getOrElse { inferOperatorFast(target, detectNetworkBrief(ctx).transport) }
                }.ifBlank { inferOperatorFast(target, detectNetworkBrief(ctx).transport) }
                // 只更新运营商，不覆盖刷新过程中可能更新过的地址/NAT。
                profile = profile.copy(operator = owner)
            }
        }
    }

    LaunchedEffect(prefs.hub, prefs.token) {
        reloadNetworkProfile(forceCarrier = true)
    }

    ExpressiveCard("网络状态", "本机接口 · 最近 NAT / 延迟结果", Icons.Rounded.Public, Color(0xFF2563EB)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("当前网络", fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = { reloadNetworkProfile(forceCarrier = true) },
                shape = CircleShape,
                color = Color(0xFF2563EB).copy(alpha = .10f),
                modifier = Modifier.size(34.dp)
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Refresh, null, tint = Color(0xFF2563EB), modifier = Modifier.size(18.dp)) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkStatusTile("IPv4出口", profile.ipv4Exit, Icons.Rounded.Public, Color(0xFF2563EB), Modifier.weight(1f), clickable = true) { open("tool_dns") }
                NetworkStatusTile("IPv6地址", profile.ipv6Address, Icons.Rounded.SettingsEthernet, Color(0xFF06B6D4), Modifier.weight(1f), clickable = true) { open("tool_dns") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkStatusTile("NAT类型", profile.natType, Icons.Rounded.Router, Color(0xFF7C3AED), Modifier.weight(1f), clickable = true) { open("tool_nat") }
                NetworkStatusTile("运营商", profile.operator, Icons.Rounded.CellTower, Color(0xFF0EA5E9), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkStatusTile("本地IP", profile.localIp, Icons.Rounded.Devices, Color(0xFF64748B), Modifier.weight(1f))
                NetworkStatusTile("优先级", profile.priority, Icons.Rounded.Timeline, Color(0xFFF59E0B), Modifier.weight(1f))
            }
        }
    }
    ToolGroupLabel("网络检测")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolHubTile("延迟测试", "Ping/TCP/HTTP", Icons.Rounded.Speed, Color(0xFF2563EB), Modifier.weight(1f)) { open("tool_ping") }
        ToolHubTile("端口测试", "TCP Connect", Icons.Rounded.SettingsEthernet, Color(0xFF0EA5E9), Modifier.weight(1f)) { open("tool_port") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolHubTile("路由追踪", "Traceroute/IP路径", Icons.Rounded.AltRoute, Color(0xFF2563EB), Modifier.weight(1f)) { open("tool_trace") }
        ToolHubTile("UDP探测", "STUN/DNS/NTP", Icons.Rounded.SyncAlt, Color(0xFF06B6D4), Modifier.weight(1f)) { open("tool_udp") }
    }

    ToolGroupLabel("解析与公网")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolHubTile("DNS解析", "A/AAAA/归属", Icons.Rounded.Dns, Color(0xFF2563EB), Modifier.weight(1f)) { open("tool_dns") }
        ToolHubTile("IPv6可用性", "IPv6/DNS/优先级", Icons.Rounded.SettingsEthernet, Color(0xFF06B6D4), Modifier.weight(1f)) { open("tool_ipv6") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolHubTile("NAT检测", "RFC5780 / 3489", Icons.Rounded.Router, Color(0xFF7C3AED), Modifier.weight(1f)) { open("tool_nat") }
        ToolHubTile("DNS质量", "多DNS延迟", Icons.Rounded.TravelExplore, Color(0xFF7C3AED), Modifier.weight(1f)) { open("tool_dns_quality") }
    }

    ToolGroupLabel("设备与链路")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolHubTile("无线漫游", "RSSI/AP切换", Icons.Rounded.Wifi, Color(0xFF16A34A), Modifier.weight(1f)) { open("tool_roam") }
        ToolHubTile("MTU检测", "分片/路径MTU", Icons.Rounded.SettingsEthernet, Color(0xFF0EA5E9), Modifier.weight(1f)) { open("tool_mtu") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolHubTile("SSH命令", "NAS/路由器", Icons.Rounded.Terminal, Color(0xFF64748B), Modifier.weight(1f)) { open("tool_ssh") }
        ToolHubTile("服务监控", "TCP/UDP可达", Icons.Rounded.Public, Color(0xFFF59E0B), Modifier.weight(1f)) { open("tool_service") }
    }
}

@Composable
fun NetworkStatusTile(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, clickable: Boolean = false, onClick: () -> Unit = {}) {
    val m = if (clickable) modifier.clickable { onClick() } else modifier
    Surface(
        modifier = m.height(62.dp),
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = .08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .12f))
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1)
                Text(value.ifBlank { "未知" }, fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ToolGroupLabel(text: String) {
    Text(text, fontSize = 11.8.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 0.dp))
}

@Composable
fun ToolHubTile(title: String, subtitle: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .height(96.dp)
            .shadow(3.dp, RoundedCornerShape(24.dp), clip = false)
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .84f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(15.dp)).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(9.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                fontSize = 10.6.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ToolEntry(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    ExpressiveCard(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = color,
        headerAction = { Icon(Icons.Rounded.ChevronRight, null, tint = color, modifier = Modifier.size(22.dp)) },
        modifier = Modifier.clip(RoundedCornerShape(30.dp)).clickable { onClick() }
    ) { }
}

@Composable
fun PingScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("延迟测试", "ICMP / TCP / HTTP · IPv4 / IPv6 · 真实时间轴", onBack) { PingTool(prefs) }
@Composable
fun DnsScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("DNS 解析", "双 DNS 备选与运营商识别", onBack) { DnsTool(prefs) }
@Composable
fun PortProbeScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("端口测试", "TCP Connect · Telnet 同类 · IPv4 / IPv6", onBack) { TcpTool(prefs) }

@Composable
fun UdpProbeScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("UDP 探测", "STUN / DNS / NTP · 无响应不等于关闭", onBack) { UdpTool(prefs) }
@Composable
fun TraceScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("路由追踪", "Traceroute · 追踪域名经过的 IP", onBack) { TraceTool(prefs) }
@Composable
fun NatScreen(prefs: AppPrefs, onBack: () -> Unit, openHistory: () -> Unit) = DetailShell("NAT 检测", "RFC5780 行为发现 · RFC3489 TEST 1-4", onBack) { NatTool(prefs, openHistory) }

@Composable
fun NatHistoryScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("NAT 记录", "最近 50 条 · 左滑删除", onBack) { NatHistoryTool(prefs) }
@Composable
fun SshScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("SSH 命令", "二级页面执行，返回工具页", onBack) { SshTool(prefs) }

@Composable
fun SpeedTemplateScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("峰值外网测速", "测到峰值即停 · 公网模板", onBack) { SpeedTemplateTool(prefs) }
@Composable
fun LanSpeedScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("局域网测速", "手机到路由器 / NAS 吞吐", onBack) { LanSpeedTool(prefs) }
@Composable
fun LoadLatencyScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("负载延迟", "下载满载时同步 Ping", onBack) { LoadLatencyTool(prefs) }
@Composable
fun Ipv6TestScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("IPv6 可用性", "公网出口 / 双栈 / AAAA / ASN", onBack) { Ipv6TestTool(prefs) }
@Composable
fun WifiRoamingScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("无线漫游", "RSSI / AP切换 / 网关延迟", onBack) { WifiRoamingTool(prefs) }
@Composable
fun MtuScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("MTU 检测", "路径 MTU · 分片探测", onBack) { MtuTool(prefs) }
@Composable
fun DnsQualityScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("DNS 质量", "多 DNS 延迟与 A/AAAA 对比", onBack) { DnsQualityTool(prefs) }
@Composable
fun ServiceMonitorScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("服务监控", "TCP / UDP 服务可达性", onBack) { ServiceMonitorTool(prefs) }


data class DownloadTemplate(val name: String, val url: String, val note: String)
data class SpeedTestResult(val avgMbps: Double, val peakMbps: Double, val totalBytes: Long, val seconds: Int, val note: String)
data class SpeedSample(val second: Int, val mbps: Double, val avgMbps: Double)
data class LoadLatencyResult(val baselineAvg: Double, val loadedAvg: Double, val loadedMax: Int?, val lossRate: Double, val note: String)
data class Ipv6TestRow(val name: String, val status: String, val detail: String, val ok: Boolean?, val route: String = "")
data class MtuProbeResult(val summary: String, val rows: List<Pair<Int, Boolean>>)
data class WifiSample(val time: String, val ssid: String, val bssid: String, val rssi: Int, val latency: Int?, val lost: Boolean, val linkMbps: Int = 0)
data class DnsQualityRow(val server: String, val ms: Long?, val a: String, val aaaa: String, val note: String)
data class ServiceTarget(val name: String, val host: String, val port: Int, val protocol: String)

fun downloadTemplates(): List<DownloadTemplate> = listOf(
    DownloadTemplate("Cloudflare 25MB", "https://speed.cloudflare.com/__down?bytes=25000000", "国际 CDN；适合粗测公网下载吞吐"),
    DownloadTemplate("Cloudflare 10MB", "https://speed.cloudflare.com/__down?bytes=10000000", "流量较小，适合移动网络快速自测"),
    DownloadTemplate("Hetzner 10MB", "https://speed.hetzner.de/10MB.bin", "海外下载源；结果受国际链路影响"),
    DownloadTemplate("GitHub 仓库包", "https://github.com/OnlyChallgener/LabProbeApp/archive/refs/heads/main.zip", "测试 GitHub 下载体验，不代表宽带上限"),
    DownloadTemplate("自定义URL", "", "手动输入下载地址")
)


@Composable
fun Ipv6TestTool(prefs: AppPrefs) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<Ipv6TestRow>>(emptyList()) }
    var summary by remember { mutableStateOf("等待检测") }
    val blue = Color(0xFF2563EB)
    ExpressiveCard("IPv6 配置", "对标 test-ipv6：IPv4/IPv6公网出口、双栈、大包、AAAA 与 ASN 分项检测。", Icons.Rounded.SettingsEthernet, Color(0xFF06B6D4)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TinyInfoParam("目标", "test-ipv6", Icons.Rounded.Dns, blue, Modifier.weight(1f))
            TinyInfoParam("模式", "自动检测", Icons.Rounded.Timeline, Color(0xFF06B6D4), Modifier.weight(1f))
        }
        PillButton(if (running) "检测中..." else "开始 IPv6 检测", Icons.Rounded.PlayArrow, enabled = !running, accent = Color(0xFF06B6D4)) {
            scope.launch {
                running = true
                summary = "检测中..."
                rows = emptyList()
                rows = runIpv6AvailabilityTest { partial -> rows = partial }
                val okCount = rows.count { it.ok == true }
                val total = rows.count { it.ok != null }.coerceAtLeast(1)
                val hasV6 = rows.any { it.name.contains("IPv6 公网出口") && it.ok == true }
                summary = "IPv6 可用性 ${okCount}/${total} · ${if (hasV6) "IPv6 可用" else "IPv6 不可用或受限"}"
                running = false
            }
        }
    }
    ExpressiveCard("IPv6 结果", summary, Icons.Rounded.FactCheck, blue) {
        if (rows.isEmpty()) Text("点击开始后检测 IPv4/IPv6 公网出口、双栈优先级、大数据包、AAAA 解析和 ASN 运营商。", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f), lineHeight = 17.sp)
        rows.forEach { row ->
            val c = when (row.ok) { true -> Color(0xFF16A34A); false -> Color(0xFFEF4444); null -> Color(0xFFF59E0B) }
            Surface(
                modifier = Modifier.fillMaxWidth().then(if (row.route.isNotBlank()) Modifier.clickable { } else Modifier),
                shape = RoundedCornerShape(18.dp),
                color = c.copy(alpha = .07f),
                border = androidx.compose.foundation.BorderStroke(1.dp, c.copy(alpha = .10f))
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(13.dp)).background(c.copy(alpha=.12f)), contentAlignment = Alignment.Center) {
                        Icon(if (row.ok == true) Icons.Rounded.CheckCircle else if (row.ok == false) Icons.Rounded.Error else Icons.Rounded.Info, null, tint = c, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, fontSize = 13.4.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text(row.detail.ifBlank { row.status }, fontSize = 11.4.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                    }
                    Text(row.status, fontSize = 12.sp, fontWeight = FontWeight.Black, color = c, maxLines = 1)
                }
            }
        }
        Text("说明：对标 test-ipv6 的分项结果；Android 客户端无法完全等同浏览器站点，但会分别标注使用 IPv4/IPv6 的链路。", fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.56f), lineHeight = 15.sp)
    }
}

@Composable
fun SpeedTemplateTool(prefs: AppPrefs) {
    val templates = remember { downloadTemplates() }
    var template by remember { mutableStateOf(templates.first().name) }
    var url by remember { mutableStateOf(templates.first().url) }
    var duration by remember { mutableStateOf("8") }
    var mode by remember { mutableStateOf("下载") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var current by remember { mutableStateOf("--") }
    var avg by remember { mutableStateOf("--") }
    var peak by remember { mutableStateOf("--") }
    var total by remember { mutableStateOf("0.0 MB") }
    var samples by remember { mutableStateOf<List<SpeedSample>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val blue = Color(0xFF2563EB)
    fun applyTemplate(name: String) {
        template = name
        templates.firstOrNull { it.name == name }?.let { if (it.url.isNotBlank()) url = it.url }
    }
    ExpressiveCard("测速配置", "峰值测速：预热后多次采样，速度稳定即自动停止。", Icons.Rounded.Speed, blue) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("模板", template, templates.map { it.name }, { applyTemplate(it) }, Icons.Rounded.Speed, Modifier.weight(1f))
            TinyParamSelectIcon("模式", mode, listOf("下载", "上传预留", "双向预留"), { mode = it }, Icons.Rounded.SyncAlt, Modifier.weight(1f))
        }
        CompactIconHistoryInput("URL", "https://...", url, { url = it }, "speed_url", prefs, Icons.Rounded.Public, KeyboardType.Text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            TinyParamInputIcon("时长", duration, { duration = it.filter { c -> c.isDigit() }.take(3) }, Icons.Rounded.HourglassEmpty, KeyboardType.Number, Modifier.weight(1f))
            TinyInfoParam("停止", "峰值稳定", Icons.Rounded.Info, blue, Modifier.weight(1f))
        }
        PillButton(if (running) "峰值测速中..." else "开始峰值测速", Icons.Rounded.PlayArrow, enabled = !running, accent = blue) {
            scope.launch {
                if (mode != "下载") { status = "上传/双向需要自建测速服务端，个人测试版先保留入口。"; return@launch }
                val safeUrl = url.trim()
                if (!safeUrl.startsWith("http")) { status = "请输入有效 HTTP/HTTPS 下载 URL"; return@launch }
                prefs.addHistory("speed_url", safeUrl)
                running = true; status = "预热并寻找峰值..."; current = "--"; avg = "--"; peak = "--"; total = "0.0 MB"; samples = emptyList()
                val result = runDownloadTemplateTest(safeUrl, duration.toIntOrNull()?.coerceIn(3, 60) ?: 8) { cur, av, pk, bytes ->
                    current = String.format(Locale.US, "%.1f Mbps", cur)
                    avg = String.format(Locale.US, "%.1f Mbps", av)
                    peak = String.format(Locale.US, "%.1f Mbps", pk)
                    total = formatTraffic(bytes)
                    val next = (samples.lastOrNull()?.second ?: 0) + 1
                    samples = (samples + SpeedSample(next, cur.coerceAtLeast(0.0), av.coerceAtLeast(0.0))).takeLast(120)
                }
                status = result.note
                avg = String.format(Locale.US, "%.1f Mbps", result.avgMbps)
                peak = String.format(Locale.US, "%.1f Mbps", result.peakMbps)
                total = formatTraffic(result.totalBytes)
                running = false
            }
        }
    }
    ExpressiveCard("测速结果", status, Icons.Rounded.Timeline, Color(0xFF0EA5E9)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("当前", current, blue, Modifier.weight(1f))
            StatChip("平均", avg, Color(0xFF0EA5E9), Modifier.weight(1f))
            StatChip("峰值", peak, Color(0xFF7C3AED), Modifier.weight(1f))
        }
        LabSpeedChart(samples, modifier = Modifier.fillMaxWidth().height(260.dp))
        Text("总流量 $total · 峰值稳定后自动停止；测速源/CDN/运营商会影响结果，不等于宽带物理上限。", fontSize = 11.4.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f), lineHeight = 16.sp)
    }
}


@Composable
fun LanSpeedTool(prefs: AppPrefs) {
    var url by remember { mutableStateOf("http://192.168.5.1:8989/download") }
    var duration by remember { mutableStateOf("8") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("等待测速") }
    var current by remember { mutableStateOf("--") }
    var avg by remember { mutableStateOf("--") }
    var peak by remember { mutableStateOf("--") }
    var total by remember { mutableStateOf("0.0 MB") }
    var samples by remember { mutableStateOf<List<SpeedSample>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val blue = Color(0xFF0EA5E9)
    ExpressiveCard("局域网配置", "需要路由器 / NAS 提供 HTTP 大文件或 Homebox 下载地址。", Icons.Rounded.SettingsEthernet, blue) {
        CompactIconHistoryInput("服务地址", "http://192.168.5.1:8989/download", url, { url = it }, "lan_speed_url", prefs, Icons.Rounded.Public, KeyboardType.Text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            TinyParamInputIcon("时长", duration, { duration = it.filter { c -> c.isDigit() }.take(3) }, Icons.Rounded.HourglassEmpty, KeyboardType.Number, Modifier.weight(1f))
            TinyInfoParam("模式", "下载峰值", Icons.Rounded.Speed, blue, Modifier.weight(1f))
        }
        PillButton(if (running) "局域网测速中..." else "开始局域网测速", Icons.Rounded.PlayArrow, enabled = !running, accent = blue) {
            scope.launch {
                val safeUrl = url.trim()
                if (!safeUrl.startsWith("http")) { status = "请输入局域网 HTTP 下载地址"; return@launch }
                prefs.addHistory("lan_speed_url", safeUrl)
                running = true; status = "连接局域网测速服务..."; current="--"; avg="--"; peak="--"; total="0.0 MB"; samples=emptyList()
                val result = runDownloadTemplateTest(safeUrl, duration.toIntOrNull()?.coerceIn(3, 60) ?: 8) { cur, av, pk, bytes ->
                    current = String.format(Locale.US, "%.1f Mbps", cur)
                    avg = String.format(Locale.US, "%.1f Mbps", av)
                    peak = String.format(Locale.US, "%.1f Mbps", pk)
                    total = formatTraffic(bytes)
                    val next = (samples.lastOrNull()?.second ?: 0) + 1
                    samples = (samples + SpeedSample(next, cur.coerceAtLeast(0.0), av.coerceAtLeast(0.0))).takeLast(120)
                }
                status = result.note
                avg = String.format(Locale.US, "%.1f Mbps", result.avgMbps)
                peak = String.format(Locale.US, "%.1f Mbps", result.peakMbps)
                total = formatTraffic(result.totalBytes)
                running = false
            }
        }
    }
    ExpressiveCard("局域网结果", status, Icons.Rounded.Timeline, blue) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("当前", current, blue, Modifier.weight(1f))
            StatChip("平均", avg, Color(0xFF2563EB), Modifier.weight(1f))
            StatChip("峰值", peak, Color(0xFF7C3AED), Modifier.weight(1f))
        }
        LabSpeedChart(samples, modifier = Modifier.fillMaxWidth().height(260.dp))
        Text("总流量 $total · 局域网测速取决于服务端、Wi‑Fi 协商速率、手机性能和路由器 CPU。", fontSize = 11.4.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f), lineHeight = 16.sp)
    }
}

@Composable
fun LoadLatencyTool(prefs: AppPrefs) {
    var url by remember { mutableStateOf("https://speed.cloudflare.com/__down?bytes=25000000") }
    var pingTarget by remember { mutableStateOf("223.5.5.5") }
    var duration by remember { mutableStateOf("8") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("等待测试") }
    var samples by remember { mutableStateOf<List<WifiSample>>(emptyList()) }
    var summary by remember { mutableStateOf<LoadLatencyResult?>(null) }
    val scope = rememberCoroutineScope()
    val accent = Color(0xFF7C3AED)
    ExpressiveCard("负载配置", "先测空闲 Ping，再下载满载并同步采样延迟。", Icons.Rounded.Timeline, accent) {
        CompactIconHistoryInput("下载URL", "https://...", url, { url = it }, "load_speed_url", prefs, Icons.Rounded.Public, KeyboardType.Text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            TinyHistoryParamInputIcon("Ping目标", "223.5.5.5", pingTarget, { pingTarget = it }, "load_ping_target", prefs, Icons.Rounded.Router, KeyboardType.Text, Modifier.weight(1f))
            TinyParamInputIcon("时长", duration, { duration = it.filter { c -> c.isDigit() }.take(3) }, Icons.Rounded.HourglassEmpty, KeyboardType.Number, Modifier.weight(1f))
        }
        PillButton(if (running) "负载测试中..." else "开始负载延迟", Icons.Rounded.PlayArrow, enabled = !running, accent = accent) {
            scope.launch {
                val safeUrl = url.trim()
                if (!safeUrl.startsWith("http")) { status = "请输入有效下载 URL"; return@launch }
                prefs.addHistory("load_speed_url", safeUrl); prefs.addHistory("load_ping_target", pingTarget)
                running = true; status = "测空闲延迟..."; samples = emptyList(); summary = null
                val result = runLoadLatencyTest(safeUrl, pingTarget, duration.toIntOrNull()?.coerceIn(3, 60) ?: 8) { idx, ms, lost ->
                    samples = (samples + WifiSample(nowClock(), "load", "", 0, ms, lost, 0)).takeLast(120)
                    status = "负载采样 ${idx} 次"
                }
                summary = result; status = result.note; running = false
            }
        }
    }
    ExpressiveCard("负载结果", status, Icons.Rounded.ShowChart, accent) {
        summary?.let { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("空闲", String.format(Locale.US, "%.0fms", r.baselineAvg), Color(0xFF2563EB), Modifier.weight(1f))
                StatChip("满载", String.format(Locale.US, "%.0fms", r.loadedAvg), accent, Modifier.weight(1f))
                StatChip("丢包", String.format(Locale.US, "%.1f%%", r.lossRate), Color(0xFFEF4444), Modifier.weight(1f))
            }
        }
        LabLatencyOnlyChart(samples, modifier = Modifier.fillMaxWidth().height(260.dp))
        Text("用于判断下载满载时是否出现 bufferbloat / 负载延迟升高。", fontSize = 11.4.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f), lineHeight = 16.sp)
    }
}

@Composable
fun WifiRoamingTool(prefs: AppPrefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf<List<WifiSample>>(emptyList()) }
    var status by remember { mutableStateOf("等待测试") }
    var targetMode by remember { mutableStateOf("路由器+外网") }
    var wanTarget by remember { mutableStateOf("223.5.5.5") }
    var sampleMs by remember { mutableStateOf("1000") }
    var timeoutMs by remember { mutableStateOf("1000") }
    var sampleMode by remember { mutableStateOf("标准1s") }
    var job by remember { mutableStateOf<Job?>(null) }
    var hasLocation by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocation = granted
        status = if (granted) "已获取精确定位权限，可读取 SSID/BSSID/RSSI" else "未授予定位权限，SSID/BSSID/RSSI 可能不可用"
    }
    val latest = samples.lastOrNull()
    val validSamples = samples.filter { it.rssi > -120 && it.bssid.isNotBlank() && it.bssid != "02:00:00:00:00:00" }
    val roamCount = validSamples.zipWithNext().count { it.first.ssid == it.second.ssid && it.first.bssid != it.second.bssid }
    val lostCount = samples.count { it.lost }
    val lossRate = if (samples.isEmpty()) "--" else String.format(Locale.US, "%.1f%%", lostCount * 100.0 / samples.size.coerceAtLeast(1))
    val interval = sampleMs.toIntOrNull()?.coerceIn(500, 5000) ?: when (sampleMode) {
        "高频500ms" -> 500
        "低频2s" -> 2000
        else -> 1000
    }
    val timeout = timeoutMs.toIntOrNull()?.coerceIn(300, 5000) ?: 1000
    val effectiveTarget = remember(targetMode, wanTarget) {
        when (targetMode) {
            "仅外网" -> wanTarget.trim().ifBlank { "223.5.5.5" }
            else -> "网关"
        }
    }

    ExpressiveCard(
        "目标",
        null,
        null,
        Color(0xFF2563EB)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoamSegmentButton("路由器+外网", targetMode == "路由器+外网", Modifier.weight(1f)) { targetMode = "路由器+外网" }
            RoamSegmentButton("仅路由器", targetMode == "仅路由器", Modifier.weight(1f)) { targetMode = "仅路由器" }
            RoamSegmentButton("仅外网", targetMode == "仅外网", Modifier.weight(1f)) { targetMode = "仅外网" }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("外网", Modifier.width(52.dp), fontSize = 12.3.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
            OutlinedTextField(
                value = wanTarget,
                onValueChange = { wanTarget = it.trim().take(64) },
                enabled = targetMode != "仅路由器",
                singleLine = true,
                leadingIcon = { FieldIconBox(Icons.Rounded.Public, Color(0xFF2563EB)) },
                textStyle = LocalTextStyle.current.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
                colors = labOutlinedColors(),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f).height(58.dp)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TinyParamInputIcon("采样ms", sampleMs, { sampleMs = it.filter { c -> c.isDigit() }.take(4) }, Icons.Rounded.Schedule, KeyboardType.Number, Modifier.weight(1f))
            TinyParamInputIcon("超时", timeoutMs, { timeoutMs = it.filter { c -> c.isDigit() }.take(4) }, Icons.Rounded.Timer, KeyboardType.Number, Modifier.weight(1f))
        }
        Text("采样模式", fontSize = 12.2.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .66f), modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("标准1s", "高频500ms", "低频2s").forEach { mode ->
                RoamSegmentButton(mode, sampleMode == mode, Modifier.weight(1f)) {
                    sampleMode = mode
                    sampleMs = when (mode) { "高频500ms" -> "500"; "低频2s" -> "2000"; else -> "1000" }
                }
            }
        }
        OutlinedButton(
            onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .35f)),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Rounded.LocationOn, null, Modifier.size(17.dp), tint = Color(0xFF2563EB))
            Spacer(Modifier.width(7.dp))
            Text(if (hasLocation) "已授权 WiFi / 定位信息（用于 BSSID、RSSI）" else "授权 WiFi / 定位信息（用于 BSSID、RSSI）", fontSize = 12.4.sp, fontWeight = FontWeight.Black, color = Color(0xFF2563EB), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { status = "漫游历史将在后续版本接入" },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(.8f).height(52.dp)
            ) { Text("漫游历史", fontSize = 13.sp, fontWeight = FontWeight.Black) }
            Button(
                onClick = {
                    if (running) {
                        job?.cancel(); running = false; status = "已停止"
                    } else {
                        samples = emptyList(); running = true; status = "采集中..."
                        job = scope.launch {
                            while (currentCoroutineContext().isActive) {
                                val info = readWifiSample(ctx, effectiveTarget, timeout)
                                samples = (samples + info).takeLast(720)
                                val okRssi = info.rssi > -120
                                val latestLoss = samples.count { it.lost }
                                val latestValid = samples.filter { it.rssi > -120 && it.bssid.isNotBlank() && it.bssid != "02:00:00:00:00:00" }
                                val latestRoam = latestValid.zipWithNext().count { it.first.ssid == it.second.ssid && it.first.bssid != it.second.bssid }
                                status = if (okRssi) "采样 ${samples.size} 次 · 漫游 $latestRoam 次 · 丢包 $latestLoss" else "Wi‑Fi 信息不可用 · 采样 ${samples.size} 次 · 丢包 $latestLoss"
                                delay(interval.toLong())
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFF64748B) else Color(0xFF2563EB)),
                modifier = Modifier.weight(1.3f).height(52.dp)
            ) {
                Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (running) "停止测试" else "开始测试", fontSize = 13.2.sp, fontWeight = FontWeight.Black)
            }
        }
    }

    ExpressiveCard("实时结果", status, null, Color(0xFF16A34A), headerAction = {
        TextButton(onClick = { status = "漫游历史将在后续版本接入" }) { Text("漫游历史", fontSize = 11.5.sp, fontWeight = FontWeight.Black) }
        if (running) TextButton(onClick = { job?.cancel(); running = false; status = "已停止" }) { Text("停止", fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = Color(0xFF64748B)) }
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("RSSI", latest?.rssi?.takeIf { it > -120 }?.let { "$it" } ?: "—", Color(0xFF16A34A), Modifier.weight(1f))
            StatChip("速率", latest?.linkMbps?.takeIf { it > 0 }?.let { "$it" } ?: "—", Color(0xFF0EA5E9), Modifier.weight(1f))
            StatChip("丢包", lossRate, Color(0xFF64748B), Modifier.weight(1f))
            StatChip("漫游", "${roamCount}", Color(0xFF7C3AED), Modifier.weight(1f))
        }
        RoamPlainInfo("BSSID", latest?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" } ?: "—")
        RoamPlainInfo("网关", if (targetMode == "仅外网") "—" else "自动")
        RoamPlainInfo("外网", if (targetMode == "仅路由器") "—" else wanTarget.ifBlank { "223.5.5.5" })
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .08f))) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ping表", fontSize = 16.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text("${when (targetMode) { "仅外网" -> "外网"; "仅路由器" -> "网关"; else -> "网关/外网" }} · 竖线=漫游", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1)
                }
                LabRoamCharts(samples, running = running, modifier = Modifier.fillMaxWidth())
            }
        }
        if (samples.isNotEmpty()) {
            Text("漫游事件", fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.72f))
            RoamEventTimeline(samples.takeLast(40))
        }
    }
}

@Composable
private fun RoamSegmentButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(46.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) Color(0xFF2563EB).copy(alpha = .10f) else MaterialTheme.colorScheme.surface.copy(alpha = .92f),
        border = BorderStroke(1.dp, if (selected) Color(0xFF2563EB).copy(alpha = .38f) else MaterialTheme.colorScheme.outline.copy(alpha = .14f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 12.2.sp, fontWeight = FontWeight.Black, color = if (selected) Color(0xFF2563EB) else MaterialTheme.colorScheme.onSurface.copy(alpha = .86f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RoamPlainInfo(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label：", Modifier.width(58.dp), fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
        Text(value, fontSize = 12.2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .80f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
fun MtuTool(prefs: AppPrefs) {
    var host by remember { mutableStateOf("223.5.5.5") }
    var protocol by remember { mutableStateOf("IPv4") }
    var result by remember { mutableStateOf("等待检测") }
    var mtuRows by remember { mutableStateOf<List<Pair<Int, Boolean>>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accent = Color(0xFF0EA5E9)
    ExpressiveCard("MTU 配置", "常用档位先测，再对通过/失败临界区间二分细化。", Icons.Rounded.SettingsEthernet, accent) {
        CompactIconHistoryInput("目标", "223.5.5.5", host, { host = it }, "mtu_host", prefs, Icons.Rounded.Dns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("协议", protocol, listOf("IPv4", "IPv6"), { selected ->
                protocol = selected
                if (selected == "IPv6" && (host.isBlank() || host == "223.5.5.5" || host == "www.amazon.com")) host = "2400:3200::1"
                if (selected == "IPv4" && (host.isBlank() || host == "2400:3200::1" || host == "2400:da00::6666")) host = "223.5.5.5"
            }, Icons.Rounded.Public, Modifier.weight(1f))
            TinyInfoParam("方式", if (protocol == "IPv6") "ICMPv6 Echo" else "DF + 二分", Icons.Rounded.Info, accent, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            TinyInfoParam("常用", if (protocol == "IPv6") "1280/1492/1500" else "1492/1500", Icons.Rounded.Timeline, Color(0xFF2563EB), Modifier.weight(1f))
            TinyInfoParam("说明", if (protocol == "IPv6") "payload+48" else "payload+28", Icons.Rounded.Info, Color(0xFF7C3AED), Modifier.weight(1f))
        }
        PillButton(if (running) "检测中..." else "开始 MTU 检测", Icons.Rounded.PlayArrow, enabled = !running, accent = accent) {
            scope.launch {
                running = true; result = "检测中..."; mtuRows = emptyList()
                prefs.addHistory("mtu_host", host)
                val r = runMtuProbeSmart(host, protocol == "IPv6") { rows -> mtuRows = rows }
                mtuRows = r.rows
                result = r.summary
                running = false
            }
        }
    }
    ExpressiveCard("MTU 结果", "估算值仅供排障参考；不同 Android ping 能力会影响结果。", Icons.Rounded.Route, Color(0xFF2563EB)) {
        MtuStepChart(mtuRows, modifier = Modifier.fillMaxWidth().height(118.dp))
        ResultText(result)
    }
}

@Composable
fun DnsQualityTool(prefs: AppPrefs) {
    var domain by remember { mutableStateOf("www.baidu.com") }
    var servers by remember { mutableStateOf("223.5.5.5,119.29.29.29,8.8.8.8,1.1.1.1") }
    var rows by remember { mutableStateOf<List<DnsQualityRow>>(emptyList()) }
    var msg by remember { mutableStateOf("等待测试") }
    val scope = rememberCoroutineScope()
    ExpressiveCard("质量配置", "并行对比多个 DNS 的 A/AAAA 响应时间。", Icons.Rounded.TravelExplore, Color(0xFF7C3AED)) {
        CompactIconHistoryInput("域名", "www.baidu.com", domain, { domain = it }, "dnsq_domain", prefs, Icons.Rounded.Dns)
        CompactIconHistoryInput("DNS", "逗号分隔", servers, { servers = it }, "dnsq_servers", prefs, Icons.Rounded.Storage)
        PillButton("开始 DNS 质量测试", Icons.Rounded.PlayArrow, accent = Color(0xFF7C3AED)) {
            scope.launch {
                msg = "测试中..."; prefs.addHistory("dnsq_domain", domain); prefs.addHistory("dnsq_servers", servers)
                rows = runDnsQuality(domain, servers.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(8))
                msg = "完成：${rows.size} 个 DNS"
            }
        }
    }
    ExpressiveCard("质量结果", msg, Icons.Rounded.Dns, Color(0xFF2563EB)) {
        if (rows.isEmpty()) Text("暂无结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.55f))
        rows.forEach { r ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha=.055f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row { Text(r.server, fontWeight = FontWeight.Black, fontSize = 12.7.sp); Spacer(Modifier.weight(1f)); Text(r.ms?.let { "${it}ms" } ?: "超时", color = if (r.ms == null) Color(0xFFEF4444) else Color(0xFF2563EB), fontWeight = FontWeight.Black, fontSize = 12.7.sp) }
                    Text("A ${r.a.ifBlank { "--" }}", fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("AAAA ${r.aaaa.ifBlank { "--" }}", fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun ServiceMonitorTool(prefs: AppPrefs) {
    var targetsText by remember { mutableStateOf("NAS HTTPS,192.168.5.1,5001,TCP\n路由SSH,192.168.5.1,54133,TCP\n阿里DNS,223.5.5.5,53,UDP\nGitHub,github.com,443,TCP") }
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ExpressiveCard("监控配置", "每行：名称,主机,端口,TCP/UDP。", Icons.Rounded.Public, Color(0xFFF59E0B)) {
        OutlinedTextField(
            value = targetsText,
            onValueChange = { targetsText = it },
            minLines = 4,
            maxLines = 6,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp),
            colors = labOutlinedColors(),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        )
        PillButton(if (running) "检测中..." else "开始服务监控", Icons.Rounded.PlayArrow, enabled = !running, accent = Color(0xFFF59E0B)) {
            scope.launch {
                running = true; result = listOf("检测中...")
                result = runServiceMonitor(parseServiceTargets(targetsText), prefs)
                running = false
            }
        }
    }
    ExpressiveCard("监控结果", "TCP 结果较明确；UDP 无响应仍需谨慎解释。", Icons.Rounded.Storage, Color(0xFFF59E0B)) {
        if (result.isEmpty()) Text("暂无结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.55f))
        result.forEach { line -> ResultText(line) }
    }
}

@Composable
fun PingTool(prefs: AppPrefs) {
    var host by remember { mutableStateOf(prefs.pingHost) }
    var count by remember { mutableStateOf(prefs.pingCount) }
    var interval by remember { mutableStateOf(prefs.pingInterval) }
    var timeout by remember { mutableStateOf(prefs.pingTimeout) }
    var protocol by remember { mutableStateOf(prefs.pingProtocol) }
    var ipMode by remember { mutableStateOf(prefs.pingIpMode) }
    var dnsMode by remember { mutableStateOf(prefs.pingDnsMode) }
    var port by remember { mutableStateOf(prefs.pingPort) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var points by remember { mutableStateOf<List<PingPoint>>(emptyList()) }
    var log by remember { mutableStateOf("等待测试") }
    var runMode by remember { mutableStateOf("高频采样，波形滑动，真实时间轴。") }
    var history by remember { mutableStateOf(prefs.pingHistory()) }
    var showHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val blue = Color(0xFF2563EB)
    val showPort = protocol.startsWith("TCP") || protocol.startsWith("HTTP")

    if (showHistory) {
        PingHistoryDialog(
            history = history,
            bytes = prefs.pingHistoryBytes(),
            onClear = { prefs.clearPingHistory(); history = emptyList() },
            onDismiss = { showHistory = false }
        )
    }

    ExpressiveCard("参数", runMode, Icons.Rounded.Tune, blue) {
        CompactIconHistoryInput("目标", "net86.dynv6.net / 192.168.0.1", host, { host = it; prefs.pingHost = it }, "ping_host", prefs, Icons.Rounded.Dns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("协议", protocol, listOf("ICMP", "TCP", "HTTP HEAD", "HTTP GET"), { protocol = it; prefs.pingProtocol = it }, Icons.Rounded.SettingsEthernet, Modifier.weight(1f))
            TinyParamSelectIcon("IP策略", ipMode, listOf("自动", "IPv6优先", "IPv4优先", "仅IPv6", "仅IPv4"), { ipMode = it; prefs.pingIpMode = it }, Icons.Rounded.Router, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("DNS", dnsMode, listOf("自动DNS", "优先AAAA", "优先A", "系统默认"), { dnsMode = it; prefs.pingDnsMode = it }, Icons.Rounded.Public, Modifier.weight(1f))
            if (showPort) {
                TinyParamInputIcon("端口", port, { port = it; prefs.pingPort = it }, Icons.Rounded.SettingsEthernet, KeyboardType.Number, Modifier.weight(1f))
            } else {
                TinyParamSelectIcon("间隔", interval, listOf("25", "30", "50", "100", "200", "500", "1000"), { interval = it; prefs.pingInterval = it }, Icons.Rounded.HourglassEmpty, Modifier.weight(1f), "ms")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("次数", count, listOf("200", "500", "1000", "2000"), { count = it; prefs.pingCount = it }, Icons.Rounded.Repeat, Modifier.weight(1f))
            if (showPort) {
                TinyParamSelectIcon("间隔", interval, listOf("25", "30", "50", "100", "200", "500", "1000"), { interval = it; prefs.pingInterval = it }, Icons.Rounded.HourglassEmpty, Modifier.weight(1f), "ms")
            }
            TinyParamSelectIcon("超时", timeout, listOf("自动", "300", "500", "800", "1000", "1500", "3000"), { timeout = it; prefs.pingTimeout = it }, Icons.Rounded.HourglassEmpty, Modifier.weight(1f), if (timeout == "自动") "" else "ms")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                prefs.addHistory("ping_host", host)
                running = true
                points = emptyList()
                log = "开始测试..."
                runMode = "正在解析 DNS 与启动稳定采样。"
                job?.cancel()
                job = scope.launch {
                    val c = (count.toIntOrNull() ?: 1000).coerceIn(1, 5000)
                    val inter = (interval.toLongOrNull() ?: 500L).coerceIn(10L, 10_000L)
                    val to = autoPingTimeoutMs(inter, timeout)
                    val pt = (port.toIntOrNull() ?: defaultPortFor(host, protocol)).coerceIn(1, 65535)
                    val buffer = mutableListOf<PingPoint>()
                    var lastUi = 0L
                    val wakeLock = runCatching {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LabProbe:PingRun").apply { acquire(60 * 60 * 1000L) }
                    }.getOrNull()
                    try {
                        val result = runLatencySeries(host, protocol, ipMode, dnsMode, pt, c, inter, to) { p ->
                            buffer += p
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUi >= 1000L || p.index >= c) {
                                points = buffer.toList()
                                log = buffer.takeLast(9).joinToString("\n") { it.text }
                                lastUi = now
                            }
                        }
                        points = result.points
                        val entry = buildPingHistoryEntry(host, protocol, ipMode, dnsMode, result, c)
                        prefs.addPingHistory(entry)
                        history = prefs.pingHistory()
                        log = result.points.takeLast(9).joinToString("\n") { it.text }
                            .ifBlank { "没有收到有效响应" } + "\n${result.mode} · 实际耗时 ${formatElapsedMs(result.elapsedMs)}"
                        runMode = result.mode + " · ${formatRate(result.points)} 次/s"
                    } finally {
                        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                        running = false
                    }
                }
            }, enabled = !running, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = blue)) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (points.isEmpty()) "开始" else "重新")
            }
            Button(onClick = { running = false; job?.cancel(); log = if (points.isEmpty()) "已停止" else log + "\n已停止" }, enabled = running, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = blue, disabledContainerColor = blue.copy(alpha=.11f), disabledContentColor = blue.copy(alpha=.34f))) {
                Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("停止")
            }
        }
    }
    PingLatencyCard(points = points, accent = blue, onHistory = { showHistory = true })
    ExpressiveCard("响应日志", null, Icons.Rounded.Notes, Color(0xFF64748B)) { ResultText(log) }
}

@Composable
fun PingLatencyCard(points: List<PingPoint>, accent: Color, onHistory: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(1.dp, shape, clip = false),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.82f))
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.ShowChart, null, tint = accent, modifier = Modifier.size(17.dp)) }
                Spacer(Modifier.width(9.dp))
                Text("延迟", Modifier.weight(1f), fontSize = 14.8.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                PingRatePillCompact(points)
                Spacer(Modifier.width(5.dp))
                PingLossPillCompact(points)
                Spacer(Modifier.width(5.dp))
                Surface(onClick = onHistory, shape = CircleShape, color = accent.copy(alpha = .10f), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .16f))) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.History, null, Modifier.size(15.dp), tint = accent) }
                }
            }
            PingChart(points, 1000L)
            PingStats(points)
        }
    }
}

@Composable
fun PingRatePillCompact(points: List<PingPoint>) {
    val rate = formatRate(points)
    Surface(shape = RoundedCornerShape(50), color = Color(0xFF2563EB).copy(alpha = .10f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .14f))) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Speed, null, Modifier.size(12.dp), tint = Color(0xFF2563EB))
            Spacer(Modifier.width(3.dp))
            Text("真实 ${rate}次/s", fontSize = 9.8.sp, fontWeight = FontWeight.Black, color = Color(0xFF2563EB), maxLines = 1)
        }
    }
}

@Composable
fun PingLossPillCompact(points: List<PingPoint>) {
    val sent = points.size
    val okCount = points.count { it.ms != null }
    val loss = if (sent == 0) 0 else ((sent - okCount) * 100 / sent)
    Surface(shape = RoundedCornerShape(50), color = Color(0xFF2563EB).copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .12f))) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFEF4444)))
            Spacer(Modifier.width(4.dp))
            Text("丢包 $loss%", fontSize = 9.8.sp, fontWeight = FontWeight.Black, color = Color(0xFF2563EB), maxLines = 1)
        }
    }
}

@Composable
fun PingRatePill(points: List<PingPoint>) {
    val rate = formatRate(points)
    Surface(shape = RoundedCornerShape(50), color = Color(0xFF2563EB).copy(alpha = .10f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .14f))) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Speed, null, Modifier.size(13.dp), tint = Color(0xFF2563EB))
            Spacer(Modifier.width(4.dp))
            Text("真实 ${rate}次/s", fontSize = 10.6.sp, fontWeight = FontWeight.Black, color = Color(0xFF2563EB), maxLines = 1)
        }
    }
}

@Composable
fun PingHistoryDialog(history: List<PingHistoryEntry>, bytes: Int, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", fontWeight = FontWeight.Black) } },
        dismissButton = { TextButton(onClick = onClear, enabled = history.isNotEmpty()) { Text("清空", fontWeight = FontWeight.Bold) } },
        shape = RoundedCornerShape(30.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
        title = { Text("延迟测试历史", fontWeight = FontWeight.Black, fontSize = 19.sp) },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (history.isEmpty()) {
                    Text("暂无历史。完成一次测试后自动保存最近 10 条汇总。", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f))
                }
                history.forEach { item -> PingHistoryItem(item) }
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF2563EB).copy(alpha = .07f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .10f))) {
                    Text("历史记录占用：约 ${formatBytes(bytes)} · 最多 10 条", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                }
            }
        }
    )
}

@Composable
fun PingHistoryItem(item: PingHistoryEntry) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val avg = item.avg?.let { "${it}ms" } ?: "--"
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { expanded = !expanded },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .13f)),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldIconBox(Icons.Rounded.History)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${item.protocol} · ${item.target}", fontSize = 12.7.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.sent}/${item.count} 次 · 平均 $avg · 丢包 ${item.loss}% · ${String.format(Locale.US, "%.1f", item.rate)}次/s", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
            }
            AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("时间：${item.time}", fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f))
                    Text("解析：${item.resolvedIp.ifBlank { "未解析" }} · ${item.ipMode} · ${item.dnsMode}", fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f))
                    Text("最低 ${item.min?.let { "${it}ms" } ?: "--"} · 最高 ${item.max?.let { "${it}ms" } ?: "--"} · 耗时 ${formatElapsedMs(item.elapsedMs)}", fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f))
                }
            }
        }
    }
}

private fun pingNiceYMax(raw: Int): Int = when {
    raw <= 15 -> 15
    raw <= 30 -> 30
    raw <= 60 -> 60
    raw <= 90 -> 90
    raw <= 120 -> 120
    raw <= 160 -> 160
    raw <= 200 -> 200
    raw <= 240 -> 240
    raw <= 320 -> 320
    raw <= 400 -> 400
    raw <= 600 -> 600
    raw <= 1000 -> 1000
    else -> ((raw + 499) / 500) * 500
}

private fun pingYTicks(yMax: Int): List<Int> {
    val safeMax = yMax.coerceAtLeast(120)
    val step = (safeMax / 4).coerceAtLeast(30)
    return listOf(0, step, step * 2, step * 3, safeMax).distinct().take(5)
}

private fun formatSecondsLabel(sec: Float): String {
    return if (sec < 3f && sec != sec.roundToInt().toFloat()) String.format(Locale.US, "%.1fs", sec) else "${sec.roundToInt()}s"
}

private fun formatPingAxisSeconds(sec: Float, stepSec: Float): String {
    return when {
        stepSec < 1f -> String.format(Locale.US, "%.1fs", sec)
        stepSec < 3f && abs(sec - sec.roundToInt()) > 0.08f -> String.format(Locale.US, "%.1fs", sec)
        else -> "${sec.roundToInt()}s"
    }
}

private fun pingVisualBucketMs(intervalMs: Long): Long = 1000L

private fun buildPingBuckets(points: List<PingPoint>, intervalMs: Long): List<PingBucket> {
    if (points.isEmpty()) return emptyList()
    val bucketMs = 1000L
    return points
        .groupBy { (it.elapsedMs.coerceAtLeast(0L) / bucketMs) }
        .toSortedMap()
        .map { (bucket, list) ->
            val ok = list.mapNotNull { it.ms }
            PingBucket(
                startMs = bucket * bucketMs,
                avgMs = if (ok.isEmpty()) null else ok.average().roundToInt(),
                peakMs = ok.maxOrNull(),
                hasLoss = list.any { it.ms == null },
                sampleCount = list.size
            )
        }
}

@Composable
fun PingLossPill(points: List<PingPoint>) {
    val sent = points.size
    val okCount = points.count { it.ms != null }
    val loss = if (sent == 0) 0 else ((sent - okCount) * 100 / sent)
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFF2563EB).copy(alpha = .08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .12f))
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFEF4444)))
            Spacer(Modifier.width(5.dp))
            Text("丢包 $loss%", fontSize = 10.6.sp, fontWeight = FontWeight.Black, color = Color(0xFF2563EB), maxLines = 1)
        }
    }
}

@Composable
fun PingChart(points: List<PingPoint>, intervalMs: Long) {
    val scrollState = rememberScrollState()
    val ok = points.mapNotNull { it.ms }
    // Y 轴优先参考当前尾部可视数据，避免历史偶发尖峰把整张图拉到过大的区间。
    val axisOk = remember(points) { points.takeLast(360).mapNotNull { it.ms }.ifEmpty { ok } }
    val minOk = axisOk.minOrNull()
    val maxOk = axisOk.maxOrNull()
    val adaptiveRange = remember(points) {
        if (minOk == null || maxOk == null) {
            0 to 30
        } else {
            val rawRange = (maxOk - minOk).coerceAtLeast(0)
            when {
                maxOk <= 15 && rawRange <= 3 -> (minOk - 3).coerceAtLeast(0) to (maxOk + 5).coerceAtLeast(8)
                maxOk <= 18 && rawRange <= 5 -> (minOk - 4).coerceAtLeast(0) to (maxOk + 6).coerceAtLeast(12)
                maxOk <= 30 && rawRange <= 10 -> (minOk - 5).coerceAtLeast(0) to (maxOk + 8).coerceAtLeast(18)
                maxOk <= 60 -> 0 to pingNiceYMax(maxOk.coerceAtLeast(30))
                else -> 0 to pingNiceYMax((maxOk * 1.12f).roundToInt())
            }
        }
    }
    val yMin = adaptiveRange.first
    val yMax = adaptiveRange.second.coerceAtLeast(yMin + 5)
    val rawLast = points.maxOfOrNull { it.elapsedMs } ?: intervalMs.coerceAtLeast(1000L)
    val rawFirst = points.firstOrNull()?.elapsedMs ?: 0L
    val totalMs = (rawLast - rawFirst).coerceAtLeast(intervalMs.coerceAtLeast(1000L))
    val chartSurfaceColor = MaterialTheme.colorScheme.surface

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(194.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        val axisWidth = 30.dp
        val baseWidth = maxWidth
        val plotViewportWidth = (baseWidth - axisWidth).coerceAtLeast(120.dp)
        val extraWidth = when {
            points.size <= 80 -> 0.dp
            points.size <= 300 -> ((points.size - 80) * 1.0f).dp
            points.size <= 1000 -> (220 + (points.size - 300) * .42f).dp
            else -> (520 + (points.size - 1000) * .20f).dp
        }
        val chartWidth = (plotViewportWidth + extraWidth).coerceAtLeast(plotViewportWidth)
        LaunchedEffect(points.size) {
            if (points.size > 120) scrollState.scrollTo(scrollState.maxValue)
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .10f)),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                if (points.isEmpty()) {
                    Text(
                        "等待测试",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha=.40f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.0.sp
                    )
                }

                // 滚动层只占用曲线区域，永远不会压到左侧 Y 轴数字。
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = axisWidth, end = 2.dp, top = 2.dp, bottom = 0.dp)
                        .horizontalScroll(scrollState)
                        .zIndex(1f)
                ) {
                    Canvas(Modifier.width(chartWidth).fillMaxHeight()) {
                        val fullW = size.width
                        val fullH = size.height
                        val bottomH = 16.dp.toPx()
                        val topH = 5.dp.toPx()
                        val rightPad = 2.dp.toPx()
                        val plotLeft = 0f
                        val plotTop = topH
                        val plotRight = fullW - rightPad
                        val plotBottom = fullH - bottomH
                        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
                        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
                        val faintGrid = Color(0xFF64748B).copy(alpha = 0.026f)
                        val labelColor = android.graphics.Color.argb(235, 15, 23, 42)
                        val xPaint = Paint().apply {
                            color = labelColor
                            textSize = 7.2.sp.toPx()
                            isFakeBoldText = true
                            isAntiAlias = true
                            textAlign = Paint.Align.CENTER
                        }
                        val totalSec = totalMs / 1000f
                        val xTickCount = when {
                            totalSec <= 3f -> 4
                            totalSec <= 10f -> 5
                            totalSec <= 60f -> 6
                            else -> 7
                        }
                        (0 until xTickCount).forEach { idx ->
                            val ratio = if (xTickCount <= 1) 0f else idx.toFloat() / (xTickCount - 1)
                            val x = plotLeft + ratio * plotW
                            drawLine(faintGrid, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
                            val labelX = when (idx) {
                                0 -> (x + 7.dp.toPx()).coerceAtMost(plotRight)
                                xTickCount - 1 -> (x - 8.dp.toPx()).coerceAtLeast(plotLeft)
                                else -> x
                            }
                            // X 轴标签改由固定覆盖层绘制：保证当前可视区域始终至少 6 个时间点位。
                        }
                        fun yFor(ms: Int): Float {
                            val ratio = ((ms - yMin).toFloat() / (yMax - yMin).toFloat()).coerceIn(0f, 1f)
                            return plotBottom - ratio * plotH
                        }
                        fun xFor(elapsed: Long): Float {
                            val ratio = if (points.size <= 1) .5f else ((elapsed - rawFirst).toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                            return plotLeft + ratio * plotW
                        }
                        val linePoints = points.mapNotNull { pnt ->
                            val ms = pnt.ms ?: return@mapNotNull null
                            Offset(xFor(pnt.elapsedMs), yFor(ms))
                        }
                        if (linePoints.size >= 2) {
                            val path = Path().apply {
                                moveTo(plotLeft, linePoints.first().y)
                                lineTo(linePoints.first().x, linePoints.first().y)
                                for (i in 1 until linePoints.size) {
                                    val p0 = linePoints[i - 1]
                                    val p1 = linePoints[i]
                                    val cx = (p0.x + p1.x) / 2f
                                    cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                                }
                                lineTo(plotRight, linePoints.last().y)
                            }
                            drawPath(path, Color(0xFF2563EB), style = Stroke(width = 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        } else if (linePoints.size == 1) {
                            drawCircle(Color(0xFF2563EB), radius = 1.8.dp.toPx(), center = linePoints.first())
                        }
                        points.forEach { pnt ->
                            if (pnt.ms == null) {
                                val x = xFor(pnt.elapsedMs)
                                drawLine(Color(0xFFEF4444), Offset(x, plotBottom - 11.dp.toPx()), Offset(x, plotBottom - 2.dp.toPx()), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                            }
                        }
                    }
                }

                // 固定覆盖层最后绘制：Y 轴数字和横向网格固定在视口，不随横向滚动。
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 0.dp, end = 2.dp, top = 2.dp, bottom = 0.dp)
                        .zIndex(2f)
                ) {
                    val fullW = size.width
                    val fullH = size.height
                    val labelW = axisWidth.toPx()
                    val bottomH = 16.dp.toPx()
                    val topH = 5.dp.toPx()
                    val rightPad = 2.dp.toPx()
                    val plotLeft = labelW
                    val plotTop = topH
                    val plotRight = fullW - rightPad
                    val plotBottom = fullH - bottomH
                    val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
                    // 给 Y 轴标签一个极淡背景，彻底避免曲线划过数字。
                    drawRect(chartSurfaceColor.copy(alpha = 0.96f), topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(labelW, fullH))
                    val grid = Color(0xFF64748B).copy(alpha = 0.068f)
                    val labelColor = android.graphics.Color.argb(238, 15, 23, 42)
                    val yPaint = Paint().apply {
                        color = labelColor
                        textSize = 7.2.sp.toPx()
                        isFakeBoldText = true
                        isAntiAlias = true
                        textAlign = Paint.Align.LEFT
                    }
                    val tickStep = (yMax - yMin) / 4.0
                    val yTicks = (0..4).map { (yMin + tickStep * it).roundToInt() }.distinct()
                    yTicks.forEach { tick ->
                        val yRatio = ((tick - yMin).toFloat() / (yMax - yMin).toFloat()).coerceIn(0f, 1f)
                        val y = plotBottom - yRatio * plotH
                        drawLine(grid, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
                        val yText = when (tick) {
                            yMin -> y - 1.4.dp.toPx()
                            yMax -> y + 3.0.dp.toPx()
                            else -> y + 2.5f
                        }
                        drawContext.canvas.nativeCanvas.drawText(tick.toString(), 5.dp.toPx(), yText, yPaint)
                    }

                    // 固定 X 轴覆盖层：根据当前横向滚动视口重新计算时间刻度。
                    // 这样长测试滚动到任意位置，底部都不会只剩 0s / 5s 两个点。
                    val xPaint = Paint().apply {
                        color = labelColor
                        textSize = 7.0.sp.toPx()
                        isFakeBoldText = true
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    }
                    val visiblePlotW = (plotRight - plotLeft).coerceAtLeast(1f)
                    val contentPlotW = (chartWidth.toPx() - 2.dp.toPx()).coerceAtLeast(visiblePlotW)
                    val maxScrollPx = (contentPlotW - visiblePlotW).coerceAtLeast(0f)
                    val scrollPx = scrollState.value.toFloat().coerceIn(0f, maxScrollPx)
                    val totalSec = totalMs / 1000f
                    val startRatio = (scrollPx / contentPlotW).coerceIn(0f, 1f)
                    val endRatio = ((scrollPx + visiblePlotW) / contentPlotW).coerceIn(0f, 1f)
                    val startSec = totalSec * startRatio
                    val endSec = totalSec * endRatio
                    val visibleSec = (endSec - startSec).coerceAtLeast(0.001f)
                    val xTickCount = 6
                    val xGrid = Color(0xFF64748B).copy(alpha = 0.030f)
                    for (idx in 0 until xTickCount) {
                        val ratio = if (xTickCount <= 1) 0f else idx.toFloat() / (xTickCount - 1)
                        val x = plotLeft + ratio * visiblePlotW
                        drawLine(xGrid, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
                        val stepSec = visibleSec / (xTickCount - 1)
                        val label = formatPingAxisSeconds(startSec + visibleSec * ratio, stepSec)
                        val labelX = when (idx) {
                            0 -> (x + 6.dp.toPx()).coerceAtMost(plotRight)
                            xTickCount - 1 -> (x - 7.dp.toPx()).coerceAtLeast(plotLeft)
                            else -> x
                        }
                        drawContext.canvas.nativeCanvas.drawText(label, labelX, plotBottom + 12.dp.toPx(), xPaint)
                    }
                }
            }
        }
    }
}


@Composable
fun PingStats(points: List<PingPoint>) {
    val ok = points.mapNotNull { it.ms }
    val current = ok.lastOrNull()?.let { "当前 ${it}ms" } ?: "当前 --"
    val avg = if (ok.isEmpty()) "平均 --" else "平均 ${ok.average().roundToInt()}ms"
    val max = ok.maxOrNull()?.let { "最高 ${it}ms" } ?: "最高 --"
    val min = ok.minOrNull()?.let { "最低 ${it}ms" } ?: "最低 --"
    val jitter = pingJitterMs(points)?.let { "抖动 ${it}ms" } ?: "抖动 --"
    val timeout = if (points.isEmpty()) "超时 --" else "超时 ${pingTimeoutCount(points)}"
    val elapsed = points.maxOfOrNull { it.elapsedMs } ?: 0L
    val spent = if (points.isEmpty()) "耗时 --" else "耗时 ${formatElapsedMs(elapsed)}"
    val text = listOf(current, avg, max, min, jitter, timeout, spent).joinToString(" ·")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF2563EB).copy(alpha = .050f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = .08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 10.4.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .76f), maxLines = 1)
        }
    }
}


@Composable
fun StatChip(label: String, value: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 58.dp),
        shape = RoundedCornerShape(18.dp),
        color = color.copy(alpha = .06f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .08f))
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalArrangement = Arrangement.Center) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f), fontWeight = FontWeight.Bold, maxLines = 1)
            Text(value, fontWeight = FontWeight.Black, color = color, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}



@Composable
fun LabSpeedChart(points: List<SpeedSample>, modifier: Modifier = Modifier) {
    val values = points.map { it.mbps }
    val labels = points.map { p -> "${p.second}s\n下载 ${String.format(Locale.US, "%.1f", p.mbps)} Mbps\n平均 ${String.format(Locale.US, "%.1f", p.avgMbps)} Mbps" }
    val maxY = niceSpeedMax((values.maxOrNull() ?: 1.0).coerceAtLeast(1.0))
    SelectableLineChart(
        values = values,
        minY = 0.0,
        maxY = maxY,
        color = Color(0xFF2563EB),
        empty = "等待测速",
        yFormat = { v -> if (v >= 1000) "${(v/1000).roundToInt()}G" else "${v.roundToInt()}M" },
        pointLabels = labels,
        modifier = modifier
    )
}

@Composable
fun LabLatencyOnlyChart(samples: List<WifiSample>, modifier: Modifier = Modifier) {
    val latencySamples = samples.filter { it.latency != null }
    val maxLat = niceLatencyMax((latencySamples.mapNotNull { it.latency }.maxOrNull() ?: 30))
    SelectableLineChart(
        values = latencySamples.mapNotNull { it.latency?.toDouble() },
        minY = 0.0,
        maxY = maxLat.toDouble(),
        color = Color(0xFF7C3AED),
        empty = "等待负载延迟",
        yFormat = { it.roundToInt().toString() },
        pointLabels = latencySamples.map { "${it.time}\n延迟 ${it.latency ?: 0} ms\n丢包 ${if (it.lost) "是" else "否"}" },
        modifier = modifier
    )
}

@Composable
fun SelectableLineChart(
    values: List<Double>,
    minY: Double,
    maxY: Double,
    color: Color,
    empty: String,
    yFormat: (Double)->String,
    pointLabels: List<String>,
    modifier: Modifier = Modifier
) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val safeMax = if (maxY <= minY) minY + 1 else maxY
    val density = LocalDensity.current
    val axisLeft = with(density) { 36.dp.toPx() }
    val axisRightPad = with(density) { 10.dp.toPx() }
    LabChartFrame(
        modifier = modifier.pointerInput(values, axisLeft, axisRightPad) {
            detectTapGestures { offset ->
                if (values.isEmpty()) return@detectTapGestures
                val left = axisLeft
                val right = size.width - axisRightPad
                val ratio = ((offset.x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
                selected = (ratio * (values.size - 1)).roundToInt().coerceIn(0, values.lastIndex)
            }
        },
        emptyText = if (values.isEmpty()) empty else null
    ) { w, h, paint ->
        if (values.isEmpty()) return@LabChartFrame
        val left = axisLeft
        val right = w - axisRightPad
        val top = 18.dp.toPx()
        val bottom = h - 28.dp.toPx()
        val ticks = listOf(minY, minY + (safeMax-minY)/4, minY + (safeMax-minY)/2, minY + (safeMax-minY)*3/4, safeMax)
        drawGrid(drawContext.canvas.nativeCanvas, paint, left, right, top, bottom, ticks, yFormat)
        val pts = values.mapIndexed { idx, v ->
            val x = left + (right-left) * idx / (values.size-1).coerceAtLeast(1)
            val y = bottom - (((v-minY)/(safeMax-minY)).coerceIn(0.0,1.0).toFloat())*(bottom-top)
            Offset(x, y)
        }
        if (pts.size >= 2) {
            val path = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) {
                    val prev = pts[i-1]
                    val cur = pts[i]
                    val midX = (prev.x + cur.x) / 2f
                    quadraticBezierTo(prev.x, prev.y, midX, (prev.y + cur.y) / 2f)
                }
                lineTo(pts.last().x, pts.last().y)
            }
            drawPath(path, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(color, 5f, pts.first())
        }
        selected?.let { idx ->
            val pt = pts.getOrNull(idx) ?: return@let
            drawLine(Color(0xFF64748B).copy(alpha=.32f), Offset(pt.x, top), Offset(pt.x, bottom), strokeWidth = 1.4f)
            drawCircle(Color.White, 7f, pt)
            drawCircle(color, 5f, pt)
            val label = pointLabels.getOrNull(idx) ?: yFormat(values[idx])
            val lines = label.split('\n').take(4)
            val boxW = 150f
            val boxH = 28f + lines.size * 24f
            val x = (pt.x + 12f).coerceAtMost(right - boxW).coerceAtLeast(left)
            val y = (pt.y - boxH - 8f).coerceAtLeast(top)
            drawRoundRect(Color.White.copy(alpha=.96f), Offset(x, y), androidx.compose.ui.geometry.Size(boxW, boxH), androidx.compose.ui.geometry.CornerRadius(14f, 14f))
            drawRoundRect(Color(0xFFE2E8F0), Offset(x, y), androidx.compose.ui.geometry.Size(boxW, boxH), androidx.compose.ui.geometry.CornerRadius(14f, 14f), style = Stroke(width=1.2f))
            drawContext.canvas.nativeCanvas.apply {
                paint.color = android.graphics.Color.rgb(51,65,85)
                paint.textSize = 21f
                paint.isFakeBoldText = true
                paint.textAlign = Paint.Align.LEFT
                lines.forEachIndexed { i, line -> drawText(line, x+12f, y+28f+i*24f, paint) }
                paint.isFakeBoldText = false
            }
        }
        drawContext.canvas.nativeCanvas.apply {
            paint.color = android.graphics.Color.rgb(2,6,23)
            paint.textSize = 10.5.sp.toPx()
            paint.textAlign = Paint.Align.LEFT
            paint.isFakeBoldText = true
            drawText("0", left, h - 7.dp.toPx(), paint)
            paint.textAlign = Paint.Align.LEFT
            drawText("${values.lastIndex}s", right, h - 7.dp.toPx(), paint)
            paint.isFakeBoldText = false
        }
    }
}

@Composable
fun MtuStepChart(rows: List<Pair<Int, Boolean>>, modifier: Modifier = Modifier) {
    LabChartFrame(modifier, emptyText = if (rows.isEmpty()) "等待 MTU 检测" else null) { w, h, paint ->
        if (rows.isEmpty()) return@LabChartFrame
        val left = 10f; val right = w - 10f; val top = 14f; val rowH = ((h - 28f) / rows.size.coerceAtMost(12)).coerceAtLeast(12f)
        rows.takeLast(12).forEachIndexed { idx, (payload, ok) ->
            val y = top + idx * rowH
            val barW = (right-left) * ((payload - 1000).coerceAtLeast(0) / 600.0f).coerceIn(0f, 1f)
            drawRoundRect(color = (if (ok) Color(0xFF16A34A) else Color(0xFFEF4444)).copy(alpha=.16f), topLeft = Offset(left, y), size = androidx.compose.ui.geometry.Size((barW).coerceAtLeast(70f), rowH-4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f,8f))
            drawContext.canvas.nativeCanvas.apply {
                paint.textSize = 22f; paint.textAlign = Paint.Align.LEFT; paint.color = if (ok) android.graphics.Color.rgb(22,163,74) else android.graphics.Color.rgb(239,68,68)
                drawText("$payload  ${if (ok) "通过" else "失败"}", left+8f, y+rowH-8f, paint)
            }
        }
    }
}

@Composable
fun LabChartFrame(modifier: Modifier = Modifier, emptyText: String? = null, draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float, Float, Paint) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFFF8FAFC), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)), modifier = modifier) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            draw(size.width, size.height, paint)
            if (!emptyText.isNullOrBlank()) {
                drawContext.canvas.nativeCanvas.apply {
                    paint.color = android.graphics.Color.rgb(148,163,184); paint.textSize = 28f; paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
                    drawText(emptyText, size.width/2, size.height/2, paint)
                    paint.isFakeBoldText = false
                }
            }
        }
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(canvas: android.graphics.Canvas, paint: Paint, left: Float, right: Float, top: Float, bottom: Float, ticks: List<Double>, format: (Double)->String) {
    paint.strokeWidth = 1.15f
    paint.color = android.graphics.Color.rgb(226,232,240)
    paint.textSize = 10.5.sp.toPx()
    paint.textAlign = Paint.Align.LEFT
    paint.isFakeBoldText = true
    val labelGap = 4.dp.toPx()
    ticks.forEach { t ->
        val min = ticks.minOrNull() ?: 0.0
        val max = ticks.maxOrNull() ?: 1.0
        val y = bottom - (((t-min)/(max-min).coerceAtLeast(0.0001)).toFloat())*(bottom-top)
        drawLine(Color(0xFFE2E8F0), Offset(left, y), Offset(right, y), strokeWidth = 1.05f)
        paint.color = android.graphics.Color.rgb(2,6,23)
        val fm = paint.fontMetrics
        val baseline = y - (fm.ascent + fm.descent) / 2f
        canvas.drawText(format(t), left-labelGap, baseline, paint)
        paint.color = android.graphics.Color.rgb(226,232,240)
    }
    paint.isFakeBoldText = false
}

@Composable
fun RoamEventTimeline(samples: List<WifiSample>) {
    val events = samples.zipWithNext().mapNotNull { (a,b) ->
        if (a.bssid.isNotBlank() && b.bssid.isNotBlank() && a.bssid != b.bssid && a.bssid != "02:00:00:00:00:00" && b.bssid != "02:00:00:00:00:00") "${b.time}  AP切换  ${a.bssid.takeLast(5)} → ${b.bssid.takeLast(5)}" else null
    }.takeLast(5)
    if (events.isEmpty()) {
        samples.takeLast(4).forEach { s -> Text("${s.time}  ${s.bssid.ifBlank { "BSSID未知" }}  ${s.rssi.takeIf { it > -120 }?.let { "$it dBm" } ?: "RSSI不可用"}  ${s.latency?.let { "${it}ms" } ?: "timeout"}", fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.68f)) }
    } else events.forEach { Text(it, fontSize = 11.4.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF16A34A), maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

fun niceLatencyMax(v: Int): Int = when {
    v <= 30 -> 30
    v <= 60 -> 60
    v <= 120 -> 120
    v <= 300 -> 300
    v <= 600 -> 600
    v <= 1000 -> 1000
    else -> ((v + 499) / 500) * 500
}

fun niceSpeedMax(v: Double): Double = when {
    v <= 10.0 -> 10.0
    v <= 50.0 -> 50.0
    v <= 100.0 -> 100.0
    v <= 300.0 -> 300.0
    v <= 1000.0 -> 1000.0
    else -> 2000.0
}


@Composable
fun SmallSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (checked) Color(0xFF2563EB) else Color(0xFFE2E8F0), label = "small-switch-bg")
    val knobOffset by animateFloatAsState(if (checked) 18f else 0f, label = "small-switch-knob")
    Box(
        Modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(bg)
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .size(18.dp)
                .graphicsLayer { translationX = knobOffset }
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun DnsTool(prefs: AppPrefs) {
    var domain by remember { mutableStateOf(prefs.dnsDomain) }
    var dns1 by remember { mutableStateOf(prefs.dns1) }
    var dns2 by remember { mutableStateOf(prefs.dns2) }
    var type by remember { mutableStateOf(prefs.dnsRecord) }
    var useSystem by remember { mutableStateOf(prefs.dnsUseSystem) }
    var result by remember { mutableStateOf<List<DnsRecord>>(emptyList()) }
    var history by remember { mutableStateOf(prefs.dnsQueryHistory()) }
    var msg by remember { mutableStateOf("等待解析") }
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    val localDns = remember { getLocalDnsServers(ctx) }.ifEmpty { listOf("系统 DNS") }
    ExpressiveCard("查询配置", "双 DNS 备选，A / AAAA 解析与运营商识别。", Icons.Rounded.Dns, Color(0xFF2563EB)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本机DNS", fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.70f))
            Spacer(Modifier.width(8.dp))
            SmallSwitch(checked = useSystem, onCheckedChange = { useSystem = it; prefs.dnsUseSystem = it })
            Spacer(Modifier.width(8.dp))
            Text(if (useSystem) "使用系统解析" else "使用自定义DNS", fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TinyInfoParam("本机DNS", localDns.joinToString(" / "), Icons.Rounded.Info, Color(0xFF2563EB))
        CompactIconHistoryInput("域名", "net86.dynv6.net", domain, { domain = it; prefs.dnsDomain = it }, "dns_domain", prefs, Icons.Rounded.Dns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("记录", type, listOf("A", "AAAA", "ALL"), { type = it; prefs.dnsRecord = it }, Icons.Rounded.FilterAlt, Modifier.weight(1f))
            TinyParamSelectIcon("策略", if (type == "AAAA") "优先AAAA" else if (type == "A") "优先A" else "自动", listOf("自动", "优先AAAA", "优先A"), { v -> type = when(v){"优先AAAA" -> "AAAA"; "优先A" -> "A"; else -> "ALL"}; prefs.dnsRecord = type }, Icons.Rounded.Public, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyHistoryParamInputIcon("DNS1", "223.5.5.5", dns1, { dns1 = it; prefs.dns1 = it }, "dns1", prefs, Icons.Rounded.Storage, KeyboardType.Text, Modifier.weight(1f))
            TinyHistoryParamInputIcon("DNS2", "8.8.8.8", dns2, { dns2 = it; prefs.dns2 = it }, "dns2", prefs, Icons.Rounded.Storage, KeyboardType.Text, Modifier.weight(1f))
        }
        PillButton("查询 DNS", Icons.Rounded.Search, accent = Color(0xFF2563EB)) {
            scope.launch {
                msg = "查询中..."; prefs.addHistory("dns_domain", domain); prefs.addHistory("dns1", dns1); prefs.addHistory("dns2", dns2)
                val records = dnsLookup(domain, if (useSystem) "system" else dns1, if (useSystem) "system" else dns2, type, prefs)
                result = records; prefs.addDnsQueryHistory(domain, records); history = prefs.dnsQueryHistory(); msg = "完成：${records.size} 条"
            }
        }
    }
    ExpressiveCard("查询结果", msg, Icons.Rounded.TravelExplore, Color(0xFF06B6D4)) { if (result.isEmpty()) Text("暂无结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.55f)); result.forEach { r -> DnsResultRow(r) { copy(ctx, r.value) } } }
    if (history.isNotEmpty()) {
        ExpressiveCard("查询记录", "最多保存 10 条，结果相同自动丢弃。", Icons.Rounded.History, Color(0xFF7C3AED)) {
            history.forEach { h -> DnsHistoryRow(h) { copy(ctx, h.summary) } }
            TextButton(onClick = { prefs.clearDnsQueryHistory(); history = emptyList() }) { Text("清空查询记录", fontSize = 12.sp) }
        }
    }
}

@Composable
fun DnsHistoryRow(h: DnsQueryHistory, onCopy: () -> Unit) {
    fun legacyLines(summary: String): List<String> {
        val clean = summary.replace(" · ", " ").trim()
        if (clean.contains("\n")) return clean.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val aaaaAt = clean.indexOf("AAAA ")
        val lines = mutableListOf<String>()
        if (clean.startsWith("A ")) {
            val a = if (aaaaAt > 0) clean.substring(0, aaaaAt).trim() else clean
            if (a.isNotBlank()) lines += a
        }
        if (aaaaAt >= 0) lines += clean.substring(aaaaAt).trim()
        if (lines.isEmpty()) lines += clean
        return lines
    }
    val lines = legacyLines(h.summary).take(2)
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable { onCopy() }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.time, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(h.domain, fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
            lines.forEach { line ->
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Text(line, fontSize = 12.3.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun DnsResultRow(r: DnsRecord, onCopy: () -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .06f), modifier = Modifier.fillMaxWidth().clickable { onCopy() }) {
        Column(Modifier.padding(11.dp)) {
            Text("${r.value} (${r.type})", fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(r.operator, r.source).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun TcpTool(prefs: AppPrefs) {
    var host by remember { mutableStateOf(prefs.tcpHost) }
    var port by remember { mutableStateOf(prefs.tcpPort) }
    var timeout by remember { mutableStateOf(prefs.tcpTimeout) }
    var ipMode by remember { mutableStateOf("自动") }
    var result by remember { mutableStateOf("等待检测") }
    val scope = rememberCoroutineScope()
    ExpressiveCard("TCP 配置", "TCP Connect，等同 telnet / nc 端口可达性。", Icons.Rounded.SettingsEthernet, Color(0xFF0EA5E9)) {
        CompactIconHistoryInput("主机", "net86.dynv6.net / 240e::1", host, { host = it; prefs.tcpHost = it }, "port_host", prefs, Icons.Rounded.Dns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamInputIcon("端口", port, { port = it; prefs.tcpPort = it }, Icons.Rounded.SettingsEthernet, KeyboardType.Number, Modifier.weight(1f))
            TinyParamInputIcon("超时", timeout, { timeout = it; prefs.tcpTimeout = it }, Icons.Rounded.HourglassEmpty, KeyboardType.Number, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("IP策略", ipMode, listOf("自动", "IPv6优先", "IPv4优先", "仅IPv6", "仅IPv4"), { ipMode = it }, Icons.Rounded.Router, Modifier.weight(1f))
            TinyInfoParam("结果", "成功/拒绝/超时", Icons.Rounded.Info, Color(0xFF0EA5E9), Modifier.weight(1f))
        }
        PillButton("开始 TCP 测试", Icons.Rounded.Power, accent = Color(0xFF0EA5E9)) {
            scope.launch {
                prefs.addHistory("port_host", host); prefs.addHistory("port_port", port)
                result = tcpProbeSmart(host, port.toIntOrNull() ?: 80, timeout.toIntOrNull() ?: 1000, prefs.dns1, prefs.dns2, ipMode)
            }
        }
    }
    ExpressiveCard("TCP 结果", "连接成功 / 拒绝 / 超时", Icons.Rounded.Route, Color(0xFF2563EB)) { ResultText(result) }
}

data class UdpTemplateSpec(val name: String, val host: String, val port: String, val note: String)

fun udpTemplateSpec(name: String): UdpTemplateSpec = when (name) {
    "DNS 查询" -> UdpTemplateSpec("DNS 查询", "223.5.5.5", "53", "发送标准 DNS Query，适合测试 UDP 53。")
    "NTP 请求" -> UdpTemplateSpec("NTP 请求", "ntp.aliyun.com", "123", "发送 NTP 请求，适合测试时间服务器 UDP。")
    "UDP 空包" -> UdpTemplateSpec("UDP 空包", "1.1.1.1", "443", "只发送空 UDP 包；无响应不代表关闭。")
    else -> UdpTemplateSpec("STUN Binding", "stun.voip.aebc.com", "3478", "发送 STUN Binding Request，适合测试 STUN/UDP 映射。")
}

@Composable
fun UdpTool(prefs: AppPrefs) {
    var host by remember { mutableStateOf(prefs.udpHost) }
    var port by remember { mutableStateOf(prefs.udpPort) }
    var timeout by remember { mutableStateOf(prefs.udpTimeout) }
    var template by remember { mutableStateOf(prefs.udpTemplate) }
    var ipMode by remember { mutableStateOf(prefs.udpIpMode) }
    var result by remember { mutableStateOf("等待探测") }
    val scope = rememberCoroutineScope()
    fun applyUdpTemplate(name: String) {
        val spec = udpTemplateSpec(name)
        template = spec.name
        host = spec.host
        port = spec.port
        prefs.udpTemplate = spec.name
        prefs.udpHost = spec.host
        prefs.udpPort = spec.port
    }
    ExpressiveCard(
        "UDP 配置",
        "切换模板会自动填入默认目标与端口。",
        Icons.Rounded.SyncAlt,
        Color(0xFF06B6D4),
        headerAction = {
            IconButton(onClick = { applyUdpTemplate(template) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Restore, null, tint = Color(0xFF06B6D4), modifier = Modifier.size(19.dp))
            }
        }
    ) {
        CompactIconHistoryInput("目标", udpTemplateSpec(template).host, host, { host = it; prefs.udpHost = it }, "udp_host", prefs, Icons.Rounded.Dns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("模板", template, listOf("STUN Binding", "DNS 查询", "NTP 请求", "UDP 空包"), { applyUdpTemplate(it) }, Icons.Rounded.FilterAlt, Modifier.weight(1f))
            TinyParamSelectIcon("IP策略", ipMode, listOf("自动", "IPv6优先", "IPv4优先", "仅IPv6", "仅IPv4"), { ipMode = it; prefs.udpIpMode = it }, Icons.Rounded.Router, Modifier.weight(1f))
        }
        Text(udpTemplateSpec(template).note, fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f), lineHeight = 15.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamInputIcon("端口", port, { port = it; prefs.udpPort = it }, Icons.Rounded.SettingsEthernet, KeyboardType.Number, Modifier.weight(1f))
            TinyParamInputIcon("超时", timeout, { timeout = it; prefs.udpTimeout = it }, Icons.Rounded.HourglassEmpty, KeyboardType.Number, Modifier.weight(1f))
        }
        PillButton("开始 UDP 探测", Icons.Rounded.Waves, accent = Color(0xFF06B6D4)) {
            scope.launch {
                prefs.addHistory("udp_host", host); prefs.addHistory("udp_port", port)
                result = udpProbeSmart(host, port.toIntOrNull() ?: 3478, timeout.toIntOrNull() ?: 1000, prefs.dns1, prefs.dns2, ipMode, template)
            }
        }
    }
    ExpressiveCard("UDP 结果", template, Icons.Rounded.TravelExplore, Color(0xFF06B6D4)) {
        ResultText(result)
        Spacer(Modifier.height(6.dp))
        Text("提示：UDP 只有收到协议响应或 ICMP Port Unreachable 才较明确；无响应通常应显示为未知/可能过滤。", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), lineHeight = 16.sp)
    }
}

@Composable
fun NatTool(prefs: AppPrefs, openHistory: () -> Unit) {
    var mode by remember { mutableStateOf(prefs.natMode) }
    var servers by remember(mode) { mutableStateOf(prefs.natServers(mode)) }
    var selected by remember(mode, servers) { mutableStateOf(servers.firstOrNull() ?: defaultNatServer(mode)) }
    var host by remember(selected) { mutableStateOf(selected.host) }
    var port by remember(selected) { mutableStateOf(selected.port.toString()) }
    var timeout by remember { mutableStateOf(prefs.natTimeout) }
    var ipMode by remember { mutableStateOf(prefs.natIpMode) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NatRunResult?>(null) }
    var msg by remember { mutableStateOf("等待检测") }
    var showServers by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = if (mode == "RFC3489") Color(0xFF7C3AED) else Color(0xFF2563EB)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == "RFC5780", onClick = { mode = "RFC5780"; prefs.natMode = mode }, label = { Text("RFC5780 / 8489", fontSize = 12.sp, fontWeight = FontWeight.Black) })
        FilterChip(selected = mode == "RFC3489", onClick = { mode = "RFC3489"; prefs.natMode = mode }, label = { Text("RFC3489 TEST", fontSize = 12.sp, fontWeight = FontWeight.Black) })
        Spacer(Modifier.weight(1f))
        Surface(onClick = openHistory, shape = CircleShape, color = Color(0xFF2563EB).copy(alpha = .10f), modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.History, null, tint = Color(0xFF2563EB), modifier = Modifier.size(19.dp)) }
        }
    }

    ExpressiveCard("检测配置", if (mode == "RFC3489") "传统 TEST 1-4 · 默认 stun.miwifi.com" else "RFC5780 行为发现 · 默认 stun.voip.aebc.com", Icons.Rounded.Router, accent) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showServers = !showServers }, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f).height(46.dp)) {
                Icon(Icons.Rounded.Storage, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("服务器列表", fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { prefs.resetNatServers(mode); servers = prefs.natServers(mode); selected = servers.first(); host = selected.host; port = selected.port.toString() }, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f).height(46.dp)) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("恢复默认", fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }
        AnimatedVisibility(showServers) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                servers.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (item.host == host && item.port.toString() == port) accent.copy(alpha = .10f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .035f),
                        modifier = Modifier.fillMaxWidth().clickable { selected = item; host = item.host; port = item.port.toString() }
                    ) {
                        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Dns, null, tint = accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(item.toString(), Modifier.weight(1f), fontSize = 12.4.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { prefs.deleteNatServer(mode, item); servers = prefs.natServers(mode); if (servers.none { it.host == host && it.port.toString() == port }) { selected = servers.first(); host = selected.host; port = selected.port.toString() } }) { Icon(Icons.Rounded.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(17.dp)) }
                        }
                    }
                }
            }
        }
        CompactIconHistoryInput("服务器", defaultNatServer(mode).host, host, { host = it }, "nat_server_$mode", prefs, Icons.Rounded.Dns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamInputIcon("端口", port, { port = it }, Icons.Rounded.SettingsEthernet, KeyboardType.Number, Modifier.weight(1f))
            TinyParamInputIcon("超时", timeout, { timeout = it; prefs.natTimeout = it }, Icons.Rounded.HourglassEmpty, KeyboardType.Number, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("IP策略", ipMode, listOf("自动", "IPv6优先", "IPv4优先", "仅IPv6", "仅IPv4"), { ipMode = it; prefs.natIpMode = it }, Icons.Rounded.Router, Modifier.weight(1f))
            TinyInfoParam("模式", if (mode == "RFC3489") "TEST 1-4" else "行为发现", Icons.Rounded.Info, accent, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(
                onClick = {
                    val safePort = port.toIntOrNull()?.coerceIn(1, 65535) ?: defaultNatServer(mode).port
                    val safeTimeout = timeout.toIntOrNull()?.coerceIn(300, 8000) ?: 1200
                    prefs.addNatServer(mode, host, safePort)
                    servers = prefs.natServers(mode)
                    scope.launch {
                        running = true
                        msg = "检测中：${if (mode == "RFC3489") "TEST 1/2/3/4" else "RFC5780 行为发现"}"
                        val chain = (listOf(StunServerItem(host, safePort)) + servers).distinctBy { it.host.lowercase(Locale.getDefault()) + ":" + it.port }.take(10)
                        result = runNatBehaviorTestChain(mode, chain, safeTimeout, ipMode)
                        val r = result
                        msg = r?.summary ?: "完成"
                        if (r != null) {
                            val profile = detectNetworkProfile(ctx, prefs)
                            prefs.addNatHistory(NatHistoryEntry(
                                id = System.currentTimeMillis(),
                                time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                                mode = mode,
                                server = r.serverUsed ?: chain.first().toString(),
                                classicType = r.classicType,
                                confidence = r.confidence,
                                mapped = r.mapped?.toString().orEmpty(),
                                local = r.local?.toString().orEmpty(),
                                ipv6 = profile.ipv6Address,
                                operator = profile.operator,
                                priority = profile.priority,
                                elapsedMs = r.elapsedMs,
                                summary = r.summary
                            ))
                        }
                        running = false
                    }
                },
                enabled = !running,
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (running) "检测中" else "开始检测", fontWeight = FontWeight.Black) }
            OutlinedButton(onClick = openHistory, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Rounded.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("NAT记录", fontWeight = FontWeight.Bold)
            }
        }
    }
    val r = result
    ExpressiveCard("结果", msg, Icons.Rounded.TravelExplore, Color(0xFF2563EB)) {
        if (r == null) {
            ResultText("说明：RFC5780 用 STUN 行为发现描述映射/过滤行为；RFC3489 保留 TEST 1-4 传统分类。公共 STUN 服务器能力不一致，失败会按服务器列表顺序重测。")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("类型", r.classicType, Color(0xFF7C3AED))
                StatusPill("可信度", r.confidence, Color(0xFF2563EB))
            }
            InfoRow("服务器", r.serverUsed, copyable = true)
            InfoRow("公网映射", r.mapped?.toString(), copyable = true)
            InfoRow("本地端点", r.local?.toString())
            InfoRow("备用地址", r.other?.toString(), copyable = true)
            InfoRow("映射行为", r.mappingBehavior)
            InfoRow("过滤行为", r.filteringBehavior)
            InfoRow("耗时", formatElapsedMs(r.elapsedMs))
        }
    }
    ExpressiveCard(if (mode == "RFC3489") "TEST 1/2/3/4" else "RFC5780 步骤", "日志保留传统编号，结论使用行为描述。", Icons.Rounded.Notes, Color(0xFF0EA5E9)) {
        val steps = r?.steps ?: listOf(
            NatStep("TEST 1 基础映射", "等待", "向 STUN 主地址发送 Binding Request，获取公网映射。", null),
            NatStep("TEST 2 换IP+端口回包", "等待", "要求服务器从备用 IP 与备用端口回包，用于判断过滤行为。", null),
            NatStep("TEST 3 换端口回包", "等待", "要求服务器仅换端口回包，用于区分地址限制和端口限制。", null),
            NatStep("TEST 4 映射一致性", "等待", "换目标地址再次获取映射，判断是否疑似对称 NAT。", null)
        )
        steps.forEach { NatStepRow(it) }
    }
}

@Composable
fun NatHistoryTool(prefs: AppPrefs) {
    var list by remember { mutableStateOf(prefs.natHistory()) }
    var opened by remember { mutableStateOf<Long?>(null) }
    ExpressiveCard("记录概览", "${list.size}/50 条 · 保存日期时间、服务器、类型和映射", Icons.Rounded.History, Color(0xFF2563EB)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { prefs.clearNatHistory(); list = prefs.natHistory() }, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f).height(44.dp)) { Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("清空", fontWeight = FontWeight.Black) }
        }
    }
    if (list.isEmpty()) {
        ExpressiveCard("暂无记录", "完成 NAT 检测后自动保存。", Icons.Rounded.Notes, Color(0xFF64748B)) { Text("等待检测", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f)) }
    }
    list.forEach { item ->
        NatHistoryCard(item, opened == item.id, onToggle = { opened = if (opened == item.id) null else item.id }, onDelete = { prefs.deleteNatHistory(item.id); list = prefs.natHistory() })
    }
}

@Composable
fun NatHistoryCard(item: NatHistoryEntry, expanded: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val density = LocalDensity.current
    val deleteWidthPx = with(density) { 92.dp.toPx() }
    var targetOffsetPx by remember(item.id) { mutableStateOf(0f) }
    var dragging by remember(item.id) { mutableStateOf(false) }
    var pendingDelete by remember(item.id) { mutableStateOf(false) }
    val animatedOffsetPx by animateFloatAsState(targetOffsetPx, animationSpec = tween(if (dragging) 0 else 180), label = "nat-history-offset")
    LaunchedEffect(pendingDelete) { if (pendingDelete) { delay(170); onDelete() } }
    AnimatedVisibility(visible = !pendingDelete, exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(170)), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 92.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).width(92.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Brush.horizontalGradient(listOf(Color(0xFFFF8A80), Color(0xFFEF4444)))).clickable { targetOffsetPx = 0f; pendingDelete = true }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp)); Text("删除", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp) }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }.pointerInput(item.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; targetOffsetPx = if (targetOffsetPx < -deleteWidthPx / 2f) -deleteWidthPx else 0f },
                        onDragCancel = { dragging = false; targetOffsetPx = 0f },
                        onHorizontalDrag = { _, dragAmount -> targetOffsetPx = (targetOffsetPx + dragAmount).coerceIn(-deleteWidthPx, 0f) }
                    )
                }.shadow(4.dp, RoundedCornerShape(24.dp), clip = false).clickable { onToggle() },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .985f)
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF7C3AED).copy(alpha=.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Router, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.time, fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.72f))
                            Text(item.classicType.ifBlank { "未知" }, fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StatusPill(item.mode, item.confidence.ifBlank { "低" }, Color(0xFF2563EB))
                    }
                    Text(item.summary.ifBlank { item.server }, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f), maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
                    if (expanded) {
                        InfoRow("服务器", item.server)
                        InfoRow("公网映射", item.mapped.ifBlank { "--" }, copyable = true)
                        InfoRow("本地IP", item.local.ifBlank { "--" })
                        InfoRow("IPv6", item.ipv6.ifBlank { "--" })
                        InfoRow("运营商", item.operator.ifBlank { "未知" })
                        InfoRow("优先级", item.priority.ifBlank { "未知" })
                        InfoRow("耗时", formatElapsedMs(item.elapsedMs))
                    }
                }
            }
        }
    }
}

@Composable
fun NatStepRow(step: NatStep) {
    val color = when (step.success) {
        true -> Color(0xFF2563EB)
        false -> Color(0xFFEF4444)
        null -> Color(0xFF64748B)
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = color.copy(alpha = .06f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .10f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(7.dp))
                Text(step.title, fontSize = 12.4.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(step.status, fontSize = 11.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1)
            }
            Text(step.detail, fontSize = 11.3.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .66f))
        }
    }
}

@Composable
fun TraceTool(prefs: AppPrefs) {
    var host by remember { mutableStateOf(prefs.traceHost) }
    var maxHops by remember { mutableStateOf(prefs.traceMaxHops) }
    var timeout by remember { mutableStateOf(prefs.traceTimeout) }
    var ipMode by remember { mutableStateOf(prefs.traceIpMode) }
    var result by remember { mutableStateOf("等待追踪") }
    var running by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(prefs.traceHistory()) }
    var openedSwipeId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    ExpressiveCard("追踪配置", "逐跳追踪域名经过的 IP；结果实时追加显示。", Icons.Rounded.AltRoute, Color(0xFF2563EB)) {
        CompactIconHistoryInput("目标", "net86.dynv6.net / 223.5.5.5", host, { host = it; prefs.traceHost = it }, "trace_host", prefs, Icons.Rounded.Dns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamSelectIcon("IP策略", ipMode, listOf("自动", "IPv6优先", "IPv4优先", "仅IPv6", "仅IPv4"), { ipMode = it; prefs.traceIpMode = it }, Icons.Rounded.Router, Modifier.weight(1f))
            TinyParamInputIcon("跳数", maxHops, { maxHops = it; prefs.traceMaxHops = it }, Icons.Rounded.Timeline, KeyboardType.Number, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamInputIcon("超时", timeout, { timeout = it; prefs.traceTimeout = it }, Icons.Rounded.HourglassEmpty, KeyboardType.Number, Modifier.weight(1f))
            TinyInfoParam("说明", "实时过程", Icons.Rounded.Info, Color(0xFF2563EB), Modifier.weight(1f))
        }
        PillButton(if (running) "追踪中" else "开始追踪", Icons.Rounded.AltRoute, accent = Color(0xFF2563EB)) {
            if (!running) scope.launch {
                running = true
                prefs.addHistory("trace_host", host)
                result = "正在追踪：$host\n"
                val started = SystemClock.elapsedRealtime()
                val finalResult = traceRouteSmart(
                    host,
                    maxHops.toIntOrNull() ?: 16,
                    timeout.toIntOrNull() ?: 1200,
                    prefs.dns1,
                    prefs.dns2,
                    ipMode
                ) { partial -> result = partial }
                val elapsed = SystemClock.elapsedRealtime() - started
                result = finalResult + "\n完成：${formatElapsedMs(elapsed)}"
                prefs.addTraceHistory(TraceHistoryEntry(
                    id = System.currentTimeMillis(),
                    time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    host = host,
                    ipMode = ipMode,
                    hops = countTraceHops(finalResult),
                    status = if (finalResult.contains("无法解析") || finalResult.contains("timeout", true)) "完成/部分超时" else "完成",
                    output = finalResult
                ))
                history = prefs.traceHistory()
                running = false
            }
        }
    }
    ExpressiveCard("追踪结果", if (running) "正在追踪，逐跳追加。点击结果可复制。" else "点击结果可复制。", Icons.Rounded.Notes, Color(0xFF2563EB)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .045f), modifier = Modifier.fillMaxWidth().clickable { copy(ctx, result) }) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(result, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
    ExpressiveCard("追踪历史", "最多保存 15 条；点击展开，长按复制，左滑删除。", Icons.Rounded.History, Color(0xFF2563EB)) {
        if (history.isEmpty()) {
            Text("暂无追踪历史", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), fontWeight = FontWeight.SemiBold)
        } else {
            history.forEach { item ->
                key(item.id) {
                    TraceHistoryCard(
                        item = item,
                        openedSwipeId = openedSwipeId,
                        onSwipeOpen = { openedSwipeId = it },
                        onSwipeClose = { if (openedSwipeId == item.id) openedSwipeId = null },
                        onCopy = { copy(ctx, item.output) },
                        onDelete = { openedSwipeId = null; prefs.deleteTraceHistory(item.id); history = prefs.traceHistory() }
                    )
                }
            }
            TextButton(onClick = { prefs.clearTraceHistory(); history = emptyList() }) { Text("清空追踪历史", fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TraceHistoryCard(item: TraceHistoryEntry, openedSwipeId: Long?, onSwipeOpen: (Long) -> Unit, onSwipeClose: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    val density = LocalDensity.current
    val deleteWidthPx = with(density) { 88.dp.toPx() }
    var targetOffsetPx by remember(item.id) { mutableStateOf(0f) }
    var dragging by remember(item.id) { mutableStateOf(false) }
    var pendingDelete by remember(item.id) { mutableStateOf(false) }
    var expanded by remember(item.id) { mutableStateOf(false) }
    val animatedOffsetPx by animateFloatAsState(targetOffsetPx, animationSpec = tween(if (dragging) 0 else 180), label = "trace-history-offset")
    LaunchedEffect(openedSwipeId) { if (openedSwipeId != item.id && targetOffsetPx != 0f) targetOffsetPx = 0f }
    LaunchedEffect(pendingDelete) { if (pendingDelete) { delay(170); onDelete() } }
    AnimatedVisibility(visible = !pendingDelete, exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(170)), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 92.dp)) {
            if (animatedOffsetPx < -1f || targetOffsetPx < -1f) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(88.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A80), Color(0xFFEF4444))))
                        .clickable { targetOffsetPx = 0f; onSwipeClose(); pendingDelete = true },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Delete, null, tint = Color.White, modifier = Modifier.size(21.dp))
                        Text("删除", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .055f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
                modifier = Modifier.fillMaxWidth().offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                    .pointerInput(item.id) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                targetOffsetPx = if (targetOffsetPx < -deleteWidthPx / 2f) { onSwipeOpen(item.id); -deleteWidthPx } else { onSwipeClose(); 0f }
                            },
                            onDragCancel = { dragging = false; targetOffsetPx = 0f; onSwipeClose() },
                            onHorizontalDrag = { _, dragAmount ->
                                if (dragAmount < 0) onSwipeOpen(item.id)
                                targetOffsetPx = (targetOffsetPx + dragAmount).coerceIn(-deleteWidthPx, 0f)
                            }
                        )
                    }
                    .combinedClickable(onClick = { targetOffsetPx = 0f; onSwipeClose(); expanded = !expanded }, onLongClick = { targetOffsetPx = 0f; onSwipeClose(); onCopy() })
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.time, fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
                        Spacer(Modifier.weight(1f))
                        Text("${item.hops}跳 · ${item.status}", fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                    Text(item.host, fontSize = 12.6.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (expanded) item.output else item.output.lines().take(4).joinToString("\n"), fontSize = 11.8.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), lineHeight = 16.sp, maxLines = if (expanded) 100 else 4, overflow = TextOverflow.Ellipsis)
                    Text(if (expanded) "点击收起 · 长按复制" else "点击展开 · 长按复制", fontSize = 10.8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary.copy(alpha = .80f))
                }
            }
        }
    }
}

@Composable
fun SshTool(prefs: AppPrefs) {
    var host by remember { mutableStateOf(prefs.sshHost) }
    var port by remember { mutableStateOf(prefs.sshPort) }
    var user by remember { mutableStateOf(prefs.sshUser) }
    var savePass by remember { mutableStateOf(prefs.sshSavePass) }
    var password by remember { mutableStateOf(if (prefs.sshSavePass) prefs.sshPassword else "") }
    var command by remember { mutableStateOf(prefs.sshCommand) }
    var results by remember { mutableStateOf(prefs.sshResults()) }
    var openedSwipeId by remember { mutableStateOf<Long?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    ExpressiveCard("连接与命令", "路由器 / NAS 单条命令执行，返回仍在 APP 内。", Icons.Rounded.Terminal, Color(0xFF2563EB)) {
        CompactIconHistoryInput("主机", "192.168.5.1", host, { host = it; prefs.sshHost = it }, "ssh_host", prefs, Icons.Rounded.Dns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TinyParamInputIcon("端口", port, { port = it; prefs.sshPort = it }, Icons.Rounded.SettingsEthernet, KeyboardType.Number, Modifier.weight(1f))
            TinyParamInputIcon("用户", user, { user = it; prefs.sshUser = it }, Icons.Rounded.Person, KeyboardType.Text, Modifier.weight(1f))
        }
        LabeledInput("密码", "SSH 密码", password, { password = it; if (savePass) prefs.sshPassword = it }, password = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("保存", Modifier.width(50.dp), fontWeight = FontWeight.Black, fontSize = 11.5.sp)
            Switch(checked = savePass, onCheckedChange = { savePass = it; prefs.sshSavePass = it; if (it) prefs.sshPassword = password else prefs.sshPassword = "" })
            Text("保存密码", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        CompactIconHistoryInput("命令", "ip -6 neigh show", command, { command = it; prefs.sshCommand = it }, "ssh_cmd", prefs, Icons.Rounded.Terminal)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("邻居" to "ip -6 neigh show", "WAN" to "ip -6 addr show dev pppoe-wan scope global", "运行" to "uptime", "内核" to "uname -a", "存储" to "df -h", "路由" to "ip route show").forEach { (t,c) ->
                AssistChip(onClick = { command = c; prefs.sshCommand = c }, label = { Text(t, fontSize = 11.5.sp) })
            }
        }
        PillButton(if (running) "执行中" else "执行 SSH", Icons.Rounded.Terminal, accent = Color(0xFF2563EB)) {
            if (!running) scope.launch {
                running = true
                prefs.addHistory("ssh_host", host)
                prefs.addHistory("ssh_cmd", command)
                val raw = runCatching { sshExec(host, port.toIntOrNull() ?: 22, user, password, command) }.getOrElse { "SSH失败：${it.message}" }
                val clean = sshRealOutput(raw)
                prefs.addSshResult(SshResultEntry(
                    id = System.currentTimeMillis(),
                    time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    host = host,
                    command = command,
                    output = clean
                ))
                results = prefs.sshResults()
                running = false
            }
        }
    }
    ExpressiveCard("执行结果", "最多保留 6 条；点击查看完整输出，长按复制，左滑删除。", Icons.Rounded.Notes, Color(0xFF2563EB)) {
        if (results.isEmpty()) {
            Text("等待连接", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), fontWeight = FontWeight.SemiBold)
        } else {
            results.forEach { item ->
                key(item.id) {
                    SshResultCard(
                        item = item,
                        openedSwipeId = openedSwipeId,
                        onSwipeOpen = { openedSwipeId = it },
                        onSwipeClose = { if (openedSwipeId == item.id) openedSwipeId = null },
                        onCopy = { copy(ctx, item.output.ifBlank { "无输出" }) },
                        onDelete = { openedSwipeId = null; prefs.deleteSshResult(item.id); results = prefs.sshResults() }
                    )
                }
            }
            TextButton(onClick = { prefs.clearSshResults(); results = emptyList() }) { Text("清空执行记录", fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SshResultCard(item: SshResultEntry, openedSwipeId: Long?, onSwipeOpen: (Long) -> Unit, onSwipeClose: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    val density = LocalDensity.current
    val deleteWidthPx = with(density) { 92.dp.toPx() }
    var targetOffsetPx by remember(item.id) { mutableStateOf(0f) }
    var dragging by remember(item.id) { mutableStateOf(false) }
    var pendingDelete by remember(item.id) { mutableStateOf(false) }
    var showDetail by remember(item.id) { mutableStateOf(false) }
    val animatedOffsetPx by animateFloatAsState(targetOffsetPx, animationSpec = tween(if (dragging) 0 else 180), label = "ssh-result-offset")
    LaunchedEffect(openedSwipeId) { if (openedSwipeId != item.id && targetOffsetPx != 0f) targetOffsetPx = 0f }
    LaunchedEffect(pendingDelete) { if (pendingDelete) { delay(170); onDelete() } }
    AnimatedVisibility(visible = !pendingDelete, exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(170)), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 106.dp)) {
            if (animatedOffsetPx < -1f || targetOffsetPx < -1f) {
                Box(Modifier.align(Alignment.CenterEnd).width(92.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Brush.horizontalGradient(listOf(Color(0xFFFF8A80), Color(0xFFEF4444)))).clickable { targetOffsetPx = 0f; onSwipeClose(); pendingDelete = true }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("删除", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .055f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                    .pointerInput(item.id) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                targetOffsetPx = if (targetOffsetPx < -deleteWidthPx / 2f) {
                                    onSwipeOpen(item.id)
                                    -deleteWidthPx
                                } else {
                                    onSwipeClose()
                                    0f
                                }
                            },
                            onDragCancel = { dragging = false; targetOffsetPx = 0f; onSwipeClose() },
                            onHorizontalDrag = { _, dragAmount ->
                                if (dragAmount < 0) onSwipeOpen(item.id)
                                targetOffsetPx = (targetOffsetPx + dragAmount).coerceIn(-deleteWidthPx, 0f)
                            }
                        )
                    }
                    .combinedClickable(onClick = { targetOffsetPx = 0f; onSwipeClose(); showDetail = true }, onLongClick = { targetOffsetPx = 0f; onSwipeClose(); onCopy() })
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.time, fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
                        Spacer(Modifier.weight(1f))
                        Text(item.host, fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(item.command, fontSize = 12.4.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.output.ifBlank { "无输出" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (showDetail) {
        SshResultDetailDialog(item = item, onDismiss = { showDetail = false }, onCopy = onCopy)
    }
}

@Composable
fun SshResultDetailDialog(item: SshResultEntry, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onCopy) { Text("复制输出", fontWeight = FontWeight.Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭", fontWeight = FontWeight.Black) } },
        title = { Text("执行结果", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("主机：${item.host}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("时间：${item.time}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("命令：${item.command}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .055f), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .10f))) {
                    SelectionContainer {
                        Text(item.output.ifBlank { "无输出" }, modifier = Modifier.padding(12.dp), fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable fun ResultText(text: String) { Text(text, Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, fontSize = 12.2.sp) }

@Composable
fun EventsScreen(state: AppState, onRefresh: () -> Unit, openDaily: () -> Unit, topNav: @Composable () -> Unit) = ScreenShell("记录", "事件流 · 左滑删除 · 每日总结", action = {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = openDaily, label = { Text("每日总结", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(17.dp)) })
        AssistChip(onClick = onRefresh, label = { Text("刷新", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)) })
    }
}, topNav = topNav) {
    val scope = rememberCoroutineScope()
    var openedSwipeId by remember { mutableStateOf<Int?>(null) }
    ExpressiveCard("事件同步", "上线、离线、STUN、DDNS 变化按通知样式显示。", Icons.Rounded.History, Color(0xFF7C3AED)) { Text(state.message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    state.events.forEach { e ->
        key(e.id) {
            EventCompactCard(
                e = e,
                openedSwipeId = openedSwipeId,
                onSwipeOpen = { openedSwipeId = it },
                onSwipeClose = { if (openedSwipeId == e.id) openedSwipeId = null },
                onDelete = {
                    openedSwipeId = null
                    scope.launch { state.deleteEvent(e) }
                }
            )
        }
    }
}

@Composable
fun EventCompactCard(e: EventItem, openedSwipeId: Int?, onSwipeOpen: (Int) -> Unit, onSwipeClose: () -> Unit, onDelete: () -> Unit) {
    val isOnline = e.type == "device_online"
    val isOffline = e.type == "device_offline"
    val accent = when {
        isOnline -> Color(0xFF16A34A)
        isOffline -> Color(0xFF7C3AED)
        e.type.contains("stun") || e.type.contains("wireguard") || e.type.contains("vpn") -> Color(0xFF0EA5E9)
        e.type.contains("ddns") -> Color(0xFFF59E0B)
        else -> Color(0xFF64748B)
    }
    val icon = when {
        isOnline -> Icons.Rounded.PhoneAndroid
        isOffline -> Icons.Rounded.Bedtime
        e.type.contains("stun") || e.type.contains("wireguard") || e.type.contains("vpn") -> Icons.Rounded.SyncAlt
        e.type.contains("ddns") -> Icons.Rounded.Public
        else -> Icons.Rounded.Notifications
    }
    val density = LocalDensity.current
    val deleteWidthPx = with(density) { 92.dp.toPx() }
    var targetOffsetPx by remember(e.id) { mutableStateOf(0f) }
    var dragging by remember(e.id) { mutableStateOf(false) }
    var pendingDelete by remember(e.id) { mutableStateOf(false) }
    val animatedOffsetPx by animateFloatAsState(targetOffsetPx, animationSpec = tween(if (dragging) 0 else 180), label = "event-swipe-offset")

    LaunchedEffect(e.id) { targetOffsetPx = 0f }
    LaunchedEffect(openedSwipeId) { if (openedSwipeId != e.id && targetOffsetPx != 0f) targetOffsetPx = 0f }
    LaunchedEffect(pendingDelete) {
        if (pendingDelete) {
            delay(170)
            onDelete()
        }
    }

    AnimatedVisibility(
        visible = !pendingDelete,
        exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(170)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 78.dp)) {
            if (animatedOffsetPx < -1f || targetOffsetPx < -1f) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(92.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A80), Color(0xFFEF4444))))
                        .clickable {
                            targetOffsetPx = 0f
                            onSwipeClose()
                            pendingDelete = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Rounded.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("删除", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                    .pointerInput(e.id) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                targetOffsetPx = if (targetOffsetPx < -deleteWidthPx / 2f) {
                                    onSwipeOpen(e.id)
                                    -deleteWidthPx
                                } else {
                                    onSwipeClose()
                                    0f
                                }
                            },
                            onDragCancel = { dragging = false; targetOffsetPx = 0f; onSwipeClose() },
                            onHorizontalDrag = { _, dragAmount ->
                                if (dragAmount < 0) onSwipeOpen(e.id)
                                targetOffsetPx = (targetOffsetPx + dragAmount).coerceIn(-deleteWidthPx, 0f)
                            }
                        )
                    }
                    .shadow(4.dp, RoundedCornerShape(24.dp), clip = false),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .985f)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha=.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(eventTitle(e), Modifier.weight(1f), fontSize = 14.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(shortTime(e.time), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.52f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Text(eventLine(e), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.76f), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}


fun eventTitle(e: EventItem): String {
    val n = e.name.ifBlank { e.title.removeSuffix(" 上线").removeSuffix(" 离线") }.ifBlank { "事件" }
    return when (e.type) {
        "device_online" -> "$n 上线"
        "device_offline" -> "$n 离线"
        else -> e.title.ifBlank { n }
    }
}

fun eventLine(e: EventItem): String {
    fun clean(v: String) = v.takeIf { it.isNotBlank() && it.lowercase(Locale.getDefault()) != "null" && it != "-" } ?: ""
    val ip = clean(e.ip).takeIf { looksLikeIp(it) } ?: ""
    val rssi = clean(e.rssi).let { if (it.isNotBlank() && !it.endsWith("dBm")) "$it dBm" else it }
    val bandRate = listOf(clean(e.band), clean(e.rxrate)).filter { it.isNotBlank() }.joinToString(" ")
    return when (e.type) {
        "device_online" -> listOf(ip, rssi, bandRate, clean(e.ssid)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "已连接" }
        "device_offline" -> listOf(clean(formatDurationText(e.onlineDurationText)).takeIf { it.isNotBlank() }?.let { "在线 $it" } ?: "", rssi.takeIf { it.isNotBlank() }?.let { "最后 $it" } ?: "", ip, bandRate).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "已断开" }
        else -> listOf(clean(e.name), clean(e.newValue)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { e.type }
    }
}

fun shortTime(t: String): String = if (t.length >= 19) t.substring(11, 19) else t

fun formatDurationText(raw: String): String {
    val s = raw.trim()
    if (s.isBlank() || s == "-" || s.lowercase(Locale.getDefault()) == "null") return ""
    if ("小时" in s || "天" in s) return s
    Regex("^(\\d+)分(\\d+)秒$").find(s)?.let {
        val totalMin = it.groupValues[1].toIntOrNull() ?: 0
        val sec = it.groupValues[2].toIntOrNull() ?: 0
        val h = totalMin / 60
        val m = totalMin % 60
        return buildString { if (h > 0) append(h).append("小时"); if (m > 0 || h == 0) append(m).append("分"); append(sec).append("秒") }
    }
    Regex("^(\\d+)分$").find(s)?.let {
        val totalMin = it.groupValues[1].toIntOrNull() ?: 0
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}小时${m}分" else "${m}分"
    }
    return s.replace("时", "小时")
}


fun looksLikeIp(v: String): Boolean = v.contains(".") || v.contains(":")

@Composable
fun TwoColsVisible(l1: String, v1: String, l2: String, v2: String) {
    if (v1.isBlank() && v2.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (v1.isNotBlank()) MiniField(l1, v1, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        if (v2.isNotBlank()) MiniField(l2, v2, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
    }
}

@Composable
fun TwoCols(l1: String, v1: String, l2: String, v2: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniField(l1, v1, Modifier.weight(1f))
        MiniField(l2, v2, Modifier.weight(1f))
    }
}

@Composable
fun MiniField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.46f), fontWeight = FontWeight.Bold, maxLines = 1)
        Text(value.ifBlank { "-" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.76f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun eventLabel(type: String): String = when {
    type.contains("online") -> "上线"
    type.contains("offline") -> "离线"
    type.contains("stun") -> "STUN"
    type.contains("ddns") -> "DDNS"
    type.contains("router") -> "路由"
    else -> "事件"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("每日总结", "实时聚合，最近 7 天", onBack) {
    val scope = rememberCoroutineScope()
    val localDates = remember { recentSevenDates() }
    var dates by remember { mutableStateOf(localDates) }
    var selected by remember { mutableStateOf(localDates.first()) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var noteEdit by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    fun loadDate(d: String) { scope.launch { runCatching { HubApi(prefs).getDaily(d) }.onSuccess { val v = it.optJSONObject("daily") ?: it; data = v; noteText = v.optString("note") } } }
    LaunchedEffect(Unit) {
        dates = localDates
        selected = localDates.first()
        loadDate(selected)
    }
    if (noteEdit) {
        AlertDialog(
            onDismissRequest = { noteEdit = false },
            title = {
                Text(
                    "编辑今日备注",
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = labOutlinedColors(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.5.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
                    placeholder = { Text("写下今天网络情况、异常判断或处理记录", fontSize = 13.sp) }
                )
            },
            confirmButton = { TextButton(onClick = { scope.launch { runCatching { HubApi(prefs).putDailyNote(selected, noteText) }.onSuccess { loadDate(selected); noteEdit = false } } }) { Text("保存", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { noteEdit = false }) { Text("取消", fontWeight = FontWeight.Bold) } },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
    }
    ExpressiveCard("日期", selected.ifBlank { "今天" }, Icons.Rounded.CalendarMonth, Color(0xFF2563EB)) {
        Box {
            PillButton("选择日期", Icons.Rounded.CalendarMonth, accent = Color(0xFF2563EB)) { expanded = true }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.995f), tonalElevation = 6.dp, shadowElevation = 10.dp, modifier = Modifier.padding(vertical = 6.dp)) {
                dates.take(7).forEachIndexed { idx, d ->
                    val label = when (idx) { 0 -> "今天  $d"; 1 -> "昨天  $d"; 2 -> "前天  $d"; else -> d }
                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }, onClick = { selected = d; expanded = false; loadDate(d) }, leadingIcon = if (d == selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }) else null)
                }
            }
        }
    }
    val d = data
    if (d == null) { ExpressiveCard("总结", "暂无数据", Icons.Rounded.Notes, Color(0xFF64748B)) { Text("等待查询", fontSize = 12.sp) } } else {
        val summary = d.optJSONObject("summary") ?: JSONObject()
        val localEvents = normalizeDeviceEvents(parseEvents(prefs.cacheEvents))
        val localSnapshot = homeDailyFromEvents(localEvents, selected, "本地规范化事件")
        val localDevices = localDailyDeviceSummary(localEvents, selected)
        ExpressiveCard("概览", "上线 / 下线 / VPN-STUN / DDNS / 备注", Icons.Rounded.Dashboard, Color(0xFF7C3AED)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusPill("上线", localSnapshot.up.toString()+"次", Color(0xFF16A34A))
                StatusPill("下线", localSnapshot.down.toString()+"次", Color(0xFFEF4444))
                StatusPill("VPN-STUN", summary.optInt("vpnChanges", localSnapshot.vpn).toString()+"次", Color(0xFF0EA5E9))
                StatusPill("DDNS", summary.optInt("ddnsChanges", localSnapshot.ddns).toString()+"次", Color(0xFFF59E0B))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusPill("备注", if (noteText.isBlank()) "0条" else "1条", Color(0xFF64748B))
            }
        }
        val sections = d.optJSONObject("sections") ?: JSONObject()
        fun arr(name:String) = sections.optJSONArray(name) ?: JSONArray()
        DailySection("终端情况", if (localDevices.length() > 0) localDevices else arr("devices"), Icons.Rounded.Devices, Color(0xFFF59E0B), kind = "devices")
        DailySection("VPN / STUN", arr("vpn"), Icons.Rounded.VpnKey, Color(0xFF7C3AED), kind = "address")
        DailySection("网络变化", arr("network"), Icons.Rounded.Public, Color(0xFF0EA5E9), kind = "address")
        DailySection("DDNS 状态", arr("ddns"), Icons.Rounded.Dns, Color(0xFF2563EB), kind = "normal")
        ExpressiveCard("今日备注", if (noteText.isBlank()) "未填写" else "已保存", Icons.Rounded.EditNote, Color(0xFF64748B)) {
            if (noteText.isBlank()) Text("暂无备注。可记录今天的网络异常、处理动作或观察结果。", fontSize=12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.56f)) else Text(noteText, fontSize=12.5.sp, fontWeight = FontWeight.SemiBold)
            PillButton(if (noteText.isBlank()) "添加备注" else "编辑备注", Icons.Rounded.Edit, accent = Color(0xFF64748B)) { noteEdit = true }
        }
    }
}


@Composable
fun DailySection(title: String, items: JSONArray, icon: ImageVector, accent: Color, kind: String) {
    if (items.length() <= 0) return
    ExpressiveCard(title, "${items.length()} 条", icon, accent) {
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            when (kind) {
                "devices" -> DailyDeviceSummaryRow(o)
                "address" -> DailyAddressSummaryRow(o)
                else -> DailyTextSummaryRow(o)
            }
            if (i < items.length() - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
        }
    }
}

@Composable
fun DailyDeviceSummaryRow(o: JSONObject) {
    val text = o.optString("text", o.toString()).replace("\r", "").trim()
    val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    val name = cleanApiText(o.optString("name")).ifBlank { lines.firstOrNull().orEmpty() }
    val detailParts = mutableListOf<String>()
    if (o.has("online")) detailParts += "上线 ${o.optInt("online", 0)} 次"
    if (o.has("offline")) detailParts += "下线 ${o.optInt("offline", 0)} 次"
    cleanApiText(o.optString("onlineDurationText")).takeIf { it.isNotBlank() }?.let { detailParts += "在线 $it" }
    cleanApiText(o.optString("lastIp")).takeIf { it.isNotBlank() }?.let { detailParts += it }
    cleanApiText(o.optString("lastSignal")).takeIf { it.isNotBlank() }?.let { detailParts += it }
    val fallbackDetail = lines.drop(1).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name.ifBlank { "未知终端" }, fontSize = 12.6.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            detailParts.joinToString(" · ").ifBlank { fallbackDetail.ifBlank { "暂无详情" } },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            maxLines = 2
        )
    }
}

@Composable
fun DailyAddressSummaryRow(o: JSONObject) {
    val ctx = LocalContext.current
    val rawText = o.optString("text", o.toString()).replace("\r", "").trim()
    val time = cleanApiText(o.optString("time"))
    val name = cleanApiText(o.optString("name")).ifBlank {
        cleanApiText(o.optString("service")).ifBlank {
            val beforeDot = rawText.substringBefore(" · ").trim()
            beforeDot.ifBlank { "网络变化" }
        }
    }
    val address = cleanApiText(o.optString("address")).ifBlank {
        cleanApiText(o.optString("newValue")).ifBlank {
            if (rawText.contains(" · ")) rawText.substringAfterLast(" · ").trim() else ""
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), fontSize = 12.6.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (time.isNotBlank()) Text(time, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        }
        if (address.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clickable { copy(ctx, address) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(address, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        } else {
            Text(rawText.ifBlank { "暂无地址详情" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), maxLines = 2)
        }
    }
}

@Composable
fun DailyTextSummaryRow(o: JSONObject) {
    val text = o.optString("text", o.toString()).replace("\r", "").trim()
    Text(text, fontSize=12.sp, fontWeight=FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), maxLines=3)
}

fun todayDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

fun normalizeHomeOrder(raw: String): List<String> {
    val all = listOf("score", "mini", "exit", "vpn", "devices", "today")
    val parsed = raw.split(",").map { it.trim() }.filter { it in all }.distinct()
    return (parsed + all.filter { it !in parsed }).take(all.size)
}

fun maskAddressForUi(value: String?, privacyMode: Boolean): String {
    val v = cleanApiText(value)
    if (!privacyMode || v.isBlank()) return v
    return v
        .replace(Regex("""(?<![\d.])((?:\d{1,3}\.){3}\d{1,3})(?::\d+)?""")) { m ->
            val parts = m.value.split(":", limit = 2)
            val ip = parts[0].split(".")
            val masked = if (ip.size == 4) "${ip[0]}.${ip[1]}.***.***" else "***.***.***.***"
            if (parts.size > 1) "$masked:*****" else masked
        }
        .replace(Regex("""\[([0-9a-fA-F:]{8,})\](?::\d+)?""")) { m ->
            val port = m.value.substringAfter("]:", "")
            if (port.isNotBlank() && port != m.value) "[****:****:****::****]:$port" else "[****:****:****::****]"
        }
        .replace(Regex("""(?i)([0-9a-f]{1,4}:){2,}[0-9a-f:]{1,}"""), "****:****:****::****")
}

fun recentSevenDates(): List<String> {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val cal = Calendar.getInstance()
    val out = mutableListOf<String>()
    repeat(7) {
        out += fmt.format(cal.time)
        cal.add(Calendar.DATE, -1)
    }
    return out
}

@Composable
fun SettingsScreen(prefs: AppPrefs, state: AppState, dark: Boolean, autoRefresh: String, onDark: (Boolean) -> Unit, onAuto: (String) -> Unit, topNav: @Composable () -> Unit) = ScreenShell("我的", "Hub · 自动刷新 · 主题", topNav = topNav) {
    var hub by remember { mutableStateOf(prefs.hub) }
    var token by remember { mutableStateOf(prefs.token) }
    var dns by remember { mutableStateOf(prefs.hubDns) }
    var msg by remember { mutableStateOf("等待测试") }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    ExpressiveCard("连接设置", "Hub 请求优先 AAAA / IPv6，失败 3 次不清空缓存。", Icons.Rounded.Link, Color(0xFF2563EB)) {
        LabeledHistoryInput("Hub", "留空，手动填写 Hub 地址", hub, { hub = it }, "hub", prefs)
        LabeledInput("Token", "APP_TOKEN", token, { token = it })
        LabeledInput("DNS", "223.5.5.5 / system", dns, { dns = it })
        SelectInput("刷新", autoRefresh, listOf("手动", "3S", "10S", "20S")) { onAuto(it); prefs.autoRefresh = it }
        Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        PillButton("保存设置", Icons.Rounded.Save, accent = Color(0xFF2563EB)) { prefs.hub = hub; prefs.token = token; prefs.hubDns = dns; prefs.addHistory("hub", hub); state.markHubChanged(); toast(ctx, "已保存") }
        PillButton("测试连接", Icons.Rounded.WifiTethering, accent = Color(0xFF7C3AED)) { prefs.hub = hub; prefs.token = token; prefs.hubDns = dns; state.markHubChanged(); scope.launch { msg = runCatching { HubApi(prefs).health(); state.hubConnected = true; "连接成功" }.getOrElse { "失败：${it.message}" } } }
    }
    ExpressiveCard("主题", "更少大色块，蓝 / 紫 / 琥珀 / 青色分区。", Icons.Rounded.Palette, Color(0xFFF59E0B)) { PillButton(if (dark) "切换到浅色" else "切换到黑夜", Icons.Rounded.DarkMode, accent = Color(0xFFF59E0B)) { onDark(!dark) } }
    var privacy by remember { mutableStateOf(prefs.privacyMode) }
    ExpressiveCard("隐私模式", "隐藏首页公网 IPv4 / IPv6 / VPN-STUN 地址，点击复制仍复制真实地址。", Icons.Rounded.VisibilityOff, Color(0xFF7C3AED)) {
        PillButton(if (privacy) "关闭隐私模式" else "开启隐私模式", Icons.Rounded.VpnKey, accent = Color(0xFF7C3AED)) {
            privacy = !privacy
            prefs.privacyMode = privacy
        }
    }
    ExpressiveCard("关于", "Kotlin + Compose + One UI 仪表盘风格", Icons.Rounded.Info, Color(0xFF64748B)) {
        Text("极客网探\n版本 ${AppVersion.NAME}\nv0.9.15：白底导航、隐私模式、首页每日总结同步、整卡点击、拖拽缓冲和删除动画。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .70f), fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 19.sp)
    }
}

class HubApi(private val prefs: AppPrefs) {
    private val client = OkHttpClient.Builder()
        .dns(CustomDns(prefs.hubDns))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun health(): String = withContext(Dispatchers.IO) {
        if (prefs.hub.isBlank()) return@withContext "失败：Hub 地址为空"
        "连接成功：${retryText("/health", false, 3)}"
    }

    suspend fun healthWithRetry(attempts: Int = 3): String = withContext(Dispatchers.IO) {
        if (prefs.hub.isBlank()) throw RuntimeException("Hub 地址为空，请先输入")
        retryText("/health", false, attempts)
    }

    suspend fun getStatus(): JSONObject = withContext(Dispatchers.IO) { JSONObject(getText("/api/status", true)) }
    suspend fun getDevices(online: Boolean): List<DeviceItem> = withContext(Dispatchers.IO) { val path = if (online) "/api/devices?view=online" else "/api/devices"; val root = JSONObject(getText(path, true)); parseDeviceArray((root.optJSONArray("devices") ?: JSONArray()).toString()) }
    suspend fun getEvents(): List<EventItem> = withContext(Dispatchers.IO) { val root = JSONObject(getText("/api/events", true)); parseEvents((root.optJSONArray("events") ?: JSONArray()).toString()).reversed() }
    suspend fun deleteEvent(id: Int): String = withContext(Dispatchers.IO) { deleteText("/api/events/$id") }
    suspend fun sendWol(mac: String): JSONObject = withContext(Dispatchers.IO) { JSONObject(postJson("/api/wol", JSONObject().put("mac", mac).toString())) }
    suspend fun getDaily(date: String? = null): JSONObject = withContext(Dispatchers.IO) { JSONObject(getText(if (date.isNullOrBlank()) "/api/daily/latest" else "/api/daily?date=$date", true)) }
    suspend fun getDailyList(): JSONObject = withContext(Dispatchers.IO) { JSONObject(getText("/api/daily/list", true)) }
    suspend fun putDailyNote(date: String, note: String): JSONObject = withContext(Dispatchers.IO) { JSONObject(putJson("/api/daily/note?date=$date", JSONObject().put("note", note).toString())) }

    private fun retryText(path: String, auth: Boolean, attempts: Int): String {
        var last: Exception? = null
        repeat(attempts.coerceAtLeast(1)) { idx ->
            try { return getText(path, auth) } catch (e: Exception) {
                last = e
                if (idx < attempts - 1) Thread.sleep(650)
            }
        }
        val reason = last?.message ?: "未知错误"
        throw RuntimeException("已尝试 ${attempts.coerceAtLeast(1)} 次仍失败：$reason")
    }

    private fun getText(path: String, auth: Boolean): String {
        if (prefs.hub.isBlank()) throw RuntimeException("Hub 地址为空，请先输入")
        val req = Request.Builder().url(joinUrl(prefs.hub, path)).apply { if (auth && prefs.token.isNotBlank()) header("Authorization", "Bearer ${prefs.token}") }.build()
        val res = client.newCall(req).execute()
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw RuntimeException("HTTP ${res.code}: $text")
        return text
    }

    private fun putJson(path: String, json: String): String {
        if (prefs.hub.isBlank()) throw RuntimeException("Hub 地址为空，请先输入")
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(joinUrl(prefs.hub, path)).put(body).apply { if (prefs.token.isNotBlank()) header("Authorization", "Bearer ${prefs.token}") }.build()
        val res = client.newCall(req).execute()
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw RuntimeException("HTTP ${res.code}: $text")
        return text
    }

    private fun postJson(path: String, json: String): String {
        if (prefs.hub.isBlank()) throw RuntimeException("Hub 地址为空，请先输入")
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(joinUrl(prefs.hub, path)).post(body).apply { if (prefs.token.isNotBlank()) header("Authorization", "Bearer ${prefs.token}") }.build()
        val res = client.newCall(req).execute()
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw RuntimeException("HTTP ${res.code}: $text")
        return text
    }

    private fun deleteText(path: String): String {
        if (prefs.hub.isBlank()) throw RuntimeException("Hub 地址为空，请先输入")
        val req = Request.Builder().url(joinUrl(prefs.hub, path)).delete().apply { if (prefs.token.isNotBlank()) header("Authorization", "Bearer ${prefs.token}") }.build()
        val res = client.newCall(req).execute()
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw RuntimeException("HTTP ${res.code}: $text")
        return text
    }
}

class CustomDns(private val server: String) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (server.equals("system", true) || server.isBlank()) return Dns.SYSTEM.lookup(hostname).filterNot { it.hostAddress == "127.0.0.1" }
        val v6 = DnsWire.query(hostname, server, 28)
        val v4 = DnsWire.query(hostname, server, 1).filter { it != "127.0.0.1" }
        val all = (v6 + v4).distinct().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        return if (all.isNotEmpty()) all else Dns.SYSTEM.lookup(hostname).filterNot { it.hostAddress == "127.0.0.1" }
    }
}

object DnsWire {
    fun query(host: String, server: String, qtype: Int): List<String> = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2500
            val target = InetAddress.getByName(server.trim())
            val packet = buildQuery(SecureRandom().nextInt(65535), host.trim().trimEnd('.'), qtype)
            socket.send(DatagramPacket(packet, packet.size, target, 53))
            val buf = ByteArray(1500); val resp = DatagramPacket(buf, buf.size); socket.receive(resp)
            parseResponse(buf.copyOf(resp.length), qtype)
        }
    }.getOrDefault(emptyList())
    private fun buildQuery(id: Int, host: String, qtype: Int): ByteArray { val out = ByteArrayOutputStream(); val d = DataOutputStream(out); d.writeShort(id); d.writeShort(0x0100); d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0); host.split('.').forEach { val b=it.toByteArray(); d.writeByte(b.size); d.write(b) }; d.writeByte(0); d.writeShort(qtype); d.writeShort(1); return out.toByteArray() }
    private fun parseResponse(data: ByteArray, qtype: Int): List<String> { if (data.size < 12) return emptyList(); val an = u16(data,6); var off=12; while (off < data.size && data[off].toInt()!=0) off += 1 + (data[off].toInt() and 0xff); off += 5; val list= mutableListOf<String>(); repeat(an){ off = skipName(data, off); if (off+10 > data.size) return@repeat; val type=u16(data,off); off+=2; off+=2; off+=4; val len=u16(data,off); off+=2; if (off+len>data.size) return@repeat; if (type==qtype){ if(type==1&&len==4) list+=InetAddress.getByAddress(data.copyOfRange(off,off+4)).hostAddress ?: ""; if(type==28&&len==16) list+=InetAddress.getByAddress(data.copyOfRange(off,off+16)).hostAddress ?: "" }; off+=len }; return list.filter{it.isNotBlank()}.distinct() }
    private fun skipName(data: ByteArray, start: Int): Int { var o=start; while(o<data.size){ val v=data[o].toInt() and 0xff; if(v and 0xc0 == 0xc0) return o+2; if(v==0) return o+1; o += 1+v }; return o }
    private fun u16(data: ByteArray, off: Int): Int = ((data[off].toInt() and 0xff) shl 8) or (data[off+1].toInt() and 0xff)
}


fun getLocalDnsServers(ctx: Context): List<String> {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
    return lp.dnsServers.mapNotNull { it.hostAddress }.filter { it.isNotBlank() }.distinct()
}

suspend fun runLoadLatencyTest(url: String, pingHost: String, durationSec: Int, onTick: suspend (Int, Int?, Boolean) -> Unit): LoadLatencyResult = coroutineScope {
    val baseline = mutableListOf<Int>()
    repeat(3) {
        pingOnce(pingHost, 1000)?.let { baseline += it }
        delay(250)
    }
    val baselineAvg = baseline.takeIf { it.isNotEmpty() }?.average() ?: 0.0
    val loaded = mutableListOf<Int?>()
    runDownloadTemplateTest(url, durationSec) { _, _, _, _ ->
        val ms = pingOnce(pingHost, 900)
        loaded += ms
        onTick(loaded.size, ms, ms == null)
    }
    val ok = loaded.mapNotNull { it }
    val lossRate = if (loaded.isEmpty()) 0.0 else loaded.count { it == null } * 100.0 / loaded.size
    val loadedAvg = ok.takeIf { it.isNotEmpty() }?.average() ?: 0.0
    val loadedMax = ok.maxOrNull()
    val note = "空闲 ${String.format(Locale.US, "%.0f", baselineAvg)}ms · 满载 ${String.format(Locale.US, "%.0f", loadedAvg)}ms · 丢包 ${String.format(Locale.US, "%.1f%%", lossRate)}"
    LoadLatencyResult(baselineAvg, loadedAvg, loadedMax, lossRate, note)
}

suspend fun dnsLookup(domain: String, dns1: String, dns2: String, type: String, prefs: AppPrefs): List<DnsRecord> = withContext(Dispatchers.IO) {
    val servers = listOf(dns1.ifBlank { "system" }, dns2.ifBlank { DEFAULT_DNS2 }).distinct()
    for (server in servers) {
        val raw = mutableListOf<DnsRecord>()
        if (server.equals("system", true)) {
            raw += InetAddress.getAllByName(domain)
                .filterNot { it.hostAddress == "127.0.0.1" }
                .filter { type == "ALL" || (type == "AAAA") == (it is Inet6Address) }
                .map { DnsRecord(it.hostAddress ?: it.hostName, if (it is Inet6Address) "AAAA" else "A", "系统DNS") }
        } else {
            if (type == "A" || type == "ALL") raw += DnsWire.query(domain, server, 1).filter { it != "127.0.0.1" }.map { DnsRecord(it, "A", server) }
            if (type == "AAAA" || type == "ALL") raw += DnsWire.query(domain, server, 28).map { DnsRecord(it, "AAAA", server) }
        }
        if (raw.isNotEmpty()) return@withContext raw.map { it.copy(operator = operatorLookup(it.value, prefs)) }
    }
    listOf(DnsRecord("无记录或超时", type, servers.joinToString(" / ")))
}

fun operatorLookup(ip: String, prefs: AppPrefs): String {
    val clean = ip.trim().removePrefix("[").removeSuffix("]")
    if (clean.isBlank() || clean.startsWith("无记录") || clean.contains("超时")) return "运营商未知"
    if (isPrivateOrLocalIp(clean)) return "本地/局域网"

    // DNS 解析页也走联网 ASN / Geo 查询；失败时再回退到 IPv6 前缀快速判断。
    runCatching { return lookupIpOwnerOnline(clean) }

    val hub = prefs.hub.trim().trimEnd('/')
    val token = prefs.token.trim()
    if (hub.isNotBlank() && token.isNotBlank()) {
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .dns(CustomDns(prefs.hubDns))
                .build()
            val url = joinUrl(hub, "/api/geo?ip=${URLEncoder.encode(clean, "UTF-8")}")
            val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
            val text = client.newCall(req).execute().body?.string().orEmpty()
            val o = JSONObject(text)
            if (o.optBoolean("ok")) {
                val g = o.optJSONObject("geo") ?: JSONObject()
                val local = g.optString("localLabel")
                val operator = g.optString("operator")
                val asn = g.optString("asn")
                return listOf(
                    local.takeIf { it.isNotBlank() }?.let { "本地：$it" } ?: "",
                    operator.takeIf { it.isNotBlank() }?.let { "运营商：$it" } ?: "",
                    asn.takeIf { it.isNotBlank() }?.let { "AS$it" } ?: ""
                ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "运营商未知" }
            }
        }
    }
    return inferOperatorByIpOnly(clean).ifBlank { "运营商未知" }
}

fun isPrivateOrLocalIp(ip: String): Boolean = runCatching {
    val addr = InetAddress.getByName(ip)
    addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress
}.getOrElse { false }

fun inferOperatorByIpOnly(ip: String): String {
    val lower = ip.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.getDefault())
    return when {
        lower.startsWith("240e:") -> "中国电信"
        lower.startsWith("2408:") -> "中国联通"
        lower.startsWith("2409:") -> "中国移动"
        lower.startsWith("240a:") -> "中国移动"
        else -> ""
    }
}

private fun formatElapsedMs(ms: Long): String = String.format(Locale.US, "%.2fs", ms.coerceAtLeast(0L) / 1000.0)

private fun formatRate(points: List<PingPoint>): String {
    val elapsed = (points.maxOfOrNull { it.elapsedMs } ?: 0L).coerceAtLeast(1L)
    val rate = if (points.isEmpty()) 0.0 else points.size * 1000.0 / elapsed
    return String.format(Locale.US, "%.1f", rate)
}

private fun autoPingTimeoutMs(intervalMs: Long, timeoutText: String): Int {
    val manual = timeoutText.trim().toIntOrNull()
    if (manual != null) return manual.coerceIn(120, 30_000)
    // 自动超时：小间隔用于高频采样，但超时不能过短，否则 Android 调度/息屏会制造伪丢包。
    // 原则：至少覆盖 8 个发送间隔，同时给系统 ping/Socket 留 250~400ms 抖动余量。
    val byInterval = (intervalMs * 8 + 260).toInt()
    return when {
        intervalMs <= 30L -> byInterval.coerceIn(300, 650)
        intervalMs <= 50L -> byInterval.coerceIn(450, 800)
        intervalMs <= 100L -> byInterval.coerceIn(700, 1100)
        intervalMs <= 200L -> byInterval.coerceIn(1000, 1600)
        intervalMs <= 500L -> byInterval.coerceIn(1500, 2600)
        else -> byInterval.coerceIn(3000, 6000)
    }
}

private fun pingJitterMs(points: List<PingPoint>): Int? {
    // RFC 常见思路的简化版：只用最近 50 个成功 RTT，丢包/超时不进队列。
    val queue = ArrayDeque<Int>()
    points.forEach { p ->
        val ms = p.ms ?: return@forEach
        queue.addLast(ms)
        while (queue.size > 50) queue.removeFirst()
    }
    if (queue.size < 2) return null
    var sum = 0
    var last: Int? = null
    var n = 0
    queue.forEach { v ->
        val prev = last
        if (prev != null) {
            sum += kotlin.math.abs(v - prev)
            n++
        }
        last = v
    }
    return if (n <= 0) null else (sum.toDouble() / n).roundToInt()
}

private fun pingTimeoutCount(points: List<PingPoint>): Int = points.count { it.ms == null }

private fun shouldAutoStopPingNoReply(points: List<PingPoint>, nowElapsedMs: Long, windowMs: Long = 3000L, minAttempts: Int = 3): Boolean {
    if (nowElapsedMs < windowMs || points.size < minAttempts) return false
    val windowStart = (nowElapsedMs - windowMs).coerceAtLeast(0L)
    var recent = 0
    var success = 0
    var i = points.size - 1
    while (i >= 0) {
        val p = points[i]
        if (p.elapsedMs < windowStart) break
        recent++
        if (p.ms != null) success++
        i--
    }
    return recent >= minAttempts && success == 0
}


private fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun defaultPortFor(host: String, protocol: String): Int = when {
    protocol.startsWith("HTTP") && host.trim().startsWith("https://", ignoreCase = true) -> 443
    protocol.startsWith("HTTP") -> 80
    protocol.startsWith("TCP") -> 80
    else -> 0
}

private fun buildPingHistoryEntry(target: String, protocol: String, ipMode: String, dnsMode: String, result: PingRunResult, requestedCount: Int): PingHistoryEntry {
    val ok = result.points.mapNotNull { it.ms }
    val sent = result.points.size
    val elapsed = result.elapsedMs.coerceAtLeast(result.points.maxOfOrNull { it.elapsedMs } ?: 0L)
    val rate = if (elapsed <= 0L) 0.0 else sent * 1000.0 / elapsed
    val loss = if (sent == 0) 0 else ((sent - ok.size) * 100 / sent)
    return PingHistoryEntry(
        id = System.currentTimeMillis(),
        time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
        target = target.trim(),
        protocol = protocol,
        ipMode = ipMode,
        dnsMode = dnsMode,
        resolvedIp = result.resolvedIp,
        count = requestedCount,
        sent = sent,
        ok = ok.size,
        loss = loss,
        avg = if (ok.isEmpty()) null else ok.average().roundToInt(),
        max = ok.maxOrNull(),
        min = ok.minOrNull(),
        elapsedMs = elapsed,
        rate = rate
    )
}

suspend fun runLatencySeries(
    host: String,
    protocol: String,
    ipMode: String,
    dnsMode: String,
    port: Int,
    count: Int,
    intervalMs: Long,
    timeoutMs: Int,
    onPoint: suspend (PingPoint) -> Unit
): PingRunResult {
    val safeCount = count.coerceIn(1, 5000)
    val safeInterval = intervalMs.coerceIn(10L, 10_000L)
    val safeTimeout = timeoutMs.coerceIn(100, 30_000)
    val start = SystemClock.elapsedRealtime()
    val targetHost = extractLatencyHost(host)
    val targets = resolveLatencyTargets(targetHost, ipMode, dnsMode)
    if (targets.isEmpty()) {
        val points = (1..safeCount).map { i ->
            PingPoint(i, null, "#$i DNS失败 @${formatElapsedMs(SystemClock.elapsedRealtime() - start)}", SystemClock.elapsedRealtime() - start)
        }
        points.forEach { onPoint(it) }
        val elapsed = SystemClock.elapsedRealtime() - start
        return PingRunResult(points, elapsed, "DNS 解析失败：$targetHost", protocol, "")
    }
    val target = targets.first()
    val resolvedIp = target.hostAddress ?: targetHost
    return when {
        protocol == "ICMP" -> runIcmpSeries(target, safeCount, safeInterval, safeTimeout, start, onPoint)
        protocol == "TCP" -> runConnectSeries(target, protocol, port, safeCount, safeInterval, safeTimeout, start, onPoint)
        protocol.startsWith("HTTP") -> runHttpSeries(host, targetHost, target, protocol, port, safeCount, safeInterval, safeTimeout, start, onPoint)
        else -> runIcmpSeries(target, safeCount, safeInterval, safeTimeout, start, onPoint)
    }.let { it.copy(protocol = protocol, resolvedIp = resolvedIp) }
}


private class PingRtoEstimator(private val baseTimeoutMs: Int, private val intervalMs: Long) {
    private var srtt = 0.0
    private var rttvar = 0.0
    private var initialized = false

    val rtoMs: Int
        get() {
            val minRto = kotlin.math.max(200.0, intervalMs * 4.0)
            val raw = if (!initialized) baseTimeoutMs.toDouble() else srtt + 4.0 * rttvar
            return raw.coerceIn(minRto, 3000.0).roundToInt()
        }

    fun onRtt(rttMs: Int) {
        val r = rttMs.toDouble().coerceAtLeast(0.1)
        if (!initialized) {
            srtt = r
            rttvar = r / 2.0
            initialized = true
        } else {
            rttvar = (1.0 - 0.25) * rttvar + 0.25 * abs(srtt - r)
            srtt = (1.0 - 0.125) * srtt + 0.125 * r
        }
    }
}

private fun buildIcmpEchoPacket(buffer: ByteArray, seq: Int, ipv6: Boolean): Int {
    val type = if (ipv6) 128 else 8
    buffer[0] = type.toByte()
    buffer[1] = 0
    buffer[2] = 0
    buffer[3] = 0
    // Linux ping datagram socket 会重写 identifier，所以这里不依赖 ID 匹配。
    buffer[4] = 0
    buffer[5] = 0
    buffer[6] = ((seq ushr 8) and 0xff).toByte()
    buffer[7] = (seq and 0xff).toByte()
    buffer[8] = 'L'.code.toByte()
    buffer[9] = 'P'.code.toByte()
    buffer[10] = 0x35
    buffer[11] = 0x0f
    val len = 16
    if (!ipv6) {
        val c = icmpChecksum(buffer, len)
        buffer[2] = ((c ushr 8) and 0xff).toByte()
        buffer[3] = (c and 0xff).toByte()
    }
    return len
}

private fun icmpChecksum(data: ByteArray, len: Int): Int {
    var sum = 0
    var i = 0
    while (i + 1 < len) {
        sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
        i += 2
    }
    if (i < len) sum += (data[i].toInt() and 0xff) shl 8
    while ((sum ushr 16) != 0) sum = (sum and 0xffff) + (sum ushr 16)
    return sum.inv() and 0xffff
}

private fun parseIcmpReplySeq(buf: ByteArray, len: Int, ipv6: Boolean): Int {
    if (len < 8) return -1
    val type = buf[0].toInt() and 0xff
    val expected = if (ipv6) 129 else 0
    if (type != expected) return -1
    return ((buf[6].toInt() and 0xff) shl 8) or (buf[7].toInt() and 0xff)
}

private fun setNonBlocking(fd: FileDescriptor) {
    runCatching {
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }
}

private suspend fun runPosixIcmpSeries(
    address: InetAddress,
    count: Int,
    intervalMs: Long,
    timeoutMs: Int,
    start: Long,
    onPoint: suspend (PingPoint) -> Unit
): PingRunResult? = withContext(Dispatchers.IO) {
    val host = address.hostAddress ?: address.hostName
    val ipv6 = address is Inet6Address
    val family = if (ipv6) OsConstants.AF_INET6 else OsConstants.AF_INET
    val proto = if (ipv6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP
    val fd = try {
        Os.socket(family, OsConstants.SOCK_DGRAM, proto)
    } catch (_: Throwable) {
        return@withContext null
    }

    val points = ArrayList<PingPoint>(count.coerceAtMost(5000))
    val sendBuf = ByteArray(16)
    val recvBuf = ByteArray(512)
    val sendTimes = LongArray(count + 1)
    val done = BooleanArray(count + 1)
    val rto = PingRtoEstimator(timeoutMs, intervalMs)
    val pollFds = arrayOf(StructPollfd().apply {
        this.fd = fd
        this.events = OsConstants.POLLIN.toShort()
    })
    val recvFrom = InetSocketAddress(0)
    var sent = 0
    var finished = 0
    var nextSendAt = SystemClock.elapsedRealtime()
    var firstSendAt = 0L
    var successCount = 0
    var noReplyFallback = false
    var autoStopNoReply = false
    var hadKernelError = false

    fun earliestTimeout(now: Long): Long {
        var t = Long.MAX_VALUE
        val currentRto = rto.rtoMs.toLong()
        var i = 1
        while (i <= sent) {
            if (!done[i]) {
                val due = sendTimes[i] + currentRto
                if (due < t) t = due
            }
            i++
        }
        return t
    }

    try {
        setNonBlocking(fd)
        runCatching { Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 1 shl 20) }
        while (currentCoroutineContext().isActive && finished < count) {
            val now = SystemClock.elapsedRealtime()
            if (sent < count && now >= nextSendAt) {
                sent++
                val len = buildIcmpEchoPacket(sendBuf, sent, ipv6)
                try {
                    Os.sendto(fd, sendBuf, 0, len, 0, address, 0)
                    val t = SystemClock.elapsedRealtime()
                    if (firstSendAt == 0L) firstSendAt = t
                    sendTimes[sent] = t
                    // 不追赶式补发，避免 App/系统短暂调度延迟后瞬间突发塞爆内核队列。
                    nextSendAt = if (t - nextSendAt > intervalMs * 4) t + intervalMs else nextSendAt + intervalMs
                } catch (e: Throwable) {
                    hadKernelError = true
                }
            }
            if (hadKernelError) break

            val timeoutDue = earliestTimeout(now)
            val nextDue = if (sent < count) min(nextSendAt, timeoutDue) else timeoutDue
            val waitMs = when {
                nextDue == Long.MAX_VALUE -> 10
                nextDue <= now -> 0
                else -> (nextDue - now).coerceIn(0L, 50L).toInt()
            }

            val ready = runCatching { Os.poll(pollFds, waitMs) }.getOrElse { hadKernelError = true; -1 }
            if (hadKernelError) break
            if (ready > 0 && (pollFds[0].revents.toInt() and OsConstants.POLLIN) != 0) {
                while (currentCoroutineContext().isActive) {
                    val recvAt = SystemClock.elapsedRealtime()
                    val n = try {
                        Os.recvfrom(fd, recvBuf, 0, recvBuf.size, 0, recvFrom)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN) -1 else { hadKernelError = true; -2 }
                    } catch (_: Throwable) {
                        hadKernelError = true; -2
                    }
                    if (n <= 0) break
                    val seq = parseIcmpReplySeq(recvBuf, n, ipv6)
                    if (seq in 1..count && !done[seq] && sendTimes[seq] > 0L) {
                        done[seq] = true
                        finished++
                        successCount++
                        val ms = (recvAt - sendTimes[seq]).toInt().coerceAtLeast(0)
                        rto.onRtt(ms)
                        val elapsed = recvAt - start
                        val point = PingPoint(seq, ms, "#$seq ${ms}ms @${formatElapsedMs(elapsed)}", elapsed)
                        points += point
                        withContext(Dispatchers.Main) { onPoint(point) }
                    }
                }
            }

            val after = SystemClock.elapsedRealtime()
            val currentRto = rto.rtoMs.toLong()
            var i = 1
            while (i <= sent) {
                if (!done[i] && sendTimes[i] > 0L && after - sendTimes[i] >= currentRto) {
                    done[i] = true
                    finished++
                    val elapsed = after - start
                    val point = PingPoint(i, null, "#$i timeout @${formatElapsedMs(elapsed)}", elapsed)
                    points += point
                    withContext(Dispatchers.Main) { onPoint(point) }
                }
                i++
            }
            val nowElapsed = after - start
            if (shouldAutoStopPingNoReply(points, nowElapsed)) {
                autoStopNoReply = true
                break
            }
            // 某些 ROM 允许创建 socket 但 ICMPv6/ICMP datagram 实际不回包。
            // 3 秒内仍无任何有效响应时直接结束，不再长期空跑。
            if (successCount == 0 && nowElapsed >= 3000L && sent >= 3) {
                autoStopNoReply = true
                break
            }
        }
    } finally {
        runCatching { Os.close(fd) }
    }

    if (hadKernelError || noReplyFallback || sent == 0) {
        null
    } else {
        val elapsed = (points.maxOfOrNull { it.elapsedMs } ?: (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0L)
        val mode = if (autoStopNoReply) "ICMP 自动停止：连续 3 秒 100% 丢包，目标可能不可达" else "ICMP Posix Os.poll 引擎：无特权 Socket，高频事件驱动"
        PingRunResult(points.sortedBy { it.elapsedMs }, elapsed, mode, "ICMP", host)
    }
}

suspend fun runIcmpSeries(
    address: InetAddress,
    count: Int,
    intervalMs: Long,
    timeoutMs: Int,
    start: Long,
    onPoint: suspend (PingPoint) -> Unit
): PingRunResult {
    // buildfix35：优先使用 Android Posix Os.socket + Os.poll 无特权 ICMP。
    // 部分 ROM / 内核未开放 ping_group_range 时会失败，此时自动降级到系统 ping 单进程采样。
    val posix = runPosixIcmpSeries(address, count, intervalMs, timeoutMs, start, onPoint)
    if (posix != null && posix.points.isNotEmpty()) return posix

    val host = address.hostAddress ?: address.hostName
    val is6 = address is Inet6Address
    val fastPoints = mutableListOf<PingPoint>()
    val fastWorked = withContext(Dispatchers.IO) {
        var process: Process? = null
        runCatching {
            val timeoutSec = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
            val intervalSec = String.format(Locale.US, "%.3f", intervalMs / 1000.0)
            val commands = if (is6) {
                listOf(
                    listOf("/system/bin/ping6", "-c", count.toString(), "-i", intervalSec, "-W", timeoutSec.toString(), host),
                    listOf("/system/bin/ping", "-6", "-c", count.toString(), "-i", intervalSec, "-W", timeoutSec.toString(), host)
                )
            } else {
                listOf(listOf("/system/bin/ping", "-c", count.toString(), "-i", intervalSec, "-W", timeoutSec.toString(), host))
            }
            val timeRegex = Regex("time[=<]([0-9.]+)")
            val timeoutRegex = Regex("request timeout|destination host unreachable|network is unreachable|100% packet loss|no answer yet", RegexOption.IGNORE_CASE)
            for (cmd in commands) {
                val before = fastPoints.size
                var unsupported = false
                val ok = runCatching {
                    process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion {
                        runCatching { if (process?.isAlive == true) process?.destroyForcibly() }
                    }
                    val reader = process!!.inputStream.bufferedReader()
                    try {
                        while (currentCoroutineContext().isActive) {
                            val line = reader.readLine() ?: break
                            val lower = line.lowercase(Locale.US)
                            if (lower.contains("invalid") || lower.contains("permission") || lower.contains("not permitted") || lower.contains("bad") || lower.contains("no such") || lower.contains("unknown option")) unsupported = true
                            val elapsed = SystemClock.elapsedRealtime() - start
                            val match = timeRegex.find(line)
                            if (match != null) {
                                val ms = match.groupValues.getOrNull(1)?.toFloatOrNull()?.roundToInt() ?: continue
                                val idx = fastPoints.size + 1
                                val point = PingPoint(idx, ms, "#$idx ${ms}ms @${formatElapsedMs(elapsed)}", elapsed)
                                fastPoints += point
                                withContext(Dispatchers.Main) { onPoint(point) }
                                if (shouldAutoStopPingNoReply(fastPoints, elapsed)) break
                                if (idx >= count) break
                                continue
                            }
                            if (timeoutRegex.containsMatchIn(line) && fastPoints.size < count) {
                                val idx = fastPoints.size + 1
                                val point = PingPoint(idx, null, "#$idx timeout @${formatElapsedMs(elapsed)}", elapsed)
                                fastPoints += point
                                withContext(Dispatchers.Main) { onPoint(point) }
                                if (shouldAutoStopPingNoReply(fastPoints, elapsed)) break
                                if (idx >= count) break
                            }
                        }
                    } finally {
                        cancelHook?.dispose()
                        runCatching { reader.close() }
                        if (process?.isAlive == true) {
                            runCatching { process?.destroy() }
                            runCatching { process?.waitFor(250, TimeUnit.MILLISECONDS) }
                            runCatching { if (process?.isAlive == true) process?.destroyForcibly() }
                        }
                    }
                    process?.waitFor(1, TimeUnit.SECONDS)
                    !unsupported && fastPoints.size > before
                }.getOrElse {
                    if (fastPoints.size > before) fastPoints.subList(before, fastPoints.size).clear()
                    if (process?.isAlive == true) runCatching { process?.destroyForcibly() }
                    false
                }
                if (ok) return@withContext true
                if (fastPoints.size > before) fastPoints.subList(before, fastPoints.size).clear()
            }
            false
        }.getOrElse {
            if (process?.isAlive == true) process?.destroy()
            false
        }
    }
    if (fastWorked) {
        // 不再把“未执行到的剩余次数”补成 timeout，避免 Android 调度 / ping 实现限制造成伪丢包。
        // 只有系统 ping 明确输出 timeout/unreachable 时，才记录为超时。
        val finalElapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
        val elapsed = (fastPoints.maxOfOrNull { it.elapsedMs } ?: finalElapsed).coerceAtLeast(0L)
        return PingRunResult(fastPoints.toList(), elapsed, "ICMP 单进程采样：减少伪超时，真实时间轴", "ICMP", host)
    }
    val fallbackPoints = mutableListOf<PingPoint>()
    var nextAt = SystemClock.elapsedRealtime()
    var consecutiveTimeout = 0
    val noDataStop = (5000L / intervalMs.coerceAtLeast(25L)).toInt().coerceIn(12, 60)
    var autoStopped = false
    for (i in 1..count) {
        if (!currentCoroutineContext().isActive) break
        val ms = pingOnceAddress(address, timeoutMs)
        if (ms == null) consecutiveTimeout++ else consecutiveTimeout = 0
        val elapsed = SystemClock.elapsedRealtime() - start
        val point = PingPoint(i, ms, if (ms == null) "#$i timeout @${formatElapsedMs(elapsed)}" else "#$i ${ms}ms @${formatElapsedMs(elapsed)}", elapsed)
        fallbackPoints += point
        onPoint(point)
        if (shouldAutoStopPingNoReply(fallbackPoints, elapsed) || (fallbackPoints.none { it.ms != null } && consecutiveTimeout >= noDataStop)) {
            autoStopped = true
            break
        }
        nextAt += intervalMs
        val sleepMs = nextAt - SystemClock.elapsedRealtime()
        if (sleepMs > 0L) delay(sleepMs)
    }
    val elapsed = (fallbackPoints.maxOfOrNull { it.elapsedMs } ?: (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0L)
    val mode = if (autoStopped) "ICMP 自动停止：连续 3 秒 100% 丢包，目标可能不可达" else "ICMP 兼容稳定采样：真实时间轴"
    return PingRunResult(fallbackPoints.toList(), elapsed, mode, "ICMP", host)
}

suspend fun runConnectSeries(
    address: InetAddress,
    protocol: String,
    port: Int,
    count: Int,
    intervalMs: Long,
    timeoutMs: Int,
    start: Long,
    onPoint: suspend (PingPoint) -> Unit
): PingRunResult {
    val points = mutableListOf<PingPoint>()
    val host = address.hostAddress ?: address.hostName
    var nextAt = SystemClock.elapsedRealtime()
    for (i in 1..count) {
        if (!currentCoroutineContext().isActive) break
        val ms = tcpConnectOnce(address, port, timeoutMs)
        val elapsed = SystemClock.elapsedRealtime() - start
        val point = PingPoint(i, ms, if (ms == null) "#$i TCP超时 @${formatElapsedMs(elapsed)}" else "#$i TCP ${ms}ms @${formatElapsedMs(elapsed)}", elapsed)
        points += point
        onPoint(point)
        if (shouldAutoStopPingNoReply(points, elapsed)) break
        nextAt += intervalMs
        val sleepMs = nextAt - SystemClock.elapsedRealtime()
        if (sleepMs > 0L) delay(sleepMs)
    }
    val elapsed = (points.maxOfOrNull { it.elapsedMs } ?: (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0L)
    return PingRunResult(points.toList(), elapsed, "TCP Connect：端口握手延迟，X轴真实时间", protocol, host)
}

suspend fun runHttpSeries(
    rawInput: String,
    hostOnly: String,
    address: InetAddress,
    protocol: String,
    port: Int,
    count: Int,
    intervalMs: Long,
    timeoutMs: Int,
    start: Long,
    onPoint: suspend (PingPoint) -> Unit
): PingRunResult {
    val points = mutableListOf<PingPoint>()
    val host = address.hostAddress ?: address.hostName
    var nextAt = SystemClock.elapsedRealtime()
    for (i in 1..count) {
        if (!currentCoroutineContext().isActive) break
        val result = httpOnce(rawInput, hostOnly, address, protocol, port, timeoutMs)
        val elapsed = SystemClock.elapsedRealtime() - start
        val point = PingPoint(i, result.first, if (result.first == null) "#$i HTTP失败 @${formatElapsedMs(elapsed)}" else "#$i HTTP ${result.first}ms @${formatElapsedMs(elapsed)}", elapsed)
        points += point
        onPoint(point)
        if (shouldAutoStopPingNoReply(points, elapsed)) break
        nextAt += intervalMs
        val sleepMs = nextAt - SystemClock.elapsedRealtime()
        if (sleepMs > 0L) delay(sleepMs)
    }
    val elapsed = (points.maxOfOrNull { it.elapsedMs } ?: (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0L)
    return PingRunResult(points.toList(), elapsed, "$protocol：包含 DNS策略、TCP/TLS 与服务器响应", protocol, host)
}

suspend fun pingOnceAddress(address: InetAddress, timeoutMs: Int): Int? = withContext(Dispatchers.IO) {
    val host = address.hostAddress ?: address.hostName
    val timeoutSec = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
    val commands = if (address is Inet6Address) {
        listOf(listOf("/system/bin/ping6", "-c", "1", "-W", timeoutSec.toString(), host), listOf("/system/bin/ping", "-6", "-c", "1", "-W", timeoutSec.toString(), host))
    } else {
        listOf(listOf("/system/bin/ping", "-c", "1", "-W", timeoutSec.toString(), host))
    }
    for (cmd in commands) {
        val ms = runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor((timeoutSec + 2).toLong(), TimeUnit.SECONDS)
            Regex("time[=<]([0-9.]+)").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.roundToInt()
        }.getOrNull()
        if (ms != null) return@withContext ms
    }
    null
}

suspend fun tcpConnectOnce(address: InetAddress, port: Int, timeoutMs: Int): Int? = withContext(Dispatchers.IO) {
    runCatching {
        val start = SystemClock.elapsedRealtime()
        Socket().use { socket -> socket.connect(InetSocketAddress(address, port), timeoutMs) }
        (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(0)
    }.getOrNull()
}

suspend fun httpOnce(rawInput: String, hostOnly: String, address: InetAddress, protocol: String, port: Int, timeoutMs: Int): Pair<Int?, String> = withContext(Dispatchers.IO) {
    runCatching {
        val url = buildHttpUrl(rawInput, hostOnly, port)
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((timeoutMs + 1000).toLong(), TimeUnit.MILLISECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(address)
            })
            .build()
        val reqBuilder = Request.Builder().url(url).header("User-Agent", "Labprobe/${AppVersion.NAME}")
        val req = if (protocol == "HTTP HEAD") reqBuilder.head().build() else reqBuilder.get().build()
        val start = SystemClock.elapsedRealtime()
        client.newCall(req).execute().use { response ->
            val ms = (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(0)
            ms to "HTTP ${response.code} · ${address.hostAddress ?: hostOnly}"
        }
    }.getOrElse { null to (it.javaClass.simpleName.ifBlank { "HTTP错误" }) }
}

private fun buildHttpUrl(rawInput: String, hostOnly: String, port: Int): String {
    val raw = rawInput.trim()
    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
    val host = if (hostOnly.contains(":") && !hostOnly.startsWith("[")) "[$hostOnly]" else hostOnly
    return "http://$host:${port.coerceIn(1, 65535)}/"
}

private fun extractLatencyHost(input: String): String {
    val raw = input.trim().ifBlank { "223.5.5.5" }
    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
        val uri = Uri.parse(raw)
        return (uri.host ?: raw).trim('[', ']')
    }
    val noPathRaw = raw.substringBefore('/').trim()
    if (noPathRaw.startsWith("[") && noPathRaw.contains("]")) return noPathRaw.substringAfter("[").substringBefore("]")
    val noPath = noPathRaw.trim('[', ']')
    val colonCount = noPath.count { it == ':' }
    return if (colonCount == 1 && noPath.substringAfterLast(':').all { it.isDigit() }) noPath.substringBeforeLast(':') else noPath
}

private suspend fun resolveLatencyTargets(host: String, ipMode: String, dnsMode: String): List<InetAddress> = withContext(Dispatchers.IO) {
    runCatching {
        val all = if (isIpLiteral(host)) listOf(InetAddress.getByName(host)) else InetAddress.getAllByName(host).toList()
        val filtered = all.filter { addr ->
            when (ipMode) {
                "仅IPv6" -> addr is Inet6Address
                "仅IPv4" -> addr !is Inet6Address
                else -> true
            }
        }
        val prefer6 = ipMode == "IPv6优先" || dnsMode == "优先AAAA" || dnsMode == "自动DNS" || (ipMode == "自动" && dnsMode != "优先A" && dnsMode != "系统默认")
        val prefer4 = ipMode == "IPv4优先" || dnsMode == "优先A"
        when {
            prefer6 -> filtered.sortedBy { if (it is Inet6Address) 0 else 1 }
            prefer4 -> filtered.sortedBy { if (it is Inet6Address) 1 else 0 }
            else -> filtered
        }.distinctBy { it.hostAddress }
    }.getOrElse { emptyList() }
}

suspend fun pingOnce(host: String, timeoutMs: Int): Int? = withContext(Dispatchers.IO) {
    runCatching { pingOnceAddress(InetAddress.getByName(host), timeoutMs) }.getOrNull()
}

private fun resolveProbeTargets(host: String, dns1: String, dns2: String, ipMode: String): List<String> {
    val raw = if (isIpLiteral(host)) listOf(host) else (
        DnsWire.query(host, dns1.ifBlank { DEFAULT_DNS1 }, 28) +
        DnsWire.query(host, dns2.ifBlank { DEFAULT_DNS2 }, 28) +
        DnsWire.query(host, dns1.ifBlank { DEFAULT_DNS1 }, 1) +
        DnsWire.query(host, dns2.ifBlank { DEFAULT_DNS2 }, 1) +
        runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
    ).distinct().filter { it != "127.0.0.1" }
    return raw.filter { ip ->
        val is6 = ip.contains(":")
        when (ipMode) {
            "仅IPv6" -> is6
            "仅IPv4" -> !is6
            else -> true
        }
    }.let { list ->
        when (ipMode) {
            "IPv6优先", "自动" -> list.sortedBy { if (it.contains(":")) 0 else 1 }
            "IPv4优先" -> list.sortedBy { if (it.contains(":")) 1 else 0 }
            else -> list
        }
    }.distinct()
}

suspend fun tcpProbeSmart(host: String, port: Int, timeout: Int, dns1: String, dns2: String, ipMode: String = "自动"): String = withContext(Dispatchers.IO) {
    val targets = resolveProbeTargets(host, dns1, dns2, ipMode)
    if (targets.isEmpty()) return@withContext "FAILED\n无法解析或当前 IP 策略无可用地址：$host"
    val logs = mutableListOf<String>()
    for (ip in targets) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeout.coerceIn(300, 8000)) }
            return@withContext "OPEN\n$host → $ip:$port\n耗时 ${System.currentTimeMillis()-start}ms\n说明：TCP 三次握手成功，端口可达。"
        } catch (e: java.net.ConnectException) {
            logs += "$ip 连接拒绝：主机可达但端口未开放/拒绝连接"
        } catch (e: java.net.SocketTimeoutException) {
            logs += "$ip 超时：可能被防火墙过滤或路由不可达"
        } catch (e: Exception) {
            logs += "$ip 失败：${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
        }
    }
    "FAILED\n$host:$port\n" + logs.joinToString("\n")
}

private fun udpPayload(template: String, host: String): ByteArray {
    return when (template) {
        "STUN Binding" -> buildStunRequest(false, false).first
        "DNS 查询" -> {
            val domain = host.takeIf { !isIpLiteral(it) } ?: "example.com"
            val out = ByteArrayOutputStream()
            val id = SecureRandom().nextInt(65535)
            out.write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            domain.split('.').filter { it.isNotBlank() }.forEach { label -> val b = label.toByteArray(); out.write(b.size); out.write(b) }
            out.write(0); out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
            out.toByteArray()
        }
        "NTP 请求" -> ByteArray(48).also { it[0] = 0x1B }
        else -> byteArrayOf(0x4c, 0x61, 0x62, 0x50, 0x72, 0x6f, 0x62, 0x65)
    }
}

suspend fun udpProbeSmart(host: String, port: Int, timeout: Int, dns1: String, dns2: String, ipMode: String = "自动", template: String = "UDP 空包"): String = withContext(Dispatchers.IO) {
    val targets = resolveProbeTargets(host, dns1, dns2, ipMode)
    if (targets.isEmpty()) return@withContext "FAILED\n无法解析或当前 IP 策略无可用地址：$host"
    val logs = mutableListOf<String>()
    val payload = udpPayload(template, host)
    for (ip in targets) {
        val start = System.currentTimeMillis()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeout.coerceIn(300, 8000)
                val addr = InetAddress.getByName(ip)
                socket.connect(addr, port)
                socket.send(DatagramPacket(payload, payload.size, addr, port))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                val elapsed = System.currentTimeMillis()-start
                return@withContext "UDP RESPONSE\n$template · $host → $ip:$port\n耗时 ${elapsed}ms\n收到 ${resp.length} bytes，来源 ${resp.address.hostAddress}:${resp.port}\n说明：收到 UDP 响应，目标协议可达。"
            }
        } catch (e: java.net.PortUnreachableException) {
            return@withContext "UDP CLOSED\n$host → $ip:$port\n收到 ICMP Port Unreachable，端口大概率关闭。"
        } catch (e: java.net.SocketTimeoutException) {
            logs += "$ip 无响应：未知 / 可能过滤 / 服务不回复"
        } catch (e: Exception) {
            logs += "$ip 失败：${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
        }
    }
    "UDP NO RESPONSE\n$template · $host:$port\n" + logs.joinToString("\n") + "\n说明：UDP 无响应不代表端口关闭。"
}

fun isIpLiteral(s: String): Boolean = s.contains(":") || Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(s)

fun detectNetworkBrief(ctx: Context): NetworkBrief {
    val transport = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi‑Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "蜂窝"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
            else -> "未知"
        }
    }.getOrDefault("未知")
    var hasV4 = false
    var hasV6 = false
    runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                if (a is Inet6Address) hasV6 = true else hasV4 = true
            }
        }
    }
    return NetworkBrief(transport, hasV4, hasV6)
}

fun detectNetworkProfile(ctx: Context, prefs: AppPrefs): NetworkProfile {
    val brief = detectNetworkBrief(ctx)
    var localV4 = ""
    var globalV4 = ""
    var globalV6 = ""
    runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                val ip = a.hostAddress?.substringBefore('%').orEmpty()
                if (ip.isBlank()) continue
                if (a is Inet6Address) {
                    if (!a.isSiteLocalAddress && globalV6.isBlank()) globalV6 = ip
                } else {
                    if (isPrivateIpv4(ip) && localV4.isBlank()) localV4 = ip
                    if (!isPrivateIpv4(ip) && globalV4.isBlank()) globalV4 = ip
                }
            }
        }
    }
    val lastNat = prefs.natHistory().firstOrNull()
    val mappedIp = extractIpv4FromEndpoint(lastNat?.mapped.orEmpty())
    val ipv4Exit = mappedIp ?: globalV4.ifBlank { "未测" }
    val natType = lastNat?.classicType?.takeIf { it.isNotBlank() } ?: "未测"
    val local = localV4.ifBlank { globalV6.ifBlank { "未知" } }
    val priority = when {
        brief.hasV6 && brief.hasV4 -> "IPv6优先"
        brief.hasV6 -> "仅IPv6"
        brief.hasV4 -> "仅IPv4"
        else -> "未知"
    }
    val operator = lastNat?.operator?.takeIf { it.isNotBlank() && it != "未知" && !it.contains("未知") }
        ?: inferOperatorFast(globalV6.ifBlank { ipv4Exit }, brief.transport)
    return NetworkProfile(
        ipv4Exit = ipv4Exit,
        ipv6Address = globalV6.ifBlank { "未见" },
        natType = natType,
        operator = operator,
        localIp = local,
        priority = priority
    )
}


fun inferOperatorFast(ip: String, transport: String): String {
    val lower = ip.lowercase(Locale.getDefault())
    val op = inferOperatorByIpOnly(lower)
    return when {
        op.isNotBlank() && transport.isNotBlank() && transport != "未知" -> "$op · $transport"
        op.isNotBlank() -> op
        transport.isNotBlank() && transport != "未知" -> transport
        else -> "未知"
    }
}

fun extractIpv4FromEndpoint(text: String): String? {
    val host = text.substringBeforeLast(':')
    return host.takeIf { Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(it) }
}
fun isPrivateIpv4(ip: String): Boolean {
    val p = ip.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 100 && p[1] in 64..127) || p[0] == 169
}


private fun resolveNatTargets(host: String, ipMode: String): List<InetAddress> {
    val all = runCatching { if (isIpLiteral(host)) listOf(InetAddress.getByName(host)) else InetAddress.getAllByName(host).toList() }.getOrElse { emptyList() }
    val filtered = all.filter { addr ->
        when (ipMode) {
            "仅IPv6" -> addr is Inet6Address
            "仅IPv4" -> addr !is Inet6Address
            else -> true
        }
    }
    val prefer6 = ipMode == "IPv6优先"
    val prefer4 = ipMode == "IPv4优先"
    return when {
        prefer6 -> filtered.sortedBy { if (it is Inet6Address) 0 else 1 }
        prefer4 -> filtered.sortedBy { if (it is Inet6Address) 1 else 0 }
        else -> filtered
    }.distinctBy { it.hostAddress }
}

fun defaultNatServer(mode: String): StunServerItem = if (mode == "RFC3489") StunServerItem("stun.miwifi.com", 3478) else StunServerItem("stun.voip.aebc.com", 3478)

suspend fun runNatBehaviorTestChain(mode: String, servers: List<StunServerItem>, timeoutMs: Int, ipMode: String): NatRunResult = withContext(Dispatchers.IO) {
    val ordered = servers.ifEmpty { listOf(defaultNatServer(mode)) }.take(10)
    val failures = mutableListOf<NatStep>()
    var best: NatRunResult? = null
    for (server in ordered) {
        val r = runCatching { runNatBehaviorTest(server.host, server.port, timeoutMs, ipMode) }.getOrElse { e ->
            NatRunResult(
                title = "检测异常",
                summary = "${server} 执行异常：${e.message ?: e.javaClass.simpleName}",
                mapped = null, local = null, other = null,
                mappingBehavior = "未知", filteringBehavior = "未知", classicType = "无法判断", confidence = "低",
                steps = listOf(NatStep("服务器 ${server}", "异常", e.javaClass.simpleName + ": " + (e.message ?: ""), false)),
                elapsedMs = 0L, serverUsed = server.toString()
            )
        }.copy(serverUsed = server.toString())
        val supportsEnhanced = r.other != null || r.steps.any { it.title.contains("TEST 2") && it.success == true }
        val basicOk = r.mapped != null
        if (basicOk && (mode == "RFC3489" || supportsEnhanced || best == null)) best = r
        if (basicOk && supportsEnhanced) return@withContext r
        failures += NatStep("服务器 ${server}", if (basicOk) "基础可用" else "失败", r.summary, basicOk)
    }
    best?.let { b ->
        val merged = failures + b.steps
        return@withContext b.copy(
            summary = if (b.other == null) "基础 STUN 可用，但未找到支持增强行为发现的服务器" else b.summary,
            steps = merged.take(12)
        )
    }
    NatRunResult(
        title = "全部失败",
        summary = "服务器列表全部无可用 STUN 响应",
        mapped = null, local = null, other = null,
        mappingBehavior = "未知", filteringBehavior = "未知", classicType = "无法判断", confidence = "低",
        steps = failures.ifEmpty { listOf(NatStep("服务器列表", "失败", "没有可用服务器", false)) },
        elapsedMs = 0L, serverUsed = ordered.joinToString(", ")
    )
}

suspend fun runNatBehaviorTest(server: String, port: Int, timeoutMs: Int, ipMode: String): NatRunResult = withContext(Dispatchers.IO) {
    val started = SystemClock.elapsedRealtime()
    val host = server.trim().ifBlank { "stun.l.google.com" }
    val targets = resolveNatTargets(host, ipMode)
    if (targets.isEmpty()) {
        return@withContext NatRunResult(
            title = "DNS失败",
            summary = "无法解析 STUN 服务器：$host",
            mapped = null,
            local = null,
            other = null,
            mappingBehavior = "未知",
            filteringBehavior = "未知",
            classicType = "无法判断",
            confidence = "低",
            steps = listOf(NatStep("TEST 1 基础映射", "失败", "DNS 解析失败或当前 IP 策略没有可用地址。", false)),
            elapsedMs = SystemClock.elapsedRealtime() - started
        )
    }
    val target = targets.first()
    DatagramSocket().use { socket ->
        socket.soTimeout = timeoutMs.coerceIn(300, 8000)
        val steps = mutableListOf<NatStep>()
        val localBefore = StunEndpoint(socket.localAddress?.hostAddress ?: "0.0.0.0", socket.localPort)
        val t1 = stunTransaction(socket, target, port, timeoutMs, changeIp = false, changePort = false)
        if (t1 == null) {
            steps += NatStep("TEST 1 基础映射", "失败", "未收到 STUN Binding Response。可能 UDP 被拦截、服务器不可用或超时。", false)
            return@withContext NatRunResult(
                title = "STUN无响应",
                summary = "TEST 1 未收到响应，无法继续 TEST 2/3/4",
                mapped = null,
                local = localBefore,
                other = null,
                mappingBehavior = "未知",
                filteringBehavior = "未知",
                classicType = "无法判断",
                confidence = "低",
                steps = steps,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        }
        val local = StunEndpoint(socket.localAddress?.hostAddress ?: "0.0.0.0", socket.localPort)
        val other = t1.other ?: t1.changed
        steps += NatStep(
            "TEST 1 基础映射",
            "成功",
            "公网映射 ${t1.mapped ?: "未知"}，响应源 ${t1.source}，备用地址 ${other ?: "服务器未返回"}。",
            true
        )
        val noNat = t1.mapped?.let { it.port == local.port && endpointHostSame(it.address, local.address) } == true
        val t2 = stunTransaction(socket, target, port, timeoutMs, changeIp = true, changePort = true)
        steps += if (t2 != null) {
            NatStep("TEST 2 换IP+端口回包", "成功", "收到来自 ${t2.source} 的响应，外部任意地址/端口回包能力较强。", true)
        } else {
            NatStep("TEST 2 换IP+端口回包", "无响应", "未收到换地址+换端口响应，继续 TEST 3 判断过滤强度。", false)
        }
        val t3 = stunTransaction(socket, target, port, timeoutMs, changeIp = false, changePort = true)
        steps += if (t3 != null) {
            NatStep("TEST 3 换端口回包", "成功", "收到同 IP 不同端口响应：过滤行为偏地址限制。", true)
        } else {
            NatStep("TEST 3 换端口回包", "无响应", "未收到同 IP 换端口响应：过滤行为偏端口限制或服务器不支持。", false)
        }
        val t4 = other?.let { alt ->
            runCatching { stunTransaction(socket, InetAddress.getByName(alt.address), alt.port, timeoutMs, changeIp = false, changePort = false) }.getOrNull()
        }
        steps += when {
            other == null -> NatStep("TEST 4 映射一致性", "跳过", "服务器未返回 CHANGED-ADDRESS / OTHER-ADDRESS，无法换目标复测映射。", null)
            t4 == null -> NatStep("TEST 4 映射一致性", "无响应", "向备用地址 $other 发送 Binding Request 未收到响应，可能备用地址不可达。", false)
            endpointsSame(t1.mapped, t4.mapped) -> NatStep("TEST 4 映射一致性", "一致", "换目标后映射仍为 ${t4.mapped}，映射行为偏 Endpoint-Independent。", true)
            else -> NatStep("TEST 4 映射一致性", "变化", "第一次 ${t1.mapped}，换目标后 ${t4.mapped}，疑似对称/地址相关映射。", false)
        }
        val mappingBehavior = when {
            noNat -> "无 NAT / 公网直连"
            other == null || t4 == null -> "未知：服务器不支持增强行为发现"
            endpointsSame(t1.mapped, t4.mapped) -> "Endpoint-Independent Mapping（端点独立映射）"
            else -> "Address/Port-Dependent Mapping（疑似对称 NAT）"
        }
        val filteringBehavior = when {
            noNat -> "无 NAT 过滤或主机防火墙未限制"
            t2 != null -> "Endpoint-Independent Filtering（接近 Full Cone）"
            t3 != null -> "Address-Dependent Filtering（Restricted Cone 倾向）"
            else -> "Address and Port-Dependent Filtering（Port Restricted 倾向）"
        }
        val classicType = when {
            noNat -> "Open Internet"
            mappingBehavior.startsWith("Unknown", true) || mappingBehavior.startsWith("未知") -> "基础 STUN：无法完整分类"
            mappingBehavior.startsWith("Address") -> "Symmetric NAT"
            t2 != null -> "Full Cone NAT"
            t3 != null -> "Restricted Cone NAT"
            else -> "Port Restricted Cone NAT"
        }
        val confidence = when {
            noNat -> "中"
            other == null || t4 == null -> "低：缺少增强 STUN"
            t2 == null && t3 == null -> "中：可能受服务器能力影响"
            else -> "较高"
        }
        val summary = when {
            noNat -> "TEST 1 成功：疑似公网直连"
            other == null -> "基础映射成功，但服务器不支持完整 TEST 2/3/4"
            else -> "检测完成：$classicType"
        }
        NatRunResult(
            title = "NAT 行为检测完成",
            summary = summary,
            mapped = t1.mapped,
            local = local,
            other = other,
            mappingBehavior = mappingBehavior,
            filteringBehavior = filteringBehavior,
            classicType = classicType,
            confidence = confidence,
            steps = steps,
            elapsedMs = SystemClock.elapsedRealtime() - started
        )
    }
}

private fun endpointsSame(a: StunEndpoint?, b: StunEndpoint?): Boolean = a != null && b != null && a.port == b.port && endpointHostSame(a.address, b.address)

private fun endpointHostSame(a: String, b: String): Boolean = runCatching { InetAddress.getByName(a) == InetAddress.getByName(b) }.getOrElse { a == b }

private fun stunTransaction(socket: DatagramSocket, address: InetAddress, port: Int, timeoutMs: Int, changeIp: Boolean, changePort: Boolean): StunResponse? {
    repeat(2) {
        val req = buildStunRequest(changeIp, changePort)
        val data = req.first
        val tx = req.second
        val start = SystemClock.elapsedRealtime()
        runCatching {
            socket.soTimeout = timeoutMs.coerceIn(300, 8000)
            socket.send(DatagramPacket(data, data.size, address, port))
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val elapsed = SystemClock.elapsedRealtime() - start
            val bytes = packet.data.copyOf(packet.length)
            val parsed = parseStunResponse(bytes, tx, StunEndpoint(packet.address.hostAddress ?: packet.address.hostName, packet.port), elapsed)
            if (parsed != null) return parsed
        }
    }
    return null
}

private fun buildStunRequest(changeIp: Boolean, changePort: Boolean): Pair<ByteArray, ByteArray> {
    val tx = ByteArray(12)
    SecureRandom().nextBytes(tx)
    val flags = (if (changeIp) 0x04 else 0x00) or (if (changePort) 0x02 else 0x00)
    val attrLen = if (flags != 0) 8 else 0
    val out = ByteArray(20 + attrLen)
    out[0] = 0x00; out[1] = 0x01
    out[2] = ((attrLen ushr 8) and 0xff).toByte(); out[3] = (attrLen and 0xff).toByte()
    out[4] = 0x21; out[5] = 0x12; out[6] = 0xA4.toByte(); out[7] = 0x42
    for (i in tx.indices) out[8 + i] = tx[i]
    if (flags != 0) {
        out[20] = 0x00; out[21] = 0x03
        out[22] = 0x00; out[23] = 0x04
        out[24] = 0x00; out[25] = 0x00; out[26] = 0x00; out[27] = flags.toByte()
    }
    return out to tx
}

private fun parseStunResponse(data: ByteArray, tx: ByteArray, source: StunEndpoint, elapsedMs: Long): StunResponse? {
    if (data.size < 20) return null
    val type = u16(data, 0)
    if (type != 0x0101) return null
    if (data[4] != 0x21.toByte() || data[5] != 0x12.toByte() || data[6] != 0xA4.toByte() || data[7] != 0x42.toByte()) return null
    for (i in tx.indices) if (data[8 + i] != tx[i]) return null
    val length = u16(data, 2).coerceAtMost(data.size - 20)
    var pos = 20
    val end = 20 + length
    var mapped: StunEndpoint? = null
    var changed: StunEndpoint? = null
    var other: StunEndpoint? = null
    while (pos + 4 <= end && pos + 4 <= data.size) {
        val attrType = u16(data, pos)
        val attrLen = u16(data, pos + 2)
        val valuePos = pos + 4
        if (valuePos + attrLen > data.size) break
        when (attrType) {
            0x0001 -> mapped = parseMappedAddress(data, valuePos, attrLen, false, data.copyOfRange(8, 20)) ?: mapped
            0x0020 -> mapped = parseMappedAddress(data, valuePos, attrLen, true, data.copyOfRange(8, 20)) ?: mapped
            0x0005 -> changed = parseMappedAddress(data, valuePos, attrLen, false, data.copyOfRange(8, 20)) ?: changed
            0x802c -> other = parseMappedAddress(data, valuePos, attrLen, false, data.copyOfRange(8, 20)) ?: other
        }
        pos = valuePos + attrLen + ((4 - (attrLen % 4)) % 4)
    }
    return StunResponse(mapped, changed, other, source, elapsedMs)
}

private fun parseMappedAddress(data: ByteArray, pos: Int, len: Int, xor: Boolean, tx: ByteArray): StunEndpoint? {
    if (len < 8 || pos + len > data.size) return null
    val family = u8(data, pos + 1)
    val rawPort = u16(data, pos + 2)
    val port = if (xor) rawPort xor 0x2112 else rawPort
    return when (family) {
        0x01 -> {
            if (len < 8) return null
            val addr = ByteArray(4)
            val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
            for (i in 0 until 4) addr[i] = (u8(data, pos + 4 + i) xor (if (xor) u8(cookie, i) else 0)).toByte()
            StunEndpoint(InetAddress.getByAddress(addr).hostAddress ?: "", port)
        }
        0x02 -> {
            if (len < 20) return null
            val mask = ByteArray(16)
            mask[0] = 0x21; mask[1] = 0x12; mask[2] = 0xA4.toByte(); mask[3] = 0x42
            for (i in tx.indices) mask[4 + i] = tx[i]
            val addr = ByteArray(16)
            for (i in 0 until 16) addr[i] = (u8(data, pos + 4 + i) xor (if (xor) u8(mask, i) else 0)).toByte()
            StunEndpoint(InetAddress.getByAddress(addr).hostAddress ?: "", port)
        }
        else -> null
    }
}

private fun u8(data: ByteArray, idx: Int): Int = data[idx].toInt() and 0xff
private fun u16(data: ByteArray, idx: Int): Int = (u8(data, idx) shl 8) or u8(data, idx + 1)


suspend fun sshExec(host: String, port: Int, user: String, pass: String, cmd: String): String = withContext(Dispatchers.IO) {
    val session = JSch().getSession(user, host, port); session.setPassword(pass)
    val cfg = java.util.Properties(); cfg["StrictHostKeyChecking"]="no"; cfg["PreferredAuthentications"]="password,keyboard-interactive,publickey"; cfg["server_host_key"]="ssh-rsa,rsa-sha2-256,rsa-sha2-512,ssh-ed25519,ecdsa-sha2-nistp256"; cfg["PubkeyAcceptedAlgorithms"]="+ssh-rsa,rsa-sha2-256,rsa-sha2-512"; cfg["kex"]="curve25519-sha256@libssh.org,curve25519-sha256,ecdh-sha2-nistp256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1"; cfg["cipher.s2c"]="aes256-ctr,aes128-ctr,aes192-ctr,aes128-cbc,3des-cbc"; cfg["cipher.c2s"]="aes256-ctr,aes128-ctr,aes192-ctr,aes128-cbc,3des-cbc"; cfg["mac.s2c"]="hmac-sha2-256,hmac-sha2-512,hmac-sha1"; cfg["mac.c2s"]="hmac-sha2-256,hmac-sha2-512,hmac-sha1"; cfg["enable_server_sig_algs"]="yes"; session.setConfig(cfg)
    session.userInfo = object: UserInfo, UIKeyboardInteractive { override fun getPassphrase(): String?=null; override fun getPassword(): String=pass; override fun promptPassword(message:String?)=true; override fun promptPassphrase(message:String?)=false; override fun promptYesNo(message:String?)=true; override fun showMessage(message:String?){}; override fun promptKeyboardInteractive(destination:String?, name:String?, instruction:String?, prompt:Array<out String>?, echo:BooleanArray?): Array<String> = Array(prompt?.size ?: 0) { pass } }
    session.connect(10000); val ch = session.openChannel("exec") as ChannelExec; ch.setCommand(cmd); val err=ByteArrayOutputStream(); ch.setErrStream(err); val input=ch.inputStream; ch.connect(10000); val out=input.bufferedReader().readText(); val errText=err.toString().trim(); val exit=ch.exitStatus; ch.disconnect(); session.disconnect(); buildString { val hasOut = out.isNotBlank(); val title = when { exit == 0 -> "执行成功"; exit == -1 && hasOut -> "执行完成 · 未获取退出码"; exit != 0 && hasOut -> "执行完成 · exit $exit"; else -> "执行失败 · exit $exit" }; append(title); append("\n"); append(out.ifBlank { "无输出" }); if(errText.isNotBlank()) append("\nERR: ").append(errText); if (exit != 0 && !hasOut) append("\n返回码：").append(exit) }
}


fun sshRealOutput(raw: String): String {
    val lines = raw.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    val useful = lines.dropWhile { it.startsWith("执行") || it.startsWith("SSH失败") }
        .filterNot { it.startsWith("返回码：") }
    return useful.joinToString("\n").ifBlank { raw.lines().filterNot { it.startsWith("返回码：") }.joinToString("\n").trim().ifBlank { "无输出" } }
}

suspend fun traceRouteSmart(host: String, maxHops: Int, timeoutMs: Int, dns1: String, dns2: String, ipMode: String, onUpdate: suspend (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
    val targetHost = extractLatencyHost(host)
    val targets = resolveProbeTargets(targetHost, dns1, dns2, ipMode)
    if (targets.isEmpty()) return@withContext "无法解析或当前 IP 策略无可用地址：$targetHost"
    val target = targets.first()
    val is6 = target.contains(":")
    val max = maxHops.coerceIn(4, 32)
    val timeoutSec = ((timeoutMs.coerceIn(500, 5000) + 999) / 1000).coerceAtLeast(1)
    val cmdName = if (is6) "/system/bin/ping6" else "/system/bin/ping"
    val fallbackCmdName = "/system/bin/ping"
    val out = mutableListOf<String>()
    out += "目标 $targetHost → $target"
    out += "说明：Android 无原生 traceroute 权限时，用逐跳 TTL Ping 近似追踪。"
    withContext(Dispatchers.Main) { onUpdate(out.joinToString("\n")) }
    for (ttl in 1..max) {
        val start = SystemClock.elapsedRealtime()
        val commands = if (is6) listOf(
            listOf(cmdName, "-c", "1", "-W", timeoutSec.toString(), "-t", ttl.toString(), target),
            listOf(fallbackCmdName, "-6", "-c", "1", "-W", timeoutSec.toString(), "-t", ttl.toString(), target)
        ) else listOf(listOf(cmdName, "-c", "1", "-W", timeoutSec.toString(), "-t", ttl.toString(), target))
        val raw = commands.asSequence().mapNotNull { cmd -> runCatching { runTraceCommand(cmd, timeoutMs.coerceIn(500, 5000) + 1200) }.getOrNull() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val elapsed = SystemClock.elapsedRealtime() - start
        val hop = parseTraceHop(raw)
        val reached = raw.contains("bytes from", true) || raw.contains(" 0% packet loss", true) || hop == target
        out += ttl.toString().padStart(2, '0') + "  " + (hop ?: "*") + "  ${elapsed}ms"
        withContext(Dispatchers.Main) { onUpdate(out.joinToString("\n")) }
        if (reached) break
    }
    out.joinToString("\n")
}

fun countTraceHops(output: String): Int = output.lines().count { line -> line.trimStart().take(2).trim().toIntOrNull() != null }

private fun runTraceCommand(cmd: List<String>, waitMs: Int): String {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val done = p.waitFor(waitMs.toLong(), TimeUnit.MILLISECONDS)
    if (!done) p.destroyForcibly()
    return p.inputStream.bufferedReader().readText().trim()
}

private fun parseTraceHop(raw: String): String? {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val fromRegex = Regex("(?i)(?:from|bytes from)\\s+([0-9a-fA-F:.%]+)")
    for (line in lines) {
        fromRegex.find(line)?.groupValues?.getOrNull(1)?.trim('[', ']', ':')?.substringBefore('%')?.let { if (it.isNotBlank()) return it }
    }
    return null
}

fun cleanApiText(v: String?): String {
    val t = v?.trim().orEmpty()
    return if (t.equals("null", true) || t == "-" || t.equals("None", true)) "" else t
}

fun shortIpv6(value: String): String {
    val ip = value.substringBefore('/').trim()
    if (ip.length <= 24) return ip
    val parts = ip.split(':').filter { it.isNotBlank() }
    return if (parts.size >= 4) "${parts.take(2).joinToString(":")}:…:${parts.takeLast(2).joinToString(":")}" else ip.take(18) + "…"
}

fun maskSensitive(s: String): String = s.replace(Regex("(?i)(token|password|secret)[^,}]*"), "$1:***")
fun nowClock(): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

private fun formatTraffic(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}


suspend fun runDownloadTemplateTest(url: String, durationSec: Int, onTick: suspend (Double, Double, Double, Long) -> Unit): SpeedTestResult = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    val start = SystemClock.elapsedRealtime()
    val maxDuration = durationSec.coerceIn(4, 60) * 1000L
    val minDuration = 4000L
    var total = 0L
    var lastTotal = 0L
    var lastAt = start
    var peak = 0.0
    var stableTicks = 0
    var samples = 0
    var stopByPeak = false
    runCatching {
        while (SystemClock.elapsedRealtime() - start < maxDuration && currentCoroutineContext().isActive) {
            val req = Request.Builder().url(url).header("Cache-Control", "no-cache").header("Pragma", "no-cache").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val input = resp.body?.byteStream() ?: throw IllegalStateException("空响应")
                val buf = ByteArray(128 * 1024)
                while (SystemClock.elapsedRealtime() - start < maxDuration && currentCoroutineContext().isActive) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAt >= 650) {
                        val cur = (total - lastTotal) * 8.0 / (now - lastAt) / 1000.0
                        val avgElapsed = (now - start - 1000L).coerceAtLeast(1L)
                        val avg = total * 8.0 / avgElapsed / 1000.0
                        samples++
                        val improve = if (peak <= 0.1) 1.0 else (cur - peak) / peak
                        if (cur > peak) peak = cur
                        if (now - start > minDuration) {
                            stableTicks = if (improve < 0.03) stableTicks + 1 else 0
                            if (stableTicks >= 3) { stopByPeak = true; break }
                        }
                        withContext(Dispatchers.Main) { onTick(cur.coerceAtLeast(0.0), avg.coerceAtLeast(0.0), peak.coerceAtLeast(0.0), total) }
                        lastTotal = total; lastAt = now
                    }
                }
            }
            if (stopByPeak) break
        }
    }.onFailure {
        val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
        val avg = total * 8.0 / elapsed / 1000.0
        return@withContext SpeedTestResult(avg, peak, total, (elapsed/1000).toInt(), "测速中断：${it.javaClass.simpleName}${it.message?.let { m -> ": $m" } ?: ""}")
    }
    val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
    val avg = total * 8.0 / elapsed / 1000.0
    val note = if (stopByPeak) "峰值稳定，自动停止：${formatTraffic(total)} · 峰值 ${String.format(Locale.US, "%.1f Mbps", peak)}" else "完成：${formatTraffic(total)} · 峰值 ${String.format(Locale.US, "%.1f Mbps", peak)}"
    SpeedTestResult(avg, peak, total, (elapsed/1000).toInt(), note)
}

suspend fun readWifiSample(ctx: Context, pingTarget: String = "网关", timeoutMs: Int = 800): WifiSample = withContext(Dispatchers.IO) {
    val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val info = wifi?.connectionInfo
    val ssid = info?.ssid?.replace("\"", "") ?: "unknown"
    val bssid = info?.bssid ?: ""
    val rssi = info?.rssi ?: -127
    val linkMbps = runCatching { info?.linkSpeed ?: 0 }.getOrDefault(0)
    val gateway = runCatching { intToIp(wifi?.dhcpInfo?.gateway ?: 0) }.getOrDefault("")
    val target = pingTarget.trim().ifBlank { "网关" }.let { if (it == "网关" || it.equals("gateway", true)) gateway else it }
    val latency = if (target.isNotBlank() && target != "0.0.0.0") runCatching { pingOnceAddress(InetAddress.getByName(target), timeoutMs.coerceIn(300, 5000)) }.getOrNull() else null
    WifiSample(now, ssid, bssid, rssi, latency, latency == null, linkMbps)
}

private fun intToIp(i: Int): String {
    if (i == 0) return ""
    return listOf(i and 0xff, i shr 8 and 0xff, i shr 16 and 0xff, i shr 24 and 0xff).joinToString(".")
}

suspend fun runMtuProbeSmart(host: String, ipv6: Boolean, onProgress: suspend (List<Pair<Int, Boolean>>) -> Unit = {}): MtuProbeResult = withContext(Dispatchers.IO) {
    val target = host.trim().ifBlank { if (ipv6) "2400:3200::1" else "223.5.5.5" }
    val resolvedAddr = resolveAddressForFamily(target, ipv6)
    if (resolvedAddr == null) {
        val type = if (ipv6) "AAAA / IPv6" else "A / IPv4"
        val summary = "无法按当前协议解析目标。\n协议：${if (ipv6) "IPv6" else "IPv4"}\n需要记录：$type\n目标：$target\n建议：IPv6请使用IPv6地址或有AAAA记录的域名；IPv4请使用IPv4地址或有A记录的域名。"
        return@withContext MtuProbeResult(summary, emptyList())
    }
    val resolved = resolvedAddr.hostAddress ?: target

    val basicOk = basicPingForMtu(resolved, ipv6)
    if (!basicOk) {
        val summary = "基础 Ping 不通，无法进行 MTU 探测。\n协议：${if (ipv6) "IPv6" else "IPv4"}\n目标：$target → $resolved\n说明：目标可能禁止 ICMP${if (ipv6) "v6" else ""} Echo、VPN/防火墙拦截，或当前网络不支持该协议；这不是 MTU 全部失败。"
        return@withContext MtuProbeResult(summary, emptyList())
    }

    val common = if (ipv6) {
        // IPv6 MTU ≈ payload + 48. 1232≈1280, 1444≈1492, 1452≈1500.
        listOf(1200, 1232, 1400, 1444, 1452)
    } else {
        // IPv4 MTU ≈ payload + 28. 1464≈1492, 1472≈1500.
        listOf(1200, 1280, 1400, 1460, 1464, 1472, 1480)
    }
    val rows = mutableListOf<Pair<Int, Boolean>>()
    var lastOk: Int? = null
    var firstFail: Int? = null
    for (p in common) {
        if (!currentCoroutineContext().isActive) break
        val ok = mtuPingOnce(resolved, p, ipv6)
        rows += p to ok
        withContext(Dispatchers.Main) { onProgress(rows.toList()) }
        if (ok) {
            lastOk = p
        } else if (firstFail == null && lastOk != null) {
            firstFail = p
        }
        if (!ok && lastOk != null) break
    }
    if (lastOk != null && firstFail != null && firstFail!! - lastOk!! > 1) {
        var lo = lastOk!!
        var hi = firstFail!!
        while (hi - lo > 1 && currentCoroutineContext().isActive) {
            val mid = (lo + hi) / 2
            val ok = mtuPingOnce(resolved, mid, ipv6)
            rows += mid to ok
            withContext(Dispatchers.Main) { onProgress(rows.toList()) }
            if (ok) lo = mid else hi = mid
        }
        lastOk = lo
    }
    val best = lastOk
    val mtuOverhead = if (ipv6) 48 else 28
    val mssOverhead = if (ipv6) 60 else 40
    val header = if (ipv6) {
        "IPv6 使用 ICMPv6 Echo payload 估算路径 MTU；不使用 IPv4 的 DF / -M do。\npayload 1232≈MTU 1280，1444≈1492，1452≈1500。"
    } else {
        "IPv4 使用 DF 禁止分片方式估算路径 MTU。\npayload 1464≈MTU 1492，1472≈1500。"
    }
    val summary = if (best != null) {
        val mtu = best + mtuOverhead
        val mss = (mtu - mssOverhead).coerceAtLeast(0)
        val hint = when {
            !ipv6 && mtu in 1488..1496 -> "疑似 PPPoE / VPN 常见 MTU 区间"
            !ipv6 && mtu >= 1500 -> "接近以太网常见 1500 MTU"
            ipv6 && mtu <= 1280 -> "IPv6 最小 MTU 附近；需关注 ICMPv6/PMTUD"
            ipv6 && mtu >= 1500 -> "IPv6 路径接近 1500 MTU"
            else -> "路径 MTU 可用，建议结合实际业务复测"
        }
        "$header\n\n最大通过 payload：$best bytes\n估算 ${if (ipv6) "IPv6" else "IPv4"} MTU：$mtu bytes\n建议 TCP MSS：$mss bytes\n判断：$hint\n目标：$target → $resolved\n" + rows.distinctBy { it.first }.sortedBy { it.first }.joinToString("\n") { "payload ${it.first}: ${if (it.second) "通过" else "失败/受限"}" }
    } else {
        "$header\n\n基础 Ping 可通，但未找到可通过 payload。可能 Android ping 参数受限、VPN/防火墙拦截大包，或路径 ICMP 报文被过滤。\n目标：$target → $resolved\n" + rows.distinctBy { it.first }.sortedBy { it.first }.joinToString("\n") { "payload ${it.first}: ${if (it.second) "通过" else "失败"}" }
    }
    MtuProbeResult(summary, rows.distinctBy { it.first }.sortedBy { it.first })
}

private fun resolveAddressForFamily(target: String, ipv6: Boolean): InetAddress? = runCatching {
    InetAddress.getAllByName(target).firstOrNull { addr -> if (ipv6) addr is Inet6Address else addr is Inet4Address }
}.getOrNull()

private fun basicPingForMtu(host: String, ipv6: Boolean): Boolean {
    val commands = if (ipv6) {
        listOf(
            listOf("/system/bin/ping6", "-c", "1", "-W", "1", host),
            listOf("/system/bin/ping", "-6", "-c", "1", "-W", "1", host)
        )
    } else {
        listOf(
            listOf("/system/bin/ping", "-c", "1", "-W", "1", host),
            listOf("/system/bin/ping", "-4", "-c", "1", "-W", "1", host)
        )
    }
    return commands.any { runPingCommand(it) }
}

private fun mtuPingOnce(host: String, payload: Int, ipv6: Boolean = false): Boolean {
    val commands = if (ipv6) {
        // IPv6 不使用 -M do；通过 ICMPv6 Echo 的 payload 大小估算。
        listOf(
            listOf("/system/bin/ping6", "-c", "1", "-W", "1", "-s", payload.toString(), host),
            listOf("/system/bin/ping", "-6", "-c", "1", "-W", "1", "-s", payload.toString(), host)
        )
    } else {
        listOf(
            listOf("/system/bin/ping", "-c", "1", "-W", "1", "-M", "do", "-s", payload.toString(), host)
        )
    }
    return commands.any { runPingCommand(it) }
}

private fun runPingCommand(cmd: List<String>): Boolean = runCatching {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    val finished = p.waitFor(2200, TimeUnit.MILLISECONDS)
    if (!finished || p.isAlive) p.destroyForcibly()
    val code = runCatching { p.exitValue() }.getOrDefault(1)
    code == 0 || text.contains("1 received", ignoreCase = true) || text.contains("1 packets received", ignoreCase = true) || text.contains("bytes from", ignoreCase = true)
}.getOrDefault(false)

suspend fun runDnsQuality(domain: String, servers: List<String>): List<DnsQualityRow> = withContext(Dispatchers.IO) {
    servers.map { server ->
        val start = SystemClock.elapsedRealtime()
        val a = DnsWire.query(domain, server, 1)
        val aaaa = DnsWire.query(domain, server, 28)
        val elapsed = SystemClock.elapsedRealtime() - start
        DnsQualityRow(server, if (a.isEmpty() && aaaa.isEmpty()) null else elapsed, a.take(2).joinToString(" / "), aaaa.take(2).joinToString(" / "), if (a.isEmpty() && aaaa.isEmpty()) "超时或无记录" else "正常")
    }
}

fun parseServiceTargets(text: String): List<ServiceTarget> = text.lines().mapNotNull { line ->
    val p = line.split(',').map { it.trim() }
    if (p.size < 4) null else ServiceTarget(p[0], p[1], p[2].toIntOrNull() ?: return@mapNotNull null, p[3].uppercase(Locale.getDefault()))
}.take(12)

suspend fun runServiceMonitor(targets: List<ServiceTarget>, prefs: AppPrefs): List<String> = withContext(Dispatchers.IO) {
    if (targets.isEmpty()) return@withContext listOf("没有可检测服务。")
    targets.map { t ->
        val r = if (t.protocol == "UDP") udpProbeSmart(t.host, t.port, 1000, prefs.dns1, prefs.dns2, "自动", "UDP 空包").lineSequence().firstOrNull().orEmpty() else tcpProbeSmart(t.host, t.port, 1000, prefs.dns1, prefs.dns2, "自动").lineSequence().firstOrNull().orEmpty()
        "${t.name}  ${t.protocol} ${t.host}:${t.port}\n$r"
    }
}


suspend fun runIpv6AvailabilityTest(onProgress: suspend (List<Ipv6TestRow>) -> Unit = {}): List<Ipv6TestRow> = withContext(Dispatchers.IO) {
    val rows = mutableListOf<Ipv6TestRow>()
    suspend fun add(name: String, ok: Boolean?, ms: Long, family: String, detail: String, route: String = "") {
        val status = when (ok) { true -> "成功"; false -> "失败"; null -> "完成" }
        val d = buildString {
            if (ms >= 0) append("(${String.format(Locale.US, "%.3fs", ms / 1000.0)}) ")
            if (family.isNotBlank()) append("使用 ").append(family).append(" · ")
            append(detail)
        }
        rows += Ipv6TestRow(name, status, d, ok, route)
        withContext(Dispatchers.Main) { onProgress(rows.toList()) }
    }

    suspend fun timed(name: String, family: String, route: String = "", block: () -> String): String? {
        val st = SystemClock.elapsedRealtime()
        return try {
            val detail = block().ifBlank { "无返回内容" }
            add(name, true, SystemClock.elapsedRealtime() - st, family, detail, route)
            detail
        } catch (e: Exception) {
            add(name, false, SystemClock.elapsedRealtime() - st, family, e.message ?: e.javaClass.simpleName, route)
            null
        }
    }

    var ipv4Exit = ""
    var ipv6Exit = ""
    var dualFamily = ""

    val ipv4 = timed("IPv4 公网出口测试", "ipv4", "tool_dns") {
        val r = firstHttpProbe(
            listOf("https://api.ipify.org", "https://ipv4.icanhazip.com", "https://v4.ident.me"),
            preferredIpv6 = false,
            timeoutMs = 3500
        )
        ipv4Exit = r.body.trim()
        "IPv4 出口 $ipv4Exit · 来源 ${hostOfUrl(r.url)}"
    }

    val ipv6 = timed("IPv6 公网出口测试", "ipv6", "tool_dns") {
        val r = firstHttpProbe(
            listOf("https://api64.ipify.org", "https://ipv6.icanhazip.com", "https://v6.ident.me"),
            preferredIpv6 = true,
            timeoutMs = 4500
        )
        ipv6Exit = r.body.trim()
        "IPv6 出口 $ipv6Exit · 来源 ${hostOfUrl(r.url)}"
    }

    timed("双栈域名连接测试", "自动", "tool_dns") {
        val r = firstHttpProbe(
            listOf("https://api64.ipify.org", "https://icanhazip.com", "https://ident.me"),
            preferredIpv6 = null,
            timeoutMs = 4500
        )
        dualFamily = inferFamilyFromIpText(r.body).ifBlank { r.family }
        "双栈域名可访问，实际使用 $dualFamily，返回 ${r.body.trim()} · 来源 ${hostOfUrl(r.url)}"
    }

    timed("双栈域名大数据包传输测试", dualFamily.ifBlank { "自动" }, "tool_ipv6") {
        val prefer = when (dualFamily) { "ipv6" -> true; "ipv4" -> false; else -> null }
        val r = httpGetWithPreferredFamily("https://speed.cloudflare.com/__down?bytes=65536", prefer, 6000)
        "下载 ${r.body.length.coerceAtLeast(0)} bytes · 实际使用 ${r.family}"
    }

    timed("IPv6 大数据包传输测试", "ipv6", "tool_ipv6") {
        val r = httpGetWithPreferredFamily("https://speed.cloudflare.com/__down?bytes=65536", true, 6500)
        "下载 ${r.body.length.coerceAtLeast(0)} bytes"
    }

    timed("本机 DNS AAAA 解析能力", "系统DNS", "tool_dns") {
        val domains = listOf("test-ipv6.com", "ipv6.google.com", "www.cloudflare.com", "testipv6.cn")
        val first = domains.firstNotNullOfOrNull { d ->
            val aaaas = runCatching { InetAddress.getAllByName(d).toList().filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
            if (aaaas.isNotEmpty()) d to aaaas else null
        } ?: throw IllegalStateException("系统 DNS 未返回 AAAA；这不等同于运营商 DNS IPv6 权威查询能力")
        "${first.first} 返回 AAAA ${first.second.take(2).joinToString(" / ")}"
    }

    timed("查询 IPv4 运营商", "ipv4", "tool_dns") {
        if (ipv4Exit.isBlank()) throw IllegalStateException("未获得 IPv4 公网出口，无法查询 ASN")
        lookupIpOwnerOnline(ipv4Exit)
    }

    timed("查询 IPv6 运营商", "ipv6", "tool_dns") {
        if (ipv6Exit.isBlank()) throw IllegalStateException("未获得 IPv6 公网出口，无法查询 ASN")
        lookupIpOwnerOnline(ipv6Exit)
    }

    val checked = rows.count { it.ok != null }.coerceAtLeast(1)
    val okCount = rows.count { it.ok == true }
    val conclusion = when {
        ipv6 != null && ipv4 != null -> "IPv6 可用性 $okCount/$checked，IPv4/IPv6 双栈公网出口均可用"
        ipv6 != null && ipv4 == null -> "IPv6 可用性 $okCount/$checked，IPv6 可用；IPv4 公网出口失败或受限"
        ipv6 == null && ipv4 != null -> "IPv6 可用性 $okCount/$checked，仅 IPv4 公网出口可用；IPv6 不可用或受限"
        else -> "IPv6 可用性 $okCount/$checked，IPv4/IPv6 公网出口均失败，请检查网络/VPN/DNS"
    }
    add("IPv6 综合结论", null, -1, "", conclusion)
    rows.toList()
}

private data class HttpFamilyProbe(val url: String, val body: String, val family: String)

private fun firstHttpProbe(urls: List<String>, preferredIpv6: Boolean?, timeoutMs: Long): HttpFamilyProbe {
    var lastError = ""
    for (url in urls) {
        try {
            return httpGetWithPreferredFamily(url, preferredIpv6, timeoutMs)
        } catch (e: Exception) {
            lastError = "${hostOfUrl(url)}: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    throw IllegalStateException("所有测试源失败：$lastError")
}

private fun httpGetWithPreferredFamily(url: String, preferredIpv6: Boolean?, timeoutMs: Long): HttpFamilyProbe {
    val clientBuilder = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    if (preferredIpv6 != null) {
        clientBuilder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = InetAddress.getAllByName(hostname).toList()
                val selected = all.filter { if (preferredIpv6) it is Inet6Address else it is Inet4Address }
                if (selected.isEmpty()) throw IllegalStateException("DNS 未解析到 ${if (preferredIpv6) "IPv6/AAAA" else "IPv4/A"} 记录")
                return selected
            }
        })
    }
    val client = clientBuilder.build()
    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
        val body = resp.body?.string().orEmpty().trim()
        val family = inferFamilyFromIpText(body).ifBlank {
            when (preferredIpv6) { true -> "ipv6"; false -> "ipv4"; null -> "自动" }
        }
        return HttpFamilyProbe(url, body, family)
    }
}

private fun inferFamilyFromIpText(text: String): String {
    val t = text.trim().removePrefix("[").removeSuffix("]")
    return when {
        t.contains(":") -> "ipv6"
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(t) -> "ipv4"
        else -> ""
    }
}

private fun hostOfUrl(url: String): String = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(url)

private fun lookupIpOwnerOnline(ip: String): String {
    val cleanIp = ip.trim().removePrefix("[").removeSuffix("]")
    val prefixGuess = inferOperatorByIpOnly(cleanIp)
    val client = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    val encoded = URLEncoder.encode(cleanIp, "UTF-8")
    runCatching {
        val url = "http://ip-api.com/json/$encoded?fields=status,message,query,as,isp,org"
        val text = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        val o = JSONObject(text)
        if (o.optString("status") == "success") {
            val asText = o.optString("as")
            val isp = o.optString("isp")
            val org = o.optString("org")
            val carrier = carrierFromAsnOrg(asText, isp, org).ifBlank { prefixGuess }
            return listOf(carrier, asText, isp.ifBlank { org }).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "ASN 查询成功，但运营商未知" }
        }
    }

    runCatching {
        val url = "https://api.ip.sb/geoip/$encoded"
        val text = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        val o = JSONObject(text)
        val asn = o.optString("asn")
        val org = o.optString("organization").ifBlank { o.optString("isp") }
        val carrier = carrierFromAsnOrg(asn, org, org).ifBlank { prefixGuess }
        if (asn.isNotBlank() || org.isNotBlank()) {
            return listOf(carrier, asn.takeIf { it.isNotBlank() }?.let { "AS$it" }.orEmpty(), org).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        }
    }

    if (prefixGuess.isNotBlank()) return "$prefixGuess · IPv6 前缀判断（在线 ASN 查询失败）"
    throw IllegalStateException("ASN/Geo 查询失败")
}

private fun carrierFromAsnOrg(asText: String, isp: String, org: String): String {
    val text = "$asText $isp $org".lowercase(Locale.getDefault())
    val asn = Regex("as\\s*(\\d+)", RegexOption.IGNORE_CASE).find(asText)?.groupValues?.getOrNull(1).orEmpty()
    return when {
        asn in setOf("4134", "4809", "4812", "4816", "58543") || text.contains("chinanet") || text.contains("china telecom") || text.contains("ct") && text.contains("telecom") -> "中国电信"
        asn in setOf("4837", "4808", "9929", "10099") || text.contains("china unicom") || text.contains("unicom") -> "中国联通"
        asn in setOf("9808", "56040", "56041", "56046", "58453") || text.contains("china mobile") || text.contains("cmcc") || text.contains("cmi") -> "中国移动"
        asn == "4538" || text.contains("cernet") || text.contains("education") -> "中国教育网"
        else -> ""
    }
}


fun copy(ctx: Context, text: String) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("极客网探", text)); toast(ctx, "已复制") }
