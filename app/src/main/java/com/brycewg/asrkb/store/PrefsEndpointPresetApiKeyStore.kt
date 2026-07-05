/**
 * 按端点预设分槽读写 ASR API Key（MiMo / StepAudio 等）。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.util.Log
import org.json.JSONObject

internal object PrefsEndpointPresetApiKeyStore {
    private const val TAG = "Prefs"

    data class ApiKeyReadResult(
        val apiKey: String,
        val migratedKeysJson: String? = null
    )

    fun getApiKey(
        keysJson: String,
        legacyApiKey: String,
        preset: String
    ): ApiKeyReadResult {
        val map = parseKeysJson(keysJson)
        if (map.isEmpty() && legacyApiKey.isNotBlank()) {
            val migrated = map.toMutableMap()
            migrated[preset] = legacyApiKey
            return ApiKeyReadResult(
                apiKey = legacyApiKey,
                migratedKeysJson = encodeKeysJson(migrated)
            )
        }
        return ApiKeyReadResult(apiKey = map[preset].orEmpty())
    }

    fun setApiKey(keysJson: String, preset: String, apiKey: String): String {
        val map = parseKeysJson(keysJson).toMutableMap()
        map[preset] = apiKey
        return encodeKeysJson(map)
    }

    private fun parseKeysJson(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(trimmed)
            buildMap {
                obj.keys().forEach { key ->
                    val value = obj.optString(key, "").trim()
                    if (value.isNotEmpty()) {
                        put(key, value)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse endpoint preset API keys JSON", t)
            emptyMap()
        }
    }

    private fun encodeKeysJson(map: Map<String, String>): String {
        if (map.isEmpty()) return ""
        val obj = JSONObject()
        map.forEach { (preset, apiKey) ->
            val normalized = apiKey.trim()
            if (normalized.isNotEmpty()) {
                obj.put(preset, normalized)
            }
        }
        return if (obj.length() == 0) "" else obj.toString()
    }
}
