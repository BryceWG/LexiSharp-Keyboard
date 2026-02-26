package com.brycewg.asrkb.store

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Soniox language hints 的兼容与序列化（从 [Prefs] 中拆出）。
 */
internal object SonioxLanguagesStore {
    private const val TAG = "Prefs"

    fun getSonioxLanguages(prefs: Prefs, json: Json): List<String> {
        val raw = prefs.sonioxLanguagesJson.trim()
        if (raw.isBlank()) {
            val single = prefs.sonioxLanguage.trim()
            return if (single.isNotEmpty()) listOf(single) else emptyList()
        }
        return try {
            json.decodeFromString<List<String>>(raw).filter { it.isNotBlank() }.distinct()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Soniox languages JSON, falling back to single value", e)
            // 回退到旧的单一字段
            val single = prefs.sonioxLanguage.trim()
            if (single.isNotEmpty()) listOf(single) else emptyList()
        }
    }

    fun setSonioxLanguages(prefs: Prefs, json: Json, list: List<String>) {
        val distinct = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        try {
            prefs.sonioxLanguagesJson = json.encodeToString(distinct)
            // 兼容旧字段：保留第一个；为空则清空
            prefs.sonioxLanguage = distinct.firstOrNull() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize Soniox languages", e)
        }
    }
}
