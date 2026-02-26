/**
 * 开机自启广播接收器：按偏好恢复悬浮球与后台保活相关能力。
 *
 * 归属模块：根目录与通用
 */
package com.brycewg.asrkb

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.brycewg.asrkb.ui.floating.FloatingKeepAliveService
import com.brycewg.asrkb.ui.floating.PrivilegedKeepAliveScheduler
import com.brycewg.asrkb.ui.floating.PrivilegedKeepAliveStarter
import java.util.concurrent.Executors

/**
 * 开机自启广播接收器：
 * - 按偏好启动悬浮服务（前提：已授予悬浮窗权限）
 * - 若具备 WRITE_SECURE_SETTINGS（ADB/系统应用），尝试自动开启无障碍服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        DebugLogManager.logPersistent(context, "keepalive", "boot_received", mapOf("action" to action))

        val handler = Handler(Looper.getMainLooper())
        // 略微延迟，等系统服务就绪
        handler.postDelayed({
            tryStartOverlayServices(context)
            tryEnableAccessibilityIfPermitted(context)
        }, 2500L)
    }

    private fun tryStartOverlayServices(context: Context) {
        try {
            val prefs = Prefs(context)
            PrivilegedKeepAliveScheduler.update(context)

            if (prefs.floatingKeepAliveEnabled) {
                // 如果启用了增强保活，尝试使用 Shizuku/Root 方式启动
                if (prefs.floatingKeepAlivePrivilegedEnabled) {
                    // 在后台线程执行 Shizuku/Root 启动，避免阻塞主线程
                    val executor = Executors.newSingleThreadExecutor()
                    executor.execute {
                        tryStartKeepAlivePrivileged(context)
                        executor.shutdown()
                    }
                } else {
                    FloatingKeepAliveService.start(context)
                    DebugLogManager.logPersistent(context, "keepalive", "boot_start_normal")
                }
            }

            val canOverlay = Settings.canDrawOverlays(context)
            if (canOverlay && prefs.floatingAsrEnabled) {
                val i2 = Intent(context, FloatingAsrService::class.java).apply {
                    action = FloatingAsrService.ACTION_SHOW
                }
                context.startService(i2)
            }
        } catch (t: Throwable) {
            Log.w("BootReceiver", "Failed to start overlay services on boot", t)
        }
    }

    /**
     * 尝试使用 Shizuku/Root 方式启动保活服务，失败时回退到普通方式。
     */
    private fun tryStartKeepAlivePrivileged(context: Context) {
        try {
            if (!PrivilegedKeepAliveStarter.tryAcquirePrivilegedStartWindow(context, "boot")) {
                return
            }
            val result = PrivilegedKeepAliveStarter.tryStartKeepAliveByShizuku(context)
                ?: PrivilegedKeepAliveStarter.tryStartKeepAliveByRoot(context)
                ?: PrivilegedKeepAliveStarter.startKeepAliveFallback(context)

            if (result.ok) {
                Log.d("BootReceiver", "Started keep-alive service via ${result.method}")
                DebugLogManager.logPersistent(context, "keepalive", "boot_start_result", mapOf("ok" to true, "method" to result.method.name.lowercase()))
            } else {
                Log.w("BootReceiver", "Privileged keep-alive start failed: ${result.method}, exit=${result.exitCode}, err=${result.stderr}")
                DebugLogManager.logPersistent(
                    context,
                    "keepalive",
                    "boot_start_result",
                    mapOf("ok" to false, "method" to result.method.name.lowercase(), "exit" to result.exitCode),
                )
            }
        } catch (t: Throwable) {
            Log.w("BootReceiver", "tryStartKeepAlivePrivileged failed, falling back", t)
            DebugLogManager.logPersistent(context, "keepalive", "boot_start_exception", mapOf("msg" to t.message))
            try {
                FloatingKeepAliveService.start(context)
                DebugLogManager.logPersistent(context, "keepalive", "boot_start_fallback")
            } catch (t2: Throwable) {
                Log.e("BootReceiver", "Fallback keep-alive start also failed", t2)
                DebugLogManager.logPersistent(context, "keepalive", "boot_start_fallback_failed", mapOf("msg" to t2.message))
            }
        }
    }

    private fun tryEnableAccessibilityIfPermitted(context: Context) {
        // 仅当应用实际拥有 WRITE_SECURE_SETTINGS 时才尝试；普通应用默认无此权限
        val granted = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        try {
            val resolver = context.contentResolver
            val component = ComponentName(context, AsrAccessibilityService::class.java).flattenToString()
            val keyEnabled = Settings.Secure.ACCESSIBILITY_ENABLED
            val keyServices = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

            val current = Settings.Secure.getString(resolver, keyServices) ?: ""
            val parts = current.split(":").filter { it.isNotBlank() }.toMutableSet()
            if (!parts.contains(component)) parts.add(component)
            val newValue = parts.joinToString(":")

            val ok1 = Settings.Secure.putString(resolver, keyServices, newValue)
            val ok2 = Settings.Secure.putInt(resolver, keyEnabled, 1)
            Log.d("BootReceiver", "tryEnableAccessibilityIfPermitted: servicesUpdated=$ok1, enabledSet=$ok2")
        } catch (t: Throwable) {
            Log.e("BootReceiver", "Failed to enable accessibility via secure settings", t)
        }
    }
}
