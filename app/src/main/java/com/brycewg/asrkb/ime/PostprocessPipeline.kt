package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.TextSanitizer
import com.brycewg.asrkb.util.TypewriterTextAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PostprocessPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val inputHelper: InputConnectionHelper,
    private val llmPostProcessor: LlmPostProcessor,
    private val logTag: String,
) {
    data class Result(
        val finalText: String,
        val rawText: String,
        val postprocFailed: Boolean,
        val aiUsed: Boolean,
        val aiPostMs: Long,
        val aiPostStatus: AsrHistoryStore.AiPostStatus,
    )

    suspend fun process(
        ic: InputConnection,
        text: String,
        isCancelled: () -> Boolean,
        onFinalReady: () -> Unit,
        onPostprocFailed: () -> Unit,
    ): Result? {
        val rawText = try {
            if (prefs.trimFinalTrailingPunct) TextSanitizer.trimTrailingPunctAndEmoji(text) else text
        } catch (_: Throwable) {
            text
        }

        val typewriterEnabled = try {
            prefs.postprocTypewriterEnabled
        } catch (_: Throwable) {
            true
        }

        var committed = false
        val typewriter = if (typewriterEnabled) {
            TypewriterTextAnimator(
                scope = scope,
                onEmit = emit@{ typed ->
                    if (isCancelled() || committed) return@emit
                    inputHelper.setComposingText(ic, typed)
                },
            )
        } else {
            null
        }

        var lastStreamingText: String? = null
        val onStreamingUpdate: (String) -> Unit = onStreamingUpdate@{ streamed ->
            if (isCancelled() || committed) return@onStreamingUpdate
            if (streamed.isEmpty() || streamed == lastStreamingText) return@onStreamingUpdate
            lastStreamingText = streamed
            if (typewriter != null) {
                typewriter.submit(streamed)
            } else {
                scope.launch {
                    if (isCancelled() || committed) return@launch
                    inputHelper.setComposingText(ic, streamed)
                }
            }
        }

        val res: LlmPostProcessor.LlmProcessResult = try {
            com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(
                context,
                prefs,
                text,
                llmPostProcessor,
                onStreamingUpdate = onStreamingUpdate,
            )
        } catch (t: Throwable) {
            Log.e(logTag, "applyWithAi failed", t)
            // 统一回退到 applySimple，确保语音预设仍然生效
            val fallback = try {
                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
            } catch (_: Throwable) {
                rawText
            }
            LlmPostProcessor.LlmProcessResult(ok = false, text = fallback, attempted = true, llmMs = 0L)
        }

        val postprocFailed = !res.ok
        if (postprocFailed) {
            onPostprocFailed()
        }

        val finalText = if (res.text.isBlank()) {
            // 若 AI 返回空文本，回退到简单后处理（包含正则/繁体），而非仅使用预修剪文本
            try {
                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
            } catch (t: Throwable) {
                Log.w(logTag, "applySimple fallback after blank AI result failed", t)
                rawText
            }
        } else {
            res.text
        }

        val aiUsed = (res.usedAi && res.ok)
        val aiPostMs = if (res.attempted) res.llmMs else 0L
        val aiPostStatus = when {
            res.attempted && aiUsed -> AsrHistoryStore.AiPostStatus.SUCCESS
            res.attempted -> AsrHistoryStore.AiPostStatus.FAILED
            else -> AsrHistoryStore.AiPostStatus.NONE
        }

        if (isCancelled()) {
            committed = true
            typewriter?.cancel()
            return null
        }

        onFinalReady()

        if (typewriter != null && aiUsed && finalText.isNotEmpty()) {
            // 最终结果到达后：不再“秒出”，改为让打字机以最快速度追到最终文本
            typewriter.submit(finalText, rush = true)
            val finalLen = finalText.length
            val t0 = try {
                android.os.SystemClock.uptimeMillis()
            } catch (_: Throwable) {
                0L
            }
            while (!isCancelled() &&
                (t0 <= 0L || (android.os.SystemClock.uptimeMillis() - t0) < 2_000L) &&
                typewriter.currentText().length != finalLen
            ) {
                delay(20)
            }
        }

        if (isCancelled()) {
            committed = true
            typewriter?.cancel()
            return null
        }

        committed = true
        typewriter?.cancel()
        inputHelper.setComposingText(ic, finalText)

        return Result(
            finalText = finalText,
            rawText = rawText,
            postprocFailed = postprocFailed,
            aiUsed = aiUsed,
            aiPostMs = aiPostMs,
            aiPostStatus = aiPostStatus,
        )
    }
}
