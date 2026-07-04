/**
 * 诊断日志的本地写入、导出与详细支持日志状态管理。
 *
 * 归属模块：store/debug
 */
package com.brycewg.asrkb.store.debug

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.brycewg.asrkb.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 统一诊断日志管理：
 * - 基础诊断日志：默认常驻，始终写入本地。
 * - 详细支持日志：手动开启后，额外记录更细粒度的链路信息。
 * - 导出：始终导出当前日志快照，无需先停止详细日志。
 *
 * 敏感信息约束：禁止记录识别文本/输入内容/剪贴板内容/密钥/完整请求响应正文。
 */
object DebugLogManager {
    private const val TAG = "DebugLogManager"
    private const val DIR_NAME = "debug"
    private const val FILE_NAME = "diagnostics.jsonl"
    private const val LEGACY_FILE_NAME = "recording.log"
    private const val MAX_BYTES: Long = 3L * 1024L * 1024L
    private const val KEEP_BYTES: Long = 2L * 1024L * 1024L
    private const val META_PREFS = "diagnostic_log_meta"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"
    private const val KEY_LAST_EXIT_REASON = "last_exit_reason"
    private const val KEY_LAST_EXIT_DESC = "last_exit_desc"
    private const val KEY_LAST_EXIT_IMPORTANCE = "last_exit_importance"
    private const val KEY_LAST_EXIT_STATUS = "last_exit_status"
    private const val KEY_LAST_EXIT_LABEL = "last_exit_label"
    private const val KEY_LAST_HANDLED_EXIT_TS = "last_handled_exit_ts"
    private const val KEY_CLEAR_ON_NEXT_VERBOSE_START = "clear_on_next_verbose_start"
    private const val MAX_STRING_VALUE = 240
    private const val MAX_ERROR_STACK = 512

    private enum class Stream(val value: String) {
        BASE("base"),
        VERBOSE("verbose")
    }

    data class LastExitInfo(
        val reason: Int,
        val reasonLabel: String,
        val timestamp: Long,
        val status: Int,
        val importance: Int,
        val description: String?
    )

    private sealed interface PendingWrite {
        val context: Context

        data class Line(
            override val context: Context,
            val line: String
        ) : PendingWrite

        data class ClearLogs(
            override val context: Context
        ) : PendingWrite

        data class Barrier(
            override val context: Context,
            val latch: CountDownLatch
        ) : PendingWrite
    }

    @Volatile
    private var recording: Boolean = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sessionId: String = ""

    @Volatile
    private var processName: String = ""

    @Volatile
    private var versionName: String = ""

    @Volatile
    private var versionCode: Long = 0L

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var uncaughtHandlerInstalled: Boolean = false

    @Volatile
    private var writerStarted: Boolean = false

    @Volatile
    private var latestExitInfo: LastExitInfo? = null

    @Volatile
    private var shareUriResolverForTest: ((Context, File) -> Uri)? = null

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeChannel = Channel<PendingWrite>(capacity = Channel.UNLIMITED)

    fun initialize(context: Context) {
        val app = context.applicationContext
        val firstInit = synchronized(this) {
            if (!initialized) {
                appContext = app
                sessionId = UUID.randomUUID().toString().take(12)
                processName = resolveProcessName(app)
                populatePackageInfo(app)
                ensureWriterStarted()
                initialized = true
                true
            } else {
                if (appContext == null) appContext = app
                false
            }
        }
        if (firstInit) {
            logBase(
                context = app,
                category = "app",
                event = "session_started",
                data = mapOf(
                    "brand" to Build.BRAND,
                    "model" to Build.MODEL,
                    "fingerprint" to safeFingerprint()
                )
            )
        }
    }

    fun isRecording(): Boolean = recording

