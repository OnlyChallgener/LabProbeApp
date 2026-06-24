package com.labprobe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.net.URLEncoder
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val DEFAULT_HUB = ""
private const val DEFAULT_DNS1 = "223.5.5.5"
private const val DEFAULT_DNS2 = "8.8.8.8"
private const val DEFAULT_TOKEN = ""

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

    var homeOrder: String get() = sp.getString("home_order", "status,exit,vpn,devices") ?: "status,exit,vpn,devices"
        set(v) = sp.edit().putString("home_order", v).apply()

    private fun getHistory(key: String): List<String> = (sp.getString(key, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotBlank() }.take(3)
    private fun putHistory(key: String, items: List<String>) { sp.edit().putString(key, items.distinct().take(3).joinToString("\n")).apply() }
    fun history(key: String): List<String> = getHistory("history_" + key)
    fun addHistory(key: String, value: String) { val v = value.trim(); if (v.isNotBlank()) putHistory("history_" + key, listOf(v) + getHistory("history_" + key).filter { it != v }) }
    fun removeHistory(key: String, value: String) { putHistory("history_" + key, getHistory("history_" + key).filter { it != value }) }

    var cacheStatus: String get() = sp.getString("cache_status", "") ?: ""
        set(v) = sp.edit().putString("cache_status", v).apply()
    var cacheDevices: String get() = sp.getString("cache_devices", "") ?: ""
        set(v) = sp.edit().putString("cache_devices", v).apply()
    var cacheOnlineDevices: String get() = sp.getString("cache_online_devices", "") ?: ""
        set(v) = sp.edit().putString("cache_online_devices", v).apply()
    var cacheEvents: String get() = sp.getString("cache_events", "") ?: ""
        set(v) = sp.edit().putString("cache_events", v).apply()
    var lastRefresh: String get() = sp.getString("last_refresh", "") ?: ""
        set(v) = sp.edit().putString("last_refresh", v).apply()

    var pingHost: String get() = sp.getString("ping_host", "223.5.5.5") ?: "223.5.5.5"
        set(v) = sp.edit().putString("ping_host", v).apply()
    var pingCount: String get() = sp.getString("ping_count", "20") ?: "20"
        set(v) = sp.edit().putString("ping_count", v).apply()
    var pingInterval: String get() = sp.getString("ping_interval", "500") ?: "500"
        set(v) = sp.edit().putString("ping_interval", v).apply()
    var pingTimeout: String get() = sp.getString("ping_timeout", "1000") ?: "1000"
        set(v) = sp.edit().putString("ping_timeout", v).apply()

    var dnsDomain: String get() = sp.getString("dns_domain", "net86.dynv6.net") ?: "net86.dynv6.net"
        set(v) = sp.edit().putString("dns_domain", v).apply()
    var dns1: String get() = sp.getString("dns1", DEFAULT_DNS1) ?: DEFAULT_DNS1
        set(v) = sp.edit().putString("dns1", v).apply()
    var dns2: String get() = sp.getString("dns2", DEFAULT_DNS2) ?: DEFAULT_DNS2
        set(v) = sp.edit().putString("dns2", v).apply()
    var dnsRecord: String get() = sp.getString("dns_record", "ALL") ?: "ALL"
        set(v) = sp.edit().putString("dns_record", v).apply()

    var tcpHost: String get() = sp.getString("tcp_host", "192.168.5.46") ?: "192.168.5.46"
        set(v) = sp.edit().putString("tcp_host", v).apply()
    var tcpPort: String get() = sp.getString("tcp_port", "58443") ?: "58443"
        set(v) = sp.edit().putString("tcp_port", v).apply()
    var tcpTimeout: String get() = sp.getString("tcp_timeout", "1000") ?: "1000"
        set(v) = sp.edit().putString("tcp_timeout", v).apply()
    var portProtocol: String get() = sp.getString("port_protocol", "TCP") ?: "TCP"
        set(v) = sp.edit().putString("port_protocol", v).apply()

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
}

data class DeviceItem(
    val name: String,
    val mac: String,
    val online: Boolean,
    val ip: String,
    val ssid: String,
    val band: String,
    val rssi: String,
    val rxrate: String,
    val onlineSince: String,
    val offlineAt: String,
    val onlineDurationText: String,
    val lastSeenAt: String
)

data class EventItem(
    val id: Int,
    val title: String,
    val type: String,
    val name: String,
    val oldValue: String,
    val newValue: String,
    val time: String,
    val ip: String = "",
    val rssi: String = "",
    val band: String = "",
    val rxrate: String = "",
    val onlineDurationText: String = ""
)
data class DnsRecord(val value: String, val type: String, val source: String, val operator: String = "")
data class PingPoint(val index: Int, val ms: Int?, val text: String)

