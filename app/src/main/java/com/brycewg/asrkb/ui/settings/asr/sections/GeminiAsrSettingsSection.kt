package com.brycewg.asrkb.ui.settings.asr.sections

import android.widget.EditText
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.installExplainedSwitch
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.brycewg.asrkb.ui.settings.asr.bindString
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

internal class GeminiAsrSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        binding.view<EditText>(R.id.etGeminiEndpoint).apply {
            setText(binding.prefs.gemEndpoint)
            bindString { binding.prefs.gemEndpoint = it }
        }
        binding.view<EditText>(R.id.etGeminiApiKey).apply {
            setText(binding.prefs.gemApiKey)
            bindString { binding.prefs.gemApiKey = it }
        }
        binding.view<EditText>(R.id.etGeminiModel).apply {
            setText(binding.prefs.gemModel)
            bindString { binding.prefs.gemModel = it }
        }
        binding.view<EditText>(R.id.etGeminiPrompt).apply {
            setText(binding.prefs.gemPrompt)
            bindString { binding.prefs.gemPrompt = it }
        }

        binding.view<MaterialSwitch>(R.id.switchGeminiDisableThinking).apply {
            isChecked = binding.prefs.geminiDisableThinking
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_gemini_disable_thinking,
                offDescRes = R.string.feature_gemini_disable_thinking_off_desc,
                onDescRes = R.string.feature_gemini_disable_thinking_on_desc,
                preferenceKey = "gemini_disable_thinking_explained",
                readPref = { binding.prefs.geminiDisableThinking },
                writePref = { v -> binding.prefs.geminiDisableThinking = v },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        binding.view<MaterialButton>(R.id.btnGeminiGetKey).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://bibidocs.brycewg.com/getting-started/asr-providers.html#gemini")
        }
    }
}
