/**
 * DashScope Qwen-Audio 请求参数回归测试。
 */
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.Prefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DashscopeRequestBuildersTest {
    @Test
    fun generationRequestSendsAtMostFourQwenAudioLanguageHints() {
        val body = buildDashGenerationAsrRequestBody(
            model = Prefs.DASH_MODEL_QWEN_AUDIO_FLASH,
            base64Audio = "AA==",
            audio = wavAudio(),
            sampleRate = 16_000,
            languages = listOf("zh", "en", "ja", "de", "fr")
        )

        val hints = JSONObject(body).getJSONObject("parameters").getJSONArray("language_hints")
        assertEquals(listOf("zh", "en", "ja", "de"), List(hints.length(), hints::getString))
    }

    @Test
    fun generationRequestDoesNotSendHintsToFunAsrFlash() {
        val body = buildDashGenerationAsrRequestBody(
            model = Prefs.DASH_MODEL_FUN_ASR_FLASH,
            base64Audio = "AA==",
            audio = wavAudio(),
            sampleRate = 16_000,
            languages = listOf("zh", "en")
        )

        assertFalse(JSONObject(body).getJSONObject("parameters").has("language_hints"))
    }

    @Test
    fun recognitionRequestSendsAtMostFourQwenAudioLanguageHints() {
        val param = buildDashRecognitionParam(
            model = Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME,
            apiKey = "test-key",
            sampleRate = 16_000,
            languages = listOf("zh", "en", "ja", "de", "fr"),
            semanticPunctuationEnabled = true
        )

        val hints = param.parameters["language_hints"] as Array<*>
        assertEquals(listOf("zh", "en", "ja", "de"), hints.toList())
        assertEquals(true, param.parameters["semantic_punctuation_enabled"])
    }

    private fun wavAudio() = UploadAudioData(
        bytes = byteArrayOf(0),
        container = UploadAudioContainer.WAV,
        sampleRate = 16_000,
        channels = 1,
        sourceBytes = 1,
        durationMs = 1,
        encodeElapsedMs = 0,
        feedElapsedMs = 0,
        finishElapsedMs = 0
    )
}
