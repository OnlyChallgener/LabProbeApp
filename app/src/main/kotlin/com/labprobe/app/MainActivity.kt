package com.labprobe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import com.jcraft.jsch.UIKeyboardInteractive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

private const val DEFAULT_HUB = "http://192.168.5.46:58443"
private const val DEFAULT_DNS = "223.5.5.5"
private const val DEFAULT_TOKEN = ""

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LabProbeApp(AppPrefs(this)) }
    }
}

class AppPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("labprobe", Context.MODE_PRIVATE)
    var hub: String
        get() = sp.getString("hub", DEFAULT_HUB) ?: DEFAULT_HUB
        set(value) = sp.edit().putString("hub", value.trim().trimEnd('/')).apply()
    var token: String
        get() = sp.getString("token", DEFAULT_TOKEN) ?: DEFAULT_TOKEN
        set(value) = sp.edit().putString("token", value.trim()).apply()
    var hubDns: String
        get() = sp.getString("hub_dns", DEFAULT_DNS) ?: DEFAULT_DNS
        set(value) = sp.edit().putString("hub_dns", value.trim()).apply()
    var dark: Boolean
        get() = sp.getBoolean("dark", false)
        set(value) = sp.edit().putBoolean("dark", value).apply()
}

data class DeviceItem(
    val name: String,
    val mac: String,
    val online: Boolean,
    val ip: String,
    val ssid: String,
    val band: String,
    val rssi: String,
    val rxrate: String
)

data class EventItem(
    val title: String,
    val type: String,
    val name: String,
    val oldValue: String,
    val newValue: String,
    val time: String
)

data class DnsRecord(val value: String, val type: String, val source: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabProbeApp(prefs: AppPrefs) {
    var dark by remember { mutableStateOf(prefs.dark) }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF47E8C8),
        secondary = Color(0xFF90DCCF),
        background = Color(0xFF071411),
        surface = Color(0xFF10231F),
        onSurface = Color(0xFFE6F7F2)
    ) else lightColorScheme(
        primary = Color(0xFF009F83),
        secondary = Color(0xFF3E786E),
        background = Color(0xFFF3FBF6),
        surface = Color.White,
        onSurface = Color(0xFF101C19)
    )

    MaterialTheme(colorScheme = scheme) {
        var page by remember { mutableStateOf(0) }
        val titles = listOf("首页", "终端", "工具", "记录", "我的")
        val icons = listOf(Icons.Rounded.Home, Icons.Rounded.Devices, Icons.Rounded.Build, Icons.Rounded.History, Icons.Rounded.Person)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { ExpressiveNav(titles, icons, page) { page = it } }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).expressiveBackground()) {
                AnimatedContent(page, label = "page") { p ->
                    when (p) {
                        0 -> HomeScreen(prefs)
                        1 -> DevicesScreen(prefs)
                        2 -> ToolsScreen()
                        3 -> EventsScreen(prefs)
                        else -> SettingsScreen(prefs, dark) { dark = it; prefs.dark = it }
                    }
                }
            }
        }
    }
}

fun Modifier.expressiveBackground(): Modifier = background(
    Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
            MaterialTheme.colorScheme.background
        )
    )
)

