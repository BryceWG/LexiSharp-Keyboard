package com.brycewg.asrkb.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.clipboard.EntryType
import com.brycewg.asrkb.clipboard.SyncClipboardManager
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

internal class ImeClipboardCoordinator(
    private val context: Context,
    private val prefs: Prefs,
    private val serviceScope: CoroutineScope,
    private val rootViewProvider: () -> android.view.View?,
    private val actionHandler: KeyboardActionHandler,
    private val isClipboardPanelVisible: () -> Boolean,
    private val refreshClipboardPanelList: () -> Unit,
    private val clipStoreProvider: () -> ClipboardHistoryStore?,
    private val showStatusMessage: (String) -> Unit,
) {
    private var clipboardManager: ClipboardManager? = null
    private var clipboardChangeListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    @Volatile private var lastShownClipboardHash: String? = null

    private var syncClipboardManager: SyncClipboardManager? = null

    fun startClipboardSync() {
        if (prefs.syncClipboardEnabled) {
            if (syncClipboardManager == null) {
                syncClipboardManager = SyncClipboardManager(
                    context,
                    prefs,
                    serviceScope,
                    object : SyncClipboardManager.Listener {
                        override fun onPulledNewContent(text: String) {
                            rootViewProvider()?.post { actionHandler.showClipboardPreview(text) }
                        }

                        override fun onUploadSuccess() {
                            // 成功时不提示
                        }

                        override fun onUploadFailed(reason: String?) {
                            rootViewProvider()?.post {
                                // 失败时短暂提示，然后恢复到剪贴板预览，方便点击粘贴
                                showStatusMessage(context.getString(R.string.sc_status_upload_failed))
                                rootViewProvider()?.postDelayed({ actionHandler.reShowClipboardPreviewIfAny() }, 900)
                            }
                        }

                        override fun onFilePulled(type: EntryType, fileName: String, serverFileName: String) {
                            rootViewProvider()?.post {
                                // 刷新剪贴板列表显示新文件
                                if (isClipboardPanelVisible()) {
                                    refreshClipboardPanelList()
                                }
                                // 在键盘信息栏展示文件预览（文件名 + 格式）
                                val store = clipStoreProvider()
                                if (store != null) {
                                    val all = store.getAll()
                                    val entry = all.firstOrNull {
                                        it.type != EntryType.TEXT &&
                                            (it.serverFileName == serverFileName || it.fileName == fileName)
                                    }
                                    if (entry != null) {
                                        actionHandler.showClipboardFilePreview(entry)
                                    }
                                }
                            }
                        }
                    },
                    clipStoreProvider(),
                )
            }
            syncClipboardManager?.start()
            serviceScope.launch(Dispatchers.IO) {
                syncClipboardManager?.proactiveUploadIfChanged()
                syncClipboardManager?.pullNow(true)
            }
        } else {
            stopClipboardSyncSafely()
        }
    }

    fun stopClipboardSyncSafely() {
        try {
            syncClipboardManager?.stop()
        } catch (t: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to stop SyncClipboardManager", t)
        }
    }

    fun downloadClipboardFile(entry: ClipboardHistoryStore.Entry) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val success = syncClipboardManager?.downloadFile(entry.id) ?: false
                rootViewProvider()?.post {
                    if (success) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.clip_file_download_success),
                            Toast.LENGTH_SHORT,
                        ).show()
                        // 刷新列表显示下载完成状态
                        if (isClipboardPanelVisible()) {
                            refreshClipboardPanelList()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.clip_file_download_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        // 刷新列表显示失败状态
                        if (isClipboardPanelVisible()) {
                            refreshClipboardPanelList()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AsrKeyboardService", "Failed to download file", e)
                rootViewProvider()?.post {
                    Toast.makeText(
                        context,
                        context.getString(R.string.clip_file_download_error, e.message ?: ""),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    fun downloadClipboardFileById(entryId: String) {
        val store = clipStoreProvider() ?: return
        val entry = store.getEntryById(entryId) ?: return
        downloadClipboardFile(entry)
    }

    fun openFile(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.clip_file_not_found),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                // 如果没有应用可以打开，则使用系统分享
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = getMimeType(file)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(shareIntent, context.getString(R.string.clip_file_open_chooser_title)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AsrKeyboardService", "Failed to open file: $filePath", e)
            Toast.makeText(
                context,
                context.getString(R.string.clip_file_open_failed, e.message ?: ""),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun startClipboardPreviewListener() {
        if (clipboardManager == null) {
            clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        }
        if (clipboardChangeListener == null) {
            clipboardChangeListener = ClipboardManager.OnPrimaryClipChangedListener {
                val text = readClipboardText() ?: return@OnPrimaryClipChangedListener
                val h = sha256Hex(text)
                if (h == lastShownClipboardHash) return@OnPrimaryClipChangedListener
                lastShownClipboardHash = h
                // 写入历史
                clipStoreProvider()?.addFromClipboard(text)
                // 若当前面板打开，同步刷新
                if (isClipboardPanelVisible()) refreshClipboardPanelList()
                rootViewProvider()?.post { actionHandler.showClipboardPreview(text) }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardChangeListener!!)
    }

    fun stopClipboardPreviewListener() {
        clipboardManager?.removePrimaryClipChangedListener(clipboardChangeListener)
    }

    fun markShownText(text: String) {
        lastShownClipboardHash = sha256Hex(text)
    }

    fun copyPlainTextToSystemClipboard(label: String, text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (e: Exception) {
            android.util.Log.e("AsrKeyboardService", "Failed to copy text to clipboard", e)
            false
        }
    }

    private fun readClipboardText(): String? {
        val cm = clipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)
        return item.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun sha256Hex(s: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format("%02x", b))
            sb.toString()
        } catch (t: Throwable) {
            android.util.Log.w("AsrKeyboardService", "sha256 failed", t)
            s // fallback: use raw text as hash key
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "*/*"
        }
    }
}
