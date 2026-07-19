/**
 * Cohere 内置 ASR 模型的语言能力与配置归一化。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.Prefs

private val GENERAL_TRANSCRIBE_LANGUAGES = listOf(
    "zh", "en", "ar", "ja", "ko", "de", "fr", "it", "es", "pt", "el", "nl", "pl", "vi"
)
private val ARABIC_TRANSCRIBE_LANGUAGES = listOf("ar", "en")

internal fun cohereSupportedLanguageCodes(model: String): List<String>? = when (model.trim()) {
    Prefs.DEFAULT_COHERE_ASR_MODEL -> GENERAL_TRANSCRIBE_LANGUAGES
    Prefs.COHERE_ARABIC_ASR_MODEL -> ARABIC_TRANSCRIBE_LANGUAGES
    else -> null
}

internal fun normalizeCohereLanguageForModel(model: String, language: String): String {
    val normalizedLanguage = language.trim()
    val supportedLanguages = cohereSupportedLanguageCodes(model)
        ?: return normalizedLanguage.ifBlank { Prefs.DEFAULT_COHERE_ASR_LANGUAGE }
    if (normalizedLanguage in supportedLanguages) return normalizedLanguage
    return if (model.trim() == Prefs.COHERE_ARABIC_ASR_MODEL) {
        "ar"
    } else {
        Prefs.DEFAULT_COHERE_ASR_LANGUAGE
    }
}
