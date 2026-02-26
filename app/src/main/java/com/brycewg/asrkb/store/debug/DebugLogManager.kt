package com.brycewg.asrkb.store.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量调试日志管理：
 * - JSONL 行日志，写入 noBackupFilesDir/debug/recording.log
 * - start(): 清空并开始录制；stop(): 停止录制但保留文件以便导出
 * - buildShareIntent(): 复制到 cache 并构造分享 Intent（要求 FileProvider 配置）
 *
 * 敏感信息约束：禁止记录识别文本/输入内容/剪贴板内容/密钥等，仅记录状态摘要。
 */
object DebugLogManager {
    private const val TAG = "DebugLogManager"
    private const val DIR_NAME = "debug"
    private const val FILE_NAME = "recording.log"
    private const val MAX_BYTES: Long = 3L * 1024L * 1024L // 3 MB

    @Volatile
    private var recording: Boolean = false

    private var scope: CoroutineScope? = null
    private var writerJob: Job? = null
    private var channel: Channel<String>? = null
    private var output: BufferedOutputStream? = null
    private var logFile: File? = null

    private data class PersistentLogEntry(
        val context: Context,
        val line: String,
    )
    private val persistentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistentChannel = Channel<PersistentLogEntry>(capacity = 256)

    @Volatile
    private var persistentWriterStarted: Boolean = false

    fun isRecording(): Boolean = recording

