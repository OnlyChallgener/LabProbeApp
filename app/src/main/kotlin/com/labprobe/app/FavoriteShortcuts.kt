package com.labprobe.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class FavoriteShortcut(
    val id: String,
    val title: String,
    val description: String,
    val iconType: String,
    val iconValue: String,
    val lanUrl: String,
    val wanUrl: String,
    val order: Int
)

private data class FavoriteDraft(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val description: String = "",
    val iconType: String = "builtin",
    val iconValue: String = "web",
    val lanUrl: String = "",
    val wanUrl: String = ""
)

private val favoriteImageClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .build()

fun AppPrefs.favoriteShortcuts(): List<FavoriteShortcut> {
    val array = runCatching { JSONArray(favoriteShortcutsJson) }.getOrElse { JSONArray() }
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        if (id.isBlank() || title.isBlank()) return@mapNotNull null
        FavoriteShortcut(
            id = id,
            title = title,
            description = item.optString("description").trim(),
            iconType = item.optString("iconType", "builtin").trim().ifBlank { "builtin" },
            iconValue = item.optString("iconValue", "web").trim().ifBlank { "web" },
            lanUrl = item.optString("lanUrl").trim(),
            wanUrl = item.optString("wanUrl").trim(),
            order = item.optInt("order", index)
        )
    }.sortedBy { it.order }
}

fun AppPrefs.saveFavoriteShortcuts(items: List<FavoriteShortcut>) {
    val array = JSONArray()
    items.forEachIndexed { index, item ->
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("description", item.description)
                .put("iconType", item.iconType)
                .put("iconValue", item.iconValue)
                .put("lanUrl", item.lanUrl)
                .put("wanUrl", item.wanUrl)
                .put("order", index)
        )
    }
    favoriteShortcutsJson = array.toString()
}

private fun normalizeFavoriteUrl(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return ""
    return if (value.contains("://")) value else "https://$value"
}

private fun FavoriteShortcut.openUrl(mode: String): String = when (mode) {
    "wan" -> wanUrl.ifBlank { lanUrl }
    else -> lanUrl.ifBlank { wanUrl }
}

private fun openFavorite(context: Context, shortcut: FavoriteShortcut, mode: String) {
    val target = normalizeFavoriteUrl(shortcut.openUrl(mode))
    if (target.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context, "无法打开该地址") }
}