class AppState(private val prefs: AppPrefs) {
    var status by mutableStateOf<JSONObject?>(prefs.cacheStatus.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() })
    var devices by mutableStateOf(parseDeviceArray(prefs.cacheDevices))
    var onlineDevices by mutableStateOf(parseDeviceArray(prefs.cacheOnlineDevices))
    var events by mutableStateOf(parseEvents(prefs.cacheEvents))
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
        val evs = api.getEvents()
        status = stRoot
        devices = devWatched
        onlineDevices = devOnline
        events = evs
        prefs.cacheStatus = stRoot.toString()
        prefs.cacheDevices = JSONArray(devWatched.map { it.toJson() }).toString()
        prefs.cacheOnlineDevices = JSONArray(devOnline.map { it.toJson() }).toString()
        prefs.cacheEvents = JSONArray(evs.map { it.toJson() }).toString()
        prefs.lastRefresh = nowClock()
        hubConnected = true
        message = "刷新成功：${prefs.lastRefresh}"
    }

    fun markHubChanged() {
        hubConnected = false
        message = "Hub 设置已变更，请测试或刷新"
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

    LaunchedEffect(Unit) { state.refreshAll() }
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

    MaterialTheme(colorScheme = if (dark) darkScheme else light) {
        val mainRoutes = listOf("home", "devices", "tools", "events", "settings")
        val navTitles = listOf("首页", "终端", "工具", "记录", "我的")
        val navIcons = listOf(Icons.Rounded.Home, Icons.Rounded.Devices, Icons.Rounded.Build, Icons.Rounded.History, Icons.Rounded.Person)
        val selected = mainRoutes.indexOf(if (route.startsWith("tool_")) "tools" else route).let { if (it < 0) 0 else it }
        BackHandler(route.startsWith("tool_")) { route = "tools" }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { ExpressiveNav(navTitles, navIcons, selected) { route = mainRoutes[it] } }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).appBackground()) {
                AnimatedContent(route, label = "route") { r ->
                    when (r) {
                        "home" -> HomeScreen(prefs, state, autoRefresh, { autoRefresh = it; prefs.autoRefresh = it }) { scope.launch { state.refreshAll() } }
                        "devices" -> DevicesScreen(state)
                        "tools" -> ToolsHomeScreen { route = it }
                        "events" -> EventsScreen(state, { scope.launch { state.refreshAll() } }, { route = "daily" })
                        "daily" -> DailyScreen(prefs) { route = "events" }
                        "settings" -> SettingsScreen(prefs, state, dark, autoRefresh, { dark = it; prefs.dark = it }, { autoRefresh = it; prefs.autoRefresh = it })
                        "tool_ping" -> PingScreen(prefs) { route = "tools" }
                        "tool_dns" -> DnsScreen(prefs) { route = "tools" }
                        "tool_port" -> PortProbeScreen(prefs) { route = "tools" }
                        "tool_ssh" -> SshScreen(prefs) { route = "tools" }
                        else -> HomeScreen(prefs, state, autoRefresh, { autoRefresh = it; prefs.autoRefresh = it }) { scope.launch { state.refreshAll() } }
                    }
                }
            }
        }
    }
}

@Composable
fun Modifier.appBackground(): Modifier = background(
    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary.copy(alpha = 0.045f), MaterialTheme.colorScheme.background))
)

@Composable
fun ScreenShell(title: String, subtitle: String, action: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 23.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            action?.invoke(this)
        }
        content()
    }
}

@Composable
fun DetailShell(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.ArrowBack, null) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        content()
    }
}

@Composable
fun ExpressiveNav(titles: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(tonalElevation = 8.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            titles.forEachIndexed { i, t ->
                val active = i == selected
                val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else Color.Transparent, label = "nav")
                val fg = if (active) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f)
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).clickable { onSelect(i) }.padding(horizontal = if (active) 15.dp else 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icons[i], null, tint = fg, modifier = Modifier.size(19.dp))
                    Text(t, color = fg, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun ExpressiveCard(title: String, subtitle: String? = null, icon: ImageVector? = null, accent: Color = MaterialTheme.colorScheme.primary, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().shadow(5.dp, RoundedCornerShape(22.dp), clip = false), shape = RoundedCornerShape(22.dp), tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(Modifier.size(33.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp)) }
                    Spacer(Modifier.width(9.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.5.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
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
        IconButton(onClick = { expanded = true }, enabled = items.isNotEmpty()) {
            Icon(Icons.Rounded.ArrowDropDown, null, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (items.isEmpty()) DropdownMenuItem(text = { Text("暂无历史") }, onClick = { expanded = false })
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onPick(item); expanded = false },
                    trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(17.dp).clickable { prefs.removeHistory(keyName, item); tick++ }) }
                )
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
            shape = RoundedCornerShape(18.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun LabeledInput(label: String, hint: String, value: String, onValueChange: (String) -> Unit, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(58.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(value = value, onValueChange = onValueChange, placeholder = { Text(hint, fontSize = 12.sp, maxLines = 1) }, singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None, keyboardOptions = KeyboardOptions(keyboardType = keyboardType), shape = RoundedCornerShape(18.dp), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectInput(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(58.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), fontSize = 12.sp, maxLines = 1)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(value = value, onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, shape = RoundedCornerShape(18.dp), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { options.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onChange(it); expanded = false }) } }
        }
    }
}

@Composable
fun HomeScreen(prefs: AppPrefs, state: AppState, autoRefresh: String, onAuto: (String) -> Unit, onRefresh: () -> Unit) = ScreenShell("LabProbe", "家庭网络仪表盘", action = {
    AssistChip(onClick = onRefresh, label = { Text(if (state.loading) "刷新中" else "刷新", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)) })
}) {
    var edit by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(prefs.homeOrder.split(',').filter { it.isNotBlank() }.ifEmpty { listOf("status","exit","vpn","devices") }) }
    val data = (state.status?.optJSONObject("data") ?: state.status)
    val nas = data?.optJSONObject("nas")
    val router = data?.optJSONObject("router")
    val nasV6 = nas?.optString("exitIpv6").orEmpty()
    val wg = if (nasV6.isNotBlank()) "[$nasV6]:51820" else data?.optJSONObject("wireguard")?.optString("publicAddress")
    val stun = data?.optJSONObject("stun")?.optString("publicAddress")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = { edit = !edit }, label = { Text(if (edit) "完成排序" else "排序", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.DragIndicator, null, Modifier.size(16.dp)) })
        Text("长按/点击排序后用箭头调整首页卡片顺序", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.46f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val cards = order.distinct().filter { it in listOf("status","exit","vpn","devices") } + listOf("status","exit","vpn","devices").filter { it !in order }
    cards.forEach { key ->
        val content: @Composable () -> Unit = when (key) {
            "status" -> { { HomeSortWrap(edit, key, cards, { order = it; prefs.homeOrder = it.joinToString(",") }) { StatusCard(prefs, state, autoRefresh, onAuto) } } }
            "exit" -> { { HomeSortWrap(edit, key, cards, { order = it; prefs.homeOrder = it.joinToString(",") }) { ExitCard(nas, router) } } }
            "vpn" -> { { if (!wg.isNullOrBlank() || !stun.isNullOrBlank()) HomeSortWrap(edit, key, cards, { order = it; prefs.homeOrder = it.joinToString(",") }) { VpnCard(wg, stun) } } }
            else -> { { HomeSortWrap(edit, key, cards, { order = it; prefs.homeOrder = it.joinToString(",") }) { DevicesHomeCard(state) } } }
        }
        content()
    }
}

