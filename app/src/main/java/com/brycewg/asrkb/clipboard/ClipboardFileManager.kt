/** 管理 Download/BiBi 中的远端附件；Android 10+ 通过 MediaStore 写入共享下载目录。 */
package com.brycewg.asrkb.clipboard

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.URLConnection

class ClipboardFileManager(private val context: Context) {
    companion object {
        private const val TAG = "ClipboardFileManager"
        private const val BIBI_FOLDER = "BiBi"
        private const val RELATIVE_PATH = "Download/BiBi/"
    }

    fun getLocalPath(fileName: String): String? {
        if (!isSafeFileName(fileName)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findMediaUri(fileName)?.toString()
        } else {
            legacyFile(fileName).takeIf(File::exists)?.absolutePath
        }
    }

    fun fileExists(fileName: String, expectedSize: Long? = null): Boolean {
        if (!isSafeFileName(fileName)) return false
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            findMedia(fileName)?.let { expectedSize == null || expectedSize <= 0L || it.size == expectedSize } == true
        } else {
            legacyFile(fileName).let { it.exists() && (expectedSize == null || expectedSize <= 0L || it.length() == expectedSize) }
        }
    }

    fun saveFile(
        fileName: String,
        inputStream: InputStream,
        totalBytes: Long = -1,
        maxBytes: Long? = null,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): String? = try {
        if (!isSafeFileName(fileName)) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaFile(fileName, inputStream, totalBytes, maxBytes, progressCallback)
        } else {
            saveLegacyFile(fileName, inputStream, totalBytes, maxBytes, progressCallback)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to save clipboard file: $fileName", t)
        null
    } finally {
        try {
            inputStream.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close clipboard file input", t)
        }
    }

    fun deleteFile(fileName: String): Boolean {
        if (!isSafeFileName(fileName)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                findMediaUri(fileName)?.let { context.contentResolver.delete(it, null, null) } ?: 0
                true
            } else {
                val file = legacyFile(fileName)
                !file.exists() || file.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to delete clipboard file: $fileName", t)
            false
        }
    }

    private fun saveMediaFile(
        fileName: String,
        input: InputStream,
        totalBytes: Long,
        maxBytes: Long?,
        onProgress: ((Long, Long) -> Unit)?
    ): String? {
        deleteFile(fileName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                copy(input, output, totalBytes, maxBytes, onProgress)
            } ?: return null
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            uri.toString()
        } catch (t: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw t
        }
    }

    private fun saveLegacyFile(
        fileName: String,
        input: InputStream,
        totalBytes: Long,
        maxBytes: Long?,
        onProgress: ((Long, Long) -> Unit)?
    ): String? {
        val file = legacyFile(fileName)
        file.parentFile?.mkdirs()
        file.outputStream().use { copy(input, it, totalBytes, maxBytes, onProgress) }
        return file.absolutePath
    }

    private fun copy(
        input: InputStream,
        output: java.io.OutputStream,
        totalBytes: Long,
        maxBytes: Long?,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val buffer = ByteArray(8192)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (maxBytes != null && copied + count > maxBytes) {
                throw IllegalStateException("Clipboard attachment exceeds configured size limit")
            }
            output.write(buffer, 0, count)
            copied += count
            onProgress?.invoke(copied, totalBytes)
        }
    }

    private fun findMediaUri(fileName: String): Uri? = findMedia(fileName)?.uri

    fun openInputStream(fileName: String): InputStream? {
        if (!isSafeFileName(fileName)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findMediaUri(fileName)?.let(context.contentResolver::openInputStream)
        } else {
            legacyFile(fileName).takeIf(File::exists)?.inputStream()
        }
    }

    private fun findMedia(fileName: String): MediaFile? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(fileName, RELATIVE_PATH),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
            MediaFile(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()), size)
        }
    }

    private fun legacyFile(fileName: String): File = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BIBI_FOLDER),
        fileName
    )

    private fun isSafeFileName(fileName: String): Boolean = fileName.isNotBlank() &&
        fileName != "." &&
        fileName != ".." &&
        !fileName.contains('/') &&
        !fileName.contains('\\')

    private data class MediaFile(val uri: Uri, val size: Long)
}
