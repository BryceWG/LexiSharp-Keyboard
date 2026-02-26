package com.brycewg.asrkb.ui.settings.asr.sections

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.SherpaPunctuationManager
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PunctuationModelSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        bindPunctButtons(
            binding = binding,
            btnDownloadId = R.id.btnTsDownloadPunct,
            btnImportId = R.id.btnTsImportPunct,
            btnClearId = R.id.btnTsClearPunct,
            statusTextId = R.id.tvTsPunctStatus,
        )
        bindPunctButtons(
            binding = binding,
            btnDownloadId = R.id.btnPfDownloadPunct,
            btnImportId = R.id.btnPfImportPunct,
            btnClearId = R.id.btnPfClearPunct,
            statusTextId = R.id.tvPfPunctStatus,
        )
    }

    override fun onResume(binding: AsrSettingsBinding) {
        updateVisibility(binding)
    }

    private fun bindPunctButtons(
        binding: AsrSettingsBinding,
        btnDownloadId: Int,
        btnImportId: Int,
        btnClearId: Int,
        statusTextId: Int,
    ) {
        val btnDl = binding.viewOrNull<MaterialButton>(btnDownloadId) ?: return
        val btnImport = binding.viewOrNull<MaterialButton>(btnImportId) ?: return
        val btnClear = binding.viewOrNull<MaterialButton>(btnClearId) ?: return
        val tvStatus = binding.viewOrNull<TextView>(statusTextId) ?: return

        val variant = "ct-zh-en-int8"
        val urlOfficial =
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.zip"

        btnImport.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.punctuationModelPicker.launch("application/zip")
        }

        btnDl.setOnClickListener { v ->
            binding.modelDownloadUiController.startDownloadFromSourceDialog(
                downloadButton = v,
                statusTextViews = listOf(tvStatus),
                urlOfficial = urlOfficial,
                variant = variant,
                modelType = "punctuation",
                startedTextResId = R.string.punct_download_started_in_bg,
                failedTextResId = R.string.punct_download_status_failed,
                logTag = TAG,
                logMessage = "Failed to start punctuation model download",
            )
        }

        btnClear.setOnClickListener { v ->
            AlertDialog.Builder(binding.activity)
                .setTitle(R.string.punct_clear_confirm_title)
                .setMessage(R.string.punct_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    binding.activity.lifecycleScope.launch {
                        try {
                            val deleted = withContext(Dispatchers.IO) {
                                SherpaPunctuationManager.clearInstalledModel(binding.activity)
                            }
                            tvStatus.text = if (deleted) {
                                binding.activity.getString(R.string.punct_clear_done)
                            } else {
                                binding.activity.getString(R.string.punct_clear_failed)
                            }
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear punctuation model", t)
                            tvStatus.text = binding.activity.getString(R.string.punct_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updateVisibility(binding)
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    private fun updateVisibility(binding: AsrSettingsBinding) {
        val ready = try {
            binding.viewModel.isPunctuationModelInstalled(binding.activity)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to check punctuation model installed", t)
            false
        }

        fun apply(btnDownloadId: Int, btnImportId: Int, btnClearId: Int, statusTextId: Int) {
            val btnDl = binding.viewOrNull<MaterialButton>(btnDownloadId) ?: return
            val btnImport = binding.viewOrNull<MaterialButton>(btnImportId) ?: return
            val btnClear = binding.viewOrNull<MaterialButton>(btnClearId) ?: return
            val tv = binding.viewOrNull<TextView>(statusTextId) ?: return

            btnDl.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
            btnImport.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
            btnClear.visibility = if (ready) android.view.View.VISIBLE else android.view.View.GONE

            if (ready && tv.text.isNullOrBlank()) {
                tv.text = binding.activity.getString(R.string.punct_download_status_done)
            }
        }

        apply(R.id.btnTsDownloadPunct, R.id.btnTsImportPunct, R.id.btnTsClearPunct, R.id.tvTsPunctStatus)
        apply(R.id.btnPfDownloadPunct, R.id.btnPfImportPunct, R.id.btnPfClearPunct, R.id.tvPfPunctStatus)
    }

    private companion object {
        private const val TAG = "PunctuationModelSettingsSection"
    }
}
