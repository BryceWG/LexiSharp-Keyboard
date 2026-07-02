/**
 * 持续热采集音频规格测试。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.media.AudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousCaptureAudioSpecTest {
    @Test
    fun acceptsDefaultPcm16Mono16kSpec() {
        assertTrue(
            ContinuousCaptureAudioSpec.isCompatible(
                sampleRate = 16000,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT
            )
        )
    }

    @Test
    fun rejectsDifferentSampleRate() {
        assertFalse(
            ContinuousCaptureAudioSpec.isCompatible(
                sampleRate = 24000,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT
            )
        )
    }

    @Test
    fun rejectsDifferentChannelOrEncoding() {
        assertFalse(
            ContinuousCaptureAudioSpec.isCompatible(
                sampleRate = 16000,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT
            )
        )
        assertFalse(
            ContinuousCaptureAudioSpec.isCompatible(
                sampleRate = 16000,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_FLOAT
            )
        )
    }
}