@Composable
fun ExpressiveNav(titles: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            titles.forEachIndexed { i, t ->
                val active = i == selected
                val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else Color.Transparent, label = "nav")
                val fg = if (active) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
                Column(
                    Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .background(bg)
                        .clickable { onSelect(i) }
                        .padding(horizontal = if (active) 22.dp else 10.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(icons[i], contentDescription = t, tint = fg, modifier = Modifier.size(22.dp))
                    Text(t, color = fg, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun ScreenShell(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(title, fontSize = 36.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        content()
    }
}


@Composable
fun ColumnScope.item(content: @Composable ColumnScope.() -> Unit) {
    content()
}

@Composable
fun ExpressiveCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(34.dp), clip = false),
        shape = RoundedCornerShape(34.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), lineHeight = 17.sp)
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun PillButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 15.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoRow(label: String, value: String?, copyable: Boolean = false) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f), fontWeight = FontWeight.Bold)
        Text(
            value?.takeIf { it.isNotBlank() } ?: "未获取",
            Modifier.weight(1f).then(if (copyable && !value.isNullOrBlank()) Modifier.clickable { copy(ctx, value) } else Modifier),
            color = if (value.isNullOrBlank()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f) else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun LabeledInput(label: String, hint: String, value: String, onValueChange: (String) -> Unit, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(88.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun HomeScreen(prefs: AppPrefs) = ScreenShell("LabProbe", "NAS 出口 / STUN / DDNS / 终端概览") {
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("等待刷新") }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    item {
        ExpressiveCard("连接状态", "当前 Hub：${prefs.hub}\nHub DNS：${prefs.hubDns}，会过滤 127.0.0.1", Icons.Rounded.Link) {
            Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontWeight = FontWeight.SemiBold)
            PillButton(if (loading) "刷新中" else "手动刷新", Icons.Rounded.Refresh, !loading) {
                loading = true
                msg = "正在请求 Hub..."
                scope.launch {
                    val r = runCatching {
                        val st = HubApi(prefs).getStatus()
                        val dev = HubApi(prefs).getDevices(false)
                        st to dev
                    }
                    loading = false
                    r.onSuccess { (st, dev) -> status = st; devices = dev; msg = "刷新成功：${nowClock()}" }
                    r.onFailure { msg = "请求失败：${it.message}"; toast(ctx, msg) }
                }
            }
        }
    }
    item {
        val data = status?.optJSONObject("data") ?: status
        val nas = data?.optJSONObject("nas")
        ExpressiveCard("NAS 出口地址", "只展示 NAS 出口 IPv4 / IPv6，路由器 IPv6 暂时隐藏", Icons.Rounded.Public) {
            InfoRow("IPv4", nas?.optString("exitIpv4"), true)
            InfoRow("IPv6", nas?.optString("exitIpv6"), true)
            InfoRow("更新时间", data?.optString("updatedAt"))
        }
    }
    item {
        val data = status?.optJSONObject("data") ?: status
        val wg = data?.optJSONObject("wireguard")?.optString("publicAddress")
        val stun = data?.optJSONObject("stun")?.optString("publicAddress")
        ExpressiveCard("STUN / WireGuard", "点地址即可复制，不再单独放复制按钮", Icons.Rounded.VpnKey) {
            InfoRow("WG", wg, true)
            InfoRow("STUN", stun, true)
        }
    }
    item {
        val data = status?.optJSONObject("data") ?: status
        ExpressiveCard("关注终端", "来自锐捷 dev_sta 推送到 Hub 的当前在线状态", Icons.Rounded.Devices) {
            if (devices.isEmpty()) Text("暂无数据，先在终端页刷新或等待锐捷定时推送。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            devices.take(5).forEach { DeviceLine(it) }
            val ddns = data?.opt("ddns")
            if (ddns != null) {
                Spacer(Modifier.height(4.dp))
                Text("DDNS", fontWeight = FontWeight.Black)
                Text(ddns.toString(), maxLines = 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            }
        }
    }
}

@Composable
fun DevicesScreen(prefs: AppPrefs) = ScreenShell("终端", "关注设备与全部在线设备") {
    var onlineMode by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var msg by remember { mutableStateOf("等待刷新") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    item {
        ExpressiveCard("终端同步", "关注设备来自 config.yaml，全部在线来自 /api/devices?view=online", Icons.Rounded.Devices) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = !onlineMode, onClick = { onlineMode = false }, label = { Text("关注设备") })
                FilterChip(selected = onlineMode, onClick = { onlineMode = true }, label = { Text("全部在线") })
            }
            Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            PillButton("刷新终端", Icons.Rounded.Refresh) {
                scope.launch {
                    val r = runCatching { HubApi(prefs).getDevices(onlineMode) }
                    r.onSuccess { devices = it; msg = "完成：${it.size} 台，${nowClock()}" }
                    r.onFailure { msg = "失败：${it.message}"; toast(ctx, msg) }
                }
            }
        }
    }
    devices.forEach { d -> item { ExpressiveCard(d.name, d.mac, if (d.online) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel) { DeviceLine(d, details = true) } } }
}

@Composable
fun DeviceLine(d: DeviceItem, details: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (d.online) Color(0xFF10B981) else Color(0xFFEF4444)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name.ifBlank { d.mac }, fontWeight = FontWeight.Black)
            Text(listOf(d.ip, d.ssid, d.band, d.rxrate).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), fontSize = 13.sp)
            if (details) Text("RSSI ${d.rssi.ifBlank { "-" }} · ${d.mac}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 12.sp)
        }
        Text(if (d.online) "在线" else "离线", color = if (d.online) Color(0xFF10B981) else Color(0xFFEF4444), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ToolsScreen() = ScreenShell("工具", "Ping · DNS · TCP · SSH") {
    item { ToolTabs() }
    item { PingCard() }
    item { DnsCard() }
    item { TcpCard() }
    item { SshCard() }
}

@Composable
fun ToolTabs() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Ping 延迟", "DNS 解析", "TCP 端口", "SSH 命令").forEach {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)) {
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun PingCard() {
    var host by remember { mutableStateOf("223.5.5.5") }
    var count by remember { mutableStateOf("4") }
    var timeout by remember { mutableStateOf("1000") }
    var result by remember { mutableStateOf("等待执行") }
    val scope = rememberCoroutineScope()
    ExpressiveCard("Ping 延迟测试", "输入 IP 或域名；次数默认 4；超时单位 ms。", Icons.Rounded.Speed) {
        LabeledInput("目标", "223.5.5.5", host, { host = it })
        LabeledInput("次数", "4", count, { count = it }, KeyboardType.Number)
        LabeledInput("超时", "1000 ms", timeout, { timeout = it }, KeyboardType.Number)
        ResultText(result)
        PillButton("开始 Ping", Icons.Rounded.PlayArrow) {
            scope.launch { result = runCatching { ping(host, count.toIntOrNull() ?: 4, timeout.toIntOrNull() ?: 1000) }.getOrElse { "失败：${it.message}" } }
        }
    }
}

@Composable
fun DnsCard() {
    var domain by remember { mutableStateOf("net86.dynv6.net") }
    var dns by remember { mutableStateOf("223.5.5.5") }
    var type by remember { mutableStateOf("ALL") }
    var result by remember { mutableStateOf("等待解析") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    ExpressiveCard("DNS 解析 / nsLookup", "左侧为功能名；支持系统 DNS、指定 DNS、A/AAAA/ALL。点结果 IP 可复制。", Icons.Rounded.Dns) {
        LabeledInput("域名", "net86.dynv6.net", domain, { domain = it })
        LabeledInput("DNS", "system / 223.5.5.5 / 8.8.8.8", dns, { dns = it })
        LabeledInput("记录", "A / AAAA / ALL", type, { type = it.uppercase() })
        ResultText(result)
        PillButton("解析", Icons.Rounded.Search) {
            scope.launch {
                result = "解析中..."
                val r = runCatching { dnsLookup(domain.trim(), dns.trim(), type.trim().uppercase()) }
                result = r.getOrElse { listOf(DnsRecord("失败：${it.message}", "ERROR", dns)) }.joinToString("\n") { "${it.value} (${it.type})  ·  ${it.source}" }
            }
        }
        if (result.contains("(")) {
            Text("点这里复制结果", Modifier.clickable { copy(ctx, result) }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TcpCard() {
    var host by remember { mutableStateOf("192.168.5.46") }
    var port by remember { mutableStateOf("58443") }
    var timeout by remember { mutableStateOf("1000") }
    var result by remember { mutableStateOf("等待检测") }
    val scope = rememberCoroutineScope()
    ExpressiveCard("TCP 端口测试 / Telnet", "这是 TCP Connect 测试，用于判断 TCP 端口是否开放；不能判断 UDP，例如 WireGuard UDP。", Icons.Rounded.SettingsEthernet) {
        LabeledInput("主机", "192.168.5.46", host, { host = it })
        LabeledInput("端口", "58443", port, { port = it }, KeyboardType.Number)
        LabeledInput("超时", "1000 ms", timeout, { timeout = it }, KeyboardType.Number)
        ResultText(result)
        PillButton("测试端口", Icons.Rounded.Power) {
            scope.launch { result = tcpProbe(host, port.toIntOrNull() ?: 80, timeout.toIntOrNull() ?: 1000) }
        }
    }
}

@Composable
fun SshCard() {
    var host by remember { mutableStateOf("192.168.5.1") }
    var port by remember { mutableStateOf("54133") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("uptime") }
    var result by remember { mutableStateOf("等待连接") }
    val scope = rememberCoroutineScope()
    ExpressiveCard("SSH 单条命令", "适合执行 uptime、wg show 等简单命令。已开启 ssh-rsa / 老 KEX 兼容；极老闭源 SSH 仍可能失败。", Icons.Rounded.Terminal) {
        LabeledInput("主机", "192.168.5.1", host, { host = it })
        LabeledInput("端口", "54133", port, { port = it }, KeyboardType.Number)
        LabeledInput("用户", "root", user, { user = it })
        LabeledInput("密码", "SSH 密码", password, { password = it }, password = true)
        LabeledInput("命令", "uptime", command, { command = it })
        ResultText(result)
        PillButton("执行 SSH", Icons.Rounded.Terminal) {
            scope.launch { result = runCatching { sshExec(host, port.toIntOrNull() ?: 22, user, password, command) }.getOrElse { "SSH失败：${it.message}" } }
        }
    }
}

@Composable
fun ResultText(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
}

@Composable
fun EventsScreen(prefs: AppPrefs) = ScreenShell("记录", "STUN / DDNS / 终端变化事件") {
    var events by remember { mutableStateOf<List<EventItem>>(emptyList()) }
    var msg by remember { mutableStateOf("等待刷新") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    item {
        ExpressiveCard("事件同步", "已隐藏 token-only 的错误 Lucky Webhook 事件。", Icons.Rounded.History) {
            Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            PillButton("刷新事件", Icons.Rounded.Refresh) {
                scope.launch {
                    val r = runCatching { HubApi(prefs).getEvents() }
                    r.onSuccess { events = it; msg = "完成：${it.size} 条" }
                    r.onFailure { msg = "失败：${it.message}"; toast(ctx, msg) }
                }
            }
        }
    }
    events.forEach { e -> item { ExpressiveCard(e.title, e.time, Icons.Rounded.Bolt) { InfoRow("类型", e.type); InfoRow("名称", e.name); InfoRow("旧值", e.oldValue, true); InfoRow("新值", e.newValue, true) } } }
}

@Composable
fun SettingsScreen(prefs: AppPrefs, dark: Boolean, onDark: (Boolean) -> Unit) = ScreenShell("我的", "Hub 设置与主题") {
    var hub by remember { mutableStateOf(prefs.hub) }
    var token by remember { mutableStateOf(prefs.token) }
    var dns by remember { mutableStateOf(prefs.hubDns) }
    var msg by remember { mutableStateOf("等待测试") }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    item {
        ExpressiveCard("连接设置", "Hub 请求默认走指定 DNS，并过滤 127.0.0.1；外网可填 https://lp.net86.dynv6.net:2186", Icons.Rounded.Link) {
            LabeledInput("Hub", "http://192.168.5.46:58443", hub, { hub = it })
            LabeledInput("Token", "APP_TOKEN", token, { token = it })
            LabeledInput("DNS", "223.5.5.5 / 8.8.8.8 / system", dns, { dns = it })
            Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
            PillButton("保存设置", Icons.Rounded.Save) { prefs.hub = hub; prefs.token = token; prefs.hubDns = dns; toast(ctx, "已保存") }
            PillButton("测试连接", Icons.Rounded.WifiTethering) {
                prefs.hub = hub; prefs.token = token; prefs.hubDns = dns
                scope.launch { msg = runCatching { HubApi(prefs).health() }.getOrElse { "失败：${it.message}" } }
            }
        }
    }
    item {
        ExpressiveCard("主题", "浅色 / 黑夜模式", Icons.Rounded.Palette) { PillButton(if (dark) "切换到浅色模式" else "切换到黑夜模式", Icons.Rounded.DarkMode) { onDark(!dark) } }
    }
    item {
        ExpressiveCard("关于", "Kotlin + Jetpack Compose + Material 3 Expressive 风格", Icons.Rounded.Info) {
            Text("LabProbe / 极客网探\n版本 0.4.1\n当前版本手动刷新，不做后台推送。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontWeight = FontWeight.SemiBold)
        }
    }
}

class HubApi(private val prefs: AppPrefs) {
    private val client = OkHttpClient.Builder()
        .dns(CustomDns(prefs.hubDns))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun health(): String = withContext(Dispatchers.IO) {
        val body = getText("/health", auth = false)
        "连接成功：$body"
    }

    suspend fun getStatus(): JSONObject = withContext(Dispatchers.IO) { JSONObject(getText("/api/status", true)) }
    suspend fun getDevices(online: Boolean): List<DeviceItem> = withContext(Dispatchers.IO) {
        val path = if (online) "/api/devices?view=online" else "/api/devices"
        val root = JSONObject(getText(path, true))
        val arr = root.optJSONArray("devices") ?: JSONArray()
        (0 until arr.length()).mapNotNull { parseDevice(arr.optJSONObject(it)) }
    }
    suspend fun getEvents(): List<EventItem> = withContext(Dispatchers.IO) {
        val root = JSONObject(getText("/api/events", true))
        val arr = root.optJSONArray("events") ?: JSONArray()
        val list = mutableListOf<EventItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type")
            val nv = o.optString("newValue")
            if (type == "lucky_webhook" && (nv.contains("token", true) || nv.length < 10)) continue
            list += EventItem(o.optString("title", type.ifBlank { "事件" }), type, o.optString("name"), o.optString("oldValue", "-"), maskSensitive(nv.ifBlank { o.optString("value", "-") }), o.optString("createdAt", o.optString("time")))
        }
        list.reversed()
    }

    private fun getText(path: String, auth: Boolean): String {
        val url = joinUrl(prefs.hub, path)
        val b = Request.Builder().url(url)
        if (auth && prefs.token.isNotBlank()) b.header("Authorization", "Bearer ${prefs.token}")
        val res = client.newCall(b.build()).execute()
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
    fun query(host: String, server: String, qtype: Int): List<String> {
        val clean = host.trim().trimEnd('.')
        if (clean.isBlank()) return emptyList()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2500
                val id = SecureRandom().nextInt(65535)
                val packet = buildQuery(id, clean, qtype)
                socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(server, 53)))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                parseResponse(buf.copyOf(resp.length), qtype)
            }
        }.getOrDefault(emptyList())
    }

    private fun buildQuery(id: Int, host: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id); d.writeShort(0x0100); d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0)
        host.split('.').forEach { part -> val b = part.toByteArray(Charsets.UTF_8); d.writeByte(b.size); d.write(b) }
        d.writeByte(0); d.writeShort(qtype); d.writeShort(1)
        return out.toByteArray()
    }

    private fun parseResponse(data: ByteArray, qtype: Int): List<String> {
        if (data.size < 12) return emptyList()
        val an = u16(data, 6)
        var off = 12
        while (off < data.size && data[off].toInt() != 0) off += 1 + (data[off].toInt() and 0xff)
        off += 5
        val list = mutableListOf<String>()
        repeat(an) {
            off = skipName(data, off)
            if (off + 10 > data.size) return@repeat
            val type = u16(data, off); off += 2
            off += 2 // class
            off += 4 // ttl
            val len = u16(data, off); off += 2
            if (off + len > data.size) return@repeat
            if (type == qtype) {
                if (type == 1 && len == 4) list += InetAddress.getByAddress(data.copyOfRange(off, off + 4)).hostAddress ?: ""
                if (type == 28 && len == 16) list += InetAddress.getByAddress(data.copyOfRange(off, off + 16)).hostAddress ?: ""
            }
            off += len
        }
        return list.filter { it.isNotBlank() }.distinct()
    }
    private fun skipName(data: ByteArray, start: Int): Int { var o = start; while (o < data.size) { val v = data[o].toInt() and 0xff; if (v and 0xc0 == 0xc0) return o + 2; if (v == 0) return o + 1; o += 1 + v }; return o }
    private fun u16(data: ByteArray, off: Int): Int = ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)
}

suspend fun dnsLookup(domain: String, dns: String, type: String): List<DnsRecord> = withContext(Dispatchers.IO) {
    val server = dns.ifBlank { DEFAULT_DNS }
    if (server.equals("system", true)) {
        InetAddress.getAllByName(domain).filterNot { it.hostAddress == "127.0.0.1" }.map { DnsRecord(it.hostAddress ?: it.hostName, if ((it.hostAddress ?: "").contains(':')) "AAAA" else "A", "系统 DNS") }
    } else {
        val out = mutableListOf<DnsRecord>()
        if (type == "A" || type == "ALL") out += DnsWire.query(domain, server, 1).filter { it != "127.0.0.1" }.map { DnsRecord(it, "A", server) }
        if (type == "AAAA" || type == "ALL") out += DnsWire.query(domain, server, 28).map { DnsRecord(it, "AAAA", server) }
        out.ifEmpty { listOf(DnsRecord("无记录或超时", type, server)) }
    }
}

suspend fun ping(host: String, count: Int, timeoutMs: Int): String = withContext(Dispatchers.IO) {
    val timeoutSec = (timeoutMs / 1000).coerceAtLeast(1)
    val p = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", timeoutSec.toString(), host).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    p.waitFor(10, TimeUnit.SECONDS)
    text.lines().takeLast(8).joinToString("\n").ifBlank { "无输出" }
}

suspend fun tcpProbe(host: String, port: Int, timeout: Int): String = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
        "OPEN\n$host:$port\n耗时 ${System.currentTimeMillis() - start}ms"
    } catch (e: Exception) {
        "FAILED\n$host:$port\n耗时 ${System.currentTimeMillis() - start}ms\n${e.javaClass.simpleName}: ${e.message}"
    }
}

