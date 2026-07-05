/**
 * Compose 在线 ASR 供应商设置组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.KEY_GEM_API_KEY
import com.brycewg.asrkb.store.KEY_GEM_ENDPOINT
import com.brycewg.asrkb.store.KEY_GEM_MODEL
import com.brycewg.asrkb.store.KEY_GEM_PROMPT
import com.brycewg.asrkb.store.KEY_OPENROUTER_ASR_API_KEY
import com.brycewg.asrkb.store.KEY_OPENROUTER_ASR_ENDPOINT
import com.brycewg.asrkb.store.KEY_OPENROUTER_ASR_MODEL
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.VendorFieldRole
import com.brycewg.asrkb.store.isOpenAiCustomTranscriptionsEndpoint
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CurrentAsrVendorConfig(
    uiMode: BibiUiMode,
    selectedVendor: AsrVendor,
    sfFreeAsrEnabled: Boolean,
    onSfFreeAsrEnabledChange: (Boolean) -> Unit,
    sfFreeAsrModel: String,
    onChooseSfFreeAsrModel: () -> Unit,
    sfApiKey: String,
    onSfApiKeyChange: (String) -> Unit,
    sfModel: String,
    onChooseSfModel: () -> Unit,
    elevenApiKey: String,
    onElevenApiKeyChange: (String) -> Unit,
    elevenStreaming: Boolean,
    onElevenStreamingChange: (Boolean) -> Unit,
    elevenLanguageCode: String,
    onElevenLanguageSelected: (String) -> Unit,
    stepAudioApiKey: String,
    onStepAudioApiKeyChange: (String) -> Unit,
    stepAudioEndpoint: String,
    onStepAudioEndpointChange: (String) -> Unit,
    stepAudioEndpointPreset: String,
    onStepAudioEndpointPresetChange: (String) -> Unit,
    stepAudioModel: String,
    onChooseStepAudioModel: () -> Unit,
    stepAudioLanguage: String,
    onStepAudioLanguageSelected: (String) -> Unit,
    stepAudioUseItn: Boolean,
    onStepAudioUseItnChange: (Boolean) -> Unit,
    zhipuApiKey: String,
    onZhipuApiKeyChange: (String) -> Unit,
    zhipuTemperature: Float,
    onZhipuTemperatureChange: (Float) -> Unit,
    onZhipuTemperatureFinished: () -> Unit,
    geminiApiKey: String,
    onGeminiApiKeyChange: (String) -> Unit,
    geminiEndpoint: String,
    onGeminiEndpointChange: (String) -> Unit,
    geminiModel: String,
    onGeminiModelChange: (String) -> Unit,
    geminiPrompt: String,
    onGeminiPromptChange: (String) -> Unit,
    geminiDisableThinking: Boolean,
    onGeminiDisableThinkingChange: (Boolean) -> Unit,
    openRouterEndpoint: String,
    onOpenRouterEndpointChange: (String) -> Unit,
    openRouterApiKey: String,
    onOpenRouterApiKeyChange: (String) -> Unit,
    openRouterModel: String,
    onOpenRouterModelChange: (String) -> Unit,
    mimoApiKey: String,
    onMimoApiKeyChange: (String) -> Unit,
    mimoEndpoint: String,
    onMimoEndpointChange: (String) -> Unit,
    mimoEndpointPreset: String,
    onMimoEndpointPresetChange: (String) -> Unit,
    mimoLanguage: String,
    onMimoLanguageChange: (String) -> Unit,
    mimoPrompt: String,
    onMimoPromptChange: (String) -> Unit,
    mimoModel: String,
    onMimoModelChange: (String) -> Unit,
    mimoPromptEnabled: Boolean,
    mimoDisableThinking: Boolean,
    onMimoDisableThinkingChange: (Boolean) -> Unit,
    openAiProviders: List<Prefs.OpenAiAsrProvider>,
    openAiActiveProviderId: String,
    onOpenAiProviderSelected: (String) -> Unit,
    onOpenAiProviderAdded: () -> Unit,
    onOpenAiProviderDeleted: () -> Boolean,
    openAiProfileName: String,
    onOpenAiProfileNameChange: (String) -> Unit,
    openAiEndpoint: String,
    onOpenAiEndpointChange: (String) -> Unit,
    openAiApiKey: String,
    onOpenAiApiKeyChange: (String) -> Unit,
    openAiModel: String,
    onOpenAiModelChange: (String) -> Unit,
    openAiStreaming: Boolean,
    onOpenAiStreamingChange: (Boolean) -> Unit,
    openAiUseCompletions: Boolean,
    onOpenAiUseCompletionsChange: (Boolean) -> Unit,
    openAiUsePrompt: Boolean,
    onOpenAiUsePromptChange: (Boolean) -> Unit,
    openAiPrompt: String,
    onOpenAiPromptChange: (String) -> Unit,
    openAiLanguage: String,
    onOpenAiLanguageChange: (String) -> Unit,
    sonioxApiKey: String,
    onSonioxApiKeyChange: (String) -> Unit,
    sonioxStreaming: Boolean,
    onSonioxStreamingChange: (Boolean) -> Unit,
    sonioxEndpointSensitivityLevel: Int,
    onSonioxEndpointSensitivityLevelChange: (Int) -> Unit,
    sonioxLanguages: List<String>,
    onChooseSonioxLanguages: () -> Unit,
    sonioxLanguageStrict: Boolean,
    onSonioxLanguageStrictChange: (Boolean) -> Unit,
    onOpenGuide: (String) -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    val context = LocalContext.current
    when (selectedVendor) {
        AsrVendor.SiliconFlow -> {
            var itemIndex = primaryIndexOffset
            val itemCount = primaryGroupCount ?: currentOnlineAsrPrimaryItemCount(
                selectedVendor = selectedVendor,
                openAiProviders = openAiProviders,
                openAiUsePrompt = openAiUsePrompt,
                openAiUseCompletions = openAiUseCompletions
            )
            AsrSwitchPreference(
                id = "sf_free_asr_enabled",
                titleRes = R.string.label_sf_free_enabled,
                checked = sfFreeAsrEnabled,
                index = itemIndex,
                count = itemCount,
                onCheckedChange = onSfFreeAsrEnabledChange
            )
            if (sfFreeAsrEnabled) {
                AsrValuePreference(
                    titleRes = R.string.label_sf_model_select,
                    value = sfFreeAsrModel.ifBlank { Prefs.DEFAULT_SF_FREE_ASR_MODEL },
                    uiMode = uiMode,
                    onClick = onChooseSfFreeAsrModel
                )
            } else {
                AsrTextField(
                    uiMode = uiMode,
                    value = sfApiKey,
                    onValueChange = onSfApiKeyChange,
                    label = stringResource(R.string.label_sf_api_key),
                    password = true,
                    index = 0,
                    count = 2
                )
                AsrValuePreference(
                    titleRes = R.string.label_sf_model_select,
                    value = sfModel.ifBlank { Prefs.DEFAULT_SF_MODEL },
                    uiMode = uiMode,
                    index = 1,
                    count = 2,
                    onClick = onChooseSfModel
                )
            }
            AsrBodyText(uiMode = uiMode, textRes = R.string.sf_free_description)
            SiliconFlowPoweredByImage()
        }

        AsrVendor.ElevenLabs -> {
            var itemIndex = primaryIndexOffset
            val itemCount = primaryGroupCount ?: 4
            AsrTextField(
                uiMode = uiMode,
                value = elevenApiKey,
                onValueChange = onElevenApiKeyChange,
                label = stringResource(R.string.label_eleven_api_key),
                password = true,
                index = itemIndex++,
                count = itemCount
            )
            AsrDropdownPreference(
                titleRes = R.string.label_eleven_language,
                options = elevenLanguageOptions(context).map { option ->
                    DropdownOption(option.value, option.label)
                },
                selectedOptionId = elevenLanguageCode,
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onElevenLanguageSelected
            )
            AsrSwitchPreference(
                id = "eleven_streaming",
                titleRes = R.string.label_eleven_streaming,
                checked = elevenStreaming,
                index = itemIndex++,
                count = itemCount,
                onCheckedChange = onElevenStreamingChange
            )
            AsrActionPreference(
                id = "eleven_get_key_guide",
                titleRes = R.string.btn_get_api_key_guide,
                index = itemIndex,
                count = itemCount,
                onClick = { onOpenGuide(ELEVEN_ASR_GUIDE_URL) }
            )
        }

        AsrVendor.StepAudio -> {
            var itemIndex = primaryIndexOffset
            val showCustomEndpoint = stepAudioEndpointPreset == Prefs.STEPAUDIO_ENDPOINT_PRESET_CUSTOM
            val itemCount = primaryGroupCount ?: stepAudioPrimaryItemCount(
                customEndpointVisible = showCustomEndpoint
            )
            AsrDropdownPreference(
                titleRes = R.string.label_stepaudio_endpoint_preset,
                options = listOf(
                    DropdownOption(
                        Prefs.STEPAUDIO_ENDPOINT_PRESET_PAYGO,
                        context.getString(R.string.stepaudio_endpoint_paygo)
                    ),
                    DropdownOption(
                        Prefs.STEPAUDIO_ENDPOINT_PRESET_CODING_PLAN,
                        context.getString(R.string.stepaudio_endpoint_coding_plan)
                    ),
                    DropdownOption(
                        Prefs.STEPAUDIO_ENDPOINT_PRESET_CUSTOM,
                        context.getString(R.string.stepaudio_endpoint_custom)
                    )
                ),
                selectedOptionId = stepAudioEndpointPreset,
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onStepAudioEndpointPresetChange
            )
            if (showCustomEndpoint) {
                AsrTextField(
                    uiMode = uiMode,
                    value = stepAudioEndpoint,
                    onValueChange = onStepAudioEndpointChange,
                    label = stringResource(R.string.label_stepaudio_endpoint),
                    index = itemIndex++,
                    count = itemCount
                )
            }
            AsrTextField(
                uiMode = uiMode,
                value = stepAudioApiKey,
                onValueChange = onStepAudioApiKeyChange,
                label = stringResource(R.string.label_stepaudio_api_key),
                password = true,
                index = itemIndex++,
                count = itemCount
            )
            AsrValuePreference(
                titleRes = R.string.label_stepaudio_model,
                value = stepAudioModel.ifBlank { Prefs.DEFAULT_STEPAUDIO_ASR_MODEL },
                uiMode = uiMode,
                index = itemIndex++,
                count = itemCount,
                onClick = onChooseStepAudioModel
            )
            AsrDropdownPreference(
                titleRes = R.string.label_stepaudio_language,
                options = stepAudioLanguageOptions(context).map { option ->
                    DropdownOption(option.value, option.label)
                },
                selectedOptionId = stepAudioLanguage,
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onStepAudioLanguageSelected
            )
            AsrSwitchPreference(
                id = "stepaudio_use_itn",
                titleRes = R.string.label_stepaudio_use_itn,
                checked = stepAudioUseItn,
                index = itemIndex++,
                count = itemCount,
                onCheckedChange = onStepAudioUseItnChange
            )
            AsrActionPreference(
                id = "stepaudio_get_key_guide",
                titleRes = R.string.btn_get_api_key_guide,
                index = itemIndex,
                count = itemCount,
                onClick = { onOpenGuide(STEPAUDIO_KEY_URL) }
            )
        }

        AsrVendor.Zhipu -> {
            var itemIndex = primaryIndexOffset
            val itemCount = primaryGroupCount ?: 2
            AsrTextField(
                uiMode = uiMode,
                value = zhipuApiKey,
                onValueChange = onZhipuApiKeyChange,
                label = stringResource(R.string.label_zhipu_api_key),
                password = true,
                index = itemIndex++,
                count = itemCount
            )
            AsrSliderPreference(
                titleRes = R.string.label_zhipu_temperature,
                valueLabel = formatAsrFloat(zhipuTemperature),
                value = zhipuTemperature,
                valueRange = 0f..1f,
                steps = 19,
                uiMode = uiMode,
                index = itemIndex,
                count = itemCount,
                onValueChange = onZhipuTemperatureChange,
                onValueChangeFinished = onZhipuTemperatureFinished
            )
            AsrBodyText(uiMode = uiMode, textRes = R.string.zhipu_temperature_hint)
        }

        AsrVendor.Gemini -> {
            var itemIndex = primaryIndexOffset
            val commonFields = geminiCommonTextFields(
                apiKey = geminiApiKey,
                onApiKeyChange = onGeminiApiKeyChange,
                endpoint = geminiEndpoint,
                onEndpointChange = onGeminiEndpointChange,
                model = geminiModel,
                onModelChange = onGeminiModelChange,
                prompt = geminiPrompt,
                onPromptChange = onGeminiPromptChange
            )
            val itemCount = primaryGroupCount ?: commonFields.size + 2
            itemIndex = CommonOnlineAsrTextFields(
                uiMode = uiMode,
                fields = commonFields,
                startIndex = itemIndex,
                count = itemCount
            )
            AsrSwitchPreference(
                id = "gemini_disable_thinking",
                titleRes = R.string.label_gemini_disable_thinking,
                checked = geminiDisableThinking,
                index = itemIndex++,
                count = itemCount,
                onCheckedChange = onGeminiDisableThinkingChange
            )
            AsrActionPreference(
                id = "gemini_get_key_guide",
                titleRes = R.string.btn_get_api_key_guide,
                index = itemIndex,
                count = itemCount,
                onClick = { onOpenGuide(GEMINI_ASR_GUIDE_URL) }
            )
        }

        AsrVendor.OpenRouter -> {
            var itemIndex = primaryIndexOffset
            val commonFields = openRouterCommonTextFields(
                endpoint = openRouterEndpoint,
                onEndpointChange = onOpenRouterEndpointChange,
                apiKey = openRouterApiKey,
                onApiKeyChange = onOpenRouterApiKeyChange,
                model = openRouterModel,
                onModelChange = onOpenRouterModelChange
            )
            val itemCount = primaryGroupCount ?: commonFields.size + 1
            itemIndex = CommonOnlineAsrTextFields(
                uiMode = uiMode,
                fields = commonFields,
                startIndex = itemIndex,
                count = itemCount
            )
            AsrActionPreference(
                id = "openrouter_get_key_guide",
                titleRes = R.string.btn_get_api_key_guide,
                index = itemIndex,
                count = itemCount,
                onClick = { onOpenGuide(OPENROUTER_KEY_URL) }
            )
        }

        AsrVendor.MiMo -> {
            var itemIndex = primaryIndexOffset
            val showCustomEndpoint = mimoEndpointPreset == Prefs.MIMO_ENDPOINT_PRESET_CUSTOM
            val itemCount = primaryGroupCount ?: mimoPrimaryItemCount(
                customEndpointVisible = showCustomEndpoint,
                promptVisible = mimoPromptEnabled
            )
            AsrDropdownPreference(
                titleRes = R.string.label_mimo_asr_endpoint_preset,
                options = listOf(
                    DropdownOption(Prefs.MIMO_ENDPOINT_PRESET_CN, context.getString(R.string.mimo_endpoint_cn)),
                    DropdownOption(Prefs.MIMO_ENDPOINT_PRESET_SGP, context.getString(R.string.mimo_endpoint_sgp)),
                    DropdownOption(Prefs.MIMO_ENDPOINT_PRESET_AMS, context.getString(R.string.mimo_endpoint_ams)),
                    DropdownOption(Prefs.MIMO_ENDPOINT_PRESET_PAYGO, context.getString(R.string.mimo_endpoint_paygo)),
                    DropdownOption(Prefs.MIMO_ENDPOINT_PRESET_CUSTOM, context.getString(R.string.mimo_endpoint_custom))
                ),
                selectedOptionId = mimoEndpointPreset,
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onMimoEndpointPresetChange
            )
            if (mimoEndpointPreset == Prefs.MIMO_ENDPOINT_PRESET_CUSTOM) {
                AsrTextField(
                    uiMode = uiMode,
                    value = mimoEndpoint,
                    onValueChange = onMimoEndpointChange,
                    label = stringResource(R.string.label_mimo_asr_endpoint),
                    index = itemIndex++,
                    count = itemCount
                )
            }
            AsrDropdownPreference(
                titleRes = R.string.label_mimo_asr_model,
                options = listOf(
                    DropdownOption("mimo-v2.5-asr", context.getString(R.string.mimo_model_asr)),
                    DropdownOption("mimo-v2.5", context.getString(R.string.mimo_model_au))
                ),
                selectedOptionId = mimoModel.ifBlank { "mimo-v2.5-asr" },
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onMimoModelChange
            )
            AsrTextField(
                uiMode = uiMode,
                value = mimoApiKey,
                onValueChange = onMimoApiKeyChange,
                label = stringResource(R.string.label_mimo_asr_api_key),
                password = true,
                index = itemIndex++,
                count = itemCount
            )
            AsrDropdownPreference(
                titleRes = R.string.label_mimo_asr_language,
                options = mimoLanguageOptions(context).map { option ->
                    DropdownOption(option.value, option.label)
                },
                selectedOptionId = mimoLanguage,
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onMimoLanguageChange
            )
            if (mimoPromptEnabled) {
                AsrSwitchPreference(
                    id = "mimo_disable_thinking",
                    titleRes = R.string.label_mimo_disable_thinking,
                    checked = mimoDisableThinking,
                    index = itemIndex++,
                    count = itemCount,
                    onCheckedChange = onMimoDisableThinkingChange
                )
                AsrTextField(
                    uiMode = uiMode,
                    value = mimoPrompt,
                    onValueChange = onMimoPromptChange,
                    label = stringResource(R.string.label_mimo_asr_prompt),
                    singleLine = false,
                    minLines = 2,
                    index = itemIndex++,
                    count = itemCount
                )
            }
            AsrActionPreference(
                id = "mimo_get_key_guide",
                titleRes = R.string.btn_get_api_key_guide,
                index = itemIndex,
                count = itemCount,
                onClick = { onOpenGuide(mimoGuideUrl(mimoEndpointPreset)) }
            )
        }

        AsrVendor.OpenAI -> {
            OpenAiAsrConfig(
                uiMode = uiMode,
                profiles = openAiProviders,
                activeProviderId = openAiActiveProviderId,
                onProviderSelected = onOpenAiProviderSelected,
                onProviderAdded = onOpenAiProviderAdded,
                onProviderDeleted = onOpenAiProviderDeleted,
                profileName = openAiProfileName,
                onProfileNameChange = onOpenAiProfileNameChange,
                endpoint = openAiEndpoint,
                onEndpointChange = onOpenAiEndpointChange,
                apiKey = openAiApiKey,
                onApiKeyChange = onOpenAiApiKeyChange,
                model = openAiModel,
                onModelChange = onOpenAiModelChange,
                streaming = openAiStreaming,
                onStreamingChange = onOpenAiStreamingChange,
                useCompletions = openAiUseCompletions,
                onUseCompletionsChange = onOpenAiUseCompletionsChange,
                usePrompt = openAiUsePrompt,
                onUsePromptChange = onOpenAiUsePromptChange,
                prompt = openAiPrompt,
                onPromptChange = onOpenAiPromptChange,
                language = openAiLanguage,
                onLanguageChange = onOpenAiLanguageChange,
                onOpenGuide = { onOpenGuide(OPENAI_ASR_GUIDE_URL) },
                primaryIndexOffset = primaryIndexOffset,
                primaryGroupCount = primaryGroupCount
            )
        }

        AsrVendor.Soniox -> {
            SonioxAsrConfig(
                uiMode = uiMode,
                apiKey = sonioxApiKey,
                onApiKeyChange = onSonioxApiKeyChange,
                streaming = sonioxStreaming,
                onStreamingChange = onSonioxStreamingChange,
                endpointSensitivityLevel = sonioxEndpointSensitivityLevel,
                onEndpointSensitivityLevelChange = onSonioxEndpointSensitivityLevelChange,
                languages = sonioxLanguages,
                onChooseLanguages = onChooseSonioxLanguages,
                languageStrict = sonioxLanguageStrict,
                onLanguageStrictChange = onSonioxLanguageStrictChange,
                onOpenGuide = { onOpenGuide(SONIOX_ASR_GUIDE_URL) },
                primaryIndexOffset = primaryIndexOffset,
                primaryGroupCount = primaryGroupCount
            )
        }

        else -> Unit
    }
}

@Composable
private fun CommonOnlineAsrTextFields(
    uiMode: BibiUiMode,
    fields: List<OnlineAsrTextFieldSpec>,
    startIndex: Int,
    count: Int
): Int {
    val rows = commonOnlineAsrTextRows(
        fields = fields,
        startIndex = startIndex,
        count = count
    )
    rows.forEach { row ->
        AsrTextField(
            uiMode = uiMode,
            value = row.value,
            onValueChange = row.onValueChange,
            label = stringResource(row.labelRes),
            password = row.password,
            singleLine = row.singleLine,
            minLines = row.minLines,
            index = row.index,
            count = row.count
        )
    }
    return startIndex + rows.size
}

internal data class OnlineAsrTextFieldSpec(
    val key: String,
    val role: VendorFieldRole,
    val labelRes: Int,
    val value: String,
    val onValueChange: (String) -> Unit,
    val password: Boolean = false,
    val displayDefault: String = "",
    val singleLine: Boolean = true,
    val minLines: Int = 1
)

internal data class OnlineAsrTextFieldRow(
    val key: String,
    val role: VendorFieldRole,
    val labelRes: Int,
    val value: String,
    val onValueChange: (String) -> Unit,
    val password: Boolean,
    val singleLine: Boolean,
    val minLines: Int,
    val index: Int,
    val count: Int
)

internal fun commonOnlineAsrTextRows(
    fields: List<OnlineAsrTextFieldSpec>,
    startIndex: Int,
    count: Int
): List<OnlineAsrTextFieldRow> = fields.mapIndexed { offset, field ->
    OnlineAsrTextFieldRow(
        key = field.key,
        role = field.role,
        labelRes = field.labelRes,
        value = field.value.ifBlank { field.displayDefault },
        onValueChange = field.onValueChange,
        password = field.password,
        singleLine = field.singleLine,
        minLines = field.minLines,
        index = startIndex + offset,
        count = count
    )
}

internal fun geminiCommonTextFields(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit
): List<OnlineAsrTextFieldSpec> = listOf(
    OnlineAsrTextFieldSpec(
        key = KEY_GEM_API_KEY,
        role = VendorFieldRole.Credential,
        labelRes = R.string.label_gemini_api_key,
        value = apiKey,
        onValueChange = onApiKeyChange,
        password = true
    ),
    OnlineAsrTextFieldSpec(
        key = KEY_GEM_ENDPOINT,
        role = VendorFieldRole.Endpoint,
        labelRes = R.string.label_gemini_endpoint,
        value = endpoint,
        onValueChange = onEndpointChange,
        displayDefault = Prefs.DEFAULT_GEM_ENDPOINT
    ),
    OnlineAsrTextFieldSpec(
        key = KEY_GEM_MODEL,
        role = VendorFieldRole.Model,
        labelRes = R.string.label_gemini_model,
        value = model,
        onValueChange = onModelChange
    ),
    OnlineAsrTextFieldSpec(
        key = KEY_GEM_PROMPT,
        role = VendorFieldRole.Prompt,
        labelRes = R.string.label_gemini_prompt,
        value = prompt,
        onValueChange = onPromptChange,
        singleLine = false,
        minLines = 2
    )
)

internal fun openRouterCommonTextFields(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit
): List<OnlineAsrTextFieldSpec> = listOf(
    OnlineAsrTextFieldSpec(
        key = KEY_OPENROUTER_ASR_ENDPOINT,
        role = VendorFieldRole.Endpoint,
        labelRes = R.string.label_openrouter_asr_endpoint,
        value = endpoint,
        onValueChange = onEndpointChange,
        displayDefault = Prefs.DEFAULT_OPENROUTER_ASR_ENDPOINT
    ),
    OnlineAsrTextFieldSpec(
        key = KEY_OPENROUTER_ASR_API_KEY,
        role = VendorFieldRole.Credential,
        labelRes = R.string.label_openrouter_api_key,
        value = apiKey,
        onValueChange = onApiKeyChange,
        password = true
    ),
    OnlineAsrTextFieldSpec(
        key = KEY_OPENROUTER_ASR_MODEL,
        role = VendorFieldRole.Model,
        labelRes = R.string.label_openrouter_model,
        value = model,
        onValueChange = onModelChange
    )
)

@Composable
private fun OpenAiAsrConfig(
    uiMode: BibiUiMode,
    profiles: List<Prefs.OpenAiAsrProvider>,
    activeProviderId: String,
    onProviderSelected: (String) -> Unit,
    onProviderAdded: () -> Unit,
    onProviderDeleted: () -> Boolean,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    streaming: Boolean,
    onStreamingChange: (Boolean) -> Unit,
    useCompletions: Boolean,
    onUseCompletionsChange: (Boolean) -> Unit,
    usePrompt: Boolean,
    onUsePromptChange: (Boolean) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    onOpenGuide: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    val context = LocalContext.current
    val profileOptions = profiles.map { profile ->
        DropdownOption(profile.id, openAiProfileDisplayName(context, profile))
    }
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: openAiAsrPrimaryItemCount(profiles, usePrompt, useCompletions)
    if (profileOptions.isNotEmpty()) {
        AsrDropdownPreference(
            titleRes = R.string.label_openai_choose_profile,
            options = profileOptions,
            selectedOptionId = activeProviderId,
            index = itemIndex++,
            count = itemCount,
            onSelectedOptionChange = onProviderSelected
        )
    }
    AsrTextField(
        uiMode = uiMode,
        value = profileName,
        onValueChange = onProfileNameChange,
        label = stringResource(R.string.label_openai_profile_name),
        index = itemIndex++,
        count = itemCount
    )
    AsrTextField(
        uiMode = uiMode,
        value = endpoint,
        onValueChange = onEndpointChange,
        label = stringResource(R.string.label_openai_asr_endpoint),
        index = itemIndex++,
        count = itemCount
    )
    if (!useCompletions && isOpenAiCustomTranscriptionsEndpoint(endpoint)) {
        AsrBodyText(
            uiMode = uiMode,
            textRes = R.string.hint_openai_custom_endpoint_wav_upload
        )
    }
    AsrTextField(
        uiMode = uiMode,
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = stringResource(R.string.label_openai_api_key),
        password = true,
        index = itemIndex++,
        count = itemCount
    )
    AsrTextField(
        uiMode = uiMode,
        value = model,
        onValueChange = onModelChange,
        label = stringResource(R.string.label_openai_model),
        index = itemIndex++,
        count = itemCount
    )
    if (!useCompletions) {
        AsrDropdownPreference(
            titleRes = R.string.label_openai_language,
            options = openAiLanguageOptions(context).map { option ->
                DropdownOption(option.value, option.label)
            },
            selectedOptionId = language,
            index = itemIndex++,
            count = itemCount,
            onSelectedOptionChange = onLanguageChange
        )
        AsrSwitchPreference(
            id = "openai_streaming",
            titleRes = R.string.label_openai_streaming,
            checked = streaming,
            index = itemIndex++,
            count = itemCount,
            onCheckedChange = onStreamingChange
        )
    }
    AsrSwitchPreference(
        id = "openai_use_completions",
        titleRes = R.string.label_openai_use_completions,
        checked = useCompletions,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = onUseCompletionsChange
    )
    AsrSwitchPreference(
        id = "openai_use_prompt",
        titleRes = R.string.label_openai_use_prompt,
        checked = usePrompt,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = onUsePromptChange
    )
    if (usePrompt) {
        AsrTextField(
            uiMode = uiMode,
            value = prompt,
            onValueChange = onPromptChange,
            label = stringResource(R.string.label_openai_prompt),
            singleLine = false,
            minLines = 2,
            index = itemIndex++,
            count = itemCount
        )
    }
    AsrActionPreference(
        id = "openai_get_key_guide",
        titleRes = R.string.btn_get_api_key_guide,
        index = itemIndex,
        count = itemCount,
        onClick = onOpenGuide
    )
    SettingsActionButtonRow(uiMode = uiMode) {
        SettingsActionButton(
            uiMode = uiMode,
            text = stringResource(R.string.btn_openai_add_profile),
            onClick = onProviderAdded,
            modifier = Modifier.weight(1f)
        )
        SettingsActionButton(
            uiMode = uiMode,
            text = stringResource(R.string.btn_openai_delete_profile),
            onClick = { onProviderDeleted() },
            enabled = profiles.size > 1,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SonioxAsrConfig(
    uiMode: BibiUiMode,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    streaming: Boolean,
    onStreamingChange: (Boolean) -> Unit,
    endpointSensitivityLevel: Int,
    onEndpointSensitivityLevelChange: (Int) -> Unit,
    languages: List<String>,
    onChooseLanguages: () -> Unit,
    languageStrict: Boolean,
    onLanguageStrictChange: (Boolean) -> Unit,
    onOpenGuide: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    val context = LocalContext.current
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: 6
    val endpointLevelMin = Prefs.SONIOX_ENDPOINT_SENSITIVITY_LEVEL_MIN
    val endpointLevelMax = Prefs.SONIOX_ENDPOINT_SENSITIVITY_LEVEL_MAX
    val endpointLevelRange = endpointLevelMin.toFloat()..endpointLevelMax.toFloat()
    val endpointLevelSteps = endpointLevelMax - endpointLevelMin - 1
    AsrTextField(
        uiMode = uiMode,
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = stringResource(R.string.label_soniox_api_key),
        password = true,
        index = itemIndex++,
        count = itemCount
    )
    AsrSwitchPreference(
        id = "soniox_streaming",
        titleRes = R.string.label_soniox_streaming,
        checked = streaming,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = onStreamingChange
    )
    AsrSliderPreference(
        titleRes = R.string.label_soniox_endpoint_mode,
        valueLabel = sonioxEndpointSensitivityLabel(context, endpointSensitivityLevel),
        value = endpointSensitivityLevel.toFloat(),
        valueRange = endpointLevelRange,
        steps = endpointLevelSteps,
        uiMode = uiMode,
        showKeyPoints = false,
        startLabel = context.getString(R.string.soniox_endpoint_mode_low_latency),
        endLabel = context.getString(R.string.soniox_endpoint_mode_high_accuracy),
        index = itemIndex++,
        count = itemCount,
        onValueChange = { value ->
            onEndpointSensitivityLevelChange(
                value.roundToInt().coerceIn(
                    endpointLevelMin,
                    endpointLevelMax
                )
            )
        },
        onValueChangeFinished = {}
    )
    AsrSwitchPreference(
        id = "soniox_language_strict",
        titleRes = R.string.label_soniox_language_strict,
        checked = languageStrict,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = onLanguageStrictChange
    )
    AsrValuePreference(
        titleRes = R.string.label_soniox_language,
        value = sonioxLanguageSummary(context, languages),
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onClick = onChooseLanguages
    )
    AsrActionPreference(
        id = "soniox_get_key_guide",
        titleRes = R.string.btn_get_api_key_guide,
        index = itemIndex,
        count = itemCount,
        onClick = onOpenGuide
    )
}

internal fun currentOnlineAsrPrimaryItemCount(
    selectedVendor: AsrVendor,
    openAiProviders: List<Prefs.OpenAiAsrProvider>,
    openAiUsePrompt: Boolean,
    openAiUseCompletions: Boolean = false,
    mimoCustomEndpointVisible: Boolean = false,
    mimoPromptVisible: Boolean = false,
    stepAudioCustomEndpointVisible: Boolean = false
): Int = when (selectedVendor) {
    AsrVendor.SiliconFlow -> 1
    AsrVendor.ElevenLabs -> 4
    AsrVendor.StepAudio -> stepAudioPrimaryItemCount(
        customEndpointVisible = stepAudioCustomEndpointVisible
    )
    AsrVendor.Zhipu -> 2
    AsrVendor.Gemini -> 6
    AsrVendor.OpenRouter -> 4
    AsrVendor.MiMo -> mimoPrimaryItemCount(
        customEndpointVisible = mimoCustomEndpointVisible,
        promptVisible = mimoPromptVisible
    )
    AsrVendor.OpenAI -> openAiAsrPrimaryItemCount(
        openAiProviders,
        openAiUsePrompt,
        openAiUseCompletions
    )
    AsrVendor.Soniox -> 6
    else -> 0
}

private fun openAiAsrPrimaryItemCount(
    profiles: List<Prefs.OpenAiAsrProvider>,
    usePrompt: Boolean,
    useCompletions: Boolean
): Int {
    val profilePicker = if (profiles.isNotEmpty()) 1 else 0
    val fixedItems = 7
    val transcriptionsOnlyItems = if (useCompletions) 0 else 2
    val promptItems = if (usePrompt) 1 else 0
    return profilePicker + fixedItems + transcriptionsOnlyItems + promptItems
}

private fun mimoPrimaryItemCount(
    customEndpointVisible: Boolean,
    promptVisible: Boolean
): Int = 5 + (if (customEndpointVisible) 1 else 0) + (if (promptVisible) 2 else 0)

private fun stepAudioPrimaryItemCount(customEndpointVisible: Boolean): Int =
    6 + (if (customEndpointVisible) 1 else 0)

private fun mimoGuideUrl(endpointPreset: String): String = if (
    endpointPreset == Prefs.MIMO_ENDPOINT_PRESET_PAYGO ||
    endpointPreset == Prefs.MIMO_ENDPOINT_PRESET_CUSTOM
) {
    MIMO_PAYGO_GUIDE_URL
} else {
    MIMO_TP_GUIDE_URL
}

internal const val MIMO_TP_GUIDE_URL = "https://platform.xiaomimimo.com/console/plan-manage"
internal const val MIMO_PAYGO_GUIDE_URL = "https://platform.xiaomimimo.com/console/api-keys"

internal fun elevenLanguageOptions(context: Context): List<OnlineVendorChoice> = listOf(
    OnlineVendorChoice("", context.getString(R.string.eleven_lang_auto)),
    OnlineVendorChoice("zh", context.getString(R.string.eleven_lang_zh)),
    OnlineVendorChoice("en", context.getString(R.string.eleven_lang_en)),
    OnlineVendorChoice("ja", context.getString(R.string.eleven_lang_ja)),
    OnlineVendorChoice("ko", context.getString(R.string.eleven_lang_ko)),
    OnlineVendorChoice("de", context.getString(R.string.eleven_lang_de)),
    OnlineVendorChoice("fr", context.getString(R.string.eleven_lang_fr)),
    OnlineVendorChoice("es", context.getString(R.string.eleven_lang_es)),
    OnlineVendorChoice("pt", context.getString(R.string.eleven_lang_pt)),
    OnlineVendorChoice("ru", context.getString(R.string.eleven_lang_ru)),
    OnlineVendorChoice("it", context.getString(R.string.eleven_lang_it))
)

internal fun elevenLanguageLabel(context: Context, code: String): String {
    val normalized = code.trim()
    return elevenLanguageOptions(context).firstOrNull { it.value == normalized }?.label
        ?: context.getString(R.string.eleven_lang_auto)
}

internal fun mimoLanguageOptions(context: Context): List<OnlineVendorChoice> = listOf(
    OnlineVendorChoice("auto", context.getString(R.string.mimo_lang_auto)),
    OnlineVendorChoice("zh", context.getString(R.string.mimo_lang_zh)),
    OnlineVendorChoice("en", context.getString(R.string.mimo_lang_en))
)

internal fun stepAudioLanguageOptions(context: Context): List<OnlineVendorChoice> = listOf(
    OnlineVendorChoice("zh", context.getString(R.string.stepaudio_lang_zh)),
    OnlineVendorChoice("en", context.getString(R.string.stepaudio_lang_en)),
    OnlineVendorChoice("", context.getString(R.string.stepaudio_lang_auto))
)

internal fun stepAudioLanguageLabel(context: Context, language: String): String {
    val normalized = language.trim()
    return stepAudioLanguageOptions(context).firstOrNull { it.value == normalized }?.label
        ?: context.getString(R.string.stepaudio_lang_zh)
}

internal fun displaySfFreeAsrModel(prefs: Prefs): String = prefs.sfFreeAsrModel.ifBlank {
    Prefs.DEFAULT_SF_FREE_ASR_MODEL
}

internal fun displaySfPaidModel(prefs: Prefs): String = prefs.sfModel.ifBlank { Prefs.DEFAULT_SF_MODEL }

internal fun sfPaidAsrModels(): List<String> = listOf(
    Prefs.DEFAULT_SF_OMNI_MODEL,
    "Qwen/Qwen3-Omni-30B-A3B-Thinking",
    "TeleAI/TeleSpeechASR",
    Prefs.DEFAULT_SF_MODEL
)

internal fun isSfOmniModel(model: String): Boolean = model.startsWith("Qwen/Qwen3-Omni-30B-A3B-")

internal fun displayStepAudioModel(prefs: Prefs): String = prefs.stepAudioModel
    .takeIf { it in Prefs.STEPAUDIO_ASR_MODELS }
    ?: Prefs.DEFAULT_STEPAUDIO_ASR_MODEL

private fun formatAsrFloat(value: Float): String = String.format(Locale.US, "%.2f", value)

internal data class OnlineVendorChoice(
    val value: String,
    val label: String
)

private const val OPENAI_ASR_GUIDE_URL =
    "https://bibidocs.brycewg.com/getting-started/asr-providers.html#openai-%E5%85%BC%E5%AE%B9%E6%8E%A5%E5%8F%A3"
private const val SONIOX_ASR_GUIDE_URL =
    "https://bibidocs.brycewg.com/getting-started/asr-providers.html#soniox"
private const val GEMINI_ASR_GUIDE_URL =
    "https://bibidocs.brycewg.com/getting-started/asr-providers.html#gemini"
private const val ELEVEN_ASR_GUIDE_URL =
    "https://bibidocs.brycewg.com/getting-started/asr-providers.html#elevenlabs"
private const val OPENROUTER_KEY_URL = "https://openrouter.ai/settings/keys"
private const val STEPAUDIO_KEY_URL = "https://platform.stepfun.com"

private fun openAiProfileDisplayName(
    context: Context,
    provider: Prefs.OpenAiAsrProvider
): String = provider.name.takeIf { it.isNotBlank() }
    ?: context.getString(R.string.untitled_profile)

internal fun openAiLanguageOptions(context: Context): List<OnlineVendorChoice> = listOf(
    OnlineVendorChoice("", context.getString(R.string.dash_lang_auto)),
    OnlineVendorChoice("zh", context.getString(R.string.dash_lang_zh)),
    OnlineVendorChoice("en", context.getString(R.string.dash_lang_en)),
    OnlineVendorChoice("ja", context.getString(R.string.dash_lang_ja)),
    OnlineVendorChoice("de", context.getString(R.string.dash_lang_de)),
    OnlineVendorChoice("ko", context.getString(R.string.dash_lang_ko)),
    OnlineVendorChoice("ru", context.getString(R.string.dash_lang_ru)),
    OnlineVendorChoice("fr", context.getString(R.string.dash_lang_fr)),
    OnlineVendorChoice("pt", context.getString(R.string.dash_lang_pt)),
    OnlineVendorChoice("ar", context.getString(R.string.dash_lang_ar)),
    OnlineVendorChoice("it", context.getString(R.string.dash_lang_it)),
    OnlineVendorChoice("es", context.getString(R.string.dash_lang_es))
)

internal fun sonioxLanguageOptions(context: Context): List<OnlineVendorChoice> = listOf(
    OnlineVendorChoice("", context.getString(R.string.soniox_lang_auto)),
    OnlineVendorChoice("en", context.getString(R.string.soniox_lang_en)),
    OnlineVendorChoice("zh", context.getString(R.string.soniox_lang_zh)),
    OnlineVendorChoice("ja", context.getString(R.string.soniox_lang_ja)),
    OnlineVendorChoice("ko", context.getString(R.string.soniox_lang_ko)),
    OnlineVendorChoice("es", context.getString(R.string.soniox_lang_es)),
    OnlineVendorChoice("pt", context.getString(R.string.soniox_lang_pt)),
    OnlineVendorChoice("de", context.getString(R.string.soniox_lang_de)),
    OnlineVendorChoice("fr", context.getString(R.string.soniox_lang_fr)),
    OnlineVendorChoice("id", context.getString(R.string.soniox_lang_id)),
    OnlineVendorChoice("ru", context.getString(R.string.soniox_lang_ru)),
    OnlineVendorChoice("ar", context.getString(R.string.soniox_lang_ar)),
    OnlineVendorChoice("hi", context.getString(R.string.soniox_lang_hi)),
    OnlineVendorChoice("vi", context.getString(R.string.soniox_lang_vi)),
    OnlineVendorChoice("th", context.getString(R.string.soniox_lang_th)),
    OnlineVendorChoice("ms", context.getString(R.string.soniox_lang_ms)),
    OnlineVendorChoice("fil", context.getString(R.string.soniox_lang_fil))
)

private fun sonioxEndpointSensitivityLabel(context: Context, level: Int): String {
    val sensitivity = sonioxEndpointSensitivityForLevel(level)
    return if (sensitivity == 0f) {
        "${context.getString(R.string.soniox_endpoint_mode_default)} (0.0)"
    } else {
        String.format(Locale.US, "%+.1f", sensitivity)
    }
}

private fun sonioxEndpointSensitivityForLevel(level: Int): Float {
    val normalized = level.coerceIn(
        Prefs.SONIOX_ENDPOINT_SENSITIVITY_LEVEL_MIN,
        Prefs.SONIOX_ENDPOINT_SENSITIVITY_LEVEL_MAX
    )
    return (Prefs.DEFAULT_SONIOX_ENDPOINT_SENSITIVITY_LEVEL - normalized) /
        Prefs.DEFAULT_SONIOX_ENDPOINT_SENSITIVITY_LEVEL.toFloat()
}

internal fun sonioxLanguageSummary(context: Context, languages: List<String>): String {
    if (languages.isEmpty()) return context.getString(R.string.soniox_lang_auto)
    val labelsByCode = sonioxLanguageOptions(context).associate { it.value to it.label }
    val labels = languages.mapNotNull { labelsByCode[it] }
    return labels.takeIf { it.isNotEmpty() }?.joinToString(separator = "、")
        ?: context.getString(R.string.soniox_lang_auto)
}
