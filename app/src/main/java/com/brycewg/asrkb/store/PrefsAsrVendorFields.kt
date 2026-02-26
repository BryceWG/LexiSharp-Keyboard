package com.brycewg.asrkb.store

import com.brycewg.asrkb.asr.AsrVendor

/**
 * ASR 供应商所需配置字段（从 [Prefs] 中拆出）。
 *
 * 用途：
 * - `hasVendorKeys(...)` 校验必填项
 * - 备份导入导出时遍历供应商字段，避免逐个硬编码
 */
internal object PrefsAsrVendorFields {
    internal val vendorFields: Map<AsrVendor, List<VendorField>> = mapOf(
        AsrVendor.Volc to listOf(
            VendorField(KEY_APP_KEY, required = true),
            VendorField(KEY_ACCESS_KEY, required = true),
        ),
        // SiliconFlow：免费服务启用时无需 API Key
        AsrVendor.SiliconFlow to listOf(
            // 免费服务时无需 API Key
            VendorField(KEY_SF_API_KEY, required = false),
            VendorField(KEY_SF_MODEL, default = Prefs.DEFAULT_SF_MODEL),
        ),
        AsrVendor.ElevenLabs to listOf(
            VendorField(KEY_ELEVEN_API_KEY, required = true),
            VendorField(KEY_ELEVEN_LANGUAGE_CODE),
        ),
        AsrVendor.OpenAI to listOf(
            VendorField(KEY_OA_ASR_ENDPOINT, required = true, default = Prefs.DEFAULT_OA_ASR_ENDPOINT),
            VendorField(KEY_OA_ASR_API_KEY, required = false),
            VendorField(KEY_OA_ASR_MODEL, required = true, default = Prefs.DEFAULT_OA_ASR_MODEL),
            // 可选 Prompt 字段（字符串）；开关为布尔，单独在导入/导出处理
            VendorField(KEY_OA_ASR_PROMPT, required = false, default = ""),
            // 可选语言字段（字符串）
            VendorField(KEY_OA_ASR_LANGUAGE, required = false, default = ""),
        ),
        AsrVendor.DashScope to listOf(
            VendorField(KEY_DASH_API_KEY, required = true),
            VendorField(KEY_DASH_PROMPT, default = ""),
            VendorField(KEY_DASH_LANGUAGE, default = ""),
        ),
        AsrVendor.Gemini to listOf(
            VendorField(KEY_GEM_ENDPOINT, required = true, default = Prefs.DEFAULT_GEM_ENDPOINT),
            VendorField(KEY_GEM_API_KEY, required = true),
            VendorField(KEY_GEM_MODEL, required = true, default = Prefs.DEFAULT_GEM_MODEL),
            VendorField(KEY_GEM_PROMPT, default = ""),
        ),
        AsrVendor.Soniox to listOf(
            VendorField(KEY_SONIOX_API_KEY, required = true),
        ),
        AsrVendor.Zhipu to listOf(
            VendorField(KEY_ZHIPU_API_KEY, required = true),
        ),
        // 本地 SenseVoice（sherpa-onnx）无需鉴权
        AsrVendor.SenseVoice to emptyList(),
        // 本地 FunASR Nano（sherpa-onnx）无需鉴权
        AsrVendor.FunAsrNano to emptyList(),
        // 本地 TeleSpeech（sherpa-onnx）无需鉴权
        AsrVendor.Telespeech to emptyList(),
        // 本地 Paraformer（sherpa-onnx）无需鉴权
        AsrVendor.Paraformer to emptyList(),
    )
}

internal data class VendorField(
    val key: String,
    val required: Boolean = false,
    val default: String = "",
)
