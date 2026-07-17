package com.labprobe.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class CertificateExpiryItem(
    val id: String,
    val note: String,
    val domain: String,
    val organization: String,
    val provider: String,
    val appliedOn: String,
    val expiresOn: String
)

private const val CERTIFICATE_CHANNEL_ID = "labprobe_certificate_expiry_v1"

fun AppPrefs.certificates(): List<CertificateExpiryItem> {
    val array = runCatching { JSONArray(certificateExpiryJson) }.getOrElse { JSONArray() }
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
        val domain = item.optString("domain").trim()
        val expiresOn = normalizeCertificateDate(item.optString("expiresOn"))
        if (domain.isBlank() || expiresOn.isBlank()) return@mapNotNull null
        CertificateExpiryItem(
            id = id,
            note = item.optString("note").trim(),
            domain = domain,
            organization = item.optString("organization").trim(),
            provider = item.optString("provider").trim(),
            appliedOn = normalizeCertificateDate(item.optString("appliedOn")),
            expiresOn = expiresOn
        )
    }.sortedBy { parseCertificateDate(it.expiresOn) ?: LocalDate.MAX }
}

fun AppPrefs.saveCertificates(items: List<CertificateExpiryItem>) {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("note", item.note.trim())
                .put("domain", item.domain.trim())
                .put("organization", item.organization.trim())
                .put("provider", item.provider.trim())
                .put("appliedOn", normalizeCertificateDate(item.appliedOn))
                .put("expiresOn", normalizeCertificateDate(item.expiresOn))
        )
    }
    certificateExpiryJson = array.toString()
}

private fun AppPrefs.certificateReminderKeys(): Set<String> {
    val array = runCatching { JSONArray(certificateReminderKeysJson) }.getOrElse { JSONArray() }
    return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.toSet()
}

private fun AppPrefs.markCertificateReminderSent(key: String) {
    certificateReminderKeysJson = JSONArray((certificateReminderKeys() + key).sorted()).toString()
}

fun AppPrefs.clearCertificateReminderHistory(id: String) {
    certificateReminderKeysJson = JSONArray(certificateReminderKeys().filterNot { it.startsWith("$id:") }.sorted()).toString()
}

private fun parseCertificateDate(raw: String): LocalDate? {
    val normalized = raw.trim().take(10).replace('/', '-').replace('.', '-')
    return runCatching { LocalDate.parse(normalized) }.getOrNull()
}

private fun normalizeCertificateDate(raw: String): String = parseCertificateDate(raw)?.toString().orEmpty()

private fun CertificateExpiryItem.remainingDays(today: LocalDate = LocalDate.now()): Long? =
    parseCertificateDate(expiresOn)?.let { ChronoUnit.DAYS.between(today, it) }

private fun reminderMilestone(remaining: Long): Int? = when (remaining) {
    in 4L..7L -> 7
    in 1L..3L -> 3
    0L -> 0
    else -> null
}

object CertificateReminderCenter {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CERTIFICATE_CHANNEL_ID, "证书到期提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "打开或刷新 APP 时，在证书到期前 7 天、3 天和当天各提醒一次"
                enableVibration(true)
            }
        )
    }

    fun notifyDue(context: Context, prefs: AppPrefs, onlyId: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val alreadySent = prefs.certificateReminderKeys()
        prefs.certificates().asSequence()
            .filter { onlyId == null || it.id == onlyId }
            .forEach { item ->
                val remaining = item.remainingDays() ?: return@forEach
                val milestone = reminderMilestone(remaining) ?: return@forEach
                val reminderKey = "${item.id}:$milestone"
                if (reminderKey in alreadySent) return@forEach
                notify(context, item, remaining, milestone)
                prefs.markCertificateReminderSent(reminderKey)
            }
    }

    private fun notify(context: Context, item: CertificateExpiryItem, remaining: Long, milestone: Int) {
        val openApp = PendingIntent.getActivity(
            context,
            5200,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (remaining == 0L) "证书今天到期" else "证书将在 $remaining 天后到期"
        val detail = listOf(item.note, item.domain, item.organization, item.provider).filter { it.isNotBlank() }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CERTIFICATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_labprobe)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n到期时间：${item.expiresOn}"))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = 0x52000000 or ((item.id + milestone).hashCode() and 0x000FFFFF)
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

@Composable
fun CertificateExpirySection(prefs: AppPrefs) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf(prefs.certificates()) }
    var editing by remember { mutableStateOf<CertificateExpiryItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val dueSoon = remember(items) { items.count { (it.remainingDays() ?: Long.MAX_VALUE) in 0L..7L } }

    fun persist(next: List<CertificateExpiryItem>) {
        prefs.saveCertificates(next)
        items = prefs.certificates()
        CertificateReminderCenter.notifyDue(context, prefs)
    }

    ExpressiveCard(
        title = "证书到期",
        subtitle = if (items.isEmpty()) "可添加多张证书" else "${items.size} 张 · ${dueSoon} 张临期",
        icon = Icons.Rounded.Badge,
        accent = LabV2.Cyan,
        headerAction = {
            Surface(onClick = { adding = true }, modifier = Modifier.size(34.dp), shape = CircleShape, color = LabV2.Primary.copy(alpha = .10f)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "添加证书", Modifier.size(18.dp), tint = LabV2.Primary) }
            }
        }
    ) {
        if (items.isEmpty()) {
            Text("暂无证书；添加后，打开或刷新 APP 会检查 7 天、3 天和当天提醒。", fontSize = 11.5.sp, color = LabV2.InkMuted)
        } else {
            items.forEach { item ->
                CertificateExpiryCard(
                    item = item,
                    onEdit = { editing = item },
                    onDelete = {
                        prefs.clearCertificateReminderHistory(item.id)
                        persist(items.filterNot { it.id == item.id })
                    }
                )
            }
        }
    }

    if (adding || editing != null) {
        CertificateEditorSheet(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { saved ->
                if (editing?.expiresOn != saved.expiresOn) prefs.clearCertificateReminderHistory(saved.id)
                val index = items.indexOfFirst { it.id == saved.id }
                persist(if (index >= 0) items.toMutableList().also { it[index] = saved } else items + saved)
                adding = false
                editing = null
            }
        )
    }
}

