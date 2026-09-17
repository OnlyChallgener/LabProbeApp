package com.labprobe.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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

/**
 * Resolves app artwork without making the page depend on the network. A verified
 * feature-package PNG/WebP wins when present, Dashboard Icons is the secondary
 * source, and the UI always has a synchronous neutral fallback.
 */
@Composable
fun DashboardAppIcon(
    iconKey: String,
    localIconPath: String? = null,
    sizeDp: Int = 44,
    modifier: Modifier = Modifier
) {
    val cacheKey = "$iconKey|${localIconPath.orEmpty()}"
    val bitmap by produceState<ImageBitmap?>(dashboardIconMemoryCache[cacheKey], cacheKey) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val local = loadLocalPackageIcon(localIconPath)
            val remote = if (local == null) loadDashboardIcon(iconKey) else null
            (local ?: remote)?.also { dashboardIconMemoryCache[cacheKey] = it }
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
        if (bitmap != null) {
            Image(bitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Icon(Icons.Rounded.Apps, contentDescription = null, tint = LabV2.InkFaint, modifier = Modifier.size((sizeDp * .46f).dp))
        }
    }
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