@Composable
fun HomeSortWrap(edit: Boolean, key: String, order: List<String>, onOrder: (List<String>) -> Unit, content: @Composable () -> Unit) {
    Box {
        content()
        if (edit) Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { val i=order.indexOf(key); if(i>0) onOrder(order.toMutableList().also { java.util.Collections.swap(it, i, i-1) }) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, null) }
            IconButton(onClick = { val i=order.indexOf(key); if(i>=0 && i<order.size-1) onOrder(order.toMutableList().also { java.util.Collections.swap(it, i, i+1) }) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, null) }
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
            Box(Modifier.weight(1f)) { SelectInput("刷新", autoRefresh, listOf("手动", "3S", "10S", "30S"), onAuto) }
            Text("最后成功 ${prefs.lastRefresh.ifBlank { "-" }}", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f))
        }
    }
}

@Composable
fun ExitCard(nas: JSONObject?, router: JSONObject?) {
    ExpressiveCard("出口与路由", "NAS 出口、路由 WAN IPv6，点地址复制。", Icons.Rounded.Public, Color(0xFF0EA5E9)) {
        InfoRowVisible("NAS IPv4", nas?.optString("exitIpv4"), true)
        InfoRowVisible("NAS IPv6", nas?.optString("exitIpv6"), true)
        InfoRowVisible("路由 WAN", router?.optString("wanIpv6") ?: router?.optString("exitIpv6"), true)
    }
}

@Composable
fun VpnCard(wg: String?, stun: String?) {
    ExpressiveCard("VPN / STUN", "仅显示已获取的 WireGuard / OpenVPN / EasyTier 地址。", Icons.Rounded.VpnKey, Color(0xFF7C3AED)) {
        InfoRowVisible("WG", wg, true)
        InfoRowVisible("STUN", stun, true)
    }
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
fun DevicesScreen(state: AppState) = ScreenShell("终端", "关注设备与全部在线设备") {
    var onlineMode by remember { mutableStateOf(false) }
    val list = if (onlineMode) state.onlineDevices else state.devices
    ExpressiveCard("终端同步", "${if (onlineMode) "全部在线" else "关注设备"} · ${list.size} 台", Icons.Rounded.Devices, Color(0xFFF59E0B)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !onlineMode, onClick = { onlineMode = false }, label = { Text("关注", fontSize = 12.sp) })
            FilterChip(selected = onlineMode, onClick = { onlineMode = true }, label = { Text("全部在线", fontSize = 12.sp) })
        }
        Text(state.message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    list.forEach { d -> ExpressiveCard(d.name, d.mac, if (d.online) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, if (d.online) Color(0xFF16A34A) else Color(0xFFEF4444)) { DeviceLine(d, details = true) } }
}

@Composable
fun DeviceLine(d: DeviceItem, details: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (d.online) Color(0xFF16A34A) else Color(0xFFEF4444)))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name.ifBlank { d.mac }, fontWeight = FontWeight.Black, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(d.ip, d.ssid, d.band, d.rxrate).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (details) {
                val stateText = if (d.online) "在线 ${d.onlineDurationText.ifBlank { "-" }} · 上线 ${d.onlineSince.ifBlank { "-" }}" else "离线 ${d.offlineAt.ifBlank { "-" }} · 最后 ${d.lastSeenAt.ifBlank { "-" }}"
                Text(stateText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(if (d.online) "在线" else "离线", color = if (d.online) Color(0xFF16A34A) else Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun ToolsHomeScreen(open: (String) -> Unit) = ScreenShell("工具", "二级页面，返回仍在 APP 内") {
    ToolEntry("Ping 延迟", "实时采样 · 1 秒刷新曲线", Icons.Rounded.Speed, Color(0xFF7C3AED)) { open("tool_ping") }
    ToolEntry("DNS 解析", "双 DNS · A/AAAA · 运营商", Icons.Rounded.Dns, Color(0xFF2563EB)) { open("tool_dns") }
    ToolEntry("端口探测", "TCP / UDP · 域名优先 AAAA", Icons.Rounded.SettingsEthernet, Color(0xFF0EA5E9)) { open("tool_port") }
    ToolEntry("SSH 命令", "锐捷 / NAS 单条命令", Icons.Rounded.Terminal, Color(0xFF64748B)) { open("tool_ssh") }
}

@Composable
fun ToolEntry(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().shadow(5.dp, RoundedCornerShape(22.dp), clip = false).clickable { onClick() }, shape = RoundedCornerShape(22.dp), tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.56f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = color)
        }
    }
}

@Composable
fun PingScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("Ping 测试", "采样间隔可变，界面固定 1 秒刷新", onBack) { PingTool(prefs) }
@Composable
fun DnsScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("DNS 解析", "双 DNS 备选与运营商识别", onBack) { DnsTool(prefs) }
@Composable
fun PortProbeScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("端口探测", "TCP / UDP，支持域名、IPv4、IPv6", onBack) { TcpTool(prefs) }
@Composable
fun SshScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell("SSH 命令", "二级页面执行，返回工具页", onBack) { SshTool(prefs) }

