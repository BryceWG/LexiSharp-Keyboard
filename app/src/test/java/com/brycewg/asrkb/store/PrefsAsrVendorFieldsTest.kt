// JVM tests for schema-driven ASR supplier preference fields.
package com.brycewg.asrkb.store

import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.AsrVendorCapability
import com.brycewg.asrkb.asr.AsrVendorRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsAsrVendorFieldsTest {
    @Test
    fun exportWritesSchemaDrivenStringBooleanAndIntFields() {
        val store = FakeVendorFieldStore().apply {
            putString(KEY_DASH_REGION, "intl")
            putBoolean(KEY_VOLC_STREAMING_ENABLED, false)
            putInt(KEY_SV_NUM_THREADS, 99)
        }
        val output = FakeVendorFieldExportSink()

        PrefsAsrVendorFields.export(
            store = store,
            output = output,
            fields = listOf(
                VendorField(KEY_DASH_REGION, default = "cn"),
                VendorField.boolean(KEY_VOLC_STREAMING_ENABLED, default = true),
                VendorField.int(KEY_SV_NUM_THREADS, default = 2, range = 1..8)
            )
        )

        assertEquals("intl", output.values[KEY_DASH_REGION])
        assertFalse(output.values[KEY_VOLC_STREAMING_ENABLED] as Boolean)
        assertEquals(8, output.values[KEY_SV_NUM_THREADS])
    }

    @Test
    fun importOnlyPresentFieldsAndKeepsMissingValuesUntouched() {
        val store = FakeVendorFieldStore().apply {
            putString(KEY_DASH_REGION, "cn")
            putBoolean(KEY_VOLC_STREAMING_ENABLED, true)
            putInt(KEY_SV_NUM_THREADS, 4)
        }
        val input = FakeVendorFieldImportSource(
            mapOf(
                KEY_DASH_REGION to "intl",
                KEY_SV_NUM_THREADS to 0
            )
        )

        PrefsAsrVendorFields.import(
            store = store,
            input = input,
            fields = listOf(
                VendorField(KEY_DASH_REGION, default = "cn"),
                VendorField.boolean(KEY_VOLC_STREAMING_ENABLED, default = true),
                VendorField.int(KEY_SV_NUM_THREADS, default = 2, range = 1..8)
            )
        )

        assertEquals("intl", store.getString(KEY_DASH_REGION, ""))
        assertTrue(store.getBoolean(KEY_VOLC_STREAMING_ENABLED, false))
        assertEquals(1, store.getInt(KEY_SV_NUM_THREADS, 2))
    }

    @Test
    fun importPreservesExplicitBlankStringForFieldsWithNonBlankDefaults() {
        val store = FakeVendorFieldStore().apply {
            putString(KEY_STEPAUDIO_LANGUAGE, "zh")
        }
        val input = FakeVendorFieldImportSource(
            mapOf(KEY_STEPAUDIO_LANGUAGE to "")
        )

        PrefsAsrVendorFields.import(
            store = store,
            input = input,
            fields = listOf(VendorField.language(KEY_STEPAUDIO_LANGUAGE, default = "zh"))
        )

        assertEquals("", store.getString(KEY_STEPAUDIO_LANGUAGE, "fallback"))
    }

    @Test
    fun backupSchemaCoversSimpleSupplierFieldsButLeavesCompatibilityPathsExplicit() {
        val keys = PrefsAsrVendorFields.backupFields.map { it.key }.toSet()

        assertTrue(KEY_SF_FREE_ASR_ENABLED in keys)
        assertTrue(KEY_VOLC_STREAMING_ENABLED in keys)
        assertTrue(KEY_VOLC_USE_NEW_AUTH in keys)
        assertTrue(KEY_VOLC_API_KEY in keys)
        assertTrue(KEY_SONIOX_ENDPOINT_SENSITIVITY_LEVEL in keys)
        assertTrue(KEY_SV_MODEL_VARIANT in keys)
        assertTrue(KEY_FN_USER_PROMPT in keys)
        assertTrue(KEY_QW_USE_ITN in keys)
        assertTrue(KEY_PK_KEEP_ALIVE_MINUTES in keys)

        assertFalse(KEY_OA_ASR_PROVIDERS in keys)
        assertFalse(KEY_OA_ASR_ACTIVE_ID in keys)
        assertFalse(KEY_DASH_STREAMING_ENABLED in keys)
        assertFalse(KEY_DASH_FUNASR_ENABLED in keys)
        assertFalse(KEY_FR_MODEL_VARIANT in keys)
        assertFalse(KEY_X_ASR_MODEL_VARIANT in keys)
    }

    @Test
    fun requiredKeyValidationStillOnlyDependsOnRequiredStringFields() {
        val volcRequired = PrefsAsrVendorFields.requiredStringFieldsForValidation(AsrVendor.Volc)
            .map { it.key }

        assertEquals(listOf(KEY_APP_KEY, KEY_ACCESS_KEY), volcRequired)
    }

    @Test
    fun fieldRolesDescribeStableSupplierContract() {
        val volcCredentials = PrefsAsrVendorFields.requiredCredentialFields(AsrVendor.Volc)
            .map { it.key }
        val openRouterEndpoints = PrefsAsrVendorFields.fieldsByRole(
            AsrVendor.OpenRouter,
            VendorFieldRole.Endpoint
        ).map { it.key }
        val elevenLanguages = PrefsAsrVendorFields.fieldsByRole(
            AsrVendor.ElevenLabs,
            VendorFieldRole.Language
        ).map { it.key }
        val volcStreaming = PrefsAsrVendorFields.fieldsByRole(
            AsrVendor.Volc,
            VendorFieldRole.StreamingToggle
        ).map { it.key }
        val senseVoiceLocal = PrefsAsrVendorFields.fieldsByRole(
            AsrVendor.SenseVoice,
            VendorFieldRole.LocalModel
        ).map { it.key }

        assertEquals(listOf(KEY_APP_KEY, KEY_ACCESS_KEY), volcCredentials)
        assertEquals(listOf(KEY_OPENROUTER_ASR_ENDPOINT), openRouterEndpoints)
        assertEquals(listOf(KEY_ELEVEN_LANGUAGE_CODE), elevenLanguages)
        assertTrue(KEY_VOLC_STREAMING_ENABLED in volcStreaming)
        assertTrue(KEY_VOLC_NONSTREAM_ENABLED in volcStreaming)
        assertTrue(KEY_SV_MODEL_DIR in senseVoiceLocal)
        assertTrue(KEY_SV_MODEL_VARIANT in senseVoiceLocal)
        assertTrue(KEY_SV_NUM_THREADS in senseVoiceLocal)
    }

    @Test
    fun requiredStringValidationUsesSchemaDefaultsForCommonSuppliers() {
        val store = FakeVendorFieldStore().apply {
            putString(
                KEY_STEPAUDIO_API_KEYS_JSON,
                """{"paygo":"key"}"""
            )
        }

        assertFalse(hasRequiredSchemaStrings(AsrVendor.Volc, store))
        assertTrue(hasRequiredSchemaStrings(AsrVendor.StepAudio, store))

        store.putString(KEY_STEPAUDIO_MODEL, "")

        assertFalse(hasRequiredSchemaStrings(AsrVendor.StepAudio, store))
    }

    @Test
    fun optionalFieldDefaultsComeFromSchema() {
        val store = FakeVendorFieldStore()
        val sfModel = PrefsAsrVendorFields.fieldsByRole(AsrVendor.SiliconFlow, VendorFieldRole.Model)
            .first { it.key == KEY_SF_MODEL }
        val mimoLanguage = PrefsAsrVendorFields.fieldsByRole(AsrVendor.MiMo, VendorFieldRole.Language)
            .first { it.key == KEY_MIMO_ASR_LANGUAGE }
        val volcStreaming = PrefsAsrVendorFields.fieldsByRole(AsrVendor.Volc, VendorFieldRole.StreamingToggle)
            .first { it.key == KEY_VOLC_STREAMING_ENABLED }
        val volcNewAuth = PrefsAsrVendorFields.fieldsFor(AsrVendor.Volc)
            .first { it.key == KEY_VOLC_USE_NEW_AUTH }

        assertEquals(Prefs.DEFAULT_SF_MODEL, sfModel.readFrom(store))
        assertEquals(Prefs.DEFAULT_MIMO_ASR_LANGUAGE, mimoLanguage.readFrom(store))
        assertEquals(true, volcStreaming.readFrom(store))
        assertEquals(false, volcNewAuth.readFrom(store))
    }

    @Test
    fun siliconFlowFreeServiceKeepsConfiguredCheckExplicit() {
        val siliconFlowCredentials = PrefsAsrVendorFields.fieldsByRole(
            AsrVendor.SiliconFlow,
            VendorFieldRole.Credential
        )

        assertEquals(listOf(KEY_SF_API_KEY), siliconFlowCredentials.map { it.key })
        assertFalse(siliconFlowCredentials.single().required)
        assertTrue(PrefsAsrVendorFields.requiredStringFieldsForValidation(AsrVendor.SiliconFlow).isEmpty())
    }

    @Test
    fun currentOnlineAndCustomEndpointVendorsParticipateInPreferenceSchema() {
        val customEndpointWithoutEndpointField = setOf(
            AsrVendor.SiliconFlow
        )

        AsrVendorRegistry.descriptors.forEach { descriptor ->
            val fields = PrefsAsrVendorFields.fieldsFor(descriptor.vendor)
            val isLocal = AsrVendorCapability.LocalRecognition in descriptor.capabilities

            if (!isLocal) {
                assertTrue("online schema fields for ${descriptor.vendor}", fields.isNotEmpty())
            }
            if (AsrVendorCapability.CustomEndpoint in descriptor.capabilities) {
                if (descriptor.vendor in customEndpointWithoutEndpointField) {
                    assertTrue("custom schema fields for ${descriptor.vendor}", fields.isNotEmpty())
                } else {
                    assertTrue(
                        "endpoint role for custom endpoint ${descriptor.vendor}",
                        fields.any { it.role == VendorFieldRole.Endpoint }
                    )
                }
            }
        }
    }

    @Test
    fun localPreferenceSchemaExceptionsAreNamedAndLimited() {
        val explicitCompatibilityExceptions = setOf(
            AsrVendor.FireRedAsr,
            AsrVendor.XAsr
        )
        val localVendors = AsrVendorRegistry.descriptors
            .filter { AsrVendorCapability.LocalRecognition in it.capabilities }
            .map { it.vendor }
            .toSet()

        localVendors.forEach { vendor ->
            val fields = PrefsAsrVendorFields.fieldsFor(vendor)
            if (vendor in explicitCompatibilityExceptions) {
                assertTrue("schema exception remains explicit for $vendor", fields.isEmpty())
            } else {
                assertTrue(
                    "local model role for $vendor",
                    fields.any { it.role == VendorFieldRole.LocalModel }
                )
            }
        }
        assertEquals(
            explicitCompatibilityExceptions,
            localVendors.filter { PrefsAsrVendorFields.fieldsFor(it).isEmpty() }.toSet()
        )
    }

    private fun hasRequiredSchemaStrings(vendor: AsrVendor, store: VendorFieldStore): Boolean =
        PrefsAsrVendorFields.requiredStringFieldsForValidation(vendor).all { field ->
            store.getString(field.key, field.default).isNotBlank()
        }

    private class FakeVendorFieldStore : VendorFieldStore {
        private val strings = mutableMapOf<String, String>()
        private val booleans = mutableMapOf<String, Boolean>()
        private val ints = mutableMapOf<String, Int>()

        override fun getString(key: String, default: String): String = strings[key] ?: default

        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default

        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }

        override fun getInt(key: String, default: Int): Int = ints[key] ?: default

        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }
    }

    private class FakeVendorFieldExportSink : VendorFieldExportSink {
        val values = mutableMapOf<String, Any>()

        override fun put(key: String, value: Any) {
            values[key] = value
        }
    }

    private class FakeVendorFieldImportSource(
        private val values: Map<String, Any>
    ) : VendorFieldImportSource {
        override fun has(key: String): Boolean = values.containsKey(key)

        override fun optString(key: String): String = values[key]?.toString().orEmpty()

        override fun optBoolean(key: String, default: Boolean): Boolean =
            (values[key] as? Boolean) ?: default

        override fun optInt(key: String, default: Int): Int =
            when (val value = values[key]) {
                is Int -> value
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: default
                else -> default
            }
    }
}
