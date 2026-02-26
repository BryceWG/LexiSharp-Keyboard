package com.brycewg.asrkb.ui.settings.asr

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.TextView
import androidx.annotation.StringRes
import com.brycewg.asrkb.R

internal class ModelImportUiController(
    context: Context,
) {

    private val uiContext = context
    private val serviceContext = context.applicationContext
    private val contentResolver: ContentResolver = context.contentResolver

    fun startZipImport(
        uri: Uri,
        statusTextViews: List<TextView>,
        @StringRes startedTextResId: Int,
        @StringRes failedTextTemplateResId: Int,
        variant: String,
        modelType: String? = null,
        logTag: String,
        logMessage: String,
    ) {
        statusTextViews.forEach { it.text = "" }

        try {
            if (!isZipUri(uri)) {
                val msg = uiContext.getString(
                    failedTextTemplateResId,
                    uiContext.getString(R.string.error_only_zip_supported),
                )
                statusTextViews.forEach { it.text = msg }
                return
            }

            if (modelType.isNullOrBlank()) {
                ModelDownloadService.startImport(serviceContext, uri, variant)
            } else {
                ModelDownloadService.startImport(serviceContext, uri, variant, modelType)
            }

            val started = uiContext.getString(startedTextResId)
            statusTextViews.forEach { it.text = started }
        } catch (t: Throwable) {
            Log.e(logTag, logMessage, t)
            val msg = uiContext.getString(
                failedTextTemplateResId,
                t.message ?: "Unknown error",
            )
            statusTextViews.forEach { it.text = msg }
        }
    }

    private fun isZipUri(uri: Uri): Boolean {
        val name = getDisplayName(uri) ?: uri.lastPathSegment ?: ""
        return name.lowercase().endsWith(".zip")
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (t: Throwable) {
            Log.w("ModelImportUiController", "Failed to query display name", t)
            null
        }
    }
}