@Composable
private fun CertificateExpiryCard(item: CertificateExpiryItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(item.id) { mutableStateOf(false) }
    val remaining = item.remainingDays()
    val accent = when {
        remaining == null -> LabV2.InkMuted
        remaining < 0 -> LabV2.InkMuted
        remaining <= 3 -> LabV2.Red
        remaining <= 7 -> LabV2.Amber
        else -> LabV2.Green
    }
    val remainingText = when {
        remaining == null -> "日期无效"
        remaining < 0 -> "已过期 ${-remaining} 天"
        remaining == 0L -> "今天到期"
        else -> "剩余 $remaining 天"
    }
    Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = .055f), border = BorderStroke(1.dp, accent.copy(alpha = .12f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.note.ifBlank { "证书" }, fontSize = 12.5.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(7.dp))
                Text(item.domain, Modifier.weight(1f), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = LabV2.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(99.dp), color = accent.copy(alpha = .12f)) {
                    Text(remainingText, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = accent, maxLines = 1)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(17.dp), tint = LabV2.InkMuted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(15.dp), containerColor = LabV2.Field) {
                        DropdownMenuItem(text = { Text("编辑", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("删除", fontSize = 12.sp, color = LabV2.Red, fontWeight = FontWeight.SemiBold) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp), tint = LabV2.Red) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CertificateCardInfo("机构", item.organization.ifBlank { "--" }, Modifier.weight(1f))
                CertificateCardInfo("服务商", item.provider.ifBlank { "--" }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CertificateCardInfo("申请", item.appliedOn.ifBlank { "--" }, Modifier.weight(1f))
                CertificateCardInfo("到期", item.expiresOn, Modifier.weight(1f), valueColor = accent)
            }
        }
    }
}

@Composable
private fun CertificateCardInfo(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LabV2.InkMuted) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, color = LabV2.InkFaint, maxLines = 1)
        Text(value, Modifier.weight(1f), fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CertificateEditorSheet(existing: CertificateExpiryItem?, onDismiss: () -> Unit, onSave: (CertificateExpiryItem) -> Unit) {
    var note by remember(existing?.id) { mutableStateOf(existing?.note.orEmpty()) }
    var domain by remember(existing?.id) { mutableStateOf(existing?.domain.orEmpty()) }
    var organization by remember(existing?.id) { mutableStateOf(existing?.organization.orEmpty()) }
    var provider by remember(existing?.id) { mutableStateOf(existing?.provider.orEmpty()) }
    var appliedOn by remember(existing?.id) { mutableStateOf(existing?.appliedOn.orEmpty()) }
    var expiresOn by remember(existing?.id) { mutableStateOf(existing?.expiresOn.orEmpty()) }
    var error by remember(existing?.id) { mutableStateOf("") }

    CompactBottomSheet(title = if (existing == null) "添加证书" else "编辑证书", onDismiss = onDismiss, scrollable = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CertificateInput("备注", note, { note = it }, "例如 lab6", Modifier.weight(1f))
            CertificateInput("域名", domain, { domain = it }, "lab.example.com", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CertificateInput("机构", organization, { organization = it }, "ZeroSSL", Modifier.weight(1f))
            CertificateInput("服务商", provider, { provider = it }, "阿里云 DNS", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CertificateInput("申请时间", appliedOn, { appliedOn = it }, "2026-07-17", Modifier.weight(1f))
            CertificateInput("到期时间", expiresOn, { expiresOn = it }, "2026-10-15", Modifier.weight(1f))
        }
        if (error.isNotBlank()) Text(error, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Red)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape) { Text("取消", fontWeight = FontWeight.Black) }
            Button(
                onClick = {
                    val normalizedApplied = if (appliedOn.isBlank()) "" else normalizeCertificateDate(appliedOn)
                    val normalizedExpiry = normalizeCertificateDate(expiresOn)
                    error = when {
                        domain.trim().isBlank() -> "请填写域名"
                        appliedOn.isNotBlank() && normalizedApplied.isBlank() -> "申请时间请使用 YYYY-MM-DD"
                        normalizedExpiry.isBlank() -> "到期时间请使用 YYYY-MM-DD"
                        else -> ""
                    }
                    if (error.isBlank()) {
                        onSave(CertificateExpiryItem(existing?.id ?: UUID.randomUUID().toString(), note.trim(), domain.trim(), organization.trim(), provider.trim(), normalizedApplied, normalizedExpiry))
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = LabV2.ButtonShape
            ) { Text("保存", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun RowScope.CertificateInput(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, modifier = Modifier.padding(start = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, maxLines = 1)
        CompactTextField(value, onValueChange, Modifier.fillMaxWidth(), placeholder = placeholder, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
    }
}
