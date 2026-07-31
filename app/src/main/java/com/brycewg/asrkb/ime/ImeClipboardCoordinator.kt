package com.brycewg.asrkb.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.clipboard.ClipboardSyncRuntimeService
import com.brycewg.asrkb.clipboard.EntryType
import com.brycewg.asrkb.clipboard.SyncClipboardManager
import com.brycewg.asrkb.clipboard.readClipboardText
import com.brycewg.asrkb.store.Prefs
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 主键盘剪贴板 UI 协调（预览/文件）。
 * 自动同步生命周期由 [ClipboardSyncRuntimeService] 拥有，不经 FloatingAsrService。
 */
internal class ImeClipboardCoordinator(
    private val context: Context,
    private val prefs: Prefs,
    private val serviceScope: CoroutineScope,
    private val rootViewProvider: () -> android.view.View?,
    private val actionHandler: KeyboardActionHandler,
    private val isClipboardPanelVisible: () -> Boolean,
    private val refreshClipboardPanelList: () -> Unit,
    private val clipStoreProvider: () -> ClipboardHistoryStore?,
    private val showStatusMessage: (String) -> Unit
) {
    private var clipboardManager: ClipboardManager? = null
    private var clipboardChangeListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    @Volatile private var lastShownClipboardHash: String? = null
    private val clipboardWorkMutex = Mutex()

    private val runtimeUiListener = object : SyncClipboardManager.Listener {
        override fun onPulledNewContent(text: String) {
            rootViewProvider()?.post { actionHandler.showClipboardPreview(text) }
        }

        override fun onUploadSuccess() = Unit

        override fun onUploadFailed(reason: String?) {
            rootViewProvider()?.post {
                showStatusMessage(context.getString(R.string.sc_status_upload_failed))
                rootViewProvider()?.postDelayed({
                    actionHandler.reShowClipboardPreviewIfAny()
                }, 900)
            }
        }

        override fun onFilePulled(type: EntryType, fileName: String, serverFileName: String) {
            rootViewProvider()?.post {
                if (isClipboardPanelVisible()) {
                    refreshClipboardPanelList()
                }
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
    }

    fun activateClipboardSyncRuntime() {
        ClipboardSyncRuntimeService.setUiListener(runtimeUiListener)
        if (prefs.syncClipboardEnabled) {
            ClipboardSyncRuntimeService.activateDirect(context)
        } else {
            ClipboardSyncRuntimeService.deactivateDirect(context)
        }
    }

    fun deactivateClipboardSyncRuntime() {
        ClipboardSyncRuntimeService.deactivateDirect(context)
    }

    fun notifyClipboardSyncConfigChanged() {
        ClipboardSyncRuntimeService.setUiListener(runtimeUiListener)
        ClipboardSyncRuntimeService.notifyConfigChanged(context)
    }

    fun downloadClipboardFile(entry: ClipboardHistoryStore.Entry) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val success = ClipboardSyncRuntimeService.downloadFile(entry.id)
                rootViewProvider()?.post {
                    if (success) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.clip_file_download_success),
                            Toast.LENGTH_SHORT
                        ).show()
                        // 刷新列表显示下载完成状态
                        if (isClipboardPanelVisible()) {
                            refreshClipboardPanelList()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.clip_file_download_failed),
                            Toast.LENGTH_SHORT
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
                        Toast.LENGTH_SHORT
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
            val uri = Uri.parse(filePath)
            val file = if (uri.scheme == "content") null else File(filePath)
            if (file != null && !file.exists()) {
                Toast.makeText(context, context.getString(R.string.clip_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }
            val sharedUri = if (file != null) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                uri
            }
            val mimeType = file?.let(::getMimeType) ?: context.contentResolver.getType(sharedUri) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(sharedUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                // 如果没有应用可以打开，则使用系统分享
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, sharedUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.clip_file_open_chooser_title)
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AsrKeyboardService", "Failed to open file: $filePath", e)
            Toast.makeText(
                context,
                context.getString(R.string.clip_file_open_failed, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun startClipboardPreviewListener() {
        if (clipboardManager == null) {
            clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        }
        if (clipboardChangeListener == null) {
            clipboardChangeListener = ClipboardManager.OnPrimaryClipChangedListener {
                val clipSnapshot = clipboardManager?.primaryClip
                    ?: return@OnPrimaryClipChangedListener
                serviceScope.launch(Dispatchers.Default) {
                    val text = clipboardWorkMutex.withLock {
                        val currentText = readClipboardText(clipSnapshot)
                            ?: return@withLock null
                        val h = sha256Hex(currentText)
                        if (h == lastShownClipboardHash) return@withLock null
                        lastShownClipboardHash = h
                        clipStoreProvider()?.addFromClipboard(currentText)
                        currentText
                    } ?: return@launch
                    withContext(Dispatchers.Main) {
                        if (isClipboardPanelVisible()) refreshClipboardPanelList()
                        actionHandler.showClipboardPreview(text)
                    }
                }
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

    fun copyPlainTextToSystemClipboard(label: String, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    } catch (e: Exception) {
        android.util.Log.e("AsrKeyboardService", "Failed to copy text to clipboard", e)
        false
    }

    private fun sha256Hex(s: String): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        sb.toString()
    } catch (t: Throwable) {
        android.util.Log.w("AsrKeyboardService", "sha256 failed", t)
        s // fallback: use raw text as hash key
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
