package com.brycewg.asrkb.store

import android.content.SharedPreferences

/**
 * DashScope 偏好项的兼容/推导逻辑（从 [Prefs] / [PrefsBackup] 中拆出）。
 */
internal object DashScopePrefsCompat {
    fun getDashHttpBaseUrl(dashRegion: String): String {
        return if (dashRegion.equals("intl", ignoreCase = true)) {
            "https://dashscope-intl.aliyuncs.com/api/v1"
        } else {
            "https://dashscope.aliyuncs.com/api/v1"
        }
    }

    fun deriveDashAsrModelFromLegacyFlags(sp: SharedPreferences): String {
        val streaming = sp.getBoolean(KEY_DASH_STREAMING_ENABLED, false)
        if (!streaming) return Prefs.DEFAULT_DASH_MODEL
        val funAsr = sp.getBoolean(KEY_DASH_FUNASR_ENABLED, false)
        return if (funAsr) Prefs.DASH_MODEL_FUN_ASR_REALTIME else Prefs.DASH_MODEL_QWEN3_REALTIME
    }
}
