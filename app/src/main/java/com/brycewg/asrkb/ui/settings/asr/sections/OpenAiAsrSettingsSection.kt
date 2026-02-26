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

internal class OpenAiAsrSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        binding.view<EditText>(R.id.etOpenAiAsrEndpoint).apply {
            setText(binding.prefs.oaAsrEndpoint)
            bindString { binding.prefs.oaAsrEndpoint = it }
        }
        binding.view<EditText>(R.id.etOpenAiApiKey).apply {
            setText(binding.prefs.oaAsrApiKey)
            bindString { binding.prefs.oaAsrApiKey = it }
        }
        binding.view<EditText>(R.id.etOpenAiModel).apply {
            setText(binding.prefs.oaAsrModel)
            bindString { binding.prefs.oaAsrModel = it }
        }
        binding.view<EditText>(R.id.etOpenAiPrompt).apply {
            setText(binding.prefs.oaAsrPrompt)
            bindString { binding.prefs.oaAsrPrompt = it }
        }

        binding.view<MaterialSwitch>(R.id.switchOpenAiUsePrompt).apply {
            isChecked = binding.prefs.oaAsrUsePrompt
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_openai_use_prompt,
                offDescRes = R.string.feature_openai_use_prompt_off_desc,
                onDescRes = R.string.feature_openai_use_prompt_on_desc,
                preferenceKey = "openai_use_prompt_explained",
                readPref = { binding.prefs.oaAsrUsePrompt },
                writePref = { v -> binding.viewModel.updateOpenAiUsePrompt(v) },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        bindLanguageSelection(binding)

        binding.view<MaterialButton>(R.id.btnOpenAiGetKey).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://bibidocs.brycewg.com/getting-started/asr-providers.html#openai-%E5%85%BC%E5%AE%B9%E6%8E%A5%E5%8F%A3")
        }
    }

    private fun bindLanguageSelection(binding: AsrSettingsBinding) {
        val langLabels = listOf(
            binding.activity.getString(R.string.dash_lang_auto),
            binding.activity.getString(R.string.dash_lang_zh),
            binding.activity.getString(R.string.dash_lang_en),
            binding.activity.getString(R.string.dash_lang_ja),
            binding.activity.getString(R.string.dash_lang_de),
            binding.activity.getString(R.string.dash_lang_ko),
            binding.activity.getString(R.string.dash_lang_ru),
            binding.activity.getString(R.string.dash_lang_fr),
            binding.activity.getString(R.string.dash_lang_pt),
            binding.activity.getString(R.string.dash_lang_ar),
            binding.activity.getString(R.string.dash_lang_it),
            binding.activity.getString(R.string.dash_lang_es),
        )
        val langCodes = listOf("", "zh", "en", "ja", "de", "ko", "ru", "fr", "pt", "ar", "it", "es")
        val tvOpenAiLanguage = binding.view<TextView>(R.id.tvOpenAiLanguageValue)

        fun updateOaLangSummary() {
            val idx = langCodes.indexOf(binding.prefs.oaAsrLanguage).coerceAtLeast(0)
            tvOpenAiLanguage.text = langLabels[idx]
        }

        updateOaLangSummary()
        tvOpenAiLanguage.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(binding.prefs.oaAsrLanguage).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_openai_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                if (code != binding.prefs.oaAsrLanguage) binding.prefs.oaAsrLanguage = code
                updateOaLangSummary()
            }
        }
    }
}
