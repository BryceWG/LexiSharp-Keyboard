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

internal class SenseVoiceSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        bindModelVariantSelection(binding)
        bindLanguageSelection(binding)
        bindThreadsSlider(binding)
        bindSwitches(binding)
        bindKeepAliveSelection(binding)
        bindModelButtons(binding)

        binding.view<MaterialButton>(R.id.btnSvGuide).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely(binding.activity.getString(R.string.local_model_guide_config_doc_url))
        }
    }

    override fun onResume(binding: AsrSettingsBinding) {
        updateDownloadUiVisibility(binding)
    }

    private fun bindModelVariantSelection(binding: AsrSettingsBinding) {
        val variantLabels = listOf(
            binding.activity.getString(R.string.sv_model_small_int8),
            binding.activity.getString(R.string.sv_model_small_full),
        )
        val variantCodes = listOf("small-int8", "small-full")
        val tvSvModelVariant = binding.view<TextView>(R.id.tvSvModelVariantValue)
        val btnSvDownload = binding.view<MaterialButton>(R.id.btnSvDownloadModel)

        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(binding.prefs.svModelVariant).coerceAtLeast(0)
            tvSvModelVariant.text = variantLabels[idx]
        }

        fun updateDownloadButtonText() {
            btnSvDownload.text = binding.activity.getString(R.string.btn_sv_download_model)
        }

        updateVariantSummary()
        updateDownloadButtonText()
        updateSvLanguageVisibility(binding)

        tvSvModelVariant.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(binding.prefs.svModelVariant).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_sv_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "small-int8"
                if (code != binding.prefs.svModelVariant) {
                    binding.viewModel.updateSvModelVariant(code)
                }
                updateVariantSummary()
                updateDownloadButtonText()
                updateSvLanguageVisibility(binding)
                updateDownloadUiVisibility(binding)
            }
        }
    }

    private fun bindLanguageSelection(binding: AsrSettingsBinding) {
        val labels = listOf(
            binding.activity.getString(R.string.sv_lang_auto),
            binding.activity.getString(R.string.sv_lang_zh),
            binding.activity.getString(R.string.sv_lang_en),
            binding.activity.getString(R.string.sv_lang_ja),
            binding.activity.getString(R.string.sv_lang_ko),
            binding.activity.getString(R.string.sv_lang_yue),
        )
        val codes = listOf("auto", "zh", "en", "ja", "ko", "yue")
        val tvSvLanguage = binding.view<TextView>(R.id.tvSvLanguageValue)

        fun updateSvLangSummary() {
            val idx = codes.indexOf(binding.prefs.svLanguage).coerceAtLeast(0)
            tvSvLanguage.text = labels[idx]
        }

        updateSvLangSummary()
        updateSvLanguageVisibility(binding)

        tvSvLanguage.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = codes.indexOf(binding.prefs.svLanguage).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_sv_language, labels.toTypedArray(), cur) { which ->
                val code = codes.getOrNull(which) ?: "auto"
                if (code != binding.prefs.svLanguage) {
                    binding.viewModel.updateSvLanguage(code)
                }
                updateSvLangSummary()
            }
        }
    }

    private fun updateSvLanguageVisibility(binding: AsrSettingsBinding) {
        val label = binding.viewOrNull<TextView>(R.id.tvSvLanguageLabel) ?: return
        val value = binding.viewOrNull<TextView>(R.id.tvSvLanguageValue) ?: return
        label.visibility = android.view.View.VISIBLE
        value.visibility = android.view.View.VISIBLE
    }

    private fun bindThreadsSlider(binding: AsrSettingsBinding) {
        binding.view<Slider>(R.id.sliderSvThreads).apply {
            value = binding.prefs.svNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != binding.prefs.svNumThreads) {
                        binding.viewModel.updateSvNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = binding.hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: Slider) = binding.hapticTapIfEnabled(slider)
            })
        }
    }

    private fun bindSwitches(binding: AsrSettingsBinding) {
        binding.view<MaterialSwitch>(R.id.switchSvUseItn).apply {
            isChecked = binding.prefs.svUseItn
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_sv_use_itn,
                offDescRes = R.string.feature_sv_use_itn_off_desc,
                onDescRes = R.string.feature_sv_use_itn_on_desc,
                preferenceKey = "sv_use_itn_explained",
                readPref = { binding.prefs.svUseItn },
                writePref = { v -> binding.viewModel.updateSvUseItn(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialSwitch>(R.id.switchSvPreload).apply {
            isChecked = binding.prefs.svPreloadEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_sv_preload,
                offDescRes = R.string.feature_sv_preload_off_desc,
                onDescRes = R.string.feature_sv_preload_on_desc,
                preferenceKey = "sv_preload_explained",
                readPref = { binding.prefs.svPreloadEnabled },
                writePref = { v -> binding.viewModel.updateSvPreload(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialSwitch>(R.id.switchSvPseudoStream).apply {
            isChecked = binding.prefs.svPseudoStreamEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_sv_pseudo_stream,
                offDescRes = R.string.feature_sv_pseudo_stream_off_desc,
                onDescRes = R.string.feature_sv_pseudo_stream_on_desc,
                preferenceKey = "sv_pseudo_stream_explained",
                readPref = { binding.prefs.svPseudoStreamEnabled },
                writePref = { v -> binding.viewModel.updateSvPseudoStream(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }
    }

    private fun bindKeepAliveSelection(binding: AsrSettingsBinding) {
        val labels = listOf(
            binding.activity.getString(R.string.sv_keep_alive_immediate),
            binding.activity.getString(R.string.sv_keep_alive_5m),
            binding.activity.getString(R.string.sv_keep_alive_15m),
            binding.activity.getString(R.string.sv_keep_alive_30m),
            binding.activity.getString(R.string.sv_keep_alive_always),
        )
        val values = listOf(0, 5, 15, 30, -1)
        val tvSvKeepAlive = binding.view<TextView>(R.id.tvSvKeepAliveValue)

        fun updateKeepAliveSummary() {
            val idx = values.indexOf(binding.prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvSvKeepAlive.text = labels[idx]
        }

        updateKeepAliveSummary()
        tvSvKeepAlive.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = values.indexOf(binding.prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            binding.showSingleChoiceDialog(R.string.label_sv_keep_alive, labels.toTypedArray(), cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != binding.prefs.svKeepAliveMinutes) {
                    binding.viewModel.updateSvKeepAlive(vv)
                }
                updateKeepAliveSummary()
            }
        }
    }

    private fun bindModelButtons(binding: AsrSettingsBinding) {
        val btnSvDownload = binding.view<MaterialButton>(R.id.btnSvDownloadModel)
        val btnSvImport = binding.view<MaterialButton>(R.id.btnSvImportModel)
        val btnSvClear = binding.view<MaterialButton>(R.id.btnSvClearModel)
        val tvSvDownloadStatus = binding.view<TextView>(R.id.tvSvDownloadStatus)

        btnSvDownload.setOnClickListener { v ->
            val variant = binding.prefs.svModelVariant
            val urlOfficial = when (variant) {
                "small-full" -> "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.zip"
                else -> "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip"
            }
            binding.modelDownloadUiController.startDownloadFromSourceDialog(
                downloadButton = v,
                statusTextViews = listOf(tvSvDownloadStatus),
                urlOfficial = urlOfficial,
                variant = variant,
                startedTextResId = R.string.sv_download_started_in_bg,
                failedTextResId = R.string.sv_download_status_failed,
                logTag = TAG,
                logMessage = "Failed to start model download",
            )
        }

        btnSvClear.setOnClickListener { v ->
            AlertDialog.Builder(binding.activity)
                .setTitle(R.string.sv_clear_confirm_title)
                .setMessage(R.string.sv_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    binding.activity.lifecycleScope.launch {
                        try {
                            val base = binding.activity.getExternalFilesDir(null) ?: binding.activity.filesDir
                            val variant = binding.prefs.svModelVariant
                            val outDirRoot = File(base, "sensevoice")
                            val outDir = when (variant) {
                                "small-full" -> File(outDirRoot, "small-full")
                                else -> File(outDirRoot, "small-int8")
                            }
                            if (outDir.exists()) {
                                withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            }
                            try {
                                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                            } catch (t: Throwable) {
                                android.util.Log.e(TAG, "Failed to unload SenseVoice recognizer", t)
                            }
                            tvSvDownloadStatus.text = binding.activity.getString(R.string.sv_clear_done)
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear model", t)
                            tvSvDownloadStatus.text = binding.activity.getString(R.string.sv_clear_failed)
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

        btnSvImport.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.senseVoiceModelPicker.launch("application/zip")
        }
    }

    private fun updateDownloadUiVisibility(binding: AsrSettingsBinding) {
        val ready = binding.viewModel.checkSvModelDownloaded(binding.activity)
        val btn = binding.view<MaterialButton>(R.id.btnSvDownloadModel)
        val btnImport = binding.view<MaterialButton>(R.id.btnSvImportModel)
        val btnClear = binding.view<MaterialButton>(R.id.btnSvClearModel)
        val tv = binding.view<TextView>(R.id.tvSvDownloadStatus)

        btn.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
        btnImport.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
        btnClear.visibility = if (ready) android.view.View.VISIBLE else android.view.View.GONE

        if (ready && tv.text.isNullOrBlank()) {
            tv.text = binding.activity.getString(R.string.sv_download_status_done)
        }
    }

    private companion object {
        private const val TAG = "SenseVoiceSettingsSection"
    }
}
