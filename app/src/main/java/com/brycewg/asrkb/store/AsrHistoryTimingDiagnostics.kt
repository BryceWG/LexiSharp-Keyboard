package com.brycewg.asrkb.store

import android.util.Log
import com.brycewg.asrkb.store.debug.DebugLogManager

/** Writes compact, text-free timing summaries to the persistent diagnostic log. */
internal object AsrHistoryTimingDiagnostics {
    fun logSaved(source: String, trace: AsrHistoryTimingTrace) {
        log(source, trace, event = "history_timing_saved")
    }

    fun logIncomplete(source: String, trace: AsrHistoryTimingTrace) {
        log(source, trace, event = "history_timing_incomplete")
    }

    private fun log(source: String, trace: AsrHistoryTimingTrace, event: String) {
        try {
            DebugLogManager.logBase(
                category = "asr",
                event = event,
                data = buildData(source, trace)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write history timing diagnostic", t)
        }
    }

    private fun buildData(source: String, trace: AsrHistoryTimingTrace): Map<String, Any> {
        val gapMs = (trace.totalElapsedMs - coveredMs(trace)).coerceAtLeast(0L)
        return LinkedHashMap<String, Any>().apply {
            put("source", source)
            put("origin", trace.origin.name)
            put("totalMs", trace.totalElapsedMs)
            put("gapMs", gapMs)
            put("completed", trace.completed)
            AsrHistoryTimingStage.values().forEach { stage ->
                put("${stage.name.lowercase()}Ms", trace.stageDurationMs(stage))
            }
        }
    }

    private fun coveredMs(trace: AsrHistoryTimingTrace): Long = trace.intervals
        .asSequence()
        .map { interval ->
            val start = interval.startOffsetMs.coerceIn(0L, trace.totalElapsedMs)
            start to interval.endOffsetMs.coerceIn(start, trace.totalElapsedMs)
        }
        .sortedBy { it.first }
        .fold(0L to 0L) { (coveredEnd, covered), (start, end) ->
            maxOf(coveredEnd, end) to (covered + (end - maxOf(coveredEnd, start)).coerceAtLeast(0L))
        }
        .second

    private const val TAG = "AsrHistoryTimingDiag"
}
