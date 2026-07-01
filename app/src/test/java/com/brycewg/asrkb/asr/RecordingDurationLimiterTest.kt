// 录音最长时长限制器的 JVM 回归测试。
package com.brycewg.asrkb.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingDurationLimiterTest {
    @Test
    fun disabledLimiterNeverTriggers() {
        val limiter = RecordingDurationLimiter(
            enabled = false,
            maxDurationMs = 1_000,
            sampleRate = 16_000
        )

        assertFalse(limiter.acceptPcm(16_000 * 2))
        assertFalse(limiter.acceptPcm(16_000 * 2))
    }

    @Test
    fun limiterTriggersOnceWhenDurationReached() {
        val limiter = RecordingDurationLimiter(
            enabled = true,
            maxDurationMs = 30_000,
            sampleRate = 16_000
        )

        assertFalse(limiter.acceptPcm(16_000 * 2 * 15))
        assertTrue(limiter.acceptPcm(16_000 * 2 * 15))
        assertFalse(limiter.acceptPcm(16_000 * 2 * 15))
    }

    @Test
    fun clampMaxDurationToSupportedRange() {
        assertEquals(30_000, RecordingDurationLimiter.clampMaxDurationMs(1_000))
        assertEquals(120_000, RecordingDurationLimiter.clampMaxDurationMs(120_000))
        assertEquals(600_000, RecordingDurationLimiter.clampMaxDurationMs(999_000))
    }
}
