package com.brycewg.asrkb.ui.settings.asr.sections

import android.view.View
import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsUiState
import com.google.android.material.materialswitch.MaterialSwitch

internal class AsrSettingsUiRendererSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        // no-op: renderer only reacts to uiState changes
    }

    override fun render(binding: AsrSettingsBinding, state: AsrSettingsUiState) {
        updateVendorSummary(binding, state.selectedVendor)
        updateBackupVendorSummary(binding)
        updateVendorVisibility(binding, state)
        updateSilenceOptionsVisibility(binding, state.autoStopSilenceEnabled)
        updateSfOmniVisibility(binding, state.sfUseOmni)
        updateOpenAiPromptVisibility(binding, state.oaAsrUsePrompt)
        updateVolcFileModeVisibility(binding, state.volcStreamingEnabled)
        updateVolcModelV2Visibility(binding, state.volcStreamingEnabled, state.volcFileStandardEnabled)

        binding.view<MaterialSwitch>(R.id.switchVolcFileStandard).let { sw ->
            if (sw.isChecked != state.volcFileStandardEnabled) {
                sw.isChecked = state.volcFileStandardEnabled
            }
        }
        binding.view<MaterialSwitch>(R.id.switchVolcModelV2).let { sw ->
            if (sw.isChecked != state.volcModelV2Enabled) {
                sw.isChecked = state.volcModelV2Enabled
            }
        }

        updateVolcStreamOptionsVisibility(binding, state.volcStreamingEnabled)
        updateVolcTwoPassVisibility(binding, state.volcStreamingEnabled)
    }

    private fun updateVendorSummary(binding: AsrSettingsBinding, vendor: AsrVendor) {
        val vendorOrder = AsrVendorUi.ordered()
        val vendorItems = AsrVendorUi.names(binding.activity)
        val idx = vendorOrder.indexOf(vendor).coerceAtLeast(0)
        binding.view<TextView>(R.id.tvAsrVendorValue).text = vendorItems[idx]
    }

    private fun updateBackupVendorSummary(binding: AsrSettingsBinding) {
        val vendorOrder = AsrVendorUi.ordered()
        val vendorItems = AsrVendorUi.names(binding.activity)
        val idx = vendorOrder.indexOf(binding.prefs.backupAsrVendor).coerceAtLeast(0)
        binding.view<TextView>(R.id.tvBackupAsrVendorValue).text = vendorItems[idx]
    }

    private fun updateVendorVisibility(binding: AsrSettingsBinding, state: AsrSettingsUiState) {
        val visMap = mapOf(
            AsrVendor.Volc to binding.view<View>(R.id.groupVolc),
            AsrVendor.SiliconFlow to binding.view<View>(R.id.groupSf),
            AsrVendor.ElevenLabs to binding.view<View>(R.id.groupEleven),
            AsrVendor.OpenAI to binding.view<View>(R.id.groupOpenAI),
            AsrVendor.DashScope to binding.view<View>(R.id.groupDashScope),
            AsrVendor.Gemini to binding.view<View>(R.id.groupGemini),
            AsrVendor.Soniox to binding.view<View>(R.id.groupSoniox),
            AsrVendor.Zhipu to binding.view<View>(R.id.groupZhipu),
            AsrVendor.SenseVoice to binding.view<View>(R.id.groupSenseVoice),
            AsrVendor.FunAsrNano to binding.view<View>(R.id.groupFunAsrNano),
            AsrVendor.Telespeech to binding.view<View>(R.id.groupTelespeech),
            AsrVendor.Paraformer to binding.view<View>(R.id.groupParaformer),
        )
        visMap.forEach { (vendor, view) ->
            val vis = if (vendor == state.selectedVendor) View.VISIBLE else View.GONE
            if (view.visibility != vis) view.visibility = vis
        }
    }

    private fun updateSilenceOptionsVisibility(binding: AsrSettingsBinding, enabled: Boolean) {
        val vis = if (enabled) View.VISIBLE else View.GONE
        binding.view<View>(R.id.tvSilenceWindowLabel).let { if (it.visibility != vis) it.visibility = vis }
        binding.view<View>(R.id.sliderSilenceWindow).let { if (it.visibility != vis) it.visibility = vis }
        binding.view<View>(R.id.tvSilenceSensitivityLabel).let { if (it.visibility != vis) it.visibility = vis }
        binding.view<View>(R.id.sliderSilenceSensitivity).let { if (it.visibility != vis) it.visibility = vis }
    }

    private fun updateSfOmniVisibility(binding: AsrSettingsBinding, enabled: Boolean) {
        val til = binding.view<View>(R.id.tilSfOmniPrompt)
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (til.visibility != vis) til.visibility = vis
    }

    private fun updateOpenAiPromptVisibility(binding: AsrSettingsBinding, enabled: Boolean) {
        val til = binding.view<View>(R.id.tilOpenAiPrompt)
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (til.visibility != vis) til.visibility = vis
    }

    private fun updateVolcFileModeVisibility(binding: AsrSettingsBinding, streamingEnabled: Boolean) {
        val sw = binding.view<MaterialSwitch>(R.id.switchVolcFileStandard)
        val vis = if (streamingEnabled) View.GONE else View.VISIBLE
        if (sw.visibility != vis) sw.visibility = vis
    }

    private fun updateVolcModelV2Visibility(binding: AsrSettingsBinding, streamingEnabled: Boolean, fileStandardEnabled: Boolean) {
        val sw = binding.view<MaterialSwitch>(R.id.switchVolcModelV2)
        val vis = if (streamingEnabled || fileStandardEnabled) View.VISIBLE else View.GONE
        if (sw.visibility != vis) sw.visibility = vis
    }

    private fun updateVolcStreamOptionsVisibility(binding: AsrSettingsBinding, enabled: Boolean) {
        val vis = if (enabled) View.VISIBLE else View.GONE
        fun setIfChanged(v: View) {
            if (v.visibility != vis) v.visibility = vis
        }
        setIfChanged(binding.view(R.id.switchVolcVad))
        setIfChanged(binding.view(R.id.tvVolcLanguageValue))
        setIfChanged(binding.view(R.id.tvVolcLanguageLabel))
    }

    private fun updateVolcTwoPassVisibility(binding: AsrSettingsBinding, streamingEnabled: Boolean) {
        val vis = if (streamingEnabled) View.VISIBLE else View.GONE
        val v = binding.view<View>(R.id.switchVolcNonstream)
        if (v.visibility != vis) v.visibility = vis
    }
}
