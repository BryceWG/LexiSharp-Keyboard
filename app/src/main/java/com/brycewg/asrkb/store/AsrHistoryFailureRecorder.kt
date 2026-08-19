package com.brycewg.asrkb.store

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.debug.DebugLogManager

/**
 * 将音频已采集但未成功提交的会话写入识别历史，供历史页重新识别。
 *
 * 归属模块：store
 */
internal object AsrHistoryFailureRecorder {
    private const val TAG = "AsrHistoryFailureRecorder"
    const val SHORT_AUDIO_MAX_MS = 250L

    fun archive(
        context: Context,
        prefs: Prefs,
        capture: AsrHistoryAudioCapture?,
        recordId: String?,
        source: String,
        vendorId: String,
        audioMs: Long,
        totalElapsedMs: Long,
        procMs: Long,
        rawText: String?,
        status: AsrHistoryStore.AsrHistoryStatus,
        failStage: AsrHistoryStore.AsrHistoryFailStage,
        failReasonCode: String,
        audioAlreadySaved: Boolean = false
    ): Boolean {
        if (recordId.isNullOrEmpty()) {
            capture?.discard()
            return false
        }
        if (audioMs in 1L..SHORT_AUDIO_MAX_MS) {
            // 太短不建失败历史；queued 路径可能已落盘，必须无条件删文件
            capture?.discard()
            AsrHistoryAudioStore(context).delete(recordId)
            return false
        }
        if (!audioAlreadySaved && capture == null) {
            return false
        }
        val saved = if (audioAlreadySaved) {
            true
        } else {
            capture?.complete() == true
        }
        if (!saved) {
            capture?.discard()
            AsrHistoryAudioStore(context).delete(recordId)
            return false
        }
        if (prefs.disableAsrHistory) {
            AsrHistoryAudioStore(context).delete(recordId)
            return false
        }
        return try {
            val store = AsrHistoryStore(context)
            store.add(
                AsrHistoryStore.AsrHistoryRecord(
                    id = recordId,
                    timestamp = System.currentTimeMillis(),
                    text = "",
                    rawText = rawText?.takeIf { it.isNotBlank() },
                    vendorId = vendorId,
                    audioMs = audioMs,
                    totalElapsedMs = totalElapsedMs,
                    procMs = procMs,
                    source = source,
                    aiProcessed = false,
                    charCount = 0,
                    status = status,
                    failStage = failStage,
                    failReasonCode = failReasonCode
                )
            )
            AsrHistoryAudioStore.pruneAsync(
                context,
                store.listAll(),
                prefs.audioHistoryRetentionCount
            )
            try {
                DebugLogManager.logBase(
                    category = "asr",
                    event = "history_fail_archived",
                    data = mapOf(
                        "source" to source,
                        "status" to status.name,
                        "stage" to failStage.name,
                        "reason" to failReasonCode
                    )
                )
            } catch (_: Throwable) { }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive unsuccessful ASR history", e)
            false
        }
    }
}
