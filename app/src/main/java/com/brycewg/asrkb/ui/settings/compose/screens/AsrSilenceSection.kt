/**
 * Compose ASR 设置页的静音自动停止区块。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import kotlin.math.roundToInt

@Composable
internal fun AsrSilenceSection(
    uiMode: BibiUiMode,
    autoStopMode: Prefs.RecordingAutoStopMode,
    silenceWindowMs: Int,
    silenceSensitivity: Int,
    recordingMaxDurationMs: Int,
    onAutoStopModeChange: (Prefs.RecordingAutoStopMode) -> Unit,
    onWindowChange: (Int) -> Unit,
    onWindowFinished: () -> Unit,
    onSensitivityChange: (Int) -> Unit,
    onSensitivityFinished: () -> Unit,
    onMaxDurationChange: (Int) -> Unit,
    onMaxDurationFinished: () -> Unit
) {
    AsrSection(uiMode = uiMode, titleRes = R.string.section_silence_autostop) {
        val itemCount = when (autoStopMode) {
            Prefs.RecordingAutoStopMode.SILENCE -> 3
            Prefs.RecordingAutoStopMode.MAX_DURATION -> 2
            Prefs.RecordingAutoStopMode.MANUAL -> 1
        }
        AsrDropdownPreference(
            id = "recording_auto_stop_mode",
            titleRes = R.string.label_recording_auto_stop_mode,
            options = listOf(
                DropdownOption(
                    Prefs.RecordingAutoStopMode.MANUAL.id,
                    stringResource(R.string.option_recording_auto_stop_manual)
                ),
                DropdownOption(
                    Prefs.RecordingAutoStopMode.SILENCE.id,
                    stringResource(R.string.option_recording_auto_stop_silence)
                ),
                DropdownOption(
                    Prefs.RecordingAutoStopMode.MAX_DURATION.id,
                    stringResource(R.string.option_recording_auto_stop_max_duration)
                )
            ),
            selectedOptionId = autoStopMode.id,
            index = 0,
            count = itemCount,
            onSelectedOptionChange = { id ->
                onAutoStopModeChange(Prefs.RecordingAutoStopMode.fromId(id))
            }
        )
        if (autoStopMode == Prefs.RecordingAutoStopMode.SILENCE) {
            AsrSliderPreference(
                titleRes = R.string.label_silence_window_ms,
                valueLabel = silenceWindowMs.toString(),
                value = silenceWindowMs.toFloat(),
                valueRange = 300f..5000f,
                steps = 46,
                uiMode = uiMode,
                highlightId = "silence_window_ms",
                index = 1,
                count = itemCount,
                onValueChange = { value -> onWindowChange(value.roundToNearestHundred()) },
                onValueChangeFinished = onWindowFinished
            )
            AsrSliderPreference(
                titleRes = R.string.label_silence_sensitivity,
                valueLabel = silenceSensitivity.toString(),
                value = silenceSensitivity.toFloat(),
                valueRange = 1f..10f,
                steps = 8,
                uiMode = uiMode,
                index = 2,
                count = itemCount,
                onValueChange = { value -> onSensitivityChange(value.toInt()) },
                onValueChangeFinished = onSensitivityFinished
            )
        }
        if (autoStopMode == Prefs.RecordingAutoStopMode.MAX_DURATION) {
            val durationRange = Prefs.RECORDING_MAX_DURATION_MIN_MS.toFloat()..
                Prefs.RECORDING_MAX_DURATION_MAX_MS.toFloat()
            AsrSliderPreference(
                titleRes = R.string.label_recording_max_duration,
                valueLabel = stringResource(
                    R.string.label_recording_max_duration_value_s,
                    recordingMaxDurationMs / 1000
                ),
                value = recordingMaxDurationMs.toFloat(),
                valueRange = durationRange,
                steps = maxDurationSliderSteps(),
                uiMode = uiMode,
                showKeyPoints = false,
                highlightId = "recording_max_duration",
                index = 1,
                count = itemCount,
                onValueChange = { value -> onMaxDurationChange(value.roundToNearestDurationStep()) },
                onValueChangeFinished = onMaxDurationFinished
            )
        }
    }
}

private fun Float.roundToNearestHundred(): Int = ((this / 100f).roundToInt() * 100).coerceIn(300, 5000)

private fun Float.roundToNearestDurationStep(): Int {
    val step = Prefs.RECORDING_MAX_DURATION_STEP_MS
    val rounded = (this / step.toFloat()).roundToInt() * step
    return rounded.coerceIn(Prefs.RECORDING_MAX_DURATION_MIN_MS, Prefs.RECORDING_MAX_DURATION_MAX_MS)
}

private fun maxDurationSliderSteps(): Int {
    val intervals = (Prefs.RECORDING_MAX_DURATION_MAX_MS - Prefs.RECORDING_MAX_DURATION_MIN_MS) /
        Prefs.RECORDING_MAX_DURATION_STEP_MS
    return (intervals - 1).coerceAtLeast(0)
}