@Composable
fun PingTool(prefs: AppPrefs) {
    var host by remember { mutableStateOf(prefs.pingHost) }
    var count by remember { mutableStateOf(prefs.pingCount) }
    var interval by remember { mutableStateOf(prefs.pingInterval) }
    var timeout by remember { mutableStateOf(prefs.pingTimeout) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var points by remember { mutableStateOf<List<PingPoint>>(emptyList()) }
    var log by remember { mutableStateOf("等待测试") }
    val scope = rememberCoroutineScope()
    ExpressiveCard("参数", "默认 20 次；采样可 30/100/200/500/1000ms。", Icons.Rounded.Tune, Color(0xFF7C3AED)) {
        LabeledHistoryInput("目标", "223.5.5.5", host, { host = it; prefs.pingHost = it }, "ping_host", prefs)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { LabeledInput("次数", "20", count, { count = it; prefs.pingCount = it }, KeyboardType.Number) }
            Box(Modifier.weight(1f)) { SelectInput("间隔", interval, listOf("30", "100", "200", "500", "1000")) { interval = it; prefs.pingInterval = it } }
        }
        LabeledInput("超时", "1000", timeout, { timeout = it; prefs.pingTimeout = it }, KeyboardType.Number)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                prefs.addHistory("ping_host", host)
                running = true; points = emptyList(); log = "开始测试..."
                job?.cancel()
                job = scope.launch {
                    val c = count.toIntOrNull() ?: 20
                    val inter = interval.toLongOrNull() ?: 500L
                    val to = timeout.toIntOrNull() ?: 1000
                    val buffer = mutableListOf<PingPoint>()
                    var lastUi = System.currentTimeMillis()
                    for (i in 1..c) {
                        if (!running) break
                        val ms = pingOnce(host, to)
                        buffer += PingPoint(i, ms, if (ms == null) "#$i timeout" else "#$i ${ms}ms")
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 1000L || i == c) { points = buffer.toList(); log = buffer.takeLast(8).joinToString("\n") { it.text }; lastUi = now }
                        delay(inter)
                    }
                    points = buffer.toList(); log = buffer.takeLast(8).joinToString("\n") { it.text }
                    running = false
                }
            }, enabled = !running, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (points.isEmpty()) "开始" else "重新") }
            Button(onClick = { running = false; job?.cancel(); log = if (points.isEmpty()) "已停止" else log + "\n已停止" }, enabled = running, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) { Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("停止") }
        }
    }
    ExpressiveCard("延迟曲线", "X 轴为时间 s，Y 轴为延迟 ms；图表约 1 秒刷新。", Icons.Rounded.ShowChart, Color(0xFF06B6D4)) { PingChart(points); PingStats(points) }
    ExpressiveCard("响应日志", null, Icons.Rounded.Notes, Color(0xFF64748B)) { ResultText(log) }
}

@Composable
fun PingChart(points: List<PingPoint>) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), modifier = Modifier.fillMaxWidth().height(190.dp)) {
        Canvas(Modifier.fillMaxSize().padding(start = 34.dp, end = 12.dp, top = 16.dp, bottom = 26.dp)) {
            val ok = points.filter { it.ms != null }
            val rawMax = (ok.maxOfOrNull { it.ms ?: 1 } ?: 50).coerceAtLeast(50)
            val yMax = when { rawMax <= 50 -> 50; rawMax <= 100 -> 100; rawMax <= 200 -> 200; rawMax <= 500 -> 500; else -> ((rawMax + 99) / 100) * 100 }
            val w = size.width; val h = size.height
            val axis = Color.Gray.copy(alpha = 0.30f)
            drawLine(axis, Offset(0f,h), Offset(w,h), strokeWidth=2f)
            drawLine(axis, Offset(0f,0f), Offset(0f,h), strokeWidth=2f)
            val yTicks = 5
            for (i in 0..yTicks) {
                val y = h * i / yTicks
                drawLine(Color.Gray.copy(alpha = 0.15f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (points.size >= 2) {
                val path = Path(); var started = false
                points.forEachIndexed { idx, p ->
                    if (p.ms != null) {
                        val x = w * idx / (points.size - 1).coerceAtLeast(1)
                        val y = h - (p.ms.toFloat() / yMax.toFloat() * h)
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    } else started = false
                }
                drawPath(path, Color(0xFF38BDF8), style = Stroke(width = 5f, cap = StrokeCap.Round))
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.52f))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val maxIdx = points.size.coerceAtLeast(1)
                val marks = (0 until maxIdx).filterIndexed { index, _ -> index % ((maxIdx / 7).coerceAtLeast(1)) == 0 }.take(8)
                if (marks.isEmpty()) Text("0s", fontSize = 10.sp) else marks.forEach { Text("${it}s", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.52f)) }
            }
        }
    }
}

@Composable
fun PingStats(points: List<PingPoint>) {
    val ok = points.mapNotNull { it.ms }
    val sent = points.size
    val loss = if (sent == 0) 0 else ((sent - ok.size) * 100 / sent)
    val avg = if (ok.isEmpty()) "-" else "${ok.average().roundToInt()}ms"
    val min = ok.minOrNull()?.let { "${it}ms" } ?: "-"
    val max = ok.maxOrNull()?.let { "${it}ms" } ?: "-"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { StatChip("平均", avg); StatChip("丢包", "$loss%"); StatChip("最短", min); StatChip("最长", max) }
}

@Composable fun StatChip(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)); Text(value, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, maxLines = 1) } }

