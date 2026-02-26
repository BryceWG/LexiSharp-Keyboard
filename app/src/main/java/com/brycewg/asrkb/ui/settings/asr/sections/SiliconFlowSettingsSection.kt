package com.brycewg.asrkb.ui.settings.asr.sections

import android.content.res.Configuration
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.brycewg.asrkb.ui.settings.asr.bindString
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

internal class SiliconFlowSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        val switchSfFreeEnabled = binding.view<MaterialSwitch>(R.id.switchSfFreeEnabled)
        val groupSfFreeModel = binding.view<android.view.View>(R.id.groupSfFreeModel)
        val groupSfApiKey = binding.view<android.view.View>(R.id.groupSfApiKey)
        val tvSfFreeModelValue = binding.view<TextView>(R.id.tvSfFreeModelValue)
        val imgSfFreePoweredBy = binding.view<ImageView>(R.id.imgSfFreePoweredBy)

        val isDarkMode =
            (binding.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        imgSfFreePoweredBy.setImageResource(
            if (isDarkMode) R.drawable.powered_by_siliconflow_dark else R.drawable.powered_by_siliconflow_light,
        )

        fun updateSfFreeUi(freeEnabled: Boolean) {
            groupSfFreeModel.visibility = if (freeEnabled) android.view.View.VISIBLE else android.view.View.GONE
            groupSfApiKey.visibility = if (freeEnabled) android.view.View.GONE else android.view.View.VISIBLE
        }

        switchSfFreeEnabled.isChecked = binding.prefs.sfFreeAsrEnabled
        updateSfFreeUi(binding.prefs.sfFreeAsrEnabled)

        switchSfFreeEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.prefs.sfFreeAsrEnabled = isChecked
            updateSfFreeUi(isChecked)
            binding.hapticTapIfEnabled(switchSfFreeEnabled)
        }

        val sfFreeModels = Prefs.SF_FREE_ASR_MODELS
        val initialFreeModel = binding.prefs.sfFreeAsrModel.ifBlank { Prefs.DEFAULT_SF_FREE_ASR_MODEL }
        if (initialFreeModel != binding.prefs.sfFreeAsrModel) binding.prefs.sfFreeAsrModel = initialFreeModel
        tvSfFreeModelValue.text = initialFreeModel

        tvSfFreeModelValue.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val curIdx = sfFreeModels.indexOf(binding.prefs.sfFreeAsrModel).coerceAtLeast(0)
            binding.showSingleChoiceDialog(
                titleResId = R.string.label_sf_model_select,
                items = sfFreeModels.toTypedArray(),
                currentIndex = curIdx,
            ) { which ->
                val selected = sfFreeModels.getOrNull(which) ?: Prefs.DEFAULT_SF_FREE_ASR_MODEL
                if (selected != binding.prefs.sfFreeAsrModel) {
                    binding.prefs.sfFreeAsrModel = selected
                    tvSfFreeModelValue.text = selected
                }
            }
        }

        binding.view<MaterialButton>(R.id.btnSfFreeRegister).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://cloud.siliconflow.cn/i/g8thUcWa")
        }

        binding.view<MaterialButton>(R.id.btnSfFreeGuide).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://bibidocs.brycewg.com/getting-started/asr-providers.html#%E7%A1%85%E5%9F%BA%E6%B5%81%E5%8A%A8-siliconflow")
        }

        binding.view<EditText>(R.id.etSfApiKey).apply {
            setText(binding.prefs.sfApiKey)
            bindString { binding.prefs.sfApiKey = it }
        }

        val tvSfModelValue = binding.view<TextView>(R.id.tvSfModelValue)
        val sfModels = listOf(
            "Qwen/Qwen3-Omni-30B-A3B-Instruct",
            "Qwen/Qwen3-Omni-30B-A3B-Thinking",
            "TeleAI/TeleSpeechASR",
            "FunAudioLLM/SenseVoiceSmall",
        )

        fun isOmni(model: String): Boolean = model.startsWith("Qwen/Qwen3-Omni-30B-A3B-")

        fun ensureValidModel(current: String): String {
            return if (current in sfModels) {
                current
            } else {
                if (binding.prefs.sfUseOmni) Prefs.DEFAULT_SF_OMNI_MODEL else Prefs.DEFAULT_SF_MODEL
            }
        }

        val initialModel = ensureValidModel(binding.prefs.sfModel.ifBlank { Prefs.DEFAULT_SF_MODEL })
        if (initialModel != binding.prefs.sfModel) binding.prefs.sfModel = initialModel
        tvSfModelValue.text = initialModel

        tvSfModelValue.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val curIdx = sfModels.indexOf(binding.prefs.sfModel).coerceAtLeast(0)
            binding.showSingleChoiceDialog(
                titleResId = R.string.label_sf_model_select,
                items = sfModels.toTypedArray(),
                currentIndex = curIdx,
            ) { which ->
                val selected = sfModels.getOrNull(which) ?: Prefs.DEFAULT_SF_MODEL
                if (selected != binding.prefs.sfModel) {
                    binding.prefs.sfModel = selected
                    tvSfModelValue.text = selected
                    binding.viewModel.updateSfUseOmni(isOmni(selected))
                }
            }
        }

        binding.view<EditText>(R.id.etSfOmniPrompt).apply {
            setText(binding.prefs.sfOmniPrompt)
            bindString { binding.prefs.sfOmniPrompt = it }
        }

        binding.view<MaterialButton>(R.id.btnSfGetKey).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://bibidocs.brycewg.com/getting-started/asr-providers.html#%E7%A1%85%E5%9F%BA%E6%B5%81%E5%8A%A8-siliconflow")
        }
    }
}
