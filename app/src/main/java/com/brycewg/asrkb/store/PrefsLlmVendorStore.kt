package com.brycewg.asrkb.store

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.asr.ReasoningMode
import kotlinx.serialization.json.Json

/**
 * LLM 供应商配置读写与 Effective 配置推导（从 [Prefs] 中拆出）。
 *
 * 存储约定：
 * - 内置供应商以 `llm_vendor_{id}_*` 形式保存独立配置
 */
internal object PrefsLlmVendorStore {
    private const val TAG = "Prefs"

    fun getLlmVendorApiKey(sp: SharedPreferences, vendor: LlmVendor): String {
        val key = "llm_vendor_${vendor.id}_api_key"
        return sp.getString(key, "") ?: ""
    }

    fun setLlmVendorApiKey(sp: SharedPreferences, vendor: LlmVendor, apiKey: String) {
        val key = "llm_vendor_${vendor.id}_api_key"
        sp.edit { putString(key, apiKey.trim()) }
    }

    fun getLlmVendorModel(sp: SharedPreferences, vendor: LlmVendor): String {
        val key = "llm_vendor_${vendor.id}_model"
        val stored = sp.getString(key, null)
        // 如果未设置，返回供应商默认模型
        return stored ?: vendor.defaultModel
    }

    fun setLlmVendorModel(sp: SharedPreferences, vendor: LlmVendor, model: String) {
        val key = "llm_vendor_${vendor.id}_model"
        sp.edit { putString(key, model.trim()) }
    }

    fun getLlmVendorTemperature(sp: SharedPreferences, vendor: LlmVendor): Float {
        val key = "llm_vendor_${vendor.id}_temperature"
        return sp.getFloat(key, Prefs.DEFAULT_LLM_TEMPERATURE)
    }

    fun setLlmVendorTemperature(sp: SharedPreferences, vendor: LlmVendor, temperature: Float) {
        val key = "llm_vendor_${vendor.id}_temperature"
        sp.edit { putFloat(key, temperature.coerceIn(vendor.temperatureMin, vendor.temperatureMax)) }
    }

    fun getLlmVendorReasoningEnabled(sp: SharedPreferences, vendor: LlmVendor): Boolean {
        val key = "llm_vendor_${vendor.id}_reasoning_enabled"
        return sp.getBoolean(key, false)
    }

    fun setLlmVendorReasoningEnabled(sp: SharedPreferences, vendor: LlmVendor, enabled: Boolean) {
        val key = "llm_vendor_${vendor.id}_reasoning_enabled"
        sp.edit { putBoolean(key, enabled) }
    }

    fun getLlmVendorModels(
        sp: SharedPreferences,
        json: Json,
        vendor: LlmVendor,
        sfFreeLlmUsePaidKey: Boolean,
    ): List<String> {
        val key = "llm_vendor_${vendor.id}_models_json"
        if (vendor == LlmVendor.SF_FREE && !sfFreeLlmUsePaidKey) {
            return Prefs.SF_FREE_LLM_MODELS
        }
        val fallback = vendor.models
        val raw = sp.getString(key, "") ?: ""
        if (raw.isBlank()) return fallback
        return try {
            val parsed = json.decodeFromString<List<String>>(raw)
            val cleaned = parsed.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (cleaned.isEmpty()) fallback else cleaned
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM vendor models JSON", e)
            fallback
        }
    }