@Composable
fun DnsTool(prefs: AppPrefs) {
    var domain by remember { mutableStateOf(prefs.dnsDomain) }
    var dns1 by remember { mutableStateOf(prefs.dns1) }
    var dns2 by remember { mutableStateOf(prefs.dns2) }
    var type by remember { mutableStateOf(prefs.dnsRecord) }
    var result by remember { mutableStateOf<List<DnsRecord>>(emptyList()) }
    var msg by remember { mutableStateOf("等待解析") }
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    ExpressiveCard("查询配置", "DNS1 失败自动尝试 DNS2，仅显示运营商。", Icons.Rounded.Dns, Color(0xFF2563EB)) {
        LabeledHistoryInput("域名", "net86.dynv6.net", domain, { domain = it; prefs.dnsDomain = it }, "dns_domain", prefs)
        LabeledHistoryInput("DNS1", "system / 223.5.5.5 / 2400:3200::1", dns1, { dns1 = it; prefs.dns1 = it }, "dns1", prefs)
        LabeledHistoryInput("DNS2", "8.8.8.8 / dns.google / system", dns2, { dns2 = it; prefs.dns2 = it }, "dns2", prefs)
        SelectInput("记录", type, listOf("A", "AAAA", "ALL")) { type = it; prefs.dnsRecord = it }
        PillButton("查询 DNS", Icons.Rounded.Search, accent = Color(0xFF2563EB)) { scope.launch { msg = "查询中..."; prefs.addHistory("dns_domain", domain); prefs.addHistory("dns1", dns1); prefs.addHistory("dns2", dns2); val records = dnsLookup(domain, dns1, dns2, type, prefs); result = records; msg = "完成：${records.size} 条" } }
    }
    ExpressiveCard("查询结果", msg, Icons.Rounded.TravelExplore, Color(0xFF06B6D4)) { if (result.isEmpty()) Text("暂无结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.55f)); result.forEach { r -> DnsResultRow(r) { copy(ctx, r.value) } } }
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
    var protocol by remember { mutableStateOf(prefs.portProtocol) }
    var result by remember { mutableStateOf("等待检测") }
    val scope = rememberCoroutineScope()
    ExpressiveCard("探测配置", "TCP 可判断开放；UDP 无响应只能判定开放或过滤。", Icons.Rounded.SettingsEthernet, Color(0xFF0EA5E9)) {
        SelectInput("协议", protocol, listOf("TCP", "UDP")) { protocol = it; prefs.portProtocol = it }
        LabeledHistoryInput("主机", "lp.net86.dynv6.net / IPv6", host, { host = it; prefs.tcpHost = it }, "port_host", prefs)
        LabeledHistoryInput("端口", "2186", port, { port = it; prefs.tcpPort = it }, "port_port", prefs, KeyboardType.Number)
        LabeledInput("超时", "1000", timeout, { timeout = it; prefs.tcpTimeout = it }, KeyboardType.Number)
        PillButton("开始探测", Icons.Rounded.Power, accent = Color(0xFF0EA5E9)) { scope.launch { prefs.addHistory("port_host", host); prefs.addHistory("port_port", port); result = if (protocol == "UDP") udpProbeSmart(host, port.toIntOrNull() ?: 53, timeout.toIntOrNull() ?: 1000, prefs.dns1, prefs.dns2) else tcpProbeSmart(host, port.toIntOrNull() ?: 80, timeout.toIntOrNull() ?: 1000, prefs.dns1, prefs.dns2) } }
    }
    ExpressiveCard("探测结果", protocol, Icons.Rounded.Route, Color(0xFF64748B)) { ResultText(result) }
}

@Composable
fun SshTool(prefs: AppPrefs) {
    var host by remember { mutableStateOf(prefs.sshHost) }
    var port by remember { mutableStateOf(prefs.sshPort) }
    var user by remember { mutableStateOf(prefs.sshUser) }
    var savePass by remember { mutableStateOf(prefs.sshSavePass) }
    var password by remember { mutableStateOf(if (prefs.sshSavePass) prefs.sshPassword else "") }
    var command by remember { mutableStateOf(prefs.sshCommand) }
    var result by remember { mutableStateOf("等待连接") }
    val scope = rememberCoroutineScope()
    ExpressiveCard("连接与命令", "默认 ip -6 neigh show；支持保存密码开关。", Icons.Rounded.Terminal, Color(0xFF64748B)) {
        LabeledHistoryInput("主机", "192.168.5.1", host, { host = it; prefs.sshHost = it }, "ssh_host", prefs)
        LabeledInput("端口", "54133", port, { port = it; prefs.sshPort = it }, KeyboardType.Number)
        LabeledInput("用户", "root", user, { user = it; prefs.sshUser = it })
        LabeledInput("密码", "SSH 密码", password, { password = it; if (savePass) prefs.sshPassword = it }, password = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("保存", Modifier.width(58.dp), fontWeight = FontWeight.Black, fontSize = 12.sp); Switch(checked = savePass, onCheckedChange = { savePass = it; prefs.sshSavePass = it; if (it) prefs.sshPassword = password else prefs.sshPassword = "" }); Text("保存密码", fontSize = 12.sp) }
        LabeledInput("命令", "ip -6 neigh show", command, { command = it; prefs.sshCommand = it })
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("邻居" to "ip -6 neigh show", "WAN" to "ip -6 addr show dev pppoe-wan scope global", "运行" to "uptime", "内核" to "uname -a", "存储" to "df -h").forEach { (t,c) -> AssistChip(onClick = { command = c; prefs.sshCommand = c }, label = { Text(t, fontSize = 11.5.sp) }) } }
        PillButton("执行 SSH", Icons.Rounded.Terminal, accent = Color(0xFF64748B)) { scope.launch { prefs.addHistory("ssh_host", host); result = runCatching { sshExec(host, port.toIntOrNull() ?: 22, user, password, command) }.getOrElse { "SSH失败：${it.message}" } } }
    }
    ExpressiveCard("执行结果", null, Icons.Rounded.Notes, Color(0xFF64748B)) { ResultText(result) }
}

@Composable fun ResultText(text: String) { Text(text, Modifier.fillMaxWidth().padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .70f), fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, fontSize = 12.5.sp) }

