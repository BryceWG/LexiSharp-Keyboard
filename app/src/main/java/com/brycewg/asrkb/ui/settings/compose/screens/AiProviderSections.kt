/**
 * Compose AI 设置页的供应商与提示词表单组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode

@Composable
internal fun SfFreeLlmSection(
    uiMode: BibiUiMode,
    presetModels: List<String>,
    staticModels: List<String>,
    sfUseFreeService: Boolean,
    sfApiKey: String,
    sfModel: String,
    sfReasoningEnabled: Boolean,
    sfReasoningOnJson: String,
    sfReasoningOffJson: String,
    customReasoningParamsEnabled: Boolean,
    sfTemperature: Float,
    customModelInputVisible: Boolean,
    testEnabled: Boolean,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null,
    actions: AiVendorActions
) {
    val showCustomModel =
        customModelInputVisible || (sfModel.isNotBlank() && !presetModels.contains(sfModel))
    val showCustomReasoningParams = sfModel.isNotBlank() && !staticModels.contains(sfModel)
    val showReasoning =
        LlmVendor.SF_FREE.supportsReasoningControl(sfModel) || showCustomReasoningParams

    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: sfFreeLlmPrimaryItemCount(
        presetModels = presetModels,
        sfUseFreeService = sfUseFreeService,
        sfModel = sfModel,
        customModelInputVisible = customModelInputVisible
    )
    AiSwitchPreference(
        id = "sf_free_service",
        titleRes = R.string.label_sf_use_free_service,
        checked = sfUseFreeService,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = actions.onToggleSfFree
    )
    if (!sfUseFreeService) {
        AiTextField(
            uiMode = uiMode,
            value = sfApiKey,
            onValueChange = actions.onSfApiKeyChange,
            label = stringResource(R.string.label_llm_api_key),
            password = true,
            index = itemIndex++,
            count = itemCount
        )
    }
    AiValuePreference(
        titleRes = R.string.label_sf_free_llm_model,
        value = sfModel.ifBlank { LlmVendor.SF_FREE.defaultModel },
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        trailingActionIcon = if (!sfUseFreeService) Icons.Rounded.CloudDownload else null,
        trailingActionContentDescriptionRes = if (!sfUseFreeService) R.string.btn_llm_fetch_models else null,
        onTrailingActionClick = if (!sfUseFreeService) actions.onFetchModels else null,
        onClick = actions.onShowModelDialog
    )
    if (showCustomModel) {
        AiTextField(
            uiMode = uiMode,
            value = sfModel,
            onValueChange = actions.onCustomModelChange,
            label = stringResource(R.string.label_custom_model_id),
            index = itemIndex++,
            count = itemCount
        )
    }
    if (!sfUseFreeService) {
        AiSliderPreference(
            titleRes = R.string.label_llm_temperature,
            valueLabel = { formatTemperature(it) },
            value = sfTemperature,
            valueRange = 0f..2f,
            steps = 19,
            uiMode = uiMode,
            index = itemIndex,
            count = itemCount,
            onValueChange = actions.onTemperatureChange,
            onValueChangeFinished = { actions.onTestHaptic() }
        )
    }
    if (showReasoning) {
        ReasoningSection(
            uiMode = uiMode,
            checked = sfReasoningEnabled,
            showParams = showCustomReasoningParams,
            customParamsEnabled = customReasoningParamsEnabled,
            onCheckedChange = actions.onReasoningChange,
            onCustomParamsEnabledChange = actions.onCustomReasoningParamsEnabledChange,
            onJson = sfReasoningOnJson,
            offJson = sfReasoningOffJson,
            onOnJsonChange = actions.onReasoningOnJsonChange,
            onOffJsonChange = actions.onReasoningOffJsonChange
        )
    }
    AiBodyText(uiMode = uiMode, textRes = R.string.sf_free_register_hint)
    AiButtonRow(uiMode = uiMode) {
        AiButton(uiMode = uiMode, textRes = R.string.btn_get_api_key, onClick = actions.onOpenRegister)
        AiButton(
            uiMode = uiMode,
            textRes = R.string.btn_llm_test_call,
            enabled = testEnabled,
            onClick = actions.onTestCall
        )
    }
    if (sfUseFreeService) {
        AiBodyText(uiMode = uiMode, textRes = R.string.sf_free_service_desc)
        SiliconFlowPoweredByImage()
    }
}

@Composable
internal fun BuiltinLlmSection(
    uiMode: BibiUiMode,
    vendor: LlmVendor,
    config: AiPostSettingsViewModel.BuiltinVendorConfig,
    presetModels: List<String>,
    customModelInputVisible: Boolean,
    testEnabled: Boolean,
    reasoningOnJson: String,
    reasoningOffJson: String,
    onApiKeyChange: (String) -> Unit,
    onChooseModel: () -> Unit,
    onCustomModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onReasoningChange: (Boolean) -> Unit,
    onCustomReasoningParamsEnabledChange: (Boolean) -> Unit,
    onReasoningOnJsonChange: (String) -> Unit,
    onReasoningOffJsonChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onOpenRegister: () -> Unit,
    onTestCall: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    val displayModel = config.model.ifBlank { vendor.defaultModel }
    val isPresetModel = displayModel.isNotBlank() && presetModels.contains(displayModel)
    val isBuiltinModel = displayModel.isNotBlank() && vendor.models.contains(displayModel)
    val showCustomModel =
        customModelInputVisible || (displayModel.isNotBlank() && !isPresetModel)
    val showCustomReasoningParams = displayModel.isNotBlank() && !isBuiltinModel
    val showReasoning = vendor.supportsReasoningControl(displayModel) || showCustomReasoningParams

    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: builtinLlmPrimaryItemCount(
        vendor = vendor,
        config = config,
        presetModels = presetModels,
        customModelInputVisible = customModelInputVisible
    )
    AiTextField(
        uiMode = uiMode,
        value = config.apiKey,
        onValueChange = onApiKeyChange,
        label = stringResource(R.string.label_llm_api_key),
        password = true,
        index = itemIndex++,
        count = itemCount
    )
    AiValuePreference(
        titleRes = R.string.label_llm_model_select,
        value = displayModel,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        trailingActionIcon = Icons.Rounded.CloudDownload,
        trailingActionContentDescriptionRes = R.string.btn_llm_fetch_models,
        onTrailingActionClick = onFetchModels,
        onClick = onChooseModel
    )
    if (showCustomModel) {
        AiTextField(
            uiMode = uiMode,
            value = displayModel,
            onValueChange = onCustomModelChange,
            label = stringResource(R.string.label_custom_model_id),
            index = itemIndex++,
            count = itemCount
        )
    }
    AiSliderPreference(
        titleRes = R.string.label_llm_temperature,
        valueLabel = { formatTemperature(it.coerceIn(vendor.temperatureMin, vendor.temperatureMax)) },
        value = config.temperature.coerceIn(vendor.temperatureMin, vendor.temperatureMax),
        valueRange = vendor.temperatureMin..vendor.temperatureMax,
        steps = temperatureSteps(vendor),
        uiMode = uiMode,
        index = itemIndex,
        count = itemCount,
        onValueChange = onTemperatureChange
    )
    if (showReasoning) {
        ReasoningSection(
            uiMode = uiMode,
            checked = config.reasoningEnabled,
            showParams = showCustomReasoningParams,
            customParamsEnabled = config.customReasoningParamsEnabled,
            onCheckedChange = onReasoningChange,
            onCustomParamsEnabledChange = onCustomReasoningParamsEnabledChange,
            onJson = reasoningOnJson,
            offJson = reasoningOffJson,
            onOnJsonChange = onReasoningOnJsonChange,
            onOffJsonChange = onReasoningOffJsonChange
        )
    }
    AiButtonRow(uiMode = uiMode) {
        if (vendor.registerUrl.isNotBlank()) {
            AiButton(uiMode = uiMode, textRes = R.string.btn_llm_register, onClick = onOpenRegister)
        }
        AiButton(
            uiMode = uiMode,
            textRes = R.string.btn_llm_test_call,
            enabled = testEnabled,
            onClick = onTestCall
        )
    }
}

@Composable
internal fun CustomLlmSection(
    uiMode: BibiUiMode,
    provider: Prefs.LlmProvider?,
    customModelInputVisible: Boolean,
    focusProfileNameAfterAdd: Boolean,
    testEnabled: Boolean,
    onFocusedProfileName: () -> Unit,
    onChooseProfile: () -> Unit,
    onProfileNameChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onChooseModel: () -> Unit,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onReasoningChange: (Boolean) -> Unit,
    onCustomReasoningParamsEnabledChange: (Boolean) -> Unit,
    onReasoningOnJsonChange: (String) -> Unit,
    onReasoningOffJsonChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onAddProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onTestCall: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    val profileName = provider?.name.orEmpty()
    val displayName = profileName.ifBlank { stringResource(R.string.untitled_profile) }
    val presetModels = provider?.models.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
    val model = provider?.model.orEmpty()
    val hasPresetModels = presetModels.isNotEmpty()
    val showFetchButton = !(customModelInputVisible && hasPresetModels)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusProfileNameAfterAdd) {
        if (focusProfileNameAfterAdd) {
            focusRequester.requestFocus()
            onFocusedProfileName()
        }
    }

    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: customLlmPrimaryItemCount(customModelInputVisible)
    AiValuePreference(
        titleRes = R.string.label_llm_choose_profile,
        value = displayName,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onClick = onChooseProfile
    )
    AiTextField(
        uiMode = uiMode,
        value = profileName,
        onValueChange = onProfileNameChange,
        label = stringResource(R.string.label_llm_profile_name),
        modifier = Modifier.focusRequester(focusRequester),
        index = itemIndex++,
        count = itemCount
    )
    AiTextField(
        uiMode = uiMode,
        value = provider?.endpoint.orEmpty(),
        onValueChange = onEndpointChange,
        label = stringResource(R.string.label_llm_endpoint),
        index = itemIndex++,
        count = itemCount
    )
    AiTextField(
        uiMode = uiMode,
        value = provider?.apiKey.orEmpty(),
        onValueChange = onApiKeyChange,
        label = stringResource(R.string.label_llm_api_key),
        password = true,
        index = itemIndex++,
        count = itemCount
    )
    AiValuePreference(
        titleRes = R.string.label_llm_model_select,
        value = model.ifBlank { stringResource(R.string.option_custom_model) },
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        trailingActionIcon = if (showFetchButton) Icons.Rounded.CloudDownload else null,
        trailingActionContentDescriptionRes = if (showFetchButton) R.string.btn_llm_fetch_models else null,
        onTrailingActionClick = if (showFetchButton) onFetchModels else null,
        onClick = onChooseModel
    )
    if (customModelInputVisible) {
        AiTextField(
            uiMode = uiMode,
            value = model,
            onValueChange = onModelChange,
            label = stringResource(R.string.label_custom_model_id),
            index = itemIndex++,
            count = itemCount
        )
    }
    AiSliderPreference(
        titleRes = R.string.label_llm_temperature,
        valueLabel = { formatTemperature(it.coerceIn(0f, 2f)) },
        value = (provider?.temperature ?: Prefs.DEFAULT_LLM_TEMPERATURE).coerceIn(0f, 2f),
        valueRange = 0f..2f,
        steps = 19,
        uiMode = uiMode,
        index = itemIndex,
        count = itemCount,
        onValueChange = onTemperatureChange
    )
    ReasoningSection(
        uiMode = uiMode,
        checked = provider?.enableReasoning ?: false,
        showParams = true,
        customParamsEnabled = provider?.useCustomReasoningParams ?: false,
        onCheckedChange = onReasoningChange,
        onCustomParamsEnabledChange = onCustomReasoningParamsEnabledChange,
        onJson = provider?.reasoningParamsOnJson.orEmpty(),
        offJson = provider?.reasoningParamsOffJson.orEmpty(),
        onOnJsonChange = onReasoningOnJsonChange,
        onOffJsonChange = onReasoningOffJsonChange
    )
    AiButtonRow(uiMode = uiMode) {
        AiButton(uiMode = uiMode, textRes = R.string.btn_llm_add_profile, onClick = onAddProfile)
        AiButton(uiMode = uiMode, textRes = R.string.btn_llm_delete_profile, onClick = onDeleteProfile)
        AiButton(
            uiMode = uiMode,
            textRes = R.string.btn_llm_test_call,
            enabled = testEnabled,
            onClick = onTestCall
        )
    }
}

@Composable
private fun ReasoningSection(
    uiMode: BibiUiMode,
    checked: Boolean,
    showParams: Boolean,
    customParamsEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onCustomParamsEnabledChange: (Boolean) -> Unit,
    onJson: String,
    offJson: String,
    onOnJsonChange: (String) -> Unit,
    onOffJsonChange: (String) -> Unit
) {
    var itemIndex = 0
    val itemCount = 1 + (if (showParams) 1 else 0) + (if (showParams && customParamsEnabled) 2 else 0)

    AiSwitchPreference(
        id = "reasoning_mode",
        titleRes = R.string.label_reasoning_mode,
        summaryRes = R.string.hint_reasoning_mode,
        checked = checked,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = onCheckedChange
    )
    if (showParams) {
        AiSwitchPreference(
            id = "custom_reasoning_params",
            titleRes = R.string.label_custom_reasoning_params,
            checked = customParamsEnabled,
            index = itemIndex++,
            count = itemCount,
            onCheckedChange = onCustomParamsEnabledChange
        )
        if (customParamsEnabled) {
            AiTextField(
                uiMode = uiMode,
                value = onJson,
                onValueChange = onOnJsonChange,
                label = stringResource(R.string.label_reasoning_params_on_json),
                singleLine = false,
                index = itemIndex++,
                count = itemCount
            )
            AiTextField(
                uiMode = uiMode,
                value = offJson,
                onValueChange = onOffJsonChange,
                label = stringResource(R.string.label_reasoning_params_off_json),
                singleLine = false,
                index = itemIndex,
                count = itemCount
            )
            AiBodyText(uiMode = uiMode, textRes = R.string.hint_reasoning_params_json)
        }
    }
}

internal data class AiVendorActions(
    val onToggleSfFree: (Boolean) -> Unit,
    val onSfApiKeyChange: (String) -> Unit,
    val onShowModelDialog: () -> Unit,
    val onCustomModelChange: (String) -> Unit,
    val onFetchModels: () -> Unit,
    val onReasoningChange: (Boolean) -> Unit,
    val onCustomReasoningParamsEnabledChange: (Boolean) -> Unit,
    val onReasoningOnJsonChange: (String) -> Unit,
    val onReasoningOffJsonChange: (String) -> Unit,
    val onTemperatureChange: (Float) -> Unit,
    val onOpenRegister: () -> Unit,
    val onTestCall: () -> Unit,
    val onTestHaptic: () -> Unit = {}
)

internal fun sfFreeLlmPrimaryItemCount(
    presetModels: List<String>,
    sfUseFreeService: Boolean,
    sfModel: String,
    customModelInputVisible: Boolean
): Int {
    val showCustomModel =
        customModelInputVisible || (sfModel.isNotBlank() && !presetModels.contains(sfModel))
    return 1 +
        (if (!sfUseFreeService) 1 else 0) +
        1 +
        (if (showCustomModel) 1 else 0) +
        (if (!sfUseFreeService) 1 else 0)
}

internal fun builtinLlmPrimaryItemCount(
    vendor: LlmVendor,
    config: AiPostSettingsViewModel.BuiltinVendorConfig,
    presetModels: List<String>,
    customModelInputVisible: Boolean
): Int {
    val displayModel = config.model.ifBlank { vendor.defaultModel }
    val isPresetModel = displayModel.isNotBlank() && presetModels.contains(displayModel)
    val showCustomModel =
        customModelInputVisible || (displayModel.isNotBlank() && !isPresetModel)
    return 3 + (if (showCustomModel) 1 else 0)
}

internal fun customLlmPrimaryItemCount(customModelInputVisible: Boolean): Int = 6 + (if (customModelInputVisible) 1 else 0)
