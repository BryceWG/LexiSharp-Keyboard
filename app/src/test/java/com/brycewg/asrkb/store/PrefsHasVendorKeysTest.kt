// JVM regression tests for real Prefs ASR supplier configured checks.
package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.asr.AsrVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsHasVendorKeysTest {
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
    fun openAiOfficialEndpointRequiresApiKey() {
        prefs.oaAsrEndpoint = Prefs.DEFAULT_OA_ASR_ENDPOINT
        prefs.oaAsrModel = Prefs.DEFAULT_OA_ASR_MODEL
        prefs.oaAsrApiKey = ""

        assertFalse(prefs.hasVendorKeys(AsrVendor.OpenAI))

        prefs.oaAsrApiKey = "oa-key"

        assertTrue(prefs.hasVendorKeys(AsrVendor.OpenAI))
    }

    @Test
    fun openAiCustomEndpointAllowsMissingApiKeyButRequiresEndpointAndModel() {
        prefs.oaAsrEndpoint = "https://example.test/v1/audio/transcriptions"
        prefs.oaAsrModel = Prefs.DEFAULT_OA_ASR_MODEL
        prefs.oaAsrApiKey = ""

        assertTrue(prefs.hasVendorKeys(AsrVendor.OpenAI))

        prefs.oaAsrModel = ""

        assertFalse(prefs.hasVendorKeys(AsrVendor.OpenAI))

        prefs.oaAsrModel = Prefs.DEFAULT_OA_ASR_MODEL
        prefs.oaAsrEndpoint = ""

        assertFalse(prefs.hasVendorKeys(AsrVendor.OpenAI))
    }

    @Test
    fun openRouterRequiresApiKey() {
        prefs.openRouterAsrEndpoint = Prefs.DEFAULT_OPENROUTER_ASR_ENDPOINT
        prefs.openRouterAsrModel = Prefs.DEFAULT_OPENROUTER_ASR_MODEL
        prefs.openRouterAsrApiKey = ""

        assertFalse(prefs.hasVendorKeys(AsrVendor.OpenRouter))

        prefs.openRouterAsrApiKey = "or-key"

        assertTrue(prefs.hasVendorKeys(AsrVendor.OpenRouter))
    }

    @Test
    fun mimoCustomPresetRequiresEffectiveEndpoint() {
        prefs.mimoAsrEndpointPreset = Prefs.MIMO_ENDPOINT_PRESET_CUSTOM
        prefs.mimoAsrApiKey = "mimo-key"
        prefs.mimoAsrEndpoint = ""

        assertFalse(prefs.hasVendorKeys(AsrVendor.MiMo))

        prefs.mimoAsrEndpoint = "https://example.test/v1/chat/completions"

        assertTrue(prefs.hasVendorKeys(AsrVendor.MiMo))
    }

    @Test
    fun stepAudioEndpointPresetResolvesEffectiveEndpoint() {
        prefs.stepAudioEndpointPreset = Prefs.STEPAUDIO_ENDPOINT_PRESET_PAYGO

        assertEquals(Prefs.DEFAULT_STEPAUDIO_ASR_ENDPOINT, prefs.getEffectiveStepAudioAsrEndpoint())

        prefs.stepAudioEndpointPreset = Prefs.STEPAUDIO_ENDPOINT_PRESET_CODING_PLAN

        assertEquals(
            Prefs.STEPAUDIO_ENDPOINT_PRESETS[Prefs.STEPAUDIO_ENDPOINT_PRESET_CODING_PLAN],
            prefs.getEffectiveStepAudioAsrEndpoint()
        )

        prefs.stepAudioEndpointPreset = Prefs.STEPAUDIO_ENDPOINT_PRESET_CUSTOM
        prefs.stepAudioEndpoint = "https://example.test/v1/audio/asr/sse"

        assertEquals("https://example.test/v1/audio/asr/sse", prefs.getEffectiveStepAudioAsrEndpoint())
    }

    @Test
    fun stepAudioCustomPresetRequiresEffectiveEndpoint() {
        prefs.stepAudioEndpointPreset = Prefs.STEPAUDIO_ENDPOINT_PRESET_CUSTOM
        prefs.stepAudioApiKey = "step-key"
        prefs.stepAudioEndpoint = ""

        assertFalse(prefs.hasVendorKeys(AsrVendor.StepAudio))

        prefs.stepAudioEndpoint = "https://example.test/v1/audio/asr/sse"

        assertTrue(prefs.hasVendorKeys(AsrVendor.StepAudio))
    }

    @Test
    fun mimoAndStepAudioKeepApiKeysPerEndpointPreset() {
        prefs.mimoAsrEndpointPreset = Prefs.MIMO_ENDPOINT_PRESET_PAYGO
        prefs.mimoAsrApiKey = "mimo-paygo"
        prefs.mimoAsrEndpointPreset = Prefs.MIMO_ENDPOINT_PRESET_CN
        prefs.mimoAsrApiKey = "mimo-cn"

        prefs.mimoAsrEndpointPreset = Prefs.MIMO_ENDPOINT_PRESET_PAYGO
        assertEquals("mimo-paygo", prefs.mimoAsrApiKey)

        prefs.mimoAsrEndpointPreset = Prefs.MIMO_ENDPOINT_PRESET_CN
        assertEquals("mimo-cn", prefs.mimoAsrApiKey)

        prefs.stepAudioEndpointPreset = Prefs.STEPAUDIO_ENDPOINT_PRESET_PAYGO
        prefs.stepAudioApiKey = "step-paygo"
        prefs.stepAudioEndpointPreset = Prefs.STEPAUDIO_ENDPOINT_PRESET_CODING_PLAN
        prefs.stepAudioApiKey = "step-plan"

        prefs.stepAudioEndpointPreset = Prefs.STEPAUDIO_ENDPOINT_PRESET_PAYGO
        assertEquals("step-paygo", prefs.stepAudioApiKey)

        prefs.stepAudioEndpointPreset = Prefs.STEPAUDIO_ENDPOINT_PRESET_CODING_PLAN
        assertEquals("step-plan", prefs.stepAudioApiKey)
    }

    @Test
    fun siliconFlowFreeServiceCanConfigureWithoutKey() {
        prefs.sfFreeAsrEnabled = true
        prefs.sfApiKey = ""

        assertTrue(prefs.hasVendorKeys(AsrVendor.SiliconFlow))

        prefs.sfFreeAsrEnabled = false

        assertFalse(prefs.hasVendorKeys(AsrVendor.SiliconFlow))

        prefs.sfApiKey = "sf-key"

        assertTrue(prefs.hasVendorKeys(AsrVendor.SiliconFlow))
    }

    @Test
    fun volcOldAuthRequiresAppKeyAndAccessKey() {
        prefs.volcUseNewAuth = false
        prefs.appKey = "app-key"
        prefs.accessKey = ""

        assertFalse(prefs.hasVendorKeys(AsrVendor.Volc))

        prefs.accessKey = "access-key"

        assertTrue(prefs.hasVendorKeys(AsrVendor.Volc))
    }

    @Test
    fun volcNewAuthRequiresOnlyApiKey() {
        prefs.volcUseNewAuth = true
        prefs.volcApiKey = ""
        prefs.appKey = ""
        prefs.accessKey = "legacy-access-key"

        assertFalse(prefs.hasVendorKeys(AsrVendor.Volc))

        prefs.volcApiKey = "api-key"

        assertTrue(prefs.hasVendorKeys(AsrVendor.Volc))
    }
}
