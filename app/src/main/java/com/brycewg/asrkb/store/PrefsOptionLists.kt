package com.brycewg.asrkb.store

/**
 * Prefs 内部用到的选项列表（从 [Prefs] 中拆出）。
 *
 * 注意：
 * - 这里仅承载数据定义；对外仍通过 `Prefs.*` 暴露，避免改动调用点。
 */
internal object PrefsOptionLists {
    val SF_FREE_ASR_MODELS = listOf(
        "FunAudioLLM/SenseVoiceSmall",
        "TeleAI/TeleSpeechASR",
    )

    val SF_FREE_LLM_MODELS = listOf(
        "Qwen/Qwen3-8B",
        "THUDM/GLM-4-9B-0414",
    )
}
