// Tests Qwen3-ASR variant-specific local model directory resolution.
package com.brycewg.asrkb.asr

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Qwen3AsrModelResolverTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun uninstalledVariantDoesNotReuseAnotherInstalledVariant() {
        val externalFilesDir = tempFolder.newFolder("external")
        createModelMarkers(externalFilesDir, "qwen3-0.6b-int8")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getExternalFilesDir(type: String?): File = externalFilesDir
        }
        val prefs = Prefs(context).apply { qwModelVariant = "qwen3-1.7b-int8" }

        assertTrue(checkQwen3AsrModel(context, prefs) is LocalModelCheck.Missing)
    }

    private fun createModelMarkers(root: File, variant: String) {
        val modelDir = File(root, "qwen3_asr/$variant").apply { mkdirs() }
        listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx").forEach {
            File(modelDir, it).createNewFile()
        }
        val tokenizerDir = File(modelDir, "tokenizer").apply { mkdirs() }
        listOf("merges.txt", "tokenizer_config.json", "vocab.json").forEach {
            File(tokenizerDir, it).createNewFile()
        }
    }
}