@Composable
fun EventsScreen(state: AppState, onRefresh: () -> Unit, openDaily: () -> Unit) = ScreenShell("记录", "紧凑事件流 · 左滑删除 · 每日总结", action = {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = openDaily, label = { Text("每日总结", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(17.dp)) })
        AssistChip(onClick = onRefresh, label = { Text("刷新", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)) })
    }
}) {
    val scope = rememberCoroutineScope()
    ExpressiveCard("事件同步", "上线、离线、STUN、DDNS 变化按通知样式显示。", Icons.Rounded.History, Color(0xFF7C3AED)) { Text(state.message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    state.events.forEach { e -> EventCompactCard(e) { scope.launch { state.deleteEvent(e) } } }
}

@Composable
fun EventCompactCard(e: EventItem, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val isOnline = e.type.contains("online")
    val isOffline = e.type.contains("offline")
    val accent = when {
        isOnline -> Color(0xFF16A34A)
        isOffline -> Color(0xFF7C3AED)
        e.type.contains("stun") || e.type.contains("wireguard") -> Color(0xFF0EA5E9)
        e.type.contains("ddns") -> Color(0xFFF59E0B)
        else -> Color(0xFF64748B)
    }
    val icon = when {
        isOnline -> Icons.Rounded.PhoneAndroid
        isOffline -> Icons.Rounded.Bedtime
        e.type.contains("stun") || e.type.contains("wireguard") -> Icons.Rounded.SyncAlt
        e.type.contains("ddns") -> Icons.Rounded.Public
        else -> Icons.Rounded.Bolt
    }
    Surface(modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(20.dp), clip = false), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .97f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha=.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifBlank { e.name }, fontSize = 15.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.time, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.52f), maxLines = 1)
                }
                Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha=.12f)) { Text(eventLabel(e.type), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1) }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp)) }
            }
            when {
                isOnline -> {
                    TwoCols("IP", e.ip.ifBlank { e.newValue.takeIf { it.contains(".") || it.contains(":") } ?: "在线" }, "信号", listOf(e.rssi, e.band, e.rxrate).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "-" })
                }
                isOffline -> {
                    TwoCols("状态", "已断开", "设备", e.name)
                    if (e.oldValue.isNotBlank()) InfoRow("在线时长", e.onlineDurationText.ifBlank { e.oldValue.takeIf { it.contains("时") || it.contains("分") } ?: "-" })
                }
                else -> {
                    InfoRow("名称", e.name)
                    InfoRow("新值", e.newValue, true)
                }
            }
        }
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
    var dates by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { HubApi(prefs).getDailyList() }.onSuccess { root -> dates = (root.optJSONArray("dates") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } }; selected = dates.firstOrNull().orEmpty() }
        runCatching { HubApi(prefs).getDaily(null) }.onSuccess { data = it.optJSONObject("daily") ?: it }
    }
    ExpressiveCard("日期", selected.ifBlank { "今天" }, Icons.Rounded.CalendarMonth, Color(0xFF2563EB)) {
        Box {
            PillButton("选择日期", Icons.Rounded.CalendarMonth, accent = Color(0xFF2563EB)) { expanded = true }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                dates.take(7).forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { selected = d; expanded = false; scope.launch { runCatching { HubApi(prefs).getDaily(d) }.onSuccess { data = it.optJSONObject("daily") ?: it } } }) }
            }
        }
    }
    val d = data
    if (d == null) { ExpressiveCard("总结", "暂无数据", Icons.Rounded.Notes, Color(0xFF64748B)) { Text("等待查询", fontSize = 12.sp) } } else {
        val summary = d.optJSONObject("summary") ?: JSONObject()
        ExpressiveCard("概览", d.optString("date"), Icons.Rounded.Dashboard, Color(0xFF7C3AED)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("终端", summary.optInt("deviceChanges",0).toString()+"次", Color(0xFF16A34A))
                if (summary.optInt("vpnChanges",0)>0) StatusPill("VPN", summary.optInt("vpnChanges",0).toString()+"次", Color(0xFF0EA5E9))
                if (summary.optInt("ddnsChanges",0)>0) StatusPill("DDNS", summary.optInt("ddnsChanges",0).toString()+"次", Color(0xFFF59E0B))
            }
        }
        val sections = d.optJSONObject("sections") ?: JSONObject()
        fun arr(name:String) = sections.optJSONArray(name) ?: JSONArray()
        listOf("devices" to "终端情况", "vpn" to "VPN / STUN", "network" to "网络变化", "ddns" to "DDNS 状态").forEach { (key,title) ->
            val a = arr(key)
            if (a.length() > 0) ExpressiveCard(title, "${a.length()} 条", Icons.Rounded.Notes, Color(0xFF64748B)) {
                for (i in 0 until a.length()) { val o=a.optJSONObject(i) ?: continue; Text(o.optString("text", o.toString()), fontSize=12.sp, fontWeight=FontWeight.SemiBold, maxLines=2, overflow=TextOverflow.Ellipsis) }
            }
        }
        val note = d.optString("note")
        if (note.isNotBlank()) ExpressiveCard("备注", null, Icons.Rounded.Info, Color(0xFF64748B)) { Text(note, fontSize=12.sp) }
    }
}

