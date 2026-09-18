package com.labprobe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val RDPI_BASE = "/api/router/rdpi"

data class RdpiSignatureSummary(
    val totalCount: Int = 378,
    val officialCount: Int = 378,
    val customCount: Int = 0,
    val customSignatures: List<RdpiCustomApp> = emptyList(),
    val templateJson: String = ""
)

data class RdpiCustomApp(
    val index: String,
    val name: String,
    val rules: List<RdpiCustomRule>
)

data class RdpiCustomRule(
    val protocol: String,
    val hosts: List<String>,
    val payloads: List<String>
)

private val DEFAULT_FALLBACK_TEMPLATE = """
{
  "${'$'}schema": "labprobe-rdpi-v1",
  "comment": "锐捷/Reyee RDPI 自定义应用特征包标准格式，适用于儿童上网与流量审计",
  "app": {
    "index": "999-1-1-0",
    "name": "自定义网游/新APP",
    "category": "game",
    "description": "基于 Wireshark/tcpdump 抓包提取的域名和握手特征",
    "rules": [
      {
        "protocol": "tcp",
        "hosts": [
          "*.customgame.com",
          "login.customgame.cn"
        ],
        "payloads": [
          {
            "pos": 0,
            "length": 4,
            "payload": "47 41 4d 45"
          }
        ]
      },
      {
        "protocol": "udp",
        "hosts": [],
        "payloads": [
          {
            "pos": 0,
            "length": 2,
            "payload": "ff ff"
          }
        ]
      }
    ]
  }
}
""".trimIndent()

/**
 * Hub API calls for RDPI Signature Studio.
 */
internal suspend fun fetchRdpiSummary(prefs: AppPrefs, routerId: String = "default"): Result<RdpiSignatureSummary> = withContext(Dispatchers.IO) {
    runCatching {
        val hub = HubApi(prefs)
        val query = if (routerId.isNotBlank()) "?router=$routerId" else ""
        val json = hub.requestJson("$RDPI_BASE/signatures$query")
        val total = json.optInt("totalCount", 378)
        val official = json.optInt("officialCount", 378)
        val custom = json.optInt("customCount", 0)
        val customApps = mutableListOf<RdpiCustomApp>()
        val arr = json.optJSONArray("customSignatures") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val idx = item.optString("index")
            val name = item.optString("name")
            val rulesArr = item.optJSONArray("rules") ?: JSONArray()
            val rules = mutableListOf<RdpiCustomRule>()
            for (j in 0 until rulesArr.length()) {
                val r = rulesArr.optJSONObject(j) ?: continue
                val proto = r.optString("protocol", "any")
                val hosts = mutableListOf<String>()
                val hArr = r.optJSONArray("hosts") ?: JSONArray()
                for (k in 0 until hArr.length()) hosts.add(hArr.optString(k))
                val payloads = mutableListOf<String>()
                val pArr = r.optJSONArray("payloads") ?: JSONArray()
                for (k in 0 until pArr.length()) {
                    val p = pArr.optJSONObject(k) ?: continue
                    payloads.add("pos=${p.optInt("pos")}: ${p.optString("payload")}")
                }
                rules.add(RdpiCustomRule(proto, hosts, payloads))
            }
            customApps.add(RdpiCustomApp(idx, name, rules))
        }
        val templateObj = json.optJSONObject("template")
        val templateStr = templateObj?.toString(2) ?: DEFAULT_FALLBACK_TEMPLATE
        RdpiSignatureSummary(total, official, custom, customApps, templateStr)
    }
}

internal suspend fun uploadRdpiCustomSignature(prefs: AppPrefs, signatureJson: String, routerId: String = "default"): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val hub = HubApi(prefs)
        val query = if (routerId.isNotBlank()) "?router=$routerId" else ""
        val parsed = JSONObject(signatureJson)
        val res = hub.requestJson("$RDPI_BASE/signatures$query", "POST", parsed)
        if (res.optBoolean("ok", false)) {
            val msg = res.optString("message", "特征已注入并热重载")
            val name = res.optString("name", "自定义应用")
            "$name: $msg"
        } else {
            throw IllegalStateException(res.optString("error", "注入失败"))
        }
    }
}