@Composable
fun FavoritesScreen(prefs: AppPrefs, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(if (prefs.favoriteNetworkMode == "wan") "wan" else "lan") }
    var query by rememberSaveable { mutableStateOf("") }
    var shortcuts by remember { mutableStateOf(prefs.favoriteShortcuts()) }
    var editing by remember { mutableStateOf<FavoriteShortcut?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun persist(items: List<FavoriteShortcut>) {
        val normalized = items.mapIndexed { index, item -> item.copy(order = index) }
        shortcuts = normalized
        prefs.saveFavoriteShortcuts(normalized)
    }

    val visible = remember(shortcuts, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) shortcuts else shortcuts.filter {
            it.title.contains(keyword, true) || it.description.contains(keyword, true) ||
                it.lanUrl.contains(keyword, true) || it.wanUrl.contains(keyword, true)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 720.dp) 3 else 2
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = LabV2.PageHorizontal, vertical = LabV2.PageTop),
            horizontalArrangement = Arrangement.spacedBy(LabV2.CardGap),
            verticalArrangement = Arrangement.spacedBy(LabV2.ListGap)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                    CompactPageHeader(
                        title = "收藏",
                        subtitle = "常用服务与网页入口",
                        action = {
                            CompactHeaderAction(Icons.Rounded.Add, "添加") { adding = true }
                            Spacer(Modifier.width(4.dp))
                            CompactHeaderAction(Icons.Rounded.Person, "我的") { onOpenSettings() }
                        }
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(15.dp), color = LabV2.FieldSoft, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
                            Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                FavoriteModeButton("内网", mode == "lan") { mode = "lan"; prefs.favoriteNetworkMode = "lan" }
                                FavoriteModeButton("外网", mode == "wan") { mode = "wan"; prefs.favoriteNetworkMode = "wan" }
                            }
                        }
                        CompactTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            placeholder = "搜索收藏",
                            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = LabV2.InkMuted) },
                            trailingIcon = if (query.isNotBlank()) ({
                                IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Rounded.Close, null, Modifier.size(16.dp), tint = LabV2.InkMuted)
                                }
                            }) else null
                        )
                    }
                }
            }

            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FavoriteEmptyState(hasSearch = query.isNotBlank()) { query = ""; adding = true }
                }
            } else {
                items(visible, key = { it.id }) { shortcut ->
                    FavoriteShortcutCard(
                        shortcut = shortcut,
                        mode = mode,
                        columns = columns,
                        dragEnabled = query.isBlank(),
                        onOpen = { openFavorite(context, shortcut, mode) },
                        onEdit = { editing = shortcut },
                        onDelete = { persist(shortcuts.filterNot { it.id == shortcut.id }) },
                        onMoveBy = { delta ->
                            val from = shortcuts.indexOfFirst { it.id == shortcut.id }
                            if (from >= 0) {
                                val to = (from + delta).coerceIn(0, shortcuts.lastIndex)
                                if (to != from) {
                                    val next = shortcuts.toMutableList()
                                    next.add(to, next.removeAt(from))
                                    persist(next)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (adding || editing != null) {
        FavoriteEditorSheet(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { saved ->
                val oldIndex = shortcuts.indexOfFirst { it.id == saved.id }
                val next = shortcuts.toMutableList()
                if (oldIndex >= 0) next[oldIndex] = saved.copy(order = oldIndex) else next += saved.copy(order = next.size)
                persist(next)
                adding = false
                editing = null
            }
        )
    }
}

@Composable
private fun CompactHeaderAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.size(38.dp), shape = CircleShape, color = LabV2.Field, border = androidx.compose.foundation.BorderStroke(1.dp, LabV2.Border)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(19.dp), tint = LabV2.Primary) }
    }
}

@Composable
private fun FavoriteModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = if (selected) LabV2.Primary else Color.Transparent) {
        Box(Modifier.height(40.dp).width(50.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.Black, color = if (selected) Color.White else LabV2.InkMuted)
        }
    }
}

@Composable
private fun FavoriteShortcutCard(
    shortcut: FavoriteShortcut,
    mode: String,
    columns: Int,
    dragEnabled: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveBy: (Int) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var dragging by remember(shortcut.id) { mutableStateOf(false) }
    var dragX by remember(shortcut.id) { mutableFloatStateOf(0f) }
    var dragY by remember(shortcut.id) { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(if (dragging) .965f else 1f, label = "favorite-drag-scale")
    val density = LocalDensity.current
    val rowThreshold = with(density) { 110.dp.toPx() }
    val colThreshold = with(density) { 150.dp.toPx() }

    fun finishDrag() {
        val row = (dragY / rowThreshold).roundToInt()
        val col = (dragX / colThreshold).roundToInt()
        val delta = row * columns + col
        if (delta != 0) onMoveBy(delta)
        dragging = false
        dragX = 0f
        dragY = 0f
    }

    CompactListCard(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(if (dragging) dragX.roundToInt() else 0, if (dragging) dragY.roundToInt() else 0) }
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (dragging) .97f else 1f }
            .shadow(if (dragging) 14.dp else 0.dp, LabV2.CompactCardShape, clip = false)
            .pointerInput(shortcut.id, dragEnabled) {
                if (dragEnabled) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = true },
                        onDragEnd = ::finishDrag,
                        onDragCancel = { dragging = false; dragX = 0f; dragY = 0f },
                        onDrag = { change, amount -> change.consume(); dragX += amount.x; dragY += amount.y }
                    )
                }
            }
            .combinedClickable(onClick = onOpen, onLongClick = {})
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            FavoriteIcon(shortcut.iconType, shortcut.iconValue, 44)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(shortcut.title, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortcut.description.ifBlank { if (mode == "lan") "内网快捷入口" else "外网快捷入口" }, fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(18.dp), tint = LabV2.InkMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = LabV2.Field) {
                    DropdownMenuItem(text = { Text("编辑", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", color = LabV2.Red, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = LabV2.Red) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun FavoriteEmptyState(hasSearch: Boolean, onAction: () -> Unit) {
    CompactListCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            LabV2ToolIcon(if (hasSearch) Icons.Rounded.SearchOff else Icons.Rounded.Bookmarks, LabV2.Primary, size = 48)
            Text(if (hasSearch) "没有匹配的收藏" else "还没有收藏", fontSize = 14.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)
            Text(if (hasSearch) "清除搜索后继续浏览" else "添加常用服务或网页入口", fontSize = 10.5.sp, color = LabV2.InkMuted)
            TextButton(onClick = onAction) { Text(if (hasSearch) "清除并添加" else "添加收藏", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun FavoriteIcon(type: String, value: String, size: Int) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, type, value) {
        value = if (type == "local" || type == "url") {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = if (type == "local") {
                        File(value).takeIf { it.isFile }?.readBytes()
                    } else {
                        val request = Request.Builder().url(normalizeFavoriteUrl(value)).build()
                        favoriteImageClient.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.bytes() else null }
                    }
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }.getOrNull()
            }
        } else null
    }
    val shape = RoundedCornerShape((size * .28f).dp)
    Box(Modifier.size(size.dp).clip(shape).background(LabV2.Primary.copy(alpha = .10f)).border(1.dp, LabV2.Primary.copy(alpha = .10f), shape), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val (icon, color) = favoriteBuiltinIcon(if (type == "builtin") value else "web")
            Icon(icon, null, Modifier.size((size * .52f).dp), tint = color)
        }
    }
}

