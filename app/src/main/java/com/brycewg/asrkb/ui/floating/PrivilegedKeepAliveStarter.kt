/**
 * Shizuku / root 启动前台保活服务的适配层。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object PrivilegedKeepAliveStarter {

    private const val TAG = "PrivKeepAlive"
    internal const val SHIZUKU_REQUEST_CODE_KEEP_ALIVE = PrivilegedAccessManager.SHIZUKU_REQUEST_CODE
    private const val START_COOLDOWN_MS = 3500L

    private val keepAliveBinderReadyHookInstalled = AtomicBoolean(false)
    private val lastPrivilegedStartAtMs = AtomicLong(0L)

    enum class ShizukuPermissionRequestResult {
        AlreadyGranted,
        Requested,
        WaitingForBinder,
        NotInstalled,
        Failed,
    }

    enum class StartMethod {
        Shizuku,
        Root,
        Normal,
    }

    data class StartResult(
        val ok: Boolean,
        val method: StartMethod,
        val exitCode: Int? = null,
        val stderr: String? = null,
    )

    private val binderReadyCallback: () -> Unit = callback@{
        val ctx = appContextForBinderReady ?: return@callback
        try {
            tryStartByShizukuWhenBinderReady(ctx)
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "binder callback failed", t)
        }
    }

    @Volatile
    private var appContextForBinderReady: Context? = null

    fun initShizuku(context: Context? = null) {
        val app = context?.applicationContext
        if (app != null) {
            appContextForBinderReady = app
        }
        PrivilegedAccessManager.init(app)
        if (keepAliveBinderReadyHookInstalled.compareAndSet(false, true)) {
            PrivilegedAccessManager.addOnBinderReadyCallback(binderReadyCallback)
        }
    }

    fun isShizukuManagerInstalled(context: Context): Boolean {
        return PrivilegedAccessManager.isShizukuManagerInstalled(context)
    }

    fun isShizukuPrivilegedApiInstalled(context: Context): Boolean {
        return PrivilegedAccessManager.isShizukuPrivilegedApiInstalled(context)
    }

    fun isShizukuBinderReady(): Boolean {
        initShizuku()
        return PrivilegedAccessManager.isShizukuBinderReady()
    }

    fun isShizukuGranted(context: Context): Boolean {
        initShizuku(context)
        return PrivilegedAccessManager.isShizukuGranted(context)
    }

    fun requestShizukuPermission(context: Context): ShizukuPermissionRequestResult {
        initShizuku(context)
        return when (PrivilegedAccessManager.requestShizukuPermission(context)) {
            PrivilegedAccessManager.ShizukuPermissionRequestResult.AlreadyGranted -> ShizukuPermissionRequestResult.AlreadyGranted
            PrivilegedAccessManager.ShizukuPermissionRequestResult.Requested -> ShizukuPermissionRequestResult.Requested
            PrivilegedAccessManager.ShizukuPermissionRequestResult.WaitingForBinder -> ShizukuPermissionRequestResult.WaitingForBinder
            PrivilegedAccessManager.ShizukuPermissionRequestResult.NotInstalled -> ShizukuPermissionRequestResult.NotInstalled
            PrivilegedAccessManager.ShizukuPermissionRequestResult.Failed -> ShizukuPermissionRequestResult.Failed
        }
    }

    private fun tryStartByShizukuWhenBinderReady(context: Context) {
        try {
            val prefs = Prefs(context)
            if (!prefs.floatingKeepAliveEnabled || !prefs.floatingKeepAlivePrivilegedEnabled) {
                return
            }
            if (!isShizukuGranted(context)) {
                DebugLogManager.logPersistent(context, "keepalive", "shizuku_ready_skip", mapOf("reason" to "permission_denied"))
                return
            }
            if (!tryAcquirePrivilegedStartWindow(context, "shizuku_ready")) {
                return
            }
            val result = tryStartKeepAliveByShizuku(context)
            DebugLogManager.logPersistent(
                context,
                "keepalive",
                "shizuku_ready_result",
                mapOf("ok" to (result?.ok == true), "method" to (result?.method?.name?.lowercase() ?: "none"), "exit" to result?.exitCode),
            )
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "tryStartByShizukuWhenBinderReady failed", t)
            DebugLogManager.logPersistent(context, "keepalive", "shizuku_ready_error", mapOf("msg" to t.message))
        }
    }

    fun isRootProbablyAvailable(): Boolean {
        return PrivilegedAccessManager.isRootProbablyAvailable()
    }

    fun startKeepAliveServiceIntent(context: Context): Intent {
        val token = FloatingKeepAliveService.getOrCreateCallerToken(context)
        return Intent(context, FloatingKeepAliveService::class.java).apply {
            action = FloatingKeepAliveService.ACTION_START
            putExtra(FloatingKeepAliveService.EXTRA_CALLER_TOKEN, token)
        }
    }

    fun startKeepAliveForegroundServiceCommand(context: Context): String {
        val component = "${context.packageName}/${FloatingKeepAliveService::class.java.name}"
        val token = FloatingKeepAliveService.getOrCreateCallerToken(context)
        return buildString {
            append("am start-foreground-service")
            append(" -n ")
            append(component)
            append(" -a ")
            append(FloatingKeepAliveService.ACTION_START)
            append(" --es ")
            append(FloatingKeepAliveService.EXTRA_CALLER_TOKEN)
            append(" ")
            append(token)
        }
    }

    /**
     * 获取增强拉起冷却窗口，避免 BootReceiver/JobService 在同一时段重复触发。
     */
    fun tryAcquirePrivilegedStartWindow(context: Context, source: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val last = lastPrivilegedStartAtMs.get()
            if (last > 0 && now - last < START_COOLDOWN_MS) {
                DebugLogManager.logPersistent(
                    context,
                    "keepalive",
                    "start_cooldown_skip",
                    mapOf("source" to source, "remainMs" to (START_COOLDOWN_MS - (now - last))),
                )
                return false
            }
            if (lastPrivilegedStartAtMs.compareAndSet(last, now)) {
                return true
            }
        }
    }

    fun tryStartKeepAliveByShizuku(context: Context): StartResult? {
        if (!isShizukuGranted(context)) {
            DebugLogManager.logPersistent(context, "keepalive", "start_skip", mapOf("method" to "shizuku", "reason" to "permission_denied"))
            return null
        }
        DebugLogManager.logPersistent(context, "keepalive", "start_attempt", mapOf("method" to "shizuku"))
        val cmd = startKeepAliveForegroundServiceCommand(context)
        val result = PrivilegedAccessManager.runShellByShizuku(context, cmd)
            ?: StartResult(ok = false, method = StartMethod.Shizuku, stderr = "Shizuku newProcess failed")
                .toExecFallback()
        val mapped = result.toStartResult()
        DebugLogManager.logPersistent(
            context,
            "keepalive",
            "start_result",
            mapOf(
                "method" to "shizuku",
                "ok" to mapped.ok,
                "exit" to mapped.exitCode,
                "err" to mapped.stderr.safeLogSnippet(),
            ),
        )
        return mapped
    }

    fun tryStartKeepAliveByRoot(context: Context): StartResult? {
        if (!isRootProbablyAvailable()) {
            DebugLogManager.logPersistent(context, "keepalive", "start_skip", mapOf("method" to "root", "reason" to "su_not_found"))
            return null
        }
        DebugLogManager.logPersistent(context, "keepalive", "start_attempt", mapOf("method" to "root"))
        val cmd = startKeepAliveForegroundServiceCommand(context)
        val result = PrivilegedAccessManager.runShellByRoot(context, cmd)
            ?: StartResult(ok = false, method = StartMethod.Root, stderr = "su exec failed")
                .toExecFallback()
        val mapped = result.toStartResult()
        DebugLogManager.logPersistent(
            context,
            "keepalive",
            "start_result",
            mapOf(
                "method" to "root",
                "ok" to mapped.ok,
                "exit" to mapped.exitCode,
                "err" to mapped.stderr.safeLogSnippet(),
            ),
        )
        return mapped
    }

    fun startKeepAliveFallback(context: Context): StartResult {
        DebugLogManager.logPersistent(context, "keepalive", "start_attempt", mapOf("method" to "normal"))
        return try {
            FloatingKeepAliveService.start(context)
            StartResult(ok = true, method = StartMethod.Normal).also {
                DebugLogManager.logPersistent(context, "keepalive", "start_result", mapOf("method" to "normal", "ok" to true))
            }
        } catch (t: Throwable) {
            StartResult(ok = false, method = StartMethod.Normal, stderr = t.message).also {
                DebugLogManager.logPersistent(
                    context,
                    "keepalive",
                    "start_result",
                    mapOf("method" to "normal", "ok" to false, "err" to t.message.safeLogSnippet()),
                )
            }
        }
    }

    private fun String?.safeLogSnippet(): String? {
        if (this.isNullOrBlank()) return null
        return this.take(160)
    }

    private fun StartResult.toExecFallback(): PrivilegedAccessManager.ExecResult {
        return PrivilegedAccessManager.ExecResult(
            ok = ok,
            method = when (method) {
                StartMethod.Shizuku -> PrivilegedAccessManager.PrivilegedMethod.Shizuku
                StartMethod.Root -> PrivilegedAccessManager.PrivilegedMethod.Root
                StartMethod.Normal -> PrivilegedAccessManager.PrivilegedMethod.None
            },
            exitCode = exitCode,
            stderr = stderr,
        )
    }

    private fun PrivilegedAccessManager.ExecResult.toStartResult(): StartResult {
        val mappedMethod = when (method) {
            PrivilegedAccessManager.PrivilegedMethod.Shizuku -> StartMethod.Shizuku
            PrivilegedAccessManager.PrivilegedMethod.Root -> StartMethod.Root
            PrivilegedAccessManager.PrivilegedMethod.None -> StartMethod.Normal
        }
        val mergedErr = stderr ?: reason
        return StartResult(ok = ok, method = mappedMethod, exitCode = exitCode, stderr = mergedErr)
    }
}
