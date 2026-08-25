/**
 * Compose AI 设置页 LLM 连通性测试动作。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsProgressDialogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AI_LLM_TEST_TAG = "AiLlmTestActions"

internal fun launchAiLlmTest(
    context: Context,
    coroutineScope: CoroutineScope,
    prefs: Prefs,
    sessionId: Long,
    flushPendingWrites: () -> Unit,
    onCancel: () -> Unit,
    isActiveSession: (Long) -> Boolean,
    onProcessorChange: (LlmPostProcessor?) -> Unit,
    onProgressChange: (SettingsProgressDialogState?) -> Unit,
    onSuccess: (LlmPostProcessor.LlmTestResult) -> Unit,
    onFailed: (String) -> Unit,
    onClear: (Long, SettingsProgressDialogState) -> Unit
): Job {
    flushPendingWrites()
    val progressState = SettingsProgressDialogState(
        message = context.getString(R.string.llm_test_running),
        cancelText = context.getString(R.string.btn_cancel),
        onCancel = onCancel
    )
    onProgressChange(progressState)

    return coroutineScope.launch {
        try {
            val processor = LlmPostProcessor()
            onProcessorChange(processor)
            val result = withContext(Dispatchers.IO) {
                processor.testConnectivity(prefs)
            }
            if (!isActive || !isActiveSession(sessionId)) return@launch
            if (result.ok) {
                onSuccess(result)
            } else {
                onFailed(
                    context.getString(
                        R.string.llm_test_failed_reason,
                        result.toDisplayMessage(context)
                    )
                )
            }
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(AI_LLM_TEST_TAG, "Failed to test LLM call", e)
            if (!isActive || !isActiveSession(sessionId)) return@launch
            onFailed(
                context.getString(
                    R.string.llm_test_failed_reason,
                    e.message ?: "unknown"
                )
            )
        } finally {
            onClear(sessionId, progressState)
        }
    }
}

private fun LlmPostProcessor.LlmTestResult.toDisplayMessage(context: Context): String = when {
    message?.contains("Missing endpoint", ignoreCase = true) == true ||
        message?.contains("Missing model", ignoreCase = true) == true ->
        context.getString(R.string.llm_test_missing_params)

    httpCode != null -> "HTTP $httpCode: ${message ?: ""}"
    else -> message ?: context.getString(R.string.llm_test_failed_generic)
}