    @Synchronized
    fun start(context: Context) {
        try {
            if (recording) return
            val dir = File(context.noBackupFilesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, FILE_NAME)
            // 清空旧记录
            if (file.exists()) {
                try {
                    FileOutputStream(file, false).use { /* truncate */ }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to truncate old recording", e)
                }
            }

            val fos = FileOutputStream(file, true)
            output = BufferedOutputStream(fos)
            logFile = file
            channel = Channel(capacity = 256)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            writerJob = scope?.launch {
                try {
                    for (line in channel!!) {
                        writeLine(line)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Writer loop error", t)
                } finally {
                    try {
                        output?.flush()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error flushing output", e)
                    }
                    try {
                        output?.close()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error closing output", e)
                    }
                }
            }
            recording = true
            // 记录环境摘要
            log(
                category = "debug",
                event = "recording_started",
                data = mapOf(
                    "sdk" to Build.VERSION.SDK_INT,
                    "brand" to Build.BRAND,
                    "model" to Build.MODEL,
                    "fingerprint" to safeFingerprint(),
                ),
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start recording", e)
            stop() // 尝试清理
        }
    }

    @Synchronized
    fun stop() {
        try {
            if (!recording) return
            log(category = "debug", event = "recording_stopping")
            recording = false
            try {
                channel?.close()
            } catch (e: Throwable) {
                Log.e(TAG, "Error closing channel", e)
            }
            try {
                writerJob?.cancel()
            } catch (e: Throwable) {
                Log.e(TAG, "Error canceling writer job", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop recording", e)
        } finally {
            channel = null
            writerJob = null
            scope = null
            try {
                output?.close()
            } catch (e: Throwable) {
                Log.e(TAG, "Error closing output in finally", e)
            }
            output = null
        }
    }

    fun log(category: String, event: String, data: Map<String, Any?> = emptyMap()) {
        if (!recording) return
        val ch = channel ?: return
        try {
            val line = buildJsonLine(category, event, data)
            ch.trySend(line).isSuccess
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to enqueue log", e)
        }
    }

    /**
     * 无需手动开始录制也可写入导出日志；若正在录制则复用录制通道。
     */
    fun logPersistent(context: Context, category: String, event: String, data: Map<String, Any?> = emptyMap()) {
        if (recording) {
            log(category, event, data)
            return
        }
        try {
            val line = buildJsonLine(category, event, data)
            ensurePersistentWriter()
            val queued = persistentChannel.trySend(PersistentLogEntry(context.applicationContext, line)).isSuccess
            if (!queued) {
                // 队列瞬时拥塞时，降级到 IO 协程直写，避免主线程阻塞
                persistentScope.launch {
                    appendPersistentLine(context.applicationContext, line)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to append persistent log", e)
        }
    }

    @Synchronized
    private fun ensurePersistentWriter() {
        if (persistentWriterStarted) return
        persistentWriterStarted = true
        persistentScope.launch {
            try {
                for (entry in persistentChannel) {
                    appendPersistentLine(entry.context, entry.line)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Persistent writer loop error", e)
                persistentWriterStarted = false
            }
        }
    }

    @Synchronized
    private fun appendPersistentLine(context: Context, line: String) {
        try {
            val dir = File(context.noBackupFilesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            if (!file.exists()) {
                file.createNewFile()
            }
            if (file.length() > MAX_BYTES) {
                truncateKeepTail(file, keepBytes = 2L * 1024L * 1024L)
            }
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            }
            logFile = file
        } catch (e: Throwable) {
            Log.e(TAG, "Failed writing persistent log line", e)
        }
    }

    /**
     * 复制日志到 cache 并构造分享 Intent。若正在录制，返回 RecordingActive 错误。
     */
    fun buildShareIntent(context: Context): ShareIntentResult {
        try {
            if (recording) return ShareIntentResult.Error(ShareError.RecordingActive)
            val src = logFile ?: File(File(context.noBackupFilesDir, DIR_NAME), FILE_NAME)
            if (!src.exists() || src.length() <= 0) return ShareIntentResult.Error(ShareError.NoLog)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = "asrkb_debug_log_$stamp.txt"
            val dst = File(context.cacheDir, name)
            try {
                FileInputStream(src).use { ins ->
                    FileOutputStream(dst).use { outs ->
                        ins.copyTo(outs)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to copy log to cache", e)
                return ShareIntentResult.Error(ShareError.Failed)
            }

            val uri: Uri = try {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", dst)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to get Uri from FileProvider", e)
                return ShareIntentResult.Error(ShareError.Failed)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ASRKB Debug Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return ShareIntentResult.Success(intent, name)
        } catch (t: Throwable) {
            Log.e(TAG, "Error building share intent", t)
            return ShareIntentResult.Error(ShareError.Failed)
        }
    }

    private fun writeLine(line: String) {
        try {
            val out = output ?: return
            // 尺寸控制：超过上限则截断为末尾 2MB
            val f = logFile
            if (f != null && f.length() > MAX_BYTES) {
                truncateKeepTail(f, keepBytes = 2L * 1024L * 1024L)
            }
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            out.flush()
        } catch (e: Throwable) {
            Log.e(TAG, "Error writing log line", e)
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
                Log.w(TAG, "Failed to delete original during truncate")
            }
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "Failed to rename tmp during truncate")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error truncating log file", e)
        }
    }

    private fun buildJsonLine(category: String, event: String, data: Map<String, Any?>): String {
        val sb = StringBuilder(128)
        sb.append('{')
        appendField(sb, "ts", isoNow())
        sb.append(',')
        appendField(sb, "cat", category)
        sb.append(',')
        appendField(sb, "evt", event)
        for ((k, v) in data) {
            sb.append(',')
            appendFieldName(sb, k)
            sb.append(':')
            appendValue(sb, v)
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendField(sb: StringBuilder, name: String, value: String) {
        appendFieldName(sb, name)
        sb.append(':')
        appendString(sb, value)
    }

    private fun appendFieldName(sb: StringBuilder, name: String) {
        appendString(sb, name)
    }

    private fun appendValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Number, is Boolean -> sb.append(v.toString())
            else -> appendString(sb, v.toString())
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

    private fun isoNow(): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
            sdf.format(Date())
        } catch (_: Throwable) {
            System.currentTimeMillis().toString()
        }
    }

    private fun safeFingerprint(): String {
        return try {
            Build.FINGERPRINT.take(24)
        } catch (_: Throwable) {
            ""
        }
    }

    sealed class ShareIntentResult {
        data class Success(val intent: Intent, val displayName: String) : ShareIntentResult()
        data class Error(val error: ShareError) : ShareIntentResult()
    }

    enum class ShareError { RecordingActive, NoLog, Failed }
}
