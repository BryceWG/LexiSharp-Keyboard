package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CohereFileAsrEngineTest {
    @Test
    fun preservesTranscriptionTextExactly() {
        assertEquals(
            " hello world \r\n",
            parseCohereTranscription("""{"text":" hello world \r\n"}""")
        )
    }

    @Test
    fun malformedOrMissingTextReturnsEmptyResult() {
        assertEquals("", parseCohereTranscription("{}"))
        assertEquals("", parseCohereTranscription("not-json"))
    }

    @Test
    fun extractsNestedAndTopLevelErrors() {
        assertEquals(
            "invalid model",
            extractCohereError("""{"error":{"message":"invalid model"}}""")
        )
        assertEquals("rate limited", extractCohereError("""{"message":"rate limited"}"""))
    }

    @Test
    fun endpointMatchesCohereV2TranscriptionsApi() {
        assertEquals(
            "https://api.cohere.com/v2/audio/transcriptions",
            CohereFileAsrEngine.ENDPOINT
        )
    }

    @Test
    fun builtInModelsMatchSupportedCohereReleases() {
        assertEquals(
            listOf("cohere-transcribe-03-2026", "cohere-transcribe-arabic-07-2026"),
            com.brycewg.asrkb.store.Prefs.COHERE_ASR_MODELS
        )
    }

    @Test
    @Config(sdk = [29])
    fun usesOggOpusCompressionWhenPlatformSupportsIt() {
        assertEquals(
            UploadAudioEncodingSpec.OGG_OPUS,
            cohereUploadAudioEncodingSpecIfSupported()
        )
    }

    @Test
    @Config(sdk = [28])
    fun fallsBackToWavWhenOggOpusEncodingIsUnavailable() {
        assertEquals(null, cohereUploadAudioEncodingSpecIfSupported())
    }
}
