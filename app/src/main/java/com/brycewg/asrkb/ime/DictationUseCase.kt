package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal class DictationUseCase(
    private val context: Context,
    private val prefs: Prefs,
    private val asrManager: AsrSessionManager,
    private val inputHelper: InputConnectionHelper,
    private val processingTimeoutController: ProcessingTimeoutController,
    private val postprocessPipeline: PostprocessPipeline,
    private val commitRecorder: AsrCommitRecorder,
    private val uiListenerProvider: () -> KeyboardActionHandler.UiListener?,
    private val getCurrentEditorInfo: () -> EditorInfo?,
    private val isCancelled: (seq: Long) -> Boolean,
    private val consumeAutoEnterOnce: () -> Boolean,
    private val updateSessionContext: ((KeyboardSessionContext) -> KeyboardSessionContext) -> Unit,
    private val transitionToState: (KeyboardState) -> Unit,
    private val transitionToIdle: (keepMessage: Boolean) -> Unit,
    private val transitionToIdleWithTiming: (showBackupUsedHint: Boolean) -> Unit,
    private val scheduleProcessingTimeout: (audioMsOverride: Long?) -> Unit,
) {
    suspend fun handleFinal(
        ic: InputConnection,
        text: String,
        state: KeyboardState.Listening,
        seq: Long,
    ) {
        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            handleWithPostprocess(ic, text, state, seq)
        } else {
            handleWithoutPostprocess(ic, text, state, seq)
        }
    }

    private suspend fun handleWithPostprocess(
        ic: InputConnection,
        text: String,
        state: KeyboardState.Listening,
        seq: Long,
    ) {
        if (isCancelled(seq)) return

        inputHelper.setComposingText(ic, text)
        uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_ai_processing))

        val postprocessResult = postprocessPipeline.process(
            ic = ic,
            text = text,
            isCancelled = { isCancelled(seq) },
            onFinalReady = { processingTimeoutController.cancel() },
            onPostprocFailed = {
                uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_llm_failed_used_raw))
            },
        ) ?: return

        if (isCancelled(seq)) return

        val finalOut = postprocessResult.finalText
        val rawText = postprocessResult.rawText
        val postprocFailed = postprocessResult.postprocFailed
        val aiUsed = postprocessResult.aiUsed
        val aiPostMs = postprocessResult.aiPostMs
        val aiPostStatus = postprocessResult.aiPostStatus

        inputHelper.setComposingText(ic, finalOut)
        inputHelper.finishComposingText(ic)

        if (finalOut.isNotEmpty() && consumeAutoEnterOnce()) {
            try {
                inputHelper.sendEnter(ic, getCurrentEditorInfo())
            } catch (t: Throwable) {
                Log.w(TAG, "sendEnter after postprocess failed", t)
            }
        }

        updateSessionContext { prev ->
            prev.copy(
                lastAsrCommitText = finalOut,
                lastPostprocCommit = if (finalOut.isNotEmpty() && finalOut != rawText) {
                    PostprocCommit(finalOut, rawText)
                } else {
                    null
                },
            )
        }

        commitRecorder.record(
            text = finalOut,
            aiProcessed = aiUsed,
            aiPostMs = aiPostMs,
            aiPostStatus = aiPostStatus,
        )

        uiListenerProvider()?.onVibrate()

        if (asrManager.isRunning()) {
            transitionToState(KeyboardState.Listening(lockedBySwipe = state.lockedBySwipe))
            return
        }

        if (postprocFailed) {
            // 回到 Idle 后再次设置错误提示，避免被 Idle 文案覆盖
            transitionToIdle(false)
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_llm_failed_used_raw))
            return
        }

        val usedBackupResult =
            (asrManager.getEngine() as? com.brycewg.asrkb.asr.ParallelAsrEngine)
                ?.wasLastResultFromBackup() == true
        transitionToState(KeyboardState.Processing)
        scheduleProcessingTimeout(null)
        transitionToIdleWithTiming(usedBackupResult)
    }

    private fun handleWithoutPostprocess(
        ic: InputConnection,
        text: String,
        state: KeyboardState.Listening,
        seq: Long,
    ) {
        val finalToCommit = com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)

        if (finalToCommit.isBlank()) {
            transitionToIdle(true)
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.asr_error_empty_result))
            uiListenerProvider()?.onVibrate()
            return
        }

        if (isCancelled(seq)) return

        val partial = state.partialText
        if (!partial.isNullOrEmpty()) {
            inputHelper.finishComposingText(ic)
            if (finalToCommit.startsWith(partial)) {
                val remainder = finalToCommit.substring(partial.length)
                if (remainder.isNotEmpty()) {
                    inputHelper.commitText(ic, remainder)
                }
            } else {
                inputHelper.deleteSurroundingText(ic, partial.length, 0)
                inputHelper.commitText(ic, finalToCommit)
            }
        } else {
            val committedStableLen = state.committedStableLen
            val remainder = if (finalToCommit.length > committedStableLen) {
                finalToCommit.substring(committedStableLen)
            } else {
                ""
            }
            inputHelper.finishComposingText(ic)
            if (remainder.isNotEmpty()) {
                inputHelper.commitText(ic, remainder)
            }
        }

        updateSessionContext { prev ->
            prev.copy(
                lastAsrCommitText = finalToCommit,
                lastPostprocCommit = null,
            )
        }

        if (finalToCommit.isNotEmpty() && consumeAutoEnterOnce()) {
            try {
                inputHelper.sendEnter(ic, getCurrentEditorInfo())
            } catch (t: Throwable) {
                Log.w(TAG, "sendEnter after final failed", t)
            }
        }

        commitRecorder.record(text = finalToCommit, aiProcessed = false)

        uiListenerProvider()?.onVibrate()

        if (asrManager.isRunning()) {
            transitionToState(KeyboardState.Listening(lockedBySwipe = state.lockedBySwipe))
            return
        }

        val usedBackupResult =
            (asrManager.getEngine() as? com.brycewg.asrkb.asr.ParallelAsrEngine)
                ?.wasLastResultFromBackup() == true
        transitionToState(KeyboardState.Processing)
        scheduleProcessingTimeout(null)
        transitionToIdleWithTiming(usedBackupResult)
    }

    companion object {
        private const val TAG = "DictationUseCase"
    }
}
