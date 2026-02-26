/**
 * Shizuku / root 通用提权能力管理器。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.brycewg.asrkb.BuildConfig
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

internal object PrivilegedAccessManager {

    private const val TAG = "PrivAccess"
    internal const val SHIZUKU_REQUEST_CODE = 4102
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"
    private const val SHIZUKU_PRIVILEGED_API_PACKAGE = "moe.shizuku.privileged.api"

    enum class PrivilegedMethod {
        Shizuku,
        Root,
        None,
    }

    enum class ShizukuPermissionRequestResult {
        AlreadyGranted,
        Requested,
        WaitingForBinder,
        NotInstalled,
        Failed,
    }

    data class ExecResult(
        val ok: Boolean,
        val method: PrivilegedMethod,
        val exitCode: Int? = null,
        val stdout: String? = null,
        val stderr: String? = null,
        val reason: String? = null,
    )

    data class CapabilitySnapshot(
        val shizukuGranted: Boolean,
        val rootAvailable: Boolean,
    ) {
        val hasAny: Boolean
            get() = shizukuGranted || rootAvailable
    }

    private val shizukuInitialized = AtomicBoolean(false)
    private val shizukuBinderReady = AtomicBoolean(false)
    private val shizukuPermissionRequestPending = AtomicBoolean(false)
    private val binderReadyCallbacks = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context? = null) {
        if (!shizukuInitialized.compareAndSet(false, true)) return
        try {
            Shizuku.addBinderReceivedListenerSticky {
                shizukuBinderReady.set(true)
                dispatchBinderReadyCallbacks()
            }
            Shizuku.addBinderDeadListener {
                shizukuBinderReady.set(false)
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "init failed", t)
        }
    }

    fun addOnBinderReadyCallback(callback: () -> Unit) {
        init()
        binderReadyCallbacks.add(callback)
        if (isShizukuBinderReady()) {
            mainHandler.post {
                try {
                    callback()
                } catch (t: Throwable) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "binder ready callback failed", t)
                }
            }
        }
    }

    fun removeOnBinderReadyCallback(callback: () -> Unit) {
        binderReadyCallbacks.remove(callback)
    }

    private fun dispatchBinderReadyCallbacks() {
        binderReadyCallbacks.forEach { callback ->
            mainHandler.post {
                try {
                    callback()
                } catch (t: Throwable) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "dispatch callback failed", t)
                }
            }
        }
    }

    fun snapshotCapabilities(context: Context): CapabilitySnapshot {
        val app = context.applicationContext
        return CapabilitySnapshot(
            shizukuGranted = isShizukuGranted(app),
            rootAvailable = isRootProbablyAvailable(),
        )
    }

    fun hasPrivilegedCapability(context: Context): Boolean {
        return snapshotCapabilities(context).hasAny
    }

    fun isShizukuManagerInstalled(context: Context): Boolean {
        return getPackageInfoOrNull(context.packageManager, SHIZUKU_MANAGER_PACKAGE) != null
    }

    fun isShizukuPrivilegedApiInstalled(context: Context): Boolean {
        return getPackageInfoOrNull(context.packageManager, SHIZUKU_PRIVILEGED_API_PACKAGE) != null
    }

    fun isShizukuBinderReady(): Boolean {
        init()
        if (shizukuBinderReady.get()) return true
        val ready = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (ready) shizukuBinderReady.set(true)
        return ready
    }

    fun isShizukuGranted(context: Context): Boolean {
        init(context.applicationContext)
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestShizukuPermission(context: Context): ShizukuPermissionRequestResult {
        val app = context.applicationContext
        init(app)
        if (!isShizukuManagerInstalled(app) && !isShizukuPrivilegedApiInstalled(app)) {
            return ShizukuPermissionRequestResult.NotInstalled
        }
        if (isShizukuGranted(app)) {
            return ShizukuPermissionRequestResult.AlreadyGranted
        }

        if (!isShizukuBinderReady()) {
            enqueueShizukuPermissionRequest()
            try {
                Shizuku.pingBinder()
            } catch (_: Throwable) {
            }
            return ShizukuPermissionRequestResult.WaitingForBinder
        }

        return if (requestShizukuPermissionNow()) {
            ShizukuPermissionRequestResult.Requested
        } else {
            ShizukuPermissionRequestResult.Failed
        }
    }

    private fun enqueueShizukuPermissionRequest() {
        if (!shizukuPermissionRequestPending.compareAndSet(false, true)) return
        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                try {
                    Shizuku.removeBinderReceivedListener(this)
                } catch (_: Throwable) {
                }
                shizukuPermissionRequestPending.set(false)
                mainHandler.post { requestShizukuPermissionNow() }
            }
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
        } catch (t: Throwable) {
            shizukuPermissionRequestPending.set(false)
            if (BuildConfig.DEBUG) Log.d(TAG, "enqueue permission request failed", t)
        }
    }

    private fun requestShizukuPermissionNow(): Boolean {
        return try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                true
            } else {
                true
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "request permission failed", t)
            false
        }
    }

    fun isRootProbablyAvailable(): Boolean {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
        )
        if (candidates.any { File(it).exists() }) return true

        val path = System.getenv("PATH") ?: return false
        return path.split(":").any { dir ->
            if (dir.isBlank()) return@any false
            File(dir, "su").exists()
        }
    }

    fun runShellPreferShizuku(context: Context, command: String): ExecResult? {
        val app = context.applicationContext
        val shizukuResult = runShellByShizuku(app, command)
        if (shizukuResult?.ok == true) return shizukuResult
        val rootResult = runShellByRoot(app, command)
        return rootResult ?: shizukuResult
    }

    fun runShellByShizuku(context: Context, command: String): ExecResult? {
        val app = context.applicationContext
        if (!isShizukuGranted(app)) return null
        return runShizukuShell(command)
    }

    fun runShellByRoot(context: Context, command: String): ExecResult? {
        if (!isRootProbablyAvailable()) return null
        return runRootShell(command)
    }

    private fun runShizukuShell(command: String): ExecResult? {
        return try {
            val process = createShizukuProcess(arrayOf("sh", "-c", command)) ?: return null
            val stdout = process.inputStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            val stderr = process.errorStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            val exit = try {
                process.waitFor()
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Shizuku process waitFor failed", t)
                null
            }
            ExecResult(
                ok = exit == null || exit == 0,
                method = PrivilegedMethod.Shizuku,
                exitCode = exit,
                stdout = stdout.ifBlank { null },
                stderr = stderr.ifBlank { null },
                reason = if (exit != null && exit != 0 && stderr.isBlank()) "non_zero_exit" else null,
            )
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "runShizukuShell failed", t)
            null
        }
    }

    @Suppress("PrivateApi")
    private fun createShizukuProcess(cmd: Array<String>): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? Process
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Shizuku.newProcess reflection failed", t)
            null
        }
    }

    private fun runRootShell(command: String): ExecResult? {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val stdout = process.inputStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            val stderr = process.errorStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            val exit = try {
                process.waitFor()
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Log.d(TAG, "root process waitFor failed", t)
                null
            }
            ExecResult(
                ok = exit == null || exit == 0,
                method = PrivilegedMethod.Root,
                exitCode = exit,
                stdout = stdout.ifBlank { null },
                stderr = stderr.ifBlank { null },
                reason = if (exit != null && exit != 0 && stderr.isBlank()) "non_zero_exit" else null,
            )
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "runRootShell failed", t)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoOrNull(packageManager: PackageManager, packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
