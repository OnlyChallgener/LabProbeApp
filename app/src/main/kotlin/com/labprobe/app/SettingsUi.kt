package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SettingsCardShape = RoundedCornerShape(18.dp)
private val SettingsTitleSize = 14.5.sp

@Composable
internal fun RouterModelArtwork(
    model: String,
    name: String,
    routerId: String,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
) {
    val identity = listOf(model, name, routerId).joinToString(" ").lowercase()
    when {
        isModelBe50(model, name, routerId) -> Image(
            painterResource(R.drawable.router_be50), "BE50 路由器", modifier.size(size),
        )
        "be72" in identity -> Image(
            painterResource(R.drawable.router_skeuomorphic_v3), "BE72 路由器", modifier.size(size),
        )
        else -> Icon(Icons.Rounded.Router, "路由器", modifier.size(size), tint = LabV2.Primary)
    }
}

@Composable
internal fun SettingsExpandableCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SettingsCardShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabV2.Border.copy(alpha = .78f)),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(SettingsCardShape).clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    Modifier.weight(1f),
                    fontSize = SettingsTitleSize,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = LabV2.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        if (expanded) "折叠$title" else "展开$title",
                        Modifier.size(19.dp),
                        tint = LabV2.InkMuted,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
            }
        }
    }
}

@Composable
internal fun SettingsEntryCard(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SettingsCardShape,
        color = Color.White,
        border = BorderStroke(1.dp, LabV2.Border.copy(alpha = .78f)),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().clip(SettingsCardShape).clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                Modifier.weight(1f),
                fontSize = SettingsTitleSize,
                lineHeight = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = LabV2.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            } else {
                Icon(Icons.Rounded.ChevronRight, "打开$title", Modifier.size(20.dp), tint = LabV2.Primary)
            }
        }
    }
}


@Composable
internal fun SettingsAddressField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
    history: @Composable () -> Unit,
) {
    val trimmed = value.trim()
    val lower = trimmed.lowercase()
    val https = lower.startsWith("https://")
    val body = when {
        lower.startsWith("https://") -> trimmed.substring(8)
        lower.startsWith("http://") -> trimmed.substring(7)
        else -> trimmed
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted), modifier = Modifier.padding(start = 2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CompactTextField(
                value = body,
                onValueChange = { onValueChange((if (https) "https://" else "http://") + it) },
                placeholder = placeholder,
                trailingIcon = history,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            HttpsCheckbox(checked = https, onCheckedChange = { checked ->
                onValueChange((if (checked) "https://" else "http://") + body)
            })
        }
    }
}

@Composable
private fun HttpsCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
            .semantics { contentDescription = "HTTPS" }
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(18.dp),
            shape = RoundedCornerShape(4.dp),
            color = if (checked) LabV2.Primary else Color.White,
            border = BorderStroke(1.dp, if (checked) LabV2.Primary else LabV2.InkMuted.copy(alpha = .32f)),
        ) {
            if (checked) Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(13.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.width(4.dp))
        Text("HTTPS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LabV2.Ink)
    }
}

@Composable
internal fun SettingsPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
) {
    var visible by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted), modifier = Modifier.padding(start = 2.dp))
        CompactTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (visible) "隐藏$label" else "显示$label",
                        Modifier.size(17.dp),
                        tint = LabV2.InkMuted.copy(alpha = .65f),
                    )
                }
            },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
}

@Composable
internal fun SettingsConnectionStatusRow(status: SettingsConnectionStatus, modifier: Modifier = Modifier) {
    val color = when (status.tone) {
        SettingsConnectionTone.Success -> LabV2.Green
        SettingsConnectionTone.Connecting -> Color(0xFF2563EB)
        SettingsConnectionTone.Warning -> LabV2.Amber
        SettingsConnectionTone.Error -> LabV2.Red
        SettingsConnectionTone.Neutral -> LabV2.InkMuted
    }
    val icon = when (status.kind) {
        SettingsConnectionKind.Hub -> Icons.Rounded.Link
        SettingsConnectionKind.Token -> Icons.Rounded.Key
        SettingsConnectionKind.Router -> Icons.Rounded.Router
        SettingsConnectionKind.Realtime -> Icons.Rounded.Sync
        SettingsConnectionKind.Notice -> if (status.tone == SettingsConnectionTone.Connecting) Icons.Rounded.Sync else Icons.Rounded.Info
        SettingsConnectionKind.Normal -> if (status.tone == SettingsConnectionTone.Success) Icons.Rounded.CheckCircle else Icons.Rounded.Link
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(15.dp).padding(top = 1.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(
            status.message,
            color = color,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SettingsCardLeading(icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color) {
    LabV2ToolIcon(icon, accent, size = 38)
}