private fun favoriteBuiltinIcon(value: String): Pair<ImageVector, Color> = when (value) {
    "router" -> Icons.Rounded.Router to Color(0xFF2563EB)
    "server" -> Icons.Rounded.Storage to Color(0xFF64748B)
    "media" -> Icons.Rounded.PlayCircle to Color(0xFF7C3AED)
    "cloud" -> Icons.Rounded.Cloud to Color(0xFF0EA5E9)
    "home" -> Icons.Rounded.Home to Color(0xFF16A34A)
    "download" -> Icons.Rounded.Download to Color(0xFF14B8A6)
    else -> Icons.Rounded.Language to LabV2.Primary
}

@Composable
private fun FavoriteEditorSheet(existing: FavoriteShortcut?, onDismiss: () -> Unit, onSave: (FavoriteShortcut) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(existing?.id) {
        mutableStateOf(
            existing?.let { FavoriteDraft(it.id, it.title, it.description, it.iconType, it.iconValue, it.lanUrl, it.wanUrl) }
                ?: FavoriteDraft()
        )
    }
    var error by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { copyFavoriteImage(context, uri) }
                    .onSuccess { path -> draft = draft.copy(iconType = "local", iconValue = path); error = "" }
                    .onFailure { error = it.message ?: "图片导入失败" }
            }
        }
    }

    CompactBottomSheet(title = if (existing == null) "添加收藏" else "编辑收藏", onDismiss = onDismiss, scrollable = true) {
        FavoriteEditorLabel("名称")
        CompactTextField(draft.title, { draft = draft.copy(title = it) }, Modifier.fillMaxWidth(), placeholder = "例如：Home Assistant")
        FavoriteEditorLabel("描述")
        CompactTextField(draft.description, { draft = draft.copy(description = it) }, Modifier.fillMaxWidth(), placeholder = "一行简短说明")
        FavoriteEditorLabel("内网地址")
        CompactTextField(draft.lanUrl, { draft = draft.copy(lanUrl = it) }, Modifier.fillMaxWidth(), placeholder = "192.168.5.10:8123", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        FavoriteEditorLabel("外网地址")
        CompactTextField(draft.wanUrl, { draft = draft.copy(wanUrl = it) }, Modifier.fillMaxWidth(), placeholder = "example.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))

        FavoriteEditorLabel("图标来源")
        CompactSegmentedControl(listOf("内置图标", "本地上传", "图片网址"), when (draft.iconType) { "local" -> "本地上传"; "url" -> "图片网址"; else -> "内置图标" }, {
            draft = when (it) {
                "本地上传" -> draft.copy(iconType = "local", iconValue = if (draft.iconType == "local") draft.iconValue else "")
                "图片网址" -> draft.copy(iconType = "url", iconValue = if (draft.iconType == "url") draft.iconValue else "")
                else -> draft.copy(iconType = "builtin", iconValue = if (draft.iconType == "builtin") draft.iconValue else "web")
            }
        })

        when (draft.iconType) {
            "local" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FavoriteIcon(draft.iconType, draft.iconValue, 46)
                OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape) {
                    Icon(Icons.Rounded.Image, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("选择 PNG / JPG / WEBP", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
            "url" -> CompactTextField(draft.iconValue, { draft = draft.copy(iconValue = it) }, Modifier.fillMaxWidth(), placeholder = "https://example.com/icon.png", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), leadingIcon = { FavoriteIcon("url", draft.iconValue, 28) })
            else -> {
                val choices = listOf("web", "router", "server", "media", "cloud", "home", "download")
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    choices.forEach { value ->
                        val selected = draft.iconValue == value
                        Surface(onClick = { draft = draft.copy(iconValue = value) }, modifier = Modifier.size(46.dp), shape = RoundedCornerShape(15.dp), color = if (selected) LabV2.Primary.copy(alpha = .12f) else LabV2.FieldSoft, border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) LabV2.Primary.copy(alpha = .35f) else LabV2.Border)) {
                            Box(contentAlignment = Alignment.Center) { val icon = favoriteBuiltinIcon(value); Icon(icon.first, null, Modifier.size(21.dp), tint = icon.second) }
                        }
                    }
                }
            }
        }

        if (error.isNotBlank()) Text(error, color = LabV2.Red, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape) { Text("取消", fontWeight = FontWeight.Black) }
            Button(
                onClick = {
                    val lan = normalizeFavoriteUrl(draft.lanUrl)
                    val wan = normalizeFavoriteUrl(draft.wanUrl)
                    error = when {
                        draft.title.trim().isBlank() -> "请填写名称"
                        lan.isBlank() && wan.isBlank() -> "内网地址和外网地址不能同时为空"
                        draft.iconType == "local" && draft.iconValue.isBlank() -> "请选择本地图标"
                        draft.iconType == "url" && draft.iconValue.isBlank() -> "请填写图片网址"
                        else -> ""
                    }
                    if (error.isBlank()) {
                        onSave(FavoriteShortcut(draft.id, draft.title.trim(), draft.description.trim(), draft.iconType, draft.iconValue.trim(), lan, wan, existing?.order ?: 0))
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = LabV2.ButtonShape,
                enabled = draft.title.trim().isNotBlank() &&
                    (draft.lanUrl.trim().isNotBlank() || draft.wanUrl.trim().isNotBlank()) &&
                    !(draft.iconType in setOf("local", "url") && draft.iconValue.trim().isBlank())
            ) { Text("保存", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun FavoriteEditorLabel(text: String) {
    Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.Black, color = LabV2.InkMuted, modifier = Modifier.padding(start = 2.dp))
}

private suspend fun copyFavoriteImage(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
    val extension = when {
        mime == "image/png" -> "png"
        mime == "image/jpeg" -> "jpg"
        mime == "image/webp" -> "webp"
        else -> uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
    }
    require(extension in setOf("png", "jpg", "jpeg", "webp")) { "仅支持 PNG、JPG、WEBP 图片" }
    val dir = File(context.filesDir, "favorite_icons").apply { mkdirs() }
    val target = File(dir, "${UUID.randomUUID()}.$extension")
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        ?: error("无法读取所选图片")
    require(target.length() in 1..(8L * 1024L * 1024L)) { target.delete(); "图片不能为空且不能超过 8MB" }
    target.absolutePath
}
