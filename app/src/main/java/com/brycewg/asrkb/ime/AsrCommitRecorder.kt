package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.TextSanitizer

internal class AsrCommitRecorder(
    private val context: Context,
    private val prefs: Prefs,
    private val asrManager: AsrSessionManager,
    private val logTag: String,
) {
    fun record(
        text: String,
        aiProcessed: Boolean,
        aiPostMs: Long = 0L,
        aiPostStatus: AsrHistoryStore.AiPostStatus = AsrHistoryStore.AiPostStatus.NONE,
    ) {
        try {
            val chars = TextSanitizer.countEffectiveChars(text)
            if (!prefs.disableUsageStats) {
                prefs.addAsrChars(chars)
            }
            try {
                val audioMs = asrManager.popLastAudioMsForStats()
                val totalElapsedMs = asrManager.popLastTotalElapsedMsForStats()
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
                    charCount = chars,
                )

                if (!prefs.disableUsageStats) {
                    prefs.recordUsageCommit("ime", vendorForRecord, audioMs, chars, procMs)
                }

                if (!prefs.disableAsrHistory) {
                    try {
                        val store = AsrHistoryStore(context)
                        store.add(
                            AsrHistoryStore.AsrHistoryRecord(
                                timestamp = System.currentTimeMillis(),
                                text = text,
                                vendorId = vendorForRecord.id,
                                audioMs = audioMs,
                                totalElapsedMs = totalElapsedMs,
                                procMs = procMs,
                                source = "ime",
                                aiProcessed = aiProcessed,
                                aiPostMs = aiPostMs,
                                aiPostStatus = aiPostStatus,
                                charCount = chars,
                            ),
                        )
                    } catch (e: Exception) {
                        Log.e(logTag, "Failed to add ASR history", e)
                    }
                }
            } catch (t: Throwable) {
                Log.e(logTag, "Failed to record usage stats", t)
            }
        } catch (t: Throwable) {
            Log.e(logTag, "Failed to record ASR commit", t)
        }
    }
}
