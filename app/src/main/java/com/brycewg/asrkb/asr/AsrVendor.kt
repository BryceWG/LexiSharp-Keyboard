package com.brycewg.asrkb.asr

enum class AsrVendor(val id: String) {
    Volc("volc"),
    SiliconFlow("siliconflow"),
    ElevenLabs("elevenlabs"),
    OpenAI("openai"),
    DashScope("dashscope"),
    Gemini("gemini"),
    Soniox("soniox"),
    Zhipu("zhipu"),
    SenseVoice("sensevoice"),
    FunAsrNano("funasr_nano"),
    Telespeech("telespeech"),
    Paraformer("paraformer"),
    ;

    companion object {
        fun fromId(id: String?): AsrVendor = when (id?.lowercase()) {
            SiliconFlow.id -> SiliconFlow
            ElevenLabs.id -> ElevenLabs
            OpenAI.id -> OpenAI
            DashScope.id -> DashScope
            Gemini.id -> Gemini
            Soniox.id -> Soniox
            Zhipu.id -> Zhipu
            SenseVoice.id -> SenseVoice
            FunAsrNano.id -> FunAsrNano
            Telespeech.id -> Telespeech
            Paraformer.id -> Paraformer
            "zipformer" -> Paraformer
            "funasr" -> FunAsrNano
            else -> Volc
        }
    }
}
