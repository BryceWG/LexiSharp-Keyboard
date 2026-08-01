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

    fun showUploaded(fileName: String) = show(
        NOTIFICATION_UPLOAD,
        R.string.sc_attachment_notification_uploaded,
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

    private fun show(notificationId: Int, titleRes: Int, fileName: String) {
        val localizedContext = LocaleHelper.wrap(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    localizedContext.getString(R.string.sc_attachment_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
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

    companion object {
        private const val CHANNEL_ID = "clipboard_attachment_sync"
        private const val NOTIFICATION_UPLOAD = 41_001
        private const val NOTIFICATION_DOWNLOAD = 41_002
    }
}
