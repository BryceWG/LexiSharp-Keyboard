/**
 * ASR 供应商可用性判断与分组工具：
 * - 在线供应商：按 API/鉴权配置是否完整判断
 * - 本地供应商：按模型文件是否已安装判断
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import java.io.File

internal data class AsrVendorPartition(
    val configured: List<AsrVendor>,
    val unconfigured: List<AsrVendor>,
)

internal fun partitionAsrVendorsByConfigured(
    context: Context,
    prefs: Prefs,
    vendors: List<AsrVendor>,
): AsrVendorPartition {
    val configured = mutableListOf<AsrVendor>()
    val unconfigured = mutableListOf<AsrVendor>()
    vendors.forEach { vendor ->
        if (isAsrVendorConfigured(context, prefs, vendor)) {
            configured.add(vendor)
        } else {
            unconfigured.add(vendor)
        }
    }
    return AsrVendorPartition(
        configured = configured,
        unconfigured = unconfigured,
    )
}

internal fun isAsrVendorConfigured(
    context: Context,
    prefs: Prefs,
    vendor: AsrVendor,
): Boolean {
    return try {
        when (vendor) {
            AsrVendor.SenseVoice -> hasSenseVoiceModelInstalled(context, prefs)
            AsrVendor.FunAsrNano -> hasFunAsrNanoModelInstalled(context)
            AsrVendor.Telespeech -> hasTelespeechModelInstalled(context, prefs)
            AsrVendor.Paraformer -> hasParaformerModelInstalled(context, prefs)
            AsrVendor.SiliconFlow -> prefs.hasSfKeys()
            else -> prefs.hasVendorKeys(vendor)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to check vendor availability: $vendor", t)
        false
    }
}

private fun hasSenseVoiceModelInstalled(context: Context, prefs: Prefs): Boolean {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val root = File(base, "sensevoice")
    val variantDir = if (prefs.svModelVariant == "small-full") {
        File(root, "small-full")
    } else {
        File(root, "small-int8")
    }
    val modelDir = findSvModelDir(variantDir) ?: findSvModelDir(root)
    return modelDir != null &&
        File(modelDir, "tokens.txt").exists() &&
        (
            File(modelDir, "model.int8.onnx").exists() ||
                File(modelDir, "model.onnx").exists()
            )
}

private fun hasFunAsrNanoModelInstalled(context: Context): Boolean {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val root = File(base, "funasr_nano")
    val variantDir = File(root, "nano-int8")
    val modelDir = findFnModelDir(variantDir) ?: findFnModelDir(root)
    val tokenizerDir = modelDir?.let { findFnTokenizerDir(it) }
    return modelDir != null &&
        File(modelDir, "encoder_adaptor.int8.onnx").exists() &&
        File(modelDir, "llm.int8.onnx").exists() &&
        File(modelDir, "embedding.int8.onnx").exists() &&
        tokenizerDir != null &&
        File(tokenizerDir, "tokenizer.json").exists()
}

private fun hasTelespeechModelInstalled(context: Context, prefs: Prefs): Boolean {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val root = File(base, "telespeech")
    val variantDir = if (prefs.tsModelVariant == "full") {
        File(root, "full")
    } else {
        File(root, "int8")
    }
    val modelDir = findTsModelDir(variantDir) ?: findTsModelDir(root)
    return modelDir != null &&
        File(modelDir, "tokens.txt").exists() &&
        (
            File(modelDir, "model.int8.onnx").exists() ||
                File(modelDir, "model.onnx").exists()
            )
}

private fun hasParaformerModelInstalled(context: Context, prefs: Prefs): Boolean {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val root = File(base, "paraformer")
    val group = if (prefs.pfModelVariant.startsWith("trilingual")) {
        File(root, "trilingual")
    } else {
        File(root, "bilingual")
    }
    val modelDir = findPfModelDir(group)
    return modelDir != null &&
        File(modelDir, "tokens.txt").exists() &&
        (
            (
                File(modelDir, "encoder.onnx").exists() &&
                    File(modelDir, "decoder.onnx").exists()
                ) ||
                (
                    File(modelDir, "encoder.int8.onnx").exists() &&
                        File(modelDir, "decoder.int8.onnx").exists()
                    )
            )
}

private const val TAG = "AsrVendorAvailability"
