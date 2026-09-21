package com.labprobe.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Bundled artwork for apps no icon source covers (未识别应用图标). */
private const val UNKNOWN_APP_ICON_KEY = "unknown"
private const val ASSET_DIR = "appicons"

/**
 * 两张缓存，各自按「最终用的是哪一张图」为键。
 *
 * 目录里 309 款只有 77 款有 logo，其余 232 款全落到同一张未识别图标。以前键是调用方
 * 传进来的 iconKey，于是同一张 unknown.png 被解码 232 份、占 232 份内存，一进列表
 * 就连着解十几张 —— 这是页面切换和上下滑动掉帧的直接原因。
 */
private val assetIconCache = ConcurrentHashMap<String, ImageBitmap>()
private val fileIconCache = ConcurrentHashMap<String, ImageBitmap>()

/** assets/appicons 里真实存在的文件名，只列一次。 */
@Volatile
private var bundledAssetNames: Set<String>? = null

/** 正则每次现编也是开销：这个函数原来每取一张图就编译一次。 */
private val iconKeyPattern = Regex("[a-z0-9][a-z0-9-]*")

/** Soft pastel fills for the letter-avatar fallback, keyed by name hash. */
private val avatarPalette = listOf(
    0xFF5B8DEF, 0xFF7C5CE7, 0xFFE8618C, 0xFFF09A3E, 0xFF2FB380,
    0xFF38B6C9, 0xFFE77A5B, 0xFF6C7BE8, 0xFF4CA97F, 0xFFD66BA0
)

/**
 * Resolves app artwork without making the page depend on the network. Priority:
 * a verified feature-package PNG/WebP, then the icon pack bundled in APK assets,
 * then the bundled 未识别应用 artwork, and finally a letter avatar so no
 * placeholder squares appear.
 *
 * 这里刻意不再回落到第三方图标 CDN：CDN 按 key 取图，拿到什么全看对方仓库里那个
 * 文件名对应的是哪个应用 —— 实测「腾讯会议」取回来一张今日头条的 logo。显示一个
 * 错的图标比显示未识别图标更糟。
 */
@Composable
fun DashboardAppIcon(
    iconKey: String,
    localIconPath: String? = null,
    sizeDp: Int = 44,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val context = LocalContext.current
    // 解码放后台线程。之前改成在组合里同步解，是为了治「微信支付先闪成安全教育平台」
    // 那种串图 —— 但串图的根因是加载协程没跟着 key 取消，不是异步本身。这里每行只查
    // 自己那一个 key：命中缓存就在同一帧直接画出来（重复滚动完全不闪），没命中先留
    // 空位，图到了再换，任何情况下都不会画成别的应用。
    val bitmap by produceState<ImageBitmap?>(
        initialValue = cachedIcon(context, iconKey, localIconPath),
        key1 = iconKey,
        key2 = localIconPath,
    ) {
        value = withContext(Dispatchers.IO) { loadIcon(context, iconKey, localIconPath) }
    }
    val shape = RoundedCornerShape((sizeDp * .24f).dp)
    Box(
        modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(LabCoreSurface.Inner)
            .border(1.dp, LabCoreSurface.Border, shape),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(current, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            LetterAvatar(label, sizeDp)
        }
    }
}

/** 已经缓存过的图直接返回，让首帧就画对；没缓存的交给后台那一步。 */
private fun cachedIcon(context: Context, iconKey: String, localIconPath: String?): ImageBitmap? {
    val path = localIconPath?.takeIf { it.isNotBlank() }
    if (path != null) return fileIconCache[path]
    return assetIconCache[resolvedAssetKey(context, iconKey)]
}

private fun loadIcon(context: Context, iconKey: String, localIconPath: String?): ImageBitmap? {
    val path = localIconPath?.takeIf { it.isNotBlank() }
    if (path != null) {
        fileIconCache[path]?.let { return it }
        return decodeFile(path)?.also { fileIconCache[path] = it }
    }
    val asset = resolvedAssetKey(context, iconKey)
    assetIconCache[asset]?.let { return it }
    return decodeAsset(context, asset)?.also { assetIconCache[asset] = it }
}

/** 认不出来的 key 一律并到未识别图标上，和别的没图的应用共用同一份位图。 */
private fun resolvedAssetKey(context: Context, iconKey: String): String =
    if (iconKey.matches(iconKeyPattern) && iconKey in bundledNames(context)) iconKey else UNKNOWN_APP_ICON_KEY

private fun bundledNames(context: Context): Set<String> {
    bundledAssetNames?.let { return it }
    val names = runCatching {
        context.assets.list(ASSET_DIR)
            ?.map { it.substringBeforeLast('.') }
            ?.filter { it.matches(iconKeyPattern) }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())
    // 列目录失败不能把空集合钉死，否则之后所有内置图都会永久退成未识别图标。
    if (names.isNotEmpty()) bundledAssetNames = names
    return names
}

private fun decodeAsset(context: Context, iconKey: String): ImageBitmap? = runCatching {
    context.assets.open("$ASSET_DIR/$iconKey.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
}.getOrNull()

private fun decodeFile(path: String): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    val bytes = file.readBytes()
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** Colored initial rendered when no artwork exists for an app. */
@Composable
private fun LetterAvatar(label: String?, sizeDp: Int) {
    val name = label?.trim().orEmpty()
    if (name.isEmpty()) {
        Box(Modifier.fillMaxSize().background(LabCoreSurface.Inner))
        return
    }
    val background = remember(name) {
        Color(avatarPalette[name.fold(0) { acc, ch -> acc * 31 + ch.code }.mod(avatarPalette.size)])
    }
    Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
        Text(
            text = name.first().uppercase(),
            color = Color.White,
            fontSize = (sizeDp * .44f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