@Composable
fun SettingsScreen(prefs: AppPrefs, state: AppState, dark: Boolean, autoRefresh: String, onDark: (Boolean) -> Unit, onAuto: (String) -> Unit) = ScreenShell("我的", "Hub · 自动刷新 · 主题") {
    var hub by remember { mutableStateOf(prefs.hub) }
    var token by remember { mutableStateOf(prefs.token) }
    var dns by remember { mutableStateOf(prefs.hubDns) }
    var msg by remember { mutableStateOf("等待测试") }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    ExpressiveCard("连接设置", "Hub 请求优先 AAAA / IPv6，失败 3 次不清空缓存。", Icons.Rounded.Link, Color(0xFF2563EB)) {
        LabeledHistoryInput("Hub", "留空，手动填写 Hub 地址", hub, { hub = it }, "hub", prefs)
        LabeledInput("Token", "APP_TOKEN", token, { token = it })
        LabeledInput("DNS", "223.5.5.5 / system", dns, { dns = it })
        SelectInput("刷新", autoRefresh, listOf("手动", "3S", "10S", "30S")) { onAuto(it); prefs.autoRefresh = it }
        Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        PillButton("保存设置", Icons.Rounded.Save, accent = Color(0xFF2563EB)) { prefs.hub = hub; prefs.token = token; prefs.hubDns = dns; prefs.addHistory("hub", hub); state.markHubChanged(); toast(ctx, "已保存") }
        PillButton("测试连接", Icons.Rounded.WifiTethering, accent = Color(0xFF7C3AED)) { prefs.hub = hub; prefs.token = token; prefs.hubDns = dns; state.markHubChanged(); scope.launch { msg = runCatching { HubApi(prefs).health(); state.hubConnected = true; "连接成功" }.getOrElse { "失败：${it.message}" } } }
    }
    ExpressiveCard("主题", "更少大色块，蓝 / 紫 / 琥珀 / 青色分区。", Icons.Rounded.Palette, Color(0xFFF59E0B)) { PillButton(if (dark) "切换到浅色" else "切换到黑夜", Icons.Rounded.DarkMode, accent = Color(0xFFF59E0B)) { onDark(!dark) } }
    ExpressiveCard("关于", "Kotlin + Compose + Material 3 Expressive", Icons.Rounded.Info, Color(0xFF64748B)) { Text("LabProbe / 极客网探\n版本 0.7.2\n运营商识别；Hub 已连接后直接刷新；失败后再重连。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .70f), fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }
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
    suspend fun getDaily(date: String? = null): JSONObject = withContext(Dispatchers.IO) { JSONObject(getText(if (date.isNullOrBlank()) "/api/daily/latest" else "/api/daily?date=$date", true)) }
    suspend fun getDailyList(): JSONObject = withContext(Dispatchers.IO) { JSONObject(getText("/api/daily/list", true)) }

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
    val hub = prefs.hub.trim().trimEnd('/')
    val token = prefs.token.trim()
    if (hub.isNotBlank() && token.isNotBlank() && !ip.startsWith("无记录")) {
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .dns(CustomDns(prefs.hubDns))
                .build()
            val url = joinUrl(hub, "/api/geo?ip=${URLEncoder.encode(ip, "UTF-8")}")
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
    return "运营商未知"
}

suspend fun pingOnce(host: String, timeoutMs: Int): Int? = withContext(Dispatchers.IO) { runCatching { val timeoutSec = (timeoutMs / 1000).coerceAtLeast(1); val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeoutSec.toString(), host).redirectErrorStream(true).start(); val text = p.inputStream.bufferedReader().readText(); p.waitFor((timeoutSec+2).toLong(), TimeUnit.SECONDS); Regex("time[=<]([0-9.]+)").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.roundToInt() }.getOrNull() }

suspend fun tcpProbeSmart(host: String, port: Int, timeout: Int, dns1: String, dns2: String): String = withContext(Dispatchers.IO) {
    val targets = if (isIpLiteral(host)) listOf(host) else (DnsWire.query(host, dns1.ifBlank { DEFAULT_DNS1 }, 28) + DnsWire.query(host, dns2.ifBlank { DEFAULT_DNS2 }, 28) + DnsWire.query(host, dns1.ifBlank { DEFAULT_DNS1 }, 1) + DnsWire.query(host, dns2.ifBlank { DEFAULT_DNS2 }, 1)).distinct().filter { it != "127.0.0.1" }
    if (targets.isEmpty()) return@withContext "FAILED\n无法解析：$host"
    val logs = mutableListOf<String>()
    for (ip in targets) {
        val start = System.currentTimeMillis()
        try { Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeout) }; return@withContext "OPEN\n$host → $ip:$port\n耗时 ${System.currentTimeMillis()-start}ms" } catch (e: Exception) { logs += "$ip 失败：${e.javaClass.simpleName}" }
    }
    "FAILED\n$host:$port\n" + logs.joinToString("\n")
}


suspend fun udpProbeSmart(host: String, port: Int, timeout: Int, dns1: String, dns2: String): String = withContext(Dispatchers.IO) {
    val targets = if (isIpLiteral(host)) listOf(host) else (DnsWire.query(host, dns1.ifBlank { DEFAULT_DNS1 }, 28) + DnsWire.query(host, dns2.ifBlank { DEFAULT_DNS2 }, 28) + DnsWire.query(host, dns1.ifBlank { DEFAULT_DNS1 }, 1) + DnsWire.query(host, dns2.ifBlank { DEFAULT_DNS2 }, 1)).distinct().filter { it != "127.0.0.1" }
    if (targets.isEmpty()) return@withContext "FAILED\n无法解析：$host"
    val probe = byteArrayOf(0x4c, 0x61, 0x62, 0x50, 0x72, 0x6f, 0x62, 0x65)
    val logs = mutableListOf<String>()
    for (ip in targets) {
        val start = System.currentTimeMillis()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeout
                val addr = InetAddress.getByName(ip)
                socket.connect(addr, port)
                socket.send(DatagramPacket(probe, probe.size, addr, port))
                val buf = ByteArray(512)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                return@withContext "UDP RESPONSE\n$host → $ip:$port\n耗时 ${System.currentTimeMillis()-start}ms\n说明：收到 UDP 响应，端口大概率开放。"
            }
        } catch (e: java.net.PortUnreachableException) {
            return@withContext "UDP CLOSED\n$host → $ip:$port\n收到 ICMP Port Unreachable"
        } catch (e: java.net.SocketTimeoutException) {
            logs += "$ip 无响应：OPEN|FILTERED"
        } catch (e: Exception) {
            logs += "$ip 失败：${e.javaClass.simpleName}"
        }
    }
    "UDP NO RESPONSE\n$host:$port\n" + logs.joinToString("\n") + "\n说明：UDP 无响应不代表端口关闭，可能开放或被防火墙过滤。"
}

