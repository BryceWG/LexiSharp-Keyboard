/**
 * VAD/波形分支的会话级电平整形封装。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.round

internal class VadInputLevelerBranch(private val sampleRate: Int = 16_000) {
    private val lock = Any()
    private val leveler = VadInputLeveler(
        VadInputLevelerConfig(sampleRate = sampleRate)
    )
    private var elapsedAudioMs = 0.0
    private var debugSessionSeq = nextDebugSessionSeq()
    private var debugSummaryFinished = false
    private var frameCount = 0
    private var speechFrameCount = 0
    private var nearZeroFrameCount = 0
    private var limiterFrameCount = 0
    private var speechCandidateAudioMs = 0.0
    private var firstSpeechCandidateAtMs: Double? = null
    private var lastSpeechCandidateAtMs: Double? = null
    private var speechRawRmsWeightedSum = 0.0
    private var speechLeveledRmsWeightedSum = 0.0
    private var peakRawRms = 0.0
    private var peakLeveledRms = 0.0
    private var peakStableAmplitude = 0.0f
    private var peakOutputGain = 1.0
    private var finalStats: VadInputLevelerStats? = null
    private var lastStatsLogAudioMs = -STATS_LOG_INTERVAL_MS
    private var lastSpeechCandidate: Boolean? = null
    private var lastNearZeroInput: Boolean? = null

    fun process(pcm: ByteArray): VadInputLevelerResult = synchronized(lock) {
        leveler.processPcm16Le(pcm).also { result ->
            recordStats(result.stats)
        }
    }

    fun finishDebugSession(reason: String = "finish") {
        synchronized(lock) {
            flushDebugSummary(reason)
        }
    }

    fun reset() {
        synchronized(lock) {
            flushDebugSummary("reset")
            leveler.reset()
            resetDebugSessionState()
        }
    }

    private fun recordStats(stats: VadInputLevelerStats) {
        val frameAudioMs = if (stats.sampleCount > 0) {
            stats.sampleCount * 1_000.0 / sampleRate
        } else {
            0.0
        }
        elapsedAudioMs += frameAudioMs
        updateDebugSummary(stats, frameAudioMs)
        if (!DebugLogManager.isRecording()) return

        val speechChanged = lastSpeechCandidate != stats.speechCandidate
        val nearZeroChanged = lastNearZeroInput != stats.nearZeroInput
        val due = elapsedAudioMs - lastStatsLogAudioMs >= STATS_LOG_INTERVAL_MS
        if (!due && !speechChanged && !nearZeroChanged) return

        lastStatsLogAudioMs = elapsedAudioMs
        lastSpeechCandidate = stats.speechCandidate
        lastNearZeroInput = stats.nearZeroInput
        DebugLogManager.log(
            category = "asr",
            event = "vad_leveler_stats",
            data = mapOf(
                "audioMs" to elapsedAudioMs.roundToDigits(0),
                "sampleRate" to sampleRate,
                "rawRms" to stats.rawRms.roundToDigits(1),
                "rawMaxAbs" to stats.rawMaxAbs,
                "leveledRms" to stats.leveledRms.roundToDigits(1),
                "leveledMaxAbs" to stats.leveledMaxAbs,
                "noiseFloorRms" to stats.noiseFloorRms.roundToDigits(1),
                "estimatedSpeechRms" to stats.estimatedSpeechRms.roundToDigits(1),
                "speechStatsMs" to stats.speechStatsDurationMs.roundToDigits(0),
                "effectiveTargetSpeechRms" to stats.effectiveTargetSpeechRms.roundToDigits(1),
                "targetGain" to stats.targetGain.roundToDigits(2),
                "adaptiveGain" to stats.adaptiveGain.roundToDigits(2),
                "outputGain" to stats.outputGain.roundToDigits(2),
                "speechCandidate" to stats.speechCandidate,
                "nearZeroInput" to stats.nearZeroInput,
                "limiterActive" to stats.limiterActive,
                "stableAmplitude" to stats.stableAmplitude.roundToDigits(3)
            )
        )
    }

    private fun updateDebugSummary(stats: VadInputLevelerStats, frameAudioMs: Double) {
        if (stats.sampleCount <= 0) return
        debugSummaryFinished = false
        frameCount += 1
        if (stats.nearZeroInput) nearZeroFrameCount += 1
        if (stats.limiterActive) limiterFrameCount += 1
        peakRawRms = maxOf(peakRawRms, stats.rawRms)
        peakLeveledRms = maxOf(peakLeveledRms, stats.leveledRms)
        peakStableAmplitude = maxOf(peakStableAmplitude, stats.stableAmplitude)
        peakOutputGain = maxOf(peakOutputGain, stats.outputGain)
        if (stats.speechCandidate) {
            speechFrameCount += 1
            speechCandidateAudioMs += frameAudioMs
            if (firstSpeechCandidateAtMs == null) {
                firstSpeechCandidateAtMs = (elapsedAudioMs - frameAudioMs).coerceAtLeast(0.0)
            }
            lastSpeechCandidateAtMs = elapsedAudioMs
            speechRawRmsWeightedSum += stats.rawRms * frameAudioMs
            speechLeveledRmsWeightedSum += stats.leveledRms * frameAudioMs
        }
        finalStats = stats
    }

    private fun flushDebugSummary(reason: String) {
        if (debugSummaryFinished || frameCount <= 0) return
        debugSummaryFinished = true
        if (!DebugLogManager.isRecording()) return

        val stats = finalStats ?: return
        val speechCoverage = if (elapsedAudioMs > 0.0) {
            speechCandidateAudioMs / elapsedAudioMs
        } else {
            0.0
        }
        val speechRawRmsAvg = if (speechCandidateAudioMs > 0.0) {
            speechRawRmsWeightedSum / speechCandidateAudioMs
        } else {
            0.0
        }
        val speechLeveledRmsAvg = if (speechCandidateAudioMs > 0.0) {
            speechLeveledRmsWeightedSum / speechCandidateAudioMs
        } else {
            0.0
        }
        DebugLogManager.log(
            category = "asr",
            event = "vad_leveler_summary",
            data = mapOf(
                "levelerSessionSeq" to debugSessionSeq,
                "reason" to reason,
                "audioMs" to elapsedAudioMs.roundToDigits(0),
                "sampleRate" to sampleRate,
                "frameCount" to frameCount,
                "speechFrameCount" to speechFrameCount,
                "nearZeroFrameCount" to nearZeroFrameCount,
                "limiterFrameCount" to limiterFrameCount,
                "firstSpeechCandidateAtMs" to firstSpeechCandidateAtMs?.roundToDigits(0),
                "lastSpeechCandidateAtMs" to lastSpeechCandidateAtMs?.roundToDigits(0),
                "speechCandidateMs" to speechCandidateAudioMs.roundToDigits(0),
                "speechCoverage" to speechCoverage.roundToDigits(3),
                "speechRawRmsAvg" to speechRawRmsAvg.roundToDigits(1),
                "speechLeveledRmsAvg" to speechLeveledRmsAvg.roundToDigits(1),
                "peakRawRms" to peakRawRms.roundToDigits(1),
                "peakLeveledRms" to peakLeveledRms.roundToDigits(1),
                "peakStableAmplitude" to peakStableAmplitude.roundToDigits(3),
                "peakOutputGain" to peakOutputGain.roundToDigits(2),
                "finalNoiseFloorRms" to stats.noiseFloorRms.roundToDigits(1),
                "finalEstimatedSpeechRms" to stats.estimatedSpeechRms.roundToDigits(1),
                "finalEffectiveTargetSpeechRms" to stats.effectiveTargetSpeechRms.roundToDigits(1),
                "finalTargetGain" to stats.targetGain.roundToDigits(2),
                "finalAdaptiveGain" to stats.adaptiveGain.roundToDigits(2),
                "finalOutputGain" to stats.outputGain.roundToDigits(2)
            )
        )
    }

    private fun resetDebugSessionState() {
        elapsedAudioMs = 0.0
        debugSessionSeq = nextDebugSessionSeq()
        debugSummaryFinished = false
        frameCount = 0
        speechFrameCount = 0
        nearZeroFrameCount = 0
        limiterFrameCount = 0
        speechCandidateAudioMs = 0.0
        firstSpeechCandidateAtMs = null
        lastSpeechCandidateAtMs = null
        speechRawRmsWeightedSum = 0.0
        speechLeveledRmsWeightedSum = 0.0
        peakRawRms = 0.0
        peakLeveledRms = 0.0
        peakStableAmplitude = 0.0f
        peakOutputGain = 1.0
        finalStats = null
        lastStatsLogAudioMs = -STATS_LOG_INTERVAL_MS
        lastSpeechCandidate = null
        lastNearZeroInput = null
    }

    private fun Double.roundToDigits(digits: Int): Double {
        val scale = POW10[digits.coerceIn(0, POW10.lastIndex)]
        return round(this * scale) / scale
    }

    private fun Float.roundToDigits(digits: Int): Double = toDouble().roundToDigits(digits)

    private companion object {
        const val STATS_LOG_INTERVAL_MS = 500.0
        val POW10 = doubleArrayOf(1.0, 10.0, 100.0, 1_000.0)
        val SESSION_SEQ = AtomicInteger()

        fun nextDebugSessionSeq(): Int = SESSION_SEQ.incrementAndGet()
    }
}
