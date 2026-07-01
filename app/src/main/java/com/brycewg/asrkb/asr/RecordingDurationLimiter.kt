/**
 * 录音最长时长兜底限制器。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.Prefs

internal class RecordingDurationLimiter(
    private val enabled: Boolean,
    maxDurationMs: Int,
    private val sampleRate: Int,
    private val bytesPerSample: Int = PCM_16_BYTES_PER_SAMPLE
) {
    private val maxDurationMs = clampMaxDurationMs(maxDurationMs)
    private var recordedSamples: Long = 0L
    private var triggered = false

    fun acceptPcm(byteCount: Int): Boolean {
        if (!enabled || triggered || byteCount <= 0 || sampleRate <= 0 || bytesPerSample <= 0) {
            return false
        }
        recordedSamples += (byteCount / bytesPerSample).coerceAtLeast(0)
        val recordedMs = recordedSamples * 1_000L / sampleRate
        if (recordedMs < maxDurationMs) return false
        triggered = true
        return true
    }

    companion object {
        private const val PCM_16_BYTES_PER_SAMPLE = 2

        fun fromPrefs(
            prefs: Prefs,
            sampleRate: Int,
            bytesPerSample: Int = PCM_16_BYTES_PER_SAMPLE
        ): RecordingDurationLimiter = RecordingDurationLimiter(
            enabled = prefs.recordingAutoStopMode == Prefs.RecordingAutoStopMode.MAX_DURATION,
            maxDurationMs = prefs.recordingMaxDurationMs,
            sampleRate = sampleRate,
            bytesPerSample = bytesPerSample
        )

        fun clampMaxDurationMs(value: Int): Int = value.coerceIn(
            Prefs.RECORDING_MAX_DURATION_MIN_MS,
            Prefs.RECORDING_MAX_DURATION_MAX_MS
        )
    }
}
