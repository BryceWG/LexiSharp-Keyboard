/** 扫描用户授权目录中的新增附件，并在上传成功后记录增量标识。 */
package com.brycewg.asrkb.clipboard

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.brycewg.asrkb.store.Prefs

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
        val firstScanForTree = seenPrefs.getString(KEY_TREE_URI, null) != root.toString()
        val attachments = mutableListOf<LocalClipboardAttachment>()
        if (firstScanForTree) {
            seenPrefs.edit().remove(KEY_LATEST_MODIFIED).remove(KEY_SEEN).apply()
        }
        try {
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
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
            }
            if (firstScanForTree) {
                attachments.forEach(::markProcessed)
            } else {
                val newAttachments = attachments.filter(::isNew)
                val attachment = selectLatestAttachment(newAttachments) {
                    policy.allows(it.kind, it.sizeBytes)
                }
                if (attachment == null) {
                    newAttachments.forEach(::markProcessed)
                } else {
                    newAttachments.asSequence()
                        .filter { it.lastModifiedMillis <= attachment.lastModifiedMillis }
                        .filterNot { it.signature == attachment.signature }
                        .forEach(::markProcessed)
                    if (upload(attachment)) {
                        markProcessed(attachment)
                        newAttachments.asSequence()
                            .filter { it.lastModifiedMillis > attachment.lastModifiedMillis }
                            .forEach(::markProcessed)
                    }
                }
            }
            if (firstScanForTree) seenPrefs.edit().putString(KEY_TREE_URI, root.toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to scan clipboard watch tree", t)
        }
    }

    private fun isNew(attachment: LocalClipboardAttachment): Boolean {
        val latestModified = seenPrefs.getLong(KEY_LATEST_MODIFIED, Long.MIN_VALUE)
        return attachment.lastModifiedMillis > latestModified ||
            (attachment.lastModifiedMillis == latestModified &&
                seenPrefs.getStringSet(KEY_SEEN, emptySet())?.contains(attachment.signature) != true)
    }

    private fun markProcessed(attachment: LocalClipboardAttachment) {
        val signature = attachment.signature
        val modified = attachment.lastModifiedMillis
        val latestModified = seenPrefs.getLong(KEY_LATEST_MODIFIED, Long.MIN_VALUE)
        if (modified > latestModified) {
            seenPrefs.edit()
                .putLong(KEY_LATEST_MODIFIED, modified)
                .putStringSet(KEY_SEEN, setOf(signature))
                .apply()
        } else if (modified == latestModified) {
            val seen = seenPrefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
            seen += signature
            seenPrefs.edit().putStringSet(KEY_SEEN, seen).apply()
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
