package com.labprobe.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

internal data class HttpsCertificateInfo(
    val domains: List<String>,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val fingerprint: String,
    val source: String,
)

internal data class HttpsServiceStatus(
    val enabled: Boolean,
    val running: Boolean,
    val supported: Boolean,
    val port: Int,
    val domain: String,
    val source: String,
    val localCertPath: String,
    val localKeyPath: String,
    val certificate: HttpsCertificateInfo?,
    val publicUrl: String,
    val realtimeUrl: String,
    val mqttUrl: String,
    val adminTokenRequired: Boolean,
    val lastError: String,
)

internal fun parseHttpsStatus(root: JSONObject): HttpsServiceStatus {
    val cert = root.optJSONObject("certificate")?.takeIf { it.has("fingerprint") }
    val certificate = cert?.let {
        HttpsCertificateInfo(
            domains = buildList {
                val values = it.optJSONArray("domains") ?: org.json.JSONArray()
                for (index in 0 until values.length()) add(values.optString(index))
            }.distinct(),
            subject = it.optString("subject"),
            issuer = it.optString("issuer"),
            notBefore = it.optLong("notBefore", 0L),
            notAfter = it.optLong("notAfter", 0L),
            fingerprint = it.optString("fingerprint"),
            source = it.optString("source").ifBlank { root.optString("source") },
        )
    }
    return HttpsServiceStatus(
        enabled = root.optBoolean("enabled"),
        running = root.optBoolean("running"),
        supported = root.optBoolean("supported", true),
        port = root.optInt("port", 58445),
        domain = root.optString("domain"),
        source = root.optString("source", "manual").ifBlank { "manual" },
        localCertPath = root.optString("localCertPath"),
        localKeyPath = root.optString("localKeyPath"),
        certificate = certificate,
        publicUrl = root.optString("publicUrl"),
        realtimeUrl = root.optString("realtimeUrl"),
        mqttUrl = root.optString("mqttUrl"),
        adminTokenRequired = root.optBoolean("adminTokenRequired"),
        lastError = root.optString("lastError"),
    )
}

internal fun hubHttpsUsesEncryptedTransport(rawHub: String): Boolean =
    normalizeHubBaseUrl(rawHub).startsWith("https://", ignoreCase = true)

internal fun hubHttpsCertificateRecordId(prefs: AppPrefs, domain: String, port: Int): String? {
    if (domain.isBlank() || port !in 1..65535) return null
    val key = "${prefs.workspaceId}|${domain.lowercase(Locale.ROOT)}|$port"
    return "hub-https-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)
}

internal fun hubHttpsCertificateExpiryItem(prefs: AppPrefs, status: HttpsServiceStatus): CertificateExpiryItem? {
    val certificate = status.certificate ?: return null
    val id = hubHttpsCertificateRecordId(prefs, status.domain, status.port) ?: return null
    if (certificate.notAfter <= 0L) return null
    fun date(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault()).toLocalDate().toString()
    return CertificateExpiryItem(
        id = id,
        note = "Hub HTTPS",
        domain = status.domain,
        organization = certificate.issuer.ifBlank { certificate.subject },
        provider = "Hub 内置 HTTPS",
        appliedOn = if (certificate.notBefore > 0L) date(certificate.notBefore) else "",
        expiresOn = date(certificate.notAfter),
        url = status.publicUrl.ifBlank { "https://${status.domain}:${status.port}" },
        owner = "hub_https",
    )
}

