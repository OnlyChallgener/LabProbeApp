package com.labprobe.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 「路由器图片」这一行，只给地址编辑弹层用。
 *
 * 以前它在网络健康卡片和路由器状态页的弹层外面各挂了一份（一个 TextButton、一个角标），
 * 于是两张卡片下半截都是空白 + 一行多余的字。图片是这台路由的装饰性设置，
 * 和备注、内外网地址同一个改动意图，所以收进同一个编辑弹层。
 */
@Composable
fun LabProbeRouterImageEntry(hasImage: Boolean, error: String, onPick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = Color(0xFFF7FAFD),
        border = BorderStroke(1.dp, LabV2.Border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Image, null, Modifier.size(19.dp), tint = LabV2.Primary)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("路由器图片", style = LabTypography.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        error.isNotBlank() -> error
                        hasImage -> "已设置，自动裁剪为方形"
                        else -> "方形图效果最好，自动裁剪后只存本机"
                    },
                    style = LabTypography.Caption,
                    color = if (error.isBlank()) LabV2.InkMuted else LabV2.Red,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onPick, shape = RoundedCornerShape(11.dp)) {
                Text(if (hasImage) "更换" else "上传", style = LabTypography.Button)
            }
        }
    }
}