    internal fun resetForTest(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            appContext = app
            sessionId = "test-session"
            processName = app.packageName
            versionName = BuildConfig.VERSION_NAME
            versionCode = BuildConfig.VERSION_CODE.toLong()
            recording = false
            initialized = true
            latestExitInfo = null
            shareUriResolverForTest = null
            ensureWriterStarted()
        }
        flushForTest(app)
        metaPrefs(app).edit().clear().commit()
        clearLogFilesDirect(app)
    }

    internal fun setShareUriResolverForTest(resolver: ((Context, File) -> Uri)?) {
        shareUriResolverForTest = resolver
    }

    internal fun flushForTest(context: Context, timeoutMs: Long = 5_000L): Boolean {
        return awaitPendingWrites(context, timeoutMs)
    }

    @Synchronized
    fun start(context: Context) {
        initialize(context)
        if (recording) return
        clearLogsIfNeededBeforeVerboseStart(context.applicationContext)
        recording = true
        updateProcessStateSummary("mode=verbose;support=1")
        logBase(
            context = context.applicationContext,
            category = "support",
            event = "verbose_enabled"
        )
        log(
            category = "support",
            event = "verbose_session_started",
            data = mapOf(
                "sdk" to Build.VERSION.SDK_INT,
                "brand" to Build.BRAND,
                "model" to Build.MODEL
            )
        )
    }

    @Synchronized
    fun stop() {
        if (!recording) return
        recording = false
        updateProcessStateSummary("mode=base;support=0")
        logBase(category = "support", event = "verbose_disabled")
    }

    /**
     * 兼容旧调用：详细支持日志，仅在手动开启后写入。
     */
    fun log(category: String, event: String, data: Map<String, Any?> = emptyMap()) {
        if (!recording) return
        val context = appContext ?: return
        enqueueLine(
            context = context,
            line = buildJsonLine(
                stream = Stream.VERBOSE,
                level = "debug",
                category = category,
                event = event,
                data = data
            )
        )
    }

    /**
     * 兼容旧调用：基础诊断日志，默认始终写入本地。
     */
    fun logPersistent(
        context: Context,
        category: String,
        event: String,
        data: Map<String, Any?> = emptyMap()
    ) {
        logBase(context, category, event, data)
    }

    fun logBase(
        context: Context,
        category: String,
        event: String,
        data: Map<String, Any?> = emptyMap()
    ) {
        initialize(context)
        enqueueLine(
            context = context.applicationContext,
            line = buildJsonLine(
                stream = Stream.BASE,
                level = "info",
                category = category,
                event = event,
                data = data
            )
        )
    }

    fun logBase(
        category: String,
        event: String,
        data: Map<String, Any?> = emptyMap()
    ) {
        val context = appContext ?: return
        logBase(context, category, event, data)
    }

    fun logWarning(
        context: Context,
        category: String,
        event: String,
        throwable: Throwable? = null,
        data: Map<String, Any?> = emptyMap()
    ) {
        initialize(context)
        enqueueLine(
            context = context.applicationContext,
            line = buildJsonLine(
                stream = Stream.BASE,
                level = "warn",
                category = category,
                event = event,
                data = data,
                throwable = throwable
            )
        )
    }

    fun logWarning(
        category: String,
        event: String,
        throwable: Throwable? = null,
        data: Map<String, Any?> = emptyMap()
    ) {
        val context = appContext ?: return
        logWarning(context, category, event, throwable, data)
    }

    fun logError(
        context: Context,
        category: String,
        event: String,
        throwable: Throwable? = null,
        data: Map<String, Any?> = emptyMap()
    ) {
        initialize(context)
        enqueueLine(
            context = context.applicationContext,
            line = buildJsonLine(
                stream = Stream.BASE,
                level = "error",
                category = category,
                event = event,
                data = data,
                throwable = throwable
            )
        )
    }

    fun logError(
        category: String,
        event: String,
        throwable: Throwable? = null,
        data: Map<String, Any?> = emptyMap()
    ) {
        val context = appContext ?: return
        logError(context, category, event, throwable, data)
    }

    fun logFatal(
        context: Context,
        category: String,
        event: String,
        throwable: Throwable,
        data: Map<String, Any?> = emptyMap()
    ) {
        initialize(context)
        appendLineDirect(
            context = context.applicationContext,
            line = buildJsonLine(
                stream = Stream.BASE,
                level = "fatal",
                category = category,
                event = event,
                data = data,
                throwable = throwable
            )
        )
    }

    fun installUncaughtExceptionHandler(context: Context) {
        initialize(context)
        synchronized(this) {
            if (uncaughtHandlerInstalled) return
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    logFatal(
                        context = app,
                        category = "crash",
                        event = "uncaught_exception",
                        throwable = throwable,
                        data = mapOf(
                            "thread" to thread.name,
                            "mainThread" to (thread == Looper.getMainLooper().thread)
                        )
                    )
                } catch (logErr: Throwable) {
                    Log.e(TAG, "Failed to persist uncaught exception", logErr)
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    thread.threadGroup?.uncaughtException(thread, throwable)
                }
            }
            uncaughtHandlerInstalled = true
        }
    }

    fun inspectHistoricalProcessExit(context: Context) {
        initialize(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            val exits = activityManager.getHistoricalProcessExitReasons(null, 0, 8)
            val latest = exits.firstOrNull { isInterestingExitReason(it.reason) } ?: return
            val timestamp = latest.timestamp
            if (timestamp <= 0L) return
            val prefs = metaPrefs(context)
            val handledTs = prefs.getLong(KEY_LAST_HANDLED_EXIT_TS, 0L)
            val info = latest.toLastExitInfo()
            latestExitInfo = info
            persistLatestExitInfo(context, info)
            if (timestamp == handledTs) return
            prefs.edit().putLong(KEY_LAST_HANDLED_EXIT_TS, timestamp).apply()
            logBase(
                context = context.applicationContext,
                category = "app",
                event = "prev_process_exit",
                data = mapOf(
                    "reason" to info.reasonLabel,
                    "reasonCode" to info.reason,
                    "status" to info.status,
                    "importance" to info.importance,
                    "timestamp" to info.timestamp,
                    "description" to info.description
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to inspect historical process exit reasons", t)
        }
    }

    fun getLatestExitInfo(context: Context): LastExitInfo? {
        latestExitInfo?.let { return it }
        val prefs = metaPrefs(context)
        val timestamp = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
        if (timestamp <= 0L) return null
        return LastExitInfo(
            reason = prefs.getInt(KEY_LAST_EXIT_REASON, 0),
            reasonLabel = prefs.getString(KEY_LAST_EXIT_LABEL, null) ?: return null,
            timestamp = timestamp,
            status = prefs.getInt(KEY_LAST_EXIT_STATUS, 0),
            importance = prefs.getInt(KEY_LAST_EXIT_IMPORTANCE, 0),
            description = prefs.getString(KEY_LAST_EXIT_DESC, null)
        ).also { latestExitInfo = it }
    }

    fun updateProcessStateSummary(summary: String) {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) ?: return
            val method = activityManager.javaClass.methods.firstOrNull {
                it.name == "setProcessStateSummary" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == ByteArray::class.java
            } ?: return
            val bytes = summary.take(96).toByteArray(Charsets.UTF_8)
            method.invoke(activityManager, bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update process state summary", t)
        }
    }

    fun buildShareIntent(context: Context): ShareIntentResult {
        initialize(context)
        try {
            if (!awaitPendingWrites(context.applicationContext)) {
                Log.e(TAG, "Timed out waiting for diagnostics writer before export")
                return ShareIntentResult.Error(ShareError.Failed)
            }
            val src = activeLogFile(context)
            if (!src.exists() || src.length() <= 0) return ShareIntentResult.Error(ShareError.NoLog)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = "asrkb_diagnostics_$stamp.jsonl"
            val dst = File(context.cacheDir, name)
            try {
                FileInputStream(src).use { ins ->
                    FileOutputStream(dst).use { outs ->
                        ins.copyTo(outs)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to copy log snapshot to cache", e)
                return ShareIntentResult.Error(ShareError.Failed)
            }

            val uri: Uri = try {
                resolveShareUri(context, dst)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to get Uri from FileProvider", e)
                return ShareIntentResult.Error(ShareError.Failed)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ASRKB Diagnostics Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            markClearOnNextVerboseStart(context)
            return ShareIntentResult.Success(intent, name)
        } catch (t: Throwable) {
            Log.e(TAG, "Error building share intent", t)
            return ShareIntentResult.Error(ShareError.Failed)
        }
    }

    @Synchronized
    private fun ensureWriterStarted() {
        if (writerStarted) return
        writerStarted = true
        writeScope.launch {
            try {
                for (pending in writeChannel) {
                    when (pending) {
                        is PendingWrite.Line -> appendLineDirect(pending.context, pending.line)
                        is PendingWrite.ClearLogs -> clearLogFilesDirect(pending.context)
                        is PendingWrite.Barrier -> pending.latch.countDown()
                    }
                }
            } catch (t: Throwable) {
                writerStarted = false
                Log.e(TAG, "Diagnostics writer loop failed", t)
            }
        }
    }

    private fun enqueueLine(context: Context, line: String) {
        ensureWriterStarted()
        val queued = writeChannel.trySend(PendingWrite.Line(context.applicationContext, line)).isSuccess
        if (!queued) appendLineDirect(context.applicationContext, line)
    }

    private fun enqueueClearLogs(context: Context) {
        ensureWriterStarted()
        val queued = writeChannel.trySend(PendingWrite.ClearLogs(context.applicationContext)).isSuccess
        if (!queued) clearLogFilesDirect(context.applicationContext)
    }

    private fun awaitPendingWrites(context: Context, timeoutMs: Long = 5_000L): Boolean {
        val latch = CountDownLatch(1)
        ensureWriterStarted()
        val queued = writeChannel.trySend(PendingWrite.Barrier(context.applicationContext, latch)).isSuccess
        return if (queued) latch.await(timeoutMs, TimeUnit.MILLISECONDS) else false
    }

    private fun resolveShareUri(context: Context, file: File): Uri {
        shareUriResolverForTest?.let { return it(context, file) }
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    @Synchronized
    private fun appendLineDirect(context: Context, line: String) {
        try {
            val file = activeLogFile(context)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            if (!file.exists()) file.createNewFile()
            if (file.length() > MAX_BYTES) {
                truncateKeepTail(file, KEEP_BYTES)
            }
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing diagnostics line", t)
        }
    }

    private fun activeLogFile(context: Context): File {
        val dir = File(context.noBackupFilesDir, DIR_NAME)
        val file = File(dir, FILE_NAME)
        if (file.exists()) return file
        val legacy = File(dir, LEGACY_FILE_NAME)
        return if (legacy.exists()) legacy else file
    }

    private fun clearLogsIfNeededBeforeVerboseStart(context: Context) {
        val prefs = metaPrefs(context)
        if (!prefs.getBoolean(KEY_CLEAR_ON_NEXT_VERBOSE_START, false)) return
        prefs.edit().putBoolean(KEY_CLEAR_ON_NEXT_VERBOSE_START, false).apply()
        enqueueClearLogs(context)
    }

    private fun markClearOnNextVerboseStart(context: Context) {
        metaPrefs(context).edit()
            .putBoolean(KEY_CLEAR_ON_NEXT_VERBOSE_START, true)
            .apply()
    }

    @Synchronized
    private fun clearLogFilesDirect(context: Context) {
        try {
            val dir = File(context.noBackupFilesDir, DIR_NAME)
            val files = listOf(File(dir, FILE_NAME), File(dir, LEGACY_FILE_NAME))
            files.forEach { file ->
                if (file.exists() && !file.delete()) {
                    FileOutputStream(file, false).use { it.flush() }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed clearing diagnostics log files", t)
        }
    }

    private fun truncateKeepTail(file: File, keepBytes: Long) {
        try {
            val size = file.length()
            if (size <= keepBytes) return
            val tmp = File(file.parentFile, file.name + ".tmp")
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(size - keepBytes)
                FileOutputStream(tmp).use { outs ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = raf.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n)
                    }
                }
            }
            if (!file.delete()) {
                Log.w(TAG, "Failed to delete original diagnostics file during truncate")
            }
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "Failed to rename diagnostics tmp file during truncate")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to truncate diagnostics log file", t)
        }
    }

    private fun buildJsonLine(
        stream: Stream,
        level: String,
        category: String,
        event: String,
        data: Map<String, Any?>,
        throwable: Throwable? = null
    ): String {
        val sb = StringBuilder(256)
        sb.append('{')
        appendField(sb, "ts", isoNow())
        sb.append(',')
        appendField(sb, "lvl", level)
        sb.append(',')
        appendField(sb, "stream", stream.value)
        sb.append(',')
        appendField(sb, "sid", sessionId.ifBlank { "unknown" })
        sb.append(',')
        appendField(sb, "proc", processName.ifBlank { "unknown" })
        sb.append(',')
        appendField(sb, "ver", versionName.ifBlank { BuildConfig.VERSION_NAME })
        sb.append(',')
        appendFieldName(sb, "vc")
        sb.append(':')
        appendValue(sb, versionCode.takeIf { it > 0 } ?: BuildConfig.VERSION_CODE)
        sb.append(',')
        appendFieldName(sb, "sdk")
        sb.append(':')
        appendValue(sb, Build.VERSION.SDK_INT)
        sb.append(',')
        appendField(sb, "cat", category)
        sb.append(',')
        appendField(sb, "evt", event)
        for ((key, value) in data) {
            sb.append(',')
            appendFieldName(sb, key)
            sb.append(':')
            appendValue(sb, normalizeValue(value))
        }
        throwable?.let {
            sb.append(',')
            appendField(sb, "errType", it.javaClass.simpleName.ifBlank { it.javaClass.name })
            sb.append(',')
            appendField(sb, "errMsg", safeText(it.message))
            sb.append(',')
            appendField(sb, "errTop", safeText(it.stackTrace.firstOrNull()?.toString()))
            sb.append(',')
            appendField(sb, "errStack", summarizeStack(it))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        null -> null
        is Number, is Boolean -> value
        else -> safeText(value.toString())
    }

    private fun appendField(sb: StringBuilder, name: String, value: String?) {
        appendFieldName(sb, name)
        sb.append(':')
        if (value == null) {
            sb.append("null")
        } else {
            appendString(sb, value)
        }
    }

    private fun appendFieldName(sb: StringBuilder, name: String) {
        appendString(sb, name)
    }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Number, is Boolean -> sb.append(value.toString())
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    private fun safeText(text: String?): String? = text
        ?.replace('\u0000', ' ')
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_STRING_VALUE)
        ?.ifBlank { null }

    private fun summarizeStack(throwable: Throwable): String? {
        val summary = buildString {
            throwable.stackTrace.take(5).forEachIndexed { index, element ->
                if (index > 0) append(" | ")
                append(element.className.substringAfterLast('.'))
                append(':')
                append(element.methodName)
                append(':')
                append(element.lineNumber)
            }
        }.take(MAX_ERROR_STACK)
        return summary.ifBlank { null }
    }

    private fun resolveProcessName(context: Context): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            context.packageName
        }
    } catch (_: Throwable) {
        context.packageName
    }

    private fun populatePackageInfo(context: Context) {
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            versionName = info.versionName ?: BuildConfig.VERSION_NAME
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (t: Throwable) {
            versionName = BuildConfig.VERSION_NAME
            versionCode = BuildConfig.VERSION_CODE.toLong()
            Log.w(TAG, "Failed to read package info for diagnostics", t)
        }
    }

    private fun persistLatestExitInfo(context: Context, info: LastExitInfo) {
        metaPrefs(context).edit()
            .putLong(KEY_LAST_EXIT_TS, info.timestamp)
            .putInt(KEY_LAST_EXIT_REASON, info.reason)
            .putInt(KEY_LAST_EXIT_STATUS, info.status)
            .putInt(KEY_LAST_EXIT_IMPORTANCE, info.importance)
            .putString(KEY_LAST_EXIT_LABEL, info.reasonLabel)
            .putString(KEY_LAST_EXIT_DESC, info.description)
            .apply()
    }

    private fun metaPrefs(context: Context) = context.applicationContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    private fun isInterestingExitReason(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_SIGNALED -> true
        else -> false
    }

    private fun ApplicationExitInfo.toLastExitInfo(): LastExitInfo = LastExitInfo(
        reason = reason,
        reasonLabel = reasonLabel(reason),
        timestamp = timestamp,
        status = status,
        importance = importance,
        description = safeText(description)
    )

    private fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        else -> "reason_$reason"
    }

    private fun isoNow(): String = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
        sdf.format(Date())
    } catch (_: Throwable) {
        System.currentTimeMillis().toString()
    }

    private fun safeFingerprint(): String = try {
        Build.FINGERPRINT.take(24)
    } catch (_: Throwable) {
        ""
    }

    sealed class ShareIntentResult {
        data class Success(val intent: Intent, val displayName: String) : ShareIntentResult()
        data class Error(val error: ShareError) : ShareIntentResult()
    }

    enum class ShareError { RecordingActive, NoLog, Failed }
}