fun isIpLiteral(s: String): Boolean = s.contains(":") || Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(s)

suspend fun sshExec(host: String, port: Int, user: String, pass: String, cmd: String): String = withContext(Dispatchers.IO) {
    val session = JSch().getSession(user, host, port); session.setPassword(pass)
    val cfg = java.util.Properties(); cfg["StrictHostKeyChecking"]="no"; cfg["PreferredAuthentications"]="password,keyboard-interactive,publickey"; cfg["server_host_key"]="ssh-rsa,rsa-sha2-256,rsa-sha2-512,ssh-ed25519,ecdsa-sha2-nistp256"; cfg["PubkeyAcceptedAlgorithms"]="+ssh-rsa,rsa-sha2-256,rsa-sha2-512"; cfg["kex"]="curve25519-sha256@libssh.org,curve25519-sha256,ecdh-sha2-nistp256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1"; cfg["cipher.s2c"]="aes256-ctr,aes128-ctr,aes192-ctr,aes128-cbc,3des-cbc"; cfg["cipher.c2s"]="aes256-ctr,aes128-ctr,aes192-ctr,aes128-cbc,3des-cbc"; cfg["mac.s2c"]="hmac-sha2-256,hmac-sha2-512,hmac-sha1"; cfg["mac.c2s"]="hmac-sha2-256,hmac-sha2-512,hmac-sha1"; cfg["enable_server_sig_algs"]="yes"; session.setConfig(cfg)
    session.userInfo = object: UserInfo, UIKeyboardInteractive { override fun getPassphrase(): String?=null; override fun getPassword(): String=pass; override fun promptPassword(message:String?)=true; override fun promptPassphrase(message:String?)=false; override fun promptYesNo(message:String?)=true; override fun showMessage(message:String?){}; override fun promptKeyboardInteractive(destination:String?, name:String?, instruction:String?, prompt:Array<out String>?, echo:BooleanArray?): Array<String> = Array(prompt?.size ?: 0) { pass } }
    session.connect(10000); val ch = session.openChannel("exec") as ChannelExec; ch.setCommand(cmd); val err=ByteArrayOutputStream(); ch.setErrStream(err); val input=ch.inputStream; ch.connect(10000); val out=input.bufferedReader().readText(); val errText=err.toString().trim(); val exit=ch.exitStatus; ch.disconnect(); session.disconnect(); buildString { val hasOut = out.isNotBlank(); val title = when { exit == 0 -> "执行成功"; exit == -1 && hasOut -> "执行完成 · 未获取退出码"; exit != 0 && hasOut -> "执行完成 · exit $exit"; else -> "执行失败 · exit $exit" }; append(title); append("\n"); append(out.ifBlank { "无输出" }); if(errText.isNotBlank()) append("\nERR: ").append(errText); if (exit != 0 && !hasOut) append("\n返回码：").append(exit) }
}

fun parseDeviceArray(json: String): List<DeviceItem> { if (json.isBlank()) return emptyList(); val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }; return (0 until arr.length()).mapNotNull { parseDevice(arr.optJSONObject(it)) } }
fun parseEvents(json: String): List<EventItem> { if (json.isBlank()) return emptyList(); val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }; val out= mutableListOf<EventItem>(); for(i in 0 until arr.length()){ val o=arr.optJSONObject(i) ?: continue; if (o.optBoolean("deleted", false)) continue; val type=o.optString("type"); val nv=o.optString("newValue"); if(type=="lucky_webhook"&&(nv.contains("token",true)||nv.length<10)) continue; out+=EventItem(o.optInt("id",0), o.optString("title", type.ifBlank{"事件"}), type, o.optString("name"), o.optString("oldValue","-"), maskSensitive(nv.ifBlank{o.optString("value","-")}), o.optString("createdAt", o.optString("time")), o.optString("ip"), o.optString("rssi"), o.optString("band"), o.optString("rxrate"), o.optString("onlineDurationText")) }; return out }
fun parseDevice(o: JSONObject?): DeviceItem? { if (o==null) return null; val mac=o.optString("mac"); val name=o.optString("name").ifBlank{o.optString("devRecommend")}.ifBlank{o.optString("hostName")}.ifBlank{mac}; return DeviceItem(name, mac, o.optBoolean("online", true), o.optString("ip").ifBlank{o.optString("userIp")}, o.optString("ssid"), o.optString("band"), o.optString("rssi"), o.optString("rxrate"), o.optString("onlineSince").ifBlank{o.optString("onlinetime")}, o.optString("offlineAt"), o.optString("onlineDurationText"), o.optString("lastSeenAt")) }
fun DeviceItem.toJson(): JSONObject = JSONObject().put("name",name).put("mac",mac).put("online",online).put("ip",ip).put("ssid",ssid).put("band",band).put("rssi",rssi).put("rxrate",rxrate).put("onlineSince",onlineSince).put("offlineAt",offlineAt).put("onlineDurationText",onlineDurationText).put("lastSeenAt",lastSeenAt)
fun EventItem.toJson(): JSONObject = JSONObject().put("id",id).put("title",title).put("type",type).put("name",name).put("oldValue",oldValue).put("newValue",newValue).put("createdAt",time).put("ip",ip).put("rssi",rssi).put("band",band).put("rxrate",rxrate).put("onlineDurationText",onlineDurationText)
fun joinUrl(base: String, path: String): String { val b=base.trim().trimEnd('/'); return if(path.startsWith("/")) b+path else "$b/$path" }
fun maskSensitive(s: String): String = s.replace(Regex("(?i)(token|password|secret)[^,}]*"), "$1:***")
fun nowClock(): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
fun copy(ctx: Context, text: String) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("LabProbe", text)); toast(ctx, "已复制") }
