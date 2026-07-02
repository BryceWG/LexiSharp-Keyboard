/**
 * OpenAI ASR 运行时策略的纯逻辑工具。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

internal fun normalizeOpenAiTranscriptionsEndpoint(endpoint: String): String =
    endpoint.ifBlank { Prefs.DEFAULT_OA_ASR_ENDPOINT }
        .trim()
        .trimEnd('/')

internal fun isOpenAiOfficialTranscriptionsEndpoint(endpoint: String): Boolean =
    normalizeOpenAiTranscriptionsEndpoint(endpoint).equals(
        normalizeOpenAiTranscriptionsEndpoint(Prefs.DEFAULT_OA_ASR_ENDPOINT),
        ignoreCase = true
    )

internal fun isOpenAiCustomTranscriptionsEndpoint(endpoint: String): Boolean =
    !isOpenAiOfficialTranscriptionsEndpoint(endpoint)

internal fun shouldCompressAudioBeforeOpenAiUpload(
    globalEnabled: Boolean,
    endpoint: String
): Boolean = globalEnabled && isOpenAiOfficialTranscriptionsEndpoint(endpoint)
