package com.labprobe.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Visual indicator only: the existing parent continues to own navigation/click handling and its touch target. */
@Composable
fun LabNavigationIndicator(modifier: Modifier = Modifier, external: Boolean = false, enabled: Boolean = true, description: String? = null) {
    Box(modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(32.dp), shape = CircleShape,
            color = if (enabled) Color(0xFFE8F4FC) else Color(0xFFF0F3F6)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (external) Icons.Rounded.ArrowOutward else Icons.Rounded.ChevronRight,
                    description, Modifier.size(if (external) 16.dp else 20.dp),
                    tint = if (enabled) LabV2.Primary else LabV2.InkFaint)
            }
        }
    }
}
