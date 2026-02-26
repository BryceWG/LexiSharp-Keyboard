package com.brycewg.asrkb.ui.settings.asr.sections

import android.widget.EditText
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

internal class FunAsrNanoSettingsSection : AsrSettingsSection {

    private var etFnUserPrompt: EditText? = null

    override fun bind(binding: AsrSettingsBinding) {
        bindModelVariantSelection(binding)

        etFnUserPrompt = binding.view<EditText>(R.id.etFnUserPrompt).apply {
            setText(binding.prefs.fnUserPrompt)
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    commitFnUserPromptIfNeeded(binding)
                }
            }
        }

        bindThreadsSlider(binding)
        bindSwitches(binding)
        bindKeepAliveSelection(binding)
        bindModelButtons(binding)

        binding.view<MaterialButton>(R.id.btnFnGuide).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely(binding.activity.getString(R.string.local_model_guide_config_doc_url))
        }
    }

    override fun onResume(binding: AsrSettingsBinding) {
        updateDownloadUiVisibility(binding)
    }

    override fun onPause(binding: AsrSettingsBinding) {
        commitFnUserPromptIfNeeded(binding)
    }

    private fun commitFnUserPromptIfNeeded(binding: AsrSettingsBinding) {
        val input = etFnUserPrompt ?: return
        val value = input.text?.toString() ?: ""
        if (value != binding.prefs.fnUserPrompt) {
            binding.viewModel.updateFnUserPrompt(value)
        }
    }

    private fun bindModelVariantSelection(binding: AsrSettingsBinding) {
        val variantLabels = listOf(
            binding.activity.getString(R.string.fn_model_nano_int8),
        )
        val variantCodes = listOf("nano-int8")
        val tvFnModelVariant = binding.view<TextView>(R.id.tvFnModelVariantValue)
        val btnFnDownload = binding.view<MaterialButton>(R.id.btnFnDownloadModel)

        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(binding.prefs.fnModelVariant).coerceAtLeast(0)
            tvFnModelVariant.text = variantLabels[idx]
        }

        fun updateDownloadButtonText() {
            btnFnDownload.text = binding.activity.getString(R.string.btn_fn_download_model)
        }

        updateVariantSummary()
        updateDownloadButtonText()

        tvFnModelVariant.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(binding.prefs.fnModelVariant).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_fn_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "nano-int8"
                if (code != binding.prefs.fnModelVariant) {
                    binding.viewModel.updateFnModelVariant(code)
                }
                updateVariantSummary()
                updateDownloadButtonText()
                updateDownloadUiVisibility(binding)
            }
        }
    }

    private fun bindThreadsSlider(binding: AsrSettingsBinding) {
        binding.view<Slider>(R.id.sliderFnThreads).apply {
            value = binding.prefs.fnNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != binding.prefs.fnNumThreads) {
                        binding.viewModel.updateFnNumThreads(v)
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
        binding.view<MaterialSwitch>(R.id.switchFnUseItn).apply {
            isChecked = binding.prefs.fnUseItn
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_fn_use_itn,
                offDescRes = R.string.feature_sv_use_itn_off_desc,
                onDescRes = R.string.feature_sv_use_itn_on_desc,
                preferenceKey = "fn_use_itn_explained",
                readPref = { binding.prefs.fnUseItn },
                writePref = { v -> binding.viewModel.updateFnUseItn(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialSwitch>(R.id.switchFnPreload).apply {
            isChecked = binding.prefs.fnPreloadEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_fn_preload,
                offDescRes = R.string.feature_sv_preload_off_desc,
                onDescRes = R.string.feature_sv_preload_on_desc,
                preferenceKey = "fn_preload_explained",
                readPref = { binding.prefs.fnPreloadEnabled },
                writePref = { v -> binding.viewModel.updateFnPreload(v) },
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
        val tvFnKeepAlive = binding.view<TextView>(R.id.tvFnKeepAliveValue)

        fun updateKeepAliveSummary() {
            val idx = values.indexOf(binding.prefs.fnKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvFnKeepAlive.text = labels[idx]
        }

        updateKeepAliveSummary()
        tvFnKeepAlive.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = values.indexOf(binding.prefs.fnKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            binding.showSingleChoiceDialog(R.string.label_fn_keep_alive, labels.toTypedArray(), cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != binding.prefs.fnKeepAliveMinutes) {
                    binding.viewModel.updateFnKeepAlive(vv)
                }
                updateKeepAliveSummary()
            }
        }
    }

    private fun bindModelButtons(binding: AsrSettingsBinding) {
        val btnFnDownload = binding.view<MaterialButton>(R.id.btnFnDownloadModel)
        val btnFnImport = binding.view<MaterialButton>(R.id.btnFnImportModel)
        val btnFnClear = binding.view<MaterialButton>(R.id.btnFnClearModel)
        val tvFnDownloadStatus = binding.view<TextView>(R.id.tvFnDownloadStatus)

        btnFnDownload.setOnClickListener { v ->
            val variant = "nano-int8"
            val urlOfficial = "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-funasr-nano-int8-2025-12-30.zip"
            binding.modelDownloadUiController.startDownloadFromSourceDialog(
                downloadButton = v,
                statusTextViews = listOf(tvFnDownloadStatus),
                urlOfficial = urlOfficial,
                variant = variant,
                modelType = "funasr_nano",
                startedTextResId = R.string.fn_download_started_in_bg,
                failedTextResId = R.string.fn_download_status_failed,
                logTag = TAG,
                logMessage = "Failed to start FunASR Nano model download",
            )
        }

        btnFnClear.setOnClickListener { v ->
            AlertDialog.Builder(binding.activity)
                .setTitle(R.string.fn_clear_confirm_title)
                .setMessage(R.string.fn_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    binding.activity.lifecycleScope.launch {
                        try {
                            val base = binding.activity.getExternalFilesDir(null) ?: binding.activity.filesDir
                            val legacySenseVoice = File(base, "sensevoice")
                            val targets = listOf(
                                File(base, "funasr_nano"),
                                File(legacySenseVoice, "nano-int8"),
                                File(legacySenseVoice, "nano-full"),
                            )
                            targets.forEach { dir ->
                                if (dir.exists()) {
                                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                                }
                            }
                            try {
                                com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
                            } catch (t: Throwable) {
                                android.util.Log.e(TAG, "Failed to unload local recognizer", t)
                            }
                            tvFnDownloadStatus.text = binding.activity.getString(R.string.fn_clear_done)
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear FunASR Nano model", t)
                            tvFnDownloadStatus.text = binding.activity.getString(R.string.fn_clear_failed)
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

        btnFnImport.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.funAsrNanoModelPicker.launch("application/zip")
        }
    }

    private fun updateDownloadUiVisibility(binding: AsrSettingsBinding) {
        val ready = binding.viewModel.checkFnModelDownloaded(binding.activity)
        val btn = binding.view<MaterialButton>(R.id.btnFnDownloadModel)
        val btnImport = binding.view<MaterialButton>(R.id.btnFnImportModel)
        val btnClear = binding.view<MaterialButton>(R.id.btnFnClearModel)
        val tv = binding.view<TextView>(R.id.tvFnDownloadStatus)

        btn.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
        btnImport.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
        btnClear.visibility = if (ready) android.view.View.VISIBLE else android.view.View.GONE

        if (ready && tv.text.isNullOrBlank()) {
            tv.text = binding.activity.getString(R.string.fn_download_status_done)
        }
    }

    private companion object {
        private const val TAG = "FunAsrNanoSettingsSection"
    }
}