@Composable
fun HubHttpsCertificateScreen(prefs: AppPrefs, onBack: () -> Unit) = DetailShell(
    title = "HTTPS 与证书",
    subtitle = "Hub 安全接入",
    onBack = onBack,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(prefs.hub, prefs.token, prefs.hubDns) { HubApi(prefs) }
    var status by remember { mutableStateOf<HttpsServiceStatus?>(null) }
    var port by remember { mutableStateOf("58445") }
    var domain by remember { mutableStateOf("") }
    var localCertPath by remember { mutableStateOf("") }
    var localKeyPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<CertificateFileSelection?>(null) }
    var adminToken by remember { mutableStateOf(prefs.httpsAdminToken) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val encryptedTransport = hubHttpsUsesEncryptedTransport(prefs.hub)

    fun applyStatus(next: HttpsServiceStatus) {
        status = next
        if (!loaded) {
            port = next.port.toString()
            domain = next.domain
            localCertPath = next.localCertPath
            localKeyPath = next.localKeyPath
            loaded = true
        }
        hubHttpsCertificateRecordId(prefs, next.domain, next.port)?.let { recordId ->
            updateHubHttpsCertificateRecord(context, prefs, recordId, hubHttpsCertificateExpiryItem(prefs, next))
        }
    }

    suspend fun loadStatus() {
        applyStatus(parseHttpsStatus(api.getHttpsStatus()))
    }

    LaunchedEffect(api) {
        loaded = false
        status = null
        busy = true
        try {
            loadStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "暂时无法读取 Hub HTTPS 状态"
        } finally {
            busy = false
        }
    }

    suspend fun runAction(label: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = label
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message = "${label}失败：${uiMessageZh(error.message.orEmpty())}"
        } finally {
            busy = false
        }
    }

    fun configBody(): JSONObject = JSONObject()
        .put("port", port.toIntOrNull() ?: 58445)
        .put("domain", domain.trim())
        .put("localCertPath", localCertPath.trim())
        .put("localKeyPath", localKeyPath.trim())

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExpressiveCard(
            title = "HTTPS 服务",
            subtitle = when {
                status?.running == true -> "运行中"
                status?.enabled == true -> "未就绪"
                status == null -> "读取中"
                else -> "已关闭"
            },
            icon = Icons.Rounded.Lock,
            accent = Color(0xFF0F766E),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("启用 HTTPS", style = LabTypography.CardTitle)
                    Text("不影响原有 HTTP 58443", style = LabTypography.Caption, color = LabV2.InkMuted)
                }
                Switch(
                    checked = status?.enabled == true,
                    enabled = !busy && status?.supported == true,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            runAction(if (enabled) "正在启用 HTTPS" else "正在关闭 HTTPS") {
                                if (enabled) applyStatus(parseHttpsStatus(api.saveHttpsConfig(configBody())))
                                applyStatus(parseHttpsStatus(api.setHttpsEnabled(enabled)))
                                message = if (enabled) "HTTPS 已启用" else "HTTPS 已关闭"
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF0F766E)),
                )
            }
            if (status?.supported == false) {
                Text("当前 Hub 暂不支持 HTTPS 组件", style = LabTypography.Supporting, color = LabV2.Amber)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(.45f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("监听端口", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
                    CompactTextField(
                        port,
                        { port = it.filter(Char::isDigit).take(5) },
                        Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("访问域名", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
                    CompactTextField(domain, { domain = it }, Modifier.fillMaxWidth(), placeholder = "hub.example.com")
                }
            }
            PillButton("保存配置", enabled = !busy && domain.isNotBlank()) {
                scope.launch {
                    runAction("正在保存 HTTPS 配置") {
                        applyStatus(parseHttpsStatus(api.saveHttpsConfig(configBody())))
                        message = "HTTPS 配置已保存"
                    }
                }
            }
        }

        ExpressiveCard(
            title = "SSL 证书",
            subtitle = status?.certificate?.let { httpsCertificateStatus(it) } ?: "未导入",
            icon = Icons.Rounded.Badge,
            accent = LabV2.Cyan,
        ) {
            status?.certificate?.let { certificate ->
                HttpsCertificateDetail("域名", certificate.domains.joinToString(", ").ifBlank { "--" })
                HttpsCertificateDetail("签发机构", certificate.issuer.ifBlank { certificate.subject.ifBlank { "--" } })
                HttpsCertificateDetail("签发时间", formatHttpsDate(certificate.notBefore))
                HttpsCertificateDetail("有效期至", formatHttpsDate(certificate.notAfter))
                HttpsCertificateDetail("来源", if (certificate.source == "local") "NAS 本地文件" else "APP 安全导入")
                PillButton("删除证书", enabled = !busy) { confirmDelete = true }
            }

            Text("导入或更新", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
            Text(
                if (encryptedTransport) "私钥仅通过当前 HTTPS 连接上传。" else "内网 HTTP 下请使用 NAS 本地证书文件。",
                style = LabTypography.Caption,
                color = if (encryptedTransport) LabV2.InkMuted else LabV2.Amber,
            )
            CertificateFileSelector(files, { files = it }, enabled = !busy && encryptedTransport)
            PillButton(
                "导入或更新证书",
                enabled = !busy && encryptedTransport && domain.isNotBlank() && files?.certificate != null && files?.privateKey != null,
            ) {
                scope.launch {
                    runAction("正在导入证书") {
                        val selected = requireNotNull(files)
                        val body = certificateImportBody(context, selected).put("domain", domain.trim())
                        applyStatus(parseHttpsStatus(api.importHttpsManual(body)))
                        files = null
                        message = "证书已更新"
                    }
                }
            }

            Text("NAS 本地证书文件", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
            CompactTextField(localCertPath, { localCertPath = it }, Modifier.fillMaxWidth(), placeholder = "fullchain.pem")
            CompactTextField(localKeyPath, { localKeyPath = it }, Modifier.fillMaxWidth(), placeholder = "privkey.pem")
            PillButton("导入本地证书", enabled = !busy && domain.isNotBlank() && localCertPath.isNotBlank() && localKeyPath.isNotBlank()) {
                scope.launch {
                    runAction("正在读取本地证书") {
                        applyStatus(parseHttpsStatus(api.importHttpsLocal(localCertPath.trim(), localKeyPath.trim(), domain.trim())))
                        message = "本地证书已导入"
                    }
                }
            }
        }

        if (status?.adminTokenRequired == true) {
            SettingsPasswordField(
                "HTTPS 管理员令牌",
                adminToken,
                {
                    adminToken = it
                    prefs.httpsAdminToken = it
                },
                placeholder = "与 Hub HTTPS_ADMIN_TOKEN 一致",
            )
        }
        if (!status?.publicUrl.isNullOrBlank()) {
            HttpsCertificateDetail("访问地址", status?.publicUrl.orEmpty())
        }
        if (!status?.lastError.isNullOrBlank()) {
            Text("HTTPS 服务：${status?.lastError}", style = LabTypography.Caption, color = LabV2.Red)
        }
        PillButton("刷新状态", enabled = !busy) {
            scope.launch {
                runAction("正在刷新 HTTPS") {
                    loadStatus()
                    message = "HTTPS 状态已刷新"
                }
            }
        }
        if (busy || message.isNotBlank()) {
            Text(if (busy) "$message…" else message, style = LabTypography.Caption, color = LabV2.InkMuted)
        }
    }

    if (confirmDelete) {
        LabCompactAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 Hub HTTPS 证书") },
            text = { Text("将关闭 HTTPS 并删除 Hub 保存的证书及私钥，不影响 HTTP 58443，也不会删除 NAS 上的原文件。") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runAction("正在删除证书") {
                            applyStatus(parseHttpsStatus(api.deleteHttpsCertificate()))
                            files = null
                            message = "证书已删除"
                        }
                    }
                }, enabled = !busy) { Text("删除", color = LabV2.Red) }
            },
        )
    }
}

@Composable
private fun HttpsCertificateDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 12.dp), style = LabTypography.Caption, color = LabV2.InkMuted)
        Text(value, Modifier.weight(1f), style = LabTypography.Caption, color = LabV2.Ink)
    }
}

private fun httpsCertificateStatus(certificate: HttpsCertificateInfo): String = when {
    certificate.notAfter <= System.currentTimeMillis() / 1000L -> "已过期"
    certificate.notAfter - System.currentTimeMillis() / 1000L <= 7L * 86_400 -> "即将到期"
    else -> "已导入"
}

private fun formatHttpsDate(epochSeconds: Long): String =
    if (epochSeconds <= 0L) "--" else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochSeconds * 1000))
