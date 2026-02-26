package com.brycewg.asrkb.store

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * LLM providers（自定义 OpenAI 兼容配置列表）的序列化与迁移（从 [Prefs] 中拆出）。
 */
internal object PrefsLlmProviderStore {
    private const val TAG = "Prefs"

    fun getLlmProviders(prefs: Prefs, json: Json): List<Prefs.LlmProvider> {
        // 首次使用：若未初始化，迁移旧字段为一个默认配置
        if (prefs.llmProvidersJson.isBlank()) {
            val migrated = Prefs.LlmProvider(
                id = "default",
                name = "默认",
                endpoint = prefs.llmEndpoint.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT },
                apiKey = prefs.llmApiKey,
                model = prefs.llmModel.ifBlank { Prefs.DEFAULT_LLM_MODEL },
                temperature = prefs.llmTemperature,
            )
            setLlmProviders(prefs, json, listOf(migrated))
        }
        return try {
            json.decodeFromString<List<Prefs.LlmProvider>>(prefs.llmProvidersJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LlmProviders JSON", e)
            emptyList()
        }
    }

    fun setLlmProviders(prefs: Prefs, json: Json, list: List<Prefs.LlmProvider>) {
        try {
            prefs.llmProvidersJson = json.encodeToString(list)
            if (list.none { it.id == prefs.activeLlmId }) {
                prefs.activeLlmId = list.firstOrNull()?.id ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize LlmProviders", e)
        }
    }

    fun getActiveLlmProvider(prefs: Prefs, json: Json): Prefs.LlmProvider? {
        val id = prefs.activeLlmId
        val list = getLlmProviders(prefs, json)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }
}
