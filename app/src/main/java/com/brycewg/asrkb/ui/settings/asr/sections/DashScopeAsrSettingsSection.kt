package com.brycewg.asrkb.ui.settings.asr.sections

import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.installExplainedSwitch
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.brycewg.asrkb.ui.settings.asr.bindString
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

internal class DashScopeAsrSettingsSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        binding.view<EditText>(R.id.etDashApiKey).apply {
            setText(binding.prefs.dashApiKey)
            bindString { binding.prefs.dashApiKey = it }
        }
        binding.view<EditText>(R.id.etDashPrompt).apply {
            setText(binding.prefs.dashPrompt)
            bindString { binding.prefs.dashPrompt = it }
        }

        binding.view<MaterialSwitch>(R.id.switchDashFunAsrSemanticPunct).apply {
            isChecked = binding.prefs.dashFunAsrSemanticPunctEnabled
            installExplainedSwitch(
                context = binding.activity,
                titleRes = R.string.label_dash_funasr_semantic_punct,
                offDescRes = R.string.feature_dash_funasr_semantic_punct_off_desc,
                onDescRes = R.string.feature_dash_funasr_semantic_punct_on_desc,
                preferenceKey = "dash_funasr_semantic_punct_explained",
                readPref = { binding.prefs.dashFunAsrSemanticPunctEnabled },
                writePref = { v -> binding.prefs.dashFunAsrSemanticPunctEnabled = v },
                hapticFeedback = { binding.hapticTapIfEnabled(it) },
            )
        }

        bindModelSelection(binding)
        bindLanguageSelection(binding)
        bindRegionSelection(binding)

        binding.view<MaterialButton>(R.id.btnDashGetKey).setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            binding.openUrlSafely("https://bibidocs.brycewg.com/getting-started/asr-providers.html#%E9%98%BF%E9%87%8C%E4%BA%91%E7%99%BE%E7%82%BC-dashscope-qwen")
        }
    }

    private fun bindModelSelection(binding: AsrSettingsBinding) {
        val modelLabels = listOf(
            binding.activity.getString(R.string.dash_model_qwen_file),
            binding.activity.getString(R.string.dash_model_qwen_realtime),
            binding.activity.getString(R.string.dash_model_fun_realtime),
        )
        val modelValues = listOf(
            Prefs.DEFAULT_DASH_MODEL,
            Prefs.DASH_MODEL_QWEN3_REALTIME,
            Prefs.DASH_MODEL_FUN_ASR_REALTIME,
        )
        val tvDashModel = binding.view<TextView>(R.id.tvDashModelValue)

        fun normalizeModel(model: String): String {
            return model.trim().ifBlank { Prefs.DEFAULT_DASH_MODEL }
        }

        fun updateModelSummary() {
            val cur = normalizeModel(binding.prefs.dashAsrModel)
            val idx = modelValues.indexOf(cur).coerceAtLeast(0)
            tvDashModel.text = modelLabels[idx]
            updateDashPromptVisibility(binding, cur)
        }

        updateModelSummary()
        tvDashModel.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = normalizeModel(binding.prefs.dashAsrModel)
            val curIdx = modelValues.indexOf(cur).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_dash_model, modelLabels.toTypedArray(), curIdx) { which ->
                val value = modelValues.getOrNull(which) ?: Prefs.DEFAULT_DASH_MODEL
                if (value != binding.prefs.dashAsrModel) binding.prefs.dashAsrModel = value
                updateModelSummary()
            }
        }
    }

    private fun updateDashPromptVisibility(binding: AsrSettingsBinding, model: String = binding.prefs.dashAsrModel) {
        val isFunAsr = model.startsWith("fun-asr", ignoreCase = true)

        val til = binding.view<View>(R.id.tilDashPrompt)
        val promptVis = if (!isFunAsr) View.VISIBLE else View.GONE
        if (til.visibility != promptVis) til.visibility = promptVis

        val switchSemanticPunct = binding.view<View>(R.id.switchDashFunAsrSemanticPunct)
        val semanticVis = if (isFunAsr) View.VISIBLE else View.GONE
        if (switchSemanticPunct.visibility != semanticVis) switchSemanticPunct.visibility = semanticVis
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
        val tvDashLanguage = binding.view<TextView>(R.id.tvDashLanguageValue)

        fun updateDashLangSummary() {
            val idx = langCodes.indexOf(binding.prefs.dashLanguage).coerceAtLeast(0)
            tvDashLanguage.text = langLabels[idx]
        }

        updateDashLangSummary()
        tvDashLanguage.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(binding.prefs.dashLanguage).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_dash_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                if (code != binding.prefs.dashLanguage) binding.prefs.dashLanguage = code
                updateDashLangSummary()
            }
        }
    }

    private fun bindRegionSelection(binding: AsrSettingsBinding) {
        val regionLabels = listOf(
            binding.activity.getString(R.string.dash_region_cn),
            binding.activity.getString(R.string.dash_region_intl),
        )
        val regionValues = listOf("cn", "intl")
        val tvDashRegion = binding.view<TextView>(R.id.tvDashRegionValue)

        fun updateRegionSummary() {
            val idx = regionValues.indexOf(binding.prefs.dashRegion.ifBlank { "cn" }).coerceAtLeast(0)
            tvDashRegion.text = regionLabels[idx]
        }

        updateRegionSummary()
        tvDashRegion.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val cur = regionValues.indexOf(binding.prefs.dashRegion.ifBlank { "cn" }).coerceAtLeast(0)
            binding.showSingleChoiceDialog(R.string.label_dash_region, regionLabels.toTypedArray(), cur) { which ->
                val value = regionValues.getOrNull(which) ?: "cn"
                if (value != binding.prefs.dashRegion) binding.prefs.dashRegion = value
                updateRegionSummary()
            }
        }
    }
}
