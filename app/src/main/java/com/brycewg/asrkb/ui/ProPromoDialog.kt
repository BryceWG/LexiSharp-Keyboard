package com.brycewg.asrkb.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.brycewg.asrkb.R
import java.io.File
import java.io.FileOutputStream

/**
 * Pro 购买动作工具类。
 */
object ProPromoDialog {

    private const val TAG = "ProPromoDialog"

    // Pro 版 Play 商店链接
    private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.brycewg.asrkb.pro"

    // Telegram 群链接
    private const val TELEGRAM_URL = "https://t.me/+UGFobXqi2bYzMDFl"

    // 作者邮箱
    private const val AUTHOR_EMAIL = "bryce1577006721@gmail.com"

    val authorEmail: String
        get() = AUTHOR_EMAIL

    fun openPlayStore(context: Context): Int? = openUrl(context, PLAY_STORE_URL)

    fun openTelegram(context: Context): Int? = openUrl(context, TELEGRAM_URL)

    private fun openUrl(context: Context, url: String): Int? = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        Log.e(TAG, "No browser found to open URL: $url", e)
        R.string.error_open_browser
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to open URL: $url", e)
        R.string.error_open_browser
    }

    /**
     * 复制邮箱到剪贴板
     */
    fun copyEmailToClipboard(context: Context): Int? {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("email", AUTHOR_EMAIL)
            clipboard.setPrimaryClip(clip)
            return R.string.payment_qr_email_copied
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to copy email to clipboard", e)
        }
        return null
    }

    /**
     * 保存二维码到相册
     */
    fun saveQrCodeToGallery(context: Context, drawableResId: Int, fileName: String): Int {
        try {
            val bitmap = BitmapFactory.decodeResource(context.resources, drawableResId)
            if (bitmap == null) {
                return R.string.payment_qr_save_failed
            }

            val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                saveImageToMediaStore(context, bitmap, fileName)
            } else {
                // Android 9 及以下使用传统方式
                saveImageToExternalStorage(context, bitmap, fileName)
            }

            return if (saved) {
                R.string.payment_qr_save_success
            } else {
                R.string.payment_qr_save_failed
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save QR code to gallery", e)
            return R.string.payment_qr_save_failed
        }
    }

    /**
     * Android 10+ 使用 MediaStore 保存图片
     */
    private fun saveImageToMediaStore(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val contentValues = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "${fileName}_${System.currentTimeMillis()}.jpg"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BiBi")
        }

        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
                ?: return false

        return try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save image via MediaStore", e)
            false
        }
    }

    /**
     * Android 9 及以下使用传统方式保存图片
     */
    @Suppress("DEPRECATION")
    private fun saveImageToExternalStorage(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ): Boolean {
        val picturesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val appDir = File(picturesDir, "BiBi")
        if (!appDir.exists() && !appDir.mkdirs()) {
            return false
        }

        val file = File(appDir, "${fileName}_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
            // 通知媒体库扫描
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save image to external storage", e)
            false
        }
    }
}
