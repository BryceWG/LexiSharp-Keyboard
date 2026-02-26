package com.brycewg.asrkb.store

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * SpeechPreset 的序列化与查询逻辑（从 [Prefs] 中拆出）。
 */
internal object SpeechPresetStore {
    private const val TAG = "Prefs"

    fun getSpeechPresets(prefs: Prefs, json: Json): List<SpeechPreset> {
        if (prefs.speechPresetsJson.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<SpeechPreset>>(prefs.speechPresetsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SpeechPresets JSON", e)
            emptyList()
        }
    }

    fun setSpeechPresets(prefs: Prefs, json: Json, list: List<SpeechPreset>) {
        val sanitized = list.mapNotNull { p ->
            val name = p.name.trim()
            if (name.isEmpty()) {
                null
            } else {
                val id = p.id.ifBlank { java.util.UUID.randomUUID().toString() }
                SpeechPreset(id, name, p.content)
            }
        }
        try {
            prefs.speechPresetsJson = json.encodeToString(sanitized)
            if (sanitized.none { it.id == prefs.activeSpeechPresetId }) {
                prefs.activeSpeechPresetId = sanitized.firstOrNull()?.id ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize SpeechPresets", e)
        }
    }

    fun findSpeechPresetReplacement(prefs: Prefs, json: Json, original: String): String? {
        val normalized = original.trim()
        if (normalized.isEmpty()) return null
        val presets = getSpeechPresets(prefs, json)
        val strict = presets.firstOrNull { it.name.trim() == normalized }
        val match = strict ?: presets.firstOrNull { it.name.trim().equals(normalized, ignoreCase = true) }
        return match?.content
    }
}