    fun setLlmVendorModels(sp: SharedPreferences, json: Json, vendor: LlmVendor, models: List<String>) {
        val key = "llm_vendor_${vendor.id}_models_json"
        val cleaned = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        try {
            val raw = json.encodeToString(cleaned)
            sp.edit { putString(key, raw) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize LLM vendor models", e)
        }
    }

    fun setLlmVendorModelsJson(sp: SharedPreferences, vendor: LlmVendor, raw: String) {
        val key = "llm_vendor_${vendor.id}_models_json"
        sp.edit { putString(key, raw.trim()) }
    }

    fun getLlmVendorReasoningParamsOnJson(sp: SharedPreferences, vendor: LlmVendor): String {
        val key = "llm_vendor_${vendor.id}_reasoning_on_json"
        val stored = sp.getString(key, "") ?: ""
        return stored.ifBlank { defaultReasoningParamsOnJson(vendor) }
    }

    fun setLlmVendorReasoningParamsOnJson(sp: SharedPreferences, vendor: LlmVendor, json: String) {
        val key = "llm_vendor_${vendor.id}_reasoning_on_json"
        sp.edit { putString(key, json.trim()) }
    }

    fun getLlmVendorReasoningParamsOffJson(sp: SharedPreferences, vendor: LlmVendor): String {
        val key = "llm_vendor_${vendor.id}_reasoning_off_json"
        val stored = sp.getString(key, "") ?: ""
        return stored.ifBlank { defaultReasoningParamsOffJson(vendor) }
    }

    fun setLlmVendorReasoningParamsOffJson(sp: SharedPreferences, vendor: LlmVendor, json: String) {
        val key = "llm_vendor_${vendor.id}_reasoning_off_json"
        sp.edit { putString(key, json.trim()) }
    }

    fun getEffectiveLlmConfig(prefs: Prefs, sp: SharedPreferences): Prefs.EffectiveLlmConfig? {
        return when (val vendor = prefs.llmVendor) {
            LlmVendor.SF_FREE -> {
                val model = if (prefs.sfFreeLlmUsePaidKey) {
                    getLlmVendorModel(sp, LlmVendor.SF_FREE).ifBlank { prefs.sfFreeLlmModel }
                } else {
                    prefs.sfFreeLlmModel
                }
                if (prefs.sfFreeLlmUsePaidKey) {
                    // 使用用户自己的付费 API Key
                    val apiKey = getLlmVendorApiKey(sp, LlmVendor.SF_FREE)
                    if (apiKey.isBlank()) {
                        null // 需要 API Key 但未配置
                    } else {
                        Prefs.EffectiveLlmConfig(
                            endpoint = vendor.endpoint,
                            apiKey = apiKey,
                            model = model,
                            temperature = getLlmVendorTemperature(sp, LlmVendor.SF_FREE),
                            vendor = vendor,
                            enableReasoning = getLlmVendorReasoningEnabled(sp, vendor),
                            useCustomReasoningParams = !isBuiltinLlmPresetModel(vendor, model, prefs.sfFreeLlmUsePaidKey),
                            reasoningParamsOnJson = getLlmVendorReasoningParamsOnJson(sp, vendor),
                            reasoningParamsOffJson = getLlmVendorReasoningParamsOffJson(sp, vendor),
                        )
                    }
                } else {
                    // SiliconFlow 免费服务：使用内置端点和模型，无需 API Key
                    // 实际 API Key 在 LlmPostProcessor 中注入
                    Prefs.EffectiveLlmConfig(
                        endpoint = vendor.endpoint,
                        // 免费服务在调用层注入内置 Key
                        apiKey = "",
                        model = model,
                        temperature = Prefs.DEFAULT_LLM_TEMPERATURE,
                        vendor = vendor,
                        enableReasoning = getLlmVendorReasoningEnabled(sp, vendor),
                        useCustomReasoningParams = !isBuiltinLlmPresetModel(vendor, model, prefs.sfFreeLlmUsePaidKey),
                        reasoningParamsOnJson = getLlmVendorReasoningParamsOnJson(sp, vendor),
                        reasoningParamsOffJson = getLlmVendorReasoningParamsOffJson(sp, vendor),
                    )
                }
            }
            LlmVendor.CUSTOM -> {
                // 自定义供应商：使用用户配置的 LlmProvider
                val provider = prefs.getActiveLlmProvider()
                if (provider != null && provider.endpoint.isNotBlank()) {
                    Prefs.EffectiveLlmConfig(
                        endpoint = provider.endpoint,
                        apiKey = provider.apiKey,
                        model = provider.model,
                        temperature = provider.temperature,
                        vendor = vendor,
                        enableReasoning = provider.enableReasoning,
                        useCustomReasoningParams = true,
                        reasoningParamsOnJson = provider.reasoningParamsOnJson,
                        reasoningParamsOffJson = provider.reasoningParamsOffJson,
                    )
                } else {
                    null
                }
            }
            else -> {
                // 内置供应商：使用预设端点 + 用户 API Key + 用户选择的模型
                val apiKey = getLlmVendorApiKey(sp, vendor)
                val model = getLlmVendorModel(sp, vendor).ifBlank { vendor.defaultModel }
                if (vendor.requiresApiKey && apiKey.isBlank()) {
                    null // 需要 API Key 但未配置
                } else {
                    Prefs.EffectiveLlmConfig(
                        endpoint = vendor.endpoint,
                        apiKey = apiKey,
                        model = model,
                        temperature = getLlmVendorTemperature(sp, vendor),
                        vendor = vendor,
                        enableReasoning = getLlmVendorReasoningEnabled(sp, vendor),
                        useCustomReasoningParams = !isBuiltinLlmPresetModel(vendor, model, prefs.sfFreeLlmUsePaidKey),
                        reasoningParamsOnJson = getLlmVendorReasoningParamsOnJson(sp, vendor),
                        reasoningParamsOffJson = getLlmVendorReasoningParamsOffJson(sp, vendor),
                    )
                }
            }
        }
    }

    private fun defaultReasoningParamsOnJson(vendor: LlmVendor): String {
        return when (vendor.reasoningMode) {
            ReasoningMode.ENABLE_THINKING -> """{"enable_thinking":true}"""
            ReasoningMode.THINKING_TYPE -> """{"thinking":{"type":"enabled"}}"""
            ReasoningMode.REASONING_EFFORT -> """{"reasoning_effort":"medium"}"""
            ReasoningMode.MODEL_SELECTION,
            ReasoningMode.NONE,
            -> Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_ON_JSON
        }
    }

    private fun defaultReasoningParamsOffJson(vendor: LlmVendor): String {
        return when (vendor) {
            LlmVendor.CEREBRAS -> """{"reasoning_effort":"low"}"""
            else -> when (vendor.reasoningMode) {
                ReasoningMode.ENABLE_THINKING -> """{"enable_thinking":false}"""
                ReasoningMode.THINKING_TYPE -> """{"thinking":{"type":"disabled"}}"""
                ReasoningMode.REASONING_EFFORT -> """{"reasoning_effort":"none"}"""
                ReasoningMode.MODEL_SELECTION,
                ReasoningMode.NONE,
                -> Prefs.DEFAULT_CUSTOM_REASONING_PARAMS_OFF_JSON
            }
        }
    }

    private fun isBuiltinLlmPresetModel(vendor: LlmVendor, model: String, sfFreeLlmUsePaidKey: Boolean): Boolean {
        if (model.isBlank()) return false
        if (vendor == LlmVendor.SF_FREE && !sfFreeLlmUsePaidKey) {
            return Prefs.SF_FREE_LLM_MODELS.contains(model)
        }
        return vendor.models.contains(model)
    }
}
