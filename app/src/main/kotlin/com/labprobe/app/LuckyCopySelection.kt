package com.labprobe.app

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString

/** Native selection handles plus an explicit whole-card copy action, confined to Lucky. */
@Composable internal fun LuckyCopySelection(allText: String, content: @Composable () -> Unit) {
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val delegate = LocalTextToolbar.current
    val latestText by rememberUpdatedState(allText)
    val toolbar = remember(view, clipboard, delegate) {
        object : TextToolbar {
            var mode: ActionMode? = null
            var toolbarStatus by mutableStateOf(TextToolbarStatus.Hidden)
            override val status: TextToolbarStatus get() = toolbarStatus
            override fun hide() { mode?.finish(); mode = null; delegate.hide(); toolbarStatus = TextToolbarStatus.Hidden }
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
                mode?.finish()
                mode = view.startActionMode(object : ActionMode.Callback2() {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        if (onCopyRequested != null) menu.add(0, 1, 0, "复制选中")
                        menu.add(0, 2, 1, "复制全部")
                        if (onSelectAllRequested != null) menu.add(0, 3, 2, "全选")
                        return true
                    }
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                    override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
                        outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                    }
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                        when (item.itemId) {
                            1 -> onCopyRequested?.invoke()
                            2 -> clipboard.setText(AnnotatedString(latestText))
                            3 -> { onSelectAllRequested?.invoke(); return true }
                            else -> return false
                        }
                        mode.finish(); return true
                    }
                    override fun onDestroyActionMode(mode: ActionMode) { toolbarStatus = TextToolbarStatus.Hidden }
                }, ActionMode.TYPE_FLOATING)
                if (mode == null) delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
                toolbarStatus = TextToolbarStatus.Shown
            }
        }
    }
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) { SelectionContainer(content = content) }
}
