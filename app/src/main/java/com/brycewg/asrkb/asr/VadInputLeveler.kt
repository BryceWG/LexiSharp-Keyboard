/**
 * VAD 输入电平整形核心，供流式与离线音频路径复用。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class VadInputLevelerConfig(
    val sampleRate: Int = 16_000,
    val targetSpeechRms: Double = 1_800.0,
    val minimumAdaptiveTargetSpeechRms: Double = 720.0,
    val adaptiveTargetSpeechGain: Double = 10.0,
    val amplitudeReferenceRms: Double = 3_000.0,
    val initialNoiseFloorRms: Double = 20.0,
    val minimumNoiseFloorRms: Double = 12.0,
    val speechNoiseRatio: Double = 2.5,
    val speechExitNoiseRatio: Double = 1.75,
    val speechCandidateHoldMs: Double = 260.0,
    val absoluteSpeechRmsFloor: Double = 8.0,
    val maxGain: Double = 12.0,
    val minGain: Double = 0.25,
    val maxNonSpeechOutputGain: Double = 1.5,
    val gainAttackMs: Double = 250.0,
    val gainReleaseMs: Double = 100.0,
    val maxGainChangePerSecond: Double = 8.0,
    val noiseFloorRiseAlpha: Double = 0.03,
    val noiseFloorFallAlpha: Double = 0.10,
    val minimumStatsWindowMs: Double = 5_000.0,
    val minimumStatsNoiseFloorRiseMs: Double = 1_500.0,
    val minimumStatsMaxNoiseRms: Double = 80.0,
    val minimumStatsMaxToMinRatio: Double = 1.4,
    val speechStatsWindowMs: Double = 8_000.0,
    val minimumSpeechStatsMs: Double = 400.0,
    val speechEstimatePercentile: Double = 0.45,
    val speechEstimateAttackMs: Double = 600.0,
    val speechEstimateReleaseMs: Double = 1_200.0,
    val minimumEstimatedSpeechRms: Double = 24.0,
    val maximumEstimatedSpeechRms: Double = 8_000.0,
    val amplitudeAttackMs: Double = 60.0,
    val amplitudeReleaseMs: Double = 220.0,
    val limiterCeiling: Int = 32_000
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(targetSpeechRms > 0.0) { "targetSpeechRms must be positive" }
        require(minimumAdaptiveTargetSpeechRms > 0.0) {
            "minimumAdaptiveTargetSpeechRms must be positive"
        }
        require(minimumAdaptiveTargetSpeechRms <= targetSpeechRms) {
            "minimumAdaptiveTargetSpeechRms must be less than or equal to targetSpeechRms"
        }
        require(adaptiveTargetSpeechGain > 0.0) { "adaptiveTargetSpeechGain must be positive" }
        require(amplitudeReferenceRms > 0.0) { "amplitudeReferenceRms must be positive" }
        require(initialNoiseFloorRms > 0.0) { "initialNoiseFloorRms must be positive" }
        require(minimumNoiseFloorRms > 0.0) { "minimumNoiseFloorRms must be positive" }
        require(speechNoiseRatio > 1.0) { "speechNoiseRatio must be greater than 1" }
        require(speechExitNoiseRatio > 1.0) { "speechExitNoiseRatio must be greater than 1" }
        require(speechExitNoiseRatio <= speechNoiseRatio) {
            "speechExitNoiseRatio must be less than or equal to speechNoiseRatio"
        }
        require(speechCandidateHoldMs >= 0.0) { "speechCandidateHoldMs must be non-negative" }
        require(absoluteSpeechRmsFloor >= 0.0) { "absoluteSpeechRmsFloor must be non-negative" }
        require(maxGain >= 1.0) { "maxGain must be at least 1" }
        require(minGain > 0.0 && minGain <= 1.0) { "minGain must be in (0, 1]" }
        require(maxNonSpeechOutputGain >= 1.0) { "maxNonSpeechOutputGain must be at least 1" }
        require(gainAttackMs > 0.0) { "gainAttackMs must be positive" }
        require(gainReleaseMs > 0.0) { "gainReleaseMs must be positive" }
        require(maxGainChangePerSecond > 0.0) { "maxGainChangePerSecond must be positive" }
        require(noiseFloorRiseAlpha in 0.0..1.0) { "noiseFloorRiseAlpha must be in [0, 1]" }
        require(noiseFloorFallAlpha in 0.0..1.0) { "noiseFloorFallAlpha must be in [0, 1]" }
        require(minimumStatsWindowMs > 0.0) { "minimumStatsWindowMs must be positive" }
        require(minimumStatsNoiseFloorRiseMs > 0.0) {
            "minimumStatsNoiseFloorRiseMs must be positive"
        }
        require(minimumStatsMaxNoiseRms > 0.0) { "minimumStatsMaxNoiseRms must be positive" }
        require(minimumStatsMaxToMinRatio >= 1.0) {
            "minimumStatsMaxToMinRatio must be at least 1"
        }
        require(speechStatsWindowMs > 0.0) { "speechStatsWindowMs must be positive" }
        require(minimumSpeechStatsMs >= 0.0) { "minimumSpeechStatsMs must be non-negative" }
        require(speechEstimatePercentile in 0.0..1.0) {
            "speechEstimatePercentile must be in [0, 1]"
        }
        require(speechEstimateAttackMs > 0.0) { "speechEstimateAttackMs must be positive" }
        require(speechEstimateReleaseMs > 0.0) { "speechEstimateReleaseMs must be positive" }
        require(minimumEstimatedSpeechRms > 0.0) {
            "minimumEstimatedSpeechRms must be positive"
        }
        require(maximumEstimatedSpeechRms >= minimumEstimatedSpeechRms) {
            "maximumEstimatedSpeechRms must be at least minimumEstimatedSpeechRms"
        }
        require(amplitudeAttackMs > 0.0) { "amplitudeAttackMs must be positive" }
        require(amplitudeReleaseMs > 0.0) { "amplitudeReleaseMs must be positive" }
        require(limiterCeiling in 1..Short.MAX_VALUE.toInt()) {
            "limiterCeiling must be in 1..${Short.MAX_VALUE}"
        }
    }
}

data class VadInputLevelerStats(
    val rawRms: Double,
    val rawMaxAbs: Int,
    val leveledRms: Double,
    val leveledMaxAbs: Int,
    val noiseFloorRms: Double,
    val estimatedSpeechRms: Double,
    val speechStatsDurationMs: Double,
    val effectiveTargetSpeechRms: Double,
    val adaptiveGain: Double,
    val targetGain: Double,
    val outputGain: Double,
    val appliedGainStart: Double,
    val appliedGainEnd: Double,
    val speechCandidate: Boolean,
    val nearZeroInput: Boolean,
    val limiterActive: Boolean,
    val stableAmplitude: Float,
    val sampleCount: Int
)

data class VadInputLevelerResult(
    val leveledPcm: ByteArray,
    val stableAmplitude: Float,
    val stats: VadInputLevelerStats
)

class VadInputLeveler(
    private val config: VadInputLevelerConfig = VadInputLevelerConfig()
) {
    private var noiseFloorRms = config.initialNoiseFloorRms.coerceAtLeast(config.minimumNoiseFloorRms)
    private var estimatedSpeechRms = config.targetSpeechRms
    private var hasSpeechEstimate = false
    private var adaptiveGain = 1.0
    private var outputGain = 1.0
    private var stableAmplitude = 0.0
    private var speechCandidateActive = false
    private var speechCandidateHoldRemainingMs = 0.0
    private val minimumStatsWindow = ArrayDeque<RmsFrame>()
    private var minimumStatsDurationMs = 0.0
    private val speechStatsWindow = ArrayDeque<RmsFrame>()
    private var speechStatsDurationMs = 0.0

    fun reset() {
        noiseFloorRms = config.initialNoiseFloorRms.coerceAtLeast(config.minimumNoiseFloorRms)
        estimatedSpeechRms = config.targetSpeechRms
        hasSpeechEstimate = false
        adaptiveGain = 1.0
        outputGain = 1.0
        stableAmplitude = 0.0
        speechCandidateActive = false
        speechCandidateHoldRemainingMs = 0.0
        minimumStatsWindow.clear()
        minimumStatsDurationMs = 0.0
        speechStatsWindow.clear()
        speechStatsDurationMs = 0.0
    }

    fun processPcm16Le(
        pcm: ByteArray,
        offset: Int = 0,
        length: Int = pcm.size - offset
    ): VadInputLevelerResult {
        require(offset >= 0) { "offset must be non-negative" }
        require(length >= 0) { "length must be non-negative" }
        require(offset <= pcm.size && offset + length <= pcm.size) {
            "offset + length must stay within pcm"
        }

        val byteCount = length - (length % BYTES_PER_SAMPLE)
        if (byteCount == 0) return emptyResult()

        val stats = computeFrameStats16le(pcm, offset, byteCount)
        val rawRms = rms(stats.sumSquares.toDouble(), stats.sampleCount)
        val nearZeroInput = isLikelyBadSource(stats.maxAbs, rawRms, stats.countAboveThreshold)
        val durationMs = stats.sampleCount * 1_000.0 / config.sampleRate
        updateMinimumStats(rawRms, durationMs, nearZeroInput)

        val speechCandidate = updateSpeechCandidateState(rawRms, durationMs, nearZeroInput)
        if (speechCandidate) {
            updateSpeechStats(rawRms, durationMs)
            updateEstimatedSpeechRms(durationMs)
        }

        if (!nearZeroInput && !speechCandidate) {
            updateNoiseFloor(rawRms)
        }
        updateNoiseFloorFromMinimumStats(durationMs)

        val speechReferenceRms = speechReferenceRms(rawRms)
        val effectiveTargetSpeechRms = effectiveTargetSpeechRms(speechReferenceRms)
        val targetGain = if (speechCandidate) {
            (effectiveTargetSpeechRms / speechReferenceRms)
                .coerceIn(config.minGain, config.maxGain)
        } else {
            adaptiveGain
        }

        if (speechCandidate) {
            adaptiveGain = moveGainToward(adaptiveGain, targetGain, durationMs)
        }

        val outputTargetGain = if (speechCandidate) {
            adaptiveGain
        } else {
            min(adaptiveGain, config.maxNonSpeechOutputGain)
        }
        val appliedGainStart = outputGain
        val appliedGainEnd = moveGainToward(outputGain, outputTargetGain, durationMs)

        val leveled = applyGainRamp(
            pcm = pcm,
            offset = offset,
            sampleCount = stats.sampleCount,
            startGain = appliedGainStart,
            endGain = appliedGainEnd,
            rawMaxAbs = stats.maxAbs
        )
        outputGain = appliedGainEnd

        val leveledRms = rms(leveled.sumSquares, stats.sampleCount)
        val instantAmplitude = (leveledRms / config.amplitudeReferenceRms).coerceIn(0.0, 1.0)
        stableAmplitude = moveScalarToward(
            current = stableAmplitude,
            target = instantAmplitude,
            durationMs = durationMs,
            attackMs = config.amplitudeAttackMs,
            releaseMs = config.amplitudeReleaseMs
        )

        val resultStats = VadInputLevelerStats(
            rawRms = rawRms,
            rawMaxAbs = stats.maxAbs,
            leveledRms = leveledRms,
            leveledMaxAbs = leveled.maxAbs,
            noiseFloorRms = noiseFloorRms,
            estimatedSpeechRms = speechReferenceRms,
            speechStatsDurationMs = speechStatsDurationMs,
            effectiveTargetSpeechRms = effectiveTargetSpeechRms,
            adaptiveGain = adaptiveGain,
            targetGain = targetGain,
            outputGain = outputGain,
            appliedGainStart = appliedGainStart,
            appliedGainEnd = appliedGainEnd,
            speechCandidate = speechCandidate,
            nearZeroInput = nearZeroInput,
            limiterActive = leveled.limiterActive,
            stableAmplitude = stableAmplitude.toFloat(),
            sampleCount = stats.sampleCount
        )
        return VadInputLevelerResult(
            leveledPcm = leveled.pcm,
            stableAmplitude = stableAmplitude.toFloat(),
            stats = resultStats
        )
    }

    fun replayPcm16Le(pcm: ByteArray, chunkBytes: Int): List<VadInputLevelerResult> {
        require(chunkBytes >= BYTES_PER_SAMPLE) { "chunkBytes must contain at least one sample" }
        val results = ArrayList<VadInputLevelerResult>()
        var offset = 0
        while (offset < pcm.size) {
            val len = min(chunkBytes, pcm.size - offset)
            results += processPcm16Le(pcm, offset, len)
            offset += len
        }
        return results
    }

    private fun updateMinimumStats(rawRms: Double, durationMs: Double, nearZeroInput: Boolean) {
        if (!nearZeroInput) {
            minimumStatsWindow.addLast(RmsFrame(rawRms = rawRms, durationMs = durationMs))
            minimumStatsDurationMs += durationMs
        }
        while (minimumStatsDurationMs > config.minimumStatsWindowMs && minimumStatsWindow.isNotEmpty()) {
            val first = minimumStatsWindow.removeFirst()
            minimumStatsDurationMs -= first.durationMs
        }
        if (minimumStatsDurationMs < 0.0) minimumStatsDurationMs = 0.0
    }

    private fun updateNoiseFloorFromMinimumStats(durationMs: Double) {
        if (minimumStatsDurationMs < config.minimumStatsNoiseFloorRiseMs) return
        val recentMinimum = minimumStatsWindow.minOfOrNull { it.rawRms } ?: return
        val recentMaximum = minimumStatsWindow.maxOfOrNull { it.rawRms } ?: return
        if (recentMinimum > config.minimumStatsMaxNoiseRms) return
        if (recentMaximum > recentMinimum.coerceAtLeast(1.0) * config.minimumStatsMaxToMinRatio) return
        if (recentMinimum <= noiseFloorRms) return
        noiseFloorRms = moveScalarToward(
            current = noiseFloorRms,
            target = recentMinimum,
            durationMs = durationMs,
            attackMs = config.minimumStatsNoiseFloorRiseMs,
            releaseMs = config.minimumStatsNoiseFloorRiseMs
        ).coerceAtLeast(config.minimumNoiseFloorRms)
    }

    private fun updateSpeechCandidateState(
        rawRms: Double,
        durationMs: Double,
        nearZeroInput: Boolean
    ): Boolean {
        if (nearZeroInput || rawRms < config.absoluteSpeechRmsFloor) {
            speechCandidateActive = false
            speechCandidateHoldRemainingMs = 0.0
            return false
        }

        val enterThreshold = max(
            config.absoluteSpeechRmsFloor,
            noiseFloorRms * config.speechNoiseRatio
        )
        val exitThreshold = max(
            config.absoluteSpeechRmsFloor,
            noiseFloorRms * config.speechExitNoiseRatio
        )
        val candidate = if (speechCandidateActive) {
            rawRms >= exitThreshold
        } else {
            rawRms >= enterThreshold
        }

        if (candidate) {
            speechCandidateActive = true
            speechCandidateHoldRemainingMs = config.speechCandidateHoldMs
            return true
        }

        if (speechCandidateActive && speechCandidateHoldRemainingMs > 0.0) {
            speechCandidateHoldRemainingMs =
                (speechCandidateHoldRemainingMs - durationMs).coerceAtLeast(0.0)
            return true
        }

        speechCandidateActive = false
        return false
    }

    private fun updateSpeechStats(rawRms: Double, durationMs: Double) {
        speechStatsWindow.addLast(RmsFrame(rawRms = rawRms, durationMs = durationMs))
        speechStatsDurationMs += durationMs
        while (speechStatsDurationMs > config.speechStatsWindowMs && speechStatsWindow.isNotEmpty()) {
            val first = speechStatsWindow.removeFirst()
            speechStatsDurationMs -= first.durationMs
        }
        if (speechStatsDurationMs < 0.0) speechStatsDurationMs = 0.0
    }

    private fun updateEstimatedSpeechRms(durationMs: Double) {
        if (speechStatsDurationMs < config.minimumSpeechStatsMs) return
        val percentileRms = percentileRms(
            frames = speechStatsWindow,
            percentile = config.speechEstimatePercentile
        ).coerceIn(config.minimumEstimatedSpeechRms, config.maximumEstimatedSpeechRms)
        estimatedSpeechRms = if (hasSpeechEstimate) {
            moveScalarToward(
                current = estimatedSpeechRms,
                target = percentileRms,
                durationMs = durationMs,
                attackMs = config.speechEstimateAttackMs,
                releaseMs = config.speechEstimateReleaseMs
            )
        } else {
            percentileRms
        }
        hasSpeechEstimate = true
    }

    private fun speechReferenceRms(rawRms: Double): Double {
        val reference = if (hasSpeechEstimate) estimatedSpeechRms else rawRms
        return reference.coerceIn(config.minimumEstimatedSpeechRms, config.maximumEstimatedSpeechRms)
    }

    private fun effectiveTargetSpeechRms(speechReferenceRms: Double): Double = (speechReferenceRms * config.adaptiveTargetSpeechGain)
        .coerceIn(config.minimumAdaptiveTargetSpeechRms, config.targetSpeechRms)

    private fun updateNoiseFloor(rawRms: Double) {
        val alpha = if (rawRms > noiseFloorRms) {
            config.noiseFloorRiseAlpha
        } else {
            config.noiseFloorFallAlpha
        }
        noiseFloorRms += (rawRms - noiseFloorRms) * alpha
        if (noiseFloorRms < config.minimumNoiseFloorRms) {
            noiseFloorRms = config.minimumNoiseFloorRms
        }
    }

    private fun moveGainToward(current: Double, target: Double, durationMs: Double): Double {
        val timeConstant = if (target > current) config.gainAttackMs else config.gainReleaseMs
        val smoothed = moveScalarToward(current, target, durationMs, timeConstant, timeConstant)
        val maxStep = config.maxGainChangePerSecond * durationMs / 1_000.0
        return when {
            smoothed > current -> min(smoothed, current + maxStep)
            smoothed < current -> max(smoothed, current - maxStep)
            else -> current
        }.coerceIn(config.minGain, config.maxGain)
    }

    private fun moveScalarToward(
        current: Double,
        target: Double,
        durationMs: Double,
        attackMs: Double,
        releaseMs: Double
    ): Double {
        val timeConstant = if (target > current) attackMs else releaseMs
        val alpha = 1.0 - exp(-durationMs.coerceAtLeast(0.0) / timeConstant)
        return current + (target - current) * alpha
    }

    private fun applyGainRamp(
        pcm: ByteArray,
        offset: Int,
        sampleCount: Int,
        startGain: Double,
        endGain: Double,
        rawMaxAbs: Int
    ): LeveledFrame {
        val out = ByteArray(sampleCount * BYTES_PER_SAMPLE)
        val limiterGain = if (rawMaxAbs > 0) {
            config.limiterCeiling.toDouble() / rawMaxAbs
        } else {
            config.maxGain
        }

        var limiterActive = false
        var maxAbs = 0
        var sumSquares = 0.0
        var src = offset
        var dst = 0
        for (i in 0 until sampleCount) {
            val sample = readPcm16Le(pcm, src)
            val rampProgress = if (sampleCount <= 1) 1.0 else i.toDouble() / (sampleCount - 1)
            val rampGain = startGain + (endGain - startGain) * rampProgress
            val appliedGain = min(rampGain, limiterGain)
            if (appliedGain < rampGain) limiterActive = true
            val scaled = (sample * appliedGain).roundToInt()
                .coerceIn(-config.limiterCeiling, config.limiterCeiling)
            val absSample = abs(scaled)
            if (absSample > maxAbs) maxAbs = absSample
            sumSquares += scaled.toDouble() * scaled.toDouble()
            writePcm16Le(out, dst, scaled)
            src += BYTES_PER_SAMPLE
            dst += BYTES_PER_SAMPLE
        }

        return LeveledFrame(
            pcm = out,
            maxAbs = maxAbs,
            sumSquares = sumSquares,
            limiterActive = limiterActive
        )
    }

    private fun emptyResult(): VadInputLevelerResult {
        val stats = VadInputLevelerStats(
            rawRms = 0.0,
            rawMaxAbs = 0,
            leveledRms = 0.0,
            leveledMaxAbs = 0,
            noiseFloorRms = noiseFloorRms,
            estimatedSpeechRms = if (hasSpeechEstimate) estimatedSpeechRms else 0.0,
            speechStatsDurationMs = speechStatsDurationMs,
            effectiveTargetSpeechRms = if (hasSpeechEstimate) {
                effectiveTargetSpeechRms(estimatedSpeechRms)
            } else {
                config.minimumAdaptiveTargetSpeechRms
            },
            adaptiveGain = adaptiveGain,
            targetGain = adaptiveGain,
            outputGain = outputGain,
            appliedGainStart = outputGain,
            appliedGainEnd = outputGain,
            speechCandidate = false,
            nearZeroInput = true,
            limiterActive = false,
            stableAmplitude = stableAmplitude.toFloat(),
            sampleCount = 0
        )
        return VadInputLevelerResult(
            leveledPcm = ByteArray(0),
            stableAmplitude = stableAmplitude.toFloat(),
            stats = stats
        )
    }

    private data class InternalFrameStats(
        val maxAbs: Int,
        val sumSquares: Long,
        val countAboveThreshold: Int,
        val sampleCount: Int
    )

    private data class RmsFrame(
        val rawRms: Double,
        val durationMs: Double
    )

    private data class LeveledFrame(
        val pcm: ByteArray,
        val maxAbs: Int,
        val sumSquares: Double,
        val limiterActive: Boolean
    )

    private companion object {
        const val BYTES_PER_SAMPLE = 2

        fun computeFrameStats16le(buf: ByteArray, offset: Int, len: Int): InternalFrameStats {
            var i = offset
            val end = offset + len
            var maxAbs = 0
            var sumSquares = 0L
            var count = 0
            var samples = 0
            while (i + 1 < end) {
                val v = readPcm16Le(buf, i)
                val a = abs(v)
                if (a > maxAbs) maxAbs = a
                sumSquares += (v * v).toLong()
                if (a > 30) count++
                samples++
                i += BYTES_PER_SAMPLE
            }
            return InternalFrameStats(maxAbs, sumSquares, count, samples)
        }

        fun readPcm16Le(buf: ByteArray, offset: Int): Int {
            val lo = buf[offset].toInt() and 0xFF
            val hi = buf[offset + 1].toInt() and 0xFF
            val s = (hi shl 8) or lo
            return if (s < 0x8000) s else s - 0x10000
        }

        fun writePcm16Le(buf: ByteArray, offset: Int, sample: Int) {
            buf[offset] = (sample and 0xFF).toByte()
            buf[offset + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        fun rms(sumSquares: Double, sampleCount: Int): Double {
            if (sampleCount <= 0) return 0.0
            return sqrt(sumSquares / sampleCount)
        }

        fun percentileRms(frames: Collection<RmsFrame>, percentile: Double): Double {
            if (frames.isEmpty()) return 0.0
            val sorted = frames.map { it.rawRms }.sorted()
            val index = ((sorted.size - 1) * percentile).roundToInt()
                .coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }
}
