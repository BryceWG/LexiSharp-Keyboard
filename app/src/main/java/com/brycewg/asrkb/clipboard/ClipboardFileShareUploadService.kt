/** Runs a user-initiated shared-file upload without opening the settings UI. */
package com.brycewg.asrkb.clipboard

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipboardFileShareUploadService : Service() {
    companion object {
        fun start(context: Context, uri: Uri) {
            context.startService(
                Intent(context, ClipboardFileShareUploadService::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data ?: return START_NOT_STICKY
        // ponytail: regular service uploads are best-effort; use a dataSync foreground service if long transfers need durability.
        serviceScope.launch {
            val prefs = Prefs(applicationContext)
            if (prefs.syncClipboardEnabled) {
                SyncClipboardManager(applicationContext, prefs, serviceScope)
                    .uploadSharedFile(uri)
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
