// Tests local ASR lifecycle UI mappings used by the IME service.
package com.brycewg.asrkb.ime

import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrKeyboardServiceLocalLifecycleTest {
    @Test
    fun localModelMissingErrorResPreservesExistingVendorMessages() {
        assertEquals(
            R.string.error_sensevoice_model_missing,
            localAsrMissingModelErrorRes(AsrVendor.SenseVoice)
        )
        assertEquals(
            R.string.error_funasr_model_missing,
            localAsrMissingModelErrorRes(AsrVendor.FunAsrNano)
        )
        assertEquals(
            R.string.error_qwen3_asr_model_missing,
            localAsrMissingModelErrorRes(AsrVendor.Qwen3Asr)
        )
        assertEquals(
            R.string.error_parakeet_model_missing,
            localAsrMissingModelErrorRes(AsrVendor.Parakeet)
        )
        assertEquals(
            R.string.error_firered_asr_model_missing,
            localAsrMissingModelErrorRes(AsrVendor.FireRedAsr)
        )
        assertEquals(
            R.string.error_x_asr_model_missing,
            localAsrMissingModelErrorRes(AsrVendor.XAsr)
        )
    }

    @Test
    fun missingModelUiConsumesLocalModelCatalog() {
        val source = projectFile(
            "app/src/main/java/com/brycewg/asrkb/ime/AsrKeyboardService.kt"
        ).readText()

        listOf(
            "com.brycewg.asrkb.ui.settings.compose.screens",
            "AllAsrLocalModelSpecs",
            "checkSenseVoiceModel(",
            "checkFunAsrNanoModel(",
            "checkQwen3AsrModel(",
            "checkParakeetModel(",
            "checkFireRedAsrModelFiles(",
            "checkXAsrModelFiles(",
            "R.string.error_sensevoice_model_missing",
            "R.string.error_funasr_model_missing",
            "R.string.error_qwen3_asr_model_missing",
            "R.string.error_parakeet_model_missing",
            "R.string.error_firered_asr_model_missing",
            "R.string.error_x_asr_model_missing"
        ).forEach { forbidden ->
            assertFalse(
                "Keyboard missing-model UI should use local model catalog instead of $forbidden",
                source.contains(forbidden)
            )
        }
    }

    @Test
    fun onlineVendorsHaveNoLocalModelErrorOrPreloadSwitch() {
        val flags = LocalAsrPreloadFlags(
            senseVoice = true,
            funAsrNano = true,
            qwen3Asr = true,
            parakeet = true,
            fireRedAsr = true,
            xAsr = true
        )

        assertNull(localAsrMissingModelErrorRes(AsrVendor.OpenAI))
        assertFalse(isLocalAsrPreloadEnabled(AsrVendor.OpenAI, flags))
    }

    @Test
    fun localPreloadFlagsMapToMatchingVendorOnly() {
        assertTrue(isLocalAsrPreloadEnabled(AsrVendor.SenseVoice, flagsWith(senseVoice = true)))
        assertTrue(isLocalAsrPreloadEnabled(AsrVendor.FunAsrNano, flagsWith(funAsrNano = true)))
        assertTrue(isLocalAsrPreloadEnabled(AsrVendor.Qwen3Asr, flagsWith(qwen3Asr = true)))
        assertTrue(isLocalAsrPreloadEnabled(AsrVendor.Parakeet, flagsWith(parakeet = true)))
        assertTrue(isLocalAsrPreloadEnabled(AsrVendor.FireRedAsr, flagsWith(fireRedAsr = true)))
        assertTrue(isLocalAsrPreloadEnabled(AsrVendor.XAsr, flagsWith(xAsr = true)))

        assertFalse(isLocalAsrPreloadEnabled(AsrVendor.SenseVoice, flagsWith(funAsrNano = true)))
        assertFalse(isLocalAsrPreloadEnabled(AsrVendor.OpenAI, flagsWith(senseVoice = true)))
    }

    private fun flagsWith(
        senseVoice: Boolean = false,
        funAsrNano: Boolean = false,
        qwen3Asr: Boolean = false,
        parakeet: Boolean = false,
        fireRedAsr: Boolean = false,
        xAsr: Boolean = false
    ): LocalAsrPreloadFlags = LocalAsrPreloadFlags(
        senseVoice = senseVoice,
        funAsrNano = funAsrNano,
        qwen3Asr = qwen3Asr,
        parakeet = parakeet,
        fireRedAsr = fireRedAsr,
        xAsr = xAsr
    )

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var dir = File(userDir).absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: break
        }
        return File(dir, path)
    }
}
