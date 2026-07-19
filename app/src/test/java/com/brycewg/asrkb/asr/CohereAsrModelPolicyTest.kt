package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CohereAsrModelPolicyTest {
    @Test
    fun generalModelSupportsAllFourteenDocumentedLanguages() {
        assertEquals(
            listOf("zh", "en", "ar", "ja", "ko", "de", "fr", "it", "es", "pt", "el", "nl", "pl", "vi"),
            cohereSupportedLanguageCodes(Prefs.DEFAULT_COHERE_ASR_MODEL)
        )
    }

    @Test
    fun arabicModelOnlySupportsArabicAndEnglish() {
        assertEquals(
            listOf("ar", "en"),
            cohereSupportedLanguageCodes(Prefs.COHERE_ARABIC_ASR_MODEL)
        )
    }

    @Test
    fun customModelKeepsUnknownLanguageCapability() {
        assertNull(cohereSupportedLanguageCodes("private-model"))
        assertEquals("ja", normalizeCohereLanguageForModel("private-model", "ja"))
    }

    @Test
    fun invalidArabicModelLanguageFallsBackToArabic() {
        assertEquals(
            "ar",
            normalizeCohereLanguageForModel(Prefs.COHERE_ARABIC_ASR_MODEL, "zh")
        )
        assertEquals(
            "en",
            normalizeCohereLanguageForModel(Prefs.COHERE_ARABIC_ASR_MODEL, "en")
        )
    }
}
