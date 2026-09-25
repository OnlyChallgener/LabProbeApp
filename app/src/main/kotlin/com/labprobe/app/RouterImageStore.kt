package com.labprobe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

private const val ROUTER_IMAGE_SIZE = 640
private const val ROUTER_DECODE_MAX_EDGE = 2048

/** The filename reveals neither the Hub address nor the workspace name. */
internal fun routerImageScopeId(hubBase: String, workspaceId: String): String {
    val scope = hubBase.trim().trimEnd('/') + "\u0000" + workspaceId.trim()
    return MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun centerCropRouterBitmap(source: Bitmap, outputSize: Int = ROUTER_IMAGE_SIZE): Bitmap {
    require(outputSize > 0)
    val edge = min(source.width, source.height)
    require(edge > 0) { "图片尺寸无效" }
    val square = Bitmap.createBitmap(source, (source.width - edge) / 2, (source.height - edge) / 2, edge, edge)
    return if (edge == outputSize) square else Bitmap.createScaledBitmap(square, outputSize, outputSize, true)
}

internal class RouterImageStore(context: Context) {
    private val appContext = context.applicationContext

    private fun imageFile(hubBase: String, workspaceId: String): File =
        File(File(appContext.filesDir, "router-images"), routerImageScopeId(hubBase, workspaceId) + ".jpg")

    suspend fun load(hubBase: String, workspaceId: String): Bitmap? = withContext(Dispatchers.IO) {
        imageFile(hubBase, workspaceId).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    suspend fun importImage(uri: Uri, hubBase: String, workspaceId: String): Bitmap = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: throw IllegalArgumentException("无法读取所选图片")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是有效图片" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > ROUTER_DECODE_MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("无法解码所选图片")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val oriented = orientRouterBitmap(decoded, orientation)
        val square = centerCropRouterBitmap(oriented)
        val target = imageFile(hubBase, workspaceId)
        val directory = target.parentFile ?: throw IllegalStateException("图片存储目录不可用")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建图片存储目录" }
        val temporary = File.createTempFile("router-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                check(square.compress(Bitmap.CompressFormat.JPEG, 86, stream)) { "无法保存路由器图片" }
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
        } finally {
            temporary.delete()
        }
        square
    }
}

private fun orientRouterBitmap(source: Bitmap, orientation: Int): Bitmap {
    val transform = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            transform.setRotate(90f)
            transform.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            transform.setRotate(270f)
            transform.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
        else -> return source
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
}
