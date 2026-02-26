package com.brycewg.asrkb.ui.settings.asr.sections

import android.widget.EditText
import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.installExplainedSwitch
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.brycewg.asrkb.ui.settings.asr.bindString
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

internal class VolcengineSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        binding.view<EditText>(R.id.etAppKey).apply {
            setText(binding.prefs.appKey)
            bindString { binding.prefs.appKey = it }
        }
        binding.view<EditText>(R.id.etAccessKey).apply {
            setText(binding.prefs.accessKey)
            bindString { binding.prefs.accessKey = it }
        }

        binding.view<MaterialSwitch>(R.id.switchVolcStreaming).apply {
            isChecked = binding.prefs.volcStreamingEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_volc_streaming,
                offDescRes = R.string.feature_volc_streaming_off_desc,
                onDescRes = R.string.feature_volc_streaming_on_desc,
                preferenceKey = "volc_streaming_explained",
                readPref = { binding.prefs.volcStreamingEnabled },
                writePref = { v -> binding.viewModel.updateVolcStreaming(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialSwitch>(R.id.switchVolcFileStandard).apply {
            isChecked = binding.prefs.volcFileStandardEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_volc_file_standard,
                offDescRes = R.string.feature_volc_file_standard_off_desc,
                onDescRes = R.string.feature_volc_file_standard_on_desc,
                preferenceKey = "volc_file_standard_explained",
                readPref = { binding.prefs.volcFileStandardEnabled },
                writePref = { v -> binding.viewModel.updateVolcFileStandard(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialSwitch>(R.id.switchVolcModelV2).apply {
            isChecked = binding.prefs.volcModelV2Enabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_volc_model_v2,
                offDescRes = R.string.feature_volc_model_v2_off_desc,
                onDescRes = R.string.feature_volc_model_v2_on_desc,
                preferenceKey = "volc_model_v2_explained",
                readPref = { binding.prefs.volcModelV2Enabled },
                writePref = { v -> binding.viewModel.updateVolcModelV2(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialSwitch>(R.id.switchVolcDdc).apply {
            isChecked = binding.prefs.volcDdcEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_volc_ddc,
                offDescRes = R.string.feature_volc_ddc_off_desc,
                onDescRes = R.string.feature_volc_ddc_on_desc,
                preferenceKey = "volc_ddc_explained",
                readPref = { binding.prefs.volcDdcEnabled },
                writePref = { v -> binding.viewModel.updateVolcDdc(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialSwitch>(R.id.switchVolcVad).apply {
            isChecked = binding.prefs.volcVadEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_volc_vad,
                offDescRes = R.string.feature_volc_vad_off_desc,
                onDescRes = R.string.feature_volc_vad_on_desc,
                preferenceKey = "volc_vad_explained",
                readPref = { binding.prefs.volcVadEnabled },
                writePref = { v -> binding.viewModel.updateVolcVad(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialSwitch>(R.id.switchVolcNonstream).apply {
            isChecked = binding.prefs.volcNonstreamEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_volc_nonstream,
                offDescRes = R.string.feature_volc_nonstream_off_desc,
                onDescRes = R.string.feature_volc_nonstream_on_desc,
                preferenceKey = "volc_nonstream_explained",
                readPref = { binding.prefs.volcNonstreamEnabled },
                writePref = { v -> binding.viewModel.updateVolcNonstream(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        bindLanguageSelection(binding)

        binding.view<MaterialButton>(R.id.btnVolcGetKey).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://bibidocs.brycewg.com/getting-started/asr-providers.html#%E7%81%AB%E5%B1%B1%E5%BC%95%E6%93%8E-volcengine")
        }
    }

    private fun bindLanguageSelection(binding: AsrSettingsBinding) {
        val langLabels = listOf(
            binding.activity.getString(R.string.volc_lang_auto),
            binding.activity.getString(R.string.volc_lang_en_us),
            binding.activity.getString(R.string.volc_lang_ja_jp),
            binding.activity.getString(R.string.volc_lang_id_id),
            binding.activity.getString(R.string.volc_lang_es_mx),
            binding.activity.getString(R.string.volc_lang_pt_br),
            binding.activity.getString(R.string.volc_lang_de_de),
            binding.activity.getString(R.string.volc_lang_fr_fr),
            binding.activity.getString(R.string.volc_lang_ko_kr),
            binding.activity.getString(R.string.volc_lang_fil_ph),
            binding.activity.getString(R.string.volc_lang_ms_my),
            binding.activity.getString(R.string.volc_lang_th_th),
            binding.activity.getString(R.string.volc_lang_ar_sa),
        )
        val langCodes = listOf(
            "",
            "en-US",
            "ja-JP",
            "id-ID",
            "es-MX",
            "pt-BR",
            "de-DE",
            "fr-FR",
            "ko-KR",
            "fil-PH",
            "ms-MY",
            "th-TH",
            "ar-SA",
        )
        val tvVolcLanguage = binding.view<TextView>(R.id.tvVolcLanguageValue)

        fun updateVolcLangSummary() {
            val idx = langCodes.indexOf(binding.prefs.volcLanguage).coerceAtLeast(0)
            tvVolcLanguage.text = langLabels[idx]
        }

        updateVolcLangSummary()
        tvVolcLanguage.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(binding.prefs.volcLanguage).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_volc_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                binding.viewModel.updateVolcLanguage(code)
                updateVolcLangSummary()
            }
        }
    }

    private companion object {
        private const val TAG = "VolcengineSettingsSection"
    }
}
