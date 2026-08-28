package com.brycewg.asrkb.store

/**
 * Prefs 内部用到的选项列表（从 [Prefs] 中拆出）。
 *
 * 注意：
 * - 这里仅承载数据定义；对外仍通过 `Prefs.*` 暴露，避免改动调用点。
 */
internal object PrefsOptionLists {
    const val SF_MODEL_QWEN3_ASR = "Qwen/Qwen3-ASR-1.7B"
    const val SF_MODEL_XINGCHEN_ULTRA = "XingChenAGI/XingChenASR-V3.2-Ultra"
    const val SF_MODEL_XINGCHEN_GSR = "XingChenAGI/XingChenGSR-V1.0"
    const val SF_MODEL_XINGCHEN_V32 = "XingChenAGI/XingChenASR-V3.2"
    const val SF_MODEL_SENSEVOICE = "FunAudioLLM/SenseVoiceSmall"
    const val SF_MODEL_TELESPEECH_ASR = "TeleAI/TeleSpeechASR"
    const val SF_MODEL_OMNI_INSTRUCT = "Qwen/Qwen3-Omni-30B-A3B-Instruct"
    const val SF_MODEL_OMNI_THINKING = "Qwen/Qwen3-Omni-30B-A3B-Thinking"

    val SF_FREE_ASR_MODELS = listOf(
        SF_MODEL_SENSEVOICE,
        SF_MODEL_XINGCHEN_V32,
        SF_MODEL_XINGCHEN_ULTRA,
        SF_MODEL_XINGCHEN_GSR,
        SF_MODEL_TELESPEECH_ASR
    )

    val SF_PAID_ASR_MODELS = listOf(
        SF_MODEL_OMNI_INSTRUCT,
        SF_MODEL_OMNI_THINKING,
        SF_MODEL_QWEN3_ASR
    ) + SF_FREE_ASR_MODELS

    val SF_FREE_LLM_MODELS = listOf(
        "Qwen/Qwen3-8B",
        "THUDM/GLM-4-9B-0414"
    )
}
