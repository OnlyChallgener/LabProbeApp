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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Homarr Labs Dashboard Icons: Apache-2.0 collection, resolved by kebab-case key.
private const val DASHBOARD_ICON_BASE = "https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png"

private val dashboardIconClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .build()

private val dashboardIconMemoryCache = ConcurrentHashMap<String, ImageBitmap>()
private val dashboardIconMissCache = ConcurrentHashMap.newKeySet<String>()

/** Soft pastel fills for the letter-avatar fallback, keyed by name hash. */
private val avatarPalette = listOf(
    0xFF5B8DEF, 0xFF7C5CE7, 0xFFE8618C, 0xFFF09A3E, 0xFF2FB380,
    0xFF38B6C9, 0xFFE77A5B, 0xFF6C7BE8, 0xFF4CA97F, 0xFFD66BA0
)

/**
 * Resolves app artwork without making the page depend on the network. Priority:
 * a verified feature-package PNG/WebP, then the icon pack bundled in APK assets
 * (Chinese apps missing from public icon CDNs), then the Dashboard Icons CDN,
 * and finally a synchronous letter avatar so no placeholder squares appear.
 */
@Composable
fun DashboardAppIcon(
    iconKey: String,
    localIconPath: String? = null,
    sizeDp: Int = 44,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val cacheKey = "$iconKey|${localIconPath.orEmpty()}"
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(dashboardIconMemoryCache[cacheKey], cacheKey) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val local = loadLocalPackageIcon(localIconPath)
            val bundled = if (local == null) loadBundledIcon(context, iconKey) else null
            val remote = if (local == null && bundled == null) loadDashboardIcon(iconKey) else null
            (local ?: bundled ?: remote)?.also { dashboardIconMemoryCache[cacheKey] = it }
        }
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

/** Icons shipped in APK assets under appicons/, generated from MIT-licensed packs. */
private fun loadBundledIcon(context: Context, iconKey: String): ImageBitmap? {
    if (!iconKey.matches(Regex("[a-z0-9][a-z0-9-]*")) || iconKey == "missing" || iconKey in dashboardIconMissCache) return null
    return runCatching {
        context.assets.open("appicons/$iconKey.png").use { stream ->
            BitmapFactory.decodeStream(stream)?.asImageBitmap()
        }
    }.getOrNull()
}

private fun loadDashboardIcon(iconKey: String): ImageBitmap? {
    if (!iconKey.matches(Regex("[a-z0-9][a-z0-9-]*")) || iconKey == "missing" || iconKey in dashboardIconMissCache) return null
    val bitmap = runCatching {
        val request = Request.Builder().url("$DASHBOARD_ICON_BASE/$iconKey.png").build()
        dashboardIconClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }.getOrNull()
    if (bitmap == null) dashboardIconMissCache.add(iconKey)
    return bitmap
}

private fun loadLocalPackageIcon(localIconPath: String?): ImageBitmap? = runCatching {
    localIconPath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.isFile }
        ?.readBytes()
        ?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
}.getOrNull()
