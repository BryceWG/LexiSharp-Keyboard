// OpenAI ASR endpoint 与上传压缩策略的 JVM 回归测试。
package com.brycewg.asrkb.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiAsrPolicyTest {
    @Test
    fun officialEndpointAllowsCompressionWhenGlobalSwitchEnabled() {
        assertTrue(
            shouldCompressAudioBeforeOpenAiUpload(
                globalEnabled = true,
                endpoint = Prefs.DEFAULT_OA_ASR_ENDPOINT
            )
        )
    }

    @Test
    fun blankEndpointFallsBackToOfficialEndpoint() {
        assertTrue(isOpenAiOfficialTranscriptionsEndpoint(""))
        assertTrue(isOpenAiOfficialTranscriptionsEndpoint("   "))
        assertTrue(
            shouldCompressAudioBeforeOpenAiUpload(
                globalEnabled = true,
                endpoint = " "
            )
        )
    }

    @Test
    fun officialEndpointVariantsRemainOfficial() {
        assertTrue(
            isOpenAiOfficialTranscriptionsEndpoint(
                "HTTPS://API.OPENAI.COM/v1/audio/transcriptions/"
            )
        )
    }

    @Test
    fun customEndpointDisablesCompressionEvenWhenGlobalSwitchEnabled() {
        assertTrue(
            isOpenAiCustomTranscriptionsEndpoint(
                "http://dbasr.example:8864/v1/audio/transcriptions"
            )
        )
        assertFalse(
            shouldCompressAudioBeforeOpenAiUpload(
                globalEnabled = true,
                endpoint = "http://dbasr.example:8864/v1/audio/transcriptions"
            )
        )
    }

    @Test
    fun globalSwitchDisabledAlwaysDisablesCompression() {
        assertFalse(
            shouldCompressAudioBeforeOpenAiUpload(
                globalEnabled = false,
                endpoint = Prefs.DEFAULT_OA_ASR_ENDPOINT
            )
        )
        assertFalse(
            shouldCompressAudioBeforeOpenAiUpload(
                globalEnabled = false,
                endpoint = "http://dbasr.example:8864/v1/audio/transcriptions"
            )
        )
    }
}
