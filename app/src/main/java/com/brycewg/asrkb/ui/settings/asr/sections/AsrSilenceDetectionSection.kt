package com.brycewg.asrkb.ui.settings.asr.sections

import android.widget.Toast
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.VadDetector
import com.brycewg.asrkb.ui.installExplainedSwitch
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

internal class AsrSilenceDetectionSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        val switchAutoStopSilence = binding.view<MaterialSwitch>(R.id.switchAutoStopSilence)
        val sliderSilenceWindow = binding.view<Slider>(R.id.sliderSilenceWindow)
        val sliderSilenceSensitivity = binding.view<Slider>(R.id.sliderSilenceSensitivity)

        // Initial values
        switchAutoStopSilence.isChecked = binding.prefs.autoStopOnSilenceEnabled
        sliderSilenceWindow.value = binding.prefs.autoStopSilenceWindowMs.toFloat()
        sliderSilenceSensitivity.value = binding.prefs.autoStopSilenceSensitivity.toFloat()

        switchAutoStopSilence.installExplainedSwitch(
            context = binding.activity,
            titleRes = R.string.label_auto_stop_silence,
            offDescRes = R.string.feature_auto_stop_silence_off_desc,
            onDescRes = R.string.feature_auto_stop_silence_on_desc,
            preferenceKey = "auto_stop_silence_explained",
            readPref = { binding.prefs.autoStopOnSilenceEnabled },
            writePref = { v -> binding.viewModel.updateAutoStopSilence(v) },
            onChanged = { enabled ->
                if (enabled) {
                    try {
                        VadDetector.preload(binding.activity.applicationContext, 16000, binding.prefs.autoStopSilenceSensitivity)
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "Failed to preload VAD", t)
                    }
                }
            },
            hapticFeedback = { binding.hapticTapIfEnabled(it) },
        )

        binding.setupSlider(sliderSilenceWindow) { value ->
            binding.viewModel.updateSilenceWindow(value.toInt().coerceIn(300, 5000))
        }

        binding.setupSlider(sliderSilenceSensitivity) { value ->
            binding.viewModel.updateSilenceSensitivity(value.toInt().coerceIn(1, 10))
        }

        sliderSilenceSensitivity.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                try {
                    if (binding.prefs.autoStopOnSilenceEnabled) {
                        VadDetector.rebuildGlobal(
                            binding.activity.applicationContext,
                            16000,
                            binding.prefs.autoStopSilenceSensitivity,
                        )
                        Toast.makeText(binding.activity, R.string.toast_vad_sensitivity_applied, Toast.LENGTH_SHORT).show()
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "Failed to rebuild VAD", t)
                }
            }
        })
    }

    private companion object {
        private const val TAG = "AsrSilenceDetectionSection"
    }
}
