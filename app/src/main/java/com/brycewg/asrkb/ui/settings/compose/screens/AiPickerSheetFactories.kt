/**
 * Compose AI 设置页选择弹层的状态构造辅助函数。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.asr.partitionLlmVendorsByConfigured
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceGroup
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceItem
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMultiChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.settingsChoiceSheetState

internal fun aiSimpleChoiceSheetState(
    context: Context,
    titleRes: Int,
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
): SettingsChoiceSheetState? = settingsChoiceSheetState(
    title = context.getString(titleRes),
    items = items,
    selectedIndex = selectedIndex,
    onSelected = onSelected
)

internal fun llmVendorChoiceSheetState(
    context: Context,
    prefs: Prefs,
    selectedVendor: LlmVendor,
    onSelected: (LlmVendor) -> Unit
): SettingsChoiceSheetState {
    val vendors = LlmVendor.allVendors()
    val vendorItems = vendors.mapIndexed { index, vendor ->
        SettingsChoiceItem(
            title = context.getString(vendor.displayNameResId),
            originalIndex = index,
            tags = emptyList()
        )
    }
    val indexByVendor = vendors.withIndex().associate { it.value to it.index }
    val partition = partitionLlmVendorsByConfigured(prefs, vendors)
    return SettingsChoiceSheetState(
        title = context.getString(R.string.label_llm_vendor),
        groups = listOf(
            SettingsChoiceGroup(
                label = context.getString(R.string.llm_vendor_group_configured),
                items = partition.configured.mapNotNull { vendor ->
                    indexByVendor[vendor]?.let { idx -> vendorItems[idx] }
                }
            ),
            SettingsChoiceGroup(
                label = context.getString(R.string.llm_vendor_group_unconfigured),
                items = partition.unconfigured.mapNotNull { vendor ->
                    indexByVendor[vendor]?.let { idx -> vendorItems[idx] }
                }
            )
        ),
        selectedIndex = vendors.indexOf(selectedVendor).coerceAtLeast(0),
        onSelected = { which ->
            vendors.getOrNull(which)?.let(onSelected)
        }
    )
}

internal fun aiModelChoiceSheetState(
    context: Context,
    titleRes: Int,
    presetModels: List<String>,
    currentModel: String,
    blankAsCustom: Boolean = true,
    onCustomSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onAfterSelected: () -> Unit = {}
): SettingsChoiceSheetState? {
    val customOption = context.getString(R.string.option_custom_model)
    val models = presetModels + customOption
    val isCurrentCustom = (blankAsCustom && currentModel.isBlank()) ||
        (currentModel.isNotBlank() && !presetModels.contains(currentModel))
    val selectedIndex = if (isCurrentCustom) {
        models.lastIndex
    } else {
        presetModels.indexOf(currentModel).coerceAtLeast(0)
    }
    return aiSimpleChoiceSheetState(
        context = context,
        titleRes = titleRes,
        items = models,
        selectedIndex = selectedIndex
    ) { which ->
        if (which == models.lastIndex) {
            onCustomSelected(currentModel.takeIf { it.isNotBlank() && !presetModels.contains(it) }.orEmpty())
        } else {
            presetModels.getOrNull(which)?.let(onModelSelected)
        }
        onAfterSelected()
    }
}

internal fun aiProfileChoiceSheetState(
    context: Context,
    profiles: List<Prefs.LlmProvider>,
    selectedIndex: Int,
    onSelected: (Prefs.LlmProvider) -> Unit
): SettingsChoiceSheetState? {
    if (profiles.isEmpty()) return null
    return aiSimpleChoiceSheetState(
        context = context,
        titleRes = R.string.label_llm_choose_profile,
        items = profiles.map { it.name.ifBlank { context.getString(R.string.untitled_profile) } },
        selectedIndex = selectedIndex
    ) { which ->
        profiles.getOrNull(which)?.let(onSelected)
    }
}

internal fun aiPromptPresetChoiceSheetState(
    context: Context,
    promptPresets: List<PromptPreset>,
    selectedIndex: Int,
    onSelected: (PromptPreset) -> Unit
): SettingsChoiceSheetState? {
    if (promptPresets.isEmpty()) return null
    return aiSimpleChoiceSheetState(
        context = context,
        titleRes = R.string.label_llm_prompt_presets,
        items = promptPresets.map { it.title.ifBlank { context.getString(R.string.untitled_preset) } },
        selectedIndex = selectedIndex
    ) { which ->
        promptPresets.getOrNull(which)?.let(onSelected)
    }
}

internal fun aiModelsMultiChoiceSheetState(
    context: Context,
    models: List<String>,
    currentModels: List<String>,
    onConfirmModels: (List<String>) -> Boolean
): SettingsMultiChoiceSheetState? {
    val uniqueModels = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (uniqueModels.isEmpty()) return null
    return SettingsMultiChoiceSheetState(
        title = context.getString(R.string.llm_models_select_title),
        items = uniqueModels,
        checkedIndices = uniqueModels.withIndex()
            .filter { currentModels.contains(it.value) }
            .map { it.index }
            .toSet(),
        confirmText = context.getString(R.string.btn_llm_models_add),
        cancelText = context.getString(R.string.btn_cancel),
        onConfirm = { selectedIndices ->
            onConfirmModels(
                uniqueModels.filterIndexed { index, _ -> selectedIndices.contains(index) }
            )
        }
    )
}
