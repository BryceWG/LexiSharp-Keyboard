/** Displays compact status notifications for attachment sync operations. */
package com.brycewg.asrkb.clipboard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R

internal class ClipboardAttachmentNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private var uploadProgress = NO_PROGRESS
    private var downloadProgress = NO_PROGRESS

    fun showUploadProgress(fileName: String, completedBytes: Long, totalBytes: Long) =
        showProgress(
            notificationId = NOTIFICATION_UPLOAD,
            titleRes = R.string.sc_attachment_notification_uploading,
            fileName = fileName,
            completedBytes = completedBytes,
            totalBytes = totalBytes,
            isUpload = true
        )

    fun showDownloadProgress(fileName: String, completedBytes: Long, totalBytes: Long) =
        showProgress(
            notificationId = NOTIFICATION_DOWNLOAD,
            titleRes = R.string.sc_attachment_notification_downloading,
            fileName = fileName,
            completedBytes = completedBytes,
            totalBytes = totalBytes,
            isUpload = false
        )

    fun showUploaded(fileName: String) = show(
        NOTIFICATION_UPLOAD,
        R.string.sc_attachment_notification_uploaded,
        fileName
    )

    fun showUploadFailed(fileName: String) = show(
        NOTIFICATION_UPLOAD,
        R.string.sc_attachment_notification_upload_failed,
        fileName
    )

    fun showDownloaded(fileName: String) = show(
        NOTIFICATION_DOWNLOAD,
        R.string.sc_attachment_notification_downloaded,
        fileName
    )

    fun showDownloadFailed(fileName: String) = show(
        NOTIFICATION_DOWNLOAD,
        R.string.sc_attachment_notification_download_failed,
        fileName
    )

    fun clearUploadProgress() = clearProgress(NOTIFICATION_UPLOAD, isUpload = true)

    fun clearDownloadProgress() = clearProgress(NOTIFICATION_DOWNLOAD, isUpload = false)

    private fun showProgress(
        notificationId: Int,
        titleRes: Int,
        fileName: String,
        completedBytes: Long,
        totalBytes: Long,
        isUpload: Boolean
    ) {
        val progress = if (totalBytes > 0L) {
            ((completedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            INDETERMINATE_PROGRESS
        }
        if (isUpload) {
            if (progress == uploadProgress) return
            uploadProgress = progress
        } else {
            if (progress == downloadProgress) return
            downloadProgress = progress
        }
        val localizedContext = LocaleHelper.wrap(appContext)
        if (!canNotify()) return
        ensureChannel(localizedContext)
        notificationManager.notify(
            notificationId,
            NotificationCompat.Builder(localizedContext, CHANNEL_ID)
                .setSmallIcon(
                    if (isUpload) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download
                )
                .setContentTitle(localizedContext.getString(titleRes))
                .setContentText(fileName)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, progress.coerceAtLeast(0), progress == INDETERMINATE_PROGRESS)
                .build()
        )
    }

    private fun show(notificationId: Int, titleRes: Int, fileName: String) {
        val localizedContext = LocaleHelper.wrap(appContext)
        if (!canNotify()) return
        if (notificationId == NOTIFICATION_UPLOAD) uploadProgress = NO_PROGRESS else downloadProgress = NO_PROGRESS
        ensureChannel(localizedContext)
        notificationManager.notify(
            notificationId,
            NotificationCompat.Builder(localizedContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(localizedContext.getString(titleRes))
                .setContentText(fileName)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun clearProgress(notificationId: Int, isUpload: Boolean) {
        if (isUpload) uploadProgress = NO_PROGRESS else downloadProgress = NO_PROGRESS
        notificationManager.cancel(notificationId)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(localizedContext: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    localizedContext.getString(R.string.sc_attachment_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "clipboard_attachment_sync"
        private const val NOTIFICATION_UPLOAD = 41_001
        private const val NOTIFICATION_DOWNLOAD = 41_002
        private const val NO_PROGRESS = -2
        private const val INDETERMINATE_PROGRESS = -1
    }
}
