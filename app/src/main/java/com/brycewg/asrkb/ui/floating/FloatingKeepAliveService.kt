/**
 * 悬浮球前台保活服务：通过常驻通知维持进程优先级。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.settings.floating.FloatingSettingsActivity
import java.util.UUID

class FloatingKeepAliveService : Service() {

    private var keepAliveStarted = false

    companion object {
        private const val TAG = "FloatingKeepAliveSvc"
        private const val CHANNEL_ID = "floating_keep_alive"
        private const val NOTIFICATION_ID = 4101
        private const val TOKEN_PREFS = "floating_keep_alive_auth"
        private const val TOKEN_KEY = "caller_token"
        const val EXTRA_CALLER_TOKEN = "com.brycewg.asrkb.extra.FLOATING_KEEP_ALIVE_TOKEN"

        const val ACTION_START = "com.brycewg.asrkb.action.FLOATING_KEEP_ALIVE_START"
        const val ACTION_STOP = "com.brycewg.asrkb.action.FLOATING_KEEP_ALIVE_STOP"

        @Synchronized
        internal fun getOrCreateCallerToken(context: Context): String {
            val prefs = context.applicationContext.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(TOKEN_KEY, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(TOKEN_KEY, generated).apply()
            return generated
        }

        fun start(context: Context) {
            val intent = Intent(context, FloatingKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CALLER_TOKEN, getOrCreateCallerToken(context))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingKeepAliveService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_CALLER_TOKEN, getOrCreateCallerToken(context))
            }
            context.startService(intent)
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && !isTrustedCaller(intent)) {
            Log.w(TAG, "Ignore unauthorized keep-alive request")
            DebugLogManager.logPersistent(this, "keepalive", "service_reject", mapOf("reason" to "token_mismatch"))
            if (!keepAliveStarted) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                DebugLogManager.logPersistent(this, "keepalive", "service_stop")
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 系统重启后可能以空 intent 触发重建：开关关闭时立即退出，避免误保活造成耗电。
                val prefs = try {
                    Prefs(this)
                } catch (_: Throwable) {
                    null
                }
                if (prefs != null && !prefs.floatingKeepAliveEnabled) {
                    DebugLogManager.logPersistent(this, "keepalive", "service_skip", mapOf("reason" to "pref_disabled"))
                    stopForegroundSafely()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (keepAliveStarted && intent?.action == ACTION_START) {
                    DebugLogManager.logPersistent(this, "keepalive", "service_skip", mapOf("reason" to "already_started"))
                    return START_STICKY
                }
                startForegroundWithNotification()
                keepAliveStarted = true
                DebugLogManager.logPersistent(this, "keepalive", "service_start", mapOf("action" to (intent?.action ?: "null")))
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        keepAliveStarted = false
        stopForegroundSafely()
        super.onDestroy()
    }

    private fun isTrustedCaller(intent: Intent): Boolean {
        val expected = getOrCreateCallerToken(this)
        val provided = intent.getStringExtra(EXTRA_CALLER_TOKEN)
        return !provided.isNullOrBlank() && provided == expected
    }

    private fun startForegroundWithNotification() {
        val openIntent = Intent(this, FloatingSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_floating_keep_alive_title))
            .setContentText(getString(R.string.notif_floating_keep_alive_desc))
            .setSmallIcon(R.drawable.microphone)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_floating_keep_alive),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notif_channel_floating_keep_alive_desc)
        try {
            notificationManager.createNotificationChannel(channel)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create keep-alive channel", e)
        }
    }

    private fun stopForegroundSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to stop foreground", e)
        }
    }
}
