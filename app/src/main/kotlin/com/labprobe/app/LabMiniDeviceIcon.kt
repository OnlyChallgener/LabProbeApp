package com.labprobe.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Suppress("UNUSED_PARAMETER")
@Composable
fun LabMiniDeviceIcon(
    iconKey: String,
    accent: Color,
    modifier: Modifier = Modifier,
    sizeDp: Int = 44
) {
    Image(
        painter = painterResource(deviceIconDrawable(iconKey)),
        contentDescription = null,
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp * 0.24f).dp)),
        contentScale = ContentScale.Fit
    )
}
