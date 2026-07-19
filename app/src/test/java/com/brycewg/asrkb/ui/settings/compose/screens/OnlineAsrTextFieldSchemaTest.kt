// JVM tests for common online ASR settings text field rows.
package com.brycewg.asrkb.ui.settings.compose.screens

import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.KEY_GEM_API_KEY
import com.brycewg.asrkb.store.KEY_GEM_ENDPOINT
import com.brycewg.asrkb.store.KEY_GEM_MODEL
import com.brycewg.asrkb.store.KEY_GEM_PROMPT
import com.brycewg.asrkb.store.KEY_OPENROUTER_ASR_API_KEY
import com.brycewg.asrkb.store.KEY_OPENROUTER_ASR_ENDPOINT
import com.brycewg.asrkb.store.KEY_OPENROUTER_ASR_MODEL
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.VendorFieldRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineAsrTextFieldSchemaTest {
    @Test
    fun geminiCommonFieldsMapToTextRowsWithoutOwningSpecialItems() {
        var changedModel = ""
        val fields = geminiCommonTextFields(
            apiKey = "gem-key",
            onApiKeyChange = {},
            endpoint = "",
            onEndpointChange = {},
            model = "gemini-model",
            onModelChange = { changedModel = it },
            prompt = "prompt",
            onPromptChange = {}
        )

        val rows = commonOnlineAsrTextRows(fields, startIndex = 1, count = 7)

        assertEquals(
            listOf(KEY_GEM_API_KEY, KEY_GEM_ENDPOINT, KEY_GEM_MODEL, KEY_GEM_PROMPT),
            rows.map { it.key }
        )
        assertEquals(
            listOf(
                VendorFieldRole.Credential,
                VendorFieldRole.Endpoint,
                VendorFieldRole.Model,
                VendorFieldRole.Prompt
            ),
            rows.map { it.role }
        )
        assertEquals(
            listOf(
                R.string.label_gemini_api_key,
                R.string.label_gemini_endpoint,
                R.string.label_gemini_model,
                R.string.label_gemini_prompt
            ),
            rows.map { it.labelRes }
        )
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.index })
        assertEquals(listOf(7, 7, 7, 7), rows.map { it.count })
        assertTrue(rows[0].password)
        assertEquals(Prefs.DEFAULT_GEM_ENDPOINT, rows[1].value)
        assertFalse(rows[3].singleLine)
        assertEquals(2, rows[3].minLines)

        rows[2].onValueChange("next-model")

        assertEquals("next-model", changedModel)
    }

    @Test
    fun openRouterCommonFieldsMapToTextRowsWithEndpointDefault() {
        val fields = openRouterCommonTextFields(
            endpoint = "",
            onEndpointChange = {},
            apiKey = "or-key",
            onApiKeyChange = {},
            model = "or-model",
            onModelChange = {}
        )

        val rows = commonOnlineAsrTextRows(fields, startIndex = 2, count = 5)

        assertEquals(
            listOf(
                KEY_OPENROUTER_ASR_ENDPOINT,
                KEY_OPENROUTER_ASR_API_KEY,
                KEY_OPENROUTER_ASR_MODEL
            ),
            rows.map { it.key }
        )
        assertEquals(
            listOf(
                VendorFieldRole.Endpoint,
                VendorFieldRole.Credential,
                VendorFieldRole.Model
            ),
            rows.map { it.role }
        )
        assertEquals(
            listOf(
                R.string.label_openrouter_asr_endpoint,
                R.string.label_openrouter_api_key,
                R.string.label_openrouter_model
            ),
            rows.map { it.labelRes }
        )
        assertEquals(listOf(2, 3, 4), rows.map { it.index })
        assertEquals(listOf(5, 5, 5), rows.map { it.count })
        assertEquals(Prefs.DEFAULT_OPENROUTER_ASR_ENDPOINT, rows[0].value)
        assertTrue(rows[1].password)
    }

    @Test
    fun primaryItemCountsPreserveCommonTextRowsAndExplicitSpecialRows() {
        val profiles = emptyList<Prefs.OpenAiAsrProvider>()

        assertEquals(
            geminiCommonTextFields("", {}, "", {}, "", {}, "", {}).size + 2,
            currentOnlineAsrPrimaryItemCount(
                selectedVendor = AsrVendor.Gemini,
                openAiProviders = profiles,
                openAiUsePrompt = false
            )
        )
        assertEquals(
            openRouterCommonTextFields("", {}, "", {}, "", {}).size + 1,
            currentOnlineAsrPrimaryItemCount(
                selectedVendor = AsrVendor.OpenRouter,
                openAiProviders = profiles,
                openAiUsePrompt = false
            )
        )
        assertEquals(
            4,
            currentOnlineAsrPrimaryItemCount(
                selectedVendor = AsrVendor.Cohere,
                openAiProviders = profiles,
                openAiUsePrompt = false
            )
        )
        assertEquals(
            5,
            currentOnlineAsrPrimaryItemCount(
                selectedVendor = AsrVendor.Cohere,
                openAiProviders = profiles,
                openAiUsePrompt = false,
                cohereCustomModelVisible = true
            )
        )
    }

    @Test
    fun cohereModelSelectionUsesBuiltInsAndCustomFallback() {
        assertEquals(
            Prefs.DEFAULT_COHERE_ASR_MODEL,
            cohereModelSelection(Prefs.DEFAULT_COHERE_ASR_MODEL)
        )
        assertEquals(
            Prefs.COHERE_ARABIC_ASR_MODEL,
            cohereModelSelection(Prefs.COHERE_ARABIC_ASR_MODEL)
        )
        assertEquals(COHERE_CUSTOM_MODEL_OPTION_ID, cohereModelSelection("private-model"))
        assertEquals(COHERE_CUSTOM_MODEL_OPTION_ID, cohereModelSelection(""))
        assertFalse(isCohereCustomModel(Prefs.DEFAULT_COHERE_ASR_MODEL))
        assertTrue(isCohereCustomModel("private-model"))
    }
}
