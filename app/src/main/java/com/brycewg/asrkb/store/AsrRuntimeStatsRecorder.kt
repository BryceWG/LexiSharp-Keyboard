/**
 * ASR 成功请求耗时统计的通道接入辅助。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.util.Log
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BackupAwareAsrEngine
import com.brycewg.asrkb.asr.StreamingAsrEngine

private const val TAG_ASR_RUNTIME_RECORDER = "AsrRuntimeRecorder"

internal fun Prefs.recordPrimaryAsrRuntimeRequestIfSuccessful(
    engine: StreamingAsrEngine?,
    fallbackPrimaryVendor: AsrVendor,
    audioMs: Long,
    requestMs: Long?
) {
    val durationMs = requestMs ?: return
    if (audioMs <= 0L || durationMs <= 0L) return
    val vendor = when (engine) {
        is BackupAwareAsrEngine -> {
            if (engine.wasLastResultFromBackup()) return
            engine.primaryVendor
        }
        else -> fallbackPrimaryVendor
    }
    try {
        recordAsrRuntimeRequest(
            vendor = vendor,
            audioMs = audioMs,
            requestMs = durationMs
        )
    } catch (t: Throwable) {
        Log.w(TAG_ASR_RUNTIME_RECORDER, "Failed to record ASR runtime request", t)
    }
}

internal fun Prefs.getAsrRuntimeStatsSnapshotOrNull(
    vendor: AsrVendor?,
    targetAudioMs: Long
): AsrRuntimeVendorSnapshot? {
    vendor ?: return null
    return try {
        getAsrRuntimeStatsSnapshot(
            vendor = vendor,
            targetAudioMs = targetAudioMs
        )
    } catch (t: Throwable) {
        Log.w(TAG_ASR_RUNTIME_RECORDER, "Failed to read ASR runtime stats snapshot", t)
        null
    }
}
