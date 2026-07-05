// JVM tests for per-endpoint preset ASR API key storage.
package com.brycewg.asrkb.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsEndpointPresetApiKeyStoreTest {
    @Test
    fun setAndGetApiKeyByPreset() {
        val encoded = PrefsEndpointPresetApiKeyStore.setApiKey(
            keysJson = "",
            preset = Prefs.MIMO_ENDPOINT_PRESET_PAYGO,
            apiKey = "paygo-key"
        )
        val paygoResult = PrefsEndpointPresetApiKeyStore.getApiKey(
            keysJson = encoded,
            legacyApiKey = "",
            preset = Prefs.MIMO_ENDPOINT_PRESET_PAYGO
        )
        val cnResult = PrefsEndpointPresetApiKeyStore.getApiKey(
            keysJson = encoded,
            legacyApiKey = "",
            preset = Prefs.MIMO_ENDPOINT_PRESET_CN
        )

        assertEquals("paygo-key", paygoResult.apiKey)
        assertNull(paygoResult.migratedKeysJson)
        assertEquals("", cnResult.apiKey)
    }

    @Test
    fun migratesLegacyApiKeyIntoCurrentPresetOnce() {
        val result = PrefsEndpointPresetApiKeyStore.getApiKey(
            keysJson = "",
            legacyApiKey = "legacy-key",
            preset = Prefs.STEPAUDIO_ENDPOINT_PRESET_CODING_PLAN
        )

        assertEquals("legacy-key", result.apiKey)
        assertTrue(!result.migratedKeysJson.isNullOrBlank())
        assertTrue(result.migratedKeysJson!!.contains(Prefs.STEPAUDIO_ENDPOINT_PRESET_CODING_PLAN))
    }

    @Test
    fun presetKeysRemainIndependentAfterUpdates() {
        var keysJson = PrefsEndpointPresetApiKeyStore.setApiKey(
            keysJson = "",
            preset = Prefs.MIMO_ENDPOINT_PRESET_PAYGO,
            apiKey = "paygo-key"
        )
        keysJson = PrefsEndpointPresetApiKeyStore.setApiKey(
            keysJson = keysJson,
            preset = Prefs.MIMO_ENDPOINT_PRESET_CN,
            apiKey = "cn-key"
        )

        val paygo = PrefsEndpointPresetApiKeyStore.getApiKey(
            keysJson = keysJson,
            legacyApiKey = "",
            preset = Prefs.MIMO_ENDPOINT_PRESET_PAYGO
        )
        val cn = PrefsEndpointPresetApiKeyStore.getApiKey(
            keysJson = keysJson,
            legacyApiKey = "",
            preset = Prefs.MIMO_ENDPOINT_PRESET_CN
        )

        assertEquals("paygo-key", paygo.apiKey)
        assertEquals("cn-key", cn.apiKey)
    }
}
