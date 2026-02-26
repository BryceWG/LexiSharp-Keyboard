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

internal class ElevenLabsSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        binding.view<EditText>(R.id.etElevenApiKey).apply {
            setText(binding.prefs.elevenApiKey)
            bindString { binding.prefs.elevenApiKey = it }
        }

        bindLanguageSelection(binding)

        binding.view<MaterialSwitch>(R.id.switchElevenStreaming).apply {
            isChecked = binding.prefs.elevenStreamingEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_eleven_streaming,
                offDescRes = R.string.feature_eleven_streaming_off_desc,
                onDescRes = R.string.feature_eleven_streaming_on_desc,
                preferenceKey = "eleven_streaming_explained",
                readPref = { binding.prefs.elevenStreamingEnabled },
                writePref = { v -> binding.viewModel.updateElevenStreaming(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialButton>(R.id.btnElevenGetKey).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://bibidocs.brycewg.com/getting-started/asr-providers.html#elevenlabs")
        }
    }

    private fun bindLanguageSelection(binding: AsrSettingsBinding) {
        val elLabels = listOf(
            binding.activity.getString(R.string.eleven_lang_auto),
            binding.activity.getString(R.string.eleven_lang_zh),
            binding.activity.getString(R.string.eleven_lang_en),
            binding.activity.getString(R.string.eleven_lang_ja),
            binding.activity.getString(R.string.eleven_lang_ko),
            binding.activity.getString(R.string.eleven_lang_de),
            binding.activity.getString(R.string.eleven_lang_fr),
            binding.activity.getString(R.string.eleven_lang_es),
            binding.activity.getString(R.string.eleven_lang_pt),
            binding.activity.getString(R.string.eleven_lang_ru),
            binding.activity.getString(R.string.eleven_lang_it),
        )
        val elCodes = listOf("", "zh", "en", "ja", "ko", "de", "fr", "es", "pt", "ru", "it")
        val tvElevenLanguage = binding.view<TextView>(R.id.tvElevenLanguageValue)

        fun updateElSummary() {
            val idx = elCodes.indexOf(binding.prefs.elevenLanguageCode).coerceAtLeast(0)
            tvElevenLanguage.text = elLabels[idx]
        }

        updateElSummary()
        tvElevenLanguage.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = elCodes.indexOf(binding.prefs.elevenLanguageCode).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_eleven_language, elLabels.toTypedArray(), cur) { which ->
                val code = elCodes.getOrNull(which) ?: ""
                if (code != binding.prefs.elevenLanguageCode) binding.prefs.elevenLanguageCode = code
                updateElSummary()
            }
        }
    }
}
