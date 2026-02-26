package com.brycewg.asrkb.ui.settings.asr.sections

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.installExplainedSwitch
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class ParaformerSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        bindVariantSelection(binding)
        bindKeepAliveSelection(binding)
        bindThreadsSlider(binding)
        bindPreloadSwitch(binding)
        bindItnSwitch(binding)
        bindModelButtons(binding)

        binding.view<MaterialButton>(R.id.btnPfGuide).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely(binding.activity.getString(R.string.local_model_guide_config_doc_url))
        }
    }

    override fun onResume(binding: AsrSettingsBinding) {
        updateDownloadUiVisibility(binding)
    }

    private fun bindVariantSelection(binding: AsrSettingsBinding) {
        val tvPfVariant = binding.view<TextView>(R.id.tvPfModelVariantValue)
        val variantLabels = listOf(
            binding.activity.getString(R.string.pf_variant_bilingual_int8),
            binding.activity.getString(R.string.pf_variant_bilingual_fp32),
            binding.activity.getString(R.string.pf_variant_trilingual_int8),
            binding.activity.getString(R.string.pf_variant_trilingual_fp32),
        )
        val variantCodes = listOf(
            "bilingual-int8",
            "bilingual-fp32",
            "trilingual-int8",
            "trilingual-fp32",
        )

        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(binding.prefs.pfModelVariant).coerceAtLeast(0)
            tvPfVariant.text = variantLabels[idx]
        }

        updateVariantSummary()
        tvPfVariant.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(binding.prefs.pfModelVariant).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_pf_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "bilingual-int8"
                if (code != binding.prefs.pfModelVariant) {
                    binding.viewModel.updatePfModelVariant(code)
                }
                updateVariantSummary()
                updateDownloadUiVisibility(binding)
            }
        }
    }

    private fun bindKeepAliveSelection(binding: AsrSettingsBinding) {
        val tvKeep = binding.view<TextView>(R.id.tvPfKeepAliveValue)
        val values = listOf(0, 5, 15, 30, -1)
        val labels = arrayOf(
            binding.activity.getString(R.string.sv_keep_alive_immediate),
            binding.activity.getString(R.string.sv_keep_alive_5m),
            binding.activity.getString(R.string.sv_keep_alive_15m),
            binding.activity.getString(R.string.sv_keep_alive_30m),
            binding.activity.getString(R.string.sv_keep_alive_always),
        )

        fun updateKeepAliveSummary() {
            val idx = values.indexOf(binding.prefs.pfKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvKeep.text = labels[idx]
        }

        updateKeepAliveSummary()
        tvKeep.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = values.indexOf(binding.prefs.pfKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            binding.showSingleChoiceDialog(R.string.label_pf_keep_alive, labels, cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != binding.prefs.pfKeepAliveMinutes) {
                    binding.viewModel.updatePfKeepAlive(vv)
                }
                updateKeepAliveSummary()
            }
        }
    }

    private fun bindThreadsSlider(binding: AsrSettingsBinding) {
        binding.view<Slider>(R.id.sliderPfThreads).apply {
            value = binding.prefs.pfNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != binding.prefs.pfNumThreads) {
                        binding.viewModel.updatePfNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = binding.hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: Slider) = binding.hapticTapIfEnabled(slider)
            })
        }
    }

    private fun bindPreloadSwitch(binding: AsrSettingsBinding) {
        binding.view<MaterialSwitch>(R.id.switchPfPreload).apply {
            isChecked = binding.prefs.pfPreloadEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_pf_preload,
                offDescRes = R.string.feature_pf_preload_off_desc,
                onDescRes = R.string.feature_pf_preload_on_desc,
                preferenceKey = "pf_preload_explained",
                readPref = { binding.prefs.pfPreloadEnabled },
                writePref = { v -> binding.viewModel.updatePfPreload(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }
    }

    private fun bindItnSwitch(binding: AsrSettingsBinding) {
        binding.view<MaterialSwitch>(R.id.switchPfItn).apply {
            isChecked = safeReadPfUseItn(binding)
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_pf_use_itn,
                offDescRes = R.string.feature_pf_use_itn_off_desc,
                onDescRes = R.string.feature_pf_use_itn_on_desc,
                preferenceKey = "pf_use_itn_explained",
                readPref = { safeReadPfUseItn(binding) },
                writePref = { v -> binding.viewModel.updatePfUseItn(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }
    }

    private fun safeReadPfUseItn(binding: AsrSettingsBinding): Boolean {
        return try {
            binding.prefs.pfUseItn
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Failed to read pfUseItn; using default", t)
            false
        }
    }

    private fun bindModelButtons(binding: AsrSettingsBinding) {
        val btnDl = binding.view<MaterialButton>(R.id.btnPfDownloadModel)
        val btnImport = binding.view<MaterialButton>(R.id.btnPfImportModel)
        val btnClear = binding.view<MaterialButton>(R.id.btnPfClearModel)
        val tvStatus = binding.view<TextView>(R.id.tvPfDownloadStatus)

        btnImport.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.paraformerModelPicker.launch("application/zip")
        }

        btnDl.setOnClickListener { v ->
            val variant = binding.prefs.pfModelVariant
            val isTri = variant.startsWith("trilingual")
            val urlOfficial = if (isTri) {
                "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en.zip"
            } else {
                "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.zip"
            }
            binding.modelDownloadUiController.startDownloadFromSourceDialog(
                downloadButton = v,
                statusTextViews = listOf(tvStatus),
                urlOfficial = urlOfficial,
                variant = variant,
                modelType = "paraformer",
                startedTextResId = R.string.pf_download_started_in_bg,
                failedTextResId = R.string.pf_download_status_failed,
                logTag = TAG,
                logMessage = "Failed to start paraformer download",
            )
        }

        btnClear.setOnClickListener { v ->
            AlertDialog.Builder(binding.activity)
                .setTitle(R.string.pf_clear_confirm_title)
                .setMessage(R.string.pf_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    binding.activity.lifecycleScope.launch {
                        try {
                            val base = binding.activity.getExternalFilesDir(null) ?: binding.activity.filesDir
                            val root = File(base, "paraformer")
                            val group = if (binding.prefs.pfModelVariant.startsWith("trilingual")) "trilingual" else "bilingual"
                            val outDir = File(root, group)
                            if (outDir.exists()) withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            try {
                                com.brycewg.asrkb.asr.unloadParaformerRecognizer()
                            } catch (t: Throwable) {
                                android.util.Log.e(TAG, "Failed to unload Paraformer recognizer", t)
                            }
                            tvStatus.text = binding.activity.getString(R.string.pf_clear_done)
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear paraformer model", t)
                            tvStatus.text = binding.activity.getString(R.string.pf_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updateDownloadUiVisibility(binding)
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    private fun updateDownloadUiVisibility(binding: AsrSettingsBinding) {
        val ready = binding.viewModel.checkPfModelDownloaded(binding.activity)
        val btn = binding.view<MaterialButton>(R.id.btnPfDownloadModel)
        val btnImport = binding.view<MaterialButton>(R.id.btnPfImportModel)
        val btnClear = binding.view<MaterialButton>(R.id.btnPfClearModel)
        val tv = binding.view<TextView>(R.id.tvPfDownloadStatus)

        btn.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
        btnImport.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
        btnClear.visibility = if (ready) android.view.View.VISIBLE else android.view.View.GONE

        if (ready && tv.text.isNullOrBlank()) {
            tv.text = binding.activity.getString(R.string.pf_download_status_done)
        }
    }

    private companion object {
        private const val TAG = "ParaformerSettingsSection"
    }
}
