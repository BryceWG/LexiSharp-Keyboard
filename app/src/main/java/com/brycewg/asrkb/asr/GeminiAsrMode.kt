/**
 * Gemini 文件识别的调用模式。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

internal enum class GeminiAsrMode(val id: String) {
    Gemini("gemini"),
    Transcribe("transcribe");

    companion object {
        fun fromId(id: String?): GeminiAsrMode = entries.firstOrNull { it.id == id } ?: Gemini
    }
}