internal suspend fun deleteRdpiCustomSignature(prefs: AppPrefs, targetIndex: String, routerId: String = "default"): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val hub = HubApi(prefs)
        val query = if (routerId.isNotBlank()) "?router=$routerId" else ""
        val res = hub.requestJson("$RDPI_BASE/signatures/$targetIndex$query", "DELETE")
        if (res.optBoolean("ok", false)) {
            res.optString("message", "已删除并热重载")
        } else {
            throw IllegalStateException(res.optString("error", "删除失败"))
        }
    }
}

data class ChildGuardIp6Status(
    val enabled: Boolean = false,
    val count: Int = 0,
    val activeMembers: List<String> = emptyList()
)

internal suspend fun fetchChildGuardIp6Status(prefs: AppPrefs): Result<ChildGuardIp6Status> = withContext(Dispatchers.IO) {
    runCatching {
        val hub = HubApi(prefs)
        val res = hub.requestJson("/api/router/child-guard/ip6-audit/status")
        val enabled = res.optBoolean("enabled", false)
        val count = res.optInt("count", 0)
        val arr = res.optJSONArray("activeMembers") ?: JSONArray()
        val members = mutableListOf<String>()
        for (i in 0 until arr.length()) members.add(arr.optString(i))
        ChildGuardIp6Status(enabled, count, members)
    }
}

internal suspend fun syncChildGuardIp6(prefs: AppPrefs): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val hub = HubApi(prefs)
        val res = hub.requestJson("/api/router/child-guard/ip6-audit/sync", "POST", JSONObject())
        if (res.optBoolean("ok", false)) {
            res.optString("message", "IPv6 降级审计已同步")
        } else {
            throw IllegalStateException(res.optString("error", "同步失败"))
        }
    }
}

internal suspend fun applyCuratedRdpiBundle(prefs: AppPrefs, routerId: String = "default"): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val hub = HubApi(prefs)
        val query = if (routerId.isNotBlank()) "?router=$routerId" else ""
        val res = hub.requestJson("$RDPI_BASE/signatures/bundle$query", "POST", JSONObject())
        if (res.optBoolean("ok", false)) {
            val count = res.optInt("totalHostsAdded", 0)
            val msg = res.optString("message", "已热重载")
            if (count > 0) "已注入 $count 个高频应用新特征并热重载！" else "高频特征包已是最新状态 ($msg)"
        } else {
            throw IllegalStateException(res.optString("error", "注入失败"))
        }
    }
}


