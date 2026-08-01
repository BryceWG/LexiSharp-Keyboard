/**
 * 使用统计分享卡导出、系统分享与保存相册。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.brycewg.asrkb.R
import com.brycewg.asrkb.UiColorTokens
import com.brycewg.asrkb.UiColors
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object UsageStatsShareActions {
    private const val TAG = "UsageStatsShare"
    private const val MIME_TYPE = "image/jpeg"
    private const val FILE_EXTENSION = "jpg"
    /** 分享友好的 JPEG 质量：体积明显小于 PNG，且卡片渐变观感可接受。 */
    private const val JPEG_QUALITY = 85

    fun writeImageToCache(context: Context, bitmap: Bitmap): File? {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(context.cacheDir, "bibi_usage_stats_$stamp.$FILE_EXTENSION")
            FileOutputStream(file).use { out ->
                if (!compressShareImage(context, bitmap, out)) {
                    Log.e(TAG, "Failed to compress usage stats image")
                    return null
                }
            }
            file
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write usage stats image to cache", t)
            null
        }
    }

    fun buildShareIntent(context: Context, file: File): Intent? {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    context.getString(R.string.about_stats_share_chooser_title)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(
                    context.contentResolver,
                    "usage_stats",
                    uri
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build usage stats share intent", t)
            null
        }
    }

    fun shareBitmap(context: Context, bitmap: Bitmap): Boolean {
        val file = writeImageToCache(context, bitmap) ?: return false
        val intent = buildShareIntent(context, file) ?: return false
        return try {
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.about_stats_share_chooser_title)
            )
            if (context !is android.app.Activity) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start usage stats share chooser", t)
            false
        }
    }

    fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap)
            } else {
                saveViaPublicPictures(context, bitmap)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save usage stats image to gallery", t)
            false
        }
    }

    private fun compressShareImage(
        context: Context,
        bitmap: Bitmap,
        out: java.io.OutputStream
    ): Boolean {
        // JPEG 不支持透明通道；导出前铺白底，避免异常底色。
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { dst ->
                val canvas = android.graphics.Canvas(dst)
                canvas.drawColor(UiColors.shareCard(context, UiColorTokens.shareCardSurface))
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        } else {
            bitmap
        }
        return try {
            opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        } finally {
            if (opaque !== bitmap && !opaque.isRecycled) {
                opaque.recycle()
            }
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "bibi_usage_stats_$stamp.$FILE_EXTENSION"
            )
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/BiBi"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        var completed = false
        return try {
            val written = resolver.openOutputStream(uri)?.use { out ->
                compressShareImage(context, bitmap, out)
            } == true
            if (!written) return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            completed = resolver.update(uri, values, null, null) > 0
            completed
        } catch (t: Throwable) {
            Log.e(TAG, "MediaStore save failed", t)
            false
        } finally {
            if (!completed) {
                try {
                    resolver.delete(uri, null, null)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to delete incomplete MediaStore image", t)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaPublicPictures(context: Context, bitmap: Bitmap): Boolean {
        val picturesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val appDir = File(picturesDir, "BiBi")
        if (!appDir.exists() && !appDir.mkdirs()) return false
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(appDir, "bibi_usage_stats_$stamp.$FILE_EXTENSION")
        FileOutputStream(file).use { out ->
            if (!compressShareImage(context, bitmap, out)) return false
        }
        return true
    }
}
