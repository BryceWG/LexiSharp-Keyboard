package com.brycewg.asrkb.store

import android.content.SharedPreferences

/**
 * DashScope 偏好项的兼容/推导逻辑（从 [Prefs] / [PrefsBackup] 中拆出）。
 */
internal object DashScopePrefsCompat {
    private const val DASH_LEGACY_QWEN3_REALTIME_MODEL = "qwen3-asr-flash-realtime-2026-02-10"

    fun getDashHttpBaseUrl(dashRegion: String): String = if (dashRegion.equals("intl", ignoreCase = true)) {
        "https://dashscope-intl.aliyuncs.com/api/v1"
    } else {
        "https://dashscope.aliyuncs.com/api/v1"
    }

    fun getDashCompatibleModeChatEndpoint(dashRegion: String): String = if (
        dashRegion.equals("intl", ignoreCase = true)
    ) {
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
    } else {
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    }

    fun getDashMultimodalGenerationEndpoint(dashRegion: String): String =
        getDashHttpBaseUrl(dashRegion).trimEnd('/') + "/services/aigc/multimodal-generation/generation"

    fun normalizeDashAsrModel(model: String): String {
        val trimmed = model.trim()
        return when {
            trimmed.isBlank() -> Prefs.DEFAULT_DASH_MODEL
            trimmed.equals(DASH_LEGACY_QWEN3_REALTIME_MODEL, ignoreCase = true) ->
                Prefs.DASH_MODEL_QWEN3_REALTIME
            else -> trimmed
        }
    }

    fun deriveDashAsrModelFromLegacyFlags(sp: SharedPreferences): String {
        val streaming = sp.getBoolean(KEY_DASH_STREAMING_ENABLED, false)
        if (!streaming) return Prefs.DEFAULT_DASH_MODEL
        val funAsr = sp.getBoolean(KEY_DASH_FUNASR_ENABLED, false)
        return if (funAsr) Prefs.DASH_MODEL_FUN_ASR_REALTIME else Prefs.DASH_MODEL_QWEN3_REALTIME
    }
}
