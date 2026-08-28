/** 扫描用户授权目录中的新增附件，并在上传成功后记录增量标识。 */
package com.brycewg.asrkb.clipboard

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

internal fun isSyncClipboardDownloadDirectory(treeUri: Uri): Boolean = try {
    DocumentsContract.getTreeDocumentId(treeUri)
        .replace('\\', '/')
        .trimEnd('/')
        .endsWith(":Download/BiBi", ignoreCase = true)
} catch (_: Throwable) {
    false
}

internal data class LocalClipboardAttachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: ClipboardAttachmentKind,
    val signature: String,
    val lastModifiedMillis: Long
)

internal fun selectLatestAttachment(
    attachments: List<LocalClipboardAttachment>,
    isEligible: (LocalClipboardAttachment) -> Boolean
): LocalClipboardAttachment? =
    attachments.filter(isEligible).maxWithOrNull(
        compareBy<LocalClipboardAttachment> { it.lastModifiedMillis }.thenBy { it.signature }
    )

internal class ClipboardAttachmentWatcher(
    context: Context,
    private val prefs: Prefs,
    private val policy: ClipboardAttachmentPolicy
) {
    private val appContext = context.applicationContext
    private val seenPrefs = appContext.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)

    fun scanAndUpload(upload: (LocalClipboardAttachment) -> Boolean) =
        ClipboardAttachmentTransferGate.run { scanAndUploadLocked(upload) }

    private fun scanAndUploadLocked(upload: (LocalClipboardAttachment) -> Boolean) {
        if (!policy.hasEnabledType()) return
        val treeUri = prefs.syncClipboardWatchTreeUri.takeIf { it.isNotBlank() } ?: return
        val root = try {
            Uri.parse(treeUri)
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid clipboard watch tree URI", t)
            return
        }
        if (isSyncClipboardDownloadDirectory(root)) {
            Log.w(TAG, "Ignore Download/BiBi as clipboard attachment watch directory")
            prefs.syncClipboardWatchTreeUri = ""
            return
        }
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                root,
                DocumentsContract.getTreeDocumentId(root)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to access clipboard watch tree", t)
            return
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val treeKey = root.toString()
        val firstScanForTree = seenPrefs.getString(KEY_TREE_URI, null) != treeKey
        val attachments = mutableListOf<LocalClipboardAttachment>()
        try {
            val queried = appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIndex) ?: continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR || cursor.isNull(sizeIndex)) continue
                    val size = cursor.getLong(sizeIndex)
                    val kind = if (mime.startsWith("image/", ignoreCase = true)) {
                        ClipboardAttachmentKind.IMAGE
                    } else {
                        ClipboardAttachmentKind.FILE
                    }
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(idIndex))
                    val modified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex)
                    val signature = buildString {
                        append(documentUri)
                        append('|')
                        append(size)
                        append('|')
                        append(modified)
                    }
                    attachments += LocalClipboardAttachment(
                        uri = documentUri,
                        displayName = cursor.getString(nameIndex).orEmpty().ifBlank { "attachment" },
                        mimeType = mime,
                        sizeBytes = size,
                        kind = kind,
                        signature = signature,
                        lastModifiedMillis = modified
                    )
                }
                true
            } ?: false
            if (!queried) {
                // provider 掉线或 URI 权限失效时不能落基线，否则下一轮会把既存文件当成新增上传。
                Log.w(TAG, "Clipboard watch tree query returned no cursor")
                logWarning("clip_attachment_scan_no_cursor", mapOf("firstScan" to firstScanForTree))
                return
            }
            if (firstScanForTree) {
                // 新目录整体作为基线，历史文件不参与上传。
                writeBaseline(treeKey, attachments)
                logBase("clip_attachment_baseline_set", mapOf("count" to attachments.size))
                return
            }
            val baseline = readBaseline()
            val newAttachments = attachments.filter(baseline::isNew)
            val attachment = selectLatestAttachment(newAttachments) {
                policy.allows(it.kind, it.sizeBytes)
            }
            if (attachment == null) {
                markProcessed(newAttachments)
                return
            }
            markProcessed(
                newAttachments.filter {
                    it.lastModifiedMillis <= attachment.lastModifiedMillis && it.signature != attachment.signature
                }
            )
            if (upload(attachment)) {
                markProcessed(
                    newAttachments.filter { it.lastModifiedMillis > attachment.lastModifiedMillis } + attachment
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to scan clipboard watch tree", t)
            logWarning("clip_attachment_scan_failed", mapOf("firstScan" to firstScanForTree), t)
        }
    }

    /** 已处理进度：最大 lastModified 与该时间戳上的签名集合，天然限制集合容量。 */
    private class SeenBaseline(val latestModified: Long, val signatures: Set<String>) {
        fun isNew(attachment: LocalClipboardAttachment): Boolean =
            attachment.lastModifiedMillis > latestModified ||
                (attachment.lastModifiedMillis == latestModified && !signatures.contains(attachment.signature))
    }

    private fun readBaseline(): SeenBaseline = SeenBaseline(
        latestModified = seenPrefs.getLong(KEY_LATEST_MODIFIED, Long.MIN_VALUE),
        signatures = seenPrefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
    )

    /** 新目录基线：一次提交写入进度与目录标识，避免逐文件落盘。 */
    private fun writeBaseline(treeKey: String, attachments: List<LocalClipboardAttachment>) {
        val editor = seenPrefs.edit()
        val latest = attachments.maxOfOrNull { it.lastModifiedMillis }
        if (latest == null) {
            editor.remove(KEY_LATEST_MODIFIED).remove(KEY_SEEN)
        } else {
            editor.putLong(KEY_LATEST_MODIFIED, latest)
                .putStringSet(KEY_SEEN, attachments.signaturesAt(latest))
        }
        editor.putString(KEY_TREE_URI, treeKey).apply()
    }

    private fun markProcessed(attachments: Collection<LocalClipboardAttachment>) {
        if (attachments.isEmpty()) return
        val storedLatest = seenPrefs.getLong(KEY_LATEST_MODIFIED, Long.MIN_VALUE)
        val latest = maxOf(storedLatest, attachments.maxOf { it.lastModifiedMillis })
        val signatures = attachments.signaturesAt(latest)
        if (latest > storedLatest) {
            // 时间戳前进后旧签名不再需要保留，集合因此不会无界增长。
            seenPrefs.edit()
                .putLong(KEY_LATEST_MODIFIED, latest)
                .putStringSet(KEY_SEEN, signatures)
                .apply()
            return
        }
        val seen = seenPrefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        if (seen.containsAll(signatures)) return
        seenPrefs.edit().putStringSet(KEY_SEEN, seen + signatures).apply()
    }

    private fun Collection<LocalClipboardAttachment>.signaturesAt(modifiedMillis: Long): Set<String> =
        asSequence().filter { it.lastModifiedMillis == modifiedMillis }.map { it.signature }.toSet()

    private fun logBase(event: String, data: Map<String, Any?>) {
        try {
            DebugLogManager.logBase(appContext, "app", event, data)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write clipboard diagnostic event: $event", t)
        }
    }

    private fun logWarning(event: String, data: Map<String, Any?>, throwable: Throwable? = null) {
        try {
            DebugLogManager.logWarning(appContext, "app", event, throwable, data)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write clipboard diagnostic event: $event", t)
        }
    }

    companion object {
        private const val TAG = "ClipboardAttachmentWatcher"
        private const val SEEN_PREFS = "clipboard_attachment_uploads"
        private const val KEY_SEEN = "seen"
        private const val KEY_LATEST_MODIFIED = "latest_modified"
        private const val KEY_TREE_URI = "tree_uri"
    }
}
