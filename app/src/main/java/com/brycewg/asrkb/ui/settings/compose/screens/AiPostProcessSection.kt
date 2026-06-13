/**
 * Compose AI 设置页后处理范围设置区块。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode

@Composable
internal fun AiPostProcessSection(
    uiMode: BibiUiMode,
    postProcessEnabled: Boolean,
    typewriterEnabled: Boolean,
    aiEditPreferLastAsr: Boolean,
    skipUnderChars: Int,
    aiEditSystemPrompt: String,
    onPostProcessChange: (Boolean) -> Unit,
    onTypewriterChange: (Boolean) -> Unit,
    onAiEditPreferLastAsrChange: (Boolean) -> Unit,
    onSkipUnderCharsChange: (Int) -> Unit,
    onSkipUnderCharsFinished: () -> Unit,
    onAiEditSystemPromptChange: (String) -> Unit
) {
    AiSection(uiMode = uiMode, titleRes = R.string.section_post_process_scope) {
        val itemCount = if (postProcessEnabled) 5 else 1
        AiSwitchPreference(
            id = "post_process_enabled",
            titleRes = R.string.label_ai_post_process_enabled,
            checked = postProcessEnabled,
            index = 0,
            count = itemCount,
            onCheckedChange = onPostProcessChange
        )
        if (postProcessEnabled) {
            AiSwitchPreference(
                id = "postproc_typewriter",
                titleRes = R.string.label_postproc_typewriter_enabled,
                checked = typewriterEnabled,
                index = 1,
                count = itemCount,
                onCheckedChange = onTypewriterChange
            )
            AiSwitchPreference(
                id = "ai_edit_prefer_last_asr",
                titleRes = R.string.label_ai_edit_default_use_last_asr,
                checked = aiEditPreferLastAsr,
                index = 2,
                count = itemCount,
                onCheckedChange = onAiEditPreferLastAsrChange
            )
            AiSliderPreference(
                titleRes = R.string.title_ai_skip_under,
                valueLabel = skipUnderChars.toString(),
                value = skipUnderChars.toFloat(),
                valueRange = 0f..100f,
                steps = 19,
                uiMode = uiMode,
                index = 3,
                count = itemCount,
                onValueChange = { value ->
                    onSkipUnderCharsChange(value.toInt().coerceIn(0, 100))
                },
                onValueChangeFinished = onSkipUnderCharsFinished
            )
            AiBodyText(uiMode = uiMode, textRes = R.string.helper_ai_skip_under_chars)
            AiTextField(
                uiMode = uiMode,
                value = aiEditSystemPrompt,
                onValueChange = onAiEditSystemPromptChange,
                label = stringResource(R.string.label_ai_edit_system_prompt),
                singleLine = false,
                minLines = 3,
                index = 4,
                count = itemCount
            )
            AiBodyText(uiMode = uiMode, textRes = R.string.helper_ai_edit_system_prompt)
            if (aiEditSystemPrompt.isNotEmpty()) {
                AiButtonRow(uiMode = uiMode) {
                    AiButton(
                        uiMode = uiMode,
                        textRes = R.string.action_reset_to_default,
                        onClick = { onAiEditSystemPromptChange("") }
                    )
                }
            }
        }
    }
}
