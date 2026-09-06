/**
 * LLM 后处理两档超时预算：正文首 token 与输出阶段。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

internal object LlmPostprocessTimeouts {
    private const val OUTPUT_TPS = 10
    private const val MIN_OUTPUT_MS = 2_000L
    private const val FIRST_TOKEN_MS_NO_REASONING = 8_000L
    private const val FIRST_TOKEN_MS_REASONING = 15_000L
    private const val CONNECTIVITY_FIRST_TOKEN_MS = 60_000L
    private const val CONNECTIVITY_OUTPUT_MS = 10_000L

    data class Budget(
        val firstTokenMs: Long,
        val outputMs: Long,
        val reasoningEnabled: Boolean,
        val charCount: Int
    ) {
        val combinedMs: Long get() = firstTokenMs + outputMs
    }

    fun budget(reasoningEnabled: Boolean, inputCharCount: Int): Budget {
        val charCount = inputCharCount.coerceAtLeast(0)
        return Budget(
            firstTokenMs = if (reasoningEnabled) {
                FIRST_TOKEN_MS_REASONING
            } else {
                FIRST_TOKEN_MS_NO_REASONING
            },
            outputMs = outputTimeoutMs(charCount),
            reasoningEnabled = reasoningEnabled,
            charCount = charCount
        )
    }

    fun connectivityBudget(reasoningEnabled: Boolean): Budget = Budget(
        firstTokenMs = CONNECTIVITY_FIRST_TOKEN_MS,
        outputMs = CONNECTIVITY_OUTPUT_MS,
        reasoningEnabled = reasoningEnabled,
        charCount = 0
    )

    fun outputTimeoutMs(inputCharCount: Int): Long {
        val estimatedMs = inputCharCount.coerceAtLeast(0) * 1_000L / OUTPUT_TPS
        return estimatedMs.coerceAtLeast(MIN_OUTPUT_MS)
    }
}
