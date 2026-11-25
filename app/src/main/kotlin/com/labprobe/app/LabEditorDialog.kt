package com.labprobe.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Shared editor contract: content-sized card, scrollable fields, visible actions. */
@Composable
fun LabEditorDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    containerColor: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * .90f).coerceAtMost(720.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LabGradientDialogSurface(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).imePadding(),
            color = containerColor,
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 18.dp, vertical = 18.dp)) {
                Text(title, Modifier.align(Alignment.CenterHorizontally), style = LabTypography.DialogTitle)
                Spacer(Modifier.height(18.dp))
                ProvideTextStyle(LabTypography.DialogBody) {
                    Column(
                        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
                    )
                }
                Spacer(Modifier.height(16.dp))
                ProvideTextStyle(LabTypography.Button) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
    }
}
