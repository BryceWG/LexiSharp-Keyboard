/**
 * LLM 供应商请求模式能力的持久化。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object PrefsLlmRequestModeStore {
    private const val TAG = "Prefs"

    fun get(
        prefs: Prefs,
        json: Json,
        capabilityKey: String
    ): Prefs.LlmRequestMode? {
        if (capabilityKey.isBlank() || prefs.llmRequestModesJson.isBlank()) return null
        return try {
            val stored = json.decodeFromString<Map<String, String>>(prefs.llmRequestModesJson)
            stored[capabilityKey]?.let(Prefs.LlmRequestMode::fromId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse LLM request modes", t)
            null
        }
    }

    @Synchronized
    fun set(
        prefs: Prefs,
        json: Json,
        capabilityKey: String,
        mode: Prefs.LlmRequestMode
    ) {
        if (capabilityKey.isBlank()) return
        try {
            val stored = if (prefs.llmRequestModesJson.isBlank()) {
                emptyMap()
            } else {
                json.decodeFromString<Map<String, String>>(prefs.llmRequestModesJson)
            }
            prefs.llmRequestModesJson = json.encodeToString(stored + (capabilityKey to mode.id))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist LLM request mode", t)
        }
    }
}
