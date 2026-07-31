package com.brycewg.asrkb.store

import org.junit.Assert.assertEquals
import org.junit.Test

class DashScopePrefsCompatTest {
    @Test
    fun normalizeDashAsrModelMigratesVersionedQwenRealtimeId() {
        assertEquals(
            Prefs.DASH_MODEL_QWEN3_REALTIME,
            DashScopePrefsCompat.normalizeDashAsrModel("qwen3-asr-flash-realtime-2026-02-10")
        )
    }

    @Test
    fun normalizeDashAsrModelKeepsFunAsrFlashVersionedId() {
        assertEquals(
            "fun-asr-flash-2026-06-15",
            DashScopePrefsCompat.normalizeDashAsrModel(Prefs.DASH_MODEL_FUN_ASR_FLASH)
        )
    }

    @Test
    fun multimodalGenerationEndpointUsesDashScopeRegionBaseUrl() {
        assertEquals(
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            DashScopePrefsCompat.getDashMultimodalGenerationEndpoint("cn")
        )
        assertEquals(
            "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            DashScopePrefsCompat.getDashMultimodalGenerationEndpoint("intl")
        )
    }

    @Test
    fun qwenAudioModelsReuseGenerationAndRecognitionProtocols() {
        assertEquals(true, DashScopePrefsCompat.isGenerationAsrModel(Prefs.DASH_MODEL_QWEN_AUDIO_FLASH))
        assertEquals(
            true,
            DashScopePrefsCompat.isRecognitionStreamingModel(Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME)
        )
        assertEquals(true, DashScopePrefsCompat.isStreamingModel(Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME))
        assertEquals(false, DashScopePrefsCompat.isPromptSupported(Prefs.DASH_MODEL_QWEN_AUDIO_FLASH))
        assertEquals(true, DashScopePrefsCompat.isLanguageSupported(Prefs.DASH_MODEL_QWEN_AUDIO_FLASH))
    }

    @Test
    fun dashLanguagesKeepAtMostFourDistinctHints() {
        assertEquals(
            listOf("zh", "en", "ja", "de"),
            DashScopePrefsCompat.parseDashLanguages(" zh, en,zh, ja, de, fr ")
        )
        assertEquals(
            "zh,en,ja,de",
            DashScopePrefsCompat.serializeDashLanguages(listOf(" zh ", "en,ja", "zh", "de", "fr"))
        )
    }
}