suspend fun sshExec(host: String, port: Int, user: String, pass: String, cmd: String): String = withContext(Dispatchers.IO) {
    val jsch = JSch()
    val session = jsch.getSession(user, host, port)
    session.setPassword(pass)

    // 适配锐捷 BE72 / 闭源 OpenWrt SSH：
    // 实测算法：server_host_key=ssh-rsa，kex=curve25519-sha256@libssh.org，cipher=aes256-ctr，mac=hmac-sha2-256。
    val cfg = java.util.Properties()
    cfg["StrictHostKeyChecking"] = "no"
    cfg["PreferredAuthentications"] = "password,keyboard-interactive,publickey"
    cfg["server_host_key"] = "ssh-rsa,rsa-sha2-256,rsa-sha2-512,ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521"
    cfg["PubkeyAcceptedAlgorithms"] = "+ssh-rsa,rsa-sha2-256,rsa-sha2-512"
    cfg["kex"] = "curve25519-sha256@libssh.org,curve25519-sha256,ecdh-sha2-nistp256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1"
    cfg["cipher.s2c"] = "aes256-ctr,aes128-ctr,aes192-ctr,aes128-cbc,3des-cbc"
    cfg["cipher.c2s"] = "aes256-ctr,aes128-ctr,aes192-ctr,aes128-cbc,3des-cbc"
    cfg["mac.s2c"] = "hmac-sha2-256,hmac-sha2-512,hmac-sha1"
    cfg["mac.c2s"] = "hmac-sha2-256,hmac-sha2-512,hmac-sha1"
    cfg["enable_server_sig_algs"] = "yes"
    session.setConfig(cfg)
    session.userInfo = object : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pass
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String> = Array(prompt?.size ?: 0) { pass }
    }

    session.connect(10000)
    val ch = session.openChannel("exec") as ChannelExec
    ch.setCommand(cmd)
    val err = ByteArrayOutputStream()
    ch.setErrStream(err)
    val input = ch.inputStream
    ch.connect(10000)
    val out = input.bufferedReader().readText()
    val errText = err.toString().trim()
    val exit = ch.exitStatus
    ch.disconnect(); session.disconnect()
    buildString {
        append(out.ifBlank { "无输出" })
        if (errText.isNotBlank()) append("\nERR: ").append(errText)
        append("\nexit=").append(exit)
    }
}

fun parseDevice(o: JSONObject?): DeviceItem? {
    if (o == null) return null
    val mac = o.optString("mac")
    val name = o.optString("name").ifBlank { o.optString("devRecommend") }.ifBlank { o.optString("hostName") }.ifBlank { o.optString("deviceName") }.ifBlank { mac }
    return DeviceItem(
        name = name,
        mac = mac,
        online = o.optBoolean("online", true),
        ip = o.optString("ip").ifBlank { o.optString("userIp") },
        ssid = o.optString("ssid"),
        band = o.optString("band"),
        rssi = o.optString("rssi"),
        rxrate = o.optString("rxrate")
    )
}

fun joinUrl(base: String, path: String): String {
    val b = base.trim().trimEnd('/')
    return if (path.startsWith("/")) b + path else "$b/$path"
}

fun maskSensitive(s: String): String = s.replace(Regex("(?i)(token|password|secret)[^,}]*"), "$1:***")
fun nowClock(): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
fun copy(ctx: Context, text: String) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("LabProbe", text)); toast(ctx, "已复制") }
