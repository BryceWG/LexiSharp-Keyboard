/**
 * 默认 Prompt 预设构建入口。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.content.Context
import com.brycewg.asrkb.R

/**
 * 默认 Prompt 预设（从 [Prefs] 中拆出）。
 */
internal fun buildDefaultPromptPresets(context: Context): List<PromptPreset> {
    return listOf(
        PromptPreset(
            id = DEFAULT_PRESET_GENERAL_ID,
            title = context.getString(R.string.llm_prompt_preset_default_general_title),
            content = context.getString(R.string.llm_prompt_preset_default_general_content),
        ),
        PromptPreset(
            id = DEFAULT_PRESET_POLISH_ID,
            title = context.getString(R.string.llm_prompt_preset_default_polish_title),
            content = context.getString(R.string.llm_prompt_preset_default_polish_content),
        ),
        PromptPreset(
            id = DEFAULT_PRESET_TRANSLATE_EN_ID,
            title = context.getString(R.string.llm_prompt_preset_default_translate_en_title),
            content = context.getString(R.string.llm_prompt_preset_default_translate_en_content),
        ),
        PromptPreset(
            id = DEFAULT_PRESET_KEY_POINTS_ID,
            title = context.getString(R.string.llm_prompt_preset_default_key_points_title),
            content = context.getString(R.string.llm_prompt_preset_default_key_points_content),
        ),
        PromptPreset(
            id = DEFAULT_PRESET_TODO_ID,
            title = context.getString(R.string.llm_prompt_preset_default_todo_title),
            content = context.getString(R.string.llm_prompt_preset_default_todo_content),
        ),
    )
}

private const val DEFAULT_PRESET_GENERAL_ID = "preset.default.general"
private const val DEFAULT_PRESET_POLISH_ID = "preset.default.polish"
private const val DEFAULT_PRESET_TRANSLATE_EN_ID = "preset.default.translate_en"
private const val DEFAULT_PRESET_KEY_POINTS_ID = "preset.default.key_points"
private const val DEFAULT_PRESET_TODO_ID = "preset.default.todo"
