package com.brycewg.asrkb.ui.settings.asr.sections

import android.widget.EditText
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.brycewg.asrkb.ui.settings.asr.bindString
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

internal class ZhipuAsrSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        binding.view<EditText>(R.id.etZhipuApiKey).apply {
            setText(binding.prefs.zhipuApiKey)
            bindString { binding.prefs.zhipuApiKey = it }
        }

        binding.view<Slider>(R.id.sliderZhipuTemperature).apply {
            value = binding.prefs.zhipuTemperature.coerceIn(0f, 1f)
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    binding.prefs.zhipuTemperature = value.coerceIn(0f, 1f)
                }
            }
        }

        binding.view<EditText>(R.id.etZhipuPrompt).apply {
            setText(binding.prefs.zhipuPrompt)
            bindString { binding.prefs.zhipuPrompt = it }
        }

        binding.view<MaterialButton>(R.id.btnZhipuGetKey).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://bibidocs.brycewg.com/getting-started/asr-providers.html#%E6%99%BA%E8%B0%B1-glm")
        }
    }
}
