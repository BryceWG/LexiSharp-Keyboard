package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrOnlineSettingsFieldsTest {
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        prefs = Prefs(context)
    }

    @Test
    fun constructorNormalizesAndPersistsCohereLanguage() {
        prefs.cohereAsrModel = Prefs.COHERE_ARABIC_ASR_MODEL
        prefs.cohereAsrLanguage = "zh"

        val fields = AsrOnlineSettingsFields(prefs)

        assertEquals("ar", fields.cohereLanguage)
        assertEquals("ar", prefs.cohereAsrLanguage)
    }

    @Test
    fun refreshNormalizesAndPersistsCohereLanguage() {
        val fields = AsrOnlineSettingsFields(prefs)
        prefs.cohereAsrModel = Prefs.COHERE_ARABIC_ASR_MODEL
        prefs.cohereAsrLanguage = "zh"

        fields.refreshFromPrefs()

        assertEquals("ar", fields.cohereLanguage)
        assertEquals("ar", prefs.cohereAsrLanguage)
    }

    @Test
    fun selectingCustomModelKeepsCurrentModelUntilDraftIsNonBlank() {
        prefs.cohereApiKey = "test-key"
        prefs.cohereAsrModel = Prefs.DEFAULT_COHERE_ASR_MODEL
        val fields = AsrOnlineSettingsFields(prefs)

        fields.showCohereCustomModelInput()
        fields.updateCohereCustomModelDraft("")

        assertEquals(Prefs.DEFAULT_COHERE_ASR_MODEL, fields.cohereModel)
        assertEquals(Prefs.DEFAULT_COHERE_ASR_MODEL, prefs.cohereAsrModel)
        assertTrue(prefs.hasCohereKeys())

        fields.updateCohereCustomModelDraft("custom-transcribe-model")

        assertEquals("custom-transcribe-model", fields.cohereModel)
        assertEquals("custom-transcribe-model", prefs.cohereAsrModel)
    }
}
