package com.labprobe.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Floating surfaces only. Page cards and original brand artwork keep their shapes. */
internal object LabPopupShapes {
    val Menu = RoundedCornerShape(8.dp)
    val Card = RoundedCornerShape(12.dp)
    val Field = RoundedCornerShape(10.dp)
    val Sheet = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
}
