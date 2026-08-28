// VAD 输入电平整形核心的 JVM 回归测试。
package com.brycewg.asrkb.asr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadInputLevelerTest {
    @Test
    fun lowAndHighRmsSpeechConvergeTowardSharedTarget() {
        val lowLeveler = VadInputLeveler()
        val highLeveler = VadInputLeveler()
        val lowSpeech = sinePcm(rms = 260.0)
        val highSpeech = sinePcm(rms = 5_200.0)

        val lowResult = repeatProcess(lowLeveler, lowSpeech, count = 24)
        val highResult = repeatProcess(highLeveler, highSpeech, count = 24)

        assertTrue(lowResult.stats.speechCandidate)
        assertTrue(highResult.stats.speechCandidate)
        assertInRange(lowResult.stats.leveledRms, 1_350.0, 2_150.0)
        assertInRange(highResult.stats.leveledRms, 1_350.0, 2_150.0)
        assertTrue(
            "leveled RMS should be closer than raw RMS",
            abs(lowResult.stats.leveledRms - highResult.stats.leveledRms) < 700.0
        )
    }

    @Test
    fun adaptiveSpeechEstimateTracksDeviceLevelInsteadOfOnlyCurrentFrame() {
        val lowLeveler = VadInputLeveler()
        val highLeveler = VadInputLeveler()
        val lowSpeech = sinePcm(rms = 260.0)
        val highSpeech = sinePcm(rms = 5_200.0)

        val lowResult = repeatProcess(lowLeveler, lowSpeech, count = 24)
        val highResult = repeatProcess(highLeveler, highSpeech, count = 24)

        assertInRange(lowResult.stats.estimatedSpeechRms, 220.0, 320.0)
        assertInRange(highResult.stats.estimatedSpeechRms, 4_800.0, 5_600.0)
        assertTrue(lowResult.stats.targetGain > 5.0)
        assertTrue(highResult.stats.targetGain < 0.5)
    }

    @Test
    fun moderateLowSpeechUsesRelativeTargetInsteadOfMaxGain() {
        val leveler = VadInputLeveler()
        val speech = sinePcm(rms = 120.0)

        val result = repeatProcess(leveler, speech, count = 24)

        assertTrue(result.stats.speechCandidate)
        assertInRange(result.stats.estimatedSpeechRms, 95.0, 145.0)
        assertInRange(result.stats.effectiveTargetSpeechRms, 950.0, 1_450.0)
        assertTrue("moderate speech should not pin target gain", result.stats.targetGain < 11.5)
        assertTrue("moderate speech should not pin adaptive gain", result.stats.adaptiveGain < 11.5)
        assertInRange(result.stats.leveledRms, 850.0, 1_450.0)
    }

    @Test
    fun verySoftVaryingSpeechKeepsMinimumTargetAndCanUseMaxGain() {
        val leveler = VadInputLeveler()
        val softSpeechChunks = listOf(
            38.0,
            64.0,
            79.0,
            62.0,
            43.0,
            60.0,
            58.0,
            47.0,
            39.0,
            51.0,
            38.0
        ).map { sinePcm(rms = it) }

        var result = leveler.processPcm16Le(softSpeechChunks.first())
        repeat(2) {
            softSpeechChunks.forEach { chunk ->
                result = leveler.processPcm16Le(chunk)
            }
        }

        assertTrue(result.stats.speechCandidate)
        assertInRange(result.stats.estimatedSpeechRms, 45.0, 70.0)
        assertEquals(720.0, result.stats.effectiveTargetSpeechRms, 0.001)
        assertEquals(12.0, result.stats.targetGain, 0.001)
        assertInRange(result.stats.leveledRms, 350.0, 750.0)
    }

    @Test
    fun coldStartSoftSpeechIsNotLearnedAsNoiseBeforeCandidateCanForm() {
        val leveler = VadInputLeveler()
        val chunks = listOf(
            47.0,
            62.0,
            61.0,
            44.0
        ).map { sinePcm(rms = it) }

        val results = chunks.map { leveler.processPcm16Le(it) }

        assertTrue(
            "soft speech should get a chance to enter speech candidate state during cold start",
            results.any { it.stats.speechCandidate }
        )
        assertTrue(
            "cold-start floor should not rise above the soft speech gate immediately",
            results.take(3).maxOf { it.stats.noiseFloorRms } < 26.0
        )
        assertTrue(results.last().stats.adaptiveGain > 1.0)
    }

    @Test
    fun silenceAndLowNoiseAreNotChasedUpward() {
        val leveler = VadInputLeveler()
        val noise = sinePcm(rms = 28.0)
        var last = leveler.processPcm16Le(noise)

        repeat(40) {
            last = leveler.processPcm16Le(noise)
            assertFalse(last.stats.speechCandidate)
        }

        assertTrue(last.stats.appliedGainEnd <= 1.05)
        assertTrue(last.stats.leveledRms < 45.0)
        assertTrue(last.stableAmplitude < 0.05f)
    }

    @Test
    fun sustainedMidLevelNoiseEscapesSpeechCandidateAndIsNotAmplified() {
        val leveler = VadInputLeveler()
        val steadyNoise = sinePcm(rms = 60.0)
        var last = leveler.processPcm16Le(steadyNoise)

        repeat(50) {
            last = leveler.processPcm16Le(steadyNoise)
        }

        assertFalse(last.stats.speechCandidate)
        assertTrue("noise floor should adapt above the initial floor", last.stats.noiseFloorRms > 24.0)
        assertTrue("steady noise should not stay pinned to high gain", last.stats.appliedGainEnd < 2.5)
        assertTrue("steady noise should not look like speech-level amplitude", last.stableAmplitude < 0.12f)
    }

    @Test
    fun fluctuatingLowSpeechIsNotLearnedAsSteadyNoiseFloor() {
        val leveler = VadInputLeveler()
        val softSyllable = sinePcm(rms = 58.0)
        val softDip = sinePcm(rms = 12.0)
        var syllableResult = leveler.processPcm16Le(softSyllable)

        repeat(50) { index ->
            val pcm = if (index % 2 == 0) softDip else softSyllable
            val result = leveler.processPcm16Le(pcm)
            if (index % 2 == 1) syllableResult = result
        }

        assertTrue(syllableResult.stats.speechCandidate)
        assertTrue("low speech floor should stay below the syllable gate", syllableResult.stats.noiseFloorRms < 24.0)
        assertTrue(syllableResult.stats.appliedGainEnd > 2.0)
    }

    @Test
    fun shortPauseKeepsDeviceSpeechEstimateForResumedSpeech() {
        val leveler = VadInputLeveler()
        val speech = sinePcm(rms = 320.0)
        val shortPause = ByteArray(CHUNK_SAMPLES * BYTES_PER_SAMPLE)

        val beforePause = repeatProcess(leveler, speech, count = 14)
        repeatProcess(leveler, shortPause, count = 3)
        val afterPause = repeatProcess(leveler, speech, count = 2)

        assertTrue(beforePause.stats.speechCandidate)
        assertTrue(afterPause.stats.speechCandidate)
        assertInRange(afterPause.stats.estimatedSpeechRms, 260.0, 380.0)
        assertTrue(
            "resumed speech should reuse the learned device gain",
            abs(afterPause.stats.targetGain - beforePause.stats.targetGain) < 0.8
        )
    }

    @Test
    fun zeroAndNearZeroInputStayNearZero() {
        val leveler = VadInputLeveler()
        val zero = ByteArray(CHUNK_SAMPLES * BYTES_PER_SAMPLE)
        val nearZero = constantPcm(value = 3)

        val zeroResult = leveler.processPcm16Le(zero)
        val nearZeroResult = leveler.processPcm16Le(nearZero)

        assertTrue(zeroResult.stats.nearZeroInput)
        assertTrue(nearZeroResult.stats.nearZeroInput)
        assertFalse(zeroResult.stats.speechCandidate)
        assertFalse(nearZeroResult.stats.speechCandidate)
        assertEquals(0.0, zeroResult.stats.leveledRms, 0.001)
        assertTrue(nearZeroResult.stats.leveledRms < 4.0)
        assertTrue(nearZeroResult.stableAmplitude < 0.01f)
    }

    @Test
    fun gainIsLimitedForVeryLowSpeech() {
        val config = VadInputLevelerConfig(maxGain = 6.0)
        val leveler = VadInputLeveler(config)
        val quietSpeech = sinePcm(rms = 85.0)

        val result = repeatProcess(leveler, quietSpeech, count = 30)

        assertTrue(result.stats.speechCandidate)
        assertEquals(config.maxGain, result.stats.targetGain, 0.001)
        assertTrue(result.stats.adaptiveGain <= config.maxGain + 0.001)
        assertTrue(result.stats.appliedGainEnd <= config.maxGain + 0.001)
    }

    @Test
    fun loudInputIsLimitedBeforePcmClips() {
        val config = VadInputLevelerConfig(maxGain = 12.0, limiterCeiling = 30_000)
        val leveler = VadInputLeveler(config)
        val quietSpeech = sinePcm(rms = 180.0)
        repeatProcess(leveler, quietSpeech, count = 12)

        val loudInput = constantPcm(value = 20_000)
        val result = leveler.processPcm16Le(loudInput)

        assertTrue(result.stats.limiterActive)
        assertTrue(result.stats.leveledMaxAbs <= config.limiterCeiling)
        assertFalse(result.leveledPcm.anyShortSample { abs(it) == Short.MAX_VALUE.toInt() })
        assertFalse(result.leveledPcm.anyShortSample { it == Short.MIN_VALUE.toInt() })
    }

    @Test
    fun gainMovesSmoothlyAcrossConsecutiveChunks() {
        val config = VadInputLevelerConfig(maxGainChangePerSecond = 4.0)
        val leveler = VadInputLeveler(config)
        val speech = sinePcm(rms = 220.0)

        val first = leveler.processPcm16Le(speech)
        val second = leveler.processPcm16Le(speech)
        val maxStep = config.maxGainChangePerSecond * CHUNK_DURATION_MS / 1_000.0 + 0.001

        assertTrue(first.stats.appliedGainEnd - first.stats.appliedGainStart <= maxStep)
        assertEquals(first.stats.appliedGainEnd, second.stats.appliedGainStart, 0.000_001)
        assertTrue(second.stats.appliedGainEnd - second.stats.appliedGainStart <= maxStep)
    }

    @Test
    fun stableAmplitudeFollowsLeveledSpeechAndFallsOnSilence() {
        val leveler = VadInputLeveler()
        val speech = sinePcm(rms = 700.0)
        val silence = ByteArray(CHUNK_SAMPLES * BYTES_PER_SAMPLE)

        val speechResult = repeatProcess(leveler, speech, count = 16)
        val silenceResult = repeatProcess(leveler, silence, count = 12)

        assertInRange(speechResult.stableAmplitude.toDouble(), 0.35, 0.85)
        assertTrue(silenceResult.stableAmplitude < speechResult.stableAmplitude)
        assertTrue(silenceResult.stableAmplitude < 0.15f)
    }

    @Test
    fun replayIsDeterministicForRecordedPcmBuffers() {
        val pcm = sinePcm(rms = 120.0) + sinePcm(rms = 2_600.0) + ByteArray(CHUNK_SAMPLES * BYTES_PER_SAMPLE)
        val first = VadInputLeveler().replayPcm16Le(pcm, chunkBytes = CHUNK_SAMPLES * BYTES_PER_SAMPLE)
        val second = VadInputLeveler().replayPcm16Le(pcm, chunkBytes = CHUNK_SAMPLES * BYTES_PER_SAMPLE)

        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) ->
            assertArrayEquals(a.leveledPcm, b.leveledPcm)
            assertEquals(a.stableAmplitude, b.stableAmplitude, 0.0f)
            assertEquals(a.stats.leveledRms, b.stats.leveledRms, 0.0)
            assertEquals(a.stats.noiseFloorRms, b.stats.noiseFloorRms, 0.0)
            assertEquals(a.stats.appliedGainEnd, b.stats.appliedGainEnd, 0.0)
        }
    }

    private fun repeatProcess(
        leveler: VadInputLeveler,
        pcm: ByteArray,
        count: Int
    ): VadInputLevelerResult {
        var result = leveler.processPcm16Le(pcm)
        repeat(count - 1) {
            result = leveler.processPcm16Le(pcm)
        }
        return result
    }

    private fun sinePcm(rms: Double): ByteArray {
        val amplitude = (rms * sqrt(2.0)).coerceAtMost(30_000.0)
        val out = ByteArray(CHUNK_SAMPLES * BYTES_PER_SAMPLE)
        var dst = 0
        for (i in 0 until CHUNK_SAMPLES) {
            val sample = (sin(2.0 * PI * i / 40.0) * amplitude).roundToInt()
            writeShortLe(out, dst, sample)
            dst += BYTES_PER_SAMPLE
        }
        return out
    }

    private fun constantPcm(value: Int): ByteArray {
        val out = ByteArray(CHUNK_SAMPLES * BYTES_PER_SAMPLE)
        var dst = 0
        repeat(CHUNK_SAMPLES) {
            writeShortLe(out, dst, value)
            dst += BYTES_PER_SAMPLE
        }
        return out
    }

    private fun writeShortLe(out: ByteArray, offset: Int, value: Int) {
        val clamped = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out[offset] = (clamped and 0xFF).toByte()
        out[offset + 1] = ((clamped shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.anyShortSample(predicate: (Int) -> Boolean): Boolean {
        var i = 0
        while (i + 1 < size) {
            val lo = this[i].toInt() and 0xFF
            val hi = this[i + 1].toInt() and 0xFF
            val s = (hi shl 8) or lo
            val sample = if (s < 0x8000) s else s - 0x10000
            if (predicate(sample)) return true
            i += BYTES_PER_SAMPLE
        }
        return false
    }

    private fun assertInRange(actual: Double, min: Double, max: Double) {
        assertTrue("expected $actual to be >= $min", actual >= min)
        assertTrue("expected $actual to be <= $max", actual <= max)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_DURATION_MS = 100
        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_MS / 1_000
    }
}