@Composable
fun RdpiSignatureCard(
    prefs: AppPrefs,
    modifier: Modifier = Modifier,
    routerId: String = "default"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<RdpiSignatureSummary?>(null) }
    var ip6Status by remember { mutableStateOf<ChildGuardIp6Status?>(null) }
    var loading by remember { mutableStateOf(false) }
    var bundleLoading by remember { mutableStateOf(false) }

    var showTemplateDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showRulesDialog by rememberSaveable { mutableStateOf(false) }

    fun refreshSummary() {
        loading = true
        scope.launch {
            fetchRdpiSummary(prefs, routerId).onSuccess {
                summary = it
                loading = false
            }.onFailure {
                loading = false
            }
        }
        scope.launch {
            fetchChildGuardIp6Status(prefs).onSuccess {
                ip6Status = it
            }
        }
    }

    LaunchedEffect(routerId) {
        refreshSummary()
    }

    if (showTemplateDialog) {
        RdpiTemplateDialog(
            templateJson = summary?.templateJson?.ifBlank { DEFAULT_FALLBACK_TEMPLATE } ?: DEFAULT_FALLBACK_TEMPLATE,
            onDismiss = { showTemplateDialog = false },
            onCopy = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clip?.setPrimaryClip(ClipData.newPlainText("RDPI Signature Template", it))
                Toast.makeText(context, "特征模板已复制到剪贴板", Toast.LENGTH_SHORT).show()
                showTemplateDialog = false
            }
        )
    }

    if (showImportDialog) {
        RdpiImportDialog(
            templateJson = summary?.templateJson?.ifBlank { DEFAULT_FALLBACK_TEMPLATE } ?: DEFAULT_FALLBACK_TEMPLATE,
            onDismiss = { showImportDialog = false },
            onApply = { jsonStr ->
                scope.launch {
                    uploadRdpiCustomSignature(prefs, jsonStr, routerId).onSuccess {
                        Toast.makeText(context, "注入成功！$it", Toast.LENGTH_LONG).show()
                        showImportDialog = false
                        refreshSummary()
                    }.onFailure {
                        Toast.makeText(context, "注入失败: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showRulesDialog && summary != null) {
        RdpiCustomRulesDialog(
            customApps = summary!!.customSignatures,
            onDismiss = { showRulesDialog = false },
            onDelete = { app ->
                scope.launch {
                    deleteRdpiCustomSignature(prefs, app.index, routerId).onSuccess {
                        Toast.makeText(context, "已移除规则 ${app.name}", Toast.LENGTH_SHORT).show()
                        refreshSummary()
                    }.onFailure {
                        Toast.makeText(context, "删除失败: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, LabV2.Border),
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header Row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = LabV2.Cyan.copy(alpha = 0.12f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Fingerprint, null, tint = LabV2.Cyan, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("应用特征库 (DPI 识别)", style = LabTypography.CardTitle)
                        if (loading) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = LabV2.Cyan)
                        }
                    }
                    Text("内核七层流量审计 · 抓包特征扩展与热重载", style = LabTypography.Supporting.copy(color = LabV2.InkMuted))
                }
            }

            // Stat Badges
            val total = summary?.totalCount ?: 379
            val custom = summary?.customCount ?: 0
            val ip6Count = ip6Status?.count ?: 0
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RdpiStatBadge("官方特征", "$total 款", LabV2.Cyan)
                RdpiStatBadge("IPv6 降级审计", if (ip6Count > 0) "${ip6Count} 台守护" else "已激活", LabV2.Green)
                RdpiStatBadge("自定义扩展", "$custom 款", if (custom > 0) LabV2.Primary else LabV2.InkMuted)
                RdpiStatBadge("热重载引擎", "就绪", LabV2.Green)
            }

            // Curated Bundle Banner
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFF0FDF4),
                border = BorderStroke(1.dp, Color(0xFFDCFCE7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF22C55E).copy(alpha = 0.15f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Bolt, null, tint = Color(0xFF16A34A), modifier = Modifier.size(18.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("常用 App 特征增强补丁", style = LabTypography.CardTitle.copy(fontSize = 13.5.sp))
                        Text("微信视频号/抖音/快手/拼多多/京东/淘宝 CDN 补全", style = LabTypography.Caption.copy(color = LabV2.InkMuted, fontSize = 11.sp))
                    }
                    Button(
                        onClick = {
                            bundleLoading = true
                            scope.launch {
                                applyCuratedRdpiBundle(prefs, routerId).onSuccess { msg ->
                                    bundleLoading = false
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    refreshSummary()
                                }.onFailure { err ->
                                    bundleLoading = false
                                    Toast.makeText(context, "注入失败: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !bundleLoading,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        if (bundleLoading) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp), color = Color.White)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("一键增强", style = LabTypography.Caption.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp))
                    }
                }
            }

            Divider(color = LabV2.Border.copy(alpha = 0.5f), thickness = 0.8.dp)

            // Action Buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showTemplateDialog = true },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LabV2.FieldSoft, contentColor = LabV2.Ink)
                ) {
                    Icon(Icons.Rounded.Code, null, modifier = Modifier.size(16.dp), tint = LabV2.Primary)
                    Spacer(Modifier.width(5.dp))
                    Text("获取模板", style = LabTypography.Caption.copy(fontWeight = FontWeight.SemiBold))
                }

                Button(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.weight(1.2f).height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan, contentColor = Color.White)
                ) {
                    Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("上传/导入特征", style = LabTypography.Caption.copy(fontWeight = FontWeight.Bold))
                }

                if (custom > 0) {
                    IconButton(
                        onClick = { showRulesDialog = true },
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(LabV2.FieldSoft)
                    ) {
                        Icon(Icons.Rounded.Tune, "管理规则", tint = LabV2.Ink)
                    }
                }
            }
        }
    }

}

@Composable
private fun RdpiStatBadge(title: String, value: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Text(title, style = LabTypography.Caption.copy(color = LabV2.InkMuted))
            Text(value, style = LabTypography.Caption.copy(fontWeight = FontWeight.Bold, color = accent))
        }
    }
}

@Composable
private fun RdpiTemplateDialog(
    templateJson: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.DataObject, null, tint = LabV2.Cyan)
                Text("锐捷 RDPI 标准特征模板", style = LabTypography.CardTitle)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "抓包 (Wireshark / tcpdump) 后提取域名、SNI 或握手包 Hex，套用本模板即可直接导入路由器：",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted)
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        templateJson,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCopy(templateJson) },
                colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)
            ) {
                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("一键复制模板")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = LabCoreSurface.Card
    )
}

