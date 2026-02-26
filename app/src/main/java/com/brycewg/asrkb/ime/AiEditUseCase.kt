package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs

internal class AiEditUseCase(
    private val context: Context,
    private val prefs: Prefs,
    private val asrManager: AsrSessionManager,
    private val inputHelper: InputConnectionHelper,
    private val llmPostProcessor: LlmPostProcessor,
    private val uiListenerProvider: () -> KeyboardActionHandler.UiListener?,
    private val currentStateProvider: () -> KeyboardState,
    private val getCurrentInputConnection: () -> InputConnection?,
    private val isCancelled: (seq: Long) -> Boolean,
    private val getLastAsrCommitText: () -> String?,
    private val updateSessionContext: ((KeyboardSessionContext) -> KeyboardSessionContext) -> Unit,
    private val transitionToState: (KeyboardState) -> Unit,
    private val transitionToIdle: (keepMessage: Boolean) -> Unit,
    private val transitionToIdleWithTiming: (showBackupUsedHint: Boolean) -> Unit,
) {
    fun handleClick(ic: InputConnection?) {
        if (ic == null) {
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_idle))
            return
        }

        val stateNow = currentStateProvider()

        if (stateNow is KeyboardState.AiEditListening) {
            asrManager.stopRecording()
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_recognizing))
            return
        }

        if (asrManager.isRunning()) return

        val selected = inputHelper.getSelectedText(ic, 0)
        val targetIsSelection = !selected.isNullOrEmpty()
        val targetText = if (targetIsSelection) {
            selected.toString()
        } else {
            if (prefs.aiEditDefaultToLastAsr) {
                val lastText = getLastAsrCommitText()
                if (lastText.isNullOrEmpty()) {
                    uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_last_asr_not_found))
                    return
                }
                lastText
            } else {
                val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
                val after = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
                val all = before + after
                if (all.isEmpty()) {
                    uiListenerProvider()?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
                    return
                }
                all
            }
        }

        val nextState = KeyboardState.AiEditListening(targetIsSelection, targetText)
        transitionToState(nextState)
        asrManager.startRecording(nextState)
        uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_ai_edit_listening))
    }

    suspend fun handleFinal(text: String, state: KeyboardState.AiEditListening, seq: Long) {
        val ic = getCurrentInputConnection() ?: run {
            transitionToIdleWithTiming(false)
            return
        }

        uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_ai_editing))

        val instruction = if (prefs.trimFinalTrailingPunct) {
            com.brycewg.asrkb.util.TextSanitizer.trimTrailingPunctAndEmoji(text)
        } else {
            text
        }

        val original = state.targetText
        if (original.isBlank()) {
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
            uiListenerProvider()?.onVibrate()
            transitionToIdleWithTiming(false)
            return
        }

        val (ok, edited) = try {
            val res = llmPostProcessor.editTextWithStatus(original, instruction, prefs)
            res.ok to res.text
        } catch (e: Throwable) {
            Log.e(TAG, "AI edit failed", e)
            false to ""
        }

        if (isCancelled(seq)) return

        if (!ok) {
            uiListenerProvider()?.onVibrate()
            transitionToIdle(false)
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_llm_edit_failed))
            return
        }

        if (edited.isBlank()) {
            uiListenerProvider()?.onVibrate()
            transitionToIdle(false)
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_llm_empty_result))
            return
        }

        val editedFinal = try {
            com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, edited)
        } catch (t: Throwable) {
            Log.w(TAG, "applySimple on AI-edited text failed", t)
            edited
        }

        if (isCancelled(seq)) return

        if (state.targetIsSelection) {
            inputHelper.commitText(ic, editedFinal)
        } else {
            if (inputHelper.replaceText(ic, original, editedFinal)) {
                updateSessionContext { prev ->
                    prev.copy(lastAsrCommitText = editedFinal)
                }
            } else {
                uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_last_asr_not_found))
                uiListenerProvider()?.onVibrate()
                transitionToIdleWithTiming(false)
                return
            }
        }

        uiListenerProvider()?.onVibrate()

        updateSessionContext { prev ->
            prev.copy(lastPostprocCommit = null)
        }

        if (asrManager.isRunning()) {
            transitionToState(KeyboardState.Listening())
        } else {
            transitionToIdleWithTiming(false)
        }
    }

    companion object {
        private const val TAG = "AiEditUseCase"
    }
}
