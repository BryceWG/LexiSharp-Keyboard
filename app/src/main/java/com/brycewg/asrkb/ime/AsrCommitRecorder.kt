package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.TextSanitizer

internal class AsrCommitRecorder(
    private val context: Context,
    private val prefs: Prefs,
    private val asrManager: AsrSessionManager,
    private val logTag: String
) {
    fun record(
        text: String,
        rawText: String = text,
        aiProcessed: Boolean,
        aiPostMs: Long = 0L,
        aiPostStatus: AsrHistoryStore.AiPostStatus = AsrHistoryStore.AiPostStatus.NONE,
        llmVendorId: String? = null,
        historyTiming: AsrSessionManager.HistoryCommitContext? = null
    ) {
        val historyRecordId = historyTiming?.recordId ?: asrManager.consumeHistoryCommitContext(null)
        var historyStore: AsrHistoryStore? = null
        var historyWritten = false
        try {
            val chars = TextSanitizer.countEffectiveChars(text)
            if (!prefs.disableUsageStats) {
                prefs.addAsrChars(chars)
            }
            try {
                val audioMs = asrManager.popLastAudioMsForStats()
                val legacyTotalElapsedMs = asrManager.popLastTotalElapsedMsForStats()
                val totalElapsedMs = legacyTotalElapsedMs
                val procMs = asrManager.getLastRequestDuration() ?: 0L
                val vendorForRecord = try {
                    asrManager.peekLastFinalVendorForStats()
                } catch (t: Throwable) {
                    Log.w(logTag, "Failed to get final vendor for stats", t)
                    prefs.asrVendor
                }

                AnalyticsManager.recordAsrEvent(
                    context = context,
                    vendorId = vendorForRecord.id,
                    audioMs = audioMs,
                    procMs = procMs,
                    source = "ime",
                    aiProcessed = aiProcessed,
                    charCount = chars
                )

                if (!prefs.disableUsageStats) {
                    prefs.recordUsageCommit("ime", vendorForRecord, audioMs, chars, procMs)
                }

                if (!prefs.disableAsrHistory) {
                    try {
                        val store = AsrHistoryStore(context)
                        historyStore = store
                        store.add(
                            AsrHistoryStore.AsrHistoryRecord(
                                id = historyRecordId,
                                timestamp = System.currentTimeMillis(),
                                text = text,
                                rawText = rawText,
                                vendorId = vendorForRecord.id,
                                audioMs = audioMs,
                                totalElapsedMs = totalElapsedMs,
                                procMs = procMs,
                                source = "ime",
                                aiProcessed = aiProcessed,
                                aiPostMs = aiPostMs,
                                aiPostStatus = aiPostStatus,
                                llmVendorId = llmVendorId,
                                charCount = chars
                            )
                        )
                        historyWritten = true
                        AsrHistoryAudioStore.pruneAsync(
                            context,
                            store.listAll(),
                            prefs.audioHistoryRetentionCount
                        )
                    } catch (e: Exception) {
                        Log.e(logTag, "Failed to add ASR history", e)
                    }
                } else {
                    AsrHistoryAudioStore(context).delete(historyRecordId)
                }
            } catch (t: Throwable) {
                Log.e(logTag, "Failed to record usage stats", t)
            }
        } catch (t: Throwable) {
            Log.e(logTag, "Failed to record ASR commit", t)
        } finally {
            historyTiming?.timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.TEXT_DELIVERY)
            val timingTrace = historyTiming?.timing?.complete()
            if (historyWritten && timingTrace != null) {
                try {
                    historyStore?.updateById(historyRecordId) { record ->
                        record.copy(
                            totalElapsedMs = timingTrace.totalElapsedMs,
                            timingTrace = timingTrace
                        )
                    }
                    com.brycewg.asrkb.store.AsrHistoryTimingDiagnostics.logSaved(
                        "ime",
                        timingTrace
                    )
                } catch (t: Throwable) {
                    Log.w(logTag, "Failed to finalize ASR history timing", t)
                }
            }
            asrManager.consumeHistoryCommitContext(historyTiming)
        }
    }
}