@Composable
private fun RdpiImportDialog(
    templateJson: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var inputJson by rememberSaveable { mutableStateOf(templateJson) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var parsedName by remember { mutableStateOf<String?>(null) }
    var parsedIndex by remember { mutableStateOf<String?>(null) }
    var parsedRulesCount by remember { mutableStateOf(0) }

    // Live validation
    LaunchedEffect(inputJson) {
        try {
            val root = JSONObject(inputJson.trim())
            val app = if (root.has("app") && root.optJSONObject("app") != null) root.getJSONObject("app") else root
            val idx = app.optString("index")
            val name = app.optString("name")
            val rules = app.optJSONArray("rules")
            if (idx.isBlank() || !idx.matches(Regex("""^\d+-\d+-\d+-\d+$"""))) {
                parseError = "索引格式错误，必须为 X-X-X-X (如 999-1-1-0)"
                parsedName = null
            } else if (name.isBlank()) {
                parseError = "应用名称 (name) 不能为空"
                parsedName = null
            } else if (rules == null || rules.length() == 0) {
                parseError = "rules 必须至少包含一条规则"
                parsedName = null
            } else {
                parseError = null
                parsedName = name
                parsedIndex = idx
                parsedRulesCount = rules.length()
            }
        } catch (e: Exception) {
            parseError = "JSON 语法解析错误: ${e.localizedMessage}"
            parsedName = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.UploadFile, null, tint = LabV2.Cyan)
                Text("导入自定义特征规则", style = LabTypography.CardTitle)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "粘贴抓包解包生成的标准 JSON，系统将自动校验并备份注入路由器：",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = inputJson,
                    onValueChange = { inputJson = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = LabV2.Ink
                    ),
                    placeholder = { Text("在此粘贴 JSON 特征规则...") }
                )
                Spacer(Modifier.height(8.dp))

                // Validation Status Card
                if (parseError != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = LabV2.Red.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = LabV2.Red, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(parseError!!, style = LabTypography.Caption.copy(color = LabV2.Red))
                        }
                    }
                } else if (parsedName != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = LabV2.Green.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = LabV2.Green, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("校验通过: [$parsedIndex] $parsedName (包含 $parsedRulesCount 条匹配规则)", style = LabTypography.Caption.copy(color = LabV2.Green, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(inputJson) },
                enabled = parseError == null && parsedName != null,
                colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)
            ) {
                Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("注入并热重载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = LabCoreSurface.Card
    )
}

@Composable
private fun RdpiCustomRulesDialog(
    customApps: List<RdpiCustomApp>,
    onDismiss: () -> Unit,
    onDelete: (RdpiCustomApp) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.ListAlt, null, tint = LabV2.Primary)
                Text("已注入自定义特征 (${customApps.size})", style = LabTypography.CardTitle)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (customApps.isEmpty()) {
                    Text("暂无自定义规则", style = LabTypography.Body.copy(color = LabV2.InkMuted))
                } else {
                    customApps.forEach { app ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = LabV2.FieldSoft,
                            border = BorderStroke(1.dp, LabV2.Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.name, style = LabTypography.CardTitle.copy(fontSize = 15.sp))
                                        Text("Index: ${app.index}", style = LabTypography.Caption.copy(color = LabV2.InkMuted))
                                    }
                                    IconButton(onClick = { onDelete(app) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.DeleteOutline, "删除", tint = LabV2.Red)
                                    }
                                }
                                app.rules.forEach { r ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("[${r.protocol.uppercase()}]", style = LabTypography.Caption.copy(fontWeight = FontWeight.Bold, color = LabV2.Cyan))
                                        val desc = when {
                                            r.hosts.isNotEmpty() -> "域名: ${r.hosts.joinToString()}"
                                            r.payloads.isNotEmpty() -> "载荷: ${r.payloads.joinToString()}"
                                            else -> "通用规则"
                                        }
                                        Text(desc, style = LabTypography.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = LabCoreSurface.Card
    )
}

@Composable
fun RdpiSignatureStudioScreen(prefs: AppPrefs, onBack: () -> Unit) {
    DetailShell(
        title = "应用特征库 (DPI 识别)",
        subtitle = "内核七层流量审计 · 抓包特征扩展与热重载",
        onBack = onBack
    ) {
        RdpiSignatureCard(prefs)
    }
}
