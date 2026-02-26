package com.brycewg.asrkb.ui.settings.asr

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.DownloadSourceConfig
import com.brycewg.asrkb.ui.DownloadSourceDialog

internal class ModelDownloadUiController(
    context: Context,
) {

    private val uiContext = context
    private val serviceContext = context.applicationContext

    fun startDownloadFromSourceDialog(
        downloadButton: View,
        statusTextViews: List<TextView>,
        urlOfficial: String,
        variant: String,
        modelType: String? = null,
        @StringRes startedTextResId: Int,
        @StringRes failedTextResId: Int,
        logTag: String,
        logMessage: String,
    ) {
        downloadButton.isEnabled = false
        statusTextViews.forEach { it.text = "" }

        val options = DownloadSourceConfig.buildOptions(uiContext, urlOfficial)
        try {
            DownloadSourceDialog.show(
                context = uiContext,
                titleRes = R.string.download_source_title,
                options = options,
                showCancelButton = false,
                onDismiss = { downloadButton.isEnabled = true },
            ) { option ->
                try {
                    if (modelType.isNullOrBlank()) {
                        ModelDownloadService.startDownload(serviceContext, option.url, variant)
                    } else {
                        ModelDownloadService.startDownload(serviceContext, option.url, variant, modelType)
                    }
                    val started = uiContext.getString(startedTextResId)
                    statusTextViews.forEach { it.text = started }
                } catch (t: Throwable) {
                    Log.e(logTag, logMessage, t)
                    val failed = uiContext.getString(failedTextResId)
                    statusTextViews.forEach { it.text = failed }
                } finally {
                    downloadButton.isEnabled = true
                }
            }
        } catch (t: Throwable) {
            Log.e(logTag, "Failed to show download source dialog", t)
            val failed = uiContext.getString(failedTextResId)
            statusTextViews.forEach { it.text = failed }
            downloadButton.isEnabled = true
        }
    }
}
