// Tests local ASR lifecycle UI mappings used by the floating ball session manager.
package com.brycewg.asrkb.ui.floatingball

import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingBallLocalLifecycleTest {
    @Test
    fun localModelMissingErrorResPreservesExistingVendorMessages() {
        assertEquals(
            R.string.error_sensevoice_model_missing,
            floatingBallLocalAsrMissingModelErrorRes(AsrVendor.SenseVoice)
        )
        assertEquals(
            R.string.error_funasr_model_missing,
            floatingBallLocalAsrMissingModelErrorRes(AsrVendor.FunAsrNano)
        )
        assertEquals(
            R.string.error_qwen3_asr_model_missing,
            floatingBallLocalAsrMissingModelErrorRes(AsrVendor.Qwen3Asr)
        )
        assertEquals(
            R.string.error_parakeet_model_missing,
            floatingBallLocalAsrMissingModelErrorRes(AsrVendor.Parakeet)
        )
        assertEquals(
            R.string.error_firered_asr_model_missing,
            floatingBallLocalAsrMissingModelErrorRes(AsrVendor.FireRedAsr)
        )
        assertEquals(
            R.string.error_x_asr_model_missing,
            floatingBallLocalAsrMissingModelErrorRes(AsrVendor.XAsr)
        )
    }

    @Test
    fun missingModelUiConsumesLocalModelCatalog() {
        val source = projectFile(
            "app/src/main/java/com/brycewg/asrkb/ui/floatingball/AsrSessionManager.kt"
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
                "Floating ball missing-model UI should use local model catalog instead of $forbidden",
                source.contains(forbidden)
            )
        }
    }

    @Test
    fun onlineVendorsHaveNoLocalModelError() {
        assertNull(floatingBallLocalAsrMissingModelErrorRes(AsrVendor.OpenAI))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var dir = File(userDir).absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: break
        }
        return File(dir, path)
    }
}
