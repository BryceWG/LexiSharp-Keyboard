// RecordedAudioVoiceFilter PCM16LE 能量统计的 JVM 回归测试。
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedAudioVoiceFilterEnergyTest {
    @Test
    fun negativeNearZeroSamplesStayNearZero() {
        val pcm = pcm16le(-3, -2, -1, 0, 1, 2, 3)

        val energy = measureRecordedVoiceFilterEnergy(pcm, pcm.size)

        assertEquals(3, energy.maxAbs)
        assertEquals(28.0, energy.sumSquares, 0.0)
        assertEquals(0, energy.countAbove30)
        assertEquals(7, energy.sampleCount)
    }

    @Test
    fun signedPositiveAndNegativeSamplesHaveSymmetricEnergy() {
        val pcm = pcm16le(-1_200, -31, -30, 30, 31, 1_200)

        val energy = measureRecordedVoiceFilterEnergy(pcm, pcm.size)

        assertEquals(1_200, energy.maxAbs)
        assertEquals(2_883_722.0, energy.sumSquares, 0.0)
        assertEquals(4, energy.countAbove30)
        assertEquals(6, energy.sampleCount)
    }

    private fun pcm16le(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        var offset = 0
        samples.forEach { sample ->
            val clamped = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[offset] = (clamped and 0xFF).toByte()
            out[offset + 1] = ((clamped shr 8) and 0xFF).toByte()
            offset += 2
        }
        return out
    }
}
